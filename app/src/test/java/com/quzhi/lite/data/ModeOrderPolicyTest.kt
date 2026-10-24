package com.quzhi.lite.data

import com.quzhi.lite.model.WashMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModeOrderPolicy] 的纯 JVM 单测（无 Robolectric、无网络、无 Android）。
 *
 * 为什么这条链路一定要有测试：`modeOrder` **一发出就扣套餐全款、不可退、
 * 没有取消接口**。倒计时/价格/余额判断错了不是「显示不好看」，
 * 是「用户的钱花错地方 + 没有任何挽回手段」。
 */
class ModeOrderPolicyTest {

    // ════════════════════════════════════════════
    //  deviceKind：只用数字，不认名字
    // ════════════════════════════════════════════

    @Test
    fun `bigTypeId 6 判为洗衣机`() {
        assertEquals(ModeDeviceKind.WASHER, ModeOrderPolicy.deviceKind(6, 3))
    }

    @Test
    fun `bigTypeId 7 判为吹风机`() {
        assertEquals(ModeDeviceKind.DRYER, ModeOrderPolicy.deviceKind(7, 5))
    }

    @Test
    fun `热水表 4 判为其它`() {
        // 热水器/水表那条链路（rateOrder）不归本包管，必须落 OTHER
        assertEquals(ModeDeviceKind.OTHER, ModeOrderPolicy.deviceKind(4, 1))
    }

    @Test
    fun `饮水机 5 判为其它`() {
        assertEquals(ModeDeviceKind.OTHER, ModeOrderPolicy.deviceKind(5, 2))
    }

    @Test
    fun `bigTypeId 为 null 时按 smallTypeId 兜底`() {
        assertEquals(ModeDeviceKind.WASHER, ModeOrderPolicy.deviceKind(null, 3))
        assertEquals(ModeDeviceKind.DRYER, ModeOrderPolicy.deviceKind(null, 5))
    }

    @Test
    fun `两个 id 都为 null 判为其它而不是猜一个`() {
        // 猜错的代价是拿热水表去调 modeOrder —— 一发出就扣全款，宁可不管
        assertEquals(ModeDeviceKind.OTHER, ModeOrderPolicy.deviceKind(null, null))
    }

    @Test
    fun `bigTypeId 优先于 smallTypeId`() {
        // big 说不是套餐设备时，small 的巧合值不能把它捞回来
        assertEquals(ModeDeviceKind.OTHER, ModeOrderPolicy.deviceKind(4, 3))
    }

    // ════════════════════════════════════════════
    //  remainingSeconds：三个边界
    // ════════════════════════════════════════════

    @Test
    fun `刚开始时剩余整段时长`() {
        assertEquals(180, ModeOrderPolicy.remainingSeconds(1_000_000L, 180, 1_000_000L))
    }

    @Test
    fun `已超时返回 0 而不是负数`() {
        // 负数会让界面显示「-01:23」这种没法看的东西
        assertEquals(0, ModeOrderPolicy.remainingSeconds(1_000_000L, 180, 1_500_000L))
    }

    @Test
    fun `中途按秒递减`() {
        // 60.5 秒 → 向下取整 60，剩 120
        assertEquals(120, ModeOrderPolicy.remainingSeconds(1_000_000L, 180, 1_060_500L))
    }

    @Test
    fun `时钟回拨时当作刚开始`() {
        // nowMs 早于 startedAtMs：手机时间被改过。返回整段而不是负数/0，
        // 真正的关停是服务端做的，我们只负责显示
        assertEquals(180, ModeOrderPolicy.remainingSeconds(1_000_000L, 180, 900_000L))
    }

    @Test
    fun `时长非法时返回 0`() {
        assertEquals(0, ModeOrderPolicy.remainingSeconds(1_000_000L, 0, 1_000_000L))
        assertEquals(0, ModeOrderPolicy.remainingSeconds(1_000_000L, -5, 1_000_000L))
    }

    // ════════════════════════════════════════════
    //  isLikelyFinished：容差方向只能是「晚」
    // ════════════════════════════════════════════

    @Test
    fun `正好到点还没过容差不算结束`() {
        // 实测吹风机 180 秒套餐实际跑了 181 秒才关。早一秒钟宣布结束 = 用户看着还在转的机器
        assertFalse(ModeOrderPolicy.isLikelyFinished(1_000_000L, 180, 1_180_000L, toleranceSec = 5))
    }

    @Test
    fun `超过时长加容差才算结束`() {
        assertTrue(ModeOrderPolicy.isLikelyFinished(1_000_000L, 180, 1_185_000L, toleranceSec = 5))
        assertTrue(ModeOrderPolicy.isLikelyFinished(1_000_000L, 180, 1_300_000L, toleranceSec = 5))
    }

    @Test
    fun `时钟回拨时不判结束`() {
        assertFalse(ModeOrderPolicy.isLikelyFinished(1_000_000L, 180, 900_000L, toleranceSec = 5))
    }

    // ════════════════════════════════════════════
    //  formatPrice：厘 → 元，两位
    // ════════════════════════════════════════════

    @Test
    fun `600 厘显示 0点60`() {
        assertEquals("0.60", ModeOrderPolicy.formatPrice(600))
    }

    @Test
    fun `1000 厘显示 1点00`() {
        assertEquals("1.00", ModeOrderPolicy.formatPrice(1000))
    }

    @Test
    fun `4000 厘显示 4点00`() {
        assertEquals("4.00", ModeOrderPolicy.formatPrice(4000))
    }

    @Test
    fun `价格为空显示占位符而不是 0点00`() {
        // 「免费」和「不知道多少钱」是两件事，后者绝不能显示成 0.00
        assertEquals(ModeOrderPolicy.PRICE_PLACEHOLDER, ModeOrderPolicy.formatPrice(null))
        assertFalse(ModeOrderPolicy.formatPrice(null).contains("0"))
    }

    // ════════════════════════════════════════════
    //  pickDefaultMode
    // ════════════════════════════════════════════

    @Test
    fun `优先选服务端标记 isSelected 为 1 的那档`() {
        val modes = listOf(
            mode(29, 600, 180, isSelected = 0),
            mode(30, 1000, 300, isSelected = 1),
            mode(31, 2000, 600, isSelected = 0)
        )

        assertEquals(30, ModeOrderPolicy.pickDefaultMode(modes)?.modeId)
    }

    @Test
    fun `没有标记时选第一档`() {
        val modes = listOf(
            mode(29, 600, 180, isSelected = 0),
            mode(30, 1000, 300, isSelected = 0)
        )

        assertEquals(29, ModeOrderPolicy.pickDefaultMode(modes)?.modeId)
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(ModeOrderPolicy.pickDefaultMode(emptyList()))
    }

    @Test
    fun `返回的是列表里那个对象本身`() {
        // 调用方会拿返回值直接去下单，复制一份字段就可能丢东西（比如 modeId 被复制成 id）
        val only = mode(31, 2000, 600, isSelected = 0)

        assertSame(only, ModeOrderPolicy.pickDefaultMode(listOf(only)))
    }

    // ════════════════════════════════════════════
    //  shouldConfirm：余额不足要拦
    // ════════════════════════════════════════════

    @Test
    fun `余额不足必须拦下`() {
        // 3356 钱包实测 26.14 元，大件洗 5 元够；但余额 3 元时点 4 元的标准洗就得拦
        val standardWash = mode(33, 4000, 2100, isSelected = 1)

        assertTrue(ModeOrderPolicy.shouldConfirm(standardWash, walletMilli = 3_000L))
    }

    @Test
    fun `余额刚好够不用额外拦`() {
        val standardWash = mode(33, 4000, 2100, isSelected = 1)

        assertFalse(ModeOrderPolicy.shouldConfirm(standardWash, walletMilli = 4_000L))
    }

    @Test
    fun `余额充足不用额外拦`() {
        val dryerMode = mode(29, 600, 180, isSelected = 0)

        assertFalse(ModeOrderPolicy.shouldConfirm(dryerMode, walletMilli = 26_140L))
    }

    @Test
    fun `拿不到余额时也要求确认`() {
        // 注意：这种情况 UI 文案不能写「余额不足」，应提示「拉取余额失败」
        val dryerMode = mode(29, 600, 180, isSelected = 0)

        assertTrue(ModeOrderPolicy.shouldConfirm(dryerMode, walletMilli = null))
    }

    @Test
    fun `套餐价格缺失时要求确认`() {
        val broken = WashMode(modeId = 29, modeName = "价格缺失")

        assertTrue(ModeOrderPolicy.shouldConfirm(broken, walletMilli = 26_140L))
    }

    // ════════════════════════════════════════════
    //  PendingModeOrder：本地倒计时视图
    // ════════════════════════════════════════════

    @Test
    fun `进行中的单按落单时间倒计时`() {
        val pending = PendingModeOrder(
            snCode = "C47F0E2351BE", modeId = 29, modeSeconds = 180,
            modeMoneyMilli = 600, placedAtMs = 1_000_000L, orderNo = "13202610240012244626"
        )

        assertEquals(180, pending.remainingSeconds(1_000_000L))
        assertEquals(0, pending.remainingSeconds(1_300_000L))
        assertTrue(pending.isLikelyFinished(1_300_000L))
        assertFalse(pending.isLikelyFinished(1_100_000L))
    }

    // ── 构造器 ──

    private fun mode(
        modeId: Int,
        moneyMilli: Int,
        seconds: Int,
        isSelected: Int
    ) = WashMode(
        modeId = modeId,
        modeName = "模式$modeId",
        modeMoney = moneyMilli,
        modeTimeQuantity = seconds,
        isSelected = isSelected
    )
}
