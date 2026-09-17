package com.hualala.linyu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import com.hualala.linyu.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.ui.theme.AppColors

/**
 * 悬浮胶囊底栏：单一高亮滑块跟随选中项，弹簧阻尼产生轻微"果冻"回弹。
 * 玻璃质感由半透明底色 + 微光描边 + 阴影构成。
 *
 * 交互：**点击**某个 tab 即切换，滑块以弹簧动画滑动到位。
 */
@Composable
fun FloatingPillNavBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val isDark = AppColors.isDark

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 22.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (isDark) Color(0xB81A202C) else Color(0xD8FFFFFF),
            border = BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x18000000)),
            shadowElevation = 10.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.width(260.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                val itemWidth = maxWidth / 3

                // 滑块位移：与 MudLife 相同的果冻弹簧动画
                val animatedOffset by animateDpAsState(
                    targetValue = itemWidth * currentTab,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
                    label = "SpringPillOffset"
                )

                // 高亮滑块
                Surface(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(itemWidth).height(42.dp),
                    shape = RoundedCornerShape(22.dp),
                    // 跟随强调色（会随自定义背景提取的主题色变化）。
                    //
                    // ⚠️ 深色模式以前写死成 #A8C7FA，注释还写着「跟随强调色」——
                    // 结果自定义背景提取出来的主题色只在浅色下生效，一到深色就变回那个默认蓝。
                    // 现在两边都用 AppColors.Accent，深浅只影响下面图标文字的取色。
                    color = AppColors.Accent,
                    shadowElevation = 2.dp
                ) {}

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NavBarTabItem(Modifier.weight(1f), "首页", rememberVectorPainter(Icons.Default.Home), currentTab == 0, isDark) {
                        if (currentTab != 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTabSelected(0)
                        }
                    }
                    NavBarTabItem(Modifier.weight(1f), "账单", painterResource(R.drawable.ic_receipt_long), currentTab == 1, isDark) {
                        if (currentTab != 1) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTabSelected(1)
                        }
                    }
                    NavBarTabItem(Modifier.weight(1f), "我的", rememberVectorPainter(Icons.Default.Person), currentTab == 2, isDark) {
                        if (currentTab != 2) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTabSelected(2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBarTabItem(
    modifier: Modifier = Modifier,
    label: String,
    // 用 Painter 而不是 ImageVector：账单图标已经换成项目自己的 vector drawable，
    // 不再从 material-icons-extended 取（那个库太大）
    icon: Painter,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    // 选中项的文字/图标压在滑块上，取色得看**滑块本身的明暗**——
    // 滑块现在是强调色，可能来自自定义背景提取，什么颜色都有可能，
    // 写死深蓝/白都可能糊成一片。按亮度挑黑或白。
    val activeColor = if (AppColors.Accent.luminance() > 0.5f) Color(0xFF0F172A) else Color.White
    val inactiveColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else inactiveColor,
        animationSpec = tween(200),
        label = "TabContentColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.04f else 1.0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 400f),
        label = "TabScale"
    )
    // 与 MudLife 一致：无点击水波纹、无按压缩放，仅靠滑块弹簧滑动作为反馈
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .scale(scale),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
