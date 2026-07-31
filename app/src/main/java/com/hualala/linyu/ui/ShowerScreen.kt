package com.hualala.linyu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun ShowerScreen(
    emoji: String,
    statusText: String,
    location: String,
    remaining: String,
    elapsedSec: Int,
    onStopClick: () -> Unit
) {
    val minutes = elapsedSec / 60
    val seconds = elapsedSec % 60
    val timeText = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    val themeMode = LocalThemeMode.current.value
    val isDark = themeMode == ThemeMode.DARK

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Brush.verticalGradient(listOf(Color(0xFF1A237E), Color(0xFF283593), Color(0xFF3949AB)))
                else Brush.verticalGradient(listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB), Color(0xFF90CAF9)))
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(emoji, fontSize = 72.sp)

            Spacer(Modifier.height(8.dp))

            Text(statusText, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)

            Spacer(Modifier.height(4.dp))

            Text(location, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)

            Spacer(Modifier.height(32.dp))

            // 预扣金额卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = AppColors.Card,
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💧 已预扣 ¥$remaining", fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = AppColors.Accent)
                    Spacer(Modifier.height(4.dp))
                    Text("计费以热水器显示为准", fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 计时器
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Card.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏱", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("已用 $timeText", fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                }
            }

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onStopClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text("结 束 使 用", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }

            Spacer(Modifier.height(16.dp))

            Text("费率: 0.041元/升 · 设备: ${location.takeLast(6)}",
                fontSize = 11.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}
