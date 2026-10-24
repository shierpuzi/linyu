package com.quzhi.lite.ui

import com.quzhi.lite.model.UserProject

/**
 * 板块（项目）选择的判断逻辑——**纯函数，不碰网络也不碰 Prefs**，所以能直接单测。
 *
 * 背景见 `model/ProjectModels.kt`：一个账号可能绑定多个**独立项目**
 * （如 4000 洗浴 / 3356 洗衣饮水吹风），各有自己的 accountId、钱包、账单。
 * `loginCode` 全局一套，但 `projectId` 和 `accountId` **必须成对切换**——
 * 这个 object 就是「该拿哪一对」的唯一出处。
 */
object ProjectSelectionPolicy {

    /**
     * 能用的项目：`projectId` 和 `accountId` 都拿到了。
     *
     * 缺其中一个就切不过去，所以列表 / 入口一律按这份过滤后的算。
     */
    fun usable(projects: List<UserProject>): List<UserProject> =
        projects.filter { it.projectId != null && it.accountId != null }

    /**
     * 要不要给用户一个切换入口。
     *
     * **绑定 1 个项目的大多数用户不该看到这个入口**——多一个可点的东西就是多一份噪音，
     * 而且点开只有一行「当前板块」。
     */
    fun hasMultiple(projects: List<UserProject>): Boolean = usable(projects).size > 1

    /**
     * 默认选哪个板块。
     *
     * [preferredProjectId] 是登录响应（或上次切换）留下的那个：**它还在列表里就继续用它**，
     * 认证参数一个字段都不动，老用户升级后完全无感。只有它不在列表里
     * （换绑过 / 服务端调整过）才退回第一个。
     */
    fun defaultProject(projects: List<UserProject>, preferredProjectId: Long?): UserProject? {
        val list = usable(projects)
        if (list.isEmpty()) return null
        return list.firstOrNull { it.projectId == preferredProjectId } ?: list.first()
    }

    /**
     * 按 projectId 找出**成对的那一条**（accountId 跟着它走）。
     *
     * 找不到返回 null——调用方必须放弃这次切换。**绝不能**拿当前板块的 accountId
     * 去配新的 projectId，服务端会回「没有项目权限，请确认身份」。
     */
    fun resolve(projects: List<UserProject>, projectId: Long?): UserProject? {
        if (projectId == null) return null
        return usable(projects).firstOrNull { it.projectId == projectId }
    }

    /**
     * 板块在界面上的名字。
     *
     * 项目的真名来自 `/project/info/triple`，而那个接口**跟着当前板块走**，
     * 一次只能问出一个名字。所以没切过去的板块只能兜底。
     *
     * 兜底顺序：
     * 1. 记住的真名（切过一次就有）
     * 2. [fallbackName]（调用方传业务名，如「洗浴」「洗衣饮水吹风」）
     * 3. `项目 {id}`
     *
     * ⚠️ 有兜底就**不要**显示裸 id：正式发布时用户看到「项目 3356」会以为是 bug。
     */
    fun label(
        projectId: Long?,
        names: Map<Long, String>,
        fallbackName: String? = null
    ): String {
        if (projectId == null) return "未知板块"
        names[projectId].takeUnless { it.isNullOrBlank() }?.let { return it }
        fallbackName?.takeUnless { it.isBlank() }?.let { return it }
        return "项目 $projectId"
    }
}
