package com.hualala.linyu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防抢占轮询的**两种计费模型判据**。
 *
 * 两者查的接口不同，判据也不同——搞混了就是"热水器被占用了却显示空闲"
 * 或者"明明空闲却禁用按钮"。
 *
 * | 计费模型 | 接口 | 占用判据 |
 * |---|---|---|
 * | 套餐（洗衣机/吹风机） | `washer/status` | `errorCode==307` 或 `success && data != null` |
 * | 按量（热水器/洗手台/饮水机） | `query/rateOrder/using` | `errorCode==307` 或 `success && orderNo != null` |
 */
class PreemptBothBillingTest {

    /** 套餐设备的占用判据（与 `ModeStatus` 三态一致） */
    private fun isPackageBusy(success: Boolean?, errorCode: Int?, hasData: Boolean): Boolean {
        if (success == null) return false      // 请求失败 = 不知道，不算被抢
        if (errorCode == 307) return true
        if (!success) return false
        return hasData
    }

    /** 按量计费设备的占用判据（与 `ShowerController` 的 queryUsing 判断一致） */
    private fun isMeteredBusy(success: Boolean?, errorCode: Int?, orderNo: String?): Boolean {
        if (success == null) return false
        if (errorCode == 307) return true
        if (!success) return false
        return !orderNo.isNullOrBlank()
    }

    // ── 套餐 ──

    @Test
    fun `套餐设备 307 算占用`() {
        assertTrue(isPackageBusy(true, 307, true))
    }

    @Test
    fun `套餐设备 空闲`() {
        assertFalse(isPackageBusy(true, 0, false))
    }

    @Test
    fun `套餐设备 请求失败不算占用`() {
        assertFalse(isPackageBusy(null, null, false))
    }

    // ── 按量计费 ──

    @Test
    fun `按量设备 有 orderNo 算占用`() {
        assertTrue(isMeteredBusy(true, 0, "13202610051024562144"))
    }

    @Test
    fun `按量设备 307 算占用`() {
        assertTrue(isMeteredBusy(true, 307, null))
    }

    @Test
    fun `按量设备 orderNo 为空是空闲`() {
        assertFalse(isMeteredBusy(true, 0, null))
        assertFalse(isMeteredBusy(true, 0, ""))
        assertFalse(isMeteredBusy(true, 0, "   "))
    }

    /**
     * ⚠️ 这是两种模型最容易搞混的地方：
     * 按量设备的响应里 `data` **永远非空**（有 orderNo/isOwner 等字段），
     * 空闲时它只是 `orderNo == null`。如果照搬套餐的 `hasData` 判据，
     * 热水器会**永远显示"被占用"**。
     */
    @Test
    fun `按量设备不能照搬套餐的 data 非空判据`() {
        // 模拟：data 非空但 orderNo 为 null（空闲）
        val hasData = true
        val orderNo: String? = null
        assertTrue("套餐判据会误判成占用", isPackageBusy(true, 0, hasData))
        assertFalse("按量判据正确识别为空闲", isMeteredBusy(true, 0, orderNo))
    }

    @Test
    fun `按量设备请求失败不算占用`() {
        assertFalse(isMeteredBusy(null, null, null))
    }
}
