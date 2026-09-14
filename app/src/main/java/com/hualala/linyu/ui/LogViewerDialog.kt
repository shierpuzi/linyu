package com.hualala.linyu.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.io.File

/**
 * 运行日志查看器：显示最近日志，右侧带滚动条，支持清空与导出分享（无需 adb）。
 */
@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(AppLogger.getLogs()) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 固定高度（屏幕 85%），避免日志数量变化时弹窗高度"突然伸展"
    val config = LocalConfiguration.current
    val dialogHeight = (config.screenHeightDp * 0.85f).dp

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.SolidSurface,
            modifier = Modifier.fillMaxWidth().height(dialogHeight)
        ) {
            Column(Modifier.fillMaxSize()) {
                // 标题栏（右上角关闭）
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("运行日志 (${logs.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = AppColors.TextPrimary)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = AppColors.TextSecondary)
                    }
                }
                HorizontalDivider()

                // 日志内容 + 右侧滚动条
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (logs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无日志", color = AppColors.TextSecondary)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize()
                                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
                                .verticalScroll(scrollState)
                        ) {
                            logs.forEach { line ->
                                Text(
                                    line,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                        // 自绘可拖动滚动条（Android Compose 无内置 VerticalScrollbar）
                        BoxWithConstraints(
                            modifier = Modifier.align(Alignment.CenterEnd)
                                .width(20.dp).fillMaxHeight().padding(vertical = 8.dp)
                        ) {
                            val thumbHeight = 40.dp
                            val maxTravel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
                            val maxTravelPx = with(density) { maxTravel.toPx() }
                            val progress = if (scrollState.maxValue > 0)
                                scrollState.value.toFloat() / scrollState.maxValue else 0f
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .width(20.dp)
                                    .height(thumbHeight)
                                    .offset(y = maxTravel * progress)
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        state = rememberDraggableState { delta ->
                                            if (maxTravelPx > 0f) {
                                                val ratio = delta / maxTravelPx
                                                val target = (scrollState.value + ratio * scrollState.maxValue)
                                                    .toInt().coerceIn(0, scrollState.maxValue)
                                                scope.launch { scrollState.scrollTo(target) }
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    Modifier.width(4.dp).fillMaxHeight()
                                        .background(
                                            AppColors.TextSecondary.copy(alpha = 0.45f),
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                // 底部操作
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { AppLogger.clear(); logs = emptyList() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("清空") }
                    Button(
                        onClick = { exportLogs(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                    ) { Text("导出/分享", color = Color.White) }
                }
            }
        }
    }
}

/** 通过 FileProvider 导出日志，调起系统分享（微信/QQ 等） */
fun exportLogs(context: Context) {
    try {
        val file = AppLogger.logFile()
        if (file != null && file.exists()) {
            shareFile(context, file)
        } else {
            // 文件不存在时用内存日志兜底
            val tmp = File(context.cacheDir, "linyu_log_${System.currentTimeMillis()}.txt")
            tmp.writeText(AppLogger.getLogs().joinToString("\n"))
            shareFile(context, tmp)
        }
    } catch (e: Exception) {
        AppLogger.e("导出日志失败", e)
    }
}

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享日志"))
}
