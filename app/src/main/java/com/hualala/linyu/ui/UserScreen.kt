package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.BuildConfig
import com.hualala.linyu.api.GithubApi
import com.hualala.linyu.api.GithubAsset
import com.hualala.linyu.api.GithubRelease
import com.hualala.linyu.api.GithubRepoInfo
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.updateUseCodeStatusSafe
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.LocalThemeReveal
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.ApkDownloadState
import com.hualala.linyu.utils.ApkInstallResult
import com.hualala.linyu.utils.ApkUpdater
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.launch

/** 「我的」页面中可编辑的卡片类型（声明顺序即默认顺序） */
enum class UserCardType(val title: String) {
    ACCOUNT("账号信息"),
    BOUND_ROOM("绑定寝室"),
    USE_CODE("使用码"),
    BACKGROUND("背景装扮"),
    UPDATE("软件更新"),
    ABOUT("关于项目"),
    LOG("运行日志")
}

// ── 卡片顺序 / 隐藏状态的读写 ──

/** 旧版本的默认顺序（「运行日志」在「关于项目」之前），用于识别"从未自定义过排序"的用户 */
private val LEGACY_DEFAULT_ORDER = listOf(
    "ACCOUNT", "BOUND_ROOM", "USE_CODE", "BACKGROUND", "UPDATE", "LOG", "ABOUT"
)

private fun loadCardOrder(): List<UserCardType> {
    val all = UserCardType.values().toList()
    val savedNames = PrefsHelper.userCardOrder.split(",")
        .map { it.trim() }.filter { it.isNotEmpty() }
    // 保存的顺序若恰好等于旧版默认顺序，说明用户没有手动排过，改用新默认顺序
    val effective = if (savedNames == LEGACY_DEFAULT_ORDER) emptyList() else savedNames
    val saved = effective.mapNotNull { name -> all.find { it.name == name } }
    // 已保存的顺序 + 新增卡片（追加到末尾），并去重
    return (saved + all).distinct()
}

private fun saveCardOrder(order: List<UserCardType>) {
    PrefsHelper.userCardOrder = order.joinToString(",") { it.name }
    UserPageCache.cardOrder = order
}

private fun loadHiddenCards(): Set<String> =
    PrefsHelper.userHiddenCards.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun saveHiddenCards(hidden: Set<String>) {
    PrefsHelper.userHiddenCards = hidden.joinToString(",")
    UserPageCache.hiddenCards = hidden
}

private fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

/**
 * 「我的」页面的进程级缓存。
 *
 * 页面在切换 tab 时会被重建（AnimatedContent），若不缓存则会反复做两件慢事：
 * 1. 读 EncryptedSharedPreferences（需要解密）
 * 2. 发 GitHub 网络请求
 * 缓存后只做一次，切换 tab 就不再卡顿。
 */
private object UserPageCache {
    var cardOrder: List<UserCardType>? = null
    var hiddenCards: Set<String>? = null
    var releasesFetched: Boolean = false
    var releases: List<GithubRelease> = emptyList()
    var repoInfoFetched: Boolean = false
    var repoInfo: GithubRepoInfo? = null
}

@Composable
fun UserScreen(phone: String, onLogout: () -> Unit, viewModel: MainViewModel? = null) {
    LaunchedEffect(Unit) { viewModel?.loadUseCode() }
    val useCode = viewModel?.useCodeData

    // 编辑模式与卡片布局
    var editMode by remember { mutableStateOf(false) }
    var cardOrder by remember {
        mutableStateOf(UserPageCache.cardOrder ?: loadCardOrder().also { UserPageCache.cardOrder = it })
    }
    var hiddenCards by remember {
        mutableStateOf(UserPageCache.hiddenCards ?: loadHiddenCards().also { UserPageCache.hiddenCards = it })
    }

    var showSchoolDialog by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }
    var showBackgroundScreen by remember { mutableStateOf(false) }
    var schoolInput by remember { mutableStateOf(PrefsHelper.schoolName) }
    var themeMode by LocalThemeMode.current
    val themeReveal = LocalThemeReveal.current
    var themeBtnPos by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // 标题栏：标题 + 编辑 + 主题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的账号", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editMode = !editMode }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (editMode) "完成" else "编辑", fontSize = 14.sp, color = AppColors.Accent)
                }
                IconButton(
                    onClick = { themeReveal.toggle(themeBtnPos) },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        themeBtnPos = coords.boundsInRoot().center
                    }
                ) {
                    Text(if (themeMode == ThemeMode.DARK) "🌙" else "☀️", fontSize = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 卡片列表（编辑模式下显示全部，便于恢复隐藏项）
        val visibleCards = if (editMode) cardOrder else cardOrder.filter { it.name !in hiddenCards }
        visibleCards.forEachIndexed { index, type ->
            EditableCardSlot(
                editMode = editMode,
                title = type.title,
                isHidden = type.name in hiddenCards,
                canMoveUp = index > 0,
                canMoveDown = index < visibleCards.lastIndex,
                onMoveUp = {
                    cardOrder = moveItem(cardOrder, index, index - 1); saveCardOrder(cardOrder)
                },
                onMoveDown = {
                    cardOrder = moveItem(cardOrder, index, index + 1); saveCardOrder(cardOrder)
                },
                onToggleHide = {
                    hiddenCards = if (type.name in hiddenCards) hiddenCards - type.name
                                  else hiddenCards + type.name
                    saveHiddenCards(hiddenCards)
                }
            ) {
                when (type) {
                    UserCardType.ACCOUNT -> AccountCard(phone) { showSchoolDialog = true }
                    UserCardType.BOUND_ROOM -> BoundRoomCard(viewModel)
                    UserCardType.USE_CODE -> UseCodeCard(useCode, viewModel)
                    UserCardType.BACKGROUND -> BackgroundCard { showBackgroundScreen = true }
                    UserCardType.UPDATE -> UpdateCard()
                    UserCardType.LOG -> LogCard { showLogViewer = true }
                    UserCardType.ABOUT -> AboutCard()
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)) {
            Text("退出登录")
        }

        Spacer(Modifier.height(8.dp))
        Text("Hualala v${BuildConfig.VERSION_NAME} · 哗啦啦啦啦让我去淋浴~",
            color = AppColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(100.dp)) // 底部留出悬浮导航栏空间
    }

    if (showSchoolDialog) {
        AlertDialog(
            onDismissRequest = { showSchoolDialog = false },
            title = { Text("修改学校名称", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = schoolInput,
                    onValueChange = { schoolInput = it },
                    label = { Text("学校名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    PrefsHelper.schoolName = schoolInput
                    showSchoolDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSchoolDialog = false }) { Text("取消") }
            }
        )
    }

    if (showLogViewer) {
        LogViewerDialog(onDismiss = { showLogViewer = false })
    }

    if (showBackgroundScreen) {
        CustomBackgroundScreen(onDismiss = { showBackgroundScreen = false })
    }
}

/** 统一的卡片外观 */
@Composable
private fun BaseCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** 编辑模式下在卡片上方显示的操作条；非编辑模式直接渲染卡片 */
@Composable
private fun EditableCardSlot(
    editMode: Boolean,
    title: String,
    isHidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHide: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        if (editMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isHidden) "$title（已隐藏）" else title,
                    fontSize = 13.sp,
                    color = if (isHidden) AppColors.TextSecondary.copy(alpha = 0.6f) else AppColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "上移",
                        tint = if (canMoveUp) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.3f))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "下移",
                        tint = if (canMoveDown) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.3f))
                }
                IconButton(onClick = onToggleHide, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        if (isHidden) "显示" else "隐藏",
                        tint = if (isHidden) AppColors.Success else AppColors.TextSecondary
                    )
                }
            }
        }
        // 隐藏状态下，编辑模式中卡片半透明显示
        Box(modifier = if (editMode && isHidden) Modifier.alpha(0.45f) else Modifier) {
            content()
        }
    }
}

@Composable
private fun AccountCard(phone: String, onEditSchool: () -> Unit) {
    BaseCard {
        Column(Modifier.padding(20.dp)) {
            InfoRow("姓名", PrefsHelper.userName.ifEmpty { "未设置" })
            Spacer(Modifier.height(10.dp))
            InfoRow("手机号", phone)
            Spacer(Modifier.height(10.dp))
            EditableInfoRow("学校", PrefsHelper.schoolName) { onEditSchool() }
        }
    }
}

@Composable
private fun BoundRoomCard(viewModel: MainViewModel?) {
    var boundRoomInput by remember { mutableStateOf(PrefsHelper.boundRoom) }
    var boundRoom by remember { mutableStateOf(PrefsHelper.boundRoom) }
    var showRoomPicker by remember { mutableStateOf(false) }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Text("绑定寝室", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (boundRoom.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏠 $boundRoom", fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        boundRoom = ""; boundRoomInput = ""
                        PrefsHelper.boundRoom = ""
                    }) {
                        Text("取消绑定", color = AppColors.Danger, fontSize = 12.sp)
                    }
                }
            } else {
                Text("未绑定寝室，设备列表将显示全部设备",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = boundRoomInput,
                onValueChange = { boundRoomInput = it },
                label = {
                    Text("输入关键词（如：3号楼南/320）",
                        fontSize = 13.sp, color = AppColors.TextSecondary)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (boundRoomInput.trim().isNotEmpty()) {
                        val v = boundRoomInput.trim()
                        boundRoom = v; PrefsHelper.boundRoom = v
                        viewModel?.toastMessage = "已绑定寝室：$v"
                    }
                }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                    Text("保存")
                }
                OutlinedButton(onClick = { showRoomPicker = true },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text("从附近设备选")
                }
            }
        }
    }

    if (showRoomPicker) {
        val devices = viewModel?.nearbyDevices?.mapNotNull {
            val n = it.deviceInfo?.deviceName ?: it.name
            viewModel.extractLocationFromDevice(n).takeIf { it.isNotBlank() }
        }?.distinct() ?: emptyList()
        AlertDialog(
            onDismissRequest = { showRoomPicker = false },
            title = { Text("选择设备位置", fontWeight = FontWeight.Bold) },
            text = {
                if (devices.isEmpty()) {
                    Text("附近暂无设备，请先到热水器旁扫描后再试", color = AppColors.TextSecondary)
                } else {
                    LazyColumn(Modifier.height(300.dp)) {
                        items(devices) { loc ->
                            TextButton(
                                onClick = {
                                    boundRoom = loc
                                    boundRoomInput = loc
                                    PrefsHelper.boundRoom = loc
                                    showRoomPicker = false
                                    viewModel?.toastMessage = "已绑定寝室：$loc"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(loc, color = AppColors.TextPrimary, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRoomPicker = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun UseCodeCard(useCode: UseCodeData?, viewModel: MainViewModel?) {
    var localCodeOn by remember { mutableStateOf(useCode?.useCodeStatus == 1) }
    LaunchedEffect(useCode?.useCodeStatus) { useCode?.useCodeStatus?.let { localCodeOn = it == 1 } }
    val scope = rememberCoroutineScope()

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("使用码", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text("后三位为手机号后三位", color = AppColors.TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            val code = useCode?.useCode
            if (!code.isNullOrEmpty()) {
                val prefix = code.dropLast(3)
                val suffix = code.takeLast(3)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = AppColors.TextPrimary)) { append(prefix) }
                        withStyle(SpanStyle(color = AppColors.Accent)) { append(suffix) }
                    },
                    fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp
                )
            } else {
                Text("加载中...", fontSize = 28.sp, fontWeight = FontWeight.Black,
                    color = AppColors.TextPrimary, letterSpacing = 6.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (localCodeOn) "使用码已开启" else "使用码已关闭",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
                Switch(checked = localCodeOn, onCheckedChange = { newVal ->
                    localCodeOn = newVal
                    scope.launch {
                        try {
                            NetworkModule.apiService.updateUseCodeStatusSafe(
                                status = if (newVal) 1 else 0,
                                auth = NetworkModule.authFields()
                            )
                        } catch (e: Exception) {
                            localCodeOn = !newVal
                            val msg = e.message ?: ""
                            viewModel?.toastMessage = when {
                                msg.contains("Unable to resolve host", ignoreCase = true) ||
                                msg.contains("No address associated", ignoreCase = true) ||
                                msg.contains("Failed to connect", ignoreCase = true) ->
                                    "网络连接失败，请检查网络设置"
                                else -> "操作失败，请重试"
                            }
                        }
                    }
                }, colors = SwitchDefaults.colors(checkedThumbColor = AppColors.Accent,
                    checkedTrackColor = AppColors.Accent.copy(alpha = 0.5f)))
            }
            Text("在热水器物理键盘上输入此码",
                color = AppColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LogCard(onOpen: () -> Unit) {
    BaseCard {
        Row(Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("查看/导出运行日志，便于排查问题",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("查看")
            }
        }
    }
}

// ── 背景装扮 ──
@Composable
private fun BackgroundCard(onOpen: () -> Unit) {
    val enabled = com.hualala.linyu.utils.BackgroundState.config(com.hualala.linyu.utils.BackgroundManager.SCOPE_HOME).enabled || com.hualala.linyu.utils.BackgroundState.config(com.hualala.linyu.utils.BackgroundManager.SCOPE_SHOWER).enabled
    BaseCard {
        Row(Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("背景装扮", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled) "已启用自定义背景" else "使用自己的图片打造专属界面",
                    color = AppColors.TextSecondary, fontSize = 12.sp
                )
            }
            Button(onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("更换")
            }
        }
    }
}

// ── 应用信息 / 软件更新 ──
@Composable
private fun UpdateCard() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 用进程级缓存，避免每次切到「我的」都重新请求
    var releases by remember { mutableStateOf(UserPageCache.releases) }
    var checking by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(UserPageCache.releasesFetched) }
    var expandedTag by remember { mutableStateOf<String?>(null) }

    val currentVersion = ApkUpdater.currentVersion
    val latestRelease = releases.firstOrNull()
    val latestTag = latestRelease?.tagName
    // 只有确实比当前新才提示更新——避免 tag 命名差异导致误报"有新版本"
    val hasUpdate = latestTag != null && ApkUpdater.isNewer(latestTag, currentVersion)
    val apkAsset = latestRelease?.apkAsset
    val downloadState = ApkUpdater.state

    // 打开卡片即自动检测一次（已拉取过则跳过）
    fun doCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val list = GithubApi.fetchReleases()
            UserPageCache.releases = list
            UserPageCache.releasesFetched = true
            releases = list
            checkedOnce = true
            checking = false
            // 不自动展开任何版本（由用户手动点击展开）
        }
    }
    LaunchedEffect(Unit) { if (!UserPageCache.releasesFetched) doCheck() }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            // ① 应用信息 + 当前版本
            Text("应用信息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("当前版本 $currentVersion", color = AppColors.TextSecondary, fontSize = 13.sp)

            // ② 自动检测结果
            Spacer(Modifier.height(8.dp))
            val (statusText, statusColor) = when {
                checking -> "正在检测更新…" to AppColors.TextSecondary
                !checkedOnce -> "正在检测更新…" to AppColors.TextSecondary
                releases.isEmpty() -> "检测失败（多为网络原因）" to AppColors.Warning
                hasUpdate -> "发现新版本 $latestTag" to AppColors.Accent
                else -> "当前已是最新版本" to AppColors.Success
            }
            Text(statusText, color = statusColor, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)

            // ③ 更新日志（点击版本号展开）
            if (releases.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = AppColors.Border)
                Spacer(Modifier.height(10.dp))
                Text("更新日志", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                releases.forEach { r ->
                    ReleaseRow(
                        release = r,
                        isCurrent = r.tagName == currentVersion,
                        expanded = expandedTag == r.tagName,
                        onToggle = { expandedTag = if (expandedTag == r.tagName) null else r.tagName }
                    )
                }
            }

            // ④ 检查更新按钮
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    ApkUpdater.reset()
                    doCheck()
                },
                enabled = !checking,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text(if (checking) "检查中..." else "检查更新") }

            // ⑤ 下载区：放在「检查更新」下方，仍在本卡片内
            if (hasUpdate) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.Border)
                Spacer(Modifier.height(12.dp))
                DownloadSection(
                    context = context,
                    asset = apkAsset,
                    state = downloadState
                )
            }
        }
    }
}

/**
 * 下载更新的四种形态：可下载 / 下载中 / 已下载待安装 / 失败。
 *
 * 全部收在这张卡片里，不往外弹任何东西。
 */
@Composable
private fun DownloadSection(
    context: android.content.Context,
    asset: GithubAsset?,
    state: ApkDownloadState
) {
    // 点「立即安装」后如果调起失败（包丢了 / 没有可用设置页），要在这里说清楚
    var installError by remember { mutableStateOf<String?>(null) }

    val startDownload: () -> Unit = {
        installError = null
        asset?.let { ApkUpdater.start(context, it.downloadUrl, it.name) }
    }

    when (state) {
        is ApkDownloadState.Running -> {
            val pct = state.percent
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("正在下载更新包…", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text(
                    // 显示「已下载 / 总大小」，比单纯一个百分比更有信息量
                    if (state.total > 0) {
                        "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                    } else {
                        formatBytes(state.downloaded)
                    },
                    color = AppColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))
            if (pct >= 0) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { ApkUpdater.cancel() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消下载", color = AppColors.TextSecondary, fontSize = 13.sp) }
        }

        is ApkDownloadState.Done -> {
            Text("更新包已下载完成", color = AppColors.Success, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    installError = when (val r = ApkUpdater.installApk(context, state.file)) {
                        is ApkInstallResult.Error -> r.message
                        else -> null
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Success)
            ) { Text("立即安装") }
            Spacer(Modifier.height(6.dp))
            Text(
                "请在系统设置里允许「安装未知应用」",
                color = AppColors.TextSecondary, fontSize = 12.sp
            )
        }

        is ApkDownloadState.Failed -> {
            Surface(shape = RoundedCornerShape(10.dp),
                color = AppColors.Warning.copy(alpha = 0.12f)) {
                Text(state.message, modifier = Modifier.padding(10.dp),
                    color = AppColors.Warning, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = startDownload,
                enabled = asset != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("重新下载") }
        }

        ApkDownloadState.Idle -> {
            Button(
                onClick = startDownload,
                enabled = asset != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("下载更新") }
        }
    }

    installError?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(10.dp),
            color = AppColors.Warning.copy(alpha = 0.12f)) {
            Text(msg, modifier = Modifier.padding(10.dp),
                color = AppColors.Warning, fontSize = 12.sp)
        }
    }
}

/** 字节数转成人看的单位 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * 从发行版说明中只抽出「新增功能」与「修复」两类内容，其余段落（安装步骤、注意事项等）丢弃。
 * 发行版说明是 Markdown，按 `#` 标题切段落，命中关键词的段落保留整段。
 */
private fun extractReleaseHighlights(body: String): String {
    val out = StringBuilder()
    var keeping = false
    body.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("#")) {
            val title = plainHeading(line)
            keeping = title.contains("新增") || title.contains("功能") ||
                      title.contains("修复") || title.contains("BUG", ignoreCase = true)
            if (keeping) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(stripMarkdown(title)).append('\n')
            }
        } else if (keeping) {
            out.append(stripMarkdown(line)).append('\n')
        }
    }
    return out.toString().trim()
}

/**
 * 去掉 Markdown 强调标记。
 *
 * 发行版说明是按 Markdown 写的，但 App 里用的是普通 Text 渲染（没有 Markdown 解析），
 * 不处理的话 `**加粗**` 会把星号原样显示出来，`反引号` 同理。
 */
private fun stripMarkdown(s: String): String =
    s.replace("**", "").replace("__", "").replace("`", "")

/** 去掉标题里的 `#`、表情符号和多余空白，只保留中日韩文字与 ASCII */
private fun plainHeading(line: String): String =
    line.trimStart().trimStart('#').trim()
        .filter { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0x20..0x7E }
        .trim()

@Composable
private fun ReleaseRow(
    release: GithubRelease,
    isCurrent: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 去掉点击水波纹：整行铺开的矩形反馈在卡片上很突兀，
                // 展开/收起本身有内容变化，已经够作反馈了
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(release.tagName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = AppColors.TextPrimary)
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text("当前", fontSize = 10.sp, color = AppColors.Success,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Success.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = AppColors.TextSecondary
            )
        }
        if (expanded) {
            Text(
                extractReleaseHighlights(release.body).ifBlank { "本版本未填写更新详情。" },
                fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                release.publishedAt.take(10),
                fontSize = 11.sp, color = AppColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            HorizontalDivider(color = AppColors.Border)
        }
    }
}

// ── 关于项目 ──
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    // 用进程级缓存，避免每次切到「我的」都重新请求
    var repo by remember { mutableStateOf(UserPageCache.repoInfo) }

    LaunchedEffect(Unit) {
        if (!UserPageCache.repoInfoFetched) {
            val r = GithubApi.fetchRepoInfo()
            UserPageCache.repoInfo = r
            UserPageCache.repoInfoFetched = true
            repo = r
        }
    }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Text("关于项目", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))

            Text("一款趣智校园第三方客户端", color = AppColors.TextSecondary, fontSize = 13.sp)

            Spacer(Modifier.height(6.dp))
            // Star 数来自 GitHub API，拉取失败时省略（不显示假数据）
            val stars = repo?.stars
            Text(
                "项目名：淋浴（Hualala）" + (stars?.let { " · ⭐ $it" } ?: ""),
                color = AppColors.TextSecondary, fontSize = 13.sp
            )

            Spacer(Modifier.height(6.dp))
            Text("制作者：${repo?.ownerLogin?.takeIf { it.isNotBlank() } ?: "yehu-imei"}",
                color = AppColors.TextSecondary, fontSize = 13.sp)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("联系方式：", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text(CONTACT_EMAIL, color = AppColors.Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { sendEmail(context) })
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { openUrl(context, repo?.htmlUrl ?: GithubApi.REPO_URL) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("访问 GitHub 仓库") }
        }
    }
}

/** 项目联系方式（点一下会调起邮件应用） */
private const val CONTACT_EMAIL = "2276391153@qq.com"

/** 点击邮箱调起系统邮件应用 */
private fun sendEmail(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("mailto:$CONTACT_EMAIL"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 打开外部链接（失败静默，不影响使用） */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.TextSecondary)
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}

@Composable
private fun EditableInfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        // 左侧：标签 + 修改按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppColors.TextSecondary)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                Text("修改", fontSize = 12.sp, color = AppColors.Accent)
            }
        }
        // 右侧：值
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}
