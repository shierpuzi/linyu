package com.quzhi.lite.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.api.NetworkModule
import com.quzhi.lite.data.AuthRepository
import com.quzhi.lite.data.BackupCodec
import com.quzhi.lite.data.BackupCodec.DecodeError
import com.quzhi.lite.data.BackupFileIo
import com.quzhi.lite.data.BackupFormat
import com.quzhi.lite.data.BackupPayload
import com.quzhi.lite.data.BackupService
import com.quzhi.lite.data.canLogoutVoluntarily
import com.quzhi.lite.ui.theme.AppColors
import com.quzhi.lite.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的「备份 / 恢复」卡片。
 *
 * ## 为什么用 SAF 而不是自己找路径写文件
 *
 * 用户明确担心过「要不要给存储权限」。系统文件选择器（SAF）**一个权限都不要**：
 * 用户在系统界面里点定位置，系统就把那一个 Uri 的读写权授给本 App，用完即止。
 * 所以整张卡片里没有 `RequestPermission`、Manifest 里也没有任何存储权限。
 *
 * ## 两条流程，加密规则不同
 *
 * | 备份内容 | 密码 | 谁决定的 |
 * |---|---|---|
 * | 只有收藏 | 默认不要，用户可自己勾「加密」 | 纯本地数据，泄漏不涉及账号 |
 * | 含登录凭证 | **强制**要 | loginCode 等价会话令牌，明文落盘是事故。数据层也会拒收 |
 *
 * UI 这里跟着强制，是为了**当场**让用户看到「勾了凭证就得设密码」，
 * 而不是等到保存时被数据层拒绝、弹一句看不懂的异常。
 *
 * ## 恢复凭证的顺序（这张卡片最容易做错的地方）
 *
 * ```
 * 选文件 → 读文本 → peek 看是否加密 → decode（可能要密码）
 *   → BackupService.restore(payload)      ← 只写收藏，凭证默认不写
 *   → AuthRepository.loginWithCode(...)   ← 先鉴权
 *       成功 → 才让登录态成立（该函数内部落盘）
 *       失败 → 什么都不写，只提示「凭证已失效」
 * ```
 *
 * 反过来做（先 saveAuth 再验证）就会出现用户点名要避免的体验：
 * 进主页一秒后被踢回登录页，收藏还没恢复成。
 *
 * @param viewModel 「我的」页共用实例。恢复成功后要借它走一次正常的会话重置
 *   （[MainViewModel.beginSession]），否则界面上的手机号/板块还是上一个账号的。
 */
@Composable
internal fun BackupCard(viewModel: MainViewModel? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 备份选项对话框 */
    var showOptions by remember { mutableStateOf(false) }
    /** 已确定要写的内容，等用户在系统文件选择器里选好位置 */
    var prepared by remember { mutableStateOf<PreparedBackup?>(null) }
    /** 加密包的内容，等用户输密码。非 null 就说明正在弹密码框 */
    var pendingJson by remember { mutableStateOf<String?>(null) }
    /** 密码框里的行内错误（密码错要留在原地让他重试，不能让他重新选一遍文件） */
    var passwordError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun toast(message: String) {
        val vm = viewModel
        if (vm != null) {
            // 走全局那套带 App 图标的提示，和设置页其它反馈保持一致
            vm.toastMessage = message
        } else {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 解密 + 恢复 + 鉴权。
     *
     * 单独抽成局部函数是因为它有两个入口：明文包（选完文件直接走）和
     * 加密包（用户在密码框点了「解锁并恢复」）。两份代码迟早会走岔——
     * 比如一处记得鉴权、一处忘了，于是「加密备份恢复后一进主页就掉线」，
     * 而且只有加密包才复现，极难查。
     */
    fun runRestore(json: String, password: String?) {
        busy = true
        scope.launch {
            val decoded = decodeBackup(json, password)
            val payload = decoded.getOrNull()
            if (payload == null) {
                busy = false
                val error = (decoded.exceptionOrNull() as? BackupCodec.DecodeException)?.error
                val message = if (error != null) backupDecodeErrorMessage(error)
                else "备份包解析失败：${decoded.exceptionOrNull()?.message ?: "未知原因"}"
                // 密码错：留在密码框里，让他重输一次就够，别让他从选文件重新开始
                if (error == DecodeError.WrongPassword && password != null) {
                    passwordError = message
                } else {
                    pendingJson = null
                    toast(message)
                }
                return@launch
            }
            passwordError = null
            pendingJson = null
            val message = restoreAndVerify(payload, viewModel)
            busy = false
            toast(message)
        }
    }

    // ── 备份：系统「新建文档」选择器 ──
    //
    // CreateDocument 由**用户**决定存去哪（下载目录、网盘、U 盘都行），
    // 默认文件名只是建议值，用户可以在选择器里改。
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val target = prepared
        prepared = null
        // uri == null：用户在选择器里按了返回。静默结束，不要弹「备份失败」吓人
        if (uri == null) return@rememberLauncherForActivityResult
        // 用户选好了位置，但待写内容没了 —— 只可能是系统在选择器开着的时候
        // 把本进程回收了（低内存），重组后 prepared 是初始值 null。
        // 这时**不能**静默返回：他会以为自己点成功了，跑去下载目录找不到文件。
        if (target == null) {
            toast("刚才那个文件没写成：系统在选位置时回收了本 App，请重新点一次「备份到文件」")
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val encoded = encodeBackup(target.payload, target.password)
            val json = encoded.getOrNull()
            if (json == null) {
                busy = false
                toast("备份没做成：${encoded.exceptionOrNull()?.message ?: "内容编码失败"}")
                return@launch
            }
            val written = BackupFileIo.writeToUri(context, uri, json)
            busy = false
            toast(
                written.fold(
                    onSuccess = {
                        val encryptedHint = if (target.password != null) {
                            "已加密，请记住密码——忘记后无法恢复"
                        } else {
                            "未加密，别把它当隐私文件随便发给别人"
                        }
                        "备份已保存（${encryptedHint}）${target.note}"
                    },
                    // writeToUri 的 message 已经是中文（找不到文件 / 没权限 / 被回收）
                    onFailure = { "备份文件没能写入：${it.message ?: "未知原因"}，换个位置再试一次" }
                )
            )
        }
    }

    // ── 恢复：系统「打开文档」选择器 ──
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val read = BackupFileIo.readFromUri(context, uri)
            busy = false
            val text = read.getOrNull()
            if (text == null) {
                toast("没能读取这个文件：${read.exceptionOrNull()?.message ?: "未知原因"}")
                return@launch
            }
            // 先只解析外壳（peek 不解密，几乎不耗时）：要不要弹密码框就看这一步。
            // 直接 decode 的话，加密包会以「缺少密码」失败，用户还得再选一次文件。
            val encrypted = try {
                BackupCodec.peek(text).encrypted
            } catch (e: BackupCodec.DecodeException) {
                toast(backupDecodeErrorMessage(e.error))
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

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                "备份 / 恢复",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "把收藏和登录凭证存成一个文件，换手机、重装后一键恢复。" +
                    "文件存哪儿由你在系统选择器里决定，不需要任何存储权限。",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showOptions = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("备份到文件", fontSize = 14.sp, maxLines = 1) }

                Button(
                    onClick = { openLauncher.launch(arrayOf("application/json")) },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                ) { Text("从备份包恢复", fontSize = 14.sp, maxLines = 1) }
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    // 加解密走 PBKDF2（21 万次），手机上有 100~300ms，必须让用户看到还在动
                    Text("正在处理备份文件…", color = AppColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    if (showOptions) {
        BackupOptionsDialog(
            canBackupCredentials = PrefsHelper.isLoggedIn,
            onDismiss = { showOptions = false },
            onConfirm = { wantFavorites, wantCredentials, password ->
                showOptions = false
                val payload = BackupService.collect(
                    favorites = wantFavorites,
                    credentials = wantCredentials
                )
                when {
                    // 一项内容都没有：此时写出去的文件解出来也是空包，数据层会拒收
                    payload.isEmpty && wantCredentials ->
                        toast("当前没有完整的登录信息，也没有收藏可以备份；先登录，或在设备列表里收藏一台")
                    payload.isEmpty ->
                        toast("还没有收藏可以备份，先在设备列表里收藏一台再来")
                    else -> {
                        prepared = PreparedBackup(
                            payload = payload,
                            password = password,
                            // 用户勾了凭证但本地信息不全（没登录/缺字段）：collect 会返回 null，
                            // 这时文件里其实没有凭证。**必须告诉他**，否则他以为备份过了
                            note = if (wantCredentials && !payload.hasCredentials) {
                                "。注意：当前登录信息不完整，这个包里没有登录凭证"
                            } else ""
                        )
                        createLauncher.launch(BackupFormat.suggestFileName(System.currentTimeMillis()))
                    }
                }
            }
        )
    }

    val json = pendingJson
    if (json != null) {
        RestorePasswordDialog(
            error = passwordError,
            busy = busy,
            onDismiss = { if (!busy) { pendingJson = null; passwordError = null } },
            onConfirm = { password -> runRestore(json, password) }
        )
    }
}

/** 等用户在系统文件选择器里选好位置的那一份备份内容 */
private data class PreparedBackup(
    val payload: BackupPayload,
    /** null = 不加密（只有「仅收藏」时允许） */
    val password: String?,
    /** 附加在成功提示后面的补充说明（例如凭证没备份成） */
    val note: String
)

/**
 * 各 [DecodeError] 对应的用户可见文案。
 *
 * 为什么一条条分开写：这些错误的**下一步动作完全不同**。
 * 把「密码错」和「文件坏了」都说成「恢复失败」，用户只能反复试同一个密码。
 *
 * `internal` 是给登录页（另一个入口）复用的——两处文案必须一字不差，
 * 否则同一个坏文件在设置页说「已损坏」、在登录页说「不是备份包」，像两个 App。
 */
internal fun backupDecodeErrorMessage(error: DecodeError): String = when (error) {
    DecodeError.NotABackup -> "这不是趣智Lite的备份包，请确认选的是本 App 导出的 .json 文件"
    // 正常流程走不到：peek 已经判断过要不要弹密码框，这里只是兜底
    DecodeError.PasswordRequired -> "这个备份包需要密码才能打开，请重新选择并输入密码"
    DecodeError.WrongPassword -> "密码错误，请重试"
    DecodeError.Corrupt -> "备份包已损坏或不完整，可能是文件被改动过或没有拷全"
    DecodeError.Empty -> "备份包里没有可恢复的内容"
    is DecodeError.Failed -> "备份包解析失败：${error.message}"
}

/**
 * 把备份负载写回 App：**先恢复收藏，凭证先鉴权再决定要不要写入**。
 *
 * @return 给用户看的提示（已经包含收藏条数、凭证结论和下一步）
 */
private suspend fun restoreAndVerify(payload: BackupPayload, viewModel: MainViewModel?): String {
    // 记下恢复前的账号，用来判断这次是「同一个账号重装恢复」还是「换账号」。
    // 必须在这一步读：下面的 restore / loginWithCode 都会改 Prefs 里的凭证。
    val previousUserId = PrefsHelper.userId

    // ① 只写收藏。restore 的 writeCredentials 默认就是 false —— 这就是为什么
    //    这里不传那个参数：凭证要等下面的鉴权通过才算数。
    val result = BackupService.restore(payload)
    viewModel?.reloadFavorites()

    val favoritesNote = when {
        result.favoritesRestored > 0 -> "已恢复 ${result.favoritesRestored} 个收藏"
        payload.hasFavorites -> "收藏都已在列表里，没有新增"
        else -> "这个备份包没有收藏列表"
    }

    val credentials = result.credentials
    if (credentials == null) {
        // 没凭证：只报收藏结果。措辞不能写「请登录」——用户此刻就在设置页里，
        // 本来就是登录状态，让他去登录只会一脸问号（登录页那个入口才那么说）
        return "$favoritesNote；备份包里没有 loginCode 和 userId 信息，当前登录状态保持不变"
    }
    if (!credentials.isComplete) {
        // 有凭证字段但缺项（accountId / projectId 少一个就登不上）。
        // 和「完全没有凭证」必须分开说：否则用户会以为自己压根没备份过凭证。
        return "$favoritesNote；备份包里的登录凭证不完整（缺 loginCode/userId/accountId/projectId 里的某一项），" +
            "没有写入，当前登录状态保持不变"
    }

    // ② **换账号前的安全闸门**。
    //
    // 既然下面是「切到备份里那个账号」，就得遵守和退出登录一样的规则：
    // 有设备正在用水时不能换账号——换掉之后那些停止入口认得的是新账号，
    // 水还在流却关不掉。这条判据直接复用 [canLogoutVoluntarily]，
    // 不在这里另写一份（两处标准迟早会分叉）。
    val switchingAccount = previousUserId.isNotEmpty() && previousUserId != credentials.userId
    if (switchingAccount && !canLogoutVoluntarily(PrefsHelper.getActiveOrders())) {
        return "$favoritesNote；备份里是另一个账号，但你有设备正在使用中，现在切换会丢掉停止入口。" +
            "请先结束用水，再恢复一次"
    }

    // ③ 先鉴权。loginWithCode 会先拉板块列表来验证这个 loginCode 还有没有效，
    //    并且**只有全部成功才落盘**（见 AuthRepository 里的注释）
    val verified = AuthRepository.loginWithCode(credentials.loginCode, credentials.userId)
    if (verified.isFailure) {
        // ⚠️ 必须还原网络层：loginWithCode 第一步就把备份里的 loginCode 塞进了内存中的
        //    认证参数（那时还没有 accountId/projectId）。失败后不还原的话，当前账号
        //    后续所有请求都会带着一份不完整的认证参数发出去——全部接口报「请重新登录」，
        //    比恢复失败本身更难排查。
        NetworkModule.restoreFromPrefs()
        val reason = verified.exceptionOrNull()?.message.orEmpty()
        return if (isNetworkReason(reason)) {
            "$favoritesNote；网络不通，没能验证备份里的登录凭证（凭证没有写入），联网后再恢复一次就行"
        } else {
            // 用户明确要求：失效就明说失效，且**不写**这份凭证，
            // 否则一进主页就被踢下线，是这套功能里最差的体验
            "$favoritesNote；备份里的登录凭证已失效（可能已在别处登录），凭证没有写入，" +
                "当前登录状态保持不变。要换账号请用手机号或 loginCode 重新登录"
        }
    }

    // ④ 鉴权通过。loginWithCode 成功时已经把这套认证参数写进 Prefs 和网络层了，
    //    而且写的是**服务端解析出来的** accountId / projectId / telephone，
    //    比备份包里那份（可能是换板块之前的）更可信。
    //
    // ⚠️ 所以这里**不再**调 BackupService.saveCredentials：
    //    那一步会拿备份包里的旧 projectId 覆盖刚拿到的值，而 currentProjectId
    //    已经被 loginWithCode 设成了新值 —— 两处不一致，切板块时会串号。
    viewModel?.beginSession()

    if (switchingAccount) {
        // 换了账号，但「我的」页/钱包页这几个 State 里还是上一任的数据
        // （姓名、学号、钱包余额、账单列表）。它们不会因为切页面自己重拉——
        // 那几个 LaunchedEffect(Unit) 只在页面首次组合时跑一次，
        // 而我们此刻就站在页面上。不重拉的话，用户会看到 B 账号的花费里
        // 混着 A 账号的名字和余额。
        viewModel?.loadAccountInfo()
        viewModel?.refreshWallet()
        viewModel?.loadBills()
        viewModel?.loadCampusBalance()
    }

    val phone = PrefsHelper.telephone
    val suffix = if (phone.length >= 4) "（手机尾号 ${phone.takeLast(4)}）" else ""
    return "$favoritesNote；登录凭证校验通过，已切回备份里的账号$suffix"
}

/**
 * 判断 loginWithCode 的失败是不是「网络问题」而不是「凭证失效」。
 *
 * 两者对用户的意义完全不同：前者过会儿重试就行，后者必须换账号登录。
 * 判据和 LoginViewModel 里那段网络错误识别保持一致（同一批友商手机上报出来的串）。
 */
private fun isNetworkReason(message: String): Boolean {
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

/**
 * 编码。放 [Dispatchers.Default]：PBKDF2 21 万次要 100~300ms，
 * 在主线程做会让按钮按下去之后明显卡一下。
 */
private suspend fun encodeBackup(payload: BackupPayload, password: String?): Result<String> =
    withContext(Dispatchers.Default) {
        try {
            Result.success(BackupCodec.encode(payload, password))
        } catch (e: Exception) {
            // encode 的 require 失败也是 Exception（含「含凭证必须提供密码」那条硬约束）
            Result.failure(e)
        }
    }

/** 解码，同上放后台线程（解密同样要跑一遍 PBKDF2） */
private suspend fun decodeBackup(json: String, password: String?): Result<BackupPayload> =
    withContext(Dispatchers.Default) {
        try {
            Result.success(BackupCodec.decode(json, password))
        } catch (e: BackupCodec.DecodeException) {
            // 保留原异常类型：调用方要靠它区分 WrongPassword 和其它错误
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

/**
 * 备份内容勾选 + 密码设置。
 *
 * 做成一个对话框而不是把勾选框铺在卡片里：备份是低频动作，
 * 常驻的密码输入框会让整张卡片看起来像表单，反而没人敢点。
 */
@Composable
private fun BackupOptionsDialog(
    canBackupCredentials: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (favorites: Boolean, credentials: Boolean, password: String?) -> Unit
) {
    var favorites by remember { mutableStateOf(true) }
    var credentials by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    // 勾了凭证就强制加密：这里只负责让用户**看见**这条规则，
    // 真正的兜底在数据层（BackupCodec.encode 收到带凭证的负载且没密码会直接抛）
    val mustEncrypt = credentials || encrypt
    val nothingChosen = !favorites && !credentials
    val canConfirm = !nothingChosen && (!mustEncrypt || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = AppColors.Card,
        title = {
            Text(
                "备份内容",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
        },
        text = {
            Column {
                CheckRow(
                    title = "收藏列表",
                    desc = "收藏过的设备，可以单独备份",
                    checked = favorites,
                    enabled = true
                ) { favorites = it }

                Spacer(Modifier.height(4.dp))
                CheckRow(
                    title = "登录凭证",
                    desc = if (canBackupCredentials) "loginCode + userId，恢复后免登录"
                    else "当前没有完整的登录信息，先登录才能备份",
                    checked = credentials,
                    enabled = canBackupCredentials
                ) { credentials = it }

                Spacer(Modifier.height(4.dp))
                CheckRow(
                    title = "加密备份",
                    // 勾了凭证时这里不能取消：说明**为什么**，别让用户以为选项坏了
                    desc = if (credentials) "含登录凭证，必须加密" else "设一个密码，别人拿到文件也打不开",
                    checked = mustEncrypt,
                    enabled = !credentials
                ) { encrypt = it }

                if (mustEncrypt) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        // 密码无复杂度限制（用户明确要求），所以这里不做任何校验，
                        // 只要求非空——空密码等于没加密
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "请妥善保存这个密码：文件里没有找回入口，忘记后无法恢复，只能重新备份一份。",
                        color = AppColors.Warning,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }

                if (nothingChosen) {
                    Spacer(Modifier.height(8.dp))
                    Text("至少选一项要备份的内容。", color = AppColors.Danger, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(favorites, credentials, if (mustEncrypt) password else null) },
                enabled = canConfirm
            ) {
                Text("下一步：选保存位置", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppColors.TextSecondary) }
        }
    )
}

/** 加密包恢复时输密码 */
@Composable
private fun RestorePasswordDialog(
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(22.dp),
        containerColor = AppColors.Card,
        title = {
            Text(
                "备份包已加密",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
        },
        text = {
            Column {
                Text(
                    "请输入备份时设置的那个密码。",
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp
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
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = AppColors.Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "忘记密码的话，这份备份就打不开了——只能找另一份备份，或者重新登录后重新备份。",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !busy && password.isNotEmpty()
            ) {
                Text(if (busy) "正在解锁…" else "解锁并恢复", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}

/** 对话框里的一行勾选项（标题 + 说明 + 勾选框） */
@Composable
private fun CheckRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(desc, color = AppColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AppColors.Accent)
        )
    }
}
