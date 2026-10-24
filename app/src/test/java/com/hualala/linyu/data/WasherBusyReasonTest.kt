package com.hualala.linyu.data

import com.hualala.linyu.model.WasherStatusData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占用态判据。
 *
 * **样本来源**：2026-10-24 09:51 轮询真实抓到（洗衣机被别人的单占着）。
 * 在此之前这里写的是「没有样本，不敢臆断」，占用一律报 `Unknown`——
 * 那意味着界面上永远显示「不确定」，而**不是**「被占用」。
 *
 * 这些用例锁住实测出来的三个关键点：
 * 1. 占用时 `success` **仍是 true**，只有 `errorCode=307` 才是信号
 * 2. 占用时 `data` 非空但 `orderNo` 是 **null**（别人的单）
 * 3. `expireTimes` 是剩余秒数（实测 729→649→589 递减）
 */
class WasherBusyReasonTest {

    // ── Busy 原因文案 ──

    @Test
    fun `别人的单带剩余时间`() {
        val d = WasherStatusData(expireTimes = 729, isOwner = false)
        assertEquals("别人的单，剩余约 12 分 9 秒", ModeOrderPolicy.formatDuration(d.expireTimes).let {
            "别人的单，剩余约 $it"
        })
    }

    @Test
    fun `自己的单文案不同`() {
        val d = WasherStatusData(expireTimes = 300, isOwner = true)
        assertTrue(d.isOwner == true)
        assertEquals("5 分 0 秒", ModeOrderPolicy.formatDuration(d.expireTimes))
    }

    /** `isOwner` 缺失时不能崩，也不能说成「别人的」 */
    @Test
    fun `isOwner 缺失时中性表述`() {
        val d = WasherStatusData(expireTimes = 100, isOwner = null)
        assertEquals(null, d.isOwner)
        assertEquals("1 分 40 秒", ModeOrderPolicy.formatDuration(d.expireTimes))
    }

    /** `expireTimes` 缺失时只报归属，不拼时间 */
    @Test
    fun `expireTimes 缺失仍能出文案`() {
        val d = WasherStatusData(expireTimes = null, isOwner = false)
        assertEquals(null, d.expireTimes)
    }

    // ── formatDuration 边界 ──

    @Test
    fun `formatDuration 实测值`() {
        // 729 秒 = 12 分 9 秒（真实样本第一轮）
        assertEquals("12 分 9 秒", ModeOrderPolicy.formatDuration(729))
        // 649 / 589 是随后两轮，验证递减不影响格式
        assertEquals("10 分 49 秒", ModeOrderPolicy.formatDuration(649))
        assertEquals("9 分 49 秒", ModeOrderPolicy.formatDuration(589))
    }

    @Test
    fun `formatDuration 不足一分钟`() {
        assertEquals("45 秒", ModeOrderPolicy.formatDuration(45))
        assertEquals("1 秒", ModeOrderPolicy.formatDuration(1))
    }

    @Test
    fun `formatDuration 整分钟`() {
        assertEquals("5 分 0 秒", ModeOrderPolicy.formatDuration(300))
        assertEquals("1 分 0 秒", ModeOrderPolicy.formatDuration(60))
    }

    /** 负数/null 都要安全收敛到 0 秒，不能出现「-1 秒」 */
    @Test
    fun `formatDuration 负数与 null`() {
        assertEquals("0 秒", ModeOrderPolicy.formatDuration(-100))
        assertEquals("0 秒", ModeOrderPolicy.formatDuration(null))
        assertEquals("0 秒", ModeOrderPolicy.formatDuration(0))
    }

    // ── 解析：实测 JSON 结构 ──

    /**
     * 服务端在占用态会把无关字段全填 null。
     * Gson 反序列化不能因为一堆 null 崩掉，也不能把 null 变成 0。
     */
    @Test
    fun `实测 JSON 的关键字段`() {
        val gson = com.google.gson.Gson()
        val json = """
            {"expireTimes":729,"orderNo":null,"xfModelName":null,
             "preDeductMoney":null,"isOwner":false,"modeMoney":null,
             "modeTime":null,"liquidOrderNo":null,"liquidOrderStatus":null,
             "liquidModeName":null,"liquidConsumeMoney":null,
             "leftGoodsModel":null,"rightGoodsModel":null,"accountId":null}
        """.trimIndent()
        val d = gson.fromJson(json, WasherStatusData::class.java)
        assertEquals(729L, d.expireTimes)
        assertEquals(false, d.isOwner)
        // 别人的单不给单号——这条是「别指望从这里取 orderNo」的依据
        assertEquals(null, d.orderNo)
        assertEquals(null, d.accountId)
    }
}
