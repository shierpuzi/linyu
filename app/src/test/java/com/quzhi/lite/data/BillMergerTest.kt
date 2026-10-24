package com.quzhi.lite.data

import com.quzhi.lite.model.BillDTO
import com.quzhi.lite.model.BillItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「消费合并」的纯逻辑测试。
 *
 * 这一层的核心风险是**算错钱**：合并后用户看到的「总消费」必须等于
 * 各板块之和。所以重点覆盖：金额合计、排序、不去重、板块前缀。
 */
class BillMergerTest {

    private fun bill(
        money: String,
        date: String = "2026-10-24 12:00:00",
        orderNo: String? = "1",
        desc: String = "洗烘设备:洗衣机-3号楼-1层"
    ) = BillItem(
        consumeBillDTO = BillDTO(
            orderId = "1",
            consumeDate = date,
            consumeMoney = money,
            description = desc,
            orderNo = orderNo
        )
    )

    private fun board(pid: Long, name: String, vararg bills: BillItem) =
        BillMerger.BoardBills(pid, name, bills.toList())

    // ── 合计 ──

    @Test
    fun `totalYuan 求和`() {
        val bills = listOf(bill("4.0"), bill("0.6"), bill("1.38"))
        assertEquals(5.98, BillMerger.totalYuan(bills), 0.0001)
    }

    /**
     * 空串必须**跳过而不是当 0 参与**——虽然结果一样，但要确认不会抛异常。
     * 服务端偶发返回 `consumeMoney: ""`（尤其刚下单还没结算的账单）。
     */
    @Test
    fun `totalYuan 跳过空串不崩`() {
        val bills = listOf(bill("4.0"), bill(""), bill("0.6"))
        assertEquals(4.6, BillMerger.totalYuan(bills), 0.0001)
    }

    @Test
    fun `totalYuan 全空返回 0`() {
        assertEquals(0.0, BillMerger.totalYuan(listOf(bill(""), bill("  "))), 0.0001)
    }

    @Test
    fun `totalYuan 空列表返回 0`() {
        assertEquals(0.0, BillMerger.totalYuan(emptyList()), 0.0001)
    }

    // ── 合并 ──

    @Test
    fun `merge 合并两个板块并按时间倒序`() {
        val bath = board(
            4000L, "某某大学洗浴",
            bill("1.00", "2026-10-24 08:00:00"),
            bill("0.50", "2026-10-22 20:00:00")
        )
        val wash = board(
            3356L, "某某大学洗衣饮水吹风",
            bill("4.00", "2026-10-24 12:00:00"),
            bill("0.60", "2026-10-23 19:00:00")
        )
        val merged = BillMerger.merge(listOf(bath, wash))
        assertEquals(4, merged.size)
        assertEquals(
            listOf("2026-10-24 12:00:00", "2026-10-24 08:00:00",
                   "2026-10-23 19:00:00", "2026-10-22 20:00:00"),
            merged.map { it.consumeBillDTO.consumeDate }
        )
    }

    /**
     * 同一天两个板块各花一笔 = **两笔真实消费**，绝不能去重。
     * 去重会让「今天实际花了多少」少算。
     */
    @Test
    fun `merge 同一天不同板块的消费都保留`() {
        val a = board(4000L, "洗浴", bill("1.00", "2026-10-24 08:00:00"))
        val b = board(3356L, "洗衣", bill("4.00", "2026-10-24 08:00:00"))
        val merged = BillMerger.merge(listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals(5.00, BillMerger.totalYuan(merged), 0.0001)
    }

    @Test
    fun `merge 单板块原样返回`() {
        val only = board(4000L, "洗浴", bill("1.00"), bill("2.00"))
        val merged = BillMerger.merge(listOf(only))
        assertEquals(2, merged.size)
        assertEquals(3.00, BillMerger.totalYuan(merged), 0.0001)
    }

    @Test
    fun `merge 空输入返回空`() {
        assertTrue(BillMerger.merge(emptyList()).isEmpty())
    }

    @Test
    fun `merge 有板块没账单时其余照常合并`() {
        val a = board(4000L, "洗浴", bill("1.00"))
        val empty = board(3356L, "洗衣")
        val merged = BillMerger.merge(listOf(a, empty))
        assertEquals(1, merged.size)
    }

    // ── 板块短名 ──

    @Test
    fun `shortProjectName 去掉学校前缀`() {
        assertEquals("洗浴", BillMerger.shortProjectName("某某大学洗浴", 4000L))
        assertEquals("洗衣饮水吹风",
            BillMerger.shortProjectName("某某大学洗衣饮水吹风", 3356L))
        assertEquals("洗浴", BillMerger.shortProjectName("某某大学洗浴", 1L))
    }

    /** 没有可识别前缀时原样返回，不做猜测式截断 */
    @Test
    fun `shortProjectName 认不出就原样返回`() {
        assertEquals("XX校区业务A", BillMerger.shortProjectName("XX校区业务A", 9L))
    }

    @Test
    fun `shortProjectName 空名用项目号兜底`() {
        assertEquals("项目 3356", BillMerger.shortProjectName("", 3356L))
        assertEquals("项目 3356", BillMerger.shortProjectName("   ", 3356L))
    }

    /** 「学院」后面没内容时不能返回空串 */
    @Test
    fun `shortProjectName 只有学校名时不返回空`() {
        assertEquals("某某大学", BillMerger.shortProjectName("某某大学", 1L))
    }
}
