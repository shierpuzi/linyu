package com.hualala.linyu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.ui.AppBackgroundLayer
import com.hualala.linyu.ui.FloatingPillNavBar
import com.hualala.linyu.ui.LinYuToast
import com.hualala.linyu.ui.LoginScreen
import com.hualala.linyu.ui.MainScreen
import com.hualala.linyu.ui.MainViewModel
import com.hualala.linyu.ui.UserScreen
import com.hualala.linyu.ui.WalletScreen
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.CircularRevealThemeHost
import com.hualala.linyu.ui.theme.LinYuTheme
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState
import com.hualala.linyu.utils.PrefsHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用 edge-to-edge：让内容延伸到状态栏/导航栏下方，
        // 这样自定义背景才能铺满到状态栏（各页面用 statusBarsPadding 自保内容位置）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        PrefsHelper.init(this)
        AppLogger.init(this)
        AppLogger.installCrashHandler(this)
        BackgroundState.refresh() // 恢复自定义背景配置（与深浅模式无关）

        val initialThemeMode = try {
            ThemeMode.valueOf(PrefsHelper.themeMode)
        } catch (_: Exception) {
            ThemeMode.LIGHT
        }
        updateStatusBarColor(initialThemeMode)

        val hasToken = PrefsHelper.isLoggedIn
        if (hasToken) NetworkModule.restoreFromPrefs()

        setContent {
            val themeModeState = remember {
                mutableStateOf(
                    try { ThemeMode.valueOf(PrefsHelper.themeMode) }
                    catch (_: Exception) { ThemeMode.LIGHT }
                )
            }

            // 状态声明在动画容器之外：切换动画会切换容器内的渲染分支，
            // 状态若声明在容器内会被重建（表现为切主题后跳回首页）
            var isLoggedIn by rememberSaveable { mutableStateOf(hasToken) }
            var userPhone by rememberSaveable { mutableStateOf(PrefsHelper.telephone) }
            var currentTab by rememberSaveable { mutableStateOf(0) }
            var showKickedDialog by remember { mutableStateOf(false) }
            var showLogoutConfirm by remember { mutableStateOf(false) }
            val mainViewModel: MainViewModel = viewModel()

            CircularRevealThemeHost(
                themeModeState = themeModeState,
                onThemeChanged = { mode ->
                    // 动画结束：持久化主题
                    PrefsHelper.themeMode = mode.name
                },
                onThemePreview = { mode ->
                    // 内容切换点同步状态栏，避免"慢半拍"
                    updateStatusBarColor(mode)
                }
            ) {

                LaunchedEffect(mainViewModel.kickedOut, isLoggedIn) {
                    if (mainViewModel.kickedOut && !showKickedDialog && isLoggedIn) {
                        showKickedDialog = true
                    }
                }

                // 登录后启动挤号心跳；退到后台就停，避免一直在后台轮询耗电。
                // 用生命周期观察者而不是给 ViewModel 加依赖，省一个库。
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, isLoggedIn) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME ->
                                if (isLoggedIn) mainViewModel.startKickWatch()
                            Lifecycle.Event.ON_PAUSE -> mainViewModel.stopKickWatch()
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    // 冷启动时可能已经处于 RESUMED（登录态是从本地恢复的），
                    // 那样就等不到下一次 ON_RESUME 了，这里补一次
                    if (isLoggedIn &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        mainViewModel.startKickWatch()
                    }
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        mainViewModel.stopKickWatch()
                    }
                }

                // 自定义背景开关变化时刷新状态栏（启用→透明，关闭→恢复纯色）
                val bgHomeEnabled = BackgroundState.config(BackgroundManager.SCOPE_HOME).enabled
                LaunchedEffect(bgHomeEnabled) {
                    updateStatusBarColor(themeModeState.value)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 全局自定义背景（最底层）：洗澡中取「使用页」配置，否则取「主页」配置
                    val bgScope = if (mainViewModel.isShowering) BackgroundManager.SCOPE_SHOWER
                                  else BackgroundManager.SCOPE_HOME
                    AppBackgroundLayer(bgScope)

                    if (!isLoggedIn) {
                        LoginScreen(onLoginSuccess = { phone ->
                            // 开一次全新会话：清掉上一轮被挤号留下的标志与在途请求，
                            // 否则登录进去会立刻又被弹出去，得反复登第二次
                            mainViewModel.beginSession()
                            userPhone = phone; isLoggedIn = true; currentTab = 0
                        })
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 三大主页面弹性视差左右滑移（对齐 MudLife 的交互）
                            AnimatedContent(
                                targetState = currentTab,
                                transitionSpec = {
                                    val forward = targetState > initialState
                                    val springSpec = spring<IntOffset>(dampingRatio = 0.78f, stiffness = 420f)
                                    val fadeSpec = spring<Float>(stiffness = 420f)
                                    (slideInHorizontally(
                                        animationSpec = springSpec,
                                        initialOffsetX = { fullWidth -> if (forward) fullWidth else -fullWidth }
                                    ) + fadeIn(animationSpec = fadeSpec))
                                        .togetherWith(
                                            slideOutHorizontally(
                                                animationSpec = springSpec,
                                                targetOffsetX = { fullWidth -> if (forward) -fullWidth else fullWidth }
                                            ) + fadeOut(animationSpec = fadeSpec)
                                        )
                                },
                                label = "PageSpringTransition"
                            ) { tab ->
                                when (tab) {
                                    0 -> MainScreen(phone = userPhone, viewModel = mainViewModel)
                                    1 -> WalletScreen(viewModel = mainViewModel)
                                    2 -> UserScreen(phone = userPhone, viewModel = mainViewModel,
                                        onLogout = { showLogoutConfirm = true })
                                }
                            }
                            // 悬浮胶囊导航栏
                            if (!mainViewModel.isShowering) {
                                FloatingPillNavBar(
                                    currentTab = currentTab,
                                    onTabSelected = { currentTab = it },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }
                        }
                    }

                    // Toast 和弹窗放在 Box 内部最上层
                    LinYuToast(
                        message = mainViewModel.toastMessage,
                        onDismiss = { mainViewModel.toastMessage = null }
                    )

                    if (showKickedDialog) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("账号在别处登录", fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                            text = { Text("当前设备已被强制下线", textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            confirmButton = {
                                Button(onClick = {
                                    showKickedDialog = false
                                    // skipNetwork：loginCode 已失效，只清本地，不再发请求，避免重登后又触发挤号
                                    mainViewModel.stopShower(skipNetwork = true)
                                    // 注意这里不要顺手把 kickedOut 置回 false：
                                    // 保持 true 直到下次 beginSession()，期间任何残留请求再报"登录失效"
                                    // 也会被 kickOut() 的重复触发保护挡住，不会重复弹窗
                                    PrefsHelper.clear()
                                    isLoggedIn = false; userPhone = ""
                                }, modifier = Modifier.fillMaxWidth()) { Text("确定") }
                            }
                        )
                    }

                    if (showLogoutConfirm) {
                        AlertDialog(
                            onDismissRequest = { showLogoutConfirm = false },
                            title = { Text("确认退出") },
                            text = { Text(if (mainViewModel.isShowering)
                                "当前正在洗澡中，退出登录不会自动停止热水器。是否退出？" else "确定退出登录？") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLogoutConfirm = false
                                    mainViewModel.logout()
                                    PrefsHelper.clear(); isLoggedIn = false; userPhone = ""
                                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } }
                        )
                    }
                }
            }
        }
    }

    private fun updateStatusBarColor(themeMode: ThemeMode) {
        val isDarkMode = themeMode == ThemeMode.DARK

        // 启用自定义背景时状态栏透明，让背景图延伸到状态栏；
        // 否则与页面 Background 一致（Light #F1F5F9 / Dark #0E131D）
        window.statusBarColor = when {
            BackgroundState.config(BackgroundManager.SCOPE_HOME).enabled -> android.graphics.Color.TRANSPARENT
            isDarkMode -> android.graphics.Color.parseColor("#0E131D")
            else -> android.graphics.Color.parseColor("#F1F5F9")
        }

        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
        }
    }
}
