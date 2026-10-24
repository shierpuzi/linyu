package com.quzhi.lite.ui

/**
 * 「消费趋势」与「消费合并」的联动规则——**纯函数，UI 和测试共用同一份**。
 *
 * ## 为什么单独抽出来
 *
 * 这两个开关有依赖关系（合并依赖趋势），而依赖状态如果散落在两个 Composable
 * 各自的 `remember` 里，会产生两种真实发生过的 bug（2026-10-24 用户实测）：
 *
 * 1. **状态不同步**：关掉趋势后，合并卡片因为父级没重组而不重新求值，
 *    看起来还是开的；退出设置页再进来才变灰
 * 2. **看起来被自动打开**：关趋势时如果只改 `trendEnabled`、不动 `mergeConsume`，
 *    用户下次开趋势就会看到合并也亮了，像是被自动勾上了
 *
 * 所以规则集中在这里，`TrendAndMergeCards` 只负责渲染 + 调这些函数，
 * 测试直接测同一批函数。**改规则只改这一处。**
 */
object TrendMergeLink {

    /**
     * 合并**是否真的生效** = 两个都开。
     *
     * 用「与」而不是只读存储值：万一存储里出现「趋势关、合并开」的脏数据
     * （旧版本升级、外部改了 Prefs），这里兜住，不会偷偷去拉别的板块账单。
     */
    fun effective(trend: Boolean, merge: Boolean): Boolean = trend && merge

    /**
     * 合并开关**是否可点**：趋势关着就禁用。
     *
     * 合并结果只喂给趋势图，趋势没了它就没有展示出口。
     */
    fun switchEnabled(trend: Boolean): Boolean = trend

    /**
     * 合并开关**显示的位置**。
     *
     * 趋势关着时一律显示为关——哪怕用户的意愿（[merge]）还是 true。
     * 否则用户会看到「趋势关了但合并还亮着」，正是 bug 1 的观感。
     */
    fun displayChecked(trend: Boolean, merge: Boolean): Boolean = trend && merge

    /**
     * 用户翻转「消费趋势」之后，两个开关应该变成什么。
     *
     * 规则：
     * - **关趋势 → 合并一起关**（bug 1 的修复：语义变成"趋势没了，合并跟着没了"）
     * - **开趋势 → 合并不自动打开**（bug 2 的修复：用户没要求就不打开）
     *
     * @return (新的 trend, 新的 merge)
     */
    fun afterTrendToggle(newTrend: Boolean, currentMerge: Boolean): Pair<Boolean, Boolean> =
        if (newTrend) newTrend to currentMerge else false to false
}
