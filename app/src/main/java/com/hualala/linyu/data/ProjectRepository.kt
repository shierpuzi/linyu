package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getUserProjectListSafe
import com.hualala.linyu.model.UserProject
import com.hualala.linyu.ui.ProjectSelectionPolicy
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper

/**
 * 多板块（多项目）的切换。
 *
 * ## 为什么需要独立一层
 *
 * 认证参数原来只有一套，写死在 [NetworkModule] 和 [PrefsHelper] 里，登录后就不动了。
 * 而一个手机号可以绑定**多个独立项目**（实测 4000 洗浴 / 3356 洗衣饮水吹风），
 * 各有自己的 accountId、钱包、账单、设备。
 *
 * `loginCode` 和 `userId` 是全局的（一个账号一套），但 `projectId` + `accountId`
 * **必须成对切换**——只换 projectId 服务端会回「没有项目权限，请确认身份」，
 * 而且这种错误在界面上只表现为"数据是空的"，非常难查。所以配对逻辑收口在
 * [ProjectSelectionPolicy] 里，这里只负责发请求和落盘。
 */
object ProjectRepository {

    /**
     * 拉全账号绑定的项目并缓存。
     *
     * 失败时**退回上次的缓存**（不为空就返回它）：板块列表不该因为一次断网就消失，
     * 用户还能继续在原来的板块里用。空缓存 + 失败才返回错误。
     */
    suspend fun loadProjects(): Result<List<UserProject>> {
        val cached = PrefsHelper.cachedProjects()
        return try {
            val resp = NetworkModule.apiService.getUserProjectListSafe()
            val list = resp.data?.let { ProjectSelectionPolicy.usable(it) }
            if (resp.success && !list.isNullOrEmpty()) {
                PrefsHelper.saveCachedProjects(list)
                Result.success(list)
            } else if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(Exception(resp.displayMessage ?: "获取板块列表失败"))
            }
        } catch (e: Exception) {
            if (cached.isNotEmpty()) Result.success(cached) else Result.failure(e)
        }
    }

    /** 缓存里的板块列表（不发网络）。界面冷启动先用它渲染，避免闪一下单板块 */
    fun cachedProjects(): List<UserProject> = ProjectSelectionPolicy.usable(PrefsHelper.cachedProjects())

    /** 当前真正在用的 projectId（认证参数里那个，**不是**用户想要的那个） */
    fun currentProjectId(): Long? = PrefsHelper.projectId.toLongOrNull()

    /**
     * 切到指定板块。
     *
     * 三件事必须一起做，顺序也有讲究：
     * 1. 换认证参数（[NetworkModule.updateAuth]）——之后所有请求才走新板块
     * 2. 落盘（accountId/projectId + 用户选择）
     * 3. 清掉按项目维度缓存的数据，**否则切过去先显示上一个板块的账单/余额**
     *
     * ⚠️ 调用方必须先确认**没有进行中的订单**。在途订单是用旧 accountId 开的，
     * 切走之后关阀/结算都会失败，而这里清缓存时**特意保留了**那些数据（见
     * [PrefsHelper.clearProjectScopedCache]），说明它不负责善后。
     *
     * @return 切换后的项目；找不到配对的 accountId 或请求失败时返回 failure
     */
    suspend fun switchTo(projectId: Long): Result<UserProject> {
        val targets = cachedProjects().ifEmpty {
            loadProjects().getOrElse { return Result.failure(it) }
        }
        // 找不到就**放弃**，绝不拿当前板块的 accountId 去配新的 projectId
        val target = ProjectSelectionPolicy.resolve(targets, projectId)
            ?: return Result.failure(Exception("账号未绑定该板块（projectId=$projectId）"))

        val accountId = target.accountId!!
        val oldProjectId = PrefsHelper.projectId
        val same = accountId.toString() == PrefsHelper.accountId && projectId.toString() == oldProjectId

        NetworkModule.updateAuth(
            loginCode = PrefsHelper.loginCode,
            userId = PrefsHelper.userId,
            accountId = accountId.toString(),
            projectId = projectId.toString(),
            telephone = PrefsHelper.telephone
        )
        PrefsHelper.accountId = accountId.toString()
        PrefsHelper.projectId = projectId.toString()
        PrefsHelper.currentProjectId = projectId.toString()

        if (!same) {
            // 换板块后才清：同一个板块重复点不算切换，不该把当前页面数据抹掉
            PrefsHelper.clearProjectScopedCache()
            DeviceInfoCache.clear()
            AppLogger.i("切换板块：projectId $oldProjectId -> $projectId（accountId=$accountId）")
        }
        return Result.success(target)
    }

    /**
     * 登录后（或冷启动时）确定用哪个板块。
     *
     * 优先级：**用户上次选的那个** → 登录响应里那个 → 列表第一个。
     *
     * 老用户升级后 [PrefsHelper.currentProjectId] 是空的，于是直接沿用登录响应里的
     * `userAccount.projectId`（他原来一直在用的那个）——不产生任何可见变化。
     * 只有那个值不在绑定列表里（换绑过 / 服务端调整过）才退到第一个。
     */
    suspend fun ensureSelection(): Result<UserProject?> {
        if (PrefsHelper.loginCode.isEmpty()) return Result.success(null)
        val projects = loadProjects().getOrElse { return Result.failure(it) }
        if (projects.isEmpty()) return Result.success(null)

        val preferredId = PrefsHelper.currentProjectId.toLongOrNull()
            ?: PrefsHelper.projectId.toLongOrNull()
        val chosen = ProjectSelectionPolicy.defaultProject(projects, preferredId) ?: return Result.success(null)
        return switchTo(chosen.projectId!!)
    }

    /** 已记住的 `projectId → 项目名`。名字由 `MainViewModel.loadSchoolName` 逐个攒 */
    fun projectNames(): Map<Long, String> = PrefsHelper.projectNames()

    /**
     * 临时借用另一个板块的认证参数跑一段只读逻辑，跑完**必定还原**。
     *
     * ## 为什么不能用 [switchTo]
     *
     * [switchTo] 会清掉项目维度缓存、改用户选择、并让界面复位。而「消费合并」
     * 只是想读一眼别的板块的账单，**不该产生任何可见副作用**。用 switchTo 的话，
     * 用户会看到余额闪一下、账单清空再填回来。
     *
     * ## 安全边界（这段逻辑必须守住）
     *
     * - **只读**：block 里只能调查询接口。本方法不阻止你以下单，靠调用方自觉
     * - **不落盘**：不改 `PrefsHelper.accountId/projectId/currentProjectId`
     * - **不清缓存**：不动 `clearProjectScopedCache`
     * - **finally 还原**：即使 block 抛异常，认证参数也会恢复成进来时的样子
     * - **可重入**：还原用的是"进来时快照"，不是"另一个板块"，嵌套调用也安全
     *
     * ## 并发风险（已知、可接受）
     *
     * 所有请求共用 [NetworkModule] 的全局认证状态，所以本方法的执行期间，
     * **其他协程发出的请求会带错参数**。这是这套架构的固有缺陷，不是本方法引入的。
     * 缓解：调用方必须保证同一时刻只有一个合并任务（`MainViewModel` 用 `isMerging` 挡住），
     * 且避免在合并期间让用户触发操作。合并只跑几秒、只发 GET，风险可控。
     *
     * @param projectId 目标板块
     * @return block 的返回值；找不到配对 accountId 时返回 null（不抛异常）
     */
    suspend fun <T> withBoardContext(projectId: Long, block: suspend () -> T): T? {
        val targets = cachedProjects()
        val target = ProjectSelectionPolicy.resolve(targets, projectId) ?: return null
        val accountId = target.accountId ?: return null

        // 进来时的快照 —— 还原用这个，不是"另一个板块"
        val snapLogin = PrefsHelper.loginCode
        val snapUser = PrefsHelper.userId
        val snapAccount = PrefsHelper.accountId
        val snapProject = PrefsHelper.projectId
        val snapPhone = PrefsHelper.telephone

        return try {
            NetworkModule.updateAuth(
                loginCode = snapLogin,
                userId = snapUser,
                accountId = accountId.toString(),
                projectId = projectId.toString(),
                telephone = snapPhone
            )
            block()
        } catch (e: Exception) {
            AppLogger.w("借板块 $projectId 做只读操作失败：${e.message}")
            null
        } finally {
            // ⚠️ 必须 in finally：block 抛异常时若不还原，App 会一直用别的板块的身份请求，
            //    用户看到的是「余额突然变了」「设备列表空了」，而且下次重启前都不会自愈
            NetworkModule.updateAuth(
                loginCode = snapLogin,
                userId = snapUser,
                accountId = snapAccount,
                projectId = snapProject,
                telephone = snapPhone
            )
        }
    }
}
