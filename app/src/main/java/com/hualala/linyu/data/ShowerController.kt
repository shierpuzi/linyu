package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.closeOrderResultSafe
import com.hualala.linyu.api.closeOrderSafe
import com.hualala.linyu.api.downRateResultSafe
import com.hualala.linyu.api.downRateSafe
import com.hualala.linyu.api.getBillListSafe
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.delay

/** 开阀结果 */
sealed interface OpenOutcome {
    /** 需要交给界面做挤号判断的原始服务端消息（没有则为 null） */
    val kickHint: String?

    /** 新开阀成功 */
    data class Opened(val autoDiscon: Int) : OpenOutcome {
        override val kickHint: String? = null
    }

    /** 设备上已有进行中的订单，直接恢复（没有发 downRate） */
    data class Resumed(val orderNo: String) : OpenOutcome {
        override val kickHint: String? = null
    }

    /** 明确失败 */
    data class Failed(val message: String) : OpenOutcome {
        override val kickHint: String? get() = message
    }

    /**
     * 预算耗尽，开没开不确定。
     * 这种情况**不能**当成失败——downRate 其实已经发出去了，
     * 只是确认轮询没跑完。谎报失败会让用户重复操作。
     */
    data object Unknown : OpenOutcome {
        override val kickHint: String? = null
    }
}

/** 关阀结果 */
sealed interface CloseOutcome {
    data class Closed(
        /** 关阀前的开阀时间戳，供调用方做账单过滤（Controller 已经把它清掉了，所以要带出来） */
        val startTimeMs: Long,
        override val kickHint: String? = null
    ) : CloseOutcome

    data class Failed(
        val message: String,
        override val kickHint: String?
    ) : CloseOutcome

    /** 需要交给界面做挤号判断的原始服务端消息 */
    val kickHint: String?
}

/**
 * 洗澡（开阀 / 关阀）的共享业务层。
 *
 * 从 MainViewModel 里抽出来，目的是让**没有 Activity、没有 Compose**的调用方
 * （桌面小组件）也能启停热水。这里只做两件事：
 *
 * 1. 调接口，把「查询 → 指令 → 轮询确认」这套流程封装好
 * 2. 把结果落到 PrefsHelper（开阀时间戳、自动关停倒计时、活跃订单、上次使用设备）
 *
 * **不碰的东西**：MQTT（只是加速器，HTTP 轮询能独立保证正确性）、
 * Compose 状态、挤号弹窗、toast —— 这些留在各自的调用方。
 *
 * ⚠️ 迁移自 MainViewModel 时是**纯搬运**，以下判断条件必须原样保留：
 * - `errorCode == 307` 表示「已有订单 / 已在关闭中」
 * - 关阀成功判据里 `displayMessage.contains("已在")` 依赖服务端中文字符串
 * - 开阀成功判据 `success && (state == 0 || result == 0 || orderNo != null)`
 */
object ShowerController {

    // ════════════════════════════════════════════
    //  本地状态（纯 Prefs 读取，无网络）
    // ════════════════════════════════════════════

    /** 该设备当前是否有进行中的订单 */
    fun isRunning(snCode: String): Boolean =
        snCode.isNotEmpty() && PrefsHelper.getActiveOrders().any { it.snCode == snCode }

    /** 已用时长（秒），按开阀时间戳算；没开始过则为 0 */
    fun elapsedSeconds(snCode: String): Int {
        val startAt = PrefsHelper.getStartedAt(snCode)
        return if (startAt > 0) ((System.currentTimeMillis() - startAt) / 1000).toInt() else 0
    }

    /** 开阀时的 Unix 毫秒时间戳，供小组件的 Chronometer 作基准 */
    fun startedAt(snCode: String): Long = PrefsHelper.getStartedAt(snCode)

    /** 自动关停剩余秒数，0 表示未知/无倒计时 */
    fun autoDisconRemain(snCode: String): Int = PrefsHelper.getAutoDisconRemain(snCode)

    fun activeOrderFor(snCode: String): ActiveOrder? =
        PrefsHelper.getActiveOrders().find { it.snCode == snCode }

    fun lastDeviceSnCode(): String = PrefsHelper.lastDeviceSnCode
    fun lastDeviceName(): String = PrefsHelper.lastDeviceName
    fun lastDeviceEmoji(): String = PrefsHelper.lastDeviceEmoji

    // ════════════════════════════════════════════
    //  动作
    // ════════════════════════════════════════════

    /**
     * 开阀。
     *
     * @param budgetMs 确认轮询的总预算。小组件跑在广播里（`goAsync()` 约 10 秒上限），
     *                 必须设上限，超了就返回 [OpenOutcome.Unknown]。
     *                 App 内部调用可以给大一点。
     */
    suspend fun openValve(
        snCode: String,
        device: DeviceInfo? = null,
        budgetMs: Long = 6_000L
    ): OpenOutcome {
        require(snCode.isNotBlank()) { "snCode 不能为空" }
        val deadline = System.currentTimeMillis() + budgetMs

        // 1. 设备上已经有订单 → 直接恢复，不再发 downRate
        val existing = NetworkModule.apiService.queryUsingSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        if (existing.errorCode == 307 || (existing.success && existing.data?.orderNo != null)) {
            val orderNo = existing.data?.orderNo ?: ""
            ensureActiveOrder(snCode, orderNo, device)
            rememberDevice(device)
            return OpenOutcome.Resumed(orderNo)
        }

        // 2. 下发开阀指令
        val resp = NetworkModule.apiService.downRateSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        if (!resp.success) return OpenOutcome.Failed(resp.displayMessage ?: "开始失败")

        // 3. 轮询确认开阀（最多 9 次 × 700ms，与 App 内一致）
        var opened = false
        var autoDiscon = resp.data?.autoDisConTime ?: 0
        for (i in 0..8) {
            if (i > 0 && System.currentTimeMillis() >= deadline) return OpenOutcome.Unknown
            delay(700)
            try {
                val r = NetworkModule.apiService.downRateResultSafe(
                    snCode = snCode, auth = NetworkModule.authFields()
                )
                val d = r.data
                if (r.success && (d?.state == 0 || d?.result == 0 || d?.orderNo != null)) {
                    opened = true
                    val autoTime = d?.autoDisConTime
                    if (autoTime != null && autoTime > 0) autoDiscon = autoTime
                    break
                }
            } catch (_: Exception) {
                // 单次轮询失败不算失败，继续下一轮
            }
        }
        if (!opened) return OpenOutcome.Failed("开阀未确认成功，请确认热水器是否已开启")

        // 4. 落盘：开阀时间戳 / 活跃订单 / 自动关停倒计时 / 上次使用设备
        if (PrefsHelper.getStartedAt(snCode) <= 0L) {
            PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
        }
        ensureActiveOrder(snCode, "", device)
        rememberDevice(device)
        if (autoDiscon > 0 && PrefsHelper.getAutoDisconRemain(snCode) <= 0) {
            PrefsHelper.setAutoDisconRemain(snCode, autoDiscon)
        }
        return OpenOutcome.Opened(autoDiscon)
    }

    /**
     * 关阀。无论确认结果如何都会清掉本地状态（与 App 原逻辑一致——
     * 确认不通过也退出洗澡界面，不把用户卡在里面）。
     */
    suspend fun closeValve(snCode: String, orderNo: String): CloseOutcome {
        if (snCode.isBlank()) return CloseOutcome.Failed("设备信息不完整", null)

        var orderNoResolved = orderNo
        if (orderNoResolved.isEmpty()) {
            val p = NetworkModule.apiService.queryUsingSafe(
                snCode = snCode, auth = NetworkModule.authFields()
            )
            orderNoResolved = p.data?.orderNo ?: ""
        }

        // 关阀前的开阀时间戳要先取出来——下面 clearDeviceState 会把它清零
        val startTime = PrefsHelper.getStartedAt(snCode)

        // 1. 下发关阀指令
        val close = NetworkModule.apiService.closeOrderSafe(
            snCode = snCode, orderNo = orderNoResolved, auth = NetworkModule.authFields()
        )
        if (!close.success) {
            // 服务器已接受 / 已在关闭中时也视为成功
            if (close.errorCode == 307 || close.displayMessage?.contains("已在") == true) {
                clearDeviceState(snCode)
                return CloseOutcome.Closed(startTime, close.displayMessage)
            }
            return CloseOutcome.Failed(close.displayMessage ?: "关闭失败，请重试", close.displayMessage)
        }

        // 2. 轮询确认关阀结果（最多 5 次 × 1 秒）
        var lastMessage: String? = null
        for (i in 0 until 5) {
            delay(1000)
            try {
                val r = NetworkModule.apiService.closeOrderResultSafe(
                    snCode = snCode, orderNo = orderNoResolved, auth = NetworkModule.authFields()
                )
                if (r.success) {
                    val d = r.data
                    val closed = d == null || d.state == 0 || d.status == 0 || d.orderNo.isNullOrEmpty()
                    if (closed) break
                } else {
                    lastMessage = r.displayMessage
                }
            } catch (_: Exception) {
                // 单次轮询失败不算失败
            }
        }

        clearDeviceState(snCode)
        return CloseOutcome.Closed(startTime, lastMessage)
    }

    /**
     * 以服务端为准，把本地状态对齐一次。
     *
     * 用在「开阀结果未知」之后：小组件预算耗尽时不知道到底开没开，
     * 与其猜，不如按一次服务端真实状态。
     * @return 对齐后是否处于使用中
     */
    suspend fun reconcile(snCode: String): Boolean {
        if (snCode.isBlank()) return false
        val orderNo = queryOrderNo(snCode)
        return if (orderNo.isNotEmpty() || hasActiveOrder(snCode)) {
            if (PrefsHelper.getStartedAt(snCode) <= 0L) {
                PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
            }
            ensureActiveOrder(snCode, orderNo, null)
            true
        } else {
            clearDeviceState(snCode)
            false
        }
    }

    /** 服务端是否报告该设备有进行中的订单 */
    private suspend fun hasActiveOrder(snCode: String): Boolean = try {
        val p = NetworkModule.apiService.queryUsingSafe(
            snCode = snCode, auth = NetworkModule.authFields()
        )
        p.errorCode == 307 || (p.success && p.data?.orderNo != null)
    } catch (_: Exception) {
        false
    }

    /** 查询设备上进行中订单的订单号，没有则返回空串 */
    suspend fun queryOrderNo(snCode: String): String {
        return try {
            val p = NetworkModule.apiService.queryUsingSafe(
                snCode = snCode, auth = NetworkModule.authFields()
            )
            if (p.errorCode == 307 || (p.success && p.data?.orderNo != null)) p.data?.orderNo ?: "" else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 查询账单获取本次消费金额。
     * 账单结算可能有延迟，故轮询最多 6 次（每次间隔 1.2 秒，共约 7 秒）。
     * 只统计 [startTimeMs]（开阀时间戳）之后产生的账单，避免读到上一次的消费；
     * 优先匹配订单号（`billRequestType = 2` 时 orderId 与 orderNo 对应）。
     * 超时仍无消费时返回 0.0，整个查询失败返回 null。
     */
    suspend fun settleAmount(orderNo: String, startTimeMs: Long): Double? {
        val parsers = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        )
        val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
            .format(java.util.Calendar.getInstance().time)

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
                    if (startTimeMs <= 0) return@filter true
                    val t = parsers.asSequence()
                        .map { p -> try { p.parse(bill.consumeBillDTO.consumeDate)?.time ?: 0L } catch (_: Exception) { 0L } }
                        .maxOrNull() ?: 0L
                    t >= startTimeMs
                }
                if (recent.isNotEmpty()) {
                    val latest = recent.maxByOrNull { it.consumeBillDTO.consumeDate }
                    val m = latest?.consumeBillDTO?.consumeMoney?.toDoubleOrNull()
                    if (m != null && m > 0) return m
                }
            } catch (_: Exception) {
                // 网络抖动，继续重试
            }
            if (attempt < 5) delay(1200)
        }
        return 0.0
    }

    // ════════════════════════════════════════════
    //  内部：持久化
    // ════════════════════════════════════════════

    /** 活跃订单里没有该设备就补一条（已有则不动，避免覆盖已解析到的 orderNo） */
    private fun ensureActiveOrder(snCode: String, orderNo: String, device: DeviceInfo?) {
        val list = PrefsHelper.getActiveOrders()
        if (list.any { it.snCode == snCode }) return
        list.add(
            ActiveOrder(
                snCode = snCode,
                orderNo = orderNo,
                deviceName = device?.displayName ?: PrefsHelper.lastDeviceName,
                deviceMac = device?.macAddress ?: PrefsHelper.lastDeviceMac,
                deviceEmoji = device?.typeEmoji ?: PrefsHelper.lastDeviceEmoji,
                preDeduct = device?.withholdMoney ?: 0.0
            )
        )
        PrefsHelper.saveActiveOrders(list)
    }

    /** 记录「上次使用的设备」。小组件调用时 device 为 null（信息本来就在 Prefs 里），跳过即可 */
    private fun rememberDevice(device: DeviceInfo?) {
        if (device == null) return
        PrefsHelper.lastDeviceName = device.displayName
        PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = device.snCode
        PrefsHelper.lastDeviceEmoji = device.typeEmoji
    }

    /** 清掉该设备的本地使用状态 */
    private fun clearDeviceState(snCode: String) {
        PrefsHelper.saveActiveOrders(PrefsHelper.getActiveOrders().filterNot { it.snCode == snCode })
        PrefsHelper.setStartedAt(snCode, 0L)
        PrefsHelper.clearAutoDiscon(snCode)
    }
}
