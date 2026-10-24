package com.hualala.linyu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点开设备后要不要跳过详情框」的规则。
 *
 * ## 为什么这条要用测试锁住
 *
 * 两条路径的风险**完全不对称**：
 *
 * | 跳过详情后直接进 | 后果 |
 * |---|---|
 * | 套餐页（洗衣机/吹风机） | 只是看到套餐列表，**不点确认不扣钱**，多点一步纯浪费 |
 * | 开阀（热水器/饮水机） | **立刻出水 + 开始计费**，误触就是钱和水 |
 *
 * 所以规则必须写死：只有 `PACKAGE` 才能跳过详情框。
 * 哪天有人为了"统一体验"把按量计费也跳过，这个测试会红。
 */
class SkipDetailPolicyTest {

    /**
     * 是否可以在拿到设备信息后**跳过详情框**，直接进入操作页。
     *
     * 与 `MainViewModel.routeAfterDeviceInfo` 的判断**必须一致**——
     * 那边是 `if (info.isPackageBilling) 跳过 else 弹详情`。
     */
    private fun canSkipDetail(bigTypeId: Int?, smallTypeId: Int?): Boolean =
        ModeOrderPolicy.billingModel(bigTypeId, smallTypeId) == BillingModel.PACKAGE

    @Test
    fun `洗衣机和吹风机可以跳过详情`() {
        assertTrue("洗衣机", canSkipDetail(6, 3))
        assertTrue("吹风机", canSkipDetail(7, 5))
    }

    /**
     * 热水器 / 洗手台 / 饮水机**绝不能**跳过。
     *
     * 跳过的后果是：点一下设备卡片就 openValve，水立刻开始流、开始扣费。
     */
    @Test
    fun `按量计费设备绝不能跳过详情`() {
        assertFalse("热水表 big=4", canSkipDetail(4, 1))
        assertFalse("热水表 big=1", canSkipDetail(1, 1))
        assertFalse("饮水机 big=5", canSkipDetail(5, null))
        assertFalse("饮水机 big=5 small=0", canSkipDetail(5, 0))
    }

    /** 未知设备按「不跳过」处理：宁可多点一步，也不要误开水 */
    @Test
    fun `未知设备不跳过`() {
        assertFalse(canSkipDetail(null, null))
        assertFalse(canSkipDetail(99, 99))
    }

    /** big 缺失时 small 兜底，与 billingModel 保持一致 */
    @Test
    fun `big 缺失时 small 兜底跳过`() {
        assertTrue(canSkipDetail(null, 3))
        assertTrue(canSkipDetail(null, 5))
    }

    /** big 存在时以 big 为准 */
    @Test
    fun `big 优先`() {
        assertFalse("big=4 是热水表，small=3 不能把它变成套餐", canSkipDetail(4, 3))
        assertTrue("big=6 是洗衣机，small=1 不能把它变成按量", canSkipDetail(6, 1))
    }
}
