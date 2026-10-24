package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getAccountInfoSafe
import com.hualala.linyu.api.getUserProjectListSafe
import com.hualala.linyu.api.getVerificationCodeSafe
import com.hualala.linyu.api.loginSafe
import com.hualala.linyu.api.registerAndLoginSafe
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.model.UserAccount
import com.hualala.linyu.ui.LoginCodePolicy
import com.hualala.linyu.ui.ProjectSelectionPolicy
import com.hualala.linyu.utils.MD5Utils
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.SignUtils
import kotlinx.coroutines.withTimeoutOrNull

object AuthRepository {
    suspend fun login(phone: String, passwordRaw: String): Result<LoginData> {
        return try {
            val encryptedPassword = MD5Utils.encryptPassword(passwordRaw)
            val response = NetworkModule.apiService.loginSafe(
                telephone = phone,
                password = encryptedPassword
            )
            if (response.success && response.data != null) {
                val data = response.data
                NetworkModule.updateAuth(
                    loginCode = data.loginCode,
                    userId = data.userId.toString(),
                    accountId = data.userAccount.accountId.toString(),
                    projectId = data.userAccount.projectId.toString(),
                    telephone = phone
                )
                // 持久化
                PrefsHelper.saveAuth(
                    data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(),
                    data.userAccount.projectId.toString(), phone,
                    data.userAccount.name
                )
                resolveProject()
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发短信验证码。
     *
     * `typeId` 是验证码的用途，官方按这个值决定发什么模板、校验时怎么比对：
     * - `3` = 登录 / 注册（默认，不需要登录态）
     * - `5` = 更换手机号 —— **号要填「新」手机号**，不是当前绑定的那个。
     *   抓包实测：换号时 `telephone=新号&typeId=5`，旧号只出现在认证参数里
     *   （`telPhone`）。发到旧号用户根本收不到，会一直提示验证码错误。
     */
    suspend fun sendSmsCode(phone: String, typeId: Int = 3): Result<Unit> {
        return try {
            // secret 按手机号动态计算（官方算法），所有人可用
            val secret = SignUtils.smsSecret(phone)
            val resp = NetworkModule.apiService.getVerificationCodeSafe(phone, secret, typeId)
            if (resp.success) Result.success(Unit)
            else Result.failure(Exception(resp.displayMessage ?: "发送失败"))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun smsLogin(phone: String, smsCode: String): Result<LoginData> {
        return try {
            val response = NetworkModule.apiService.registerAndLoginSafe(phone, smsCode)
            if (response.success && response.data != null) {
                val data = response.data
                NetworkModule.updateAuth(data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(), data.userAccount.projectId.toString(), phone)
                PrefsHelper.saveAuth(data.loginCode, data.userId.toString(),
                    data.userAccount.accountId.toString(), data.userAccount.projectId.toString(), phone,
                    data.userAccount.name)
                resolveProject()
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 用现成的 `loginCode` 登录（不走向官方服务器要验证码，直接用抓包得到的会话令牌）。
     *
     * ## 为什么必须一起给 `loginCode` 和 `userId`
     *
     * 服务端把这两个当作**一对**来识别会话，只传 `loginCode` 时所有接口都回
     * `errorCode=8 未获取到用户信息，请重新登录`（2026-10-24 实测 6 个接口全如此）。
     * 而 `loginCode` 是 32 位随机 hex，反推不出 `userId`，所以只能让用户一起粘。
     *
     * ## 其余认证参数全部自动解析，用户不用填
     *
     * 拿到 `loginCode + userId` 之后：
     * 1. `GET /user/project/list` → 每个板块的 `accountId` + `projectId`
     * 2. `GET /account/info` → `telephone`、`name`、学号
     *
     * ## 与普通登录的差别
     *
     * 官方登录响应会一次性给齐 5 个认证参数，这里只能自己凑，所以要**先校验再落盘**：
     * 任何一步失败都不能写 Prefs，否则用户会进到一个"半登录"状态（能进主界面但每个
     * 请求都失败），比直接报错难查得多。
     *
     * @param loginCode 32 位十六进制会话令牌
     * @param userId 与它配对的那个 userId
     * @return 成功时返回解析出来的 [LoginData]（`userAccount` 是选定的板块）
     */
    suspend fun loginWithCode(loginCode: String, userId: String): Result<LoginData> {
        val code = LoginCodePolicy.normalize(loginCode)
        val uid = userId.trim()
        if (!LoginCodePolicy.isValidLoginCode(code)) {
            return Result.failure(Exception("loginCode 格式不对（应该是 32 位十六进制）"))
        }
        if (!LoginCodePolicy.isValidUserId(uid)) {
            return Result.failure(Exception("userId 格式不对（应该是一串数字）"))
        }

        return try {
            // ① 先只放 loginCode + userId 进网络层，其余留空。
            //    实测空白参数不影响（`accountId=&projectId=` 照样 success），所以不用特判。
            NetworkModule.updateAuth(
                loginCode = code,
                userId = uid,
                accountId = "",
                projectId = "",
                telephone = ""
            )

            // ② 拉板块列表 —— 这一步同时验证了 loginCode 是否还有效
            val projectsResp = NetworkModule.apiService.getUserProjectListSafe()
            if (!projectsResp.success) {
                return Result.failure(Exception(projectsResp.displayMessage ?: "loginCode 已失效，请重新获取"))
            }
            val projects = ProjectSelectionPolicy.usable(projectsResp.data.orEmpty())
            if (projects.isEmpty()) {
                return Result.failure(Exception("该账号未绑定任何板块"))
            }

            // ③ 定板块：沿用「上次选的 → 第一个」的规则（这里没有登录响应可参考）
            val preferred = PrefsHelper.currentProjectId.toLongOrNull()
            val chosen = ProjectSelectionPolicy.defaultProject(projects, preferred) ?: projects.first()
            val pid = chosen.projectId ?: return Result.failure(Exception("板块缺少 projectId"))
            val aid = chosen.accountId ?: return Result.failure(Exception("板块缺少 accountId"))

            // ④ 补 telephone —— 拦截器会给每个 GET 带上它，空着的话
            //    `/settlement/campus/userInfo` 会报「手机号不能为空」
            NetworkModule.updateAuth(code, uid, aid.toString(), pid.toString(), "")
            val info = NetworkModule.apiService.getAccountInfoSafe().data
            val phone = info?.telephone.orEmpty()

            // ⑤ 全部齐了才落盘。顺序同普通登录：内存 → Prefs → 板块增强
            NetworkModule.updateAuth(code, uid, aid.toString(), pid.toString(), phone)
            PrefsHelper.saveAuth(
                code, uid, aid.toString(), pid.toString(), phone,
                info?.name
            )
            PrefsHelper.saveCachedProjects(projects)
            PrefsHelper.currentProjectId = pid.toString()
            resolveProject()

            Result.success(
                LoginData(
                    userId = uid.toLongOrNull() ?: 0L,
                    loginCode = code,
                    userAccount = UserAccount(
                        accountId = aid,
                        name = info?.name.orEmpty(),
                        projectId = pid,
                        accountRealMoney = 0.0
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 登录后确定用哪个板块。
     *
     * 登录响应里只带**其中一个** `userAccount`，而一个手机号可能绑定多个独立项目。
     * 这里按「用户上次选的 → 登录响应里那个 → 列表第一个」定下当前板块
     * （见 [ProjectRepository.ensureSelection]）。
     *
     * ## 三条硬约束
     *
     * 1. **凭证先落盘**：调用点都在 `PrefsHelper.saveAuth` 之后，所以哪怕下面全失败，
     *    登录响应那一套参数也已经在内存和 Prefs 里了——洗浴功能立刻可用。
     * 2. **失败绝不能让登录失败**：拉不到列表就保持登录响应里的那一个。
     * 3. **不能拖慢登录**：只多一次 GET（列表拉回来后 `switchTo` 全走内存），
     *    再加一个超时兜底。没有它的话，网络半死不活时登录页会一直转圈。
     *
     * 同步做而不是异步，是为了避免一次可见的闪变：异步的话界面会先按登录响应的
     * 板块（4000）拉一遍钱包/账单，切完再清掉重拉，用户看到数字跳一下。
     */
    private suspend fun resolveProject() {
        try {
            withTimeoutOrNull(PROJECT_RESOLVE_TIMEOUT_MS) {
                ProjectRepository.ensureSelection()
            }
        } catch (_: Exception) {
            // 增强项失败，保持登录响应里的那一个
        }
    }

    /** 定板块最多等这么久。超时就按登录响应的板块先用，下次进主页 `loadProjects` 会再对齐 */
    private const val PROJECT_RESOLVE_TIMEOUT_MS = 4_000L
}
