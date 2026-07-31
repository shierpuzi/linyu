package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getVerificationCodeSafe
import com.hualala.linyu.api.loginSafe
import com.hualala.linyu.api.registerAndLoginSafe
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.utils.MD5Utils
import com.hualala.linyu.utils.PrefsHelper

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
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendSmsCode(phone: String): Result<Unit> {
        return try {
            val resp = NetworkModule.apiService.getVerificationCodeSafe(phone)
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
                    data.userAccount.accountId.toString(), data.userAccount.projectId.toString(), phone, data.userAccount.name)
                Result.success(data)
            } else {
                Result.failure(Exception(response.displayMessage ?: "登录失败"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}
