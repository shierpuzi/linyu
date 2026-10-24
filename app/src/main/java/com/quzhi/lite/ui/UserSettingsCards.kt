package com.quzhi.lite.ui

// 从 UserScreen.kt 拆出：设置页的四张卡片。它们只依赖 PrefsHelper / Notifier，
// 不碰 MainViewModel 状态，是最独立的一块（拆分见 docs 里的可维护性说明）。

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.BuildConfig
import com.quzhi.lite.R
import com.quzhi.lite.utils.MD5Utils
import com.quzhi.lite.utils.Notifier
import com.quzhi.lite.api.GithubApi
import com.quzhi.lite.api.GithubAsset
import com.quzhi.lite.api.GithubRelease
import com.quzhi.lite.api.GithubRepoInfo
import com.quzhi.lite.api.NetworkModule
import com.quzhi.lite.data.AuthRepository
import com.quzhi.lite.api.forgetPasswordSafe
import com.quzhi.lite.api.updatePasswordSafe
import com.quzhi.lite.api.updatePhoneSafe
import com.quzhi.lite.api.updateUseCodeStatusSafe
import com.quzhi.lite.model.DeviceInfo
import com.quzhi.lite.model.UseCodeData
import com.quzhi.lite.ui.theme.AppColors
import com.quzhi.lite.ui.theme.LocalThemeMode
import com.quzhi.lite.ui.theme.LocalThemeReveal
import com.quzhi.lite.ui.theme.ThemeMode
import com.quzhi.lite.utils.ApkDownloadState
import com.quzhi.lite.utils.ApkInstallResult
import com.quzhi.lite.utils.ApkUpdater
import com.quzhi.lite.utils.PrefsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通知设置。
 *
 * 三个开关背后是同一套后台监控（`ShowerWatchService`），
 * **关掉任何一个都只是「不发那条通知」，监控本身照常跑**——
 * 自动关停不能因为用户不想被提醒就失效（那正是小组件卡在「使用中」的老毛病）。
 */
@Composable
internal fun NotifyCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PrefsHelper.notifyEnabled) }
    var expanded by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf(PrefsHelper.notifyInUse) }
    var finished by remember { mutableStateOf(PrefsHelper.notifyFinished) }
    var autoClose by remember { mutableStateOf(PrefsHelper.notifyAutoClose) }
    var alert by remember { mutableStateOf(PrefsHelper.notifyAlert) }
    // 系统层面允不允许发通知。Android 13+ 是运行时权限，用户随时能在系统设置里撤销，
    // 所以每次进这张卡片都重新读一遍，别信缓存
    var allowed by remember { mutableStateOf(Notifier.canNotify(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed = Notifier.canNotify(context) }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            // ── 总开关 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("通知", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (enabled) "用水期间和结束时收到系统提醒" else "已关闭全部通知",
                        color = AppColors.TextSecondary, fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; PrefsHelper.notifyEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
                )
            }

            // 权限没开的话开关全是摆设，必须说清楚，否则用户会以为坏了。
            //
            // ⚠️ 但要跟着**总开关**一起判断：用户自己把通知全关了的时候，
            // 再提示「请打开通知权限」就很莫名其妙——他本来就是不想要通知，
            // 而且那个「去开启」按钮还能一键把系统权限打开，等于跟用户对着干。
            if (!allowed && enabled) {
                Spacer(Modifier.height(12.dp))
                // fillMaxWidth 不能少：Surface 默认**按内容撑宽**，不写的话
                // 这块提示只有文字那么宽，右边空一截，看着像没画完
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.Warning.copy(alpha = 0.12f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("请打开通知权限", color = AppColors.Warning,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("开启后才能收到用水提醒和结束通知。",
                            color = AppColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openNotificationSettings(context)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                        ) { Text("去开启", fontSize = 13.sp) }
                    }
                }
            }

            // ── 展开入口 + 细分开关 ──
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { expanded = !expanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("详细设置", color = AppColors.Accent, fontSize = 13.sp)
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = AppColors.Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (expanded) {
                    NotifyRow("用水状态通知", "使用期间在通知栏显示已用时间", inUse) {
                        inUse = it; PrefsHelper.notifyInUse = it
                    }
                    NotifyRow("使用结束通知", "停止后显示用时和消费金额", finished) {
                        finished = it; PrefsHelper.notifyFinished = it
                    }
                    NotifyRow("自动关停提醒", "设备超时自己关闭时提醒", autoClose) {
                        autoClose = it; PrefsHelper.notifyAutoClose = it
                    }
                    // 横幅是这几条里最「吵」的一档，单独给开关。
                    // 靠切渠道实现（见 Notifier.alertChannel），不是改渠道优先级——
                    // 系统不允许 App 改已存在渠道的 importance
                    NotifyRow("横幅提醒", "开阀失败、设备被占用时从屏幕顶部弹出", alert) {
                        alert = it; PrefsHelper.notifyAlert = it
                    }

                    Spacer(Modifier.height(14.dp))
                    // ── 自检按钮 ──
                    //
                    // 为什么要有它：排查「通知不显示」时，正常路径必须先下一单
                    // （真扣钱、0.6 元起）。这个按钮能**不花钱**就把整条链路跑一遍：
                    // 建渠道 → 走 notify() → 系统是否真的呈现。
                    //
                    // 它发的就是真实那条套餐倒计时通知（ID_MODE_ORDER），
                    // 所以如果这里能出来、下单时出不来，问题就精确定位在
                    // 「下单后的调用时机」而不是通知本身。
                    var probeResult by remember { mutableStateOf<String?>(null) }
                    TextButton(
                        onClick = {
                            val r = Notifier.debugProbeModeOrder(context)
                            probeResult = r.fold(
                                onSuccess = { "已发送，请看通知栏（3 分钟后自动结束）" },
                                onFailure = { "发送失败：${it.message}" }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("发送一条测试倒计时通知", fontSize = 14.sp, color = AppColors.Accent)
                    }
                    Text(
                        probeResult ?: "不会扣钱，只是模拟一条套餐倒计时，用来确认通知是否正常",
                        fontSize = 11.sp,
                        color = if (probeResult?.startsWith("发送失败") == true)
                            AppColors.Warning else AppColors.TextSecondary
                    )

                    // 事件类通知的自检：和上面那条分工，验证「普通通知」的样式。
                    // 普通通知（结束/失败/时间到）走标准样式，不进流体云——
                    // 用户要能零成本确认这两类都长得对。
                    var eventProbeResult by remember { mutableStateOf<String?>(null) }
                    TextButton(
                        onClick = {
                            val r = Notifier.debugProbeEvent(context)
                            eventProbeResult = r.fold(
                                onSuccess = { "已发送，请看通知栏（可划掉）" },
                                onFailure = { "发送失败：${it.message}" }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("发送一条测试事件通知", fontSize = 14.sp, color = AppColors.Accent)
                    }
                    Text(
                        eventProbeResult ?: "不会扣钱，验证「使用结束 / 开阀失败」那类通知的样式",
                        fontSize = 11.sp,
                        color = if (eventProbeResult?.startsWith("发送失败") == true)
                            AppColors.Warning else AppColors.TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * 消费趋势开关。**受控组件**：状态由调用方持有。
 *
 * 控制钱包页那张「消费趋势」统计卡片（`SpendingTrendCard`）的显隐。
 * 跟通知开关一个道理：只控制显隐，不影响账单数据本身。
 *
 * @param checked 当前是否开启（来自调用方的 state，**不要在这里 remember**）
 * @param onChanged 用户翻转后的回调
 */
@Composable
internal fun TrendToggleCard(checked: Boolean, onChanged: (Boolean) -> Unit) {
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("消费趋势", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (checked) "在钱包页显示消费统计图" else "已隐藏钱包页的消费统计图",
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChanged,
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
            )
        }
    }
}

/**
 * 「消费合并」开关。**受控组件**：状态由调用方持有。
 *
 * ## 它解决什么问题
 *
 * 趣智校园按项目拆账：洗浴、洗衣饮水吹风各一套账单。想回答
 * 「我今天到底花了多少」，得把两边加起来。开了这个开关，钱包页的趋势图
 * 显示的就是**合并后**的消费，而不是当前板块那一份。
 *
 * ## 依赖「消费趋势」
 *
 * 合并结果**只喂给趋势图**，趋势图关着的时候合并没有展示出口，
 * 开着只会白拉几倍请求（还要临时切认证参数），所以 [trendOn] 为 false 时禁用。
 *
 * @param trendOn 消费趋势是否开着（由调用方传，决定本开关是否可用）
 * @param checked 用户自己的意愿（存储值 `PrefsHelper.mergeConsume` 的镜像）。
 *   趋势关着时这里可能仍是 true，但显示按 [trendOn] && [checked] 算。
 * @param onChanged 用户翻转后的回调
 */
@Composable
internal fun MergeConsumeToggleCard(
    trendOn: Boolean,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    val effective = TrendMergeLink.displayChecked(trendOn, checked)

    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "消费合并",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trendOn) AppColors.TextPrimary else AppColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !trendOn -> "需要先开启「消费趋势」"
                        effective -> "趋势图显示所有板块合计消费（含洗浴）"
                        else -> "只显示当前板块的消费"
                    },
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = effective,
                enabled = TrendMergeLink.switchEnabled(trendOn),
                onCheckedChange = onChanged,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AppColors.Accent,
                    // 禁用态别做得太显眼，但也别让用户以为能点
                    disabledUncheckedTrackColor = AppColors.Border,
                    disabledUncheckedThumbColor = AppColors.TextSecondary
                )
            )
        }
    }
}

/**
 * 「消费趋势 + 消费合并」两张卡片。
 *
 * ## 为什么两个开关要放在一起、共享 state
 *
 * 它们有依赖关系（合并依赖趋势），而**依赖关系的状态必须在同一处**——
 * 各自 `remember` 会出两种 bug，都真实发生过（2026-10-24 用户实测）：
 *
 * 1. **状态不同步**：趋势关掉后，合并卡片因为父级没重组而不重新求值，
 *    看起来还是开的；退出再进来才变灰
 * 2. **看起来"被自动打开"**：关趋势时如果只改 `trendEnabled`、不动 `mergeConsume`，
 *    用户下次打开趋势就会看到合并也是开的，像是被自动勾上了
 *
 * 所以这里把两个开关的 state 提升到本组件，**任一变化立即写盘 + 同步两边的显示**。
 *
 * ## 关趋势时**真的关掉**合并（不是隐藏）
 *
 * 用户实测后的明确诉求：「我要不想看消费合并的内容需要再一次的关上」——
 * 说明「重开趋势自动恢复合并」是反直觉的。所以关趋势时把
 * `PrefsHelper.mergeConsume` 也置 false，语义变成「趋势没了，合并跟着没了」。
 *
 * @param onChanged 任一开关变化后的回调（让 ViewModel 重算合并数据）
 */
@Composable
internal fun TrendAndMergeCards(onChanged: () -> Unit) {
    // 初值从 Prefs 读一次；之后以这里的 state 为准，**每次都写回 Prefs**
    var trendOn by remember { mutableStateOf(PrefsHelper.trendEnabled) }
    var mergeOn by remember { mutableStateOf(PrefsHelper.mergeConsume) }

    Column {
        TrendToggleCard(checked = trendOn) { on ->
            // 联动规则统一走 TrendMergeLink，别在这里手写 if——测试测的是同一份
            val (newTrend, newMerge) = TrendMergeLink.afterTrendToggle(on, mergeOn)
            trendOn = newTrend
            mergeOn = newMerge
            PrefsHelper.trendEnabled = newTrend
            PrefsHelper.mergeConsume = newMerge
            onChanged()
        }
        Spacer(Modifier.height(10.dp))
        // 紧跟在趋势下面：它俩是依赖关系，放远了用户会以为合并开关点了没反应
        MergeConsumeToggleCard(
            trendOn = trendOn,
            checked = mergeOn
        ) { on ->
            mergeOn = on
            PrefsHelper.mergeConsume = on
            onChanged()
        }
    }
}

@Composable
internal fun NotifyRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColors.TextPrimary, fontSize = 14.sp)
            Text(desc, color = AppColors.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
        )
    }
}

/** 系统设置里本应用的通知页（Android 8+）；更低版本只能进应用详情页 */
internal fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
