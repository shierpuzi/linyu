package com.quzhi.lite.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知文案与刷新节奏。
 *
 * ## 双模式设计（2026-10-24 第四轮定案）
 *
 * | 剩余时间 | 胶囊由谁渲染 | 重发间隔 |
 * |---|---|---|
 * | ≥ 2 分钟 | **系统**（按 `when` 算「13分钟」这种） | 30 秒 |
 * | < 2 分钟 | **App**（写 `shortCriticalText` = `01:30`） | 1 秒 |
 *
 * ### 为什么以 2 分钟为界
 *
 * 官方文档《创建实时更新通知》：
 * > The when time is at least **2 minutes** in the future: 胶囊显示 `5min`。
 * > **The when time is in the past: The text isn't shown.**
 *
 * 即剩余不足 2 分钟时系统**不再渲染时间**——用户只看到胶囊里一个标题。
 * 而最后 2 分钟恰恰最需要看时间，所以切给 App 接管。
 */
class NotifyTextPolicyTest {

    // ════════════════════════════════════════════
    //  双模式切换的边界
    // ════════════════════════════════════════════

    @Test
    fun `剩余 2 分钟及以上走系统模式`() {
        assertFalse(NotifyTextPolicy.usePreciseChip(120))   // 正好 2 分钟 = 系统
        assertFalse(NotifyTextPolicy.usePreciseChip(180))
        assertFalse(NotifyTextPolicy.usePreciseChip(3600))
    }

    @Test
    fun `剩余不足 2 分钟走精确模式`() {
        assertTrue(NotifyTextPolicy.usePreciseChip(119))
        assertTrue(NotifyTextPolicy.usePreciseChip(90))
        assertTrue(NotifyTextPolicy.usePreciseChip(1))
        assertTrue(NotifyTextPolicy.usePreciseChip(0))
    }

    @Test
    fun `剩余为负不走精确模式`() {
        assertFalse(NotifyTextPolicy.usePreciseChip(-1))
    }

    @Test
    fun `重发间隔随模式切换`() {
        // 远离结束：30 秒
        assertEquals(30_000L, NotifyTextPolicy.refreshIntervalMs(180))
        assertEquals(30_000L, NotifyTextPolicy.refreshIntervalMs(120))
        // 最后 2 分钟：1 秒
        assertEquals(1_000L, NotifyTextPolicy.refreshIntervalMs(119))
        assertEquals(1_000L, NotifyTextPolicy.refreshIntervalMs(10))
    }

    // ════════════════════════════════════════════
    //  胶囊文本：**任何剩余时间都有**（ColorOS 不支持 when 回退）
    // ════════════════════════════════════════════

    /** 最后 2 分钟：mm:ss，秒级 */
    @Test
    fun `精确模式给 mmss 且不超过 7 字符`() {
        assertEquals("01:30", NotifyTextPolicy.chipText(90))
        assertEquals("00:45", NotifyTextPolicy.chipText(45))
        assertEquals("01:59", NotifyTextPolicy.chipText(119))
        assertTrue(NotifyTextPolicy.chipText(119).length <= 7)
    }

    /**
     * **前段也必须给文本**——这是 2026-10-24 实测推翻我原先判断的地方。
     *
     * 原以为剩余 ≥2 分钟时系统会按 `when` 自动渲染，但 ColorOS 16 不实现那条回退，
     * 不写 `shortCriticalText` 时胶囊只有标题（用户原话「大于2分钟还是这个样子」）。
     */
    @Test
    fun `系统模式也给分钟级胶囊文本`() {
        // 整分钟不多进一位：120 秒正好 2 分钟 → "2分"
        assertEquals("2分", NotifyTextPolicy.chipText(120))
        // 非整分钟向上取整：150 秒 = 2.5 分钟 → "3分"
        assertEquals("3分", NotifyTextPolicy.chipText(150))
        assertEquals("5分", NotifyTextPolicy.chipText(300))
        assertEquals("1时", NotifyTextPolicy.chipText(3600))
        assertEquals("1时2分", NotifyTextPolicy.chipText(3720))
    }

    @Test
    fun `胶囊文本永远不为空且不超过 7 字符`() {
        for (sec in listOf(-5, 0, 1, 45, 60, 119, 120, 150, 300, 3599, 3600, 3720, 35999)) {
            val t = NotifyTextPolicy.chipText(sec)
            assertTrue("sec=$sec 不能为空", t.isNotEmpty())
            assertTrue("sec=$sec 时「$t」超过 7 字符", t.length <= 7)
        }
    }

    // ════════════════════════════════════════════
    //  正文文案：前段分钟级，最后 2 分钟秒级
    // ════════════════════════════════════════════

    @Test
    fun `前段正文按分钟且向上取整`() {
        assertEquals("剩余 3 分钟", NotifyTextPolicy.remainingLine(150))
        assertEquals("剩余 1 小时 2 分钟", NotifyTextPolicy.remainingLine(3720))
    }

    @Test
    fun `前段正文不含秒`() {
        for (sec in listOf(120, 150, 180, 600, 3600, 7200)) {
            val line = NotifyTextPolicy.remainingLine(sec)
            assertFalse("sec=$sec 不该出现「秒」：$line", line.contains("秒"))
        }
    }

    @Test
    fun `最后 2 分钟正文精确到秒`() {
        assertEquals("剩余 1 分 30 秒", NotifyTextPolicy.remainingLine(90))
        assertEquals("剩余 45 秒", NotifyTextPolicy.remainingLine(45))
    }

    @Test
    fun `剩余为 0 时不说剩余 0`() {
        assertEquals("即将结束", NotifyTextPolicy.remainingLine(0))
        assertEquals("即将结束", NotifyTextPolicy.remainingLine(-5))
    }

    // ════════════════════════════════════════════
    //  正向计时（「正在使用」）：始终分钟级
    // ════════════════════════════════════════════

    @Test
    fun `已用时间分钟级`() {
        assertEquals("已用 45 分钟", NotifyTextPolicy.elapsedLine(2712))
        assertEquals("已用 1 小时 2 分钟", NotifyTextPolicy.elapsedLine(3720))
        assertEquals("已用 不到 1 分钟", NotifyTextPolicy.elapsedLine(30))
    }

    @Test
    fun `minuteGranularity 映射`() {
        assertEquals("0 分钟", NotifyTextPolicy.minuteGranularity(0))
        assertEquals("不到 1 分钟", NotifyTextPolicy.minuteGranularity(59))
        assertEquals("1 分钟", NotifyTextPolicy.minuteGranularity(60))
        assertEquals("1 小时", NotifyTextPolicy.minuteGranularity(3600))
        assertEquals("1 小时 30 分钟", NotifyTextPolicy.minuteGranularity(5400))
    }

    // ════════════════════════════════════════════
    //  节流
    // ════════════════════════════════════════════

    @Test
    fun `从没发过时应该发`() {
        assertTrue(NotifyTextPolicy.shouldRefresh(0L, 1_000_000L))
    }

    @Test
    fun `系统模式下不到 30 秒不重发`() {
        val last = 1_000_000L
        assertFalse(NotifyTextPolicy.shouldRefresh(last, last + 1, remainingSec = 300))
        assertFalse(NotifyTextPolicy.shouldRefresh(last, last + 29_999L, remainingSec = 300))
        assertTrue(NotifyTextPolicy.shouldRefresh(last, last + 30_000L, remainingSec = 300))
    }

    /** 最后 2 分钟必须每秒重发，否则秒级胶囊会停在旧值 */
    @Test
    fun `精确模式下满 1 秒就重发`() {
        val last = 1_000_000L
        assertFalse(NotifyTextPolicy.shouldRefresh(last, last + 999L, remainingSec = 90))
        assertTrue(NotifyTextPolicy.shouldRefresh(last, last + 1_000L, remainingSec = 90))
    }

    @Test
    fun `时钟回拨也要发而不是卡死`() {
        assertTrue(NotifyTextPolicy.shouldRefresh(2_000_000L, 1_000_000L, remainingSec = 300))
    }

    @Test
    fun `基准间隔常量未被改动`() {
        assertEquals(30_000L, NotifyTextPolicy.REFRESH_INTERVAL_MS)
        assertEquals(1_000L, NotifyTextPolicy.REFRESH_INTERVAL_PRECISE_MS)
        assertEquals(120, NotifyTextPolicy.PRECISE_MODE_THRESHOLD_SEC)
    }
}
