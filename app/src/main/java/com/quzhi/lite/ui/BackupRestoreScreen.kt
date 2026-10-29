package com.quzhi.lite.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.api.NetworkModule
import com.quzhi.lite.data.AuthRepository
import com.quzhi.lite.data.BackupCodec
import com.quzhi.lite.data.BackupFileIo
import com.quzhi.lite.data.BackupPayload
import com.quzhi.lite.data.BackupService
import com.quzhi.lite.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页的「从备份包恢复登录」入口。
 *
 * ## 和设置页那张卡片的关系
 *
 * 设置页（`BackupCards.kt`）是「已登录用户主动备份 / 恢复」；这里是
 * 「换机、重装之后还站在登录页，手上只有一个备份文件」。
 * **解密文案和鉴权函数是共用的**（`backupDecodeErrorMessage` / `AuthRepository.loginWithCode`），
 * 所以同一个坏文件在两个页面说同一句话。
 *
 * 但**恢复后的收尾不能共用**：
 * - 设置页在登录状态里操作，失败要说「当前登录状态保持不变」
 * - 这里本来就没登录，那句完全不成立；成功则要触发真正的登录跳转
 *
 * 所以流程各写一份、文案各按场景润色。硬抽成一个公共函数反而要塞一堆
 * "你是不是已登录"的分支，读起来更绕。
 *
 * ## 为什么先鉴权再写登录态（用户点名的要求）
 *
 * 用户原话：「有 loginCode + userId 的话，先鉴权看看能不能登录，别一登录就显示登录失效」。
 *
 * 如果直接 `PrefsHelper.saveAuth(...)` 再让主页去试：
 * MainActivity 立刻切进主页 → 主页第一个请求发现 loginCode 早失效了 → 弹「账号在别处登录」
 * 把用户踢回登录页。他刚点了「恢复成功」，转头就被踢出去，而且收藏恢复的提示也看不到了。
 *
 * 所以顺序是：**先拿这份凭证去问服务端，服务端点头才承认它是登录态**。
 * `AuthRepository.loginWithCode` 正好是这条路：它拉一次板块列表同时验证 loginCode，
 * 全部成功才落盘（见那个函数里的注释），失败则 Prefs 一行没动。
 */
@Composable
internal fun BackupRestoreEntry(onRestored: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 已读出、还没解密的文件内容。非 null 就说明要弹密码框 */
    var pendingJson by remember { mutableStateOf<String?>(null) }
    /** 密码错要留在框里让他重输，不能关掉框让他重新选一遍文件 */
    var passwordError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 恢复结论（成功提示 / 各种失败原因）。null = 没话要说 */
    var notice by remember { mutableStateOf<String?>(null) }
    /** 结论是"出问题了"还是仅仅是"说明情况"。决定配色 */
    var noticeIsProblem by remember { mutableStateOf(false) }

    fun show(message: String, isProblem: Boolean) {
        notice = message
        noticeIsProblem = isProblem
    }

    /**
     * 解密 → 写收藏 → 鉴权。两个入口（明文包、密码框提交）都走它，
     * 免得其中一条忘了鉴权——那种 bug 只在加密包里复现，极难查。
     */
    fun runRestore(json: String, password: String?) {
        busy = true
        notice = null
        scope.launch {
            val decoded = decodeBackupFile(json, password)
            val payload = decoded.getOrNull()
            if (payload == null) {
                busy = false
                val error = (decoded.exceptionOrNull() as? BackupCodec.DecodeException)?.error
                val message = if (error != null) backupDecodeErrorMessage(error)
                else "备份包解析失败：${decoded.exceptionOrNull()?.message ?: "未知原因"}"
                // 密码错：留在密码框里重输一次就够
                if (error == BackupCodec.DecodeError.WrongPassword && password != null) {
                    passwordError = message
                } else {
                    pendingJson = null
                    show(message, true)
                }
                return@launch
            }
            passwordError = null
            pendingJson = null
            when (val outcome = restoreAndVerify(payload)) {
                is RestoreOutcome.LoggedIn -> {
                    busy = false
                    notice = null
                    // 走和「loginCode 登录成功」完全相同的出口：
                    // LoginScreen 的那个回调最终是 MainActivity 的 beginSession + isLoggedIn = true
                    onRestored(outcome.phone)
                }
                is RestoreOutcome.StayHere -> {
                    busy = false
                    show(outcome.message, outcome.isProblem)
                }
            }
        }
    }

    // SAF「打开文档」：由用户在系统界面里挑文件，本 App 只拿到那一个 Uri 的读权限。
    // 全程不需要任何存储权限（也不用读 MediaStore）。
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // uri == null：用户在选择器里按了返回。静默结束——
        // 他明显是改主意了，弹一句「恢复失败」只会让人以为出故障了
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        notice = null
        scope.launch {
            val read = BackupFileIo.readFromUri(context, uri)
            busy = false
            val text = read.getOrNull()
            if (text == null) {
                show("没能读取这个文件：${read.exceptionOrNull()?.message ?: "未知原因"}", true)
                return@launch
            }
            // 先只解外壳（peek 不解密）：要不要密码框就看这一步。
            // 直接 decode 的话加密包会以「缺少密码」失败，用户还得再选一次文件。
            val encrypted = try {
                BackupCodec.peek(text).encrypted
            } catch (e: BackupCodec.DecodeException) {
                show(backupDecodeErrorMessage(e.error), true)
                return@launch
            }
            if (encrypted) {
                passwordError = null
                pendingJson = text
            } else {
                runRestore(text, null)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextButton(
            onClick = { if (!busy) openLauncher.launch(arrayOf("application/json")) },
            enabled = !busy,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF1A73E8)
                )
                Spacer(Modifier.width(8.dp))
                // 解密要跑 PBKDF2 21 万次（100~300ms），鉴权还要一次网络往返，
                // 全程不给反馈会像是点了没反应。文案用「处理」而不是「读取」：
                // 这个忙碌态同时覆盖 读文件 / 解密 / 鉴权 三段
                Text("正在处理备份包…", fontSize = 13.sp, color = Color(0xFF1A73E8))
            } else {
                Text("从备份包恢复登录 →", fontSize = 13.sp, color = Color(0xFF1A73E8))
            }
        }

        notice?.let { message ->
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                // 说明性结论（比如"这个包只有收藏"）不用红色吓人，那是正常情况；
                // 真出问题（坏文件、凭证失效）才用红
                color = if (noticeIsProblem) Color(0xFFFFEBEE) else Color(0xFFE8F0FE)
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = if (noticeIsProblem) Color(0xFFC62828) else Color(0xFF1A4B8F),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    val json = pendingJson
    if (json != null) {
        BackupPasswordDialog(
            error = passwordError,
            busy = busy,
            onDismiss = { if (!busy) { pendingJson = null; passwordError = null } },
            onConfirm = { password -> runRestore(json, password) }
        )
    }
}

/** 恢复的两种结局，决定调用方是「跳主页」还是「留在登录页说句话」 */
private sealed interface RestoreOutcome {
    /** 鉴权通过，凭证已落盘。带上手机号给登录成功流程用 */
    data class LoggedIn(val phone: String) : RestoreOutcome
    /** 没有可用凭证 / 鉴权失败：留在登录页，把原因说清楚 */
    data class StayHere(val message: String, val isProblem: Boolean) : RestoreOutcome
}

/**
 * 写收藏 + 鉴权。**顺序不能换**，理由见文件头。
 */
private suspend fun restoreAndVerify(payload: BackupPayload): RestoreOutcome {
    // ① 只恢复收藏。这里不传 writeCredentials：它默认 false，
    //    正是「凭证要等鉴权通过才算数」的意思（见 BackupService.restore 的注释）。
    //    收藏是纯本地数据，成功了就该立刻写进去——哪怕后面鉴权失败，
    //    用户至少没白折腾这一趟。
    val result = BackupService.restore(payload)
    val favoritesNote = when {
        result.favoritesRestored > 0 -> "已恢复 ${result.favoritesRestored} 个收藏"
        payload.hasFavorites -> "收藏都已在列表里，没有新增"
        else -> "备份包里没有收藏"
    }

    val credentials = result.credentials
    if (credentials == null) {
        // 包里没凭证：收藏已经写回，剩下的事只能靠他自己登录。
        // 这里**必须**说清楚"收藏已恢复"，否则用户会以为整个包都没用上。
        return RestoreOutcome.StayHere(
            "$favoritesNote；这个备份包没有 loginCode 和 userId 信息，请用手机号或 loginCode 登录",
            isProblem = false
        )
    }
    if (!credentials.isComplete) {
        // 有凭证字段但缺项（accountId / projectId 少一个都登不上）。
        // 和"完全没凭证"分开说，否则用户会以为自己压根没备份过凭证。
        return RestoreOutcome.StayHere(
            "$favoritesNote；备份包里的登录凭证不完整（缺 loginCode/userId/accountId/projectId 里的某一项），" +
                "请用手机号或 loginCode 登录",
            isProblem = true
        )
    }

    // ② 先鉴权，再决定要不要认这个登录态。
    //    loginWithCode 内部先 GET /user/project/list 验 loginCode，再补齐
    //    accountId / projectId / telephone，**全部成功才写 Prefs**。
    val verified = AuthRepository.loginWithCode(credentials.loginCode, credentials.userId)
    if (verified.isFailure) {
        // ⚠️ 必须还原：loginWithCode 第一步就把备份里的凭证塞进了内存中的认证参数
        //    （那时还没有 accountId / projectId）。不还原的话，这个刚被拒绝的
        //    loginCode 会一直留在网络层，用户接着用手输方式登录时，
        //    请求里带的还是这份没收干净的参数，报错信息会完全对不上。
        NetworkModule.restoreFromPrefs()

        val reason = verified.exceptionOrNull()?.message.orEmpty()
        return if (looksLikeNetworkFailure(reason)) {
            // 网络问题 ≠ 凭证失效。说成"失效"会把人赶去重新抓包，白折腾。
            RestoreOutcome.StayHere(
                "$favoritesNote；但网络不通，没能验证备份里的登录凭证（凭证没有写入）。" +
                    "连上网后再点一次就行",
                isProblem = true
            )
        } else {
            RestoreOutcome.StayHere(
                "$favoritesNote；备份里的登录凭证已失效（可能已在别处登录过），凭证没有写入，" +
                    "请用手机号或 loginCode 重新登录",
                isProblem = true
            )
        }
    }

    // ③ 鉴权通过，凭证已经被 loginWithCode 写进 Prefs 和网络层了。
    //    **不再**调 BackupService.saveCredentials：那会用备份包里的旧 projectId
    //    覆盖刚解析出来的新值，和 currentProjectId 对不上，切板块时串号。
    //    phone 也从 Prefs 读——loginWithCode 填的是服务端给的真实号码。
    return RestoreOutcome.LoggedIn(PrefsHelper.telephone)
}

/**
 * loginWithCode 的失败是「网络不通」还是「凭证失效」。
 *
 * 判据照抄 `LoginViewModel.codeLogin` 里那套字符串匹配（同一批机型/网络环境下
 * 报出来的串就这几种）。没有把它抽成公共函数，是因为那两处分别在两个文件、
 * 各自只做一次判断；为 8 行代码建一个 utils 反而更难找。
 */
private fun looksLikeNetworkFailure(message: String): Boolean {
    if (message.isBlank()) return false
    return listOf(
        "Unable to resolve host",
        "No address associated",
        "Network is unreachable",
        "Failed to connect",
        "timeout",
        "timed out",
        "ConnectException"
    ).any { message.contains(it, ignoreCase = true) }
}

/** 解密。PBKDF2 21 万次要 100~300ms，放主线程会让按钮按下后明显卡一下 */
private suspend fun decodeBackupFile(json: String, password: String?): Result<BackupPayload> =
    withContext(Dispatchers.Default) {
        try {
            Result.success(BackupCodec.decode(json, password))
        } catch (e: Exception) {
            // DecodeException 也在这里被捕获；调用方靠异常类型区分具体原因
            Result.failure(e)
        }
    }

/**
 * 加密备份包的密码输入框。
 *
 * 没有复用设置页那个：`BackupCards.kt` 里的 `RestorePasswordDialog` 是 private，
 * 而那个文件不在本次写入范围内（提成 internal 要去改别人的文件）。
 * 交互细节按同一套来：**密码错只换行内红字、不关框、不让人重新选文件**。
 */
@Composable
private fun BackupPasswordDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(20.dp),
        // 登录页卡片是固定白底，弹窗也跟白底，别取主题色（深色模式下会和登录页割裂）
        containerColor = Color.White,
        title = {
            Text(
                "备份包已加密",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A1A)
            )
        },
        text = {
            Column {
                Text(
                    "请输入备份时设置的那个密码。",
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1A73E8),
                        unfocusedBorderColor = Color(0xFFBFC7D5),
                        focusedLabelColor = Color(0xFF1A73E8),
                        unfocusedLabelColor = Color(0xFF6B7280),
                        focusedTextColor = Color(0xFF1A1A1A),
                        unfocusedTextColor = Color(0xFF1A1A1A),
                        cursorColor = Color(0xFF1A73E8)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFC62828), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "忘记密码的话这份备份就打不开了，只能重新登录后再备份一次。",
                    fontSize = 11.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !busy && password.isNotEmpty()
            ) {
                Text(
                    if (busy) "正在解锁…" else "解锁并恢复",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A73E8)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消", color = Color(0xFF6B7280))
            }
        }
    )
}
