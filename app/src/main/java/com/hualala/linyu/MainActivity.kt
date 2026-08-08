package com.hualala.linyu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.ui.LinYuToast
import com.hualala.linyu.ui.LoginScreen
import com.hualala.linyu.ui.MainScreen
import com.hualala.linyu.ui.MainViewModel
import com.hualala.linyu.ui.UserScreen
import com.hualala.linyu.ui.WalletScreen
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LinYuTheme
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.PrefsHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsHelper.init(this)

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

            CompositionLocalProvider(LocalThemeMode provides themeModeState) {
            LinYuTheme {
                // 在 LinYuTheme 内部观察主题变化，实时更新状态栏
                val currentThemeMode by themeModeState
                SideEffect {
                    updateStatusBarColor(currentThemeMode)
                }

                var isLoggedIn by rememberSaveable { mutableStateOf(hasToken) }
                var userPhone by rememberSaveable { mutableStateOf(PrefsHelper.telephone) }
                var currentTab by rememberSaveable { mutableStateOf(0) }
                var showKickedDialog by remember { mutableStateOf(false) }
                var showLogoutConfirm by remember { mutableStateOf(false) }
                val mainViewModel: MainViewModel = viewModel()

                LaunchedEffect(mainViewModel.kickedOut, isLoggedIn) {
                    if (mainViewModel.kickedOut && !showKickedDialog && isLoggedIn) {
                        showKickedDialog = true
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!isLoggedIn) {
                        LoginScreen(onLoginSuccess = { phone ->
                            userPhone = phone; isLoggedIn = true; currentTab = 0
                        })
                    } else {
                        Scaffold(
                            bottomBar = {
                                if (!mainViewModel.isShowering) {
                                    NavigationBar(containerColor = AppColors.Card) {
                                        val nc = NavigationBarItemDefaults.colors(
                                            selectedIconColor = AppColors.Accent,
                                            selectedTextColor = AppColors.Accent,
                                            indicatorColor = AppColors.Accent.copy(alpha = 0.1f))
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Home, "主页") }, label = { Text("主页") },
                                            selected = currentTab == 0, onClick = { currentTab = 0 }, colors = nc)
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.ShoppingCart, "钱包") }, label = { Text("钱包") },
                                            selected = currentTab == 1, onClick = { currentTab = 1 }, colors = nc)
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Person, "我的") }, label = { Text("我的") },
                                            selected = currentTab == 2, onClick = { currentTab = 2 }, colors = nc)
                                    }
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                when (currentTab) {
                                    0 -> MainScreen(phone = userPhone, viewModel = mainViewModel)
                                    1 -> WalletScreen(viewModel = mainViewModel)
                                    2 -> UserScreen(phone = userPhone, viewModel = mainViewModel,
                                        onLogout = { showLogoutConfirm = true })
                                }
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
                                    showKickedDialog = false; mainViewModel.kickedOut = false
                                    // skipNetwork：loginCode 已失效，只清本地，不再发请求，避免重登后又触发挤号
                                    mainViewModel.stopShower(skipNetwork = true); PrefsHelper.clear()
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
    }

    private fun updateStatusBarColor(themeMode: ThemeMode) {
        val isDarkMode = themeMode == ThemeMode.DARK

        window.statusBarColor = if (isDarkMode) {
            android.graphics.Color.parseColor("#0D1117")
        } else {
            android.graphics.Color.parseColor("#F6F8FA")
        }

        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
        }
    }
}
