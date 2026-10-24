package com.quzhi.lite.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
 * 设备 emoji → 图标资源。**没有对应图标的返回 null**，调用方回退去画 emoji。
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
 * ⚠️ `🪥` 和 `♨️`/`❄️` 暂无矢量图：设备头像处是**实心**风格，项目里现有的
 * `ic_widget_faucet` 是描边风格，混在同一列会明显不协调，所以仍回退画 emoji。
 */
fun deviceIconRes(emoji: String?): Int? = when (emoji) {
    "🚿" -> R.drawable.ic_device_shower
    "🪥" -> R.drawable.ic_device_sink
    // 洗衣 / 吹风（新增取值，见 DeviceInfo.typeEmoji）
    "🧺" -> R.drawable.ic_device_washer
    "💨" -> R.drawable.ic_device_dryer
    else -> null
}

/**
 * 设备头像里的那个图形。
 *
 * 有图标就用图标（`ic_device_*`），没有就原样画 emoji ——
 * 所以饮水机那几个目前还是彩色 emoji，和花洒/牙刷的图标会**混在同一列里**。
 * 这是「只换这两个」的必然结果，等饮水机也有图了再收口。
 *
 * ⚠️ 图标固定用**白色**：它压在设备类型色的圆底上（洗手台橙、饮水机绿、其余蓝），
 * 单色图标跟底色同色就糊了，白色对比度最稳。
 */
@Composable
fun DeviceGlyph(
    emoji: String,
    emojiSize: TextUnit,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    val res = deviceIconRes(emoji)
    if (res != null) {
        Icon(
            painter = painterResource(res),
            contentDescription = null,
            tint = Color.White,
            modifier = modifier.size(iconSize)
        )
    } else {
        Text(emoji, fontSize = emojiSize, modifier = modifier)
    }
}
