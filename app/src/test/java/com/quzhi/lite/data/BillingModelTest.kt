package com.quzhi.lite.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 计费模型判定——**UI 分流的第一性依据**。
 *
 * 这一条判错，后果不是显示难看，而是给不可退款的设备放出一个「停止」按钮。
 * 所以单独一组用例锁住。
 *
 * 实测依据见 [ModeOrderPolicy.billingModel] 的注释（账单 preDeduct vs consumeMoney）。
 */
class BillingModelTest {

    @Test
    fun `洗衣机和吹风机是套餐计费`() {
        assertEquals(BillingModel.PACKAGE,
            ModeOrderPolicy.billingModel(bigTypeId = 6, smallTypeId = 3))
        assertEquals(BillingModel.PACKAGE,
            ModeOrderPolicy.billingModel(bigTypeId = 7, smallTypeId = 5))
    }

    /**
     * 饮水机**必须是按量计费**，哪怕它和洗衣/吹风在同一个项目（3356）里。
     *
     * 实测账单：饮水机 preDeduct=2.0 而 consumeMoney=0.1/0.44（不等，按量结算），
     * 洗衣/吹风则是 preDeduct == consumeMoney（扣全款）。
     * 判成套餐的话会做出「饮水机也显示到点自动结束」的界面，而它其实可以中途关。
     */
    @Test
    fun `饮水机是按量计费不是套餐`() {
        assertEquals(BillingModel.METERED,
            ModeOrderPolicy.billingModel(bigTypeId = 5, smallTypeId = null))
        assertEquals(BillingModel.METERED,
            ModeOrderPolicy.billingModel(bigTypeId = 5, smallTypeId = 0))
    }

    @Test
    fun `热水表是按量计费`() {
        assertEquals(BillingModel.METERED,
            ModeOrderPolicy.billingModel(bigTypeId = 4, smallTypeId = 1))
        assertEquals(BillingModel.METERED,
            ModeOrderPolicy.billingModel(bigTypeId = 1, smallTypeId = 1))
    }

    /** 全空/未知一律按量：宁可走老链路，也不要误判成扣钱通道 */
    @Test
    fun `未知设备不进套餐通道`() {
        assertEquals(BillingModel.METERED, ModeOrderPolicy.billingModel(null, null))
        assertEquals(BillingModel.METERED, ModeOrderPolicy.billingModel(bigTypeId = 99, smallTypeId = 99))
    }

    /** bigTypeId 缺失时 smallTypeId 兜底，与 deviceKind 保持一致 */
    @Test
    fun `big 缺失时 small 兜底`() {
        assertEquals(BillingModel.PACKAGE, ModeOrderPolicy.billingModel(null, 3))
        assertEquals(BillingModel.PACKAGE, ModeOrderPolicy.billingModel(null, 5))
    }

    /** big 存在时以 big 为准——small=1 在热水链路上是「热水表」通用名，不能拿它判套餐 */
    @Test
    fun `big 优先于 small`() {
        assertEquals(BillingModel.METERED, ModeOrderPolicy.billingModel(bigTypeId = 4, smallTypeId = 3))
        assertEquals(BillingModel.PACKAGE, ModeOrderPolicy.billingModel(bigTypeId = 6, smallTypeId = 1))
    }
}
