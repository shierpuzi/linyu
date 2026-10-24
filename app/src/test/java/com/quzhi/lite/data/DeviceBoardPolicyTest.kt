package com.quzhi.lite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** 两边都给不出就返回 null：此时界面不该显示余额，而不是显示错的钱包 */
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

    @Test
    fun `判断不出目标时不借`() {
        assertFalse(DeviceBoardPolicy.needsBorrow(targetProjectId = null, currentProjectId = wash))
    }

    /** 洗浴兜底 id 存在且是 4000（本项目实测校） */
    @Test
    fun `洗浴兜底 id`() {
        assertEquals(4000L, DeviceBoardPolicy.BATH_PROJECT_ID)
        assertNotNull(DeviceBoardPolicy.resolve(DeviceBoardPolicy.BATH_PROJECT_ID, null))
    }
}
