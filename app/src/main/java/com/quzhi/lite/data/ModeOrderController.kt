package com.quzhi.lite.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.quzhi.lite.api.NetworkModule
import com.quzhi.lite.api.getModeListSafe
import com.quzhi.lite.api.lastOrderByMacSafe
import com.quzhi.lite.api.modeOrderSafe
import com.quzhi.lite.api.washerStatusSafe
import com.quzhi.lite.model.ModeOrderInfo
import com.quzhi.lite.model.WashMode
import com.quzhi.lite.model.WasherStatusData
import com.quzhi.lite.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 本链路自己抛的业务异常。
 *
 * 带 [errorCode] 是为了让上层能区分「服务端拒绝」和「断网」——
 * 前者让用户重试毫无意义，后者重试才有用。文案会因学校而变，错误码相对稳定。
 */
class ModeOrderException(message: String, val errorCode: Int? = null) : Exception(message)

/** 下单结果。**三种，缺一不可**——见 [ModeOrderController.placeOrder] 的说明。 */
sealed interface PlaceOrderResult {
    /** 已下单且拿到了 orderNo */
    data class Placed(val orderNo: String) : PlaceOrderResult

    /**
     * **钱没花出去**：服务端明确拒绝（余额不足 / 设备离线 / 没有权限 …）。
     *
     * @param errorCode 服务端业务错误码；**本地校验不通过（参数缺失、已有进行中的单）时为 null**
     */
    data class Failed(val message: String, val errorCode: Int? = null) : PlaceOrderResult

    /**
     * **钱很可能已经扣了，但 orderNo 还没拿到**。
     *
     * 不能报 [Failed]：`modeOrder` 一发出就扣套餐全款、不可退、没有取消接口。
     * 谎报失败会让用户再点一次，于是**扣第二份钱**。
     * 这个状态下界面必须显示「已开始，正在获取订单信息」，并允许稍后重试 [ModeOrderController.awaitOrderNo]。
     */
    data object AwaitingOrderNo : PlaceOrderResult
}

/** 设备状态三态。**请求失败 ≠ 空闲**，所以 [Unknown] 必须存在。 */
sealed interface ModeStatus {
    /** 明确占用中（本机有进行中的套餐单，或服务端数据非空） */
    data class Busy(val reason: String) : ModeStatus

    /** 明确空闲（服务端 `data == null`，且本地没有进行中的单） */
    data object Idle : ModeStatus

    /** **不知道**（断网、超时、解析失败）——界面不能据此放开下单按钮 */
    data class Unknown(val reason: String?) : ModeStatus
}

/**
 * 本机记着的「进行中的套餐订单」。
 *
 * 只用于两件事：显示倒计时、防止重复下单。**它不是「设备是否被占用」的判据**——
 * 服务端到点自动关停且不会通知我们，本地时间到了也只能是「大概率结束」，
 * 想确认只能再查一次接口（见 [ModeOrderController.queryStatus]）。
 */
data class PendingModeOrder(
    val snCode: String,
    /** 下单时用的 `xfModel`（= 套餐 `modeId`） */
    val modeId: Int?,
    /** 套餐时长（秒），来自 `modeTimeQuantity` */
    val modeSeconds: Int,
    /** 套餐价（厘），来自 `modeMoney` */
    val modeMoneyMilli: Int?,
    /** 下单那一刻的**本机**时间戳（毫秒）。倒计时以它为准，不取服务端时间 */
    val placedAtMs: Long,
    /** orderNo；MQTT / byMac 还没补上时是空串 */
    val orderNo: String = ""
) {
    fun remainingSeconds(nowMs: Long): Int =
        ModeOrderPolicy.remainingSeconds(placedAtMs, modeSeconds, nowMs)

    fun isLikelyFinished(nowMs: Long): Boolean =
        ModeOrderPolicy.isLikelyFinished(placedAtMs, modeSeconds, nowMs)
}

/**
 * 洗衣机 / 吹风机「按套餐下单」的控制器。
 *
 * 两者**共用这一条通道**：套餐接口、下单接口、状态接口完全相同，
 * 只有 `smallTypeId`（洗衣机 3 / 吹风机 5）和套餐档位不同。
 *
 * ## ⚠️ 这是钱，不是水
 *
 * [placeOrder] **一发出去就扣套餐全款**，而服务端**没有任何** stop / close / cancel / refund
 * 接口（洗衣机、吹风机两次完整抓包都证实了）。套餐到点由服务端自动关停。
 *
 * 因此**调用方必须自己承担以下三条**，本类替你做不了：
 *
 * 1. **二次确认 UI**：点下单前必须让用户看清「X 元 / N 分钟」，并明确告知
 *    「立即扣款、不可退款、到点自动结束」。不要做成「点一下就开始」。
 * 2. **防重复点击**：本类只挡「本地已有进行中的单」这一种情况，
 *    **挡不住双击造成的两次并发请求**（两次都在 pending 写盘之前）。UI 必须自己置灰按钮。
 * 3. **文案不写「停止」**：没有这个功能，写「到点自动结束」。
 *
 * ## 构造参数里那两个 lambda 是干嘛的
 *
 * 「进行中的单」要跨进程存活。为了**不改** `utils/PrefsHelper.kt`（不归本任务管），
 * 这里用构造参数注入最朴素的读写接口，Lead 后面直接接上即可：
 *
 * ```kotlin
 * ModeOrderController(
 *     readPref  = { PrefsHelper.readRaw(it) },
 *     writePref = { k, v -> PrefsHelper.writeRaw(k, v) }   // v 为空串表示删除
 * )
 * ```
 *
 * 存储格式是单个字符串，键见 [PREF_KEY_PREFIX]，内容见 [encode] / [decode]。
 *
 * ## 这个类不做什么
 *
 * - **不连 MQTT**：不改 `MqttManager`，MQTT 消息从外部转发进 [onMqttMessage] 即可
 * - **不轮询状态、不做倒计时循环**：抓包证明 180 秒内 App 零请求，
 *   服务端没有「结束」事件可等，倒计时是纯本地的
 * - **不碰** `ShowerController` / `SettlementEngine` / `ShowerWatchService`（热水器链路，别混）
 */
class ModeOrderController(
    private val readPref: (String) -> String?,
    private val writePref: (String, String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** 持久化键前缀。完整键 = [PREF_KEY_PREFIX] + snCode */
        const val PREF_KEY_PREFIX = "mode_order_"

        /** `lastOrder/byMac` 轮询间隔。它是免费 GET，但没必要打太密 */
        private const val BY_MAC_POLL_MS = 800L

        /**
         * 判定「byMac 查到的是**这一单**」允许的时间倒挂。
         *
         * 手机时间与服务端时间天然有几十秒偏差，`consumeDate` 是服务端时间。
         * 5 分钟足够覆盖正常偏差，又远小于两单的间隔（最短套餐也 3 分钟，
         * 但同一台设备不会在上一次结束后 5 分钟内又冒出一单旧记录）。
         */
        private const val FRESH_SLACK_MS = 5 * 60 * 1000L

        /**
         * 套餐单的 MQTT 场景值。抓包样本：吹风机 `consumeSceneType = 4`。
         *
         * ⚠️ 只有**一个**样本、且只有吹风机。所以只在推送里**没有** `deviceSnCode`
         * 时用作弱判据；有 `deviceSnCode` 时一律以它为权威。
         */
        private const val MODE_CONSUME_SCENE_TYPE = 4

        /**
         * 「设备正在使用中」的业务错误码。
         *
         * 2026-10-24 09:51 实测（洗衣机被别人的单占着）：
         * `{"success":true,"errorCode":307,"errorMessage":"设备正在使用中", ...}`
         *
         * ⚠️ `success` **仍然是 true**——只看 success 会以为查询成功、然后拿
         * `data != null` 去猜。必须显式判这个码。
         * 和热水器 `queryUsing` 的 307 是同一个语义。
         */
        private const val ERROR_IN_USE = 307
    }

    private val gson = Gson()

    /** 最近一条通过过滤的 MQTT 下单推送。`@Volatile`：写入来自 MQTT 回调线程 */
    @Volatile
    private var lastPush: MqttModePush? = null

    // ════════════════════════════════════════════
    //  套餐列表
    // ════════════════════════════════════════════

    /**
     * 拉套餐列表。
     *
     * @param snCode 只用于日志定位设备（接口按 `smallTypeId` 取，与设备无关）
     * @param smallTypeId 洗衣机 [ModeOrderPolicy.SMALL_TYPE_WASHER]=3，吹风机 [ModeOrderPolicy.SMALL_TYPE_DRYER]=5
     *
     * @throws ModeOrderException 服务端拒绝或网络失败。
     *   ⚠️ **空列表不能当「这个设备没有套餐」**：断网时同样会拿到空，
     *   而两种情况的处理完全不同（一个让用户重试，一个让他找管理员）。
     */
    @Throws(ModeOrderException::class)
    suspend fun loadModes(snCode: String, smallTypeId: Int): List<WashMode> {
        val resp = try {
            NetworkModule.apiService.getModeListSafe(smallTypeId.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("拉套餐列表失败（$snCode, smallTypeId=$smallTypeId）", e)
            throw ModeOrderException("套餐列表查询失败：${e.message}")
        }
        if (!resp.success) {
            AppLogger.w("拉套餐列表被拒：$snCode errCode=${resp.errorCode} msg=${resp.displayMessage}")
            throw ModeOrderException(resp.displayMessage ?: "套餐列表查询失败", resp.errorCode)
        }
        val modes = resp.data.orEmpty()
        AppLogger.i("套餐列表：$snCode smallTypeId=$smallTypeId 共 ${modes.size} 档")
        return modes
    }

    // ════════════════════════════════════════════
    //  下单（★ 扣钱）
    // ════════════════════════════════════════════

    /**
     * 按套餐下单 —— **一发出就扣全款，不可退，无法取消**。
     *
     * 流程：本地防重 → `POST modeOrder` → 尽力补 orderNo（MQTT / byMac）。
     *
     * ⚠️ 服务端响应**恒为 `{"data":null}`**，`success=true` 是唯一的成功判据。
     * 所以本函数**不会**在 `success` 后就返回 [PlaceOrderResult.Placed]：
     * 它会在 [timeoutMs] 内尽力补 orderNo，补不到就返回
     * [PlaceOrderResult.AwaitingOrderNo]——那是「钱花了、单号还不知道」，**不是失败**。
     *
     * @param mode 必须是用户**确认过的那一档**。`mode.id` **不能**代替 `mode.modeId` 当 `xfModel` 传。
     * @param timeoutMs 补 orderNo 的预算。默认 4 秒（MQTT 实测几十毫秒就到，
     *   byMac 是免费 GET）。太长会让用户盯着按钮转圈。
     *
     * 网络异常**不会抛出**（那时钱可能已经扣了，抛异常会让调用方当成「没下单成功」而重试）；
     * 只在协程被取消时抛 [CancellationException]。
     */
    suspend fun placeOrder(
        snCode: String,
        mode: WashMode,
        timeoutMs: Long = 4_000L
    ): PlaceOrderResult {
        if (snCode.isBlank()) return PlaceOrderResult.Failed("设备信息不完整")
        val modeId = mode.modeId
            ?: return PlaceOrderResult.Failed("套餐信息不完整（缺少 modeId），请刷新套餐列表")

        // 防重：本地已有进行中的单，绝不再发一次 —— 每发一次就是一份钱
        pendingOrder(snCode)?.let { pending ->
            if (!pending.isLikelyFinished(nowMs())) {
                AppLogger.w("拒绝重复下单：$snCode 已有进行中的套餐单 orderNo=${pending.orderNo}")
                return PlaceOrderResult.Failed("这台设备已有进行中的套餐订单，请等它自动结束")
            }
            // 时间已过：服务端早该关停了，本地记录只是没清。往下走之前清掉
            clearPending(snCode)
        }

        val placedAt = nowMs()
        val draft = PendingModeOrder(
            snCode = snCode,
            modeId = modeId,
            modeSeconds = mode.modeTimeQuantity ?: 0,
            modeMoneyMilli = mode.modeMoney,
            placedAtMs = placedAt
        )
        AppLogger.i(
            "按套餐下单：sn=$snCode modeId=$modeId name=${mode.modeName} " +
                "money=${mode.modeMoney}厘 seconds=${mode.modeTimeQuantity}"
        )

        // **先落盘再发请求**：崩在请求途中也能知道「可能已经扣钱了」
        savePending(draft)
        // ⚠️ 清掉上一次的推送，否则同一台设备再下一单时，会把**上一单**的 orderNo 当成本单的
        lastPush = null

        val resp = try {
            NetworkModule.apiService.modeOrderSafe(
                xfModel = modeId.toString(),
                snCode = snCode,
                auth = NetworkModule.authFields()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ⚠️ 请求抛异常时**不知道钱扣没扣**（超时可能发生在服务端已受理之后）。
            // 所以不报 Failed，尽力补 orderNo；补不到就是 AwaitingOrderNo。
            // 报失败会骗用户再点一次 → 第二份钱。
            AppLogger.e("下单请求抛出（钱可能已扣）：$snCode modeId=$modeId", e)
            val recovered = awaitOrderNo(snCode, mac = snCode, timeoutMs = timeoutMs)
            return if (recovered != null) PlaceOrderResult.Placed(recovered) else PlaceOrderResult.AwaitingOrderNo
        }

        if (!resp.success) {
            // 服务端明确拒绝 = 没扣钱，可以把本地记录撤掉
            clearPending(snCode)
            AppLogger.w("下单被拒：$snCode modeId=$modeId errCode=${resp.errorCode} msg=${resp.displayMessage}")
            return PlaceOrderResult.Failed(resp.displayMessage ?: "下单失败", resp.errorCode)
        }

        // success=true，data 恒为 null —— 此时已扣款，只差 orderNo
        AppLogger.i("下单已受理（已扣款）：$snCode modeId=$modeId，开始补 orderNo")
        val orderNo = awaitOrderNo(snCode, mac = snCode, timeoutMs = timeoutMs)
        return if (orderNo != null) {
            AppLogger.i("下单完成：$snCode orderNo=$orderNo")
            PlaceOrderResult.Placed(orderNo)
        } else {
            AppLogger.w("下单已扣款但 ${timeoutMs}ms 内没拿到 orderNo：$snCode（界面应显示处理中）")
            PlaceOrderResult.AwaitingOrderNo
        }
    }

    /**
     * 补 orderNo：轮询 `lastOrder/byMac`（**免费**），同时看 MQTT 推送。
     *
     * 为什么两条路都要：`modeOrder` 的响应永远是 `data:null`，orderNo 只存在于
     * MQTT 推送里；但 broker 地址实测换过（洗衣机抓包 `47.107.37.60`，
     * 吹风机抓包 `39.108.147.136`，而 `MqttManager` 硬编码了前者），
     * 连不上就一条推送都收不到。byMac 是保底的免费查询。
     *
     * @param mac 12 位不带冒号的 MAC。本项目约定 `snCode == mac 去掉冒号`，
     *   所以 [placeOrder] 直接传 snCode；万一某校不成立，调用方可以传真实 MAC。
     * @return orderNo；预算内没拿到返回 null（**不是失败**，可以稍后再调）
     */
    suspend fun awaitOrderNo(snCode: String, mac: String, timeoutMs: Long = 5_000L): String? {
        val pending = pendingOrder(snCode)
        // 已经有了就不必再查
        pending?.orderNo?.takeIf { it.isNotBlank() }?.let { return it }

        val deadline = nowMs() + timeoutMs
        var waitMs = 0L
        while (true) {
            if (waitMs > 0) delay(waitMs)

            // ① MQTT 推送（最快，实测几十毫秒）
            val push = lastPush
            val pushOrderNo = push?.orderNo
            if (push != null && pushOrderNo != null && push.matches(snCode)) {
                AppLogger.i("orderNo 来自 MQTT：$snCode → $pushOrderNo")
                pending?.let { savePending(it.copy(orderNo = pushOrderNo)) }
                return pushOrderNo
            }

            // ② byMac 兜底（免费 GET）
            val info = try {
                NetworkModule.apiService.lastOrderByMacSafe(mac)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("lastOrder/byMac 查询失败（继续等）：${e.message}")
                null
            }
            val orderNo = info?.takeIf { it.success }?.data?.let { orderNoOf(it, pending) }
            if (orderNo != null) {
                AppLogger.i("orderNo 来自 lastOrder/byMac：$snCode → $orderNo")
                pending?.let { savePending(it.copy(orderNo = orderNo)) }
                return orderNo
            }

            if (nowMs() >= deadline) {
                AppLogger.w("补 orderNo 超时：$snCode（MQTT 未达且 byMac 无本单）")
                return null
            }
            waitMs = BY_MAC_POLL_MS
        }
    }

    // ════════════════════════════════════════════
    //  状态
    // ════════════════════════════════════════════

    /**
     * 设备状态，**三态**。
     *
     * 判据顺序：
     * 1. 本地有**未到点**的套餐单 → [ModeStatus.Busy]（不再发请求，这是本机下的单，可信）
     * 2. `POST washer/status` 且 `success && data == null` → [ModeStatus.Idle]
     * 3. `success && data != null` → [ModeStatus.Busy]（具体结构仍未抓到样本）
     * 4. 请求失败 / `success == false` → [ModeStatus.Unknown]
     *
     * ⚠️ **第 4 条绝不能返回 Idle。** 断网时把「不知道」当「空闲」，会让用户站在
     * 一台别人正在用的机器前看到「可使用」，点下去就扣他的钱。
     *
     * ⚠️ 吹风机也调这个接口（名字里的 washer 是历史包袱，不是洗衣机专用）。
     */
    suspend fun queryStatus(snCode: String): ModeStatus {
        pendingOrder(snCode)?.let { pending ->
            if (!pending.isLikelyFinished(nowMs())) {
                return ModeStatus.Busy("本机已下单，剩余约 ${pending.remainingSeconds(nowMs())} 秒")
            }
        }

        val resp = try {
            NetworkModule.apiService.washerStatusSafe(snCode, auth = NetworkModule.authFields())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("设备状态查询失败（判为不确定）：$snCode ${e.message}")
            return ModeStatus.Unknown(e.message)
        }

        return when {
            // 明确空闲
            resp.success && resp.data == null -> ModeStatus.Idle

            // ── 占用中（2026-10-24 09:51 首次抓到真实样本）──
            //
            // 实测响应（那台洗衣机正被**别人**用）：
            // ```json
            // {"success":true,"errorCode":307,"errorMessage":"设备正在使用中",
            //  "data":{"expireTimes":729,"orderNo":null,"isOwner":false,
            //          "accountId":null, ...}}
            // ```
            //
            // 三个关键事实：
            // 1. **`success` 仍是 true**，只有 `errorCode=307` 才是占用信号——
            //    只看 success 会误判
            // 2. `data` 非空，但 `orderNo` 是 **null**（别人的单，不给单号）
            // 3. **`expireTimes` 是剩余秒数**，实测 729 → 649 → 589 逐轮递减，
            //    正好是「快速洗 25 分钟」那单剩下的时间
            //
            // 判据与热水器的 `queryUsing` 一致：`307 || (success && data != null)`
            resp.errorCode == ERROR_IN_USE -> ModeStatus.Busy(busyReason(resp.data))

            resp.success -> ModeStatus.Busy(busyReason(resp.data))

            // 业务拒绝但不是 307：保守报「不确定」，**绝不报空闲**
            else -> {
                AppLogger.w("设备状态查询被拒：$snCode errCode=${resp.errorCode} msg=${resp.displayMessage}")
                ModeStatus.Unknown(resp.displayMessage)
            }
        }
    }

    /**
     * 从占用态的 `data` 拼一句人话。
     *
     * 实测字段：`expireTimes`（剩余秒）、`isOwner`（是不是自己的单）。
     * 两者都可能缺失，所以**都要容错**，拼不出来也要返回一句能看的。
     */
    private fun busyReason(data: WasherStatusData?): String {
        val left = data?.expireTimes
        val owner = data?.isOwner
        val who = when (owner) {
            true -> "你自己的单"
            false -> "别人的单"
            else -> "有进行中的单"
        }
        return if (left != null && left > 0) {
            "$who，剩余约 ${ModeOrderPolicy.formatDuration(left)}"
        } else {
            who
        }
    }

    // ════════════════════════════════════════════
    //  MQTT 入口
    // ════════════════════════════════════════════

    /**
     * 把 MQTT 收到的原始消息转进来（外部在 `MqttManager` 的回调里调用）。
     *
     * 只认带 `orderNo` 的下单推送。⚠️ 热水器的 `rateOrder` 单号**也发在同一个 topic**
     * `app_downRate_{phone}` 上，所以必须过滤，否则会把水单的单号记到洗衣机头上。
     *
     * 过滤规则（按可靠性排序）：
     * 1. 推送里有 `deviceSnCode` → 必须等于本机 pending 的 snCode（服务端给的设备标识最可信）
     * 2. 没有 `deviceSnCode` → 退到 `consumeSceneType == 4`（唯一一个吹风机样本的取值）
     * 3. 都判不了 → **丢弃**，让 byMac 兜底。记错单号比暂时没有单号更糟。
     *
     * 不抛异常：这是从回调里调用的，抛了只会被外面吞掉。
     */
    fun onMqttMessage(json: String) {
        val push = parsePush(json) ?: return
        val orderNo = push.orderNo?.takeIf { it.isNotBlank() } ?: return

        val pushSn = push.deviceSnCode?.takeIf { it.isNotBlank() }
        val pending = pushSn?.let { pendingOrder(it) }
        val ok = when {
            pushSn != null -> pending != null
            push.consumeSceneType != null -> push.consumeSceneType == MODE_CONSUME_SCENE_TYPE
            else -> false
        }
        if (!ok) {
            AppLogger.d("忽略非本设备的 MQTT 推送：sn=$pushSn scene=${push.consumeSceneType}")
            return
        }
        AppLogger.i("收到 MQTT 套餐下单推送：sn=$pushSn orderNo=$orderNo")
        lastPush = push
        // 顺手落盘，别等 awaitOrderNo 来取
        pending?.let { savePending(it.copy(orderNo = orderNo)) }
    }

    // ════════════════════════════════════════════
    //  本地状态（注入的读写接口）
    // ════════════════════════════════════════════

    /** 读本机记着的进行中订单；没有或格式不对返回 null */
    fun pendingOrder(snCode: String): PendingModeOrder? {
        val raw = readPref(PREF_KEY_PREFIX + snCode)?.takeIf { it.isNotBlank() } ?: return null
        val pending = decode(snCode, raw)
        if (pending == null) AppLogger.w("本地套餐单记录解析失败，已忽略：$snCode")
        return pending
    }

    /** 清掉本地记录（时间到点、或用户已看到结束之后由上层调用） */
    fun clearPending(snCode: String) {
        lastPush = null
        writePref(PREF_KEY_PREFIX + snCode, "")
    }

    private fun savePending(order: PendingModeOrder) {
        writePref(PREF_KEY_PREFIX + order.snCode, encode(order))
    }

    // ════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════

    /**
     * byMac 查到的那个 orderNo，**只有当它是刚下的这一单时**才返回。
     *
     * 接口返回的是该设备**最近一单**，一小时内都查得到——不比对就会把
     * 半小时前那单的单号贴到这次消费上。
     */
    private fun orderNoOf(info: ModeOrderInfo, pending: PendingModeOrder?): String? {
        val orderNo = info.orderNo?.takeIf { it.isNotBlank() } ?: return null
        if (pending == null) {
            // 盘上没记录（比如 placeOrder 之外的路径调用），无从比对，只能不要
            AppLogger.w("byMac 查到 orderNo=$orderNo，但本地无 pending 记录，无法确认归属")
            return null
        }
        // 套餐档位已知就必须对上（同一台设备换档是常态）
        val modeId = pending.modeId
        if (modeId != null && info.xfModel != null && info.xfModel != modeId) {
            AppLogger.d("byMac 结果档位不符（${info.xfModel} != $modeId），不是本单")
            return null
        }
        val consumedAtMs = info.consumeDate?.let { BillDateParser.toEpochMillis(it) } ?: 0L
        if (consumedAtMs <= 0L) {
            AppLogger.w("byMac 结果缺 consumeDate，无法确认是否本单，忽略 orderNo=$orderNo")
            return null
        }
        if (consumedAtMs < pending.placedAtMs - FRESH_SLACK_MS) {
            AppLogger.d("byMac 结果偏旧（${info.consumeDate}），不是本单：orderNo=$orderNo")
            return null
        }
        return orderNo
    }

    private fun parsePush(json: String): MqttModePush? = try {
        // 静态 parseString，别用 `JsonParser().parse()`（后者在 Gson 2.11 已弃用）
        val obj = JsonParser.parseString(json)
        if (!obj.isJsonObject) null else gson.fromJson(obj, MqttModePush::class.java)
    } catch (e: Exception) {
        AppLogger.d("MQTT 消息不是 JSON，忽略：${e.message}")
        null
    }

    /**
     * 落盘格式：`orderNo|placedAtMs|modeId|modeSeconds|modeMoney`。
     * 用 `|` 而不是 JSON，是因为这里只有 5 个标量、reverse 解析失败也只是丢掉本地记录，
     * 不值得为它引一层序列化。空字段留空串。
     */
    private fun encode(o: PendingModeOrder): String = listOf(
        o.orderNo,
        o.placedAtMs.toString(),
        o.modeId?.toString() ?: "",
        o.modeSeconds.toString(),
        o.modeMoneyMilli?.toString() ?: ""
    ).joinToString("|")

    private fun decode(snCode: String, raw: String): PendingModeOrder? = runCatching {
        val p = raw.split('|')
        PendingModeOrder(
            snCode = snCode,
            orderNo = p[0],
            placedAtMs = p[1].toLong(),
            modeId = p[2].toIntOrNull(),
            modeSeconds = p[3].toIntOrNull() ?: 0,
            modeMoneyMilli = p[4].toIntOrNull()
        )
    }.getOrNull()

    /**
     * MQTT 下单推送，只取判归属用得到的三个字段。字段名取自吹风机抓包原文
     * （报文里还有 `xfModelName` / `preDeductMoney` / `state` / `result`，用不上就不解析）。
     */
    private data class MqttModePush(
        val orderNo: String? = null,
        val deviceSnCode: String? = null,
        val consumeSceneType: Int? = null
    ) {
        fun matches(snCode: String): Boolean {
            if (!deviceSnCode.isNullOrBlank()) return deviceSnCode.equals(snCode, ignoreCase = true)
            // 没有设备标识：只认场景值是套餐单的那条
            return consumeSceneType == MODE_CONSUME_SCENE_TYPE
        }
    }
}
