package com.quzhi.lite.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import com.quzhi.lite.QrScanActivity
import com.quzhi.lite.R
import com.quzhi.lite.data.BalanceEstimator
import com.quzhi.lite.data.FavoriteDevice
import com.quzhi.lite.data.ModeOrderPolicy
import com.quzhi.lite.model.DeviceInfo
import com.quzhi.lite.model.NearbyDevice
import com.quzhi.lite.ui.theme.AppColors
import com.quzhi.lite.utils.BackgroundManager
import com.quzhi.lite.utils.BackgroundState
import com.quzhi.lite.utils.PrefsHelper
import com.quzhi.lite.utils.ScanPermission
import com.quzhi.lite.utils.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(phone: String, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    // 是否已获得扫描权限。做成状态：用户授权后要立刻反映到界面上
    var hasScanPerm by remember { mutableStateOf(ScanPermission.granted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val ok = perms.entries.all { it.value }
        hasScanPerm = ok
        if (ok) viewModel.startScan() else viewModel.toastMessage = ScanPermission.deniedMessage()
    }

    // 扫描前才要权限：已授权直接扫，没授权才弹系统对话框。
    // 这样 Android 12+ 的用户全程只会看到「附近的设备」，不会看到定位
    fun scanWithPermission() {
        if (ScanPermission.granted(context)) viewModel.startScan()
        else permissionLauncher.launch(ScanPermission.required)
    }

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
        } else {
            // 扫码页自己弹的说明（没相机权限、相机起不来）挂在 error extra 上。
            // 以前这里只看 RESULT_OK，这些消息全被丢掉了——用户看到的就是
            // 点了扫码、黑屏一闪、回到主页，什么提示都没有。
            result.data?.getStringExtra(QrScanActivity.EXTRA_ERROR)
                ?.takeIf { it.isNotEmpty() }
                ?.let { viewModel.toastMessage = it }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshWallet()
        viewModel.loadBills()
        // 余额和学校名是账号级的，跟哪个页面无关。以前只在「我的」页面拉，
        // 结果先进主页/钱包页的时候 `campusBalance` 还是 null——界面就当拿不到
        // 真实余额，继续显示「手动填写」和「并非真实余额」。三个入口都拉一次。
        viewModel.loadCampusBalance()
        viewModel.loadSchoolName()
        // ⚠️ 板块列表必须在这里拉一次。
        //
        // `projects` 为空时 `showProjectSwitcher` 为 false，切换入口就不渲染——
        // 而 `loadProjects()` 就算定义了、没人调用，**编译器也不会报未使用**。
        // 这个调用点曾经丢失过一次，表现是「板块切换入口消失」但代码齐全。
        viewModel.loadProjects()
        // 未支付账单也要在这里拉一次：开阀失败弹窗的「立即补扣」靠它。
        // 只在钱包页拉的话，冷启动直接开阀失败时列表是空的，
        // 弹窗会退化成只有「知道了」——正好把这个功能的主要场景漏掉
        viewModel.loadUnpaidBills()
        viewModel.initManagers(context)
        // 已授权就静默开始扫描（老用户无感知）；没授权不再自动弹窗，
        // 改由界面上的「开启扫描」按钮触发，避免登录完突然被要定位权限
        if (ScanPermission.granted(context)) viewModel.startScan()
        visible = true
    }

    var pullRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.pullRefresh()
            scanWithPermission()
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
        // ⚠️ 用 ViewModel 里的状态，不是 PrefsHelper.boundRoom。
        // 读 pref 不会触发重组，取消绑定后列表不会立刻更新（要等下一次扫描）。
        viewModel.boundRoom
    ) {
        viewModel.nearbyDevices.filter {
            val n = it.deviceInfo?.deviceName ?: it.name
            viewModel.matchesBoundRoom(n)
        }
    }

    // 余额：接口拿得到就用真实的，拿不到才回退估算。含日期解析，开销较大，
    // 所以放在 LazyColumn 之外并用 remember 缓存，避免滚动时 item 反复组合/销毁
    // 导致重新解析日期而掉帧。
    // 账单没到位时返回 null：那时候减数为 0，算出来的就是用户填的初始余额，
    // 直接显示出来会先亮一个错数字再跳变。宁可先显示「—」——
    // 但真实余额不用等账单，它是现成的数字。
    val displayBalance = remember(
        viewModel.billList, viewModel.billsLoaded, viewModel.campusBalance,
        viewModel.walletBalance,
        PrefsHelper.manualBalance, PrefsHelper.manualBalanceTime
    ) {
        // 真实余额（一卡通或钱包）不用等账单，是现成的数字；
        // 只有要走估算时才必须等账单到位，否则减数为 0 会先亮一个错数字。
        if (viewModel.campusBalance != null || viewModel.walletBalance != null || viewModel.billsLoaded)
            BalanceEstimator.estimate(viewModel.billList)
        else null
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Scaffold(
            // 背景应用到「主页」时透明，让最底层的背景图透出来
            containerColor = if (BackgroundState.config(BackgroundManager.SCOPE_HOME).enabled) Color.Transparent else AppColors.Background
        ) { padding ->
            // 页面层级：0 = 首页列表，1 = 设备操作页（套餐 / 按量计费）。
            //
            // 用 AnimatedContent 做**弹性视差左右滑移**，参数与 MainActivity 的三个主 tab
            // 完全一致（dampingRatio=0.78 / stiffness=420）——视觉语言统一，
            // 用户不会觉得「进设备」和「切 tab」是两套交互。
            //
            // 方向：进入设备 = 新页从右滑入（forward）；返回 = 列表从左滑入。
            //
            // ⚠️ 主页分支必须**内联**在这里，不能抽成独立函数：它引用了
            // scanLauncher / context / filteredDevices / hasScanPerm 等一堆局部变量，
            // 抽出去就得把十几个参数一路传下来（试过一次，编译报 12 个 Unresolved）。
            val onDevicePage = viewModel.modeOrderDevice != null ||
                viewModel.meteredScreenOpen ||
                viewModel.isShowering
            AnimatedContent(
                targetState = onDevicePage,
                transitionSpec = {
                    val forward = targetState
                    val springSpec = spring<IntOffset>(dampingRatio = 0.78f, stiffness = 420f)
                    val fadeSpec = spring<Float>(stiffness = 420f)
                    (slideInHorizontally(
                        animationSpec = springSpec,
                        initialOffsetX = { full -> if (forward) full else -full }
                    ) + fadeIn(animationSpec = fadeSpec))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = springSpec,
                                targetOffsetX = { full -> if (forward) -full else full }
                            ) + fadeOut(animationSpec = fadeSpec)
                        )
                },
                label = "DevicePageTransition"
            ) { onDevice ->
            if (onDevice) {
                if (viewModel.modeOrderDevice != null) {
                    val dev = viewModel.modeOrderDevice!!
                    ModeOrderScreen(
                        deviceName = dev.displayName,
                        emoji = dev.typeEmoji,
                        modes = viewModel.modeModes,
                        selectedModeId = viewModel.selectedModeId,
                        walletYuan = viewModel.modeOrderWalletYuan,
                        onSelectMode = { viewModel.selectMode(it) },
                        pending = viewModel.modePending?.let { p ->
                            val now = viewModel.modeOrderTick
                            PendingView(
                                modeName = viewModel.modeModes
                                    .firstOrNull { it.modeId == p.modeId }?.modeName,
                                paidYuan = ModeOrderPolicy.formatPrice(p.modeMoneyMilli),
                                remainingSec = p.remainingSeconds(now),
                                likelyFinished = p.isLikelyFinished(now)
                            )
                        },
                        placing = viewModel.isPlacingModeOrder,
                        occupiedReason = viewModel.modeOccupiedReason,
                        errorText = viewModel.modeOrderError,
                        isFavorite = viewModel.isFavorite(dev.macAddress),
                        onToggleFavorite = { viewModel.toggleFavoriteCurrent() },
                        onConfirmOrder = { viewModel.confirmModeOrder(phone) },
                        onRefresh = { viewModel.refreshModeOrder() },
                        onBack = { viewModel.exitModeOrder() }
                    )
                } else if (viewModel.isShowering) {
                    // 使用中的按量计费设备也走同一套全屏页，保证「开始前」和「开始后」
                    // 是同一个界面语言（同一个按钮位置、同一个收藏入口）。
                    val dev = viewModel.selectedDevice
                    MeteredDeviceScreen(
                        emoji = dev?.typeEmoji ?: "🚿",
                        deviceName = dev?.displayName ?: "正在使用中",
                        typeName = dev?.typeName ?: "",
                        location = dev?.locationOnly ?: "",
                        preDeductYuan = viewModel.showerRemaining.ifBlank {
                            MoneyFormat.format(dev?.withholdMoney ?: 0.0)
                        },
                        inUse = true,
                        // 余额取**这台设备所属板块**的钱包，不是当前板块的。
                        // 用户可能停在洗衣板块却开着洗浴的热水器，拿当前板块的钱包
                        // 就是他报的「余额不准」（显示的是另一个板块的钱）。
                        // 见 MainViewModel.loadDeviceBoardWallet / DeviceBoardPolicy
                        walletYuan = viewModel.deviceBoardWallet?.let { MoneyFormat.format(it) },
                        elapsedSec = viewModel.showerElapsedSec,
                        autoDisConSec = viewModel.autoDisConSec,
                        isStopping = viewModel.isStopping,
                        isFavorite = viewModel.isFavorite(dev?.macAddress),
                        onToggleFavorite = { viewModel.toggleFavoriteCurrent() },
                        onStartClick = {},
                        onStopClick = { viewModel.stopShower() },
                        onBack = { viewModel.minimizeShower() }
                    )
                } else if (viewModel.selectedDevice != null) {
                    // 按量计费「未开始」：同样是全屏页，但按钮是「开始使用」（点了先弹确认框）
                    val dev = viewModel.selectedDevice!!
                    MeteredDeviceScreen(
                        emoji = dev.typeEmoji,
                        deviceName = dev.displayName,
                        typeName = dev.typeName,
                        location = dev.locationOnly,
                        preDeductYuan = MoneyFormat.format(dev.withholdMoney),
                        inUse = false,
                        // 同上：按设备所属板块取余额，不是当前板块
                        walletYuan = viewModel.deviceBoardWallet?.let { MoneyFormat.format(it) },
                        // 每秒轮询来的「被占用」状态：非空时禁用开始按钮并显示横幅
                        occupiedReason = viewModel.modeOccupiedReason,
                        isFavorite = viewModel.isFavorite(dev.macAddress),
                        onToggleFavorite = { viewModel.toggleFavoriteCurrent() },
                        onStartClick = { viewModel.requestStartMetered() },
                        onStopClick = {},
                        onBack = { viewModel.closeMeteredScreen() }
                    )
                }
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
                                    Text("趣智Lite", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                                    // 板块切换入口（绑定 2 个以上项目时才显示）。
                                    // ⚠️ 这里是**唯一**的板块入口：曾经被误删过一次，
                                    // 表现是"洗浴/洗衣饮水吹风切换没了"，但 ViewModel 里
                                    // switchProject 还在——所以只需把入口补回来，逻辑不用动。
                                    ProjectSwitcher(viewModel)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 扫码按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = {
                                            scanLauncher.launch(Intent(context, QrScanActivity::class.java))
                                        }, modifier = Modifier.size(40.dp)) {
                                            Icon(painterResource(R.drawable.ic_qr_code_scanner), null,
                                                tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // 刷新按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = { viewModel.pullRefresh(); scanWithPermission() },
                                            modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Default.Refresh, null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // 收藏的设备 —— 排在「上次使用」前面。
                        // 用户主动加的、杀后台也不丢的列表（「上次使用」只有一个槽位）。
                        if (viewModel.favorites.isNotEmpty()) {
                            item {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("收藏设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                        color = AppColors.TextPrimary)
                                    Text("${viewModel.favorites.size} 台",
                                        color = AppColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                            viewModel.favorites.forEach { fav ->
                                item(key = "fav_${fav.mac}") {
                                    FavoriteDeviceCard(fav) { viewModel.openFavorite(fav) }
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
                            // 「上次使用」也走新流程：点击进对应的操作页，
                            // 不再直接开阀。和收藏项的行为保持一致。
                            item { LastDeviceCard(viewModel, phone) }
                        }

                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("附近设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                                // 余额在 LazyColumn 外已算好并缓存。
                                // 标题带板块名（和钱包页一致）：两个板块钱包独立，
                                // 不写板块用户分不清这个数字属于哪一边。
                                Text(
                                    "${BalanceEstimator.title(viewModel.projectShortLabel(viewModel.currentProjectId))} " +
                                        (displayBalance?.let { "¥${BalanceEstimator.formatMoney(it)}" } ?: "¥ —"),
                                    color = AppColors.TextSecondary, fontSize = 14.sp
                                )
                            }
                        }

                        // 寝室筛选状态
                        if (viewModel.hasBoundRoom) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    // 存的就是寝室键，但老版本存过 `320房` / 完整设备名，
                                    // 过一遍 roomKey 让两种历史值都显示成同一个样子
                                    Text("🏠 已筛选：${DeviceInfo.roomKey(viewModel.boundRoom) ?: viewModel.boundRoom}",
                                        color = AppColors.Accent, fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        viewModel.applyBoundRoom("")
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

                        if (!hasScanPerm) {
                            // 未授权时不自动弹系统对话框，改为显式按钮，用户自己决定何时授权
                            item { ScanPermissionCard { scanWithPermission() } }
                        } else if (filteredDevices.isEmpty() && !viewModel.isScanning) {
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
                            DeviceCard(
                                device = device,
                                onClick = { viewModel.fetchDeviceInfo(device.mac) },
                                // 长按收藏：附近设备只有广播里的 MAC/名字，没有 bigTypeId，
                                // 所以要先把台账查回来再存（收藏必须存完整字段，否则离线渲染不出来）
                                onLongClick = { viewModel.favoriteNearbyDevice(device) }
                            )
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
            } // AnimatedContent
        }
    }

    if (viewModel.showDeviceDetail) {
        val isActive = viewModel.selectedDevice?.snCode?.let { viewModel.isDeviceActive(it) } ?: false
        DeviceDetailDialog(viewModel.selectedDevice, isActive, viewModel.isOwner,
            onDismiss = { viewModel.showDeviceDetail = false },
            onConfirm = { viewModel.startShower(phone) })
    }

    // ── 套餐下单确认弹窗 ──
    //
    // modeOrder 一发出就扣全款且不可退。用户要在这里亲眼核对
    // 设备名 + 套餐 + 金额 + 时长，点了「确认开启」才真正调 commitModeOrder。
    viewModel.pendingConfirmMode?.let { mode ->
        val dev = viewModel.modeOrderDevice
        ConfirmStartDialog(
            deviceName = dev?.displayName ?: "",
            deviceType = dev?.typeName ?: "套餐设备",
            mode = mode,
            isSubmitting = viewModel.isPlacingModeOrder,
            onConfirm = { viewModel.commitModeOrder() },
            onDismiss = { viewModel.dismissConfirmMode() }
        )
    }

    // ── 按量计费「开始使用」确认弹窗 ──
    //
    // 开阀是**立刻出水并开始计费**的动作，不能点一下就执行。
    if (viewModel.pendingConfirmStart) {
        val dev = viewModel.selectedDevice
        ConfirmStartDialog(
            deviceName = dev?.displayName ?: "",
            deviceType = dev?.typeName ?: "用水设备",
            preDeductYuan = dev?.let { MoneyFormat.format(it.withholdMoney) },
            mode = null,
            isSubmitting = viewModel.meteredBusy,
            onConfirm = { viewModel.confirmStartMetered(phone) },
            onDismiss = { viewModel.dismissConfirmStart() }
        )
    }

    // ── 开阀失败 ──
    //
    // 以前 `showerError` 是**只写不读**的：失败时用户只看到「正在开启热水器…」消失，
    // 然后什么都不发生，完全不知道是失败了还是没点上。
    viewModel.showerError?.let { err ->
        // ⚠️ **只有服务端明确拒绝**才可能和欠费有关。网络异常、设备被占用、结果未知
        // 都跟账单无关——把它们也引导到「补扣」，用户会因为一次 WiFi 掉线去白扣钱
        val bills = if (err.kind == ShowerErrorKind.SERVER_REJECTED) viewModel.deductibleBills
                    else emptyList()

        // 金额是服务端给的**字符串**，解析不出来时**不能悄悄按 0 算**：
        // 这张弹窗存在的唯一目的就是「会动钱，再停一下」，数字失真就失去意义了
        val amounts = bills.map { it.consumeMoney?.toDoubleOrNull() }
        val total = amounts.sumOf { it ?: 0.0 }
        val amountKnown = amounts.all { it != null }

        // key 带上 err：错误换了一条时确认态要跟着复位，不然「确认扣款」会
        // 叠在一条新错误上
        var confirming by remember(err) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.clearShowerError() },
            title = {
                Text(
                    if (bills.isEmpty()) "开阀失败" else "开阀失败 · 有未扣账单",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(err.message, color = AppColors.TextPrimary, fontSize = 14.sp)
                    if (bills.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("检测到 ${bills.size} 笔没扣成功的账单：",
                            color = AppColors.Warning, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        bills.forEach { bill ->
                            Text(
                                "· ¥${bill.consumeMoney ?: "?"}   ${bill.consumeDate?.take(16) ?: ""}",
                                color = AppColors.TextSecondary, fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("账户有欠费时服务端会拒绝开阀",
                            color = AppColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                if (bills.isNotEmpty()) {
                    Button(
                        onClick = { confirming = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Warning)
                    ) { Text("立即补扣") }
                } else {
                    TextButton(onClick = { viewModel.clearShowerError() }) { Text("知道了") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearShowerError() }) { Text("关闭") }
            }
        )

        // 扣钱前的二次确认（和钱包页单笔代扣共用同一个组件，文案不会各写一份）
        if (confirming) {
            DeductConfirmDialog(
                amount = if (amountKnown) total else null,
                detail = "共 ${bills.size} 笔",
                onConfirm = {
                    confirming = false
                    viewModel.clearShowerError()
                    viewModel.deductAllUnpaid { s ->
                        viewModel.toastMessage = when {
                            // null = 一笔都没发出去（没有可补的，或被另一个代扣挡住）
                            s == null -> "没有可补扣的账单，或正在处理另一笔代扣"
                            s.unknown > 0 && s.ok == 0 && s.failed == 0 ->
                                "补扣结果未知，请下拉刷新确认后再操作"
                            s.unknown > 0 ->
                                "成功 ${s.ok} 笔、失败 ${s.failed} 笔、${s.unknown} 笔结果未知"
                            s.failed == 0 -> "补扣成功，再试一次开阀"
                            s.ok == 0 -> s.firstFailReason ?: "补扣失败，请到「钱包」页重试"
                            else -> "成功 ${s.ok} 笔、失败 ${s.failed} 笔"
                        }
                    }
                },
                onDismiss = { confirming = false }
            )
        }
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
                    // 蓝牙表那条链路要好几步（连接设备 → 读状态帧 → 下费率 → 写费率包），
                    // 比 4G 表慢得多；给具体进度，别让用户以为卡死了
                    Text(
                        viewModel.bleProgress ?: "正在确认设备是否开启，请稍候",
                        color = AppColors.TextSecondary
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 云端开阀回了「设备不在线」——问用户要不要改用手机蓝牙。
    //
    // 为什么必须问用户：服务端**不提供**任何能区分「云端表 / 蓝牙表」的字段
    // （`smallTypeId` 两校的 4G 表也都是 1；`isBle` 压根不返回），
    // 参考项目也没做这个判断。只有站在设备旁的人才知道答案。
    viewModel.bleRetryDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissBleRetry() },
            title = {
                Text(
                    "要用蓝牙方式开启吗？",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    "「${dev.displayName}」无法通过云端开启。\n\n" +
                        "如果这台设备需要手机蓝牙直连（开启时必须待在设备旁边），" +
                        "可以改用蓝牙方式。\n\n" +
                        "不确定的话：用官方「趣智校园」试一下——" +
                        "需要站到设备旁才能开的就是这一类。",
                    color = AppColors.TextSecondary,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmBleRetry() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("用蓝牙开启") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBleRetry() }) { Text("取消") }
            }
        )
    }

    // 自动关停确认弹窗
    if (viewModel.showAutoCloseDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_device_shower),
                        contentDescription = null,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("热水器已自动关闭", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_device_shower),
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
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
                        // 金额可能是 null（结算没拿到）——那是「还不知道」，
                        // 不能显示成 ¥0.00，否则用户以为没花钱
                        Text(
                            viewModel.autoCloseConsumed?.let { "本次消费：${MoneyFormat.withSymbol(it)}" }
                                ?: "消费金额稍后可在账单中查看",
                            fontWeight = FontWeight.Bold, color = AppColors.Accent
                        )
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

/**
 * 板块（项目）切换入口 —— 首页标题下方那行。
 *
 * ## 还原自作者原版（2026-10-24 #24）
 *
 * 这个组件曾被误删，我按"看起来更合理"的方式重写过一版（单板块显示项目名、
 * 加「当前」标记、加在途订单说明），但**那是重写不是还原**。
 * 本版逐行对照旧 APK 反编译结果重写，行为与作者原版一致：
 *
 * - **单板块**：显示 `"趣智Lite"` 占位副标题（原为作者的 `"Hualala"`，本项目已改名）
 * - **多板块**：Box + 圆角 8dp 可点 Row + 项目名（Accent 色、Medium 字重、14sp）
 *   + `KeyboardArrowDown` 图标（18dp、Accent 色）+ DropdownMenu
 * - **切换中**：项目名前面显示 12dp 转圈（1.5dp 粗）+ 6dp 间距
 *
 * ⚠️ 不要再"优化"它。作者原版就是作者的选择，改之前先确认不是风格偏好。
 */
@Composable
private fun ProjectSwitcher(viewModel: MainViewModel) {
    if (!viewModel.showProjectSwitcher) {
        Text("趣智Lite", color = AppColors.TextSecondary, fontSize = 14.sp)
        return
    }

    // 展开状态。用 remember 保存，点外面 / 选中后收起
    var expanded by remember { mutableStateOf(false) }
    val label = viewModel.projectLabel(viewModel.currentProjectId)

    // 原版这里是 ColumnScope 里的 Box(Modifier.align(TopStart))；独立函数里没有那个作用域，
    // 而外层本来就是 Column，默认左对齐，去掉 align 视觉一致。
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !viewModel.isSwitchingProject) { expanded = true }
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 切换中：转圈 + 6dp 间距（原版顺序：spinner 在项目名前面）
            if (viewModel.isSwitchingProject) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = AppColors.Accent,
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = AppColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = AppColors.Accent
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            viewModel.projects.forEach { project ->
                val pid = project.projectId ?: return@forEach
                DropdownMenuItem(
                    text = { Text(viewModel.projectLabel(pid)) },
                    onClick = {
                        expanded = false
                        viewModel.switchProject(pid)
                    }
                )
            }
        }
    }
}

@Composable
private fun ActiveOrderCard(order: com.quzhi.lite.model.ActiveOrder, viewModel: MainViewModel, phone: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.ActiveBg),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                // 底色统一由 DeviceInfo.colorForEmoji 决定，UI 里不再各写一份 when
                // （之前就是两处各写一份，加洗衣/吹风时漏改，头像顶着默认蓝）
                .background(com.quzhi.lite.model.DeviceInfo.colorForEmoji(order.deviceEmoji)),
                contentAlignment = Alignment.Center) {
                // 花洒/牙刷/洗衣机/吹风走矢量图标，其余（饮水机）仍画 emoji，见 DeviceGlyph
                DeviceGlyph(order.deviceEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                // 和下面的「上次使用」卡片一样用 TailEllipsisText：
                // 设备名有辨识度的部分在**结尾**（房号），而且必须单行——
                // 普通 Text 放不下会折成两三行，把这张卡片撑变形。
                TailEllipsisText(
                    text = order.deviceName.ifEmpty { "热水器" },
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
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

/**
 * 「上次使用设备」卡片。
 *
 * ## 行为改过（2026-10-24 #21）
 *
 * 原来右下角有一个「开始」按钮，**点了直接开阀**——那是按量计费设备的
 * 危险捷径（误触就出水计费）。现在去掉按钮，整张卡片可点，
 * 点击后走和收藏/扫码一样的入口 `routeAfterDeviceInfo`：
 * 套餐设备进选套餐页、按量计费进全屏操作页（还要再确认一次才开阀）。
 */
@Composable
private fun LastDeviceCard(viewModel: MainViewModel, phone: String) {
    Card(
        Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { viewModel.openLastDevice(phone) }
        ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                // 同上：固定色映射在 DeviceInfo.colorForEmoji 里，不跟随背景主题变化
                .background(com.quzhi.lite.model.DeviceInfo.colorForEmoji(viewModel.lastDeviceEmoji)),
                contentAlignment = Alignment.Center) {
                DeviceGlyph(viewModel.lastDeviceEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                // 用尾部优先省略：设备名和 MAC 都是后半段才有辨识度
                // （房号在末尾、MAC 的厂商前缀是固定的）。同时彻底不换行——
                // 有的手机系统字体调到很大，普通 Text 会折成两三行把卡片撑变形。
                TailEllipsisText(
                    text = viewModel.lastDeviceName.ifEmpty { "热水器" },
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                TailEllipsisText(
                    text = "MAC: ${viewModel.lastDeviceMac}",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 收藏设备卡片。
 *
 * 和 [LastDeviceCard] 视觉一致（用户不该觉得这是两种东西），差别只有：
 * 点整张卡片就进去（不需要单独一个「开始」按钮）。
 *
 * 数据全部来自本地快照（[FavoriteDevice]），**离线也能渲染**。
 */
@Composable
private fun FavoriteDeviceCard(fav: FavoriteDevice, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(fav.toDeviceInfo().typeColor),
                contentAlignment = Alignment.Center
            ) {
                DeviceGlyph(fav.emoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                TailEllipsisText(
                    text = fav.displayName.ifEmpty { fav.rawName },
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    fav.typeName + " · " + if (fav.toDeviceInfo().isPackageBilling) "按套餐" else "按量计费",
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 未授予扫描权限时的引导卡片。
 * Android 12+ 只会要「附近的设备」；Android 11 及以下系统强制要定位权限，文案里说明清楚。
 */
@Composable
private fun ScanPermissionCard(onGrant: () -> Unit) {
    val needLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("${if (needLocation) "🔍" else "📶"} 开启扫描，发现附近设备",
                fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                if (needLocation)
                    "Android 11 及以下系统规定：扫描蓝牙设备必须授予定位权限。" +
                        "授权后仅用于发现附近的热水器。"
                else
                    "需要「附近的设备」权限才能扫描附近的热水器。",
                color = AppColors.TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("授予权限并扫描")
            }
            Spacer(Modifier.height(6.dp))
            Text("也可以直接用右上角 📷 扫码绑定设备，无需任何权限",
                color = AppColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(device: NearbyDevice, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
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
            .combinedClickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = AppColors.Accent.copy(alpha = 0.25f)),
                onClick = onClick,
                // 长按 = 收藏 / 取消收藏。
                // 不加心形按钮的原因：附近设备列表可能有几十台（公共澡堂），
                // 每张卡都塞按钮会很挤；收藏是低频操作，藏起来正合适。
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(device.typeColor),
                contentAlignment = Alignment.Center) {
                DeviceGlyph(device.typeEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
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