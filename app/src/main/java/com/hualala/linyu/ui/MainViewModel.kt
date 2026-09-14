package com.hualala.linyu.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.closeOrderSafe
import com.hualala.linyu.api.closeOrderResultSafe
import com.hualala.linyu.api.downRateSafe
import com.hualala.linyu.api.downRateResultSafe
import com.hualala.linyu.api.getBillListSafe
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.api.getUseCodeSafe
import com.hualala.linyu.api.getWalletSafe
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.MqttOrderMsg
import com.hualala.linyu.model.NearbyDevice
import com.hualala.linyu.model.WalletData
import com.hualala.linyu.utils.BluetoothScanner
import com.hualala.linyu.utils.MqttManager
import com.hualala.linyu.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainViewModel : ViewModel() {

    var walletInfo by mutableStateOf<WalletData?>(null)
    val nearbyDevices = mutableStateListOf<NearbyDevice>()
    var isScanning by mutableStateOf(false)
    var scanStartTime by mutableStateOf(0L)

    var isShowering by mutableStateOf(false)
    var isStartingShower by mutableStateOf(false)
    var isStopping by mutableStateOf(false)
    var showerRemaining by mutableStateOf("0.00")
    var showerConsumed by mutableStateOf(0.0)
    var showerPreDeduct by mutableStateOf(0.0)
    var showerElapsedSec by mutableStateOf(0)
    var autoDisConSec by mutableStateOf(0)   // 自动关停剩余秒数，0 = 未知
    var showerError by mutableStateOf<String?>(null)
    var toastMessage by mutableStateOf<String?>(null)

    // ── 自动关停确认弹窗 ──
    var showAutoCloseDialog by mutableStateOf(false)
    var autoCloseDeviceName by mutableStateOf("")
    var autoCloseElapsed by mutableStateOf(0)
    var autoCloseConsumed by mutableStateOf(0.0)
    var autoCloseLoading by mutableStateOf(false)

    var kickedOut by mutableStateOf(false)

    var lastDeviceName by mutableStateOf("")
    var lastDeviceMac by mutableStateOf("")
    var lastDeviceSnCode by mutableStateOf("")
    var lastDeviceEmoji by mutableStateOf("🚿")

    var selectedDevice by mutableStateOf<DeviceInfo?>(null)
    var showDeviceDetail by mutableStateOf(false)
    var isOwner by mutableStateOf(true)

    // ── 多设备活跃订单 ──
    val activeOrders = mutableStateListOf<ActiveOrder>()

    // 用于当前洗澡的设备 snCode
    private var showerSnCode: String? = null

    private var currentOrderNo: String? = null
    private var activeDeviceSnCodes = mutableSetOf<String>()
    private val fetchingMacs = mutableSetOf<String>()
    private val gson = Gson()
    private var scanner: BluetoothScanner? = null
    private var mqttManager: MqttManager? = null
    private var timerJob: Job? = null
    private var orderPollJob: Job? = null

    // ── 账单 ──
    var billList by mutableStateOf<List<BillItem>>(emptyList())
    var isLoadingBills by mutableStateOf(false)
    var useCodeData by mutableStateOf<UseCodeData?>(null)

    fun initManagers(context: Context) {
        if (scanner == null) scanner = BluetoothScanner(context, { addDevice(it) }, { onScanTimeout() })
        if (mqttManager == null) mqttManager = MqttManager(context, { handleMqttMessage(it) })

        lastDeviceName = PrefsHelper.lastDeviceName
        lastDeviceMac = PrefsHelper.lastDeviceMac
        lastDeviceSnCode = PrefsHelper.lastDeviceSnCode
        lastDeviceEmoji = PrefsHelper.lastDeviceEmoji

        // 恢复所有活跃订单
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear()
        activeOrders.addAll(saved)
        saved.forEach { activeDeviceSnCodes.add(it.snCode) }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel(); orderPollJob?.cancel()
        mqttManager?.disconnect(); scanner?.stopScan()
    }

    fun startScan() {
        nearbyDevices.clear(); activeDeviceSnCodes.clear()
        isScanning = true; scanStartTime = System.currentTimeMillis(); scanner?.startScan()
    }
    fun onScanTimeout() {
        val e = System.currentTimeMillis() - scanStartTime
        if (e < 600) viewModelScope.launch { delay(600 - e); isScanning = false }
        else isScanning = false
    }

    // ── 扫码绑定 ──
    fun scanBind(snCode: String) {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(snCode)
                if (resp.success && resp.data != null) {
                    val info = resp.data
                    // 保存为上次使用设备
                    PrefsHelper.lastDeviceSnCode = info.snCode
                    PrefsHelper.lastDeviceMac = info.macAddress
                    PrefsHelper.lastDeviceName = info.displayName
                    PrefsHelper.lastDeviceEmoji = info.typeEmoji
                    // 弹出设备详情
                    selectedDevice = info; showDeviceDetail = true
                    refreshDeviceStatus(info.snCode)
                    // 停止扫描（避免设备详情弹出后列表还在跳）
                    scanner?.stopScan(); isScanning = false
                } else {
                    toastMessage = resp.displayMessage ?: "未找到该设备"
                    checkKick(resp.displayMessage)
                }
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                toastMessage = if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    "网络连接失败，请检查网络设置"
                } else {
                    "查询设备失败"
                }
            }
        }
    }

    // ── 点击设备 ──
    fun fetchDeviceInfo(mac: String) {
        val cached = nearbyDevices.find { it.mac == mac }?.deviceInfo
        if (cached != null) {
            selectedDevice = cached; showDeviceDetail = true
            viewModelScope.launch { refreshDeviceStatus(cached.snCode) }
            return
        }
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; showDeviceDetail = true
                    refreshDeviceStatus(resp.data.snCode)
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    private suspend fun refreshDeviceStatus(snCode: String) {
        try {
            val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
            if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                activeDeviceSnCodes.add(snCode)
                isOwner = q.data?.isOwner ?: true
                // 只有自己的订单才加入 activeOrders
                if (isOwner && activeOrders.none { it.snCode == snCode }) {
                    val orderNo = q.data?.orderNo ?: ""
                    val deviceInfo = nearbyDevices.find { it.deviceInfo?.snCode == snCode }?.deviceInfo
                    if (deviceInfo != null) {
                        activeOrders.add(ActiveOrder(snCode, orderNo, deviceInfo.displayName, deviceInfo.macAddress, deviceInfo.typeEmoji, deviceInfo.withholdMoney))
                        saveOrders()
                    }
                }
            } else isOwner = true
        } catch (_: Exception) {}
    }

    fun startLastDevice(phone: String) {
        val sn = lastDeviceSnCode.ifEmpty { return }
        // 找对应的活跃订单
        val order = activeOrders.find { it.snCode == sn }
        if (order != null) {
            viewModelScope.launch {
                try {
                    val resp = NetworkModule.apiService.getDeviceInfoSafe(order.deviceMac)
                    if (resp.success && resp.data != null) {
                        selectedDevice = resp.data; startShower(phone)
                    } else {
                        toastMessage = "获取设备信息失败"
                    }
                } catch (e: Exception) {
                    toastMessage = "获取设备信息失败"
                }
            }
            return
        }
        val mac = lastDeviceMac.ifEmpty { return }
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; startShower(phone)
                } else {
                    toastMessage = "获取设备信息失败"
                }
            } catch (e: Exception) {
                toastMessage = "获取设备信息失败"
            }
        }
    }

    // ════════════════════════════════════════════
    fun startShower(phone: String) {
        val device = selectedDevice ?: return
        val snCode = device.snCode
        if (snCode.isNullOrBlank()) { showerError = "设备信息不完整"; return }

        isStartingShower = true
        viewModelScope.launch {
            try {
                showerError = null
                showerSnCode = snCode

                val existing = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
                if (existing.errorCode == 307 || (existing.success && existing.data?.orderNo != null)) {
                    val oNo = existing.data?.orderNo ?: ""
                    if (activeOrders.none { it.snCode == snCode }) {
                        activeOrders.add(ActiveOrder(snCode, oNo, device.displayName, device.macAddress, device.typeEmoji, device.withholdMoney))
                        saveOrders()
                    }
                    // 恢复订单：从持久化恢复自动关停剩余时间
                    val remain = PrefsHelper.getAutoDisconRemain(snCode)
                    enterShowerState(oNo, snCode, device, remain)
                    return@launch
                }

                mqttManager?.connect(phone)
                val resp = NetworkModule.apiService.downRateSafe(snCode = snCode, auth = NetworkModule.authFields())
                if (!resp.success) {
                    showerError = resp.displayMessage ?: "开始失败"
                    checkKick(resp.displayMessage); mqttManager?.disconnect(); return@launch
                }

                // 开阀确认：轮询 downRateResult，确认开阀成功
                var opened = false
                var autoDiscon = resp.data?.autoDisConTime ?: 0
                for (i in 0..8) {
                    delay(700)
                    try {
                        val r = NetworkModule.apiService.downRateResultSafe(snCode = snCode, auth = NetworkModule.authFields())
                        val d = r.data
                        if (r.success && (d?.state == 0 || d?.result == 0 || d?.orderNo != null)) {
                            opened = true
                            val autoTime = d?.autoDisConTime
                            if (autoTime != null && autoTime > 0) {
                                autoDiscon = autoTime
                            }
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (!opened) {
                    // 开阀确认失败，退出并提示
                    showerError = "开阀未确认成功，请确认热水器是否已开启"
                    mqttManager?.disconnect()
                    return@launch
                }

                // 记录开阀时间戳（供消费金额过滤）
                if (PrefsHelper.getStartedAt(snCode) <= 0L) {
                    PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
                }

                activeOrders.add(ActiveOrder(snCode, "", device.displayName, device.macAddress, device.typeEmoji, device.withholdMoney))
                saveOrders()
                enterShowerState(null, snCode, device, autoDiscon)

                orderPollJob?.cancel()
                orderPollJob = viewModelScope.launch {
                    for (i in 0..10) {
                        delay(800)
                        if (currentOrderNo != null) break
                        try {
                            val p = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
                            if (p.errorCode == 307 || (p.success && p.data?.orderNo != null)) {
                                currentOrderNo = p.data?.orderNo
                                updateOrderNo(snCode, currentOrderNo ?: "")
                            } else checkKick(p.displayMessage)
                        } catch (e: Exception) { checkKickEx(e) }
                    }
                }
            } catch (e: Exception) { checkKickEx(e); if (!isShowering) showerError = e.message ?: "网络错误" }
            finally { isStartingShower = false }
        }
    }

    private fun updateOrderNo(snCode: String, orderNo: String) {
        val i = activeOrders.indexOfFirst { it.snCode == snCode }
        if (i >= 0 && orderNo.isNotEmpty()) {
            activeOrders[i] = activeOrders[i].copy(orderNo = orderNo)
            saveOrders()
        }
    }

    private fun enterShowerState(orderNo: String?, snCode: String, device: DeviceInfo, autoDiscon: Int = 0) {
        currentOrderNo = orderNo; isShowering = true
        showerPreDeduct = device.withholdMoney; showerConsumed = 0.0
        showerRemaining = "%.2f".format(device.withholdMoney)
        activeDeviceSnCodes.add(snCode)

        // 自动关停倒计时：仅在已知且尚未开始时初始化
        if (autoDiscon > 0 && PrefsHelper.getAutoDisconRemain(snCode) <= 0) {
            autoDisConSec = autoDiscon
            PrefsHelper.setAutoDisconRemain(snCode, autoDiscon)
        }

        val st = PrefsHelper.getStartedAt(snCode)
        showerElapsedSec = if (st > 0) ((System.currentTimeMillis() - st) / 1000).toInt() else {
            PrefsHelper.setStartedAt(snCode, System.currentTimeMillis()); 0
        }

        lastDeviceName = device.displayName; lastDeviceMac = device.macAddress; lastDeviceSnCode = snCode; lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceName = device.displayName; PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = snCode; PrefsHelper.lastDeviceEmoji = device.typeEmoji

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var tick = 0
            while (isShowering) {
                delay(1000); tick++
                val st = PrefsHelper.getStartedAt(snCode)
                if (st > 0) showerElapsedSec = ((System.currentTimeMillis() - st) / 1000).toInt()

                // 自动关停倒计时递减
                val remain = PrefsHelper.getAutoDisconRemain(snCode)
                if (remain > 0) {
                    val newRemain = remain - 1
                    PrefsHelper.setAutoDisconRemain(snCode, newRemain)
                    autoDisConSec = newRemain
                    if (newRemain <= 0) {
                        // 时间到，触发自动关停 → 弹确认框
                        onAutoClose(snCode)
                        return@launch
                    }
                }

                // 每 15 秒检查一次订单状态：
                // 设备被外部关闭或超时自动关停时，及时退出使用界面（原来 30 秒太慢）
                if (tick % 15 == 0) {
                    try {
                        val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
                        // 不要求 success：只要不是「使用中」(307) 且没有订单号，即认为订单已结束
                        if (q.errorCode != 307 && q.data?.orderNo == null) {
                            onAutoClose(snCode)
                            return@launch
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleMqttMessage(message: String) {
        try {
            val msg = gson.fromJson(message, MqttOrderMsg::class.java)
            msg.orderNo?.let { if (currentOrderNo == null) { currentOrderNo = it; showerSnCode?.let { updateOrderNo(it, it) } } }
            msg.consumeMoney?.let {
                showerConsumed = it
                showerRemaining = "%.2f".format(if (showerPreDeduct - it < 0) 0.0 else showerPreDeduct - it)
            }
        } catch (_: Exception) {}
    }

    /**
     * 设备自动关闭：停止计时，查询消费金额，弹出确认框。
     * 用户点确认后才真正退出（finishShower）。
     */
    private fun onAutoClose(snCode: String) {
        if (!isShowering) return
        // 停止计时（弹窗期间洗澡界面不再走秒）
        timerJob?.cancel(); orderPollJob?.cancel()

        autoCloseDeviceName = selectedDevice?.displayName ?: lastDeviceName.ifEmpty { "热水器" }
        autoCloseElapsed = showerElapsedSec
        autoCloseConsumed = 0.0
        autoCloseLoading = true
        showAutoCloseDialog = true

        // 后台异步等账单结算，拿到金额后更新弹窗
        viewModelScope.launch {
            val orderNo = currentOrderNo ?: activeOrders.find { it.snCode == snCode }?.orderNo ?: ""
            val startTime = PrefsHelper.getStartedAt(snCode)
            autoCloseConsumed = try {
                queryLastBillAmount(orderNo, startTime) ?: 0.0
            } catch (_: Exception) { 0.0 }
            autoCloseLoading = false
        }
    }

    /** 用户点确认：退出洗澡界面并清理 */
    fun confirmAutoClose() {
        showAutoCloseDialog = false
        val snCode = showerSnCode ?: ""
        finishShower(snCode, null)
    }

    /**
     * 最小化使用界面：退出界面但**不结束用水**。
     * 订单保留在 activeOrders，计时器（startedAt）继续累计，可随时通过"恢复"回到界面。
     */
    fun minimizeShower() {
        if (!isShowering) return
        timerJob?.cancel()
        orderPollJob?.cancel()
        isShowering = false
        isStopping = false
        showerSnCode = null
        currentOrderNo = null
        // 注意：保留 activeOrders 与 PrefsHelper.getStartedAt(snCode)，用水与计时都继续
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        toastMessage = "已返回主页，设备仍在运行"
    }

    // ════════════════════════════════════════════
    fun stopShower(skipNetwork: Boolean = false) {
        if (!isShowering || isStopping) return
        val snCode = showerSnCode ?: ""
        val oNo = currentOrderNo ?: activeOrders.find { it.snCode == snCode }?.orderNo ?: ""

        // 挤号等场景：loginCode 已失效，跳过网络请求，直接本地清理，避免再次触发挤号
        if (skipNetwork) {
            finishShower(snCode, null)
            return
        }

        isStopping = true
        viewModelScope.launch {
            try {
                var orderNo = oNo
                if (orderNo.isEmpty()) {
                    val p = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
                    orderNo = p.data?.orderNo ?: ""
                }

                // 1. 发送关阀指令
                val close = NetworkModule.apiService.closeOrderSafe(snCode = snCode, orderNo = orderNo, auth = NetworkModule.authFields())
                if (!close.success) {
                    checkKick(close.displayMessage)
                    // 服务器已接受/已在关闭中时也视为成功
                    if (close.errorCode == 307 || close.displayMessage?.contains("已在") == true) {
                        finishShower(snCode, null)
                    } else {
                        toastMessage = close.displayMessage ?: "关闭失败，请重试"
                        isStopping = false
                    }
                    return@launch
                }

                // 2. 轮询确认关阀结果（最多 5 次，间隔 1 秒）
                var closedOk = false
                for (i in 0 until 5) {
                    delay(1000)
                    try {
                        val r = NetworkModule.apiService.closeOrderResultSafe(snCode = snCode, orderNo = orderNo, auth = NetworkModule.authFields())
                        if (r.success) {
                            val d = r.data
                            val closed = d == null ||
                                d.state == 0 ||
                                d.status == 0 ||
                                d.orderNo.isNullOrEmpty()
                            if (closed) { closedOk = true; break }
                        } else {
                            checkKick(r.displayMessage)
                        }
                    } catch (_: Exception) {}
                }

                // 3. 关阀确认成功后：先立即退出（不阻塞），后台异步等账单结算后弹金额
                if (closedOk) {
                    val startTime = PrefsHelper.getStartedAt(snCode)  // 开阀时间戳，未开始时为 0
                    finishShower(snCode, null)
                    // 后台轮询账单（最多 10 次 × 2 秒 = 20 秒），拿到金额后弹 toast
                    viewModelScope.launch {
                        val amount = queryLastBillAmount(orderNo, startTime)
                        if (amount != null) {
                            toastMessage = if (amount > 0) {
                                "已停止，本次消费 ¥%.2f".format(amount)
                            } else {
                                "热水器已关闭，本次无消费"
                            }
                        } else {
                            toastMessage = "热水器已关闭"
                        }
                    }
                } else {
                    finishShower(snCode, null)
                }
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "停止洗澡失败，请检查网络后重试"
                isStopping = false
            }
        }
    }

    /**
     * 查询账单获取本次消费金额（后台异步调用，不阻塞关闭流程）。
     * 账单结算可能有延迟，故轮询最多 10 次（每次间隔 2 秒，共约 20 秒）等服务器结算完成。
     * 只统计 [startTime]（开阀时间戳，毫秒）之后产生的账单，避免读到上一次的消费；
     * 优先匹配当前订单号（billRequestType=2 时 orderId 与 orderNo 对应）。
     * 超时仍无消费时返回 0.0。
     */
    private suspend fun queryLastBillAmount(orderNo: String, startTime: Long): Double? {
        val parsers = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        )
        val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
        val month = fmt.format(java.util.Calendar.getInstance().time)

        // 最多 6 次 × 1.2 秒 ≈ 7 秒（原 20 秒太慢，用户感知为"迟迟不弹"）
        for (attempt in 0 until 6) {
            try {
                val resp = NetworkModule.apiService.getBillListSafe(month = month)
                val bills = resp.data ?: return null

                // 优先精确匹配订单号
                if (orderNo.isNotEmpty()) {
                    val matched = bills.firstOrNull { it.consumeBillDTO.orderId == orderNo }
                    if (matched != null) {
                        val m = matched.consumeBillDTO.consumeMoney.toDoubleOrNull()
                        if (m != null && m > 0) return m
                        // 金额仍为 0 → 可能结算中，继续轮询
                    }
                }

                // 开阀之后的账单取最新一笔
                val recent = bills.filter { bill ->
                    if (startTime <= 0) return@filter true
                    val t = parsers.asSequence()
                        .map { p -> try { p.parse(bill.consumeBillDTO.consumeDate)?.time ?: 0L } catch (_: Exception) { 0L } }
                        .maxOrNull() ?: 0L
                    t >= startTime
                }
                if (recent.isNotEmpty()) {
                    val latest = recent.maxByOrNull { it.consumeBillDTO.consumeDate }
                    val m = latest?.consumeBillDTO?.consumeMoney?.toDoubleOrNull()
                    if (m != null && m > 0) return m
                }
            } catch (_: Exception) {}
            if (attempt < 5) delay(1200)
        }
        return 0.0
    }

    /** 完成停止流程：退出洗澡界面，清理状态，显示结算结果 */
    private fun finishShower(snCode: String, consumed: Double?) {
        isShowering = false
        isStopping = false

        // 从活跃列表移除
        activeOrders.removeAll { it.snCode == snCode }
        saveOrders()
        activeDeviceSnCodes.remove(snCode)

        if (consumed != null) {
            toastMessage = "已停止，本次消费 ¥%.2f".format(consumed)
        } else {
            toastMessage = "热水器已关闭"
        }

        currentOrderNo = null; showerConsumed = 0.0; showerPreDeduct = 0.0
        showerRemaining = "0.00"; showerElapsedSec = 0
        autoDisConSec = 0
        PrefsHelper.setStartedAt(snCode, 0L) // 重置该设备计时器
        PrefsHelper.clearAutoDiscon(snCode)  // 清除自动关停倒计时
        showerSnCode = null; timerJob?.cancel(); orderPollJob?.cancel()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
    }

    fun logout() {
        // 停止所有后台任务
        timerJob?.cancel()
        orderPollJob?.cancel()
        
        // 断开 MQTT 连接
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        
        // 停止蓝牙扫描
        try { scanner?.stopScan() } catch (_: Exception) {}
        
        // 重置所有状态
        isShowering = false
        showerSnCode = null
        currentOrderNo = null
        showerConsumed = 0.0
        showerPreDeduct = 0.0
        showerRemaining = "0.00"
        showerElapsedSec = 0
        showerError = null
        toastMessage = null
        kickedOut = false
        selectedDevice = null
        showDeviceDetail = false
        nearbyDevices.clear()
        activeOrders.clear()
        activeDeviceSnCodes.clear()
        billList = emptyList()
        useCodeData = null
        walletInfo = null
    }

    private fun saveOrders() { PrefsHelper.saveActiveOrders(activeOrders.toList()) }

    fun isDeviceActive(snCode: String) = snCode in activeDeviceSnCodes

    // ── 寝室绑定 / 设备筛选 ──

    /** 当前是否有绑定寝室 */
    val hasBoundRoom: Boolean get() = PrefsHelper.boundRoom.isNotBlank()

    /** 从附近设备名提取位置关键词：去掉 "热水器-"/"热水表-"/"洗手台N-" 前缀 */
    fun extractLocationFromDevice(name: String): String {
        var n = name
        n = n.replaceFirst(Regex("^洗手台\\d*"), "").trim('-').trim()
        n = n.replaceFirst(Regex("^热水[器表]"), "").trim('-').trim()
        return n.trim()
    }

    // 归一化后的寝室关键词缓存，避免每次过滤都重复做字符串替换
    private var cachedRoomKey: String? = null
    private var cachedNormKey: String = ""

    /** 判断设备名是否匹配绑定的寝室（忽略大小写、空格、连字符） */
    fun matchesBoundRoom(deviceName: String): Boolean {
        val key = PrefsHelper.boundRoom.trim()
        if (key.isEmpty()) return true
        if (cachedRoomKey != key) {
            cachedRoomKey = key
            cachedNormKey = key.lowercase().replace(" ", "").replace("-", "")
        }
        if (cachedNormKey.isEmpty()) return true
        val n = deviceName.lowercase().replace(" ", "").replace("-", "")
        return n.contains(cachedNormKey)
    }

    // ── 挤号 ──
    private fun checkKick(msg: String?) {
        if (msg.isNullOrEmpty()) return
        if (msg.contains("登录") || msg.contains("token") || msg.contains("失效") || msg.contains("过期") || msg.contains("认证") || msg.contains("未登录") || msg.contains("请重新")) {
            kickedOut = true; PrefsHelper.clear()
        }
    }
    private fun checkKickEx(e: Exception) {
        val m = e.message ?: return
        if (m.contains("401") || m.contains("403") || m.contains("Unauthorized") || m.contains("Forbidden")) { kickedOut = true; PrefsHelper.clear() }
    }

    fun refreshWallet() {
        viewModelScope.launch {
            try { 
                val resp = NetworkModule.apiService.getWalletSafe()
                if (resp.success) walletInfo = resp.data else checkKick(resp.displayMessage) 
            } catch (e: Exception) { 
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    fun loadUseCode() {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getUseCodeSafe()
                if (resp.success && resp.data != null) useCodeData = resp.data
            } catch (_: Exception) {}
        }
    }

    fun pullRefresh() { 
        refreshWallet()
        loadBills()
        startScan() 
    }

    // ── 设备发现 ──
    fun addDevice(device: NearbyDevice) {
        val idx = nearbyDevices.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            val e = nearbyDevices[idx]
            if (abs(e.rssi - device.rssi) > 5 || e.deviceInfo == null) {
                nearbyDevices[idx] = e.copy(rssi = device.rssi)
                if (e.deviceInfo == null && fetchingMacs.add(device.mac)) fetchInfo(device.mac)
            }
        } else {
            nearbyDevices.add(device); if (fetchingMacs.add(device.mac)) fetchInfo(device.mac)
        }
    }

    private fun fetchInfo(mac: String) {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    val info = resp.data; val i = nearbyDevices.indexOfFirst { it.mac == mac }
                    if (i >= 0) nearbyDevices[i] = nearbyDevices[i].copy(deviceInfo = info)
                    try {
                        val q = NetworkModule.apiService.queryUsingSafe(snCode = info.snCode, auth = NetworkModule.authFields())
                        if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                            activeDeviceSnCodes.add(info.snCode)
                            val owner = q.data?.isOwner ?: true
                            if (owner && activeOrders.none { it.snCode == info.snCode }) {
                                activeOrders.add(ActiveOrder(info.snCode, q.data?.orderNo ?: "", info.displayName, info.macAddress, info.typeEmoji, info.withholdMoney))
                                saveOrders()
                            }
                        }
                    } catch (_: Exception) {}
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) { checkKickEx(e) }
            finally { fetchingMacs.remove(mac) }
        }
    }

    // ── 账单 ──
    fun loadBills() {
        viewModelScope.launch {
            isLoadingBills = true
            try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                val cal = java.util.Calendar.getInstance()
                val all = mutableListOf<BillItem>()
                for (i in 0..2) {
                    val month = fmt.format(cal.time)
                    val resp = NetworkModule.apiService.getBillListSafe(month = month)
                    if (resp.success && !resp.data.isNullOrEmpty()) all.addAll(resp.data)
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                billList = all.take(20)
            } catch (e: Exception) { 
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
            finally { isLoadingBills = false }
        }
    }
}
