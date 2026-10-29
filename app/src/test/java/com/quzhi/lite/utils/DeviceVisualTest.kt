package com.quzhi.lite.utils

import com.quzhi.lite.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备 emoji → 图标 / 模块名的**唯一出处**。
 *
 * ## 为什么这个测试值得存在
 *
 * 这套映射以前在三个地方各写一份（`ui/DeviceGlyph`、`widget/WidgetRenderer`、
 * 通知里干脆没接、固定用水滴）。三份必须一致，改一处漏两处，用户就会看到
 * 「同一台设备在 App 里是洗衣机、在桌面和胶囊上是热水器」。
 *
 * 收口到 [DeviceVisual] 之后，这里钉住三件事：
 * 1. 每种设备的 emoji 都映射到**各自不同**的图标（`ic_device_*` 只有 5 个，
 *    写错一个字母就会静默退化成 else 分支的热水器图标——编译不报错，只有肉眼能发现）；
 * 2. 饮水机三种取值（热水/冷水/未知）**共用**一个图标，这是有意的；
 * 3. 未知值一律兜底热水器，不能返回空串或抛异常。
 */
class DeviceVisualTest {

    // ════════════════════════════════════════════
    //  模块名
    // ════════════════════════════════════════════

    @Test
    fun `每种设备都有自己的模块名`() {
        assertEquals("洗手台", DeviceVisual.moduleName("🪥"))
        assertEquals("洗衣机", DeviceVisual.moduleName("🧺"))
        assertEquals("吹风机", DeviceVisual.moduleName("💨"))
    }

    /** 饮水机三种取值同一套文案：冷热已经由「正在接热水/凉水」表达，模块名不必再分 */
    @Test
    fun `饮水机三种取值都是饮水机`() {
        assertEquals("饮水机", DeviceVisual.moduleName("♨️"))
        assertEquals("饮水机", DeviceVisual.moduleName("❄️"))
        assertEquals("饮水机", DeviceVisual.moduleName("🚰"))
    }

    /**
     * 兜底：null（老快照没这个字段）和认不出的值都当热水器。
     *
     * ⚠️ 不能兜成空串——模块名会直接拼进胶囊左侧文字，空串会让胶囊一侧凭空缩掉。
     */
    @Test
    fun `未知值与 null 兜底成热水器`() {
        assertEquals("热水器", DeviceVisual.moduleName(null))
        assertEquals("热水器", DeviceVisual.moduleName(""))
        assertEquals("热水器", DeviceVisual.moduleName("xx"))
        assertEquals("热水器", DeviceVisual.moduleName("🚿"))
    }

    /**
     * 模块名必须短。
     *
     * OPPO 流体云规范要求胶囊单侧文本精简，超过 5 个字符会被截成「…」。
     * 改造前胶囊左右不对称，就是因为一侧写的是「卫生间热水器」这种全称。
     */
    @Test
    fun `模块名不超过 3 个字`() {
        val all = listOf("🪥", "🧺", "💨", "♨️", "❄️", "🚰", "🚿", null, "xx")
        for (emoji in all) {
            val name = DeviceVisual.moduleName(emoji)
            assertTrue("emoji=$emoji 的模块名「$name」超过 3 字", name.length <= 3)
        }
    }

    // ════════════════════════════════════════════
    //  图标
    // ════════════════════════════════════════════

    @Test
    fun `每种设备映射到各自的图标`() {
        assertEquals(R.drawable.ic_device_sink, DeviceVisual.iconRes("🪥"))
        assertEquals(R.drawable.ic_device_washer, DeviceVisual.iconRes("🧺"))
        assertEquals(R.drawable.ic_device_dryer, DeviceVisual.iconRes("💨"))
        assertEquals(R.drawable.ic_device_shower, DeviceVisual.iconRes("🚿"))
    }

    /**
     * 五个图标 id 必须两两不同。
     *
     * 上面那组 assertEquals 已经钉死了具体资源，但**复制粘贴写错名字**时最容易出的错
     * 是「两个分支指向同一个 drawable」——编译通过、测试也过（如果只比对一个），
     * 只有并排看才看得出来。所以这里再显式比一次总数。
     */
    @Test
    fun `五种设备的图标 id 互不相同`() {
        val ids = listOf("🪥", "🧺", "💨", "♨️", "🚿").map { DeviceVisual.iconRes(it) }
        assertEquals("五个图标里有重复的：$ids", 5, ids.toSet().size)
    }

    /** 饮水机冷热共用一张 24dp 单色图——差异由文案表达，不靠图标 */
    @Test
    fun `饮水机三种取值共用一个图标`() {
        val hot = DeviceVisual.iconRes("♨️")
        assertEquals(R.drawable.ic_device_faucet, hot)
        assertEquals(hot, DeviceVisual.iconRes("❄️"))
        assertEquals(hot, DeviceVisual.iconRes("🚰"))
        // 别和淋浴混了：饮水机 ≠ 热水器
        assertNotEquals(DeviceVisual.iconRes("🚿"), hot)
    }

    /**
     * 兜底必须给热水器图标。
     *
     * 以前饮水机没有矢量图时这里返回 0（透明），卡片上留一块空白；
     * 现在要求永远显示成一台设备，宁可显示得不精确，也不要空白或彩色 emoji。
     */
    @Test
    fun `未知值与 null 兜底成热水器图标`() {
        val shower = R.drawable.ic_device_shower
        assertEquals(shower, DeviceVisual.iconRes(null))
        assertEquals(shower, DeviceVisual.iconRes(""))
        assertEquals(shower, DeviceVisual.iconRes("xx"))
        assertEquals(shower, DeviceVisual.iconRes("🚿"))
    }
}
