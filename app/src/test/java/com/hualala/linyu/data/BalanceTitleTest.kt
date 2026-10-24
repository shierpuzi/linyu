package com.hualala.linyu.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 余额标题的文案测试。
 *
 * 只测 [BalanceEstimator.titleFor] 这个纯函数：真机上最容易出的错不是数字算错，
 * 而是**标题写错账户**——把趣智校园钱包的钱写成「一卡通余额」，用户会去校园卡
 * 系统对账然后发现对不上。所以三个分支各锁一条。
 */
class BalanceTitleTest {

    @Test
    fun `一卡通优先，且不带板块名`() {
        assertEquals("一卡通余额", BalanceEstimator.titleFor(hasCampus = true, hasWallet = true, projectName = "洗浴"))
        assertEquals("一卡通余额", BalanceEstimator.titleFor(hasCampus = true, hasWallet = false, projectName = "洗浴"))
    }

    @Test
    fun `钱包余额带上板块名`() {
        assertEquals(
            "趣智校园洗浴余额",
            BalanceEstimator.titleFor(hasCampus = false, hasWallet = true, projectName = "洗浴")
        )
        assertEquals(
            "趣智校园洗衣饮水吹风余额",
            BalanceEstimator.titleFor(hasCampus = false, hasWallet = true, projectName = "洗衣饮水吹风")
        )
    }

    /** 板块名拿不到时宁可不写，也不能显示「趣智校园项目 3356 余额」这种像 bug 的文案 */
    @Test
    fun `板块名缺失时退回不带板块的标题`() {
        assertEquals("趣智校园余额", BalanceEstimator.titleFor(false, true, null))
        assertEquals("趣智校园余额", BalanceEstimator.titleFor(false, true, ""))
        assertEquals("趣智校园余额", BalanceEstimator.titleFor(false, true, "   "))
    }

    @Test
    fun `都没有时只是个「余额」`() {
        assertEquals("余额", BalanceEstimator.titleFor(hasCampus = false, hasWallet = false, projectName = "洗浴"))
    }

    /** 板块名前后空白不该进到标题里（接口值偶带空格） */
    @Test
    fun `板块名会 trim`() {
        assertEquals("趣智校园洗浴余额", BalanceEstimator.titleFor(false, true, " 洗浴 "))
    }
}
