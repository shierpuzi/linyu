package com.hualala.linyu.data

import com.hualala.linyu.model.WashMode
import com.hualala.linyu.utils.MoneyFormat
import java.util.Locale

/**
 * 按套餐计费的设备类别。
 *
 * 判据**只有数字**（`bigTypeId` / `smallTypeId`）：服务端 `bigTypeName` 在
 * `/device/info/mac` 里叫「洗衣机」，在 `/device/smallType/id` 里同一个 `bigTypeId=6`
 * 却叫「洗烘设备」，账单里的 `consumeType` 又是「洗烘设备」——**名字三处不一致，不能用来分支**。
 *
 * | 设备 | bigTypeId | smallTypeId |
 * |---|---|---|
 * | 洗衣机 | 6 | 3 |
 * | 吹风机 | 7 | 5 |
 *
 * 其余（热水表 4/1、饮水机 5 …）一律 [OTHER]，本包的下单通道不管它们。
 */
enum class ModeDeviceKind {
    WASHER,
    DRYER,
    OTHER
}

/**
 * 计费模型——**UI 分流的第一性依据**（判据见 [ModeOrderPolicy.billingModel]）。
 */
enum class BillingModel {
    /** 按量计费：热水表 / 洗手台 / 饮水机。预扣后按量结算，可以中途关。走 `ShowerScreen` */
    METERED,

    /** 套餐计费：洗衣机 / 吹风机。扣全款不可退、无停止接口、服务端到点关停。走 `ModeOrderScreen` */
    PACKAGE
}

/**
 * 洗衣 / 吹风「按套餐下单」的纯逻辑部分：**不碰网络、不碰 Android、不碰时间源**。
 *
 * 放在这里的唯一目的是能被纯 JVM 单测覆盖——这条链路是**不可退款的扣款**，
 * 倒计时、价格、余额判断出错都不是显示问题，而是钱的问题。
 * 网络与状态机见 [ModeOrderController]。
 */
object ModeOrderPolicy {

    const val BIG_TYPE_WASHER = 6
    const val BIG_TYPE_DRYER = 7
    const val SMALL_TYPE_WASHER = 3
    const val SMALL_TYPE_DRYER = 5

    /**
     * 价格拿不到时的占位符。**不能显示成 `0.00`**——
     * 「免费」和「不知道多少钱」是两件事，前者会让人直接点下单。
     */
    const val PRICE_PLACEHOLDER = "—"

    /**
     * 倒计时到点后额外等的容差（秒）。
     *
     * 实测（吹风机 0.6 元/3 分钟）：下单 00:12:24 → 账单结束 00:15:25，**181 秒**。
     * 服务端自己是到点关停，不看我们；我们**早一秒钟**宣布「已结束」，
     * 用户看到的可能是还在转的机器。所以判「结束」必须留余量，方向只能是晚不能早。
     */
    const val FINISH_TOLERANCE_SEC = 5

    /**
     * 设备大类判定。
     *
     * @param smallTypeId 仅在 [bigTypeId] 缺失时作为**兜底**——它是弱判据
     *   （热水器那条链路上 `smallTypeId=1` 曾经是「热水表」这个通用名，
     *   被当成蓝牙特征后误判了整个学校），所以有 `bigTypeId` 时**一律以它为准**。
     */
    fun deviceKind(bigTypeId: Int?, smallTypeId: Int?): ModeDeviceKind = when {
        bigTypeId == BIG_TYPE_WASHER -> ModeDeviceKind.WASHER
        bigTypeId == BIG_TYPE_DRYER -> ModeDeviceKind.DRYER
        // big 缺失才看 small；两者都没有 → OTHER（宁可不管，也不要猜错去扣钱）
        bigTypeId == null && smallTypeId == SMALL_TYPE_WASHER -> ModeDeviceKind.WASHER
        bigTypeId == null && smallTypeId == SMALL_TYPE_DRYER -> ModeDeviceKind.DRYER
        else -> ModeDeviceKind.OTHER
    }

    /**
     * **计费模型**——决定用哪个界面，是本项目 UI 分流的第一性依据。
     *
     * ## 为什么按计费模型切，而不是按设备类型切
     *
     * 「有没有结束按钮」「显示已用还是剩余」「要不要守倒计时」这些差异，
     * 全部由**计费方式**决定，和设备是什么机器无关：
     *
     * | | 按量计费 | 套餐计费 |
     * |---|---|---|
     * | 扣款 | 预扣后按量结算 | **扣全款、不可退** |
     * | 中途停 | 可以（`closeOrder`） | **没有这个接口** |
     * | 时长 | 用到多久算多久 | 固定，服务端到点关停 |
     * | 界面 | `ShowerScreen`（显示已用） | `ModeOrderScreen`（显示剩余） |
     *
     * 按设备类型切的话，将来接入第六种套餐设备就得再写一个页面；
     * 按计费模型切，新设备只要 `deviceKind` 认得出 `bigTypeId`，界面自动就有。
     *
     * ## 实测依据（账单数据反推）
     *
     * | 设备 | preDeduct | consumeMoney | 判定 |
     * |---|---|---|---|
     * | 洗烘设备 | 4.0 | 4.0 | 相等 → 套餐 |
     * | 吹风机 | 0.6 | 0.6 | 相等 → 套餐 |
     * | **饮水机** | **2.0** | **0.1 / 0.44** | **不等 → 按量** |
     *
     * ⚠️ 饮水机虽然和洗衣/吹风在**同一个项目**（3356）里，但计费模型是「按量」，
     * 所以它走原来的 `ShowerScreen`。**别把它并进套餐通道**——
     * 那会做出一个「饮水机也显示到这个点自动结束」的界面，而它其实是预扣 2 元后按量结算。
     */
    fun billingModel(bigTypeId: Int?, smallTypeId: Int?): BillingModel =
        when (deviceKind(bigTypeId, smallTypeId)) {
            ModeDeviceKind.WASHER, ModeDeviceKind.DRYER -> BillingModel.PACKAGE
            ModeDeviceKind.OTHER -> BillingModel.METERED
        }

    /**
     * 套餐剩余秒数，下限 0。
     *
     * 时钟回拨（[nowMs] 早于 [startedAtMs]）时返回**整段时长**而不是负数或 0：
     * 手机时间被改、或从别的时区设备上恢复状态时就会这样。
     * 按负数算会让倒计时直接跳到「已结束」，按 0 算会让界面显示「正在结束」，
     * 都不如「从头开始算」诚实——反正真正的关停是服务端到点做的，我们只是显示。
     */
    fun remainingSeconds(startedAtMs: Long, modeSeconds: Int, nowMs: Long): Int {
        if (modeSeconds <= 0) return 0
        val elapsedMs = nowMs - startedAtMs
        if (elapsedMs <= 0L) return modeSeconds
        val left = modeSeconds - (elapsedMs / 1000L).toInt()
        return if (left > 0) left else 0
    }

    /**
     * 是否**大概率**已经结束（到点 + [toleranceSec] 容差）。
     *
     * 只是用来决定「什么时候去调一次 `lastOrder/byMac` 核对」，
     * **不是**「设备已经停了」的判据——那个服务端说了算，且我们没有任何停止/查询结束的接口。
     */
    fun isLikelyFinished(
        startedAtMs: Long,
        modeSeconds: Int,
        nowMs: Long,
        toleranceSec: Int = FINISH_TOLERANCE_SEC
    ): Boolean {
        if (modeSeconds <= 0) return true
        val elapsedMs = nowMs - startedAtMs
        if (elapsedMs <= 0L) return false // 时钟回拨：当作刚开始，别判结束
        val waitedSec = elapsedMs / 1000L
        return waitedSec >= modeSeconds.toLong() + maxOf(0, toleranceSec).toLong()
    }

    /**
     * 套餐价格显示，单位厘 → 两位小数字符串：`600` → `"0.60"`，`1000` → `"1.00"`。
     *
     * 换算**复用** [MoneyFormat.milliToYuan]，不自己写 `/1000.0`（那正是差 1000 倍的经典坑）。
     * 这里只负责显示成「元/角分」两位——本链路的套餐价实测都是整角
     * （400/600/1000/2000/3000/5000 厘），两位够用。
     *
     * ponytail: 真出现厘级套餐价（如 25 厘 = 0.025 元）会被四舍五入成 `"0.03"`。
     * 届时改成三位并按 [MoneyFormat.format] 收敛，别在这儿加分支。
     */
    fun formatPrice(modeMilli: Int?): String {
        val yuan = MoneyFormat.milliToYuan(modeMilli) ?: return PRICE_PLACEHOLDER
        return "%.2f".format(Locale.US, yuan)
    }

    /**
     * 秒数 → 人话，用于「剩余约 X」这类文案。
     *
     * `729` → `"12 分 9 秒"`，`45` → `"45 秒"`，`0`/负数 → `"0 秒"`。
     *
     * 数据来源：`washer/status` 占用态返回的 `expireTimes`（实测 729→649→589 递减）。
     * **不要**把它和 [formatPrice] 混用——一个是钱（厘），一个是时间（秒）。
     */
    fun formatDuration(seconds: Long?): String {
        val s = (seconds ?: 0L).coerceAtLeast(0L)
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "$m 分 $sec 秒" else "$sec 秒"
    }

    /**
     * 这次下单**是不是必须先停下来给用户看清楚**。
     *
     * 只有「余额已知且够付」才返回 `false`；余额查不到、价格缺失、余额不足都返回 `true`。
     *
     * ## 两个别搞错的点
     *
     * 1. **返回 `false` 也不等于可以直接发请求。** `modeOrder` 一发出就扣套餐全款、
     *    不可退、没有取消接口，所以调用方**任何情况下**都要先显示「X 元 / N 分钟」再让用户点确认。
     *    这个函数只回答「有没有*额外的*理由必须拦住」。
     * 2. **别把 `true` 一律显示成「余额不足」。** 余额拿不到时同样是 `true`，
     *    但那句文案会让用户跑去充值（其实该重试拉余额）。两种原因要分开说。
     */
    fun shouldConfirm(mode: WashMode, walletMilli: Long?): Boolean {
        val price = mode.modeMoney ?: return true
        val wallet = walletMilli ?: return true
        return wallet < price
    }

    /**
     * 默认选中哪一档：服务端标了 `isSelected == 1` 的优先，否则第一档。
     *
     * 没有 `isSelected` 标记时**不要**按价格/时长排序猜——列出的顺序就是官方 App 的顺序。
     */
    fun pickDefaultMode(modes: List<WashMode>): WashMode? =
        modes.firstOrNull { it.isSelected == 1 } ?: modes.firstOrNull()
}
