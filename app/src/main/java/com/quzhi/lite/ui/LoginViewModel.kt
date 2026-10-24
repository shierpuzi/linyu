package com.quzhi.lite.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quzhi.lite.data.AuthRepository
import com.quzhi.lite.model.LoginData
import com.quzhi.lite.utils.PrefsHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    var phone by mutableStateOf("")
    var password by mutableStateOf("")
    var smsCode by mutableStateOf("")

    /** loginCode 登录用的两个框。和手机号/验证码那套完全独立 */
    var loginCode by mutableStateOf("")
    var codeUserId by mutableStateOf("")
    /**
     * 默认走手机号验证码。
     *
     * 验证码登录**没注册过会自动注册**，新用户不用先去别处开账号；而密码登录
     * 要求用户早就设过密码——对第一次用的人来说是死路。所以默认给验证码。
     */
    var isSmsMode by mutableStateOf(true)

    /**
     * 当前是不是 loginCode 登录模式。
     *
     * 三种模式互斥，但 `isSmsMode` 已经表达了「密码 / 验证码」二选一，所以这里
     * 只额外加一个布尔量，不把它仨重构成 enum——那会连带改动登录页所有分支。
     */
    var isCodeMode by mutableStateOf(false)

    var isLoading by mutableStateOf(false)
    var isSendingCode by mutableStateOf(false)
    var countdown by mutableStateOf(0)
    var loginResult by mutableStateOf<Result<LoginData>?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var smsSent by mutableStateOf(false)

    /** 独立的倒计时协程，和发送请求解耦，所以能点击即开始 */
    private var countdownJob: Job? = null

    fun login() {
        if (isCodeMode) { codeLogin(); return }
        if (phone.isBlank()) return
        if (phone.length != 11) { errorMessage = "请输入 11 位手机号"; return }
        if (isSmsMode) {
            if (smsCode.isBlank()) return
            smsLogin()
        } else {
            if (password.isBlank()) return
            passwordLogin()
        }
    }

    /**
     * loginCode 登录。
     *
     * 用户常常直接从抓包 URL 里复制，所以先过一遍 [LoginCodePolicy.extractLoginCode]
     * ——整条 URL 粘进来也能用，不用要求他手抠那 32 位。
     */
    private fun codeLogin() {
        val code = LoginCodePolicy.extractLoginCode(loginCode)
        val uid = LoginCodePolicy.extractUserId(codeUserId)
        if (code.isEmpty()) { errorMessage = "没找到 32 位 loginCode，请检查粘贴内容"; return }
        if (uid.isEmpty()) { errorMessage = "没找到 userId，请检查粘贴内容"; return }

        viewModelScope.launch {
            isLoading = true; errorMessage = null
            try {
                val result = AuthRepository.loginWithCode(code, uid)
                loginResult = result
                if (result.isSuccess) {
                    // ⚠️ 必须把解析出来的手机号回填到 [phone]：
                    // 登录页成功后是 `onLoginSuccess(viewModel.phone)`，MainActivity 拿它写
                    // `mainViewModel.phone`。这里留空的话会把 beginSession() 刚从 Prefs
                    // 读出来的正确号码又覆盖成空串（小组件/通知都按它找人）。
                    phone = PrefsHelper.telephone
                    // 会话令牌用完即清，和密码/验证码同一条原则
                    loginCode = ""
                    codeUserId = ""
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "登录失败"
                    errorMessage = when {
                        msg.contains("Unable to resolve host", ignoreCase = true) ||
                        msg.contains("Network is unreachable", ignoreCase = true) -> "网络连接失败，请检查网络设置"
                        msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
                            "连接超时，请检查网络后重试"
                        else -> msg
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun passwordLogin() {
        viewModelScope.launch {
            isLoading = true; errorMessage = null
            try {
                val result = AuthRepository.login(phone, password)
                loginResult = result
                if (result.isFailure) {
                    val msg = result.exceptionOrNull()?.message ?: "登录失败"
                    errorMessage = when {
                        msg.contains("Unable to resolve host", ignoreCase = true) ||
                        msg.contains("No address associated", ignoreCase = true) ||
                        msg.contains("Network is unreachable", ignoreCase = true) -> "网络连接失败，请检查网络设置"
                        msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
                            "连接超时，请检查网络后重试"
                        else -> msg
                    }
                }
            } finally {
                password = LoginSecretPolicy.clearPassword(password)
                isLoading = false
            }
        }
    }

    private fun smsLogin() {
        viewModelScope.launch {
            isLoading = true; errorMessage = null
            try {
                val result = AuthRepository.smsLogin(phone, smsCode)
                loginResult = result
                if (result.isFailure) {
                    errorMessage = result.exceptionOrNull()?.message ?: "登录失败"
                }
            } finally {
                smsCode = LoginSecretPolicy.clearSmsCode(smsCode)
                isLoading = false
            }
        }
    }

    fun sendSmsCode() {
        if (phone.isBlank() || isSendingCode || countdown > 0) return
        if (phone.length != 11) { errorMessage = "请输入 11 位手机号"; return }

        // 倒计时**先跑起来**，不等网络往返。
        // 发送请求本身要几百毫秒到一两秒，等它回来再置 60 的话，
        // 按钮会先僵在「发送」上不动，看起来像点了没反应。
        // 三条失败路径（手机号不合法、网络异常、服务端拒绝）都会撤销倒计时，不会把用户困在 60 秒里。
        startCountdown()

        viewModelScope.launch {
            isSendingCode = true; errorMessage = null
            val result = AuthRepository.sendSmsCode(phone)
            if (result.isSuccess) {
                smsSent = true
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "验证码发送失败"
                // 没发出去就把倒计时收回，否则要干等 60 秒才能重试
                cancelCountdown()
            }
            isSendingCode = false
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdown = 60
        countdownJob = viewModelScope.launch {
            while (countdown > 0) { delay(1000); countdown-- }
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel(); countdownJob = null; countdown = 0
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    fun switchMode() {
        isSmsMode = !isSmsMode
        errorMessage = null
        password = ""
        smsCode = ""
    }

    /**
     * 在「账号登录」和「loginCode 登录」之间切。
     *
     * 切回来时把 loginCode/userId 清掉：它俩是**会话令牌**，比密码还敏感，
     * 留在内存里没必要（见 CHANGELOG「登录请求结束后立即清除密码与验证码」同一原则）。
     */
    fun switchCodeMode() {
        isCodeMode = !isCodeMode
        errorMessage = null
        loginCode = ""
        codeUserId = ""
        password = ""
        smsCode = ""
    }

    fun resetResult() { loginResult = null }
}
