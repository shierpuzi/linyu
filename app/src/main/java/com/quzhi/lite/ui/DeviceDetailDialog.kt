package com.quzhi.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.model.DeviceInfo
import com.quzhi.lite.ui.theme.AppColors
import com.quzhi.lite.utils.MoneyFormat

@Composable
fun DeviceDetailDialog(
    device: DeviceInfo?,
    isActive: Boolean = false,
    isOwner: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (device == null) return

    val emoji = device.typeEmoji
    val type = when {
        device.isDrinkingWater -> if (device.isHotWater) "饮水（热水）" else "饮水（冷水）"
        device.typeName == "洗手台" -> "洗漱"
        else -> "沐浴"
    }
    val location = device.displayName
    val startText = if (device.isDrinkingWater) "开始接水" else "开始使用"
    // 蓝牙表：App 内**已支持**，走 BleShowerController。
    // 只是要多提醒一句「手机是网关、别走远」——这是这类设备固有的限制，
    // 用户不知道的话很容易开了水然后就关不上了。
    val isBle = device.needsBluetoothControl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 美术统一：设备图标全部改成矢量图，这里不再画 emoji。
                    //
                    // ⚠️ 但这一行**没有底色**，不能用默认的白色 tint（白图标压在
                    //    对话框浅色背景上等于看不见）。DeviceGlyph 加了 tint 参数，
                    //    这里传设备自己的类型色——和首页卡片底色同源，视觉一致。
                    DeviceGlyph(
                        emoji = emoji,
                        emojiSize = 22.sp,
                        iconSize = 24.dp,
                        tint = device.typeColor
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(type, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(location, fontSize = 15.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column {
                DetailRow("类型", device.typeName)
                DetailRow("SN 码", device.snCode)
                DetailRow("MAC 地址", device.macAddress)
                DetailRow("预扣金额", MoneyFormat.withSymbol(device.withholdMoney))
                DetailRow("状态", if (isActive) "使用中" else "空闲")
                if (isBle) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "此设备通过手机蓝牙直连控制：开启后请留在设备旁，" +
                            "走远会断开连接、无法关闭。",
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            val canUse = !isActive || isOwner
            val txt = when {
                isActive && !isOwner -> "他人使用中"
                isActive -> "恢复使用"
                else -> startText
            }
            Button(onClick = { if (canUse) { onConfirm(); onDismiss() } },
                enabled = canUse,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canUse) AppColors.Accent else AppColors.TextSecondary
                )) {
                Text(txt)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
