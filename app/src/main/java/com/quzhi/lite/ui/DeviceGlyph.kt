package com.quzhi.lite.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.R

/**
 * 设备 emoji → 图标资源。
 *
 * ## 为什么数据层还是 emoji
 *
 * 设备类型在全链路是**emoji 字符串**：`DeviceInfo.typeEmoji` 算出来之后会写进
 * `PrefsHelper.lastDeviceEmoji`，还会序列化进小组件的 `widgetBillJson` /
 * `widgetNearbyJson` 快照。如果直接把 emoji 字面量换成图标 id，**老用户设备上
 * 存着的旧值就全认不出来了**，会一律回退成默认设备类型。
 *
 * 所以这里只做「**渲染时映射**」：数据层一个字节不动，老数据零迁移。
 *
 * ## 现在覆盖全部设备类型（美术统一，不再回退画 emoji）
 *
 * 以前饮水机那几个没有矢量图，会和别的设备图标混在同一列里。
 * 现在每种设备都有 `ic_device_*`，所以本函数**不再返回 null**：
 * 未知值兜底给热水器图标，至少显示成一台设备，而不是空白或彩色 emoji。
 */
fun deviceIconRes(emoji: String?): Int = when (emoji) {
    "🪥" -> R.drawable.ic_device_sink
    "🧺" -> R.drawable.ic_device_washer
    "💨" -> R.drawable.ic_device_dryer
    // 饮水机三种取值（热水 ♨️ / 冷水 ❄️ / 未知 🚰）共用同一个饮水机图标：
    // 26dp 单色图标塞不下冷热差异，而冷热已经由文案「正在接热水/凉水」表达
    "♨️", "❄️", "🚰" -> R.drawable.ic_device_faucet
    else -> R.drawable.ic_device_shower
}

/**
 * 设备头像里的那个图形。
 *
 * 全部走矢量图标。`emojiSize` 保留只是为了不改各处调用签名（历史原因），
 * 现在只有 [iconSize] 生效。
 *
 * ⚠️ 图标固定用**白色**：它压在设备类型色的圆底上（洗手台橙、饮水机绿、其余蓝），
 * 单色图标跟底色同色就糊了，白色对比度最稳。
 *
 * ⚠️ 如果用在**没有底色**的地方（比如对话框标题行），不能直接用它——
 * 白色图标压在浅色背景上等于看不见，那种场合要用 `tintedGlyph` 或显式传色。
 */
@Composable
fun DeviceGlyph(
    emoji: String,
    emojiSize: TextUnit,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Icon(
        painter = painterResource(deviceIconRes(emoji)),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(iconSize)
    )
}
