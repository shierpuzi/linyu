package com.hualala.linyu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState

/**
 * **按量计费设备**（热水器 / 洗手台 / 饮水机）的全屏操作页。
 *
 * ## 取代原来的详情弹窗
 *
 * 以前点设备卡片只弹一个小对话框，信息量很小、也不好看。现在改成全屏页，
 * 复用使用页的视觉语言（同一套背景、同一个 `DeviceGlyph`、同样的卡片风格），
 * 但多了一个「**未开始**」状态：
 *
 * | 状态 | 显示 | 按钮 |
 * |---|---|---|
 * | 未开始（`inUse == false`） | 设备信息 + 预扣金额 + 说明 | 「开始使用」→ **再弹确认框** |
 * | 使用中（`inUse == true`） | 已用时长 + 自动关停倒计时 | 「结束使用」 |
 *
 * ## ⚠️ 「开始使用」不能直接开阀
 *
 * 按量计费的开阀是**立刻出水并开始计费**的动作。所以点它只负责
 * **弹出 [ConfirmStartDialog]**，真正的 `startShower` 在用户确认后才执行。
 * 这个分工写在 `MainViewModel.requestStartMetered()` / `confirmStartMetered()` 里。
 *
 * @param inUse 是否正在使用。false = 未开始状态
 * @param startBusy 正在开阀（确认后到开阀完成的窗口期，按钮要转圈）
 * @param isFavorite 是否已收藏
 * @param onToggleFavorite 点右上角收藏
 */
@Composable
fun MeteredDeviceScreen(
    emoji: String,
    deviceName: String,
    typeName: String,
    location: String,
    preDeductYuan: String,
    inUse: Boolean,
    elapsedSec: Int = 0,
    autoDisConSec: Int = 0,
    isStopping: Boolean = false,
    startBusy: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
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

    fun fmtTime(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

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
                Spacer(Modifier.height(10.dp))
            }

            // 状态标题：未开始 / 使用中，一眼能区分
            Text(
                if (inUse) "正在使用" else "准备就绪",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (inUse) AppColors.Accent else AppColors.TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(typeName, fontSize = 13.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                deviceName.ifBlank { "未知设备" },
                fontSize = 15.sp,
                color = AppColors.TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            if (location.isNotBlank() && location != deviceName) {
                Spacer(Modifier.height(2.dp))
                Text(location, fontSize = 12.sp, color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(28.dp))

            if (inUse) {
                // ── 使用中：计时 + 预扣 + 自动关停倒计时 ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AppColors.Card,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.18f else 0.6f)),
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("已用 ${fmtTime(elapsedSec)}", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = AppColors.Accent)
                        Spacer(Modifier.height(6.dp))
                        Text("💧 已预扣 ¥$preDeductYuan", fontSize = 13.sp,
                            color = AppColors.TextSecondary)
                        Text("计费以设备显示为准", fontSize = 11.sp,
                            color = AppColors.TextSecondary)
                    }
                }

                if (autoDisConSec > 0) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = AppColors.Warning.copy(alpha = 0.12f),
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⏳", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("闲置约 ${fmtTime(autoDisConSec)} 后自动关闭",
                                fontSize = 13.sp, color = AppColors.Warning)
                        }
                    }
                }
            } else {
                // ── 未开始：预扣说明 ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AppColors.Card,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.18f else 0.6f)),
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("预扣 ", fontSize = 14.sp, color = AppColors.TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp))
                            Text("¥$preDeductYuan", fontSize = 30.sp,
                                fontWeight = FontWeight.Bold, color = AppColors.Accent)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("开启后开始计费，用完请记得关闭",
                            fontSize = 12.sp, color = AppColors.TextSecondary,
                            textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            if (inUse) {
                Button(
                    onClick = onStopClick,
                    enabled = !isStopping,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935),
                        disabledContainerColor = Color(0xFFE53935).copy(alpha = 0.4f)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    if (isStopping) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("正在关闭…", color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    } else {
                        Text("结 束 使 用", color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    }
                }
            } else {
                Button(
                    onClick = onStartClick,
                    enabled = !startBusy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        disabledContainerColor = AppColors.Accent.copy(alpha = 0.4f)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    if (startBusy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("正在开启…", color = Color.White, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold)
                    } else {
                        Text("开 始 使 用", color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("点开会先让你确认设备名", fontSize = 11.sp,
                    color = AppColors.TextSecondary, textAlign = TextAlign.Center)
            }
        }

        // 左上角返回
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

        // 右上角收藏 —— 两种计费都要有，所以这个按钮在两个页面里都出现
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
