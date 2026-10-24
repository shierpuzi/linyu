package com.hualala.linyu.ui

import com.hualala.linyu.model.UserProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 板块选择逻辑的单测。
 *
 * 用的就是实测那两条数据（4000 洗浴 / 3356 洗衣饮水吹风），
 * 把「默认选哪个」「按 projectId 找 accountId」这两件最容易写错的事钉住——
 * 配错组合服务端只会回一句「没有项目权限」，界面上什么都看不出来。
 */
class ProjectSelectionPolicyTest {

    private val shower = UserProject(userId = 10000001, projectId = 4000, accountId = 10000002)
    private val laundry = UserProject(userId = 10000001, projectId = 3356, accountId = 10000101)
    private val all = listOf(laundry, shower)

    @Test
    fun `single project user gets no switcher entry`() {
        assertFalse(ProjectSelectionPolicy.hasMultiple(listOf(shower)))
        assertFalse(ProjectSelectionPolicy.hasMultiple(emptyList()))
        assertTrue(ProjectSelectionPolicy.hasMultiple(all))
    }

    @Test
    fun `default keeps the project already in the auth params`() {
        // 现场情况：linyu 一直用 4000 洗澡，登录/切换留下的 preferred 必须是 4000
        assertEquals(4000L, ProjectSelectionPolicy.defaultProject(all, 4000)?.projectId)
        // 反过来也一样，不能偏爱某一个写死的 id
        assertEquals(3356L, ProjectSelectionPolicy.defaultProject(all, 3356)?.projectId)
    }

    @Test
    fun `default falls back to the first entry when preferred is gone`() {
        assertEquals(3356L, ProjectSelectionPolicy.defaultProject(all, 9999)?.projectId)
        assertEquals(3356L, ProjectSelectionPolicy.defaultProject(all, null)?.projectId)
        assertNull(ProjectSelectionPolicy.defaultProject(emptyList(), 4000))
    }

    @Test
    fun `projectId resolves to its own paired accountId`() {
        assertEquals(10000002L, ProjectSelectionPolicy.resolve(all, 4000)?.accountId)
        assertEquals(10000101L, ProjectSelectionPolicy.resolve(all, 3356)?.accountId)
    }

    @Test
    fun `unknown projectId resolves to nothing instead of a wrong pair`() {
        // 返回 null 调用方就会放弃切换；绝不能退回当前 accountId
        assertNull(ProjectSelectionPolicy.resolve(all, 1234))
        assertNull(ProjectSelectionPolicy.resolve(all, null))
        assertNull(ProjectSelectionPolicy.resolve(emptyList(), 4000))
    }

    @Test
    fun `entries missing accountId are ignored`() {
        val broken = UserProject(userId = 10000001, projectId = 4000, accountId = null)
        assertNull(ProjectSelectionPolicy.resolve(listOf(broken), 4000))
        assertFalse(ProjectSelectionPolicy.hasMultiple(listOf(shower, broken)))
        // 只剩一条能用 → 默认还是它
        assertEquals(4000L, ProjectSelectionPolicy.defaultProject(listOf(shower, broken), 3356)?.projectId)
    }

    @Test
    fun `duplicated projectId keeps the first occurrence`() {
        val dup = listOf(shower, UserProject(projectId = 4000, accountId = 1111))
        assertEquals(10000002L, ProjectSelectionPolicy.resolve(dup, 4000)?.accountId)
    }
}
