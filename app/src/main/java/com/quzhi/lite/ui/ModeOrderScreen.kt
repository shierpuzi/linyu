package com.quzhi.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.data.ModeOrderPolicy
import com.quzhi.lite.model.WashMode
import com.quzhi.lite.ui.theme.AppColors
import com.quzhi.lite.ui.theme.LocalThemeMode
import com.quzhi.lite.ui.theme.ThemeMode
import com.quzhi.lite.utils.BackgroundManager
import com.quzhi.lite.utils.BackgroundState

/**
 * 套餐计费设备（洗衣机 / 吹风机）的下单 + 使用页。
 *
 * ## 页面不区分设备类型
 *
 * 只认「这个设备的套餐列表」，渲染逻辑完全一样：吹风机 3 档、洗衣机 4 档，
 * 都来自 `GET /device/mode/list`。**新接入的套餐设备不用改这个文件。**
 *
 * ## 两个阶段
 *
 * | 阶段 | 触发 | 内容 |
 * |---|---|---|
 * | 选套餐 | [pending] == null | 套餐卡片列表 + 「确认下单 ¥x.xx」 |
 * | 使用中 | [pending] != null | 剩余倒计时 + 「到点自动结束」 |
 *
 * ## ⚠️ 这个页面**故意没有「停止」按钮**
 *
 * 服务端没有停止/取消/退款接口（实测洗衣机、吹风机都没有），
 * 套餐一发出就扣全款且不可退。放一个点了没用的按钮，
 * 用户会以为能退钱——那比没有按钮更糟。
 *
 * @param modes 套餐列表（可能为空，表示还没加载到/加载失败）
 * @param selectedModeId 当前选中的套餐
 * @param onSelectMode 选中某档
 * @param pending 本机记着的进行中订单；null = 还没下单
 * @param occupiedReason 设备被**别人**占用的原因（含剩余时间）。非 null 时**禁用下单**
 * @param remainingSec 剩余秒数（使用中才有效）
 * @param placing 正在提交下单（按钮要转圈 + 置灰，防双击扣两份钱）
 * @param errorText 错误提示（余额不足 / 网络异常等）；null 表示没错误
 * @param onConfirmOrder 确认下单（**唯一的花钱入口**）
 * @param onRefresh 使用中时「刷新」——核对订单是否真的结束了
 * @param onBack 返回（使用中时只是离开页面，不结束订单）
 */
@Composable
fun ModeOrderScreen(
    deviceName: String,
    emoji: String,
    modes: List<WashMode>,
    selectedModeId: Int?,
    walletYuan: String? = null,
    onSelectMode: (Int) -> Unit,
    pending: PendingView? = null,
    occupiedReason: String? = null,
    placing: Boolean = false,
    errorText: String? = null,
    /** 是否已收藏该设备。右上角心形按钮的填充状态 */
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onConfirmOrder: () -> Unit,
    onRefresh: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val themeMode = LocalThemeMode.current.value
    val isDark = themeMode == ThemeMode.DARK
    val customBg = BackgroundState.config(BackgroundManager.SCOPE_SHOWER).enabled

    val bgModifier = if (customBg) Modifier
    else Modifier.background(
        if (isDark) Brush.verticalGradient(listOf(Color(0xFF1A237E), Color(0xFF283593), Color(0xFF3949AB)))
        else Brush.verticalGradient(listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB), Color(0xFF90CAF9)))
    )

    Box(modifier = Modifier.fillMaxSize().then(bgModifier)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!customBg) {
                DeviceGlyph(emoji, emojiSize = 64.sp, iconSize = 64.dp)
                Spacer(Modifier.height(8.dp))
            }
            Text(deviceName, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))

            if (pending == null) {
                ModePicker(
                    modes = modes,
                    selectedModeId = selectedModeId,
                    walletYuan = walletYuan,
                    occupiedReason = occupiedReason,
                    placing = placing,
                    errorText = errorText,
                    onSelectMode = onSelectMode,
                    onConfirmOrder = onConfirmOrder
                )
            } else {
                ModeInUse(
                    pending = pending,
                    errorText = errorText,
                    onRefresh = onRefresh
                )
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = AppColors.TextPrimary
            )
        }

        // 右上角收藏 —— 与按量计费页保持同一个位置和视觉，
        // 用户不该因为设备"是套餐还是按量"而找不到收藏按钮
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
        ) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏设备",
                tint = if (isFavorite) Color(0xFFE53935) else AppColors.TextPrimary
            )
        }
    }
}

/**
 * 使用中要展示的数据——只放界面需要的，不暴露 `PendingModeOrder` 本身。
 */
data class PendingView(
    /** 套餐名，如「电吹风模式一」；拿不到时显示「套餐」 */
    val modeName: String?,
    /** 已付金额（元，两位小数字符串） */
    val paidYuan: String,
    /** 剩余秒数 */
    val remainingSec: Int,
    /** 是否大概率已到点（界面据此提示「请点刷新核对」） */
    val likelyFinished: Boolean
)

// ── 阶段一：选套餐 ───────────────────────────────────────────────────

@Composable
private fun ModePicker(
    modes: List<WashMode>,
    selectedModeId: Int?,
    walletYuan: String?,
    occupiedReason: String?,
    placing: Boolean,
    errorText: String?,
    onSelectMode: (Int) -> Unit,
    onConfirmOrder: () -> Unit
) {
    val selected = modes.firstOrNull { it.modeId == selectedModeId }
    // 被占用时整页不能下单，套餐也点不动——避免用户选好一档才发现用不了
    val occupied = occupiedReason != null

    if (modes.isEmpty()) {
        // 还没拉到 / 拉失败。**不能**显示「暂无套餐」了事——用户会以为是这台机器不支持
        Text("正在获取套餐…", fontSize = 14.sp, color = AppColors.TextSecondary)
        if (occupied) {
            Spacer(Modifier.height(12.dp))
            OccupiedBanner(occupiedReason!!)
        }
        if (errorText != null) {
            Spacer(Modifier.height(12.dp))
            ErrorBanner(errorText)
        }
        return
    }

    // 占用提示放在最上面：跳过详情框之后，这是用户唯一能知道「机器是别人的」的地方
    if (occupied) {
        OccupiedBanner(occupiedReason!!)
        Spacer(Modifier.height(16.dp))
    }

    Text("选择套餐", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        color = AppColors.TextPrimary)
    Spacer(Modifier.height(12.dp))

    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        modes.forEach { mode ->
            val id = mode.modeId ?: return@forEach
            val checked = id == selectedModeId
            ModeRow(
                mode = mode,
                checked = checked,
                enabled = !placing && !occupied,
                onClick = { onSelectMode(id) }
            )
        }
    }

    if (errorText != null) {
        Spacer(Modifier.height(12.dp))
        ErrorBanner(errorText)
    }

    Spacer(Modifier.height(20.dp))

    // 下单前必须让用户看清「多少钱 / 多久」——这是不可退款扣款
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = AppColors.Card,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥", fontSize = 16.sp, color = AppColors.Accent,
                    modifier = Modifier.padding(bottom = 6.dp))
                Text(
                    ModeOrderPolicy.formatPrice(selected?.modeMoney),
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent
                )
                Text(
                    " / ${selected?.modeTimeMinute ?: "—"} 分钟",
                    fontSize = 14.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "立即扣款 · 不可退款 · 到点自动结束",
                fontSize = 12.sp, color = AppColors.Warning
            )
            if (walletYuan != null) {
                Spacer(Modifier.height(4.dp))
                Text("当前余额 ¥$walletYuan", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onConfirmOrder,
        // 已经在下单就不能再点：每点一次都可能是一份钱
        // 被占用也不能点：那是别人的机器，扣的是自己的钱
        enabled = !placing && !occupied && selected != null,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Accent,
            disabledContainerColor = AppColors.Accent.copy(alpha = 0.4f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        if (placing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("正在下单…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        } else if (occupied) {
            Text("设备使用中，无法下单", color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold)
        } else {
            Text(
                "确认下单 ¥${ModeOrderPolicy.formatPrice(selected?.modeMoney)}",
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 设备被**别人**占用时的提示。
 *
 * 用 Warning 色（和 ErrorBanner 同一套视觉），因为这对用户是「现在不能做这件事」，
 * 不同于红色那种「出错了」。
 */
@Composable
private fun OccupiedBanner(reason: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.Warning.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AppColors.Warning.copy(alpha = 0.5f)),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⏳", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("设备正在被使用", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.Warning)
                if (reason.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(reason, fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: WashMode,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (checked) AppColors.Accent.copy(alpha = 0.12f) else AppColors.Card,
        border = BorderStroke(
            if (checked) 2.dp else 1.dp,
            if (checked) AppColors.Accent else AppColors.Border
        ),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = checked,
                onClick = null, // 交给外层 selectable，避免双重触发
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = AppColors.Accent)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(mode.modeName ?: "套餐", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                mode.modeDesc?.takeIf { it.isNotBlank() && it != mode.modeName }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("¥${ModeOrderPolicy.formatPrice(mode.modeMoney)}",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)
                Text("${mode.modeTimeMinute ?: "—"} 分钟",
                    fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

// ── 阶段二：使用中 ───────────────────────────────────────────────────

@Composable
private fun ModeInUse(pending: PendingView, errorText: String?, onRefresh: () -> Unit) {
    val min = pending.remainingSec / 60
    val sec = pending.remainingSec % 60
    val countdown = if (min > 0) "${min}分${sec}秒" else "${sec}秒"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = AppColors.Card,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (pending.likelyFinished) "套餐时间已到" else "正在使用中",
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (pending.likelyFinished) AppColors.TextSecondary else AppColors.Accent
            )
            Spacer(Modifier.height(12.dp))

            if (pending.likelyFinished) {
                // 到点只是「大概率」：服务端到点关停且不通知我们，本地只能按时间推
                Text("如果设备已经停了，点下面刷新核对",
                    fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$min", fontSize = 44.sp, fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary)
                    Text(" 分 ", fontSize = 16.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Text("%02d".format(sec), fontSize = 44.sp, fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary)
                    Text(" 秒", fontSize = 16.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("剩余时间", fontSize = 12.sp, color = AppColors.TextSecondary)
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pending.modeName ?: "套餐", fontSize = 14.sp, color = AppColors.TextPrimary)
                Text(" · ¥${pending.paidYuan}", fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = AppColors.Accent)
            }
            Spacer(Modifier.height(4.dp))
            Text("已扣款，到点自动结束", fontSize = 12.sp, color = AppColors.TextSecondary)
        }
    }

    if (errorText != null) {
        Spacer(Modifier.height(12.dp))
        ErrorBanner(errorText)
    }

    Spacer(Modifier.height(20.dp))

    // 没有「结束」按钮：服务端没有这个接口。只提供刷新核对
    OutlinedButton(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, AppColors.Accent)
    ) {
        Text(
            if (pending.likelyFinished) "刷新核对（设备已结束请点这里）" else "刷新状态",
            color = AppColors.Accent, fontSize = 15.sp
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "本设备按套餐计费，无法中途停止或退款",
        fontSize = 11.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center
    )
}

// ── 共用 ─────────────────────────────────────────────────────────────

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.Warning.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AppColors.Warning.copy(alpha = 0.4f)),
        shadowElevation = 0.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            fontSize = 13.sp, color = AppColors.Warning
        )
    }
}
