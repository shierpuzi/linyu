package com.quzhi.lite.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quzhi.lite.data.ModeOrderPolicy
import com.quzhi.lite.model.WashMode
import com.quzhi.lite.ui.theme.AppColors

/**
 * 下单前的**最终确认**弹窗——两种计费模型共用。
 *
 * ## 为什么必须有这一步
 *
 * | 计费模型 | 点「确认」之后会发生什么 | 能撤销吗 |
 * |---|---|---|
 * | 按量计费 | `openValve` **立刻开阀放水**并开始计费 | 可以关，但已经产生费用 |
 * | 套餐计费 | `modeOrder` **立刻扣套餐全款** | **不能，没有退款接口** |
 *
 * 两者都是「点一下就有真实代价」的动作。而这个 App 是扫码/列表点进来的，
 * 设备可能就在身边、也可能扫错了一台——**让用户亲眼核对设备名**是最后一道防线，
 * 也是官方 App 的做法。
 *
 * ## 两种内容形态
 *
 * - [mode] == null（按量计费）：只显示设备名 + 预扣金额
 * - [mode] != null（套餐计费）：设备名 + 套餐列表（名称 / 金额 / 时长）
 *
 * @param deviceName 设备显示名（**确认弹窗的核心**，用户要认的就是这个）
 * @param deviceType 类型名（热水器 / 洗衣机 / 吹风机…），副标题用
 * @param preDeductYuan 预扣金额（元，两位小数字符串）。null 表示不显示
 * @param mode 套餐计费时选中的套餐；按量计费传 null
 * @param isSubmitting 正在提交（按钮转圈 + 置灰，防双击）
 * @param onConfirm 用户点了「确认开启」
 * @param onDismiss 取消 / 点外部
 */
@Composable
fun ConfirmStartDialog(
    deviceName: String,
    deviceType: String,
    preDeductYuan: String? = null,
    mode: WashMode? = null,
    isSubmitting: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isPackage = mode != null

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        shape = RoundedCornerShape(22.dp),
        containerColor = AppColors.Card,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (isPackage) "确认套餐" else "确认开启",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 设备名：这次确认的重点，字号最大、居中、允许换行 ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = AppColors.Accent.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AppColors.Accent.copy(alpha = 0.35f)),
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            deviceType,
                            fontSize = 11.sp,
                            color = AppColors.TextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            deviceName.ifBlank { "未知设备" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isPackage) {
                    // ── 套餐明细：名称 / 金额 / 时长 ──
                    Text(
                        "请确认设备名和套餐，是否开启该设备",
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AppColors.Card,
                        border = BorderStroke(1.dp, AppColors.Border),
                        shadowElevation = 0.dp
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            ConfirmRow("套餐", mode.modeName ?: "套餐")
                            ConfirmRow("金额", "¥${ModeOrderPolicy.formatPrice(mode.modeMoney)}")
                            ConfirmRow("时长", "${mode.modeTimeMinute ?: "—"} 分钟")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "确认后立即扣款，不可退款、无法中途停止",
                        fontSize = 12.sp,
                        color = AppColors.Warning,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "请确认设备名，是否开启该设备",
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    if (!preDeductYuan.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("预扣 ", fontSize = 13.sp, color = AppColors.TextSecondary)
                            Text(
                                "¥$preDeductYuan",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.Accent
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "开启后开始计费，用完请记得关闭",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (!isSubmitting) onConfirm() },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    disabledContainerColor = AppColors.Accent.copy(alpha = 0.4f)
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在开启…", color = Color.White)
                } else {
                    Text(if (isPackage) "确认开启" else "确认开启",
                        color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = AppColors.TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = AppColors.TextPrimary)
    }
}
