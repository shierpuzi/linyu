package com.quzhi.lite.utils

/**
 * 通知上的**纯文本/节奏规则**：不碰 Android、不碰时钟源，只做计算，便于纯 JVM 单测。
 *
 * ## 为什么要有这个文件（ColorOS 倒计时芯片不显示）
 *
 * 2026-10-24 真机实测（RMX3888 / Android 16 / ColorOS）：
 * `setUsesChronometer(true)` + `setChronometerCountDown(true)` + `setWhen(endAt)` 三件套
 * 在 dumpsys 里元数据全对，但通知栏右侧**渲染成了发送时刻**，不是倒计时芯片。
 * 也就是说「交给系统自己走秒」这条路在部分 OEM SystemUI 上不保证有 UI。
 *
 * 所以时间**必须有文字兜底**：通知正文里显式写「剩余 2分30秒」。
 * 文字不会自己变，得靠 [REFRESH_INTERVAL_MS] 节奏重发同一条通知来刷新——
 * OPPO 流体云适配文档明确禁止「每秒 notify」（限流 + 耗电），
 * 所以这里固定 30 秒，配合调用方的 `setOnlyAlertOnce(true)`。
 */
object NotifyTextPolicy {
    /**
     * 剩余时间跌破这个值时，**改由 App 提供秒级文本**（写 `shortCriticalText`）。
     *
     * ## 为什么正好是 2 分钟
     *
     * 官方文档《创建实时更新通知》对 `when` 驱动的胶囊有明确门槛：
     *
     * > The when time is at least **2 minutes** in the future:
     * > 当前 10:05、when 设成 10:10，胶囊显示 `5min`。
     * > **The when time is in the past: The text isn't shown.**
     *
     * 即剩余 ≥2 分钟时系统自己渲染分钟级倒计时（`13min`，同竞品）；
     * 低于 2 分钟它**不再显示时间**——用户看到的就是「胶囊只剩标题」。
     *
     * 而最后 1 分多钟恰恰最需要看时间（马上要结束了），所以切一手：
     * **前段交给系统（顺滑、零成本），最后 2 分钟由 App 供秒级文本**。
     */
    const val PRECISE_MODE_THRESHOLD_SEC = 120

    /**
     * 重发同一条通知的**基准**间隔：30 秒。
     *
     * ⚠️ 实际间隔由 [refreshIntervalMs] 动态决定：
     * 远离结束时 30 秒（只为刷新正文的「剩余 N 分钟」）；
     * 进入最后 2 分钟后变成 1 秒（秒级文本必须每秒刷新才准）。
     *
     * ## 历史（走过两次弯路，别再来回改）
     *
     * | 版本 | 做法 | 实测结果 |
     * |---|---|---|
     * | v1 | 30s 重发 + App 写秒级 chipText | 胶囊 `3:00 → 2:29` 每 30 秒跳一次 |
     * | v2 | 1s 重发 + App 写秒级 chipText | 逐秒跳，但**不顺滑**、耗电 |
     * | v3 | 不写 chipText，全交系统 | 分钟级顺滑，但**最后 2 分钟没时间**（文档门槛） |
     * | v4（现在） | **前段交系统 + 最后 2 分钟 App 供秒级** | 兼顾 |
     *
     * ## 关键：前段胶囊不该由我们驱动
     *
     * 竞品（外卖「送货中 · 13分钟」）的胶囊都是**系统渲染**的分钟级倒计时。
     * 做法就是**不写 `shortCriticalText`**，只给 `setWhen(endAtMs)` + `setShowWhen(true)`。
     *
     * AOSP 文档原文：
     * > If setShowWhen(boolean) is true, the chip content will be the time remaining
     * > until Notification.when, if positive.
     *
     * ⚠️ **之前误判过一次**：把 `shortCriticalText` 去掉后看到 `PROMOTED_ONGOING` 消失，
     * 就断定「ColorOS 必须有显式文本」。其实那次测试时**用户还没开流体云权限**，
     * 两条依赖搅在一起了。权限开后去掉 `shortCriticalText` 是正常的。
     */
    const val REFRESH_INTERVAL_MS = 30_000L

    /** 最后 2 分钟内的重发间隔：秒级文本必须每秒刷才准 */
    const val REFRESH_INTERVAL_PRECISE_MS = 1_000L

    /**
     * 这一秒该不该走「App 供秒级文本」模式。
     *
     * @param remainingSec 剩余秒数
     * @return true = 最后 2 分钟内，需每 [REFRESH_INTERVAL_PRECISE_MS] 重发并写 `shortCriticalText`
     */
    fun usePreciseChip(remainingSec: Int): Boolean =
        remainingSec in 0 until PRECISE_MODE_THRESHOLD_SEC

    /**
     * 按剩余时间给出该用的重发间隔。
     *
     * 远离结束时慢刷（省电、正文按分钟足够）；
     * 进入最后 2 分钟后快刷（秒级文本要准）。
     */
    fun refreshIntervalMs(remainingSec: Int): Long =
        if (usePreciseChip(remainingSec)) REFRESH_INTERVAL_PRECISE_MS else REFRESH_INTERVAL_MS

    /**
     * 写进 `shortCriticalText` 的胶囊文本：**任何剩余时间都有**。
     *
     * ## ⚠️ 为什么必须永远返回非 null（2026-10-24 实测推翻了我之前的判断）
     *
     * 我原以为「剩余 ≥2 分钟时系统会按 `when` 自动渲染 `13分钟`」，依据是官方文档：
     * > The when time is at least 2 minutes in the future: 胶囊显示 `5min`。
     *
     * **但 ColorOS 16 实测：不写 `shortCriticalText` 时，胶囊只有通知标题，没有时间。**
     * 用户原话：「大于2分钟还是这个样子，没有剩余 3 分钟；小于两分钟还正常」。
     *
     * 也就是说这台 ROM **不实现** AOSP 文档里那条 `when` 回退渲染。
     * 结论：**胶囊文本只能由 App 提供，没有别的路**。
     *
     * ## 粒度按剩余时间分两档
     *
     * | 剩余 | 文本 | 例 |
     * |---|---|---|
     * | ≥ 2 分钟 | **分钟** | `3分` / `13分` / `1时2分` |
     * | < 2 分钟 | **mm:ss** | `01:30` / `00:45` |
     *
     * 前段用「分」而不是 `mm:ss`，是因为大段时间逐秒变只会抖（用户原话「不太顺滑」），
     * 而且 `3分` 比 `03:00` 更接近竞品那种「13分钟」的观感。
     *
     * 官方限制：胶囊最大 96dp，**≤7 字符才完整显示**。这里最长的 `1时59分` 正好 5 字符。
     */
    fun chipText(remainingSec: Int): String {
        if (remainingSec <= 0) return "0分"
        if (usePreciseChip(remainingSec)) {
            val m = remainingSec / 60
            val s = remainingSec % 60
            return "%02d:%02d".format(m, s)
        }
        // 前段：分钟级，向上取整（剩 2 分 50 秒算 3 分）
        val totalMin = (remainingSec + 59) / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}时${m}分"
            h > 0 -> "${h}时"
            else -> "${totalMin}分"
        }
    }


    /**
     * 正文里的剩余时间：**按分钟，不精确到秒**。
     *
     * 竞品的胶囊就是「13 分钟」这种粒度（见用户截图：外卖「送货中 13分钟」）。
     * 精确到秒有两个坏处：
     * 1. 每秒变一次，视觉上**抖**（用户原话「不太顺滑」）
     * 2. 想让它动就得每秒 notify，耗电且容易被 ROM 限流
     *
     * 不足 1 分钟时显示「不到 1 分钟」而不是「0 分钟」——
     * 后者会让人以为已经结束了。
     */
    fun remainingLine(remainingSec: Int): String {
        if (remainingSec <= 0) return "即将结束"
        // 最后 2 分钟：秒级（马上结束，用户需要确切剩余）
        if (usePreciseChip(remainingSec)) return "剩余 " + formatDuration(remainingSec)
        if (remainingSec < 60) return "剩余不到 1 分钟"
        val totalMin = remainingSec / 60
        // 进位：剩 2 分 50 秒算 3 分钟。用户关心的是「还要等几档」，
        // 向下取整会让人觉得时间没动（2分10秒和2分50秒都显示 2 分钟）。
        val showMin = if (remainingSec % 60 > 0) totalMin + 1 else totalMin
        val h = showMin / 60
        val m = showMin % 60
        return when {
            h > 0 && m > 0 -> "剩余 $h 小时 $m 分钟"
            h > 0 -> "剩余 $h 小时"
            else -> "剩余 $showMin 分钟"
        }
    }

    // ⚠️ 这里曾有 `chipText()` / `elapsedChipText()` / `CHIP_MIN_SEC`，已删除（2026-10-24 #16）。
    //
    // 它们的职责是「算出胶囊里那个 `02:58` 字符串」。删掉的原因：
    // 胶囊**不该由 App 提供文本**，交给系统的 `setWhen` + `setShowWhen` 去算，
    // 系统渲染出来就是竞品那种「13 分钟」的分钟级倒计时，而且自己会走、不用重发。
    // 留着它们只会诱使人再写进 `shortCriticalText`，把系统倒计时盖成静态串。

    /**
     * 现在要不要重发那条通知。
     *
     * [lastRefreshMs] 传 0（从没发过）时返回 true——第一次本该无条件发。
     * 时钟回拨（now < last）也返回 true：宁可多发一次，也不要卡着不动。
     *
     * @param remainingSec 当前剩余秒数——决定用慢刷还是快刷（见 [refreshIntervalMs]）。
     *   最后 2 分钟必须走 1 秒间隔，否则秒级胶囊会停在旧值。
     */
    fun shouldRefresh(lastRefreshMs: Long, nowMs: Long, remainingSec: Int = Int.MAX_VALUE): Boolean {
        if (lastRefreshMs <= 0L) return true
        if (nowMs < lastRefreshMs) return true
        return nowMs - lastRefreshMs >= refreshIntervalMs(remainingSec)
    }

    /**
     * 正向计时（「正在使用」那条）的**分钟级**文案，给通知正文用。
     *
     * 和 [remainingLine] 同一套粒度：**只到分钟**，不显示秒。
     * 理由：秒级文案要么每秒重发（抖 + 耗电），要么就一直停在旧值（骗人）。
     */
    fun elapsedLine(elapsedSec: Int): String = "已用 " + minuteGranularity(elapsedSec)

    /**
     * 秒 → 分钟级时长文案：`8 分钟` / `1 小时 5 分钟` / `不到 1 分钟`。
     *
     * 用户明确要求「按分钟计算，不精确到秒」——竞品胶囊就是 `13 分钟` 这种粒度。
     */
    fun minuteGranularity(sec: Int): String {
        if (sec <= 0) return "0 分钟"
        if (sec < 60) return "不到 1 分钟"
        val totalMin = sec / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分钟"
            h > 0 -> "$h 小时"
            else -> "$totalMin 分钟"
        }
    }

    /** 时长文案：1 小时 02 分 / 12 分 34 秒 / 45 秒（**App 内 UI** 用，精确到秒） */
    fun formatDuration(sec: Int): String {
        if (sec <= 0) return "0 秒"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "%d 小时 %02d 分".format(h, m)
            m > 0 -> "%d 分 %d 秒".format(m, s)
            else -> "%d 秒".format(s)
        }
    }
}
