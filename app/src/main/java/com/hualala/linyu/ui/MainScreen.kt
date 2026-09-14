package com.hualala.linyu.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.hualala.linyu.QrScanActivity
import com.hualala.linyu.model.NearbyDevice
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState
import com.hualala.linyu.utils.PrefsHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(phone: String, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms -> if (perms.entries.all { it.value }) viewModel.startScan() }

    // 扫码绑定设备
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val sn = result.data?.getStringExtra(QrScanActivity.EXTRA_SN_CODE)
            if (!sn.isNullOrEmpty()) {
                viewModel.scanBind(sn)
            } else {
                viewModel.toastMessage = "未识别到有效二维码"
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshWallet()
        viewModel.loadBills()
        viewModel.initManagers(context)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN); perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())
        visible = true
    }

    var pullRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.pullRefresh()
            // 1秒后隐藏顶部指示器
            scope.launch {
                kotlinx.coroutines.delay(1000)
                pullRefreshing = false
            }
        }
    )

    // 按寝室筛选设备：用 remember 缓存，仅当设备列表或绑定寝室变化时才重新过滤
    val filteredDevices = remember(
        viewModel.nearbyDevices.toList(),
        PrefsHelper.boundRoom
    ) {
        viewModel.nearbyDevices.filter {
            val n = it.deviceInfo?.deviceName ?: it.name
            viewModel.matchesBoundRoom(n)
        }
    }

    // 余额估算：含日期解析，开销较大。放在 LazyColumn 之外并用 remember 缓存，
    // 避免滚动时 item 反复组合/销毁导致重新解析日期而掉帧。
    val displayBalance = remember(viewModel.billList, PrefsHelper.manualBalance, PrefsHelper.manualBalanceTime) {
        val balanceTime = PrefsHelper.manualBalanceTime
        val newBills = viewModel.billList.filter {
            val timeStr = it.consumeBillDTO.consumeDate.replace(" ", "T")
            try {
                java.time.LocalDateTime.parse(timeStr)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() > balanceTime
            } catch (_: Exception) { false }
        }
        val totalNewSpent = newBills.sumOf { (it.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0) }
        val initialBalance = PrefsHelper.manualBalance.toDoubleOrNull() ?: 0.0
        if (PrefsHelper.manualBalance.isEmpty()) 0.0 else initialBalance - totalNewSpent
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Scaffold(
            // 背景应用到「主页」时透明，让最底层的背景图透出来
            containerColor = if (BackgroundState.config(BackgroundManager.SCOPE_HOME).enabled) Color.Transparent else AppColors.Background
        ) { padding ->
            if (viewModel.isShowering) {
                ShowerScreen(
                    emoji = viewModel.selectedDevice?.typeEmoji ?: "🚿",
                    statusText = viewModel.selectedDevice?.statusText ?: "正在沐浴中",
                    location = viewModel.selectedDevice?.locationOnly ?: "",
                    remaining = viewModel.showerRemaining,
                    elapsedSec = viewModel.showerElapsedSec,
                    autoDisConSec = viewModel.autoDisConSec,
                    isStopping = viewModel.isStopping,
                    onStopClick = { viewModel.stopShower() },
                    onMinimizeClick = { viewModel.minimizeShower() }
                )
            } else {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().pullRefresh(pullState).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item { Spacer(Modifier.height(8.dp)) }

                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("淋浴", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                                    Text("Hualala", color = AppColors.TextSecondary, fontSize = 14.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 扫码按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = {
                                            scanLauncher.launch(Intent(context, QrScanActivity::class.java))
                                        }, modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Outlined.QrCodeScanner, null,
                                                tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // 刷新按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = { viewModel.pullRefresh() },
                                            modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Default.Refresh, null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // 使用中的设备（多个）
                        if (viewModel.activeOrders.isNotEmpty()) {
                            item {
                                Text("使用中的设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                            }
                            viewModel.activeOrders.forEach { order ->
                                item { ActiveOrderCard(order, viewModel, phone) }
                            }
                        } else if (viewModel.lastDeviceMac.isNotEmpty()) {
                            item {
                                Text("上次使用设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                            }
                            item { LastDeviceCard(viewModel, phone) }
                        }

                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("附近设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                                // 余额在 LazyColumn 外已算好并缓存
                                Text("余额 ¥%.2f".format(displayBalance), color = AppColors.TextSecondary, fontSize = 14.sp)
                            }
                        }

                        // 寝室筛选状态
                        if (viewModel.hasBoundRoom) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🏠 已筛选：${PrefsHelper.boundRoom}",
                                        color = AppColors.Accent, fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        PrefsHelper.boundRoom = ""
                                        viewModel.toastMessage = "已取消寝室筛选"
                                    }, contentPadding = PaddingValues(0.dp)) {
                                        Text("取消筛选", fontSize = 12.sp, color = AppColors.TextSecondary)
                                    }
                                }
                            }
                        }

                        if (viewModel.isScanning) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                                        Spacer(Modifier.width(8.dp))
                                        Text("扫描中...", fontSize = 13.sp, color = AppColors.TextSecondary)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LegendDot(AppColors.Success, "强")
                                        Spacer(Modifier.width(8.dp))
                                        LegendDot(AppColors.Warning, "中")
                                        Spacer(Modifier.width(8.dp))
                                        LegendDot(AppColors.Danger, "弱")
                                    }
                                }
                            }
                        }

                        if (filteredDevices.isEmpty() && !viewModel.isScanning) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (viewModel.hasBoundRoom) "未找到寝室内的热水器"
                                        else "未发现热水器",
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                        }

                        items(filteredDevices, key = { it.mac }) { device ->
                            DeviceCard(device) { viewModel.fetchDeviceInfo(device.mac) }
                        }

                        item { Spacer(Modifier.height(110.dp)) }
                    }

                    PullRefreshIndicator(
                        refreshing = pullRefreshing,
                        state = pullState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        backgroundColor = AppColors.SolidSurface, // 不透明，避免半透明叠加导致内外不一致
                        contentColor = AppColors.Accent
                    )
                }
            }
        }
    }

    if (viewModel.showDeviceDetail) {
        val isActive = viewModel.selectedDevice?.snCode?.let { viewModel.isDeviceActive(it) } ?: false
        DeviceDetailDialog(viewModel.selectedDevice, isActive, viewModel.isOwner,
            onDismiss = { viewModel.showDeviceDetail = false },
            onConfirm = { viewModel.startShower(phone) })
    }

    // 开始使用中的加载提示（开阀确认需要几秒）
    if (viewModel.isStartingShower) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在开启热水器...", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = AppColors.Accent)
                    Spacer(Modifier.width(12.dp))
                    Text("正在确认设备是否开启，请稍候", color = AppColors.TextSecondary)
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 自动关停确认弹窗
    if (viewModel.showAutoCloseDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text("⚠️ 热水器已自动关闭", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🚿 ", fontSize = 16.sp)
                        Text(viewModel.autoCloseDeviceName, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                    }
                    Spacer(Modifier.height(6.dp))
                    val sec = viewModel.autoCloseElapsed
                    val t = if (sec / 60 > 0) "${sec / 60}分${sec % 60}秒" else "${sec}秒"
                    Text("⏱ 已用 $t", color = AppColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    if (viewModel.autoCloseLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                            Spacer(Modifier.width(6.dp))
                            Text("结算中...", color = AppColors.TextSecondary)
                        }
                    } else {
                        Text("本次消费：¥%.2f".format(viewModel.autoCloseConsumed),
                            fontWeight = FontWeight.Bold, color = AppColors.Accent)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmAutoClose() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("确 认")
                }
            },
            dismissButton = {}
        )
    }
}

@Composable
private fun ActiveOrderCard(order: com.hualala.linyu.model.ActiveOrder, viewModel: MainViewModel, phone: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.ActiveBg),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(
                    when (order.deviceEmoji) {
                        "🪥" -> Color(0xFFFFCC80)
                        "❄️", "♨️", "🚰" -> Color(0xFF10B981)
                        else -> AppColors.Accent
                    }
                ),
                contentAlignment = Alignment.Center) { Text(order.deviceEmoji, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(order.deviceName.ifEmpty { "热水器" },
                    fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.ActiveBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("使用中", color = AppColors.Warning, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("点击恢复订单", color = AppColors.TextSecondary, fontSize = 12.sp)
                }
            }
            Button(onClick = {
                viewModel.lastDeviceSnCode = order.snCode
                viewModel.lastDeviceMac = order.deviceMac
                viewModel.startLastDevice(phone)
            }, shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) { Text("恢复") }
        }
    }
}

@Composable
private fun LastDeviceCard(viewModel: MainViewModel, phone: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(
                    when (viewModel.lastDeviceEmoji) {
                        "🪥" -> Color(0xFFFFCC80)
                        "❄️", "♨️", "🚰" -> Color(0xFF10B981)
                        // 固定色，与「附近设备」卡片保持一致；不跟随背景主题色变化
                        else -> Color(0xFF2563EB)
                    }
                ),
                contentAlignment = Alignment.Center) { Text(viewModel.lastDeviceEmoji, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(viewModel.lastDeviceName.ifEmpty { "热水器" },
                    fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("MAC: ${viewModel.lastDeviceMac}", color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = { viewModel.startLastDevice(phone) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) { Text("开始") }
        }
    }
}

@Composable
private fun DeviceCard(device: NearbyDevice, onClick: () -> Unit) {
    val signalColor = when {
        device.rssi >= -70 -> AppColors.Success
        device.rssi >= -85 -> AppColors.Warning
        else -> AppColors.Danger
    }

    // 按下反馈：轻微缩小 + 水波纹
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "DeviceCardPress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(RoundedCornerShape(22.dp)) // 让点击涟漪也贴合卡片圆角
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = AppColors.Accent.copy(alpha = 0.25f)),
                onClick = onClick
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(device.typeColor),
                contentAlignment = Alignment.Center) { Text(device.typeEmoji, fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(device.signalText, color = signalColor, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(device.mac, color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
    }
}
