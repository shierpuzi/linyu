package com.quzhi.lite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「余额取哪个板块」的判定测试。
 *
 * 这里锁的是**钱显示在哪个钱包**这件事：判错不会崩、不会报错，
 * 只是安静地显示另一个板块的余额——用户看到的就是「余额不对」。
 */
class DeviceBoardPolicyTest {

    private val bath = 4000L    // 洗浴
    private val wash = 3356L    // 洗衣饮水吹风

    /** 设备自己报了板块，就信它——哪怕和当前板块不同 */
    @Test
    fun `设备报的板块优先于当前板块`() {
        assertEquals(bath, DeviceBoardPolicy.resolve(deviceProjectId = bath, currentProjectId = wash))
        assertEquals(wash, DeviceBoardPolicy.resolve(deviceProjectId = wash, currentProjectId = bath))
    }

    /** 设备没报板块（老接口/某些学校），退回当前板块 */
    @Test
    fun `设备没报板块时退回当前板块`() {
        assertEquals(wash, DeviceBoardPolicy.resolve(deviceProjectId = null, currentProjectId = wash))
    }

    /** 0 / 负数不是合法 projectId，当作没报 */
    @Test
    fun `非法 projectId 视为没报`() {
        assertEquals(wash, DeviceBoardPolicy.resolve(deviceProjectId = 0L, currentProjectId = wash))
        assertEquals(wash, DeviceBoardPolicy.resolve(deviceProjectId = -1L, currentProjectId = wash))
    }

    /**
     * 两边都给不出 → null（不显示余额）。
     *
     * ⚠️ 这里曾经兜底成 `BATH_PROJECT_ID = 4000`（本实测校的洗浴）。
     * 那个常量已删除：换学校它就是个错的 id，会让界面要么一直加载中、
     * 要么显示别的板块的钱。现在改为「不显示」，这是有意的降级。
     */
    @Test
    fun `都判断不出时返回 null`() {
        assertNull(DeviceBoardPolicy.resolve(deviceProjectId = null, currentProjectId = null))
        assertNull(DeviceBoardPolicy.resolve(deviceProjectId = 0L, currentProjectId = 0L))
    }

    /**
     * 只有跨板块时才需要借数据。
     * 同板块时界面上的 walletBalance 已经是那个板块的钱，再借一次纯属
     * 让全局认证参数短暂变身——能不做就不做。
     */
    @Test
    fun `同板块不需要借`() {
        assertFalse(DeviceBoardPolicy.needsBorrow(targetProjectId = bath, currentProjectId = bath))
    }

    @Test
    fun `跨板块需要借`() {
        assertTrue(DeviceBoardPolicy.needsBorrow(targetProjectId = bath, currentProjectId = wash))
    }

    /**
     * 判断不出目标时不借。
     *
     * ⚠️ 这条也顺带锁住「不再有硬编码兜底」：以前有个 `BATH_PROJECT_ID = 4000`
     * 会在两边都判断不出时返回 4000，换学校就是错的 id。现在一律 null。
     */
    @Test
    fun `判断不出目标时不借`() {
        assertFalse(DeviceBoardPolicy.needsBorrow(targetProjectId = null, currentProjectId = wash))
    }

    // ── operationBoard：设备操作（开阀/关阀/下单）该用哪个板块 ──
    //
    // 这组锁的是「设备信息和项目信息不匹配（534）」那个 bug：
    // 饮水机属于 3356，而用户停在 4000 板块。以前余额借对了板块，
    // 但**真正下发指令的那条路**用的是当前板块 → downRate 回 534。

    /** 跨板块设备：必须用设备自己的板块，不能用当前板块 */
    @Test
    fun `设备操作使用设备所属板块`() {
        assertEquals(wash, DeviceBoardPolicy.operationBoard(deviceProjectId = wash, currentProjectId = bath))
        assertEquals(bath, DeviceBoardPolicy.operationBoard(deviceProjectId = bath, currentProjectId = wash))
    }

    /** 同板块：返回同一个值，调用方据此跳过切换（零开销） */
    @Test
    fun `同板块时设备操作沿用当前板块`() {
        assertEquals(wash, DeviceBoardPolicy.operationBoard(deviceProjectId = wash, currentProjectId = wash))
    }

    /**
     * 设备没报板块（老数据 / 服务端不下发）→ **null = 保持当前板块别动**。
     *
     * 这是单板块学校和老数据的兜底：null 让调用方直接透明执行，行为与修复前一致。
     * 注意**不能**返回任何硬编码 id（以前这里返回 BATH_PROJECT_ID=4000，
     * 换学校就会把请求打到不存在的板块上）。
     */
    @Test
    fun `设备没报板块时返回 null 表示不动`() {
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = null, currentProjectId = wash))
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = 0L, currentProjectId = bath))
    }

    /** 什么都判断不出也返回 null，绝不猜一个板块 */
    @Test
    fun `操作板块都判断不出时返回 null`() {
        assertNull(DeviceBoardPolicy.operationBoard(null, null))
    }

    /** 负数 / 0 不是合法板块，当作没报 */
    @Test
    fun `非法设备板块不参与判定`() {
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = -5L, currentProjectId = wash))
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = 0L, currentProjectId = wash))
    }
}
