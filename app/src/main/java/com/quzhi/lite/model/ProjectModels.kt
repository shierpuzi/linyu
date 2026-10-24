package com.quzhi.lite.model

import com.google.gson.annotations.SerializedName

/**
 * 账号绑定的一个「趣智校园项目」。
 *
 * ## 一个学校可能是**多个独立项目**
 *
 * 实测某校：同一个手机号（同 userId）绑了两条完全独立的账户——
 *
 * | 板块 | projectId | accountId | 业务 |
 * |---|---|---|---|
 * | 洗浴 | 4000 | （各自独立） | 热水表，按量计费 |
 * | 洗衣饮水吹风 | 3356 | （各自独立） | 洗烘 / 饮水 / 吹风机，按套餐计费 |
 *
 * 注意同一账号在两个项目下的 `accountId` **不同**（服务端为每条绑定关系分配一个），
 * 具体值请运行时从 `/user/project/list` 读，不要硬编码。
 *
 * 两个**钱包独立、账单独立、设备独立**，界面上就是官方 App 首页那两个板块。
 *
 * ## ⚠️ projectId 和 accountId 必须成对使用
 *
 * `loginCode` 全局只有一套（一个 userId 一个），但换成别的 projectId 时必须
 * **同时**换 accountId，否则接口报「没有项目权限，请确认身份」。
 * 别只换 projectId——这是本功能最容易写错的地方。
 */
data class UserProject(
    val userId: Long? = null,
    val projectId: Long? = null,
    val accountId: Long? = null
)

/**
 * 项目下的一个业务模块（`GET /project/module/list`）。
 *
 * 官方 App 首页那排图标就是按这个渲染的：`isUse == 1` 的才显示。
 * 实测 3356 有 8 个模块，启用的只有「洗烘 / 饮水 / 吹风机」三个，
 * 其余（公寓洗澡 / 澡堂洗澡 / 无线澡堂 / 充电 / 远传水电表）都是 `isUse == 0`。
 *
 * 用它可以判断「这个学校有没有洗衣机业务」，比硬编码 bigTypeId 更靠谱。
 */
data class ProjectModule(
    val moduleId: Int? = null,
    val projectId: Long? = null,
    val moduleName: String? = null,
    /** 1 = 启用；0 或其它 = 不启用 */
    val isUse: Int? = null,
    val openUseParagraph: Int? = null,
    val restraintType: Int? = null,
    val restraintContext: String? = null,
    /** 模块对应的大类 id 列表，服务端目前不填（实测为 null），别依赖它 */
    val bigTypeIds: String? = null,
    val moduleKey: String? = null
) {
    val enabled: Boolean get() = isUse == 1
}

/**
 * 项目（学校）信息 `GET /project/info/triple` 的模型**不在这里**——
 * 单项目时代就已经有 `model/LoginModels.kt` 的 `ProjectInfo(projectId, projectName)`，
 * 复用那一个即可（本项目只需要学校名，用不到 tenantId 之类）。
 *
 * ⚠️ 曾经在这个文件里重复定义过一次，编译直接报 `Redeclaration: ProjectInfo`。
 * 加模型前先 grep 一遍，别凭印象新建。
 */
