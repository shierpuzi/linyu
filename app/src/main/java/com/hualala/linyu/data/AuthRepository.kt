package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getVerificationCodeSafe
import com.hualala.linyu.api.loginSafe
import com.hualala.linyu.api.registerAndLoginSafe
import com.hualala.linyu.model.LoginData
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
