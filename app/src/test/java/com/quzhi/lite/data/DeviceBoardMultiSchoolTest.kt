package com.quzhi.lite.data

import com.quzhi.lite.model.UserProject
import com.quzhi.lite.ui.ProjectSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不同学校的板块数量差异下，判定逻辑是否自适应。
 *
 * ## 为什么要单独测这组
 *
 * 现实中各校部署差别很大：
 * - **多数学校只有一个项目**（只有洗浴）→ 不该出现切换入口，也不该有任何「借板块」动作
 * - 有的学校两个项目（洗浴 4000 / 洗衣饮水吹风 3356）→ 要能切、跨板块设备要能操作
 * - 极端情况：绑定了项目但 `accountId` 缺失、或列表拉不到 → 必须降级而不是崩
 *
 * 修复「设备信息和项目信息不匹配（534）」时新增了 `withDeviceBoard`，
 * 它对单板块学校必须是**完全无感的**（不切认证参数、不发额外请求）。
 * 这组测试把这条性质锁住——否则改着改着就会把单板块用户拖坏。
 */
class DeviceBoardMultiSchoolTest {

    private val bath = 4000L
    private val wash = 3356L

    // ── operationBoard：不同板块数量下的取值 ──

    /**
     * 单板块学校：设备报的板块 == 当前板块 → 走「零开销」分支。
     *
     * 这是最关键的一条：绝大多数用户属于这类。`withDeviceBoard` 里
     * `board == currentProjectId` 时直接 `return block()`，
     * **不碰 NetworkModule 的全局认证参数**，因此行为与修复前完全一致。
     */
    @Test
    fun `单板块学校设备操作零开销`() {
        val only = bath
        assertEquals(only, DeviceBoardPolicy.operationBoard(deviceProjectId = only, currentProjectId = only))
    }

    /**
     * 单板块学校但服务端没下发设备 projectId（老接口 / 某些学校）。
     *
     * 返回 null = 保持当前板块别动 → 依然零开销，行为与修复前完全一致。
     */
    @Test
    fun `单板块学校设备没报板块时不切换`() {
        val only = bath
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = null, currentProjectId = only))
    }

    /** 多板块学校 + 跨板块设备 → 必须切到设备那个板块（否则服务端回 534） */
    @Test
    fun `多板块学校跨板块设备必须切板块`() {
        assertEquals(wash, DeviceBoardPolicy.operationBoard(deviceProjectId = wash, currentProjectId = bath))
    }

    /** 多板块学校 + 同板块设备 → 零开销 */
    @Test
    fun `多板块学校同板块设备零开销`() {
        assertEquals(wash, DeviceBoardPolicy.operationBoard(deviceProjectId = wash, currentProjectId = wash))
    }

    // ── needsBorrow：只有真正跨板块才借 ──

    /**
     * 单板块学校永远不该触发「借板块」——否则每个请求都会白白切换一次全局认证参数，
     * 增加并发串号的风险，却没有任何收益。
     */
    @Test
    fun `单板块学校永不借板块`() {
        assertFalse(DeviceBoardPolicy.needsBorrow(targetProjectId = bath, currentProjectId = bath))
        // 设备没报板块时 target 退回当前板块，同样不借
        val target = DeviceBoardPolicy.resolve(deviceProjectId = null, currentProjectId = bath)
        assertFalse(DeviceBoardPolicy.needsBorrow(target, bath))
    }

    @Test
    fun `多板块学校跨板块才借`() {
        assertTrue(DeviceBoardPolicy.needsBorrow(targetProjectId = wash, currentProjectId = bath))
        assertFalse(DeviceBoardPolicy.needsBorrow(targetProjectId = wash, currentProjectId = wash))
    }

    // ── ProjectSelectionPolicy：单板块 vs 多板块的界面差异 ──

    /** 只绑 1 个项目 → 不显示板块切换入口（多一个可点的东西就是噪音） */
    @Test
    fun `单板块不显示切换入口`() {
        assertFalse(ProjectSelectionPolicy.hasMultiple(listOf(project(bath, 100L))))
    }

    /** 绑 2 个及以上 → 显示切换入口 */
    @Test
    fun `多板块显示切换入口`() {
        assertTrue(ProjectSelectionPolicy.hasMultiple(listOf(project(bath, 100L), project(wash, 200L))))
    }

    /** 空列表（还没拉回来 / 拉失败）→ 当单板块处理，界面不闪切换入口 */
    @Test
    fun `板块列表为空时不显示切换入口`() {
        assertFalse(ProjectSelectionPolicy.hasMultiple(emptyList()))
    }

    /**
     * 缺 `accountId` 的条目要被 `usable` 过滤掉。
     *
     * 服务端理论上不会给，但真给了的话，拿它去发请求只会得到「没有项目权限」。
     * 过滤掉之后如果只剩 1 条，界面就自然退回单板块形态——这是我们要的自适应。
     */
    @Test
    fun `缺 accountId 的板块被忽略`() {
        val mixed = listOf(project(bath, 100L), UserProject(userId = 1L, projectId = wash, accountId = null))
        assertEquals(1, ProjectSelectionPolicy.usable(mixed).size)
        // 过滤后只剩一条 → 不显示切换入口
        assertFalse(ProjectSelectionPolicy.hasMultiple(mixed))
    }

    // ── defaultProject：默认选哪个 ──

    /** 单板块：无论如何就是那一个 */
    @Test
    fun `单板块默认就是它`() {
        val list = listOf(project(bath, 100L))
        assertEquals(bath, ProjectSelectionPolicy.defaultProject(list, null)?.projectId)
        assertEquals(bath, ProjectSelectionPolicy.defaultProject(list, bath)?.projectId)
        // 记住的 id 不在列表里（换过绑）也退回第一个，不能返回 null
        assertEquals(bath, ProjectSelectionPolicy.defaultProject(list, wash)?.projectId)
    }

    /** 多板块：记住的那个优先，其次第一个 */
    @Test
    fun `多板块优先沿用上次选择`() {
        val list = listOf(project(bath, 100L), project(wash, 200L))
        assertEquals(bath, ProjectSelectionPolicy.defaultProject(list, bath)?.projectId)
        assertEquals(wash, ProjectSelectionPolicy.defaultProject(list, wash)?.projectId)
        assertEquals(bath, ProjectSelectionPolicy.defaultProject(list, 99999L)?.projectId)
    }

    /** 空列表返回 null，调用方必须能处理（不能 first() 崩掉） */
    @Test
    fun `空板块列表返回 null`() {
        assertNull(ProjectSelectionPolicy.defaultProject(emptyList(), bath))
    }

    // ── resolve：找不到配对时必须放弃，不能拼凑 ──

    /**
     * 找不到该 projectId 的配对 → null。
     *
     * ⚠️ 绝不能拿当前板块的 accountId 去配新的 projectId：
     * 服务端会回「没有项目权限，请确认身份」，而且这种错误在界面上只表现为
     * 「数据是空的」，非常难查。
     */
    @Test
    fun `找不到配对的板块返回 null`() {
        val list = listOf(project(bath, 100L))
        assertNull(ProjectSelectionPolicy.resolve(list, wash))
        assertEquals(bath, ProjectSelectionPolicy.resolve(list, bath)?.projectId)
    }

    private fun project(projectId: Long, accountId: Long) =
        UserProject(userId = 1L, projectId = projectId, accountId = accountId)
}

/**
 * **别的学校**：projectId 不是 4000/3356 那套编码。
 *
 * ## 为什么单独测
 *
 * 趣智校园在各地部署的项目 id 完全不同。任何写死的数字（例如曾经想加的
 * `BATH_PROJECT_ID = 4000` 兜底）在换学校后都会把请求打到**不存在的板块**上，
 * 表现是「开阀报错」「余额一直加载中」，而且极难定位（代码里看不出错）。
 *
 * 这组用一组随意的 id（9991/7777，纯属编造）证明判定完全是**相对关系**，
 * 不依赖任何具体数值。
 */
class DeviceBoardOtherSchoolTest {

    private val projectA = 9991L   // 编造的「洗浴」
    private val projectB = 7777L   // 编造的「洗衣饮水吹风」

    /** 跨板块判据只比「相不相同」，与具体数值无关 */
    @Test
    fun `任意 id 都能正确判定跨板块`() {
        assertEquals(projectB, DeviceBoardPolicy.operationBoard(projectB, projectA))
        assertEquals(projectA, DeviceBoardPolicy.operationBoard(projectA, projectB))
    }

    @Test
    fun `任意 id 同板块都不切换`() {
        assertEquals(projectA, DeviceBoardPolicy.operationBoard(projectA, projectA))
        assertFalse(DeviceBoardPolicy.needsBorrow(projectA, projectA))
        assertTrue(DeviceBoardPolicy.needsBorrow(projectB, projectA))
    }

    /** 判断不出时返回 null，**绝不猜 4000 或任何其他写死的值** */
    @Test
    fun `判断不出时不返回硬编码 id`() {
        assertNull(DeviceBoardPolicy.operationBoard(null, projectA))
        assertNull(DeviceBoardPolicy.operationBoard(null, null))
        // 显式排除：结果不能等于任何特定的"已知" id
        assertNull(DeviceBoardPolicy.operationBoard(null, 4000L))
        assertNull(DeviceBoardPolicy.operationBoard(null, 3356L))
    }

    /** 单板块 + 设备没报 → null（不切），这是「别的学校只有一个项目」的典型路径 */
    @Test
    fun `别的学校单板块也不切换`() {
        assertNull(DeviceBoardPolicy.operationBoard(deviceProjectId = null, currentProjectId = projectA))
    }

    /** 板块列表、默认选择、配对解析同样不依赖具体 id */
    @Test
    fun `任意 id 的板块列表都能正常工作`() {
        val list = listOf(
            UserProject(userId = 1L, projectId = projectA, accountId = 11L),
            UserProject(userId = 1L, projectId = projectB, accountId = 22L)
        )
        assertTrue(ProjectSelectionPolicy.hasMultiple(list))
        assertEquals(projectA, ProjectSelectionPolicy.defaultProject(list, projectA)?.projectId)
        assertEquals(projectB, ProjectSelectionPolicy.defaultProject(list, projectB)?.projectId)
        assertEquals(22L, ProjectSelectionPolicy.resolve(list, projectB)?.accountId)
        // 没绑的板块 → null，不能拼凑
        assertNull(ProjectSelectionPolicy.resolve(list, 12345L))
    }
}
