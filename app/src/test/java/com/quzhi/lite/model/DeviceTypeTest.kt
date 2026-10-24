package com.quzhi.lite.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备类型识别的回归测试。
 *
 * 起因是一个真实 issue：某林业大学的表叫「**热水变**--1栋宿舍-11层-1001」，
 * 被识别成「饮水机 · 热水」——因为当时的判据是「名字含『热水』且不以
 * 『热水器』『热水表』开头」。淋浴设备的名字天然就含「热水」，
 * 这种**枚举排除法**遇到新命名就漏。
 *
 * 所以这里把「淋浴设备绝不能被判成饮水机」这件事锁死，
 * 而且刻意用**多种命名变体**来测，避免以后又变成打地鼠。
 */
class DeviceTypeTest {

    private fun device(
        name: String,
        bigTypeId: Int? = null,
        bigTypeName: String? = null,
        smallTypeId: Int? = null,
        isBle: Boolean? = null
    ) =
        DeviceInfo(
            deviceId = 1,
            deviceName = name,
            snCode = "SN",
            macAddress = "AA:BB:CC:DD:EE:FF",
            withholdMoney = 0.0,
            onlineStatusId = 1,
            bigTypeId = bigTypeId,
            bigTypeName = bigTypeName,
            smallTypeId = smallTypeId,
            isBle = isBle
        )

    // ── 蓝牙水表识别 ──

    @Test
    fun `only server-side isBle marks a bluetooth meter`() {
        // ⚠️ 实现**刻意只认 isBle**（见 DeviceModels.needsBluetoothControl 注释）：
        // smallTypeId==1 是「热水表」这个**通用类型名**——某校全校 162 条设备全是 1，
        // 却都是能云端开阀的 4G 表；早期拿它当蓝牙判据导致全校开阀失败。
        assertFalse(device("热水表-某工商职业学院-16D-1层-101房", smallTypeId = 1).needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1).needsBluetoothControl)

        // 只有服务端明确标记才算蓝牙表
        assertTrue(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1, isBle = true).needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001", smallTypeId = 1, isBle = false).needsBluetoothControl)
    }

    @Test
    fun `smallTypeId 3 is cloud controllable`() {
        // 潍坊 790 系列能正常开阀
        assertFalse(device("790-16D某工商职业学院-1层-101", smallTypeId = 3).needsBluetoothControl)
    }

    /**
     * 关键防误判：字段缺失时**绝不能**当成蓝牙表。
     *
     * 旧版本客户端、或返回结构不同的学校可能拿不到 `smallTypeId`，
     * 那种情况下如果判成「不支持」，会把本来能用的设备直接挡在门外——
     * 比漏判严重得多（漏判只是回到现在的 306 提示）。
     */
    @Test
    fun `missing smallTypeId is never treated as bluetooth-only`() {
        assertFalse(device("热水表-某校-1号楼-1层-101").needsBluetoothControl)
        assertFalse(device("热水变--1栋宿舍-11层-1001").needsBluetoothControl)
    }

    // ── 淋浴设备：一律不能判成饮水机 ──

    @Test
    fun `热水变 is not drinking water`() {
        // 就是那个 issue 的原始名字，一个字都不要改
        val d = device("热水变--1栋宿舍-11层-1001", bigTypeId = 4, bigTypeName = "热水表")
        assertFalse(d.isDrinkingWater)
        assertEquals("热水器", d.typeName)
        assertEquals("🚿", d.typeEmoji)
        assertEquals("正在沐浴中", d.statusText)
    }

    @Test
    fun `热水表 is not drinking water`() {
        val d = device("热水表-某工商职业学院-16D-1层-101房", bigTypeId = 4)
        assertFalse(d.isDrinkingWater)
        assertEquals("🚿", d.typeEmoji)
    }

    @Test
    fun `热水器 is not drinking water`() {
        val d = device("热水器-某校-3号楼-3层-320")
        assertFalse(d.isDrinkingWater)
        assertEquals("🚿", d.typeEmoji)
    }

    /**
     * 关键防回归：**任何以「热水+单字」开头的淋浴命名都不能被判成饮水机**。
     * 以后遇到新变体时，这个测试会告诉你「排除法漏了」，而不是等用户来报。
     */
    @Test
    fun `any 热水X shower naming is not drinking water`() {
        listOf("热水表", "热水器", "热水变", "热水源", "热水机", "热水站", "热水房").forEach { prefix ->
            val d = device("$prefix-某校-1号楼-5层-829")
            assertFalse("「$prefix」被误判成饮水机了", d.isDrinkingWater)
        }
    }

    @Test
    fun `洗手台 is not drinking water`() {
        val d = device("洗手台54-某小区-1栋-1层-101洗手台")
        assertFalse(d.isDrinkingWater)
        assertEquals("洗手台", d.typeName)
        assertEquals("🪥", d.typeEmoji)
    }

    // ── 饮水机：必须能认出来 ──

    @Test
    fun `bigTypeId 5 is drinking water`() {
        val d = device("某某设备-1号楼-1层-101", bigTypeId = 5)
        assertTrue(d.isDrinkingWater)
        assertEquals("饮水机", d.typeName)
    }

    @Test
    fun `直饮 naming is drinking water`() {
        listOf("直饮热水-某校-1号楼-1层-101", "直饮冷水-某校-1号楼-1层-101").forEach { n ->
            assertTrue("「$n」没被认成饮水机", device(n).isDrinkingWater)
        }
    }

    @Test
    fun `饮水机 and 开水机 naming is drinking water`() {
        listOf("饮水机-1号楼-1层-101", "开水机-1号楼-1层-101").forEach { n ->
            assertTrue("「$n」没被认成饮水机", device(n).isDrinkingWater)
        }
    }

    @Test
    fun `bigTypeName containing 饮 is drinking water`() {
        // 服务端大类名带「饮」字也算（正向判据）
        assertTrue(device("某某-1号楼-1层-101", bigTypeName = "直饮水机").isDrinkingWater)
    }

    // ── 饮水机冷热：只认名字里的「冷水」 ──
    //
    // 服务端**不下发任何冷热字段**（实测 /device/info/mac 完整字段与账单 consumeType
    // 都只有类型和位置），只能靠名字。安徽三联学院那台叫
    // 「饮水机-饮水机-3号宿舍楼-6层-东边卫生间-2」——既不写热也不写冷，
    // 以前被判成冷水，界面挂雪花 ❄️ 显示「正在接凉水」，实际是开水器。

    @Test
    fun `name without 冷水 defaults to hot water`() {
        val d = device("饮水机-饮水机-3号宿舍楼-6层-东边卫生间-2", bigTypeId = 5)
        assertTrue("没有「冷水」字样时应该按热水", d.isHotWater)
        assertEquals("♨️", d.typeEmoji)
        assertEquals("直饮水机 · 热水", d.typeLabel)
        assertEquals("正在接热水", d.statusText)
    }

    @Test
    fun `explicit 冷水 stays cold`() {
        val d = device("直饮冷水-某校-1号楼-1层-101", bigTypeId = 5)
        assertFalse(d.isHotWater)
        assertEquals("❄️", d.typeEmoji)
        assertEquals("直饮水机 · 冷水", d.typeLabel)
    }

    // ── 洗衣机 / 吹风机：要有自己的类型、emoji 和图标 ──
    //
    // 判据只用数字（bigTypeId / smallTypeId），因为服务端同一台设备在三处接口
    // 叫三个名字（洗衣机 / 洗烘设备 / 洗烘设备）。

    @Test
    fun `washer has its own type and emoji`() {
        val d = device("洗衣机-洗衣机-3号宿舍楼-6层-洗衣房-4", bigTypeId = 6, smallTypeId = 3)
        assertEquals("洗衣机", d.typeName)
        assertEquals("🧺", d.typeEmoji)
        assertEquals("洗衣机", d.typeLabel)
        assertEquals("正在洗涤中", d.statusText)
        assertFalse("洗衣机不是饮水机", d.isDrinkingWater)
        assertTrue("洗衣机走套餐计费", d.isPackageBilling)
    }

    @Test
    fun `dryer has its own type and emoji`() {
        val d = device("吹风机-吹风机-3号宿舍楼-6层-东边卫生间-1", bigTypeId = 7, smallTypeId = 5)
        assertEquals("吹风机", d.typeName)
        assertEquals("💨", d.typeEmoji)
        assertEquals("吹风机", d.typeLabel)
        assertEquals("正在吹风中", d.statusText)
        assertFalse("吹风机不是饮水机，不能被当成热水器", d.isDrinkingWater)
        assertTrue("吹风机走套餐计费", d.isPackageBilling)
    }

    /**
     * 关键防回归：**存量数据的 emoji 取值一个都不能变**。
     *
     * `typeEmoji` 会写进 `PrefsHelper.lastDeviceEmoji` 和小组件 JSON 快照，
     * 改了这几种的取值，老用户桌面上的设备会一律显示成默认类型。
     */
    @Test
    fun `legacy emoji values are unchanged`() {
        assertEquals("🚿", device("热水器-某校-3号楼-3层-320", bigTypeId = 4).typeEmoji)
        assertEquals("🪥", device("洗手台-某校-1号楼-1层-101", bigTypeId = 4).typeEmoji)
    }

    // ── 账单里的设备类型（和附近设备用同一套 emoji）──

    @Test
    fun `bill dryer is not labeled as bathroom heater`() {
        val bill = BillDTO(
            orderId = "1", consumeDate = "2026-10-24 03:29:00", consumeMoney = "0.6",
            description = "吹风机:吹风机-吹风机-3号宿舍楼-6层-东边卫生间-1"
        )
        assertEquals("吹风机", bill.deviceTypeLabel)
        assertEquals("💨", bill.deviceEmoji)
    }

    @Test
    fun `bill washer is recognized`() {
        val bill = BillDTO(
            orderId = "2", consumeDate = "2026-10-24 09:51:00", consumeMoney = "4.0",
            description = "洗烘设备:洗衣机-洗衣机-3号宿舍楼-6层-洗衣房-4"
        )
        assertEquals("洗衣机", bill.deviceTypeLabel)
        assertEquals("🧺", bill.deviceEmoji)
    }

    @Test
    fun `bill shower device is still bathroom heater`() {
        val bill = BillDTO(
            orderId = "3", consumeDate = "2026-10-24 00:00:00", consumeMoney = "0.72",
            description = "热水表:热水表-洗浴热水-3号宿舍楼-6层-609房"
        )
        assertEquals("卫生间热水器", bill.deviceTypeLabel)
        assertEquals("🚿", bill.deviceEmoji)
    }

    @Test
    fun `bill drinking water keeps faucet emoji`() {
        val bill = BillDTO(
            orderId = "4", consumeDate = "2026-10-24 13:45:00", consumeMoney = "0.04",
            description = "饮水机:饮水机-饮水机-3号宿舍楼-6层-东边卫生间-2"
        )
        assertEquals("饮水机", bill.deviceTypeLabel)
        assertEquals("🚰", bill.deviceEmoji)
    }

    // ── 设备名格式化 & 寝室键 ──

    @Test
    fun `热水变 prefix is stripped from display name`() {
        val formatted = DeviceInfo.formatDeviceName("热水变--1栋宿舍-11层-1001")
        assertFalse("显示名还残留着「热水变」", formatted.startsWith("热水变"))
        assertTrue(formatted.contains("1001"))
    }

    /**
     * 同寝室的热水变和洗手台必须算出**同一个**寝室键，
     * 否则绑定寝室后其中一台会被筛掉。
     */
    @Test
    fun `热水变 and 洗手台 in same room share a room key`() {
        val a = DeviceInfo.roomKey("热水变--1栋宿舍-11层-1001")
        val b = DeviceInfo.roomKey("洗手台-1栋宿舍-11层-1001洗手台")
        assertEquals(a, b)
        assertTrue(DeviceInfo.inSameRoom(a!!, "热水变--1栋宿舍-11层-1001"))
    }

    @Test
    fun `roomKey is idempotent for 热水变`() {
        val key = DeviceInfo.roomKey("热水变--1栋宿舍-11层-1001")!!
        assertEquals(key, DeviceInfo.roomKey(key))
    }
}
