package com.quzhi.lite.data

import com.quzhi.lite.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏设备的纯逻辑。
 *
 * 核心风险不是显示，而是**数据一致性**：
 * 收藏列表是持久化的，一旦写坏（重复条目、上限失控、坏 JSON 崩溃），
 * 用户每次打开首页都会看到错的东西，而且越积越糟。
 */
class FavoriteStoreTest {

    private fun fav(
        mac: String,
        name: String = "设备",
        big: Int? = 4,
        small: Int? = 1,
        savedAt: Long = 0L
    ) = FavoriteDevice(
        mac = mac,
        snCode = mac.replace(":", ""),
        displayName = name,
        rawName = name,
        emoji = "🚿",
        typeName = "热水器",
        bigTypeId = big,
        smallTypeId = small,
        withholdMoney = 5.0,
        savedAtMs = savedAt
    )

    private fun info(mac: String, name: String = "设备", big: Int? = 4, small: Int? = 1) = DeviceInfo(
        deviceId = 1,
        deviceName = name,
        snCode = mac.replace(":", ""),
        macAddress = mac,
        withholdMoney = 5.0,
        onlineStatusId = 1,
        bigTypeId = big,
        bigTypeName = "热水器",
        smallTypeId = small,
        smallTypeName = "热水器"
    )

    // ── 增删 ──

    @Test
    fun `添加一个收藏`() {
        val list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01"))
        assertEquals(1, list.size)
        assertEquals("AA:BB:CC:DD:EE:01", list[0].mac)
    }

    /** 同一台设备重复收藏只保留一条（用户看到的是 MAC，不能按 snCode 判重） */
    @Test
    fun `重复收藏不产生重复条目`() {
        var list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01", savedAt = 100))
        list = FavoriteStore.add(list, fav("AA:BB:CC:DD:EE:01", name = "改过的名字", savedAt = 200))
        assertEquals(1, list.size)
        assertEquals("改过的名字", list[0].displayName)
        // 位置不变：保留原来的 savedAtMs
        assertEquals(100L, list[0].savedAtMs)
    }

    /** MAC 大小写不同也算同一台（服务端下发可能是大写） */
    @Test
    fun `判重忽略大小写`() {
        var list = FavoriteStore.add(emptyList(), fav("aa:bb:cc:dd:ee:01"))
        list = FavoriteStore.add(list, fav("AA:BB:CC:DD:EE:01"))
        assertEquals(1, list.size)
    }

    @Test
    fun `移除收藏`() {
        var list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01"))
        list = FavoriteStore.add(list, fav("AA:BB:CC:DD:EE:02"))
        list = FavoriteStore.remove(list, "AA:BB:CC:DD:EE:01")
        assertEquals(1, list.size)
        assertEquals("AA:BB:CC:DD:EE:02", list[0].mac)
    }

    @Test
    fun `移除不存在的项不报错`() {
        val list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01"))
        assertEquals(1, FavoriteStore.remove(list, "FF:FF:FF:FF:FF:FF").size)
    }

    /** 空 MAC 不该被收藏：渲染不出名字、也没法下发指令 */
    @Test
    fun `空 MAC 被拒绝`() {
        assertTrue(FavoriteStore.add(emptyList(), fav("")).isEmpty())
        assertTrue(FavoriteStore.add(emptyList(), fav("   ")).isEmpty())
    }

    @Test
    fun `判重`() {
        val list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01"))
        assertTrue(FavoriteStore.contains(list, "AA:BB:CC:DD:EE:01"))
        assertTrue("大小写不影响", FavoriteStore.contains(list, "aa:bb:cc:dd:ee:01"))
        assertFalse(FavoriteStore.contains(list, "FF:FF:FF:FF:FF:FF"))
    }

    // ── 上限 ──

    @Test
    fun `超出上限时挤掉最早的`() {
        var list = emptyList<FavoriteDevice>()
        // 多塞 3 个
        for (i in 0 until FavoriteStore.MAX_FAVORITES + 3) {
            list = FavoriteStore.add(list, fav("AA:BB:CC:DD:%02X:%02X".format(i / 256, i % 256),
                savedAt = i.toLong()))
        }
        assertEquals(FavoriteStore.MAX_FAVORITES, list.size)
        // 最早的（savedAt=0,1,2）应被挤掉
        assertFalse(list.any { it.savedAtMs == 0L })
        assertTrue("最新的应保留", list.any { it.savedAtMs == (FavoriteStore.MAX_FAVORITES + 2).toLong() })
    }

    // ── 排序 ──

    @Test
    fun `新收藏排在前面`() {
        var list = FavoriteStore.add(emptyList(), fav("AA:BB:CC:DD:EE:01", savedAt = 100))
        list = FavoriteStore.add(list, fav("AA:BB:CC:DD:EE:02", savedAt = 300))
        list = FavoriteStore.add(list, fav("AA:BB:CC:DD:EE:03", savedAt = 200))
        val sorted = FavoriteStore.sorted(list)
        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.savedAtMs })
    }

    // ── 序列化 ──

    @Test
    fun `编解码往返不丢字段`() {
        val src = listOf(fav("AA:BB:CC:DD:EE:01", name = "3号楼-601", big = 6, small = 3, savedAt = 999))
        val back = FavoriteStore.decode(FavoriteStore.encode(src))
        assertEquals(1, back.size)
        val f = back[0]
        assertEquals("AA:BB:CC:DD:EE:01", f.mac)
        assertEquals("3号楼-601", f.displayName)
        // ⚠️ bigTypeId 必须存下来：离线时靠它判断计费模型
        assertEquals(6, f.bigTypeId)
        assertEquals(3, f.smallTypeId)
        assertEquals(999L, f.savedAtMs)
    }

    /** 坏 JSON 不能崩，只能退化成空列表 */
    @Test
    fun `坏 JSON 退化为空列表`() {
        assertTrue(FavoriteStore.decode("not a json").isEmpty())
        assertTrue(FavoriteStore.decode("{").isEmpty())
        assertTrue(FavoriteStore.decode("").isEmpty())
        assertTrue(FavoriteStore.decode(null).isEmpty())
    }

    /** 缺 mac 的旧数据（或坏数据）应被过滤，否则会渲染出一张点不动的卡 */
    @Test
    fun `缺少 mac 的条目被过滤`() {
        val json = """[{"mac":"","displayName":"坏数据"},{"mac":"AA:BB:CC:DD:EE:01","displayName":"好的"}]"""
        val list = FavoriteStore.decode(json)
        assertEquals(1, list.size)
        assertEquals("AA:BB:CC:DD:EE:01", list[0].mac)
    }

    // ── 转回 DeviceInfo（分流要能用）──

    @Test
    fun `收藏能转回 DeviceInfo 且保留计费模型判据`() {
        // 洗衣机 big=6 → 套餐计费
        assertTrue(fav("AA:BB:CC:DD:EE:01", big = 6, small = 3).toDeviceInfo().isPackageBilling)
        // 热水器 big=4 → 按量计费
        assertFalse(fav("AA:BB:CC:DD:EE:02", big = 4, small = 1).toDeviceInfo().isPackageBilling)
    }

    @Test
    fun `DeviceInfo 转收藏保留全部渲染字段`() {
        val f = info("AA:BB:CC:DD:EE:01", name = "洗手台-3号楼-1层", big = 6, small = 3)
            .toFavorite(nowMs = 12345)
        assertEquals("AA:BB:CC:DD:EE:01", f.mac)
        assertEquals("AABBCCDDEE01", f.snCode)
        assertEquals(6, f.bigTypeId)
        assertEquals(3, f.smallTypeId)
        assertEquals(12345L, f.savedAtMs)
        // ⚠️ displayName 是 **formatDeviceName 格式化后**的名字（"洗手台"会被转成房号写法），
        // 所以这里只断言「有可读的名字」，不断言原文——那是 rawName 的职责
        assertTrue("格式化后的名字不能为空，离线要能显示", f.displayName.isNotBlank())
        assertEquals("rawName 保留原始名（寝室筛选用）", "洗手台-3号楼-1层", f.rawName)
    }

    // ── 设备归属（projectId）──
    //
    // 这一组锁的是一个真 bug：从收藏进设备页时余额显示成了**当前板块**的钱。
    // 根因是 FavoriteDevice 没存 projectId，toDeviceInfo() 还原出 null，
    // 于是回退到当前板块。判错不崩不报错，只是安静地显示另一个板块的余额。

    @Test
    fun `收藏保留设备归属并还原回 DeviceInfo`() {
        val src = DeviceInfo(
            deviceId = 1, deviceName = "洗浴热水-3号宿舍楼-6层-609房",
            snCode = "AABBCCDDEE01", macAddress = "AA:BB:CC:DD:EE:01",
            withholdMoney = 5.0, onlineStatusId = 1,
            bigTypeId = 4, bigTypeName = "热水器", smallTypeId = 1, smallTypeName = "热水表",
            projectId = 4000L, projectName = "安徽三联学院洗浴"
        )
        val back = src.toFavorite(nowMs = 1L).toDeviceInfo()
        assertEquals(4000L, back.projectId)
        assertEquals("安徽三联学院洗浴", back.projectName)
    }

    @Test
    fun `归属经过 JSON 往返仍在`() {
        val src = DeviceInfo(
            deviceId = 1, deviceName = "洗衣机", snCode = "S1", macAddress = "AA:BB:CC:DD:EE:02",
            withholdMoney = 0.0, onlineStatusId = 1, bigTypeId = 6, smallTypeId = 3,
            projectId = 3356L, projectName = "安徽三联学院洗衣饮水吹风"
        )
        val json = FavoriteStore.encode(listOf(src.toFavorite(1L)))
        val decoded = FavoriteStore.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(3356L, decoded[0].projectId)
        assertEquals(3356L, decoded[0].toDeviceInfo().projectId)
    }

    /** 老收藏条目没有 projectId：解析不能崩，只留 null 让上层退回旧行为 */
    @Test
    fun `老数据没有 projectId 也不崩`() {
        val json = """[{"mac":"AA:BB:CC:DD:EE:03","snCode":"S3","displayName":"旧设备","rawName":"旧设备"}]"""
        val list = FavoriteStore.decode(json)
        assertEquals(1, list.size)
        assertNull(list[0].projectId)
        assertNull(list[0].toDeviceInfo().projectId)
    }
}
