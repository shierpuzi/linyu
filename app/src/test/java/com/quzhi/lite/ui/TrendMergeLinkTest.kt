package com.quzhi.lite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消费趋势 / 消费合并的**联动规则**。
 *
 * 这些规则是从真实 bug 反推出来的（2026-10-24 用户实测两个）：
 *
 * 1. 关掉「消费趋势」后，「消费合并」看着还是开的 → 退出再进来才变灰
 * 2. 重新打开「消费趋势」时「消费合并」自己亮了 → 用户没要求过，得再手动关一次
 *
 * 第一个的根因是 state 没提升（Composable 各自 remember，父级不重组就不重算）；
 * 第二个的根因是**存储值没联动**。规则全部抽进 [TrendMergeLink]，
 * UI 和本测试调的是同一份代码，不存在"测的和跑的不是一个东西"。
 */
class TrendMergeLinkTest {

    // ── bug 2：开趋势不该自动打开合并 ──

    @Test
    fun `重新打开趋势不会自动打开合并`() {
        // 用户之前手动关掉了合并，现在重新打开趋势
        val (trend, merge) = TrendMergeLink.afterTrendToggle(newTrend = true, currentMerge = false)
        assertTrue("趋势应该开着", trend)
        assertFalse("合并不该被自动打开（bug 2）", merge)
    }

    @Test
    fun `开趋势时合并原本开着就保持开着`() {
        val (trend, merge) = TrendMergeLink.afterTrendToggle(newTrend = true, currentMerge = true)
        assertTrue(trend)
        assertTrue("用户本来就要合并，不该被关掉", merge)
    }

    // ── bug 1：关趋势必须真的关掉合并 ──

    @Test
    fun `关趋势会把合并一起关掉`() {
        val (trend, merge) = TrendMergeLink.afterTrendToggle(newTrend = false, currentMerge = true)
        assertFalse(trend)
        assertFalse("关趋势后合并必须也是关的（bug 1）", merge)
    }

    @Test
    fun `关趋势时合并本来就是关的`() {
        val (trend, merge) = TrendMergeLink.afterTrendToggle(newTrend = false, currentMerge = false)
        assertFalse(trend)
        assertFalse(merge)
    }

    /** 完整走一遍「关趋势 → 重开」：合并必须一直关着，不能自己复活 */
    @Test
    fun `关趋势再重开合并仍然关着`() {
        var trend = true
        var merge = true

        // 关趋势
        val step1 = TrendMergeLink.afterTrendToggle(newTrend = false, currentMerge = merge)
        trend = step1.first
        merge = step1.second
        assertFalse("关趋势后 trend 应关", trend)
        assertFalse("关趋势后 merge 应跟着关", merge)

        // 重开趋势
        val step2 = TrendMergeLink.afterTrendToggle(newTrend = true, currentMerge = merge)
        trend = step2.first
        merge = step2.second
        assertTrue("趋势应重新开着", trend)
        assertFalse("重开趋势后合并不该自己复活着（bug 2）", merge)
    }

    // ── 生效值 = 两个都开 ──

    @Test
    fun `只有两个都开才真合并`() {
        assertTrue(TrendMergeLink.effective(trend = true, merge = true))
        assertFalse(TrendMergeLink.effective(trend = true, merge = false))
        assertFalse(TrendMergeLink.effective(trend = false, merge = true))
        assertFalse(TrendMergeLink.effective(trend = false, merge = false))
    }

    /**
     * 存储值出现「趋势关但合并开」的脏数据时（旧版本升级、外部改 Prefs），
     * 生效值必须是 false——不能偷偷拉别的板块账单。
     */
    @Test
    fun `脏数据不会导致偷偷合并`() {
        assertFalse(TrendMergeLink.effective(trend = false, merge = true))
    }

    // ── 合并开关可点性 ──

    @Test
    fun `趋势关着时合并开关不可点`() {
        assertFalse(TrendMergeLink.switchEnabled(trend = false))
        assertTrue(TrendMergeLink.switchEnabled(trend = true))
    }

    /** 显示给用户看的开关位置：趋势关着时一律显示为关，否则按用户意愿 */
    @Test
    fun `趋势关着时合并显示为关`() {
        assertFalse("关趋势后合并不能还亮着（bug 1 的观感）",
            TrendMergeLink.displayChecked(trend = false, merge = true))
        assertTrue(TrendMergeLink.displayChecked(trend = true, merge = true))
        assertFalse(TrendMergeLink.displayChecked(trend = true, merge = false))
    }

    /** displayChecked 和 effective 必须一致，否则界面显示的和实际跑的会分叉 */
    @Test
    fun `显示态与生效态一致`() {
        for (t in listOf(true, false)) {
            for (m in listOf(true, false)) {
                assertEquals(
                    "trend=$t merge=$m 时显示态和生效态应一致",
                    TrendMergeLink.effective(t, m),
                    TrendMergeLink.displayChecked(t, m)
                )
            }
        }
    }
}
