package com.quzhi.lite.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quzhi.lite.api.NetworkModule
import com.quzhi.lite.api.getAccountInfoSafe
import com.quzhi.lite.api.getBillListSafe
import com.quzhi.lite.api.getBindCardInfoSafe
import com.quzhi.lite.api.getCampusUserInfoSafe
import com.quzhi.lite.api.getDeviceInfoSafe
import com.quzhi.lite.api.forgetPasswordSafe
import com.quzhi.lite.api.generateUseCodeSafe
import com.quzhi.lite.api.getProjectInfoSafe
import com.quzhi.lite.api.getUseCodeSafe
import com.quzhi.lite.api.queryUnpaidBillsSafe
import com.quzhi.lite.api.requestDeductSafe
import com.quzhi.lite.api.setUseCodeSafe
import com.quzhi.lite.api.getWalletSafe
import com.quzhi.lite.api.queryUsingSafe
import com.quzhi.lite.data.BillMerger
import com.quzhi.lite.data.BalanceEstimator
import com.quzhi.lite.data.BleArbiter
import com.quzhi.lite.data.CloseOutcome
import com.quzhi.lite.data.OpenOutcome
import com.quzhi.lite.ble.BleController
import com.quzhi.lite.ble.BleConnectResult
import com.quzhi.lite.ble.DeviceQueryState
import com.quzhi.lite.data.BleShowerController
import com.quzhi.lite.data.ProjectRepository
import com.quzhi.lite.data.ShowerController
import com.quzhi.lite.data.ShowerEvents
import com.quzhi.lite.data.SettlementReconciler
import com.quzhi.lite.data.canLogoutVoluntarily
import com.quzhi.lite.service.ShowerWatchService
import com.quzhi.lite.widget.LinYuWidget
import com.quzhi.lite.model.AccountInfo
import com.quzhi.lite.model.ActiveOrder
import com.quzhi.lite.model.CachedBill
import com.quzhi.lite.model.CachedDevice
import com.quzhi.lite.model.UseCodeData
import com.quzhi.lite.model.BillItem
import com.quzhi.lite.model.DeviceInfo
import com.quzhi.lite.model.MqttOrderMsg
import com.quzhi.lite.model.NearbyDevice
import com.quzhi.lite.model.UnpaidBill
import com.quzhi.lite.model.UserProject
import com.quzhi.lite.model.WalletData
import com.quzhi.lite.model.WashMode
import com.quzhi.lite.utils.AppLogger
import com.quzhi.lite.utils.BluetoothScanner
import com.quzhi.lite.utils.MqttManager
import com.quzhi.lite.utils.Notifier
import com.quzhi.lite.utils.NotifyTextPolicy
import com.quzhi.lite.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.quzhi.lite.utils.MoneyFormat
import com.quzhi.lite.data.DeviceBoardPolicy
import com.quzhi.lite.data.DeviceInfoCache
import com.quzhi.lite.data.FavoriteDevice
import com.quzhi.lite.data.FavoriteStore
import com.quzhi.lite.data.toFavorite
import com.quzhi.lite.widget.WidgetBridge
import com.quzhi.lite.data.MacFormat
import com.quzhi.lite.data.ModeOrderController
import com.quzhi.lite.data.ModeOrderPolicy
import com.quzhi.lite.data.ModeStatus
import com.quzhi.lite.data.PendingModeOrder
import com.quzhi.lite.data.PlaceOrderResult
import com.quzhi.lite.data.SettlementEngine

/**
 * 开阀失败的原因分类。
 *
 * ⚠️ 界面**只对 [SERVER_REJECTED] 引导去补扣**。把网络异常、设备被占用、结果未知
 * 也归到「有欠费」里，用户就会因为一次 WiFi 掉线被引导去真扣钱。
 */
enum class ShowerErrorKind {
    /** 服务端明确拒绝（欠费、账户异常…）——**只有这一类**和未扣账单有关 */
    SERVER_REJECTED,
    /** 设备正被别人用着 */
    IN_USE_BY_OTHERS,
    /** 开阀结果未知（预算耗尽没确认上） */
    UNKNOWN_RESULT,
    /** 网络异常 */
    NETWORK,
    /** 设备信息不完整 */
    BAD_DEVICE,
}

/** 开阀失败：[message] 给用户看，[kind] 决定界面要不要引导去补扣 */
data class ShowerError(val message: String, val kind: ShowerErrorKind)

class MainViewModel : ViewModel() {

    var walletInfo by mutableStateOf<WalletData?>(null)
    val nearbyDevices = mutableStateListOf<NearbyDevice>()
    var isScanning by mutableStateOf(false)
    var scanStartTime by mutableStateOf(0L)

    var isShowering by mutableStateOf(false)
    var isStartingShower by mutableStateOf(false)
    var isStopping by mutableStateOf(false)

    // ── 蓝牙水表（smallTypeId==1 / isBle）──
    //
    // 与 4G 表最大的不同：**连接必须跨「开阀 → 用水 → 关阀」整段存活**。
    // 蓝牙表的阀是手机直连开的，中途断开连接就没法再关阀了，
    // 所以这两个对象挂在 ViewModel 上，不能像普通请求那样用完即弃。

    /** 当前蓝牙连接。没有进行中的蓝牙用水时为 null */
    private var bleController: BleController? = null

    /** 当前蓝牙用水会话。开阀成功后写入，结算完清空 */
    private var bleSession: BleShowerController.Session? = null

    /**
     * 蓝牙流程的进度文案（null 表示没在进行）。
     *
     * 蓝牙开阀比 4G 表慢得多——要连设备、等状态帧、下费率、写费率包，
     * 全程好几秒，不给反馈用户会以为卡死。
     */
    var bleProgress by mutableStateOf<String?>(null)
        private set

    /**
     * 云端开阀返回 306 时暂存这里，等用户在确认框里决定要不要改用蓝牙。
     *
     * 非 null 即表示「弹框正在显示」。用户确认或取消后清空。
     */
    var bleRetryDevice by mutableStateOf<DeviceInfo?>(null)
        private set

    /**
     * 用户在确认框里选了「用蓝牙重试」。
     *
     * 先记下这台设备（`markBleDevice`），再走蓝牙流程——
     * **先记再看结果**：万一蓝牙连接超时（用户走远了），标记还是要保住的，
     * 否则下次又要问一遍。真猜错了（连上但没有水控服务）会自动撤销。
     */
    fun confirmBleRetry() {
        val device = bleRetryDevice ?: return
        bleRetryDevice = null
        PrefsHelper.markBleDevice(device.snCode)
        isStartingShower = true
        sessionScope().launch {
            try {
                showerError = null
                showerSnCode = device.snCode
                startBleShower(device, phone, device.snCode)
            } catch (t: Throwable) {
                AppLogger.e("蓝牙重试异常", t)
                showerError = ShowerError("蓝牙开启失败，请重试", ShowerErrorKind.NETWORK)
            } finally {
                isStartingShower = false
            }
        }
    }

    /** 用户放弃蓝牙方式 */
    fun dismissBleRetry() {
        bleRetryDevice = null
    }

    /**
     * 服务端「设备不在线」的业务错误码。
     *
     * ⚠️ 用它判断，**不要匹配文案**（之前就是匹配「不在线」三个字，太脆：
     * 措辞会随学校和服务端版本变，而且我们只能覆盖到自己见过的说法）。
     */
    private val ERROR_DEVICE_OFFLINE = 306
    var showerRemaining by mutableStateOf("0.00")
    var showerConsumed by mutableStateOf(0.0)
    var showerPreDeduct by mutableStateOf(0.0)
    var showerElapsedSec by mutableStateOf(0)
    var autoDisConSec by mutableStateOf(0)   // 自动关停剩余秒数，0 = 未知
    var showerError by mutableStateOf<ShowerError?>(null)
    var toastMessage by mutableStateOf<String?>(null)

    // ── 自动关停确认弹窗 ──
    var showAutoCloseDialog by mutableStateOf(false)
    var autoCloseDeviceName by mutableStateOf("")
    var autoCloseElapsed by mutableStateOf(0)
    /** 自动关停那笔的金额。**null = 没拿到**，和 0.0（确实没花钱）不是一回事 */
    var autoCloseConsumed by mutableStateOf<Double?>(null)
    var autoCloseLoading by mutableStateOf(false)

    var kickedOut by mutableStateOf(false)

    var lastDeviceName by mutableStateOf("")
    var lastDeviceMac by mutableStateOf("")
    var lastDeviceSnCode by mutableStateOf("")
    var lastDeviceEmoji by mutableStateOf("🚿")

    /**
     * 当前打开的设备。
     *
     * ## 为什么 setter 里挂余额加载
     *
     * 设备页要显示的是**这台设备所属板块**的钱包余额，不是当前板块的
     * （见 [DeviceBoardPolicy]）。这个赋值点有 5 处以上：扫码、点附近设备、
     * 点收藏、点「上次使用」、从活跃订单恢复……以前是每个入口各自调一次
     * [loadDeviceBoardWallet]，结果**漏了两处**——从收藏进入时余额还是错的。
     *
     * 收口到 setter 里，以后新增入口不会再漏。代价是赋值时会顺带发一次请求，
     * 但只在跨板块时才会真发（同板块直接复用 [walletBalance]）。
     *
     * ⚠️ 底层仍是 mutableStateOf：只写普通字段的话界面不会重组，
     *    设备页会一直停在上一台设备的内容上。
     */
    private var selectedDeviceState by mutableStateOf<DeviceInfo?>(null)

    var selectedDevice: DeviceInfo?
        get() = selectedDeviceState
        set(value) {
            selectedDeviceState = value
            // null 是「页面关掉了」，不该再去取余额
            if (value != null) loadDeviceBoardWallet(value)
        }

    var showDeviceDetail by mutableStateOf(false)

    /**
     * 设备归属（按 snCode 索引）。
     *
     * ⚠️ 原先是一个全局 `isOwner` 布尔，而 [refreshDeviceStatus] 是**异步**的——
     * 快速切换设备时，A 的响应回来会盖到 B 上（「A 是别人的」串成 B 的状态，
     * 详情弹窗的按钮就会错灰或错亮）。改成按 snCode 存：谁的结果写谁，互不干扰。
     */
    private val ownerBySn = mutableStateMapOf<String, Boolean>()

    /** 该设备是否归自己（没查过时按「是自己的」处理，与原默认值一致） */
    fun isOwnerFor(snCode: String): Boolean = ownerBySn[snCode] ?: true

    /** 当前选中设备是否归自己（设备详情弹窗用） */
    val isOwner: Boolean get() = selectedDevice?.snCode?.let { isOwnerFor(it) } ?: true

    // ── 多设备活跃订单 ──
    val activeOrders = mutableStateListOf<ActiveOrder>()

    // 用于当前洗澡的设备 snCode
    private var showerSnCode: String? = null

    private var currentOrderNo: String? = null
    private var activeDeviceSnCodes = mutableSetOf<String>()
    /** 本轮扫描里已经发起过设备信息查询的 MAC（见 [addDevice]）；[startScan] 时清空 */
    private val queriedThisScan = mutableSetOf<String>()
    private val gson = Gson()
    private var scanner: BluetoothScanner? = null
    private var mqttManager: MqttManager? = null
    private var timerJob: Job? = null
    private var orderPollJob: Job? = null
    private var kickWatchJob: Job? = null

    /**
     * 会话级协程作用域。
     *
     * 所有"发请求"的任务都挂在这上面，而不是直接挂 viewModelScope——
     * 这样被挤号 / 退出登录时可以把它们**整体取消**。
     *
     * 为什么必须整体取消：这些请求是拿旧 loginCode 发出去的，
     * 响应可能在用户已经重新登录之后才回来，里面写着"登录失效"。
     * 若不掐断，`checkKick` 会拿旧会话的失败去清掉**新会话**的凭证，
     * 表现就是"被挤下线后重新登录，刚进去又被弹出来"。
     */
    private var sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private fun sessionScope() = CoroutineScope(viewModelScope.coroutineContext + sessionJob)

    init {
        // 让小组件服务知道「App 还活着、可能握着 BLE 连接」（见 BleArbiter）
        BleArbiter.appAlive = true
        // 用水结束由 ShowerWatchService 检测（它会清理本地状态并发通知），
        // 这里只负责界面收尾：同步内存状态 + 弹确认框。
        // App 不在前台时这个事件没人收，也没关系——服务那边已经处理完了。
        viewModelScope.launch {
            ShowerEvents.finished.collect { onShowerFinishedFromService(it) }
        }
        // 关阀未确认时服务会把活跃订单**放回去**（见 restoreActiveOrder）。那件事发生在
        // `finished` 之后，而上面那个处理已经调 `finishShower` 把设备从内存里删了——
        // 不在这里重新读一次 Prefs，内存和 Prefs 就会一直对不上。
        viewModelScope.launch {
            ShowerEvents.ordersRestored.collect {
                syncActiveOrdersFromPrefs()
                refreshWidgets()
            }
        }
        // 通知栏「结束使用」按钮 / 小组件的「关闭」对蓝牙表：服务发来请求，
        // App 若还持有这台设备的 BLE 连接就用自己的连接关阀（更稳），否则**重连设备**再关。
        viewModelScope.launch {
            ShowerEvents.stopBleRequest.collect { snCode ->
                AppLogger.i("收到服务请求停止蓝牙用水 $snCode")
                // ⚠️ App 能直接关的前提是**这一进程亲手开过这台设备的阀**——
                // 只有那时它才同时握着 [bleSession]（会话）和 [bleController]（活的 GATT 连接）。
                //
                // 小组件开的阀（会话在 WidgetBleService 那边）、或 App 进程被系统回收过
                // （内存里的会话/连接都没了，但 [BleArbiter.appAlive] 仍为真，因为
                // MainViewModel 一建起来就把它置 true），这两种情况下都**不能**直接关。
                //
                // 这时必须走 [reconnectAndStopBle]：重连设备 → 用状态帧重建会话 → 关阀。
                // 少了这个分支，请求就停在这里（老代码只弹一句提示），
                // 卡片闪一下「正在关闭…」又恢复原状，阀一直开着——用户看到的就是「关闭按钮失效」。
                if (bleSession?.snCode == snCode && bleController != null) {
                    stopBleShower()
                } else {
                    reconnectAndStopBle(snCode)
                }
            }
        }
    }

    // ── 账单 ──
    var billList by mutableStateOf<List<BillItem>>(emptyList())
    var billHistory by mutableStateOf<List<BillItem>>(emptyList())
    var isLoadingBills by mutableStateOf(false)

    /**
     * 「消费合并」的合并结果，**只给趋势图用**。
     *
     * 为 null 表示没开合并、或合并还没跑/失败了——趋势图会退回用 [billHistory]（当前板块）。
     * 非 null 时是一份跨板块合并后的账单（按时间倒序）。
     *
     * ⚠️ 千万别把它当业务数据用：不同板块的 orderNo 可能撞号，
     * 关阀/结算/对账必须用当前板块自己的 [billHistory]。
     */
    var mergedBills by mutableStateOf<List<BillItem>?>(null)
        private set

    /** 合并涉及哪些板块（给界面标注「合并自：洗浴 + 洗衣饮水吹风」） */
    var mergedFrom by mutableStateOf<List<BillMerger.BoardBills>>(emptyList())
        private set

    /** 防止重复触发合并（合并会临时改全局认证参数，绝不能并发） */
    private var isMerging = false

    // ════════════════════════════════════════════
    //  套餐计费下单（洗衣机 / 吹风机）
    //
    //  与洗浴链路完全独立：不 openValve、不进 ShowerScreen、不接 ShowerWatchService。
    //  详见 ModeOrderController 的类注释（那条链路是**不可退款扣款**）。
    // ════════════════════════════════════════════

    /** 当前正在操作套餐下单的设备；null = 不在套餐页面 */
    var modeOrderDevice by mutableStateOf<DeviceInfo?>(null)
        private set

    /** 该设备的套餐列表（来自 `/device/mode/list`） */
    var modeModes by mutableStateOf<List<WashMode>>(emptyList())
        private set

    /** 当前选中的套餐 modeId */
    var selectedModeId by mutableStateOf<Int?>(null)
        private set

    /** 正在提交下单（UI 要置灰按钮 + 转圈，防双击扣两份钱） */
    var isPlacingModeOrder by mutableStateOf(false)
        private set

    /** 套餐页的错误提示（余额不足 / 网络异常 / 未知错误）；null = 没错误 */
    var modeOrderError by mutableStateOf<String?>(null)
        private set

    /** 下单前的钱包余额（元）。拿不到是 null，UI 就不显示这一行 */
    var modeOrderWalletYuan by mutableStateOf<String?>(null)
        private set

    /**
     * 设备被**别人**占用的原因（含剩余时间）。null = 没占用或不知道。
     *
     * 跳过详情框之后，这是用户唯一能看到「这台机器正在被用」的地方，
     * 所以非空时选套餐页必须展示它，并且**禁用下单按钮**。
     */
    var modeOccupiedReason by mutableStateOf<String?>(null)
        private set

    /** 本机记着的进行中套餐单；null = 还没下单，页面停在选套餐阶段 */
    var modePending by mutableStateOf<PendingModeOrder?>(null)
        private set

    /** 倒计时刷新用（每秒 tick 一次，只在使用中才跑） */
    var modeOrderTick by mutableStateOf(0L)
        private set

    /**
     * 套餐下单的**待确认套餐**；非 null = 确认弹窗开着。
     *
     * 不是直接下单：`modeOrder` 一发出就扣全款且不可退，必须让用户
     * 亲眼核对设备名 + 套餐 + 金额 + 时长。见 [confirmModeOrder] / [commitModeOrder]。
     */
    var pendingConfirmMode by mutableStateOf<WashMode?>(null)
        private set

    private var modeOrderTickerJob: Job? = null

    // ── 按量计费设备的全屏页（取代原来的详情弹窗）──
    //
    // 状态刻意做得很薄：设备信息直接复用 selectedDevice，使用中状态复用
    // showerSnCode / isShowering / showerElapsedSec 那套现成的热水器状态机。
    // 只有「要不要显示全屏页」和「确认弹窗开没开」是新的。

    /** 当前是否停在这个设备的全屏操作页（按量计费）。false = 回首页列表 */
    var meteredScreenOpen by mutableStateOf(false)
        private set

    /** 按量计费的待确认「开始使用」；非 null = 确认弹窗开着 */
    var pendingConfirmStart by mutableStateOf(false)
        private set

    /**
     * 待确认页的**抢占检测**：每秒查一次设备状态，发现被占用就立刻告诉用户。
     *
     * ## 为什么需要
     *
     * 用户停在「确认开启」弹窗 / 选套餐页时可能在犹豫、翻兜找卡、跟室友说话。
     * 这段时间里设备可能被别人抢先启动。如果等他点确认时才失败，
     * 体验是「点了没反应/报错」，而不是「早就该提示我」。
     *
     * ## 查到被抢占后怎么处理
     *
     * 不自动关弹窗、不自动跳页（那会让用户莫名其妙），而是：
     * 1. 把 `modeOccupiedReason` 置上 → 页面上立刻出现占用横幅
     * 2. 按钮变成禁用态（`confirmModeOrder` 里已有拦截）
     * 3. 弹窗还开着，用户能看到"为什么不能点了"
     *
     * 也就是**只降级不打断**。用户自己关掉弹窗、或换台设备。
     */
    var preemptedByOthers by mutableStateOf<String?>(null)
        private set

    private var preemptPollJob: Job? = null

    /** 上一次重发套餐倒计时通知的时刻；0 = 还没发过。用于 30 秒节流（见 [postModeOrderNotification]） */
    private var lastModeNotifyAtMs = 0L

    /**
     * 进入套餐下单页。
     *
     * 进来先做三件事：读本地 pending（可能上次下单还没结束）、拉套餐、拉余额。
     * 三者都失败也不阻塞——套餐列表空了界面会提示「正在获取套餐…」并允许重试。
     */
    fun enterModeOrderPicker(device: DeviceInfo) {
        val sn = device.snCode ?: return
        modeOrderDevice = device
        modeOrderError = null
        modeOccupiedReason = null
        modeModes = emptyList()
        selectedModeId = null
        modeOrderWalletYuan = null

        // 本地有进行中的单 → 直接进「使用中」阶段，不再让用户选套餐
        val pending = modeOrderController.pendingOrder(sn)
        modePending = pending?.takeUnless { it.isLikelyFinished(System.currentTimeMillis()) }
        if (modePending != null) {
            startModeTicker()
            // 进程被杀/重装后重新进来：把倒计时通知补回来，否则用户只剩 App 内看得见
            postModeOrderNotification(force = true)
        }

        val smallTypeId = device.smallTypeId ?: return
        sessionScope().launch {
            // 套餐列表
            try {
                val modes = modeOrderController.loadModes(sn, smallTypeId)
                modeModes = modes
                selectedModeId = ModeOrderPolicy.pickDefaultMode(modes)?.modeId
            } catch (e: Exception) {
                modeOrderError = "套餐列表获取失败，请检查网络后重试"
                AppLogger.w("套餐列表获取失败：${e.message}")
            }
            // 余额（拿不到就不显示，不影响下单）。
            // ⚠️ 必须按**设备所属板块**取：用户可能停在洗衣板块去开洗浴的热水器
            //    （或反之），照当前板块取就是拿错钱包，显示的是另一个板块的钱。
            //    判定见 DeviceBoardPolicy，借参数见 ProjectRepository.withBoardContext。
            try {
                val boardId = DeviceBoardPolicy.resolve(device.projectId, currentProjectId)
                val sameBoard = boardId == null || !DeviceBoardPolicy.needsBorrow(boardId, currentProjectId)
                val w = if (sameBoard) {
                    NetworkModule.apiService.getWalletSafe()
                } else {
                    ProjectRepository.withBoardContext(boardId!!) {
                        NetworkModule.apiService.getWalletSafe()
                    }
                }
                modeOrderWalletYuan = w?.data?.money?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { }

            // 设备占用检查。
            //
            // ⚠️ 这一步以前在详情框里靠 `refreshDeviceStatus` + `isDeviceActive` 粗判，
            // 现在套餐设备**跳过详情框**了，用户看不到任何占用提示就会直接进选套餐页。
            // 所以必须在这里查一次 `washer/status`，把「别人正在用」明确说出来。
            //
            // 三态照抄 ModeOrderController.queryStatus 的语义：
            //   Busy    → 展示占用 + 剩余时间，**禁用下单按钮**
            //   Idle    → 正常
            //   Unknown → 不拦（断网不该阻止用户看到套餐），但提示网络异常
            if (modePending == null) {
                when (val st = modeOrderController.queryStatus(sn)) {
                    is ModeStatus.Busy -> {
                        modeOccupiedReason = st.reason
                        AppLogger.i("套餐设备占用中：$sn ${st.reason}")
                    }
                    is ModeStatus.Idle -> modeOccupiedReason = null
                    is ModeStatus.Unknown -> {
                        modeOccupiedReason = null
                        modeOrderError = "网络状态不佳，暂时无法确认设备是否空闲"
                    }
                }
                // 进入待确认状态 → 开始每秒盯着这台设备，被别人抢先时立刻提示
                if (modePending == null) startPreemptWatch(sn, packageBilling = true)
            }
        }
    }

    /**
     * 进入「待确认」阶段时启动每秒状态轮询，防止别人抢先使用。
     *
     * 只在这两个时机跑：
     * - 选套餐页打开（[enterModeOrderPicker] 里调用）
     * - 确认弹窗弹出（[confirmModeOrder] 里调用）
     *
     * 一旦下单成功（有 pending）就停——那时设备是自己的，再轮询没意义。
     *
     * ponytail: 每秒一次 HTTP 打的是 `washer/status`（轻量只读）。
     * 若将来担心流量/耗电，可放宽到 2~3 秒；但「被抢」这件事用户体感上
     * 越早发现越好，1 秒是能接受的上限。上限就是 1 req/s/设备。
     */
    /**
     * 进入「待确认」阶段时启动每秒状态轮询，防止别人抢先使用。
     *
     * ## 两种计费模型查的接口不同
     *
     * | 计费模型 | 接口 | 占用判据 |
     * |---|---|---|
     * | 套餐（洗衣机/吹风机） | `washer/status` | `errorCode==307` 或 `success && data != null` |
     * | 按量（热水器/洗手台/饮水机） | `query/rateOrder/using` | `errorCode==307` 或 `success && orderNo != null` |
     *
     * 两者都是**每秒一次只读请求**，都不动钱。
     *
     * ## 什么时候跑
     *
     * - 选套餐页打开 / 确认弹窗弹出
     * - 按量计费的全屏页打开（未开始态）
     *
     * 一旦下单成功 / 开阀成功就停——那时设备是自己的了。
     *
     * ponytail: 1 req/s/设备。若将来担心流量/耗电可放宽到 2~3 秒，
     * 但「被抢」越早发现越好，1 秒是能接受的上限。
     *
     * @param sn 设备序列号
     * @param packageBilling true = 套餐设备（查 washer/status）；
     *                       false = 按量计费设备（查 queryUsing）
     */
    fun startPreemptWatch(sn: String, packageBilling: Boolean) {
        preemptPollJob?.cancel()
        preemptPollJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                // 已有自己的单 / 已经在用水 → 设备是自己的了，停止轮询
                if (modePending != null || isShowering) break
                // 页面已关闭 → 停止
                if (modeOrderDevice == null && !meteredScreenOpen) break

                val busy: String? = try {
                    if (packageBilling) {
                        // 套餐设备：三态，Unknown 不算被抢
                        when (val st = modeOrderController.queryStatus(sn)) {
                            is ModeStatus.Busy -> st.reason
                            else -> null
                        }
                    } else {
                        // 按量计费设备：走 queryUsing，判据与 ShowerController 一致
                        val q = NetworkModule.apiService.queryUsingSafe(
                            snCode = sn, auth = NetworkModule.authFields()
                        )
                        val hasOrder = q.errorCode == 307 || (q.success && q.data?.orderNo != null)
                        if (!hasOrder) null
                        else if (q.data?.isOwner == true) "你自己的单还在进行中"
                        else "别人的单"
                    }
                } catch (_: Exception) {
                    continue // 网络抖动不算"被抢占"，继续下一轮
                }

                if (busy != null) {
                    val wasEmpty = preemptedByOthers == null
                    preemptedByOthers = busy
                    // 同步到页面横幅（两处 UI 都读这个）
                    modeOccupiedReason = busy
                    if (wasEmpty) AppLogger.i("设备被抢先占用：$sn $busy")
                } else {
                    // 空闲了（对方结束了）→ 清掉提示，允许操作
                    if (preemptedByOthers != null) {
                        preemptedByOthers = null
                        modeOccupiedReason = null
                        AppLogger.i("设备已空闲，可重新操作：$sn")
                    }
                }
            }
        }
    }

    private fun stopPreemptWatch() {
        preemptPollJob?.cancel()
        preemptPollJob = null
        preemptedByOthers = null
    }

    /** 离开套餐页（不结束订单） */
    fun exitModeOrder() {
        stopModeTicker()
        stopPreemptWatch()
        modeOrderDevice = null
        modeModes = emptyList()
        selectedModeId = null
        modeOrderError = null
        modeOccupiedReason = null
        modePending = null
        modeOrderWalletYuan = null
        // ⚠️ 必须一起清：否则退出页面后确认框还挂着，
        // 用户点「确认开启」时 modeOrderDevice 已经是 null，会静默什么都不发生
        pendingConfirmMode = null
    }

    fun selectMode(modeId: Int) {
        selectedModeId = modeId
        modeOrderError = null
    }

    /**
     * 确认下单 —— **唯一的花钱入口**。
     *
     * 三重防重：`isPlacingModeOrder` 挡并发、`modePending` 挡已有订单、
     * `ModeOrderController.placeOrder` 内部还有一道本地 pending 检查。
     *
     * 余额不足**在这里拦**：实测套餐是先扣全款，余额不够服务端会失败，
     * 但那次失败已经是花完力气才发现的，不如提前说清楚。
     * （饮水机那种「不足按剩余金额预扣」的规则不适用于套餐设备，见 ModeOrderPolicy）
     */
    /**
     * 用户点了「确认下单」——**先弹确认框，不直接扣钱**。
     *
     * ## 为什么要多这一步
     *
     * `modeOrder` 一发出就扣套餐全款且不可退。而这个 App 是扫码/列表点进来的，
     * 设备可能在身边、也可能扫错了一台。让用户亲眼核对**设备名 + 套餐 + 金额 + 时长**
     * 是最后一道防线（官方 App 也是这么做的）。
     *
     * 校验（已有订单 / 被占用 / 余额不足）仍然在这里做——**先把不可能成功的拦掉**，
     * 别让用户确认完才发现余额不够。
     */
    fun confirmModeOrder(phone: String) {
        val device = modeOrderDevice ?: return
        val sn = device.snCode ?: return
        if (isPlacingModeOrder) return
        if (modePending != null) {
            modeOrderError = "这台设备已有进行中的订单"
            return
        }
        // 占用拦截：跳过详情框之后，这是最后一道「别人正在用」的防线。
        // ⚠️ 不能用 modeOccupiedReason 的旧值直接拦——它可能是几秒前的状态，
        //    但也不该忽略：宁可让用户重进页面刷新，也不要扣了钱才发现机器是别人的。
        modeOccupiedReason?.let {
            modeOrderError = "这台设备正在被使用（$it），请稍后再试"
            return
        }
        val mode = modeModes.firstOrNull { it.modeId == selectedModeId }
        if (mode == null) {
            modeOrderError = "请先选择套餐"
            return
        }

        // 余额不足拦截：只在**拿得到余额**时判断，拿不到就放行（不能因为查不到余额就不让用）
        val walletYuan = modeOrderWalletYuan?.toDoubleOrNull()
        val priceYuan = (mode.modeMoney ?: 0) / 1000.0
        if (walletYuan != null && walletYuan < priceYuan) {
            modeOrderError = "余额不足：需要 ¥%.2f，当前 ¥%.2f".format(priceYuan, walletYuan)
            return
        }

        // 校验全过 → 弹确认框，等用户点头
        modeOrderError = null
        pendingConfirmMode = mode
        // 弹窗期间也要盯着：用户犹豫时设备可能被抢
        startPreemptWatch(sn, packageBilling = true)
    }

    /** 用户在确认框里点了「取消」 */
    fun dismissConfirmMode() { pendingConfirmMode = null }

    /** 用户在确认框里点了「确认开启」——真正发下单请求 */
    fun commitModeOrder() {
        val device = modeOrderDevice ?: return
        val sn = device.snCode ?: return
        val mode = pendingConfirmMode ?: return
        pendingConfirmMode = null
        if (isPlacingModeOrder) return

        isPlacingModeOrder = true
        modeOrderError = null
        // 已经开始下单，不再需要"被抢"检测（成功的话设备就是自己的了）
        stopPreemptWatch()
        sessionScope().launch {
            try {
                when (val r = modeOrderController.placeOrder(sn, mode)) {
                    is PlaceOrderResult.Placed -> {
                        // 拿到 orderNo：进入使用中
                        refreshPendingFrom(sn, mode)
                        AppLogger.i("套餐下单成功 sn=$sn orderNo=${r.orderNo}")
                    }
                    is PlaceOrderResult.AwaitingOrderNo -> {
                        // ⚠️ 钱可能已经扣了，绝不能报失败让用户再点一次
                        refreshPendingFrom(sn, mode)
                        modeOrderError = "网络状态不佳，已提交下单，正在确认订单信息"
                        AppLogger.w("套餐下单已发请求但未拿到 orderNo，需核对 sn=$sn")
                    }
                    is PlaceOrderResult.Failed -> {
                        modeOrderError = r.message ?: "下单失败，请重试"
                        AppLogger.w("套餐下单失败 sn=$sn：${r.message}")
                    }
                }
            } catch (e: Exception) {
                // 抛异常 = 不知道扣没扣，同样不能报失败
                refreshPendingFrom(sn, mode)
                modeOrderError = "网络状态不佳，请刷新核对订单状态"
                AppLogger.e("套餐下单异常 sn=$sn", e)
            } finally {
                isPlacingModeOrder = false
            }
        }
    }

    /** 下单后重新读本地 pending（控制器已经落盘），并启动倒计时 */
    private fun refreshPendingFrom(sn: String, mode: WashMode) {
        val p = modeOrderController.pendingOrder(sn)
        modePending = p ?: PendingModeOrder(
            snCode = sn,
            modeId = mode.modeId,
            modeSeconds = mode.modeTimeQuantity ?: 0,
            modeMoneyMilli = mode.modeMoney,
            placedAtMs = System.currentTimeMillis()
        )
        startModeTicker()
        postModeOrderNotification(force = true)
    }

    /**
     * 把「套餐倒计时」那条通知按当前剩余时间重发一次。
     *
     * ## 为什么不是「发给一次就完事」
     *
     * 通知的 **Chronometer 芯片**在 ColorOS 上不渲染（真机实测，见 [Notifier.showModeOrderRunning]
     * 的注释），所以正文里必须有**文字**的剩余时间。文字不会自己走，只能靠重发刷新。
     *
     * ## 为什么不每秒发
     *
     * 上游 OPPO 流体云适配文档的铁律：严禁 Service 每秒 `notify()`（会被限流且耗电），
     * 走秒交给系统 Chronometer。所以这里固定 30 秒一次（[NotifyTextPolicy.REFRESH_INTERVAL_MS]），
     * 且通知本身带 `setOnlyAlertOnce(true)`，不会重复响铃。
     *
     * 秒级 UI 刷新仍由 [startModeTicker] 每秒跑，两件事互不影响。
     *
     * @param force true = 忽略节流立即发（下单成功、进入页面时用）
     */
    private fun postModeOrderNotification(force: Boolean = false) {
        val p = modePending
        val ctx = appContext
        val dev = modeOrderDevice

        // ⚠️ 每一步都要留日志：2026-10-24 用户实测「完全看不到倒计时通知」，
        //    排查时发现多层 `?.let` 一步失败就静默跳过，日志里连痕迹都没有，
        //    完全无法判断是「没调」还是「调了但被系统拦」。所以宁可啰嗦。
        if (p == null) {
            AppLogger.w("套餐倒计时通知跳过：modePending 为 null")
            return
        }
        if (ctx == null) {
            AppLogger.w("套餐倒计时通知跳过：appContext 为 null（initManagers 还没跑完？）")
            return
        }
        if (dev == null) {
            AppLogger.w("套餐倒计时通知跳过：modeOrderDevice 为 null")
            return
        }
        // 结束时刻 = 下单时刻 + 套餐时长。用**绝对时刻**而不是「剩余秒数」，
        // 这样每次重发算出来的倒计时是连续的，不会每次跳回满格。
        val endAtMs = p.placedAtMs + p.modeSeconds * 1000L
        val remainingSec = (((endAtMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)).toInt()
        // 节流间隔随剩余时间变：前段 30 秒（省电），最后 2 分钟 1 秒（秒级胶囊要准）
        if (!force && !NotifyTextPolicy.shouldRefresh(
                lastModeNotifyAtMs, System.currentTimeMillis(), remainingSec
            )
        ) {
            return
        }

        val sent = Notifier.showModeOrderRunning(
            ctx,
            deviceName = dev.displayName,
            modeName = modeNameFor(p),
            endAtMs = endAtMs,
            priceYuan = ModeOrderPolicy.formatPrice(p.modeMoneyMilli)
        )
        if (sent == null) {
            AppLogger.w("套餐倒计时通知未发出：showModeOrderRunning 返回 null")
        } else {
            lastModeNotifyAtMs = System.currentTimeMillis()
            AppLogger.i("套餐倒计时通知已发出 id=${Notifier.ID_MODE_ORDER}，剩余=${p.remainingSeconds(lastModeNotifyAtMs)}秒")
            // 接上 Notifier 自己的自刷新循环。
            //
            // ⚠️ 为什么不能只靠 startModeTicker：ticker 在 viewModelScope 里，
            //    App 被划掉就停，那时通知会一直挂着、Chronometer 走成负数
            //    （实测 2026-10-24）。Notifier 的循环用 Handler，同样会随进程死，
            //    但它至少**不依赖界面存在**——用户退出套餐页后仍然继续刷，
            //    到点还会自己撤。
            //    进程真被杀了就靠 `setTimeoutAfter`（系统闹钟）+ 启动时的
            //    `cancelStaleModeOrder` 兜底。
            if (force) {
                Notifier.startModeOrderAutoRefresh(
                    ctx,
                    deviceName = dev.displayName,
                    modeName = modeNameFor(p),
                    endAtMs = endAtMs,
                    priceYuan = ModeOrderPolicy.formatPrice(p.modeMoneyMilli)
                )
            }
        }
    }

    /** 套餐名：优先用当前设备套餐列表里的名字，找不到就用 id 兜底 */
    private fun modeNameFor(p: PendingModeOrder): String =
        modeModes.firstOrNull { it.modeId == p.modeId }?.modeName ?: "套餐"

    /**
     * 刷新核对：用户点「设备已结束请点这里」时调。
     *
     * 套餐设备**没有结束接口**，服务端到点关停也不通知我们，
     * 所以「结束」只能是：本地时间到 + 服务端查询确认没有进行中的单。
     */
    fun refreshModeOrder(sn: String? = null) {
        val sn2 = sn ?: modeOrderDevice?.snCode ?: return
        sessionScope().launch {
            try {
                when (val st = modeOrderController.queryStatus(sn2)) {
                    is ModeStatus.Idle -> {
                        finishModeOrderLocally(sn2)
                        AppLogger.i("套餐订单已结束（服务端确认无进行中订单） sn=$sn2")
                    }
                    is ModeStatus.Busy -> {
                        // 还在用：刷新余额，别把已花的钱当没花
                        reloadWalletQuiet()
                    }
                    is ModeStatus.Unknown -> {
                        modeOrderError = "网络状态不佳，请稍后重试"
                    }
                }
            } catch (e: Exception) {
                modeOrderError = "网络状态不佳，请稍后重试"
                AppLogger.w("刷新套餐状态失败：${e.message}")
            }
        }
    }

    /**
     * 本地把套餐单收尾：清 pending、停 ticker、撤通知、**清掉主页残留的「使用中」**。
     *
     * ## 为什么要清 activeOrders
     *
     * 主页那张「使用中的设备」卡片读的是 `PrefsHelper.getActiveOrders()`（热水器链路的数据结构），
     * 套餐单从来不写它。但用户实测发现：**套餐用完之后那张卡片还挂在那里**，
     * 点「恢复」会走 `startShower` 的分流又回到套餐页——看起来像"结束不了"。
     *
     * 真实原因是那台设备之前用热水器留下的记录没被清。所以套餐单收尾时顺手按 snCode
     * 清掉它（只清这一台，别动别的设备的在途订单）。
     */
    private fun finishModeOrderLocally(snCode: String) {
        modeOrderController.clearPending(snCode)
        modePending = null
        modeOrderError = null
        // 清零节流：下一单要立刻能发通知，不能等上一个 30 秒窗口
        lastModeNotifyAtMs = 0L
        stopModeTicker()
        appContext?.let { Notifier.cancelModeOrder(it) }

        // 清主页残留：这台设备的 activeOrder 记录（如果有）
        if (activeOrders.any { it.snCode == snCode }) {
            val remaining = activeOrders.filterNot { it.snCode == snCode }
            PrefsHelper.saveActiveOrders(remaining)
            syncActiveOrdersFromPrefs()
            AppLogger.i("套餐收尾：已清掉 $snCode 的残留「使用中」记录")
        }
        // 「上次使用设备」留着不动——它是历史记录，清掉反而让用户找不到刚才用的那台
    }

    private suspend fun reloadWalletQuiet() {
        try {
            val w = NetworkModule.apiService.getWalletSafe()
            modeOrderWalletYuan = w.data?.money?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { }
    }

    private fun startModeTicker() {
        stopModeTicker()
        modeOrderTickerJob = viewModelScope.launch {
            var finishedNotified = false
            while (true) {
                val now = System.currentTimeMillis()
                modeOrderTick = now
                val p = modePending
                if (p != null && p.isLikelyFinished(now)) {
                    // 到点只提示一次，然后**自动去服务端核对**，不干等用户点刷新。
                    //
                    // 为什么不在到点就直接本地清掉：服务端到点关停不通知我们，
                    // 本地算的时间可能早了几秒（手机时钟、网络延迟），
                    // 直接清会让用户看到"结束了"但机器还在转。
                    // 所以先查一次服务端——它说没单了才是真结束。
                    if (!finishedNotified) {
                        finishedNotified = true
                        appContext?.let {
                            Notifier.showModeOrderFinished(it, modeOrderDevice?.displayName ?: "设备")
                        }
                        refreshModeOrder(p.snCode)
                    }
                } else if (p != null) {
                    // 还在用：按 30 秒节奏刷新通知的**文字**剩余时间。
                    // 内部有节流（NotifyTextPolicy.shouldRefresh），这里每秒调也不会真的每秒发。
                    postModeOrderNotification()
                }
                delay(1000L)
            }
        }
    }

    private fun stopModeTicker() {
        modeOrderTickerJob?.cancel()
        modeOrderTickerJob = null
    }

    /**
     * 账单是否**至少成功拉取过一次**。
     *
     * 余额是「用户填的初始余额 − 账单里的消费」算出来的。账单到位之前 billList 是空的，
     * 减数为 0，于是界面上会先亮出那个初始值、等账单到了再跳到真实值——
     * 看着就像余额自己变了一下（用户复现的「10.60 跳变」就是这个）。
     * 所以没加载完之前不显示估算结果，宁可先显示「—」。
     */
    var billsLoaded by mutableStateOf(false)
        private set
    var useCodeData by mutableStateOf<UseCodeData?>(null)
        private set

    /**
     * 使用码接口有没有**成功**拉过一次。
     *
     * 界面靠它区分两种「没有码」：还没拉到（显示「加载中」）和
     * 拉到了但服务端说这人没有码（`useCode` 为 null，显示「尚未领取」）。
     * 没有这个标志的话，没领过码的人会永远停在「加载中」。
     */
    var useCodeLoaded by mutableStateOf(false)
        private set

    /**
     * 账号信息（姓名 / 学号 / 绑定状态）。null = 还没拉到。
     *
     * 存在的意义在于**能触发重组**：直接让界面读 `PrefsHelper.userName` 的话，
     * 拉完数据界面不会自己刷新（Prefs 不是 Compose State）。
     */
    var accountInfo by mutableStateOf<AccountInfo?>(null)
        private set

    /**
     * 一卡通**真实余额**。null = 拿不到（没签约免密支付、或者还没拉过），
     * 这时界面回退到「初始余额 − 账单消费」的本地估算。
     *
     * 初值取 Prefs 里上次拉到的值：从桌面小组件点进账单页会**新建一个 ViewModel**，
     * 等接口回来之前这段时间界面会退回估算、还会冒出「手动填写」按钮，
     * 看着像数据丢了。先亮上次的值，拉到新的再覆盖。
     */
    var campusBalance by mutableStateOf(PrefsHelper.campusBalance.toDoubleOrNull())

    /**
     * 趣智校园钱包余额（`/account/wallet`）。
     *
     * 一卡通拿不到时的第二来源，见 [com.quzhi.lite.data.BalanceEstimator]。
     * 界面判断「有没有真实余额」时必须把它算进去，否则没接一卡通的学校
     * 会被误判成「只能估算」。
     */
    var walletBalance by mutableStateOf(PrefsHelper.walletBalance.toDoubleOrNull())
        private set

    /**
     * 当前账号的手机号——**界面唯一该读的地方**。
     *
     * 以前是 MainActivity 里一个 `rememberSaveable`，登录成功时写一次就再也不动了，
     * 所以在「我的」页改完手机号，卡片上还显示旧的，得等下次重新登录才更新。
     * 现在换成 ViewModel 里的状态：登录、改号、退出三处都写它，界面自动跟着刷。
     */
    var phone by mutableStateOf(PrefsHelper.telephone)

    /**
     * 学校名（`/project/info/triple` 的 `projectName`）。
     *
     * 同 [accountInfo]：写进 Prefs 不会触发重组，界面得读这个 State 才刷得出来。
     * 初值取 Prefs，老用户不用等接口回来就有值显示。
     */
    var schoolName by mutableStateOf(PrefsHelper.schoolName)
        private set

    /** 存一份 AppContext 供刷新桌面小组件用（ViewModel 不应该长期持有 Activity） */
    private var appContext: Context? = null

    // ── 多板块（项目）──
    //
    // 一个手机号可能绑定多个独立项目（4000 洗浴 / 3356 洗衣饮水吹风），
    // 各有自己的 accountId、钱包、账单、设备。切板块 = 整体换一套认证参数 +
    // 清掉按项目维度缓存的数据，见 data/ProjectRepository.switchTo。

    /** 绑定过的板块（已过滤掉缺 accountId 的脏数据） */
    var projects by mutableStateOf<List<UserProject>>(emptyList())
        private set

    /** 当前板块的 projectId（取自认证参数，不是"用户想要的那个"） */
    var currentProjectId by mutableStateOf(PrefsHelper.projectId.toLongOrNull())
        private set

    /** 已记住的项目名。`/project/info/triple` 一次只能问当前板块，见 ProjectRepository */
    var projectNames by mutableStateOf<Map<Long, String>>(emptyMap())
        private set

    /** 正在切板块（切换要点网络 + 重拉数据，期间给个 loading，别让人连点） */
    var isSwitchingProject by mutableStateOf(false)
        private set

    /**
     * 板块切换成功的次数。
     *
     * 切完要重扫设备，而扫描需要权限、只能由界面发起（见 MainScreen 的
     * `scanWithPermission`），所以这里只留一个触发器让界面监听，不在 ViewModel
     * 里直接调 [startScan]——没有权限时那会直接抛 SecurityException。
     */
    var projectSwitchTick by mutableStateOf(0)
        private set

    /** 绑定了 2 个以上板块才显示切换入口；单板块用户不该被塞一个点了没用的按钮 */
    val showProjectSwitcher: Boolean get() = ProjectSelectionPolicy.hasMultiple(projects)

    /**
     * 按套餐计费的设备（洗衣机 / 吹风机）控制器。
     *
     * ## 为什么要在这里持有
     *
     * 它的「进行中的单」要跨进程存活，而订单号只能从 MQTT 推送拿——
     * 所以必须有一个长生命周期的实例接住 [handleMqttMessage] 转发过来的消息。
     *
     * ## ⚠️ 这个类会**真扣钱**
     *
     * 调用 [ModeOrderController.placeOrder] 就等于让服务端预扣套餐全款，且没有退款接口。
     * UI 层必须先做二次确认，见该类注释里的三条要求。
     */
    val modeOrderController: ModeOrderController by lazy {
        ModeOrderController(
            readPref = { PrefsHelper.readRaw(it) },
            writePref = { k, v -> PrefsHelper.writeRaw(k, v) }
        )
    }

    fun initManagers(context: Context) {
        appContext = context.applicationContext
        if (scanner == null) scanner = BluetoothScanner(context, { addDevice(it) }, { ok -> onScanTimeout(ok) })
        if (mqttManager == null) mqttManager = MqttManager(context, { handleMqttMessage(it) })

        lastDeviceName = PrefsHelper.lastDeviceName
        lastDeviceMac = PrefsHelper.lastDeviceMac
        lastDeviceSnCode = PrefsHelper.lastDeviceSnCode
        lastDeviceEmoji = PrefsHelper.lastDeviceEmoji
        boundRoom = PrefsHelper.boundRoom
        // 收藏是持久列表（不是易失的「上次使用」），冷启动就要能渲染
        reloadFavorites()

        // 恢复所有活跃订单
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear()
        activeOrders.addAll(saved)
        saved.forEach { activeDeviceSnCodes.add(it.snCode) }
    }

    override fun onCleared() {
        super.onCleared()
        BleArbiter.appAlive = false
        stopTimer()
        sessionJob.cancel()
        mqttManager?.disconnect(); scanner?.stopScan()
    }

    fun startScan() {
        nearbyDevices.clear(); activeDeviceSnCodes.clear()
        // 新一轮扫描 = 允许对每台设备再查一次（见 addDevice）
        queriedThisScan.clear()
        isScanning = true; scanStartTime = System.currentTimeMillis(); scanner?.startScan()
    }
    /**
     * 扫描结束。
     *
     * @param ok 是否正常扫完。**只有正常扫完才更新小组件的快照**——
     *           扫描失败（蓝牙被关、适配器异常）时结果为空是"没扫成"，不是"附近没有设备"，
     *           拿它去覆盖会把上次扫到的好数据清空，小组件就白白变成「未发现热水器」了。
     */
    fun onScanTimeout(ok: Boolean) {
        val e = System.currentTimeMillis() - scanStartTime
        if (e < 600) sessionScope().launch { delay(600 - e); isScanning = false }
        else isScanning = false
        if (!ok) return

        // ⚠️ 扫描**结束后**才按信号强度排一次序。
        //
        // 原先列表是纯插入顺序（谁先被扫到谁在前面）。寝室场景只有一两台设备时无所谓，
        // 但**公共澡堂**完全不是这回事：一间屋十二个位置、几个屋子连在一起，
        // 一次能扫到几十上百台，按扫描顺序排列等于让用户在一堆设备名里大海捞针，
        // 根本找不到自己站的那个位置。
        //
        // 按 RSSI 降序后排在最前面的，就是离手机最近的那一台——澡堂里也就是
        // 用户面前这个位置，通常正是他要开的那个。
        //
        // ⚠️ **必须是扫描结束后排，不能实时排**。扫描过程中 RSSI 一直在小幅波动，
        // 实时重排会让列表不停跳动，用户正要点的时候被挪走，直接点错设备——
        // 点错在澡堂里意味着替别人付了水费。结束后排一次既有正确的顺序，又不会跳。
        val sorted = nearbyDevices.sortedByDescending { it.rssi }
        nearbyDevices.clear()
        nearbyDevices.addAll(sorted)

        cacheNearbyForWidget()
    }

    // ── 扫码绑定 ──
    fun scanBind(snCode: String) {
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(snCode)
                if (resp.success && resp.data != null) {
                    val info = resp.data
                    // 三条入口共用同一个分流：套餐设备直接进选套餐页，
                    // 按量计费设备仍先弹详情（点「开始使用」会立刻出水计费）
                    routeAfterDeviceInfo(info, fromScan = true)
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
    //
    // ⚠️ 这条路径**不能静默失败**。
    //
    // `/device/info/mac` 查不到设备时返回的是
    // `success=true, errorCode=0, errorMessage="成功", data=null`——它既不是网络错误，
    // 也不是"报错"，只是服务端说"没有这台设备"。而老代码的失败分支只调了 [checkKick]，
    // 它只认「登录 / token / 失效 / 过期 / 认证」这类词，"成功"一个都不匹配，
    // 于是**既不弹窗、也不提示、也没有日志**：用户点一次设备卡片，屏幕上什么都不发生。
    // 用户报的「点击热水器卡片跳不到开关页面」就是这个观感。
    //
    // 现在三种结果分开说：查到了 → 弹详情；服务端说没有这台设备 → 明说；
    // 服务端明确报错 → 把原话转给用户。
    /**
     * 拿到设备信息之后**该带用户去哪**——所有入口的唯一归宿。
     *
     * ## 为什么需要这一层
     *
     * 进设备有三条路：首页卡片点击（[fetchDeviceInfo]）、扫码（[scanBind]）、
     * 以及以后可能新增的入口。以前每条路各写一遍「弹详情」，
     * 想改行为就得改三处，漏一处就出现"有的入口跳过了有的没跳过"。
     *
     * ## 分流规则：按**计费模型**，和 [startShower] 保持一致
     *
     * | 计费模型 | 去哪 | 为什么 |
     * |---|---|---|
     * | `PACKAGE`（洗衣机 / 吹风机） | **直接进选套餐页** | 选套餐这一步不扣钱，多点一次无意义 |
     * | `METERED`（热水器 / 洗手台 / 饮水机） | **先弹详情框** | 「开始使用」会**立刻开阀出水并计费**，不能误触 |
     *
     * ⚠️ **按量计费设备绝不能跳过详情框**：点一下设备卡片就出水，
     * 用户滑动列表时手抖一下就是钱，而且水已经开始流了。
     * 套餐设备无所谓——选套餐页只是列表，不点「确认下单」不会扣款。
     *
     * @param fromScan 是否来自扫码。目前两条路行为一致，留参数是为以后需要区分时不必改签名
     */
    private fun routeAfterDeviceInfo(info: DeviceInfo, fromScan: Boolean = false) {
        // 记成「上次使用设备」（两条路都要记，原来只有扫码那条记了）
        PrefsHelper.lastDeviceSnCode = info.snCode
        PrefsHelper.lastDeviceMac = info.macAddress
        PrefsHelper.lastDeviceName = info.displayName
        PrefsHelper.lastDeviceRawName = info.deviceName
        PrefsHelper.lastDeviceEmoji = info.typeEmoji

        selectedDevice = info
        if (info.isPackageBilling) {
            // 套餐设备：跳过详情框，直接进选套餐
            showDeviceDetail = false
            meteredScreenOpen = false
            enterModeOrderPicker(info)
            AppLogger.i("进入套餐下单页（跳过详情）：${info.displayName} fromScan=$fromScan")
        } else {
            // 按量计费设备：**全屏操作页**（不再弹小对话框）。
            //
            // 「开始使用」按钮在这个页面里，点了先弹确认框，
            // 用户确认后才真正 openValve——见 requestStartMetered / confirmStartMetered。
            showDeviceDetail = false
            openMeteredScreen(info)
            AppLogger.i("进入按量计费全屏页：${info.displayName} fromScan=$fromScan")
        }
    }

    // ── 按量计费：全屏页里的「开始使用」两步走 ──

    /**
     * 进入按量计费设备的全屏页时调用（由 `routeAfterDeviceInfo` 触发）。
     *
     * 除了打开页面，还要**开始防抢占轮询**：按量计费设备同样会被别人抢先开走，
     * 而用户在这个页面上可能停留很久（犹豫、找卡、接电话）。
     * 不盯着的话，点「开始使用」才会发现 307。
     */
    private fun openMeteredScreen(info: DeviceInfo) {
        meteredScreenOpen = true
        val sn = info.snCode
        if (!sn.isNullOrBlank()) startPreemptWatch(sn, packageBilling = false)
        // 余额不在这里取：selectedDevice 的 setter 已经统一负责（见那里的注释）
    }

    /**
     * 设备页面要显示的「当前余额」。
     *
     * 和 [walletBalance]（当前板块的钱包）分开存：用户可能停在洗衣板块却点开一台
     * 热水器，那台热水器属于洗浴板块，余额得显示洗浴钱包的钱。
     * 直接复用 [walletBalance] 就是他报的「余额不准」——显示的是另一个板块的钱。
     *
     * 值为 null 表示「还没取到/判断不出」，界面此时不显示这一行，宁可不显示也不错显示。
     */
    var deviceBoardWallet by mutableStateOf<Double?>(null)
        private set

    /** 设备所属板块（判定逻辑见 [DeviceBoardPolicy]）。null = 判断不出 */
    var deviceBoardProjectId by mutableStateOf<Long?>(null)
        private set

    /**
     * 按**设备自己的板块**取钱包余额。
     *
     * 三步：
     * 1. 用设备自报的 `projectId`（`/device/info/mac` 下发）定板块
     * 2. 就是当前板块 → 直接用已有的 [walletBalance]，不重复请求
     * 3. 跨板块 → 借那个板块的认证参数查一次（[ProjectRepository.withBoardContext]，
     *    finally 里必定还原，不会把全局认证状态留错）
     */
    private fun loadDeviceBoardWallet(info: DeviceInfo) {
        val target = DeviceBoardPolicy.resolve(
            deviceProjectId = info.projectId,
            currentProjectId = currentProjectId
        ) ?: run {
            // 设备没报板块、当前板块也没有：退回洗浴兜底。
            // ponytail: 兜底是硬编码（本项目实测校 4000 洗浴），换学校可能不对——
            //           但比显示一个确定错误的钱包好，且只在服务端不下发 projectId 时才会走到。
            deviceBoardProjectId = DeviceBoardPolicy.BATH_PROJECT_ID
            deviceBoardWallet = null
            return
        }
        deviceBoardProjectId = target

        if (!DeviceBoardPolicy.needsBorrow(target, currentProjectId)) {
            // 同板块：界面上的钱包余额本来就是它
            deviceBoardWallet = walletBalance
            return
        }

        deviceBoardWallet = null
        sessionScope().launch {
            val money = ProjectRepository.withBoardContext(target) {
                NetworkModule.apiService.getWalletSafe().data?.money?.toDoubleOrNull()
            }
            // 借数据期间用户可能已经退出页面/换了设备，此时别把结果盖到新状态上
            if (deviceBoardProjectId == target) deviceBoardWallet = money
        }
    }

    /** 用户点了「开始使用」→ **只弹确认框**，不开阀 */
    fun requestStartMetered() {
        if (isStartingShower) return
        val sn = selectedDevice?.snCode
        if (sn.isNullOrBlank()) {
            toastMessage = "设备信息不完整"
            return
        }
        // 已被抢占时不该弹确认框——直接告诉用户，别让他白走一遍确认流程
        preemptedByOthers?.let {
            toastMessage = "这台设备正被使用（$it），请稍后再试"
            return
        }
        pendingConfirmStart = true
    }

    /** 确认框里点了「确认开启」→ 真正开阀 */
    fun confirmStartMetered(phone: String) {
        pendingConfirmStart = false
        if (isStartingShower) return
        // 复用现有热水器开阀链路：它已经处理了蓝牙表分流、306 引导、MQTT、
        // 恢复已有订单等一整套，不要在这里重写。
        //
        // ⚠️ startShower 是**异步**的（内部 sessionScope().launch），并且它自己会维护
        // `isStartingShower`。所以这里**不要**设 isStartingMetered 再立刻复位——
        // 那样按钮会闪一下就恢复，用户以为没点上就可能再点一次。
        // 展示用的 busy 状态直接读 `isStartingShower`（见 meteredBusy）。
        // 已经走到开阀这一步，不用再盯"被抢"了
        stopPreemptWatch()
        startShower(phone)
    }

    /** 按量计费页按钮的忙碌态 = 正在开阀中。转发给 UI，避免 UI 直接摸内部字段 */
    val meteredBusy: Boolean get() = isStartingShower

    fun dismissConfirmStart() { pendingConfirmStart = false }

    /** 关掉按量计费全屏页，回首页 */
    fun closeMeteredScreen() {
        meteredScreenOpen = false
        pendingConfirmStart = false
        stopPreemptWatch()
        // 如果没在使用中，把选中设备清掉，避免下次进来看到上一个的残留
        if (!isShowering) selectedDevice = null
    }

    // ── 收藏 ──

    /** 收藏列表的内存副本（启动时从 Prefs 读，增删后同步写回） */
    var favorites by mutableStateOf<List<FavoriteDevice>>(emptyList())
        private set

    fun reloadFavorites() {
        favorites = PrefsHelper.favorites()
    }

    /** 当前打开的设备是否已收藏 */
    fun isFavorite(snOrMac: String?): Boolean {
        if (snOrMac.isNullOrBlank()) return false
        return favorites.any { it.mac.equals(snOrMac, true) || it.snCode.equals(snOrMac, true) }
    }

    /**
     * 收藏 / 取消收藏当前打开的**任意设备**（套餐页或按量计费页都走这里）。
     *
     * 字段全部落盘（含 bigTypeId），所以收藏列表**离线也能渲染**、
     * 也能直接按计费模型分流——不用联网回查设备台账。
     */
    fun toggleFavoriteCurrent() {
        val dev = modeOrderDevice ?: selectedDevice ?: return
        doToggleFavorite(dev)
    }

    /** 收藏列表里点某项 → 按计费模型分流（和扫码/列表点击同一个入口） */
    fun openFavorite(fav: FavoriteDevice) {
        // 先把快照还原成 DeviceInfo 再走统一入口。快照里带 projectId（见 FavoriteDevice），
        // 所以设备页能按正确板块取余额；不带的话会退回当前板块、显示错的钱包。
        routeAfterDeviceInfo(fav.toDeviceInfo(), fromScan = true)
        // ⚠️ 老收藏条目没有 projectId（这个字段是后加的，Gson 会留成 null）。
        //    此时上面那次路由拿不到设备归属，余额会退回当前板块。
        //    这里补一次台账查询把归属校正回来：只影响老数据，新收藏不受影响；
        //    查失败也不影响用户正常使用（余额按当前板块显示，和改之前一样）。
        if (fav.projectId == null) {
            sessionScope().launch {
                try {
                    val info = DeviceInfoCache.load(fav.mac).data
                    // 用户可能已经退出这个页面/换了设备，别把结果盖到新状态上
                    if (info?.projectId != null && selectedDevice?.macAddress == fav.mac) {
                        // 赋值即触发余额重算（setter 里统一处理）
                        selectedDevice = selectedDevice?.copy(
                            projectId = info.projectId,
                            projectName = info.projectName
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 长按「附近设备」条目 → 收藏 / 取消收藏。
     *
     * 附近设备的 [NearbyDevice] 只有广播里带的 MAC 和名字，**没有 bigTypeId**，
     * 而收藏必须存计费模型判据（否则离线时无法分流）。所以这里先查一次设备台账：
     *
     * - 缓存里有（[nearbyDevices] 的 `deviceInfo`）→ 直接用，不发请求
     * - 否则 `DeviceInfoCache.load` 查一次
     * - 查不到就提示，**不收藏**（收藏一条只有 MAC 的记录没意义，离线渲染不出名字）
     */
    fun favoriteNearbyDevice(device: NearbyDevice) {
        val cachedInfo = device.deviceInfo
        if (cachedInfo != null) {
            doToggleFavorite(cachedInfo)
            return
        }
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(device.mac)
                val info = resp.data
                if (resp.success && info != null) {
                    doToggleFavorite(info)
                } else {
                    toastMessage = resp.displayMessage ?: "获取设备信息失败，无法收藏"
                }
            } catch (e: Exception) {
                toastMessage = "获取设备信息失败，无法收藏"
                AppLogger.w("收藏前查设备台账失败：${device.mac} ${e.message}")
            }
        }
    }

    /** 实际的收藏切换（字段已齐） */
    private fun doToggleFavorite(info: DeviceInfo) {
        val mac = info.macAddress
        if (mac.isBlank() || info.snCode.isNullOrBlank()) {
            toastMessage = "设备信息不完整，无法收藏"
            return
        }
        val existed = FavoriteStore.contains(favorites, mac)
        val next = if (existed) FavoriteStore.remove(favorites, mac)
        else FavoriteStore.add(favorites, info.toFavorite(System.currentTimeMillis()))
        favorites = FavoriteStore.sorted(next)
        PrefsHelper.saveFavorites(favorites)
        toastMessage = if (existed) "已取消收藏" else "已收藏：${info.displayName}"
        AppLogger.i("${if (existed) "取消收藏" else "收藏"}设备：${info.displayName}")
    }

    fun fetchDeviceInfo(mac: String) {
        val cached = nearbyDevices.find { it.mac == mac }?.deviceInfo
        if (cached != null) {
            routeAfterDeviceInfo(cached)
            sessionScope().launch { refreshDeviceStatus(cached.snCode) }
            return
        }
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
                if (resp.success && resp.data != null) {
                    routeAfterDeviceInfo(resp.data)
                    refreshDeviceStatus(resp.data.snCode)
                } else {
                    checkKick(resp.displayMessage)
                    toastMessage = when {
                        // 服务端明确报错（被挤下线由上面的 checkKick 负责），照原话说
                        !resp.success -> resp.displayMessage ?: "获取设备信息失败，请稍后重试"
                        // success=true 但 data=null：服务端确认没有登记这台设备
                        else -> "服务端没有这台设备的记录（$mac）"
                    }
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
                    // 以前这里什么都不说，异常被整个吞掉，同样是"点了没反应"
                    "获取设备信息失败，请稍后重试"
                }
            }
        }
    }

    private suspend fun refreshDeviceStatus(snCode: String) {
        try {
            val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
            if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                activeDeviceSnCodes.add(snCode)
                // ⚠️ 按 snCode 记录归属（不能写全局变量：异步响应会串到别的设备上）
                val owner = q.data?.isOwner ?: true
                ownerBySn[snCode] = owner
                // 只有自己的订单才加入 activeOrders
                if (owner && activeOrders.none { it.snCode == snCode }) {
                    val orderNo = q.data?.orderNo ?: ""
                    val deviceInfo = nearbyDevices.find { it.deviceInfo?.snCode == snCode }?.deviceInfo
                    if (deviceInfo != null) {
                        activeOrders.add(ActiveOrder(snCode, orderNo, deviceInfo.displayName, deviceInfo.macAddress, deviceInfo.typeEmoji, deviceInfo.withholdMoney))
                        saveOrders()
                    }
                }
                // 设备上确实有订单，且**是自己的** → 之前那个「占用中」已经不成立了。
                // （不是自己的就是真被占着，标记该留着，不用管。）
                if (owner) clearOccupiedIfStale(snCode)
            } else {
                // 服务端说这台上**没有订单**：不管之前记没记过「占用中」，现在都空了。
                ownerBySn[snCode] = true
                clearOccupiedIfStale(snCode)
            }
        } catch (_: Exception) {}
    }

    /**
     * 这台设备已经不归「占用中」管了，就把标记清掉并让桌面跟着刷。
     *
     * 补的是这么一条路径：小组件上点开阀被拒 → 桌面记下「占用中」→ 用户打开 App
     * 点进设备详情 → `refreshDeviceStatus` 已经查到设备空了 → **但小组件那边没人通知**，
     * 桌面会一直挂着「占用中」，直到用户再去点一次小组件的开水按钮。
     *
     * 现在 App 这边一旦确认设备空了就顺手把它抹掉。`PrefsHelper` 里还有一个
     * 3 分钟的兜底失效，防的是「用户此后既不打开 App、也不点小组件」那种情况。
     */
    private fun clearOccupiedIfStale(snCode: String) {
        if (PrefsHelper.occupiedSnCode != snCode) return
        PrefsHelper.clearOccupied()
        AppLogger.i("设备已空闲（$snCode），清掉小组件的「占用中」标记")
        refreshWidgets()
    }

    fun startLastDevice(phone: String) {
        val sn = lastDeviceSnCode.ifEmpty { return }
        // 找对应的活跃订单
        val order = activeOrders.find { it.snCode == sn }
        if (order != null) {
            sessionScope().launch {
                try {
                    val resp = DeviceInfoCache.load(order.deviceMac)
                    if (resp.success && resp.data != null) {
                        // 赋值即触发余额重算（setter 里统一处理，按设备所属板块）
                        selectedDevice = resp.data
                        startShower(phone)
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
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data
                    startShower(phone)
                } else {
                    toastMessage = "获取设备信息失败"
                }
            } catch (e: Exception) {
                toastMessage = "获取设备信息失败"
            }
        }
    }

    /**
     * 点「上次使用设备」卡片 → 走和收藏/扫码同一个入口，**不直接开阀**。
     *
     * ## 为什么不再调 [startShower]
     *
     * 原来那张卡片右下角是「开始」按钮，点一下直接 `startShower` → `openValve`：
     * 按量计费设备会**立刻出水并开始计费**。这是列表里最容易误触的捷径
     * （用户在首页滑动时手抖碰到卡片就出水）。
     *
     * 现在改成和收藏项一致：先查设备台账，再交给 [routeAfterDeviceInfo] 分流——
     * 套餐设备进选套餐页、按量计费进全屏页（那里还要再点「开始使用」+ 确认框）。
     *
     * ## 活跃订单仍然保持「恢复」语义
     *
     * 如果这台设备**正在使用**（在 [activeOrders] 里），说明用户已经开了水/下了单，
     * 这时点卡片的意思是"回到那个使用页"，不是重新开一次。所以这种情况下直接
     * 恢复界面状态，不走确认流程。
     */
    fun openLastDevice(phone: String) {
        val order = activeOrders.find { it.snCode == lastDeviceSnCode }
        if (order != null) {
            // 有活跃订单：恢复使用页（不重新开阀，不重新扣费）
            sessionScope().launch {
                try {
                    val resp = DeviceInfoCache.load(order.deviceMac)
                    val info = resp.data
                    if (resp.success && info != null) {
                        // 赋值即触发余额重算（setter 里统一处理，按设备所属板块）
                        selectedDevice = info
                        if (info.isPackageBilling) {
                            enterModeOrderPicker(info)
                        } else {
                            // 按量计费且已在用：直接把界面挂回使用中
                            meteredScreenOpen = true
                            showDeviceDetail = false
                            AppLogger.i("恢复按量计费使用页：${info.displayName}")
                        }
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
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
                val info = resp.data
                if (resp.success && info != null) {
                    routeAfterDeviceInfo(info)
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
        if (snCode.isNullOrBlank()) { showerError = ShowerError("设备信息不完整", ShowerErrorKind.BAD_DEVICE); return }

        // ── 分流点：按**计费模型**，不按设备类型 ──
        //
        // 套餐计费（洗衣机 / 吹风机）走完全独立的通道：不能 openValve（那是按量计费的开阀），
        // 也不能进 ShowerScreen（那里有「结束使用」按钮，而套餐设备没有停止接口）。
        // 判据见 ModeOrderPolicy.billingModel（纯函数、有单测）。
        //
        // 这里放在最前面：蓝牙分支、开阀、MQTT 连接那一整套都不该对套餐设备执行。
        if (device.isPackageBilling) {
            showDeviceDetail = false
            enterModeOrderPicker(device)
            return
        }

        isStartingShower = true
        sessionScope().launch {
            try {
                showerError = null
                showerSnCode = snCode

                // ① 用户之前确认过这台需要蓝牙（或服务端明确标了 isBle）→ 直接走蓝牙，
                //    不再白试一次云端、再吃一句 306
                // ② 否则按普通设备走云端；万一回 306，会在下面弹框问用户要不要改用蓝牙
                if (device.needsBluetoothControl || PrefsHelper.isBleDevice(snCode)) {
                    startBleShower(device, phone, snCode)
                    return@launch
                }

                // 「查询是否已有订单 → 开阀 → 轮询确认 → 落盘」这套流程在 ShowerController 里，
                // 桌面小组件也走同一份逻辑；这里只负责 MQTT、界面状态与结果提示
                mqttManager?.connect(phone)
                val outcome = ShowerController.openValve(snCode, device)

                when (outcome) {
                    is OpenOutcome.Resumed -> {
                        syncActiveOrdersFromPrefs()
                        // 恢复订单：从持久化恢复自动关停剩余时间
                        enterShowerState(outcome.orderNo, snCode, device, PrefsHelper.getAutoDisconRemain(snCode))
                    }

                    is OpenOutcome.Opened -> {
                        syncActiveOrdersFromPrefs()
                        enterShowerState(null, snCode, device, outcome.autoDiscon)
                        startOrderPoll(snCode)
                    }

                    /**
                     * 设备被别人用着。
                     *
                     * App 内正常情况下走不到这里——设备详情弹窗早就用 `isOwner`
                     * 把按钮置灰了。留着这个分支是为了：万一以后从别的入口绕进来
                     * （比如扫码头、小组件深链），也不会把别人的订单当成自己的。
                     */
                    is OpenOutcome.InUseByOthers -> {
                        showerError = ShowerError(outcome.message, ShowerErrorKind.IN_USE_BY_OTHERS)
                        mqttManager?.disconnect()
                    }

                    is OpenOutcome.Failed -> {
                        // 306「设备不在线」：**这台很可能就是蓝牙表**（云端到不了它）。
                        // 弹框问用户要不要改用手机蓝牙——这是唯一可靠的判别方式：
                        // 服务端不给字段、参考项目也不判断，只有用户站在设备旁才知道。
                        if (outcome.errorCode == ERROR_DEVICE_OFFLINE &&
                            device.macAddress.isNotBlank()
                        ) {
                            bleRetryDevice = device
                        }
                        showerError = ShowerError(outcome.message, ShowerErrorKind.SERVER_REJECTED)
                        checkKick(outcome.kickHint)
                        mqttManager?.disconnect()
                    }

                    OpenOutcome.Unknown -> {
                        // App 内调用给了充足预算，理论上不会走到这；保守起见按未确认处理
                        showerError = ShowerError(
                            "开阀未确认成功，请确认热水器是否已开启",
                            ShowerErrorKind.UNKNOWN_RESULT
                        )
                        mqttManager?.disconnect()
                    }
                }
            } catch (e: Exception) {
                checkKickEx(e)
                if (!isShowering) {
                    showerError = ShowerError(e.message ?: "网络错误", ShowerErrorKind.NETWORK)
                }
            } finally { isStartingShower = false }
        }
    }

    /**
     * 蓝牙表开阀。
     *
     * 与 [startShower] 主体（4G 表）的区别：
     *
     * | | 4G 表 | 蓝牙表 |
     * |---|---|---|
     * | 连接 | 无需连接 | **必须先连上设备**，还要保持在范围内 |
     * | 耗时 | 1~2 秒 | 数秒（连接 + 等状态帧 + 下费率 + 写费率包） |
     * | MQTT | 用来收实时消费 | **用不上**（蓝牙表不走 MQTT） |
     * | 自动关停 | 服务端下发时间 | 设备自己管 |
     *
     * ⚠️ 这里**不 disconnect**——连接要留给后面的关阀用。
     * 蓝牙表的阀是手机开的，断开连接就再也关不上了。
     */
    private suspend fun startBleShower(device: DeviceInfo, phone: String, snCode: String) {
        val ctx = appContext
        if (ctx == null) {
            showerError = ShowerError("应用上下文丢失，请重试", ShowerErrorKind.UNKNOWN_RESULT)
            return
        }

        // 蓝牙通道互斥：小组件服务可能正在操作同一台设备，两边同时 connectGatt 会互抢连接
        if (!BleArbiter.begin(snCode, "app")) {
            showerError = ShowerError("该设备正在处理其他蓝牙操作，请稍候再试", ShowerErrorKind.UNKNOWN_RESULT)
            return
        }

        val ble = bleController ?: BleController(ctx).also { bleController = it }

        bleProgress = "正在连接设备…"
        val result = try {
            BleShowerController.openValve(
                ble = ble,
                device = device,
                loginCode = PrefsHelper.loginCode,
                telephone = phone
            )
        } catch (t: Throwable) {
            AppLogger.e("蓝牙开阀异常", t)
            null
        } finally {
            // 临界区（连设备 + 开阀）结束即释放，后面的状态处理不需要独占
            BleArbiter.end(snCode)
        }
        bleProgress = null

        when (result) {
            is BleShowerController.OpenOutcome.Opened -> {
                bleSession = result.session
                // 会话落盘：小组件服务是另一个入口，关阀时要读它（App 退出也能关阀）
                PrefsHelper.bleSessionJson = gson.toJson(result.session)
                // ✅ 开阀成功 ⇒ 这台**确实**是蓝牙表，记下来，以后直接走蓝牙
                PrefsHelper.markBleDevice(snCode)
                AppLogger.i("蓝牙开阀成功，进入使用页")
                // 蓝牙表没有服务端下发的自动关停时间，第四项传 0
                enterShowerState(null, snCode, device, 0)
            }

            is BleShowerController.OpenOutcome.Resumed -> {
                // 恢复用水：设备本来就在出水，不再写费率包、不再重新开阀。
                // 沿用当前 BLE 连接（session）进入使用页，让用户能继续计时、也能关阀。
                bleSession = result.session
                PrefsHelper.bleSessionJson = gson.toJson(result.session)
                AppLogger.i("蓝牙恢复用水，进入使用页")
                enterShowerState(null, snCode, device, 0)
            }

            is BleShowerController.OpenOutcome.Recovered -> {
                // 补结算完成：上一笔遗留账单已结清，但设备要等下次 rateOrder 才生成新
                // randomNumber，立刻开阀会 226。用 toast 温和提示，让用户重新点开阀。
                AppLogger.i("蓝牙补结算完成，提示重新开阀")
                releaseBle()
                toastMessage = result.message
            }

            is BleShowerController.OpenOutcome.NotBleDevice -> {
                // 连上了但没有水控服务 ⇒ 用户当初猜错了。
                // **自动撤销标记**，以后不再对这台设备尝试蓝牙——
                // 否则用户每次开阀都要白等一次连接超时。
                PrefsHelper.unmarkBleDevice(snCode)
                showerError = ShowerError(
                    "这台设备不支持蓝牙控制，已切回普通方式，请重试",
                    ShowerErrorKind.BAD_DEVICE
                )
                releaseBle()
            }

            is BleShowerController.OpenOutcome.Failed -> {
                // ⚠️ 这里**不撤销标记**：可能是走远了、设备忙、蓝牙没开，
                // 但设备本身大概率确实是蓝牙表。撤销了反而要用户重新确认一遍。
                showerError = ShowerError(result.message, ShowerErrorKind.SERVER_REJECTED)
                releaseBle()
            }

            is BleShowerController.OpenOutcome.Unconfirmed -> {
                showerError = ShowerError(result.message, ShowerErrorKind.UNKNOWN_RESULT)
                // ⚠️ **故意不断开**：费率包可能已经写进设备、水正在流，
                // 断开等于把唯一的关阀通道也扔掉了。留在使用页让用户能停。
            }

            null -> {
                showerError = ShowerError("蓝牙开阀失败，请重试", ShowerErrorKind.NETWORK)
                releaseBle()
            }
        }
    }

    /**
     * 释放蓝牙连接。
     *
     * ⚠️ 只在**确定没有进行中的用水**时调用。蓝牙表断开后无法再关阀，
     * 所以「开阀未确认」那种情况必须保留连接。
     */
    private fun releaseBle() {
        bleController?.disconnect()
        bleController = null
        bleSession = null
        // 会话已结束/失效，落盘那份也要清——否则小组件会拿一条过期会话去关阀
        PrefsHelper.bleSessionJson = ""
    }

    /**
     * 桌面上有小组件时刷新一下。
     * 小组件自己不持有状态、每次都从 Prefs 现读，所以这里只要推它重绘即可。
     */
    private fun refreshWidgets() {
        appContext?.let { LinYuWidget.refreshAll(it) }
    }

    /**
     * 把账单快照存给小组件。
     * 小组件自己拉不了账单，2x4 的「账单」页读的就是这份数据，界面上会标同步时间。
     */
    private fun cacheBillsForWidget(recent: List<BillItem>) {
        val list = recent.map { b ->
            CachedBill(
                emoji = b.consumeBillDTO.deviceEmoji,
                name = b.consumeBillDTO.displayDesc,
                timeText = b.consumeBillDTO.consumeDate.take(16),
                moneyText = "-¥ " + b.consumeBillDTO.consumeMoney,
                // 原始值一并存下来：小组件的余额估算要拿它们做减法，
                // 靠上面那两个格式化字符串反解是解不出来的
                rawTimeMs = BalanceEstimator.billTimeMs(b.consumeBillDTO.consumeDate),
                rawMoney = b.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0
            )
        }
        PrefsHelper.widgetBillJson = gson.toJson(list)
        PrefsHelper.widgetBillTime = System.currentTimeMillis()
    }

    /**
     * 把附近设备快照存给小组件。
     *
     * **空结果也要写**：小组件靠 `widgetNearbyTime` 区分「扫过但附近没有」和「压根没扫过」，
     * 前者显示「未发现热水器」（和 App 首页一致），后者才提示回 App 扫一次。
     * 之前空结果直接 return，两种情况在桌面上长得一模一样。
     *
     * ⚠️ **不在这里按寝室过滤，也不在这里 `take(2)`**，两个都是坑：
     *
     * - 过滤搬到这里 = 快照被绑定的瞬间冻住。用户改绑寝室后，桌面上要等到**下次扫描**
     *   才跟着变，中间这段时间显示的是上一个寝室的设备。
     *   过滤放在小组件渲染时做，改绑定立刻生效。
     * - `take(2)` 放在这里 = **在过滤之前截断**。小组件只能显示 2 行，要是扫到的前两台
     *   恰好都是别的寝室，而本寝室的在第 3 台之后，过滤完就是空的——桌面显示
     *   「未发现热水器」，可用户寝室里明明有设备。
     *
     * 所以这里存**全量**（BLE 扫描一般就几台，几百字节），筛选和截断都留给渲染时。
     */
    private fun cacheNearbyForWidget() {
        val list = nearbyDevices.map { d ->
            CachedDevice(
                emoji = d.typeEmoji,
                name = d.displayName,
                // 设备信息还没拉到时用名字兜底，保证小字那行不会是空的
                desc = d.deviceInfo?.typeLabel
                    ?: if (d.name.startsWith("洗手台")) "洗手台热水器" else "卫生间热水器",
                rssi = d.rssi,
                mac = d.mac,
                // 原始名，寝室筛选用。注意和 name 不是一回事
                rawName = d.deviceInfo?.deviceName ?: d.name
            )
        }
        PrefsHelper.widgetNearbyJson = gson.toJson(list)
        PrefsHelper.widgetNearbyTime = System.currentTimeMillis()
    }

    /**
     * 把活跃订单从 Prefs 重新读回内存。
     * Prefs 是唯一事实来源——ShowerController 可能在没有这个 ViewModel 的情况下改过它
     * （例如用户在桌面小组件里开了阀）。
     */
    private fun syncActiveOrdersFromPrefs() {
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear(); activeOrders.addAll(saved)
        activeDeviceSnCodes.clear(); saved.forEach { activeDeviceSnCodes.add(it.snCode) }
    }

    /** 开阀后订单号还没生成，轮询把它补上 */
    private fun startOrderPoll(snCode: String) {
        orderPollJob?.cancel()
        orderPollJob = sessionScope().launch {
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
        showerRemaining = MoneyFormat.format(device.withholdMoney)
        activeDeviceSnCodes.add(snCode)
        // 开阀时间戳可能刚刚才写入（开阀成功时），这里读一次再刷新桌面，计时才对得上
        refreshWidgets()

        // 自动关停倒计时。界面上的秒数无条件跟随传入值；
        // 持久化的截止时间只在没有时才写——恢复订单时传入的本身就是「剩余秒数」，
        // 若用无条件覆盖的写法会把它当成新的完整时长，倒计时会越恢复越长
        if (autoDiscon > 0) {
            autoDisConSec = autoDiscon
            if (PrefsHelper.getAutoDisconRemain(snCode) <= 0) {
                PrefsHelper.setAutoDisconRemain(snCode, autoDiscon)
            }
        }

        val st = PrefsHelper.getStartedAt(snCode)
        showerElapsedSec = if (st > 0) ((System.currentTimeMillis() - st) / 1000).toInt() else {
            PrefsHelper.setStartedAt(snCode, System.currentTimeMillis()); 0
        }

        lastDeviceName = device.displayName; lastDeviceMac = device.macAddress; lastDeviceSnCode = snCode; lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceName = device.displayName; PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = snCode; PrefsHelper.lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceRawName = device.deviceName   // 寝室筛选用，见 PrefsHelper

        // 交给前台服务持续监控：超时自动关停、被外部关闭，都要能立刻发现。
        // 这两件事以前挂在下头的 timerJob 上，退出洗澡页就被 cancel 了。
        appContext?.let { ShowerWatchService.start(it, snCode) }

        // ⚠️ 这个计时器现在只做一件事：每秒刷新界面上显示的「已用时长」。
        //
        // 它以前还兼职做「倒计时递减」和「每 15 秒轮询订单状态」，但那两件事挂在这里
        // 有两个致命问题：`minimizeShower()` 会 cancel 它（退出洗澡页就没人管了），
        // 从小组件开阀时更是压根不会启动。现在都搬进 ShowerWatchService 了。
        //
        // 倒计时也从「每秒减一」改成了直接读 Prefs——那里存的是截止**时间戳**，
        // 按当前时间算出来，不会因为协程被延迟而慢慢走偏。
        timerJob?.cancel()
        timerJob = sessionScope().launch {
            while (isShowering) {
                delay(1000)
                val st = PrefsHelper.getStartedAt(snCode)
                if (st > 0) {
                    showerElapsedSec = ((System.currentTimeMillis() - st) / 1000).toInt()
                    autoDisConSec = PrefsHelper.getAutoDisconRemain(snCode)
                }
            }
        }
    }

    private fun handleMqttMessage(message: String) {
        // 先给「按套餐计费」的通道递一份。
        //
        // 它自己按 `deviceSnCode` 过滤，不会把热水器的单认成自己的；
        // 内部所有异常都吞掉了（没有 throw），所以放在热水器解析之前是安全的，
        // 也避免热水器那段 `try/catch` 把消息提前吃掉。
        try {
            modeOrderController.onMqttMessage(message)
        } catch (_: Exception) {
            // 双保险：条款上它不该抛，但一条推送解析失败绝不能影响热水器链路
        }

        try {
            val msg = gson.fromJson(message, MqttOrderMsg::class.java)

            // ⚠️ 这里曾经是 `showerSnCode?.let { updateOrderNo(it, it) }`——
            // 内层的 `it` **遮蔽**了外层 `it`，两个参数都变成了设备序列号，
            // 于是持久化的 orderNo 被写成了 snCode。
            //
            // 后果不容易当场发现：关阀和结算拿这个假 orderNo 去查，服务端只会说"查不到"，
            // 然后静默退化成按时间窗猜账单——而那个兜底本来就不可靠。
            // 所以这里**必须用具名变量**，别再用嵌套 `let` 的隐式 `it`。
            msg.orderNo?.let { orderNo ->
                if (currentOrderNo == null) {
                    currentOrderNo = orderNo
                    showerSnCode?.let { sn -> updateOrderNo(sn, orderNo) }
                }
            }
            msg.consumeMoney?.let {
                showerConsumed = it
                showerRemaining = MoneyFormat.format(if (showerPreDeduct - it < 0) 0.0 else showerPreDeduct - it)
            }
        } catch (_: Exception) {}
    }

    /**
     * 回到前台时对齐一次洗澡状态。
     *
     * [ShowerEvents] 是**没有 replay 的进程内事件**——正常情况下服务发、界面收，
     * 但万一漏了（事件发出时收集器恰好没就绪、或者进程被重建），
     * 用户就会看到洗澡界面挂在那里、计时还不走了（`startedAt` 已被清成 0）。
     *
     * 这里按本地活跃订单再核一次，兜住那条链路。**纯读 Prefs，不发网络请求**——
     * 服务结束时会先清 `activeOrders`，所以本地状态就是准的。
     */
    fun reconcileShowerOnResume() {
        if (!isShowering) return
        val sn = showerSnCode ?: return
        if (ShowerController.isRunning(sn)) {
            // 还在用，只是把界面上的数字拉正（切后台期间计时器没跑）
            val st = PrefsHelper.getStartedAt(sn)
            if (st > 0) showerElapsedSec = ((System.currentTimeMillis() - st) / 1000).toInt()
            autoDisConSec = PrefsHelper.getAutoDisconRemain(sn)
            return
        }
        // 已经结束了，而界面还停在洗澡页——补一次收尾
        AppLogger.i("回前台对齐：$sn 已结束，退出使用页")
        finishShower(sn, null)
    }

    /**
     * 用水结束（由 [ShowerWatchService] 检测到：设备超时自己关的，或在别处被关的）。
     *
     * 服务那边已经把活跃订单清掉、小组件刷新过、通知也发完了，
     * 这里只剩界面收尾——所以金额是直接带过来的，**不再自己结算一遍**。
     */
    private fun onShowerFinishedFromService(e: ShowerEvents.Finished) {
        // 先把 Prefs 里的活跃订单读回内存：小组件那边开了阀又关了，
        // 这个 ViewModel 的内存副本一直是旧的
        syncActiveOrdersFromPrefs()

        if (!isShowering || showerSnCode != e.snCode) return

        timerJob?.cancel(); orderPollJob?.cancel()

        if (!e.autoClosed) {
            // 用户自己结束的（通知栏「结束用水」按钮 / 小组件停止）：
            // 直接退出使用页就行，不用再弹一个确认框
            finishShower(e.snCode, e.money?.takeIf { it > 0 })
            return
        }

        // 设备自己超时关的：弹确认框把结果告诉用户
        autoCloseDeviceName = e.deviceName
        autoCloseElapsed = e.elapsedSec
        autoCloseConsumed = e.money   // 可空：null 时弹窗会说「未能获取」而不是 ¥0.00
        autoCloseLoading = false
        showAutoCloseDialog = true
    }

    /** 用户点确认：退出洗澡界面并清理 */
    fun confirmAutoClose() {
        showAutoCloseDialog = false
        val snCode = showerSnCode ?: ""
        finishShower(snCode, null)
    }

    /**
     * 把使用页拉回来。点「正在使用」通知进来时调用。
     *
     * 界面可能是三种状态之一：
     * - **已经停在使用页** → 什么都不用做
     * - **之前点了「最小化」** → `isShowering` 是 false，但设备还在跑
     * - **App 被杀过** → 同上，而且内存里的活跃订单也没了
     *
     * 后两种都要先从 Prefs 把活跃订单读回来，再走「恢复订单」那条路——
     * `openValve` 查到已有订单且是自己的时候返回 `Resumed`，**不会重新开阀**，
     * 正好是首页那张「使用中的设备」卡片上「恢复」按钮的行为。
     */
    fun restoreShowerScreen(phone: String) {
        if (isShowering) return
        syncActiveOrdersFromPrefs()
        val order = activeOrders.firstOrNull() ?: return
        lastDeviceSnCode = order.snCode
        lastDeviceMac = order.deviceMac
        startLastDevice(phone)
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

        // 蓝牙表：关阀靠**当前这条 BLE 连接**，和云端下发完全不是一条路
        if (bleSession != null) {
            stopBleShower(skipNetwork)
            return
        }

        // 会话丢了但设备标着蓝牙 → 进程曾被杀（崩溃/系统回收），
        // 恢复出使用页但内存里的 session 没了。落到下面的云端关阀对
        // 无 4G 的蓝牙表**必然失败**，必须重连设备再关。
        val snForBle = showerSnCode ?: activeOrders.firstOrNull()?.snCode ?: ""
        if (PrefsHelper.isBleDevice(snForBle)) {
            reconnectAndStopBle(snForBle)
            return
        }

        val snCode = showerSnCode ?: ""
        val oNo = currentOrderNo ?: activeOrders.find { it.snCode == snCode }?.orderNo ?: ""

        // 挤号等场景：loginCode 已失效，跳过网络请求，直接本地清理，避免再次触发挤号
        if (skipNetwork) {
            finishShower(snCode, null)
            return
        }

        isStopping = true
        // 立刻把通知切成「正在结束」。关阀确认要轮询最多 5 秒，
        // 不切的话用户点完还得盯着「正在使用 · 秒数还在走」干等。
        appContext?.let { Notifier.showStopping(it, lastDeviceName.ifEmpty { "热水器" }) }
        sessionScope().launch {
            try {
                // ⚠️ orderNo 必须在**关阀之前**敲定：关阀成功后订单就没了，
                // `queryUsing` 再也问不出来。而开阀后 orderNo 是异步轮询补上的，
                // 「开完水马上停」时本地可能还是空串——那就白丢了结算用的那个直答接口。
                val settledNo = ShowerController.resolveOrderNo(snCode).ifEmpty { oNo }
                // 关阀 + 确认 + 清本地状态都在 ShowerController 里，小组件的「停止使用」走同一份逻辑
                val outcome = ShowerController.closeValve(snCode, settledNo)
                checkKick(outcome.kickHint)

                if (outcome is CloseOutcome.Failed) {
                    toastMessage = outcome.message
                    isStopping = false
                    return@launch
                }

                // ⚠️ 没确认到设备停了（多半是断网，关阀请求压根没发出去）——
                // **不能退出使用页**。这一页上的「停止」是用户唯一的停止入口，
                // 退出去就等于把他扔在一个「以为停了、其实还在扣钱」的状态里，
                // 而且他想再停一次都找不到按钮。留在这里，让他能重试。
                if (outcome is CloseOutcome.Unconfirmed) {
                    toastMessage = "没能确认设备已关闭，请检查网络后重试"
                    isStopping = false
                    AppLogger.w("停止用水未确认：${outcome.message}")
                    return@launch
                }

                val startTime = (outcome as CloseOutcome.Closed).startTimeMs
                finishShower(snCode, null)
                // 界面已退出（不阻塞），后台异步等账单结算后弹金额
                sessionScope().launch {
                    // 和小组件/通知栏那两个入口一样：先挂「结算中」，拿到金额原地更新
                    val elapsed0 = if (startTime > 0)
                        ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0
                    appContext?.let {
                        Notifier.showSettling(
                            it, Notifier.ID_FINISHED,
                            "使用结束 · ${lastDeviceName.ifEmpty { "热水器" }}", elapsed0
                        )
                    }
                    val amount = SettlementEngine.settleAmount(
                        settledNo,
                        startTime,
                        snCode,
                        lastDeviceName
                    )
                    toastMessage = when {
                        // null = 两条路都没拿到金额，是「还不知道」而不是「没花钱」
                        amount == null -> "已停止，消费金额稍后可在账单中查看"
                        amount > 0 -> "已停止，本次消费 ${MoneyFormat.withSymbol(amount)}"
                        else -> "热水器已关闭，本次无消费"
                    }
                    // 结算拿到了新的「上次消费」，让桌面小组件跟上
                    refreshWidgets()

                    // 发系统通知，然后把后台监控停掉。
                    // 不停的话它下一轮会查到订单已经没了，再发一条重复的结束通知。
                    appContext?.let { ctx ->
                        val elapsed = if (startTime > 0)
                            ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0
                        Notifier.showFinished(
                            ctx,
                            lastDeviceName.ifEmpty { "热水器" },
                            elapsed,
                            amount      // 可空：null 会显示成「结算中」而不是「无消费」
                        )
                        ShowerWatchService.stop(ctx)
                    }
                }
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "停止洗澡失败，请检查网络后重试"
                isStopping = false
            }
        }
    }

    /**
     * 蓝牙表：关阀 + 结算。
     *
     * 流程：关阀(`0x22`) → 采集(`0x85`) → 上传 → 解密 `clData` → 写回(`0x86`)。
     *
     * ⚠️ 两个和 4G 表不一样的地方：
     *
     * 1. **关阀是本地 BLE 操作，不依赖登录态**——所以即使被挤号、
     *    `loginCode` 已失效，也要尽力把阀关掉（只是结算会失败）。
     * 2. **关阀失败时绝不能退出使用页**。这一页的按钮是用户唯一的停止入口，
     *    退出去等于把他扔在「以为停了、其实水还在流」的状态里。
     *
     * @param skipNetwork 保留以对齐 [stopShower] 的调用方；蓝牙表关阀不走网络，
     *   所以这里**不因它跳过流程**（结算失败会自然落到「部分结算」分支）
     */
    /**
     * BLE 会话丢失后的补救关阀：重连设备 → 拿状态帧重建会话 → 走正常蓝牙关阀。
     *
     * 适用场景：开阀成功后进程被杀（崩溃 / 系统回收），App 重启恢复出使用页，
     * 但内存里的 [bleSession] 已经没了。此时云端关阀必然失败（蓝牙表没有 4G，
     * 服务端管不到阀），唯一的路是重新连上设备把阀关掉。
     *
     * 状态帧里的 `protocolType` / `randomNumber` 重连后设备会重新给，
     * 所以会话可以完整重建；`consumeDate` 拿不回来了（只在失败上报时用，留空）。
     */
    private fun reconnectAndStopBle(snCode: String) {
        val ctx = appContext
        if (ctx == null) {
            toastMessage = "应用上下文丢失，请重试"
            isStopping = false
            endStopAttempt()
            return
        }
        isStopping = true
        appContext?.let { Notifier.showStopping(it, lastDeviceName.ifEmpty { "热水器" }) }
        sessionScope().launch {
            // MAC 优先取活跃订单里存的（就是开阀时用的那个），丢了再回落
            val mac = (ShowerController.activeOrderFor(snCode)?.deviceMac ?: PrefsHelper.lastDeviceMac)
                .replace(":", "")
                .replace("-", "")
                .uppercase()
            if (mac.length != 12) {
                AppLogger.w("蓝牙补救关阀中止：MAC 无效（$mac）")
                toastMessage = "设备地址丢失，无法关闭，请重新进入设备页面后重试"
                isStopping = false
                endStopAttempt()
                return@launch
            }

            val ble = bleController ?: BleController(ctx).also { bleController = it }
            bleProgress = "正在重新连接设备…"
            // ⚠️ 这里存的是**台账 MAC**，而蓝牙连接要的是手机扫描到的**空口广播地址**，
            // 两个对部分设备不是一个字符串（见 MacFormat）。只有台账 MAC 时按规范反推；
            // 推不出来说明本来就是个广播地址，原样返回。
            val connectAddress = MacFormat.connectAddressFor(mac)
            val st: DeviceQueryState? = try {
                when (val cr = ble.connect(connectAddress)) {
                    is BleConnectResult.Ready -> ble.query()
                    is BleConnectResult.NoService -> null
                    is BleConnectResult.Failed -> {
                        AppLogger.w("蓝牙补救关阀连接失败：${cr.reason}")
                        null
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e("蓝牙补救关阀重连异常", t)
                null
            }
            bleProgress = null
            if (st == null) {
                toastMessage = "蓝牙连接失败，请靠近设备后重试"
                isStopping = false
                endStopAttempt()
                return@launch
            }

            AppLogger.i("蓝牙会话已重建：protocol=${st.protocolType} random=${st.randomNumber}")
            bleSession = BleShowerController.Session(
                snCode = snCode,
                // ⚠️ 必须是**连接地址**（上面那个 connectAddress），不能是台账 MAC：
                // closeAndSettle 会拿 session.mac 再连一次设备（设备只在新连接里
                // 给出可结算的记录），存错了就永远连不上、关阀失败。
                mac = connectAddress,
                protocolType = st.protocolType,
                randomNumber = st.randomNumber,
                consumeDate = "",   // 进程死亡后拿不到了；只有失败上报用，先留空
                orderNo = ShowerController.activeOrderFor(snCode)?.orderNo ?: ""
            )
            // 会话已就位，走正常蓝牙关阀 + 结算
            stopBleShower()
        }
    }

    private fun stopBleShower(@Suppress("UNUSED_PARAMETER") skipNetwork: Boolean = false) {
        // 小组件转交过来的关阀：busy 已由服务标记为「托管给 App」，所有出口都要 endStopAttempt
        val session = bleSession ?: return
        val ble = bleController
        if (ble == null) {
            // 连接已经没了，阀关不了——如实告诉用户，别假装成功
            toastMessage = "蓝牙连接已断开，请重新连接该设备后再关闭"
            isStopping = false
            endStopAttempt()
            return
        }

        // 蓝牙通道互斥：小组件服务可能正在操作同一台设备，两边同时 connectGatt 会互抢连接
        if (!BleArbiter.begin(session.snCode, "app")) {
            toastMessage = "该设备正在处理其他蓝牙操作，请稍候再试"
            isStopping = false
            endStopAttempt()
            return
        }

        isStopping = true
        appContext?.let { Notifier.showStopping(it, lastDeviceName.ifEmpty { "热水器" }) }

        sessionScope().launch {
            bleProgress = "正在关闭设备…"
            val outcome = try {
                BleShowerController.closeAndSettle(
                    ble = ble,
                    session = session,
                    loginCode = PrefsHelper.loginCode,
                    telephone = phone
                )
            } catch (t: Throwable) {
                AppLogger.e("蓝牙停止异常", t)
                null
            } finally {
                // 临界区（连设备 + 关阀 + 结算）结束即释放
                BleArbiter.end(session.snCode)
            }
            bleProgress = null

            when (outcome) {
                is BleShowerController.SettleOutcome.Completed -> {
                    // ⚠️ 金额单位**未实测确认**。按与 4G 表一致的「厘」处理（÷1000），
                    // 并在日志里留原始值——第一次真机结算后要用账单列表核对一次。
                    val yuan = MoneyFormat.milliToYuan(outcome.rawUpMoney)
                    AppLogger.i("蓝牙结算金额：原始=${outcome.rawUpMoney} 按厘换算=¥$yuan")
                    toastMessage = if (yuan != null && yuan > 0) {
                        "已停止，本次消费 ${MoneyFormat.withSymbol(yuan)}"
                    } else {
                        "设备已关闭"
                    }
                    // ⚠️ 立即记一笔「上次消费」给桌面小组件。蓝牙路径原先漏了这一步，
                    // 卡片只能等下次拉账单才对上，表现为「上次消费更新不及时」。
                    if (yuan != null && yuan > 0) PrefsHelper.recordConsume(session.snCode, yuan)
                    finishShower(session.snCode, yuan)
                    refreshWidgets()
                    releaseBle()
                }

                is BleShowerController.SettleOutcome.PartialSettlement -> {
                    // 水**已经关了**，只是账没结完。要退出使用页（否则用户被卡住），
                    // 但明确告诉他金额去哪儿看
                    toastMessage = "设备已关闭；${outcome.message}，金额请稍后在账单中查看"
                    finishShower(session.snCode, null)
                    releaseBle()
                }

                is BleShowerController.SettleOutcome.CloseFailed -> {
                    // ⚠️ 水可能还在流 —— 留在使用页，让他能重试
                    toastMessage = outcome.message
                    AppLogger.w("蓝牙关阀失败，保留使用页以便重试")
                    isStopping = false
                    endStopAttempt()   // 卡片从「正在关闭…」回到「使用中」，水确实还在流
                }

                null -> {
                    toastMessage = "停止失败，请重试"
                    isStopping = false
                    endStopAttempt()
                }
            }
        }
    }

    /** 完成停止流程：退出洗澡界面，清理状态，显示结算结果 */
    /**
     * 一次停止尝试结束（**成败都算**）：收掉小组件托管过来的「正在关闭…」并重绘桌面。
     *
     * 小组件的关阀有两种走法，busy 的归属跟着操作走：
     *
     * | 走法 | busy 谁清 |
     * |---|---|
     * | 服务自己关（App 进程不在） | `WidgetBleService` 的 finally |
     * | 转交 App 关（App 握着连接） | **本方法**——转交时 busy 被保留，一直显示「正在关闭…」直到真正关完 |
     *
     * 少了这里的收尾，转交路径的卡片会在关阀中途弹回「使用中」继续计时，
     * 过一两秒才变空闲——用户看到的就是「闪一下正在关闭、又接着计时」。
     */
    private fun endStopAttempt() {
        WidgetBridge.clearBusy()
        refreshWidgets()
    }

    private fun finishShower(snCode: String, consumed: Double?) {
        isShowering = false
        isStopping = false

        // 从活跃列表移除
        activeOrders.removeAll { it.snCode == snCode }
        saveOrders()
        activeDeviceSnCodes.remove(snCode)

        if (consumed != null && consumed > 0) {
            toastMessage = "已停止，本次消费 ${MoneyFormat.withSymbol(consumed)}"
        } else {
            // 0.0 或 null（结算没拿到金额）都只提示关闭，不弹「消费 ¥0.00」
            toastMessage = "热水器已关闭"
        }

        currentOrderNo = null; showerConsumed = 0.0; showerPreDeduct = 0.0
        showerRemaining = "0.00"; showerElapsedSec = 0
        autoDisConSec = 0
        PrefsHelper.setStartedAt(snCode, 0L) // 重置该设备计时器
        PrefsHelper.clearAutoDiscon(snCode)  // 清除自动关停倒计时
        showerSnCode = null; timerJob?.cancel(); orderPollJob?.cancel()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        // ⚠️ 小组件的「正在关闭…」由本进程收尾：转交过来的关阀（见 WidgetBleService），
        // busy 一直保留到这里，清掉后卡片才落到「空闲」。
        WidgetBridge.clearBusy()
        refreshWidgets()
    }

    fun logout(preserveActiveRecovery: Boolean = false) {
        // 掐断所有网络任务（含挤号心跳），避免退出后还有响应回来改状态
        stopTimer()
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])

        // 释放蓝牙连接。能走到这里说明**没有进行中的用水**——
        // 有活跃订单时 `canLogoutVoluntarily` 会先拦住，所以断连是安全的。
        // ⚠️ 蓝牙表断开后就再也关不了阀，这句话是这里唯一的安全保证，别绕过。
        releaseBle()

        // 断开 MQTT 连接
        try { mqttManager?.disconnect() } catch (_: Exception) {}

        // 停止蓝牙扫描
        try { scanner?.stopScan() } catch (_: Exception) {}
        isScanning = false

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
        billHistory = emptyList()
        // 一起复位：下一轮登录要重新等账单到位，不能沿用上一次的「已加载」
        billsLoaded = false
        useCodeData = null
        walletInfo = null
        // 账号信息是跟人走的，换账号必须清掉，否则会显示上一任的姓名/学号/余额
        accountInfo = null
        campusBalance = null
        walletBalance = null
        phone = ""
        useCodeLoaded = false
        // 未支付账单和代扣锁是**跟账号走的**：不清的话，换账号后开阀失败弹窗会列出
        // 上一任的账单和金额，还能用新账号的凭证去扣它；锁更麻烦——
        // 万一扣款协程在启动前就被取消（logout/挤号都会 cancel sessionJob），
        // 清锁那行永远跑不到，之后所有代扣都会静默失效，只能杀进程
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
        // 内存态的「上次设备」也要跟着清。
        //
        // Prefs 那份由 `PrefsHelper.clear()` 负责——它的动态 filter 走
        // `shouldPreserveForRecovery`，而那个函数覆盖了 `lastDevice*` 前缀，
        // 所以正常登出时落盘的七个字段都会被 remove。**内存副本是另一份**，
        // 不清的话，重新登录之后、`initManagers` 重新读 Prefs 覆盖回来之前
        // 那段窗口里，界面读到的还是上一任的设备。
        //
        // ⚠️ 挤号（`preserveActiveRecovery = true`）时**绝不能清**：那时正是要靠
        // 这些字段保住正在用水的设备上下文，清了用户就找不到停止入口了。
        if (!preserveActiveRecovery) {
            lastDeviceName = ""
            lastDeviceMac = ""
            lastDeviceSnCode = ""
            lastDeviceEmoji = "🚿"
            // 板块是**账号级**的：换账号后还挂着上一任的两个板块和名字就串了
            projects = emptyList()
            projectNames = emptyMap()
            currentProjectId = null
        }
        PrefsHelper.clear(preserveActiveRecovery)
        if (preserveActiveRecovery) appContext?.let(ShowerWatchService::stop)
    }

    private fun saveOrders() { PrefsHelper.saveActiveOrders(activeOrders.toList()) }

    fun isDeviceActive(snCode: String) = snCode in activeDeviceSnCodes

    // ── 寝室绑定 / 设备筛选 ──

    /**
     * 绑定的寝室键。
     *
     * ⚠️ 必须是 Compose 状态，**不能直接读 `PrefsHelper.boundRoom`**。
     * SharedPreferences 是普通属性，改它不会触发重组——首页设备列表的过滤结果
     * 是用 `remember(..., boundRoom)` 缓存的，读 pref 的话取消绑定后列表要等
     * 下一次扫描（或别的什么把界面顶一下）才会更新，表现就是「点了取消没反应」。
     *
     * 改绑定一律走 [applyBoundRoom]，别绕过它直接写 PrefsHelper。
     */
    var boundRoom by mutableStateOf(PrefsHelper.boundRoom)
        private set

    /** 绑定/取消绑定寝室。写 Prefs + 更新状态 + 收尾（清寝室外的上次设备、刷新桌面） */
    fun applyBoundRoom(value: String) {
        val v = value.trim()
        PrefsHelper.boundRoom = v
        boundRoom = v
        onBoundRoomChanged()
    }

    /** 当前是否有绑定寝室 */
    val hasBoundRoom: Boolean get() = boundRoom.isNotBlank()

    /**
     * 这台设备在不在绑定的寝室内。
     *
     * `boundRoom` 里存的是**完整的设备名**（选择附近时存的是被选中的那台的原名），
     * 所以两边都要先过一遍 [DeviceInfo.roomKey] 取关键词，再**等值**比较——
     * 规则和原因都在那里写着，别在这儿另写一套。
     */
    fun matchesBoundRoom(deviceName: String): Boolean =
        DeviceInfo.inSameRoom(PrefsHelper.boundRoom, deviceName)

    /**
     * 绑定/换绑寝室之后收尾：**清掉寝室外的「上次使用设备」**，并让桌面跟上来。
     *
     * 为什么是「清掉」而不是「只是不显示」：`lastDeviceSnCode` 是小组件主按钮的
     * **开阀依据**。留着它就等于留着一条「点一下就开别寝室的水」的路——
     * 虽然小组件那边也加了一道闸门，但两处都拦不如根本不留下这台设备。
     *
     * ⚠️ **正在用水的设备不清**：那条记录对应着一个活跃订单，清掉会让水还在流、
     * 卡片却没了停止入口。用水中的卡片本来就不参与筛选（见 WidgetRenderer.readState），
     * 所以留着它是安全的。等这单结束，用户再重新选设备或改绑定。
     */
    fun onBoundRoomChanged() {
        val name = PrefsHelper.lastDeviceName
        val sn = PrefsHelper.lastDeviceSnCode
        if (name.isEmpty() || sn.isEmpty()) { refreshWidgets(); return }
        // ⚠️ 判断用**原始设备名**（roomFilterName），不是 name。
        // name 是格式化过的显示名，楼层被 formatDeviceName 去掉了，拿它算出来的
        // 寝室键和绑定值对不上——会把一个**本来就在寝室里**的设备误清掉。
        if (DeviceInfo.inSameRoom(PrefsHelper.boundRoom, ShowerController.roomFilterName())) {
            refreshWidgets()
            return
        }
        if (ShowerController.isRunning(sn)) {
            AppLogger.w("绑定寝室后没清上次设备：$name 正在用水，保留停止入口")
            refreshWidgets()
            return
        }
        PrefsHelper.lastDeviceSnCode = ""
        PrefsHelper.lastDeviceName = ""
        PrefsHelper.lastDeviceRawName = ""
        PrefsHelper.lastDeviceMac = ""
        PrefsHelper.lastDeviceEmoji = "🚿"
        lastDeviceSnCode = ""
        lastDeviceName = ""
        lastDeviceMac = ""
        lastDeviceEmoji = "🚿"
        AppLogger.i("绑定寝室后清掉了寝室外的上次设备：$name")
        refreshWidgets()
    }

    // ── 挤号 ──

    /** 心跳间隔。要"马上发现被挤号"就得主动轮询，这是拿一点电和流量换来的 */
    private val kickWatchIntervalMs = 25_000L

    /**
     * 开始挤号心跳检测：登录后调用，按生命周期在前后台启停。
     *
     * 原来只有发请求时才顺带检查挤号（被动），用户挂在这个页面不动就永远发现不了。
     * 这里定时打一个最轻的接口兜底，被挤号最多 25 秒内弹提示。
     */
    fun startKickWatch() {
        if (kickWatchJob?.isActive == true) return
        kickWatchJob = sessionScope().launch {
            while (isActive) {
                delay(kickWatchIntervalMs)
                if (kickedOut) break
                try {
                    val resp = NetworkModule.apiService.getWalletSafe()
                    if (resp.success) {
                        // 顺手把钱包余额刷新了。这条心跳本来就在跑，
                        // 不额外增加请求量，却让余额每 25 秒自己跟上——
                        // 洗完澡余额会变，不用等用户手动下拉刷新。
                        resp.data?.money?.toDoubleOrNull()?.let {
                            walletBalance = it
                            PrefsHelper.walletBalance = it.toString()
                        }
                    } else checkKick(resp.displayMessage)
                } catch (e: Exception) {
                    // 网络异常不算挤号，checkKickEx 只认 401/403
                    checkKickEx(e)
                }
            }
        }
    }

    fun stopKickWatch() {
        kickWatchJob?.cancel()
        kickWatchJob = null
    }

    /**
     * 被挤号：标记状态并清掉本地凭证。
     *
     * 用 `if (kickedOut) return` 兜住重复触发——多个请求可能几乎同时返回"登录失效"，
     * 否则会反复 clear() + 反复通知界面。
     */
    private fun kickOut() {
        if (kickedOut) return
        kickedOut = true
        stopTimer()
        PrefsHelper.clear(preserveActiveRecovery = true)
        appContext?.let(ShowerWatchService::stop)
        // 和 logout() 同理：账号已经失效，上一任的未支付账单和代扣锁都不能留
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
        // 掐断旧会话的所有在途请求，避免它们的失败响应回来干扰用户接下来的重新登录。
        // 放在最后：调用者本身就跑在 sessionJob 上，取消会连自己一起取消，
        // 而取消是协作式的——只要后面不再有挂起点，这几行仍会执行完。
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    }

    private fun checkKick(msg: String?) {
        if (msg.isNullOrEmpty()) return
        if (msg.contains("登录") || msg.contains("token") || msg.contains("失效") || msg.contains("过期") || msg.contains("认证") || msg.contains("未登录") || msg.contains("请重新")) {
            kickOut()
        }
    }

    private fun checkKickEx(e: Exception) {
        val m = e.message ?: return
        if (m.contains("401") || m.contains("403") || m.contains("Unauthorized") || m.contains("Forbidden")) {
            kickOut()
        }
    }

    /** 取消计时与轮询（挤号 / 退出登录共用） */
    private fun stopTimer() {
        timerJob?.cancel(); timerJob = null
        orderPollJob?.cancel(); orderPollJob = null
        stopKickWatch()
    }

    /**
     * 开始一次新会话：登录成功后调用。
     *
     * 必须做三件事，缺一个都会导致"重新登录进去还是被弹出"：
     * 1. 换一个全新的 sessionJob，让旧会话的残留请求彻底失效
     * 2. 清掉 kickedOut 标志，否则主界面一挂载就又弹挤号框
     * 3. 断开上一轮的 MQTT（它还连着旧会话的 topic），由新会话按需重连
     */
    fun beginSession() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        stopTimer()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        kickedOut = false
        // 换账号了，手机号跟着换（新会话的认证参数已经写进 Prefs，这里同步到界面状态）
        phone = PrefsHelper.telephone
        // 登录时已按「上次选的 → 登录响应里的 → 列表第一个」定过板块（见 AuthRepository），
        // 这里把结果同步到界面状态，不然标题下面还显示上一任的板块名
        currentProjectId = ProjectRepository.currentProjectId()
        projects = ProjectRepository.cachedProjects()
        projectNames = ProjectRepository.projectNames()
    }

    fun refreshWallet() {
        sessionScope().launch {
            try { 
                val resp = NetworkModule.apiService.getWalletSafe()
                if (resp.success) {
                    walletInfo = resp.data
                    // 钱包余额落盘：一卡通拿不到时它就是真实余额
                    // （见 BalanceEstimator 的三档优先级）。
                    // ⚠️ 用 `money` 字符串字段而不是 `accountRealMoney` 数字：
                    //    `money` / `accountRealMoneyStr` 已经是**元**（如 "55.460"），
                    //    而 `accountRealMoney` 是厘（55460），单位得自己除 1000——
                    //    服务端万一换单位就会静默错 1000 倍，字符串字段没这个风险。
                    resp.data?.money?.toDoubleOrNull()?.let {
                        walletBalance = it
                        PrefsHelper.walletBalance = it.toString()
                    }
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

    fun loadUseCode() {
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getUseCodeSafe()
                if (resp.success && resp.data != null) useCodeData = resp.data
                // 无论成功与否都算「拉过了」——网络失败时停在「加载中」比显示
                // 「尚未领取」更糟，用户会以为 App 卡住了，而重进页面就会重拉
                useCodeLoaded = true
            } catch (_: Exception) {}
        }
    }

    /**
     * 「换一个」：换出候选使用码，**不改变当前生效的码**。
     *
     * 服务端每次扣一次额度（每天 20 次），返回剩余次数。换出来要再
     * [claimUseCode] 才生效，3 分钟内不领取就作废。
     *
     * @param onResult 成功时回调 `(新码, 剩余次数)`；失败回调 null
     */
    fun generateUseCode(onResult: (Pair<String, Int>?) -> Unit) {
        sessionScope().launch {
            val r = try {
                val resp = NetworkModule.apiService.generateUseCodeSafe(NetworkModule.authFields())
                val code = resp.data?.useCode
                if (resp.success && !code.isNullOrEmpty()) {
                    code to (resp.data?.remainTimes ?: 0)
                } else null
            } catch (_: Exception) {
                null
            }
            onResult(r)
        }
    }

    /** 「确定领取」：把换出来的候选码设为当前生效的使用码。成功返回 true */
    fun claimUseCode(useCode: String, onResult: (Boolean) -> Unit) {
        sessionScope().launch {
            val ok = try {
                NetworkModule.apiService
                    .setUseCodeSafe(useCode, NetworkModule.authFields())
                    .success
            } catch (_: Exception) {
                false
            }
            if (ok) loadUseCode()
            onResult(ok)
        }
    }

    /**
     * 拉账号信息（姓名 / 学号 / 校园卡绑定状态）。
     *
     * 登录响应里其实也带这些字段，但我们的 Gson 模型只解析了用到的几个，
     * 而且**姓名在学校没同步时会是空**——单独走这个接口补齐，拿到就写回 Prefs。
     */
    fun loadAccountInfo() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                NetworkModule.apiService.getAccountInfoSafe().data?.let {
                    applyIdentity(it.name, it.idCardNumber, it)
                }
                // 姓名靠学校同步学籍数据，没同步的账号 /account/info 返回的 name 就是 null
                // （实测同学的两个号都是这样）。换个接口再要一次——绑定卡信息里也有姓名和学号，
                // 两个接口数据来源不同，一个没有另一个可能有。
                if (PrefsHelper.userName.isEmpty() || PrefsHelper.userStudentId.isEmpty()) {
                    NetworkModule.apiService.getBindCardInfoSafe().data?.let {
                        applyIdentity(it.name, it.idCardNumber, null)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 把姓名 / 学号落到 Prefs **和** [accountInfo] 上。
     *
     * 现在有三个接口都能给这两个字段（`/account/info`、`/account/card/getBindCardInfo`、
     * `/settlement/campus/userInfo`），谁先拿到算谁的。所以统一走这里收口，
     * 免得每个调用点各写一遍「判空 → 写 Prefs」。
     *
     * ⚠️ 必须同时更新 [accountInfo]：界面读的是这个 State，只写 Prefs 不会触发重组，
     * 数据回来了界面也不刷新。
     *
     * @param extra 该来源额外的字段（卡状态之类），为 null 时保留已有的
     */
    private fun applyIdentity(name: String?, studentId: String?, extra: AccountInfo?) {
        val n = name?.takeIf { it.isNotBlank() }
        val s = studentId?.takeIf { it.isNotBlank() }
        if (n != null) PrefsHelper.userName = n
        if (s != null) PrefsHelper.userStudentId = s
        if (n == null && s == null && extra == null) return

        val cur = accountInfo
        val merged = AccountInfo(
            name = n ?: cur?.name,
            idCardNumber = s ?: cur?.idCardNumber,
            telephone = extra?.telephone ?: cur?.telephone,
            genderName = extra?.genderName ?: cur?.genderName,
            cardStatus = extra?.cardStatus ?: cur?.cardStatus,
            cardStatusName = extra?.cardStatusName ?: cur?.cardStatusName,
            gradeName = extra?.gradeName ?: cur?.gradeName,
            className = extra?.className ?: cur?.className
        )
        if (merged != cur) accountInfo = merged
    }

    /**
     * **未支付账单**——就是俗称的「残留账单」。
     *
     * 12 点后结束用水、或者一卡通余额不足时，服务端结算没扣成，账单会留在待扣状态。
     * 界面靠它决定哪些账单显示「请求代扣」按钮；开阀失败时也用它来提示
     * 「你可能是因为有未扣账单才开不了」。
     *
     * 存整个对象而不是只存日期，因为弹窗要显示**欠了多少钱**。
     */
    var unpaidBills by mutableStateOf<List<UnpaidBill>>(emptyList())
        private set

    /**
     * **真正能被补扣的账单**：`consumeDate` 非空的那些。
     *
     * 代扣接口就是靠 `consumeDate` 定位账单的，日期为空的一笔根本发不出去。
     * 界面显示的笔数 / 金额、以及 [deductAllUnpaid] 实际扣的，**都必须用这一份**——
     * 否则会出现「弹窗说共 3 笔、实际只扣 2 笔」，全为空时还会报一次假成功。
     */
    val deductibleBills: List<UnpaidBill>
        get() = unpaidBills.filter { !it.consumeDate.isNullOrBlank() }

    /** 未支付账单的 `consumeDate` 集合。按账单列表匹配按钮显隐时用它 */
    val unpaidConsumeDates: Set<String>
        get() = deductibleBills.mapNotNull { it.consumeDate }.toSet()

    /** 正在代扣的那条账单的 consumeDate；非空期间按钮禁用，防止连点重复扣款 */
    var deductingConsumeDate by mutableStateOf<String?>(null)
        private set

    /**
     * 未支付列表请求的单调序号。
     *
     * ⚠️ 不能省。刷新入口有四个（钱包页进入、下拉刷新、主页下拉、代扣成功后），
     * `AnimatedContent` 切页时新旧页面还会并存几百毫秒——**多个请求同时在途是常态**。
     * 没有序号的话，先发后到的旧响应会把刚扣掉的那笔「复活」，
     * 用户以为没扣成功再点一次，就是**扣两次**。
     */
    private var unpaidSeq = 0

    /** 拉一次未支付列表；失败返回 null（调用方保持原样，不清空） */
    private suspend fun fetchUnpaid(): List<UnpaidBill>? = try {
        val resp = NetworkModule.apiService.queryUnpaidBillsSafe(NetworkModule.authFields())
        if (resp.success) resp.data ?: emptyList() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 拉未支付账单列表。**失败保持原样，不清空**——清空会让按钮凭空消失 */
    fun loadUnpaidBills() {
        if (!PrefsHelper.isLoggedIn) return
        val seq = ++unpaidSeq
        sessionScope().launch {
            // 只接受最新一次请求的结果，见 [unpaidSeq]
            val list = fetchUnpaid() ?: return@launch
            if (seq == unpaidSeq) unpaidBills = list
        }
    }

    // ── 代扣 ──

    /**
     * 单笔代扣的结果。**「失败」和「结果未知」必须分开**。
     *
     * 分不清的代价很实在：请求已经发到服务端、只是没拿到答复的时候，
     * 如果报「失败」，界面就会把按钮放回去让用户重试——而服务端可能已经扣过了，
     * 那一下就是**扣两次**。见 [deductOnce]。
     */
    sealed interface DeductResult {
        data class Ok(val consumeDate: String) : DeductResult

        /** 服务端**明确**说没成（余额不足、账户异常…）。可以放心重试 */
        data class Failed(val consumeDate: String, val reason: String?) : DeductResult

        /**
         * 请求发出去了，但没拿到答复（超时、连接断开、会话被取消…）。
         *
         * **绝对不能当成失败**，也不该让用户重试，只能重新查一次服务端状态来判断。
         */
        data class Unknown(val consumeDate: String) : DeductResult
    }

    /** 批量补扣的汇总 */
    data class DeductSummary(
        val ok: Int,
        val failed: Int,
        /** 结果未知的笔数——这些既不能说成功也不能说失败 */
        val unknown: Int,
        /**
         * 第一笔失败时服务端给的原文（「余额不足」之类）。
         *
         * 批量路径以前只累加计数、把原因丢了，用户只能看到「请到钱包页重试」。
         */
        val firstFailReason: String? = null
    )

    /**
     * 扣一笔。**这是唯一碰代扣接口的地方**，单笔和批量都走它，
     * 免得两处流程各写一遍、改一处漏一处（而这是动钱的路径）。
     *
     * ⚠️ `CancellationException` 必须**先于** `Exception` 捕获并重抛：
     * 它是 `Exception` 的子类，混进下面的 catch 里就会被当成「扣款失败」，
     * 而请求其实已经发出去了。详见 [DeductResult.Unknown]。
     */
    private suspend fun deductOnce(consumeDate: String): DeductResult = try {
        val resp = NetworkModule.apiService
            .requestDeductSafe(consumeDate, NetworkModule.authFields())
        if (resp.success) DeductResult.Ok(consumeDate)
        else DeductResult.Failed(consumeDate, resp.displayMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 超时 / 断连：请求**可能已经到达服务端**，不能当作失败
        DeductResult.Unknown(consumeDate)
    }

    /**
     * 「结果未知」的收尾：重查一次服务端，以它为准。
     *
     * 账单还在列表里 → 说明没扣成（服务端会拒绝重复扣）；不在了 → 说明扣成了。
     * 这是唯一能确定答案的办法，**不能靠猜**。
     */
    private suspend fun reconcileUnpaid(): List<UnpaidBill>? {
        val list = fetchUnpaid() ?: return null
        // 让更早发出的刷新全部作废——这份是**刚刚**拿到的，比它们都新。
        // 不推序号的话，一个还在路上的旧响应回来后会把这里的结论覆盖掉
        unpaidSeq++
        unpaidBills = list
        return list
    }

    /**
     * 手动请求代扣（单笔）。
     *
     * ⚠️ **这是动钱的接口**，防重是第一要务：
     * - [deductingConsumeDate] 非空时**回调告知被挡住**，不静默 return
     *   （静默的话调用方已经把弹窗关了，用户点完什么都不发生）
     * - 成功后立刻把这条从列表里摘掉，不等网络往返
     * - 结果未知时不摘、也不报失败，重查服务端定夺
     */
    fun requestDeduct(consumeDate: String, onResult: (DeductResult) -> Unit) {
        if (consumeDate.isBlank()) return
        if (deductingConsumeDate != null) {
            // ⑧ 不能静默返回——调用方已经关了弹窗，用户会以为在扣
            onResult(DeductResult.Failed(consumeDate, BUSY_MESSAGE))
            return
        }

        deductingConsumeDate = consumeDate
        sessionScope().launch {
            try {
                val r = deductOnce(consumeDate)
                when (r) {
                    is DeductResult.Ok -> {
                        unpaidBills = unpaidBills.filterNot { it.consumeDate == consumeDate }
                        loadUnpaidBills()
                        loadBills()
                    }
                    is DeductResult.Unknown -> reconcileUnpaid() ?: loadUnpaidBills()
                    is DeductResult.Failed -> Unit
                }
                onResult(r)
            } catch (_: CancellationException) {
                // 会话级取消（挤号 / 退出登录 / 重新登录）。这里**不能报失败**——
                // 请求可能已经发出去了。清掉锁就够了，界面马上就会被替换掉
            } finally {
                deductingConsumeDate = null
            }
        }
    }

    /**
     * 补扣**全部**未支付账单，一笔一笔来。
     *
     * 开阀被未扣账单卡住时用这个：只补一笔往往还是开不了阀，
     * 而让用户回「钱包」页一笔一笔点太绕。
     *
     * @param onResult 传 null 表示**一笔都没发出去**（没有可补的账单，或者被另一个代扣挡住了）
     */
    fun deductAllUnpaid(onResult: (DeductSummary?) -> Unit) {
        if (deductingConsumeDate != null) {
            onResult(null)          // ⑧ 被挡住了也要回调，不能静默
            return
        }
        // 用和界面显示完全一致的来源，并且去重——review 发现过这里没去重，
        // 而 unpaidConsumeDates 去了（.toSet()），同一份数据两个口径
        val dates = unpaidConsumeDates.toList()
        if (dates.isEmpty()) {
            onResult(null)
            return
        }

        deductingConsumeDate = dates.first()   // 同步置锁，和 requestDeduct 一致
        sessionScope().launch {
            var ok = 0
            var failed = 0
            var unknown = 0
            var failReason: String? = null
            try {
                for (d in dates) {
                    deductingConsumeDate = d
                    when (val r = deductOnce(d)) {
                        is DeductResult.Ok -> {
                            ok++
                            unpaidBills = unpaidBills.filterNot { it.consumeDate == d }
                        }
                        is DeductResult.Failed -> {
                            failed++
                            if (failReason == null) failReason = r.reason
                        }
                        is DeductResult.Unknown -> unknown++
                    }
                }
                // 有未知的才需要重查；全部明确成功时直接刷新即可
                if (unknown > 0) reconcileUnpaid() ?: loadUnpaidBills() else loadUnpaidBills()
                loadBills()
                onResult(DeductSummary(ok, failed, unknown, failReason))
            } catch (_: CancellationException) {
                // 会话被取消，剩下的都没发出去。不回调——界面马上要被替换掉
            } finally {
                deductingConsumeDate = null
            }
        }
    }

    companion object {
        /** 被另一个代扣挡住时给用户的说法 */
        const val BUSY_MESSAGE = "正在处理另一笔代扣，请稍候"

        /**
         * 事后对账补发通知的时间窗。
         *
         * 结算当时可能因为服务端还没算完而误判成「无消费」，账单下来后对账能拿到
         * 真金额。但那可能是几小时之后——那时用户早就不在等这条通知了，
         * 凭空弹一条「消费 ¥x.xx」只会吓人一跳。所以只补最近 30 分钟内的。
         */
        private const val SETTLEMENT_NOTIFY_GRACE_MS = 30 * 60 * 1000L
    }

    /** 关掉开阀失败弹窗 */
    fun clearShowerError() { showerError = null }

    /**
     * 拉学校名填进「学校」那一行。
     *
     * 服务端的 `projectName` 就是学校名，本来就有——以前这里是写死的默认值，
     * 让用户自己填，纯属多余。**拉不到就保持原样**（默认值或用户手动填过的），
     * 不覆盖成空串。
     */
    fun loadSchoolName() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                val n = NetworkModule.apiService.getProjectInfoSafe().data?.projectName
                if (!n.isNullOrBlank() && n != PrefsHelper.schoolName) {
                    PrefsHelper.schoolName = n
                    schoolName = n
                }
                // 顺手记下「这个 projectId 叫什么」：切换列表要显示每个板块的名字，
                // 而 /project/info/triple 只能问当前板块，所以只能切一个记一个
                if (!n.isNullOrBlank()) {
                    PrefsHelper.projectId.toLongOrNull()?.let { pid ->
                        PrefsHelper.rememberProjectName(pid, n)
                        projectNames = PrefsHelper.projectNames()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 拉账号绑定的板块列表。
     *
     * 失败的静默处理：拿不到列表就保持单板块的样子（`showProjectSwitcher` 为 false），
     * 不影响原有洗浴功能——这是这次改动唯一的硬约束。
     */
    fun loadProjects() {
        if (!PrefsHelper.isLoggedIn) return
        // 当前板块以**认证参数**为准。MainViewModel 是在登录前就建好的（见 MainActivity），
        // 构造时读到的 projectId 可能是上一任账号的，所以每次拉列表都重新对齐一次。
        currentProjectId = ProjectRepository.currentProjectId()
        // 先用缓存渲染一次：冷启动时不让切换入口闪一下才冒出来
        projects = ProjectRepository.cachedProjects()
        projectNames = ProjectRepository.projectNames()
        sessionScope().launch {
            val list = ProjectRepository.loadProjects().getOrNull() ?: return@launch
            projects = list
            projectNames = ProjectRepository.projectNames()
        }
    }

    /**
     * 切换到另一个板块。
     *
     * ⚠️ **有进行中的订单时拒绝切换**：那笔订单是用旧 accountId 开的，
     * 换掉认证参数之后关阀和结算都会失败，用户会被卡在"水还在放、关不掉"。
     * 和退出登录用同一条判断（[canLogoutVoluntarily]）。
     */
    fun switchProject(projectId: Long) {
        if (isSwitchingProject) return
        if (projectId == currentProjectId) return
        if (!canLogoutVoluntarily(activeOrders)) {
            toastMessage = "还有设备正在使用，结束后才能切换板块"
            return
        }
        isSwitchingProject = true
        sessionScope().launch {
            val target = try {
                ProjectRepository.switchTo(projectId).getOrNull()
            } catch (e: Exception) {
                null
            }
            if (target == null) {
                toastMessage = "切换板块失败，账号未绑定该板块"
                isSwitchingProject = false
                return@launch
            }
            // 认证参数已经整体换掉了，内存里所有"按项目维度"的数据都必须复位，
            // 否则界面会拿着上个板块的钱包/账单/设备继续显示（串号）。
            currentProjectId = target.projectId
            resetProjectScopedState()
            refreshWallet()
            loadBills()
            loadCampusBalance()
            loadSchoolName()
            loadUnpaidBills()
            // 姓名/学号和使用码属于 accountId 维度，换板块后重拉一遍
            loadAccountInfo()
            loadUseCode()
            toastMessage = "已切换到${ProjectSelectionPolicy.label(target.projectId, projectNames)}"
            isSwitchingProject = false
            // 设备列表要重扫：扫描需要蓝牙权限，只能由界面发起（见 MainScreen）。
            // 这里只发一个"板块变了"的通知，扫不扫由界面决定。
            projectSwitchTick++
        }
    }

    /**
     * 板块名字。
     *
     * 兜底顺序见 [ProjectSelectionPolicy.label]：记住的真名 → 业务名 → 「项目 id」。
     *
     * 业务名兜底是给**没切过的板块**用的：`/project/info/triple` 只能问当前板块，
     * 没去过的那条本来只有 id。正式发布时显示「项目 3356」用户会以为是 bug，
     * 所以按已知的 projectId → 业务名做一个映射。
     *
     * ⚠️ 这个映射只是**显示兜底**，不参与任何业务判断。
     * 换学校时映射可能对不上，但那时真名早就从接口拿到了，不会走到这里。
     */
    fun projectLabel(projectId: Long?): String =
        ProjectSelectionPolicy.label(projectId, projectNames, knownBoardName(projectId))

    /**
     * 板块的**短名**，只给「标题里拼一句话」这类地方用（如余额标题）。
     *
     * 和 [projectLabel] 的差别只在**去掉学校前缀**：`/project/info/triple` 的
     * `projectName` 是「安徽三联学院洗浴」这种全名，直接拼进标题会变成
     * 「趣智校园安徽三联学院洗浴余额」——一句话里重复了学校名，太长。
     *
     * 板块切换列表仍然用 [projectLabel]（全名），那里需要分清「哪个学校」。
     */
    fun projectShortLabel(projectId: Long?): String? {
        val name = ProjectSelectionPolicy.label(projectId, projectNames, knownBoardName(projectId))
        // 「未知板块」/「项目 3356」是兜底文案，不是真名：这种情况下**不拼进标题**，
        // 让标题退回「趣智校园余额」。宁可少写，也不要在正式版里露出裸 id。
        if (name.startsWith("项目 ") || name == "未知板块") return null
        val pid = projectId ?: return null
        return BillMerger.shortProjectName(name, pid)
    }

    /** 已知板块 id → 业务名（仅用于真名还没拿到时的显示兜底） */
    private fun knownBoardName(projectId: Long?): String? = when (projectId) {
        // 某某大学：4000 洗浴 / 3356 洗衣饮水吹风。
        // 别的学校 id 不同，会走「项目 id」兜底——不影响正确性，只是不够好看。
        4000L -> "洗浴"
        3356L -> "洗衣饮水吹风"
        else -> null
    }

    /**
     * 清掉内存里按项目维度缓存的状态。
     *
     * 落盘的那部分由 [PrefsHelper.clearProjectScopedCache] 负责，这里是**内存副本**——
     * 两边都要清，少一边就会先亮一个错数字再跳变。
     */
    private fun resetProjectScopedState() {
        walletInfo = null
        walletBalance = null
        campusBalance = null
        schoolName = ""
        billList = emptyList()
        billHistory = emptyList()
        billsLoaded = false
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
        // 姓名 / 学号 / 使用码也是**按 accountId** 的，两个板块的 accountId 不同。
        // 同名同人大多一样，但校园卡绑定状态和使用码本来就是各账户一份，
        // 拿着上个板块的码去用会直接被服务端拒掉——宁可显示「加载中」让它重拉。
        accountInfo = null
        useCodeData = null
        useCodeLoaded = false
        nearbyDevices.clear()
        activeDeviceSnCodes.clear()
        selectedDevice = null
        showDeviceDetail = false
        lastDeviceName = ""
        lastDeviceMac = ""
        lastDeviceSnCode = ""
        lastDeviceEmoji = "🚿"
    }

    /**
     * 拉一卡通真实余额。
     *
     * **拿不到就保持 null**，界面回退到本地估算——服务端只在学生签约过
     * 校园卡免密支付时才给 `amount`。
     */
    fun loadCampusBalance() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                val d = NetworkModule.apiService.getCampusUserInfoSafe().data ?: return@launch
                val amount = d.amount?.toDoubleOrNull()
                if (amount != null) {
                    campusBalance = amount
                    PrefsHelper.campusBalance = d.amount.orEmpty()
                    PrefsHelper.campusBalanceTime = System.currentTimeMillis()
                    // 桌面小组件的余额是**渲染时**从 Prefs 读的，不会自己知道值变了。
                    // 不推一次刷新，它会一直顶着上次渲染的估算值——
                    // 用户看到的就是「App 里是真余额、桌面上还是估算」。
                    appContext?.let { LinYuWidget.refreshAll(it) }
                }
                // 这个接口顺带也给姓名和学号。它是从**校园卡**那边查的，
                // 和 /account/info 的数据来源不同——学校没同步学籍时前者为 null、
                // 这里却有值，正好互补。以前只取了学号，把姓名漏了。
                applyIdentity(d.studentName, d.studentNumber, null)
            } catch (_: Exception) {}
        }
    }

    /**
     * 刷新余额与账单。
     * 扫描不在这里触发——是否扫描由界面决定（要先确认拿到权限），
     * 见 MainScreen 的 scanWithPermission()。
     */
    fun pullRefresh() {
        refreshWallet()
        loadBills()
        // 一卡通余额是外部账户的钱，会随时变（食堂刷卡、充值都会动），
        // 下拉刷新时一起拉一次
        loadCampusBalance()
        // 残留账单同理——在别处（比如官方 App）补扣掉了，这边也得跟着消失
        loadUnpaidBills()
        // 主页「使用中的设备」卡片：用户实测下拉刷新后它还是老样子。
        // 原来这里不重新同步，于是那张卡片只有等别处触发 syncActiveOrdersFromPrefs 才更新。
        syncActiveOrdersFromPrefs()
        // 套餐单同理：到点了但用户没点「刷新核对」时，下拉刷新应该帮他核对一次
        modePending?.let { p ->
            if (p.isLikelyFinished(System.currentTimeMillis())) refreshModeOrder(p.snCode)
        }
    }

    // ── 设备发现 ──
    fun addDevice(device: NearbyDevice) {
        val idx = nearbyDevices.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            val e = nearbyDevices[idx]
            if (abs(e.rssi - device.rssi) > 5) nearbyDevices[idx] = e.copy(rssi = device.rssi)
        } else {
            nearbyDevices.add(device)
        }

        // ⚠️ 每台设备**每轮扫描只查一次**。
        //
        // 扫描回调对同一台设备每 ~200ms 就来一条，而"只要这台还没有 deviceInfo 就再查一次"
        // 会让**查不到**的设备被无限重查（实测日志里 13 秒对同一个 MAC 发了 35 次）。
        // 设备信息在一轮扫描内不会变，想重试交给下拉刷新（会重新 startScan 并清空本集合）。
        if (queriedThisScan.add(device.mac)) fetchInfo(device.mac)
    }

    private fun fetchInfo(mac: String) {
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
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
        }
    }

    // ── 账单 ──
    fun loadBills() {
        sessionScope().launch {
            isLoadingBills = true
            val cacheKey = PrefsHelper.userId.ifEmpty { PrefsHelper.telephone }
            val cached = PrefsHelper.getCachedBills(cacheKey)
            if (cached.isNotEmpty()) {
                billHistory = cached
                billList = cached.take(20)
                billsLoaded = true
            }
            try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                val cal = java.util.Calendar.getInstance()
                val all = mutableListOf<BillItem>()
                var allRequestsSucceeded = true
                for (i in 0..2) {
                    val month = fmt.format(cal.time)
                    val resp = NetworkModule.apiService.getBillListSafe(month = month)
                    if (!resp.success) allRequestsSucceeded = false
                    if (resp.success && !resp.data.isNullOrEmpty()) all.addAll(resp.data)
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                billHistory = all.toList()
                billList = all.take(20)
                billsLoaded = true
                if (allRequestsSucceeded) PrefsHelper.saveCachedBills(cacheKey, all)
                val pendingBefore = PrefsHelper.getPendingSettlements()
                val reconciliation = SettlementReconciler.reconcile(pendingBefore, all)
                val pendingBySn = pendingBefore.associateBy { it.snCode }
                reconciliation.updates.forEach { update ->
                    PrefsHelper.recordConsume(update.snCode, update.amount)

                    // 结算那会儿要是误判成了「无消费」，这条通知现在还挂着错误金额——
                    // 拿到了真金额就得把它改成对的，否则用户看到的一直是错的。
                    //
                    // ⚠️ 只补最近 [SETTLEMENT_NOTIFY_GRACE_MS] 内的。超过这个时间
                    // 用户早就不在等这条通知了，凭空弹一条「消费 ¥x.xx」只会吓人一跳。
                    val p = pendingBySn[update.snCode] ?: return@forEach
                    val ageMs = System.currentTimeMillis() - p.createdAt
                    if (ageMs <= SETTLEMENT_NOTIFY_GRACE_MS) {
                        appContext?.let { ctx ->
                            Notifier.showFinished(
                                ctx,
                                p.deviceName.ifEmpty { "热水器" },
                                p.elapsedSec,
                                update.amount
                            )
                        }
                        AppLogger.i(
                            "结算对账补发通知：${p.snCode} ¥${update.amount}（延迟 ${ageMs / 1000}s）"
                        )
                    }
                }
                PrefsHelper.savePendingSettlements(reconciliation.remaining)
                // 顺手把最近一笔消费记给桌面小组件。账单按月倒序拉取，第一条就是最新的。
                // ⚠️ 只有确认它属于「上次使用设备」时才记——账单接口不带 snCode，只能拿设备名比对。
                // 比不中就跳过：宁可这次不记录，也别把别的设备的消费记到当前设备头上。
                val newest = all.firstOrNull()?.consumeBillDTO
                val lastSn = PrefsHelper.lastDeviceSnCode
                if (newest != null && lastSn.isNotEmpty() &&
                    newest.displayDesc == PrefsHelper.lastDeviceName
                ) {
                    newest.consumeMoney.toDoubleOrNull()
                        ?.let { PrefsHelper.recordConsume(lastSn, it) }
                }
                // ⚠️ 必须和上面 billList 用的条数一致。
                // 小组件用这份缓存算「初始余额 − 消费」的估算值，
                // 只给 2 笔的话减数偏小，桌面上的余额会比 App 里高出一截。
                // 界面上只渲染 2 行，多存的不影响显示。
                cacheBillsForWidget(all.take(20))
                refreshWidgets()
                // 账单到位后再拉其它板块做合并（失败不影响上面任何东西）
                loadMergedTrend()
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

    /**
     * 拉「消费合并」需要的其它板块账单，合并成一份给趋势图用。
     *
     * ## 什么时候跑
     *
     * [loadBills] 成功之后调一次。没开合并（[PrefsHelper.canMergeConsume] 为 false）
     * 或只有单个板块时直接清空 [mergedBills]，趋势图退回单板块数据。
     *
     * ## 为什么单独一个函数、而不是塞进 loadBills
     *
     * 合并要**临时切认证参数**去别的板块读账单，这件事风险独立于账单本身：
     * 它失败了不该影响 [loadBills] 的结果（用户至少还能看当前板块的账）。
     * 分开之后失败只记日志、清空 mergedBills，一切照常。
     *
     * ## 顺序与并发
     *
     * - 用 [isMerging] 挡住重入：合并期间认证参数是别人的，绝不能并发
     * - 跳过**当前板块**（它的账单已经在 [billHistory] 里，再拉一遍纯浪费）
     * - 失败逐个跳过，能合几个算几个
     */
    fun loadMergedTrend() {
        val others = projects.filter { it.projectId != currentProjectId }
        if (!PrefsHelper.canMergeConsume || others.isEmpty()) {
            // 关掉合并、或单板块：清空，趋势图自动退回 billHistory
            mergedBills = null
            mergedFrom = emptyList()
            return
        }
        if (isMerging) return
        isMerging = true
        sessionScope().launch {
            try {
                val boards = mutableListOf<BillMerger.BoardBills>()
                val names = ProjectRepository.projectNames()

                // 当前板块直接用已有数据，不重复请求
                if (billHistory.isNotEmpty()) {
                    val curId = currentProjectId ?: 0L
                    boards += BillMerger.BoardBills(
                        projectId = curId,
                        projectName = names[curId] ?: schoolName,
                        bills = billHistory
                    )
                }

                for (p in others) {
                    val pid = p.projectId ?: continue
                    val fetched = ProjectRepository.withBoardContext(pid) {
                        fetchRecentBills()
                    } ?: continue
                    boards += BillMerger.BoardBills(
                        projectId = pid,
                        projectName = names[pid] ?: "",
                        bills = fetched
                    )
                }

                // 只有一个板块有数据时没必要合并
                if (boards.size < 2) {
                    mergedBills = null
                    mergedFrom = emptyList()
                    return@launch
                }
                val merged = BillMerger.merge(boards)
                mergedBills = merged
                mergedFrom = boards
                AppLogger.i(
                    "消费合并：${boards.size} 个板块 / ${merged.size} 条，" +
                        "合计 ¥${String.format("%.2f", BillMerger.totalYuan(merged))}"
                )
            } catch (e: Exception) {
                // 合并是锦上添花，失败就退回单板块，绝不打扰用户
                AppLogger.w("消费合并失败，退回单板块：${e.message}")
                mergedBills = null
                mergedFrom = emptyList()
            } finally {
                isMerging = false
            }
        }
    }

    /**
     * 拉最近 3 个月账单（和 [loadBills] 同口径）。
     *
     * 抽出来是给 [ProjectRepository.withBoardContext] 复用的：
     * 那段逻辑跑在"别人的认证参数"下，不能碰任何 UI 状态。
     */
    private suspend fun fetchRecentBills(): List<BillItem> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        val out = mutableListOf<BillItem>()
        for (i in 0..2) {
            val month = fmt.format(cal.time)
            val resp = try {
                NetworkModule.apiService.getBillListSafe(month = month)
            } catch (_: Exception) {
                break
            }
            if (resp.success && !resp.data.isNullOrEmpty()) out.addAll(resp.data)
            cal.add(java.util.Calendar.MONTH, -1)
        }
        return out
    }

    /** 开关变化后重算。设置页调它，让趋势图立刻反映新设置 */
    fun refreshMergeState() {
        mergedBills = null
        mergedFrom = emptyList()
        if (PrefsHelper.canMergeConsume) loadMergedTrend()
        refreshWidgets()
    }
}
