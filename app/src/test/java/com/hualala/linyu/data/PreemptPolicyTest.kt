package com.hualala.linyu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「待确认页被别人抢先」的判定规则。
 *
 * ## 这个规则为什么值得单独测
 *
 * 用户停在确认弹窗时可能在犹豫、找卡、跟室友说话。这段时间设备可能被抢。
 * 判早判晚都不对：
 *
 * | 判错方向 | 后果 |
 * |---|---|
 * | 网络抖一下就判「被抢」 | 用户明明可以下单，按钮却被禁用，白白错过 |
 * | 被抢了却没判出来 | 用户点确认 → 服务端拒绝 → 体验是"点了没反应" |
 *
 * 所以 [shouldTreatAsPreempted] 只在**明确的服务端占用信号**上返回 true，
 * 网络失败一律不算。
 */
class PreemptPolicyTest {

    /**
     * 服务端响应 → 是否算「被别人抢先占用」。
     *
     * 判据沿用 [ModeOrderController.queryStatus] 的三态语义（实测样本见该类注释）：
     * - `errorCode == 307` → 占用中（**success 仍是 true**，只看 success 会漏判）
     * - `success == true && data != null` → 占用中
     * - `success == true && data == null` → 空闲
     * - 请求异常 / `success == false` → **不确定**，不算被抢
     */
    private fun shouldTreatAsPreempted(
        success: Boolean?,
        errorCode: Int?,
        hasData: Boolean
    ): Boolean {
        if (success == null) return false      // 请求失败 = 不知道
        if (errorCode == 307) return true      // 明确的"设备正在使用中"
        if (!success) return false             // 服务端拒绝但不是占用 → 不算被抢
        return hasData                          // success + 有数据 = 有单
    }

    @Test
    fun `errorCode 307 是被抢占`() {
        // 实测样本：{"success":true,"errorCode":307,"errorMessage":"设备正在使用中","data":{...}}
        assertTrue(shouldTreatAsPreempted(success = true, errorCode = 307, hasData = true))
    }

    /** ⚠️ 真实坑：307 时 success 仍是 true，只看 success 会误判成正常 */
    @Test
    fun `success 为 true 不代表没被占`() {
        assertTrue("307 + success=true 必须判成被占",
            shouldTreatAsPreempted(success = true, errorCode = 307, hasData = true))
    }

    @Test
    fun `success 且有数据是被抢占`() {
        assertTrue(shouldTreatAsPreempted(success = true, errorCode = 0, hasData = true))
    }

    @Test
    fun `success 且无数据是空闲`() {
        assertFalse(shouldTreatAsPreempted(success = true, errorCode = 0, hasData = false))
    }

    /**
     * **请求失败绝不能判成被抢**：断网时把「不知道」当「被占用」，
     * 会让用户明明能下单却被禁用按钮，而且他还不知道为什么。
     */
    @Test
    fun `请求失败不算被抢占`() {
        assertFalse(shouldTreatAsPreempted(success = null, errorCode = null, hasData = false))
    }

    /** 服务端明确拒绝（如参数错）但不是占用 → 不该显示"被别人用了" */
    @Test
    fun `服务端拒绝但不是 307 不算被抢占`() {
        assertFalse(shouldTreatAsPreempted(success = false, errorCode = 1, hasData = false))
    }

    // ── 占用文案（给用户看的） ──

    /**
     * 占用原因文案：要能区分「别人的单」和「自己的单」。
     *
     * 用户看到「已被他人占用」和「你自己的单还在跑」的处理方式完全不同：
     * 前者换台设备，后者去结束它。
     */
    @Test
    fun `占用文案区分自己的单和别人的单`() {
        assertEquals("别人的单", ownerText(isOwner = false))
        assertEquals("你自己的单", ownerText(isOwner = true))
        assertEquals("有进行中的单", ownerText(isOwner = null))
    }

    private fun ownerText(isOwner: Boolean?): String = when (isOwner) {
        true -> "你自己的单"
        false -> "别人的单"
        else -> "有进行中的单"
    }

    /** 有剩余秒数时要带上，用户能判断"等 2 分钟"还是"换一台" */
    @Test
    fun `有剩余时间时文案带时间`() {
        val text = withRemaining("别人的单", expireTimes = 729)
        assertTrue("应含剩余时间", text.contains("12") || text.contains("分"))
    }

    private fun withRemaining(who: String, expireTimes: Int?): String =
        if (expireTimes != null && expireTimes > 0) {
            "$who，剩余约 ${ModeOrderPolicy.formatDuration(expireTimes.toLong())}"
        } else who
}
