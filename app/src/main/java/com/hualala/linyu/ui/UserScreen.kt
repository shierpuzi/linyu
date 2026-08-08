package com.hualala.linyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.updateUseCodeStatusSafe
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.launch

@Composable
fun UserScreen(phone: String, onLogout: () -> Unit, viewModel: MainViewModel? = null) {
    LaunchedEffect(Unit) { viewModel?.loadUseCode() }
    val useCode = viewModel?.useCodeData
    var showSchoolDialog by remember { mutableStateOf(false) }
    var schoolInput by remember { mutableStateOf(PrefsHelper.schoolName) }
    var themeMode by LocalThemeMode.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的账号", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            IconButton(onClick = {
                themeMode = if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
                PrefsHelper.themeMode = themeMode.name
            }) {
                Text(
                    if (themeMode == ThemeMode.DARK) "🌙" else "☀️",
                    fontSize = 20.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(20.dp)) {
                InfoRow("姓名", PrefsHelper.userName.ifEmpty { "未设置" })
                Spacer(Modifier.height(10.dp))
                InfoRow("手机号", phone)
                Spacer(Modifier.height(10.dp))
                InfoRow("项目 ID", PrefsHelper.projectId)
                Spacer(Modifier.height(10.dp))
                EditableInfoRow("学校", PrefsHelper.schoolName) { showSchoolDialog = true }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 绑定寝室
        var boundRoomInput by remember { mutableStateOf(PrefsHelper.boundRoom) }
        var boundRoom by remember { mutableStateOf(PrefsHelper.boundRoom) }
        var showRoomPicker by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("绑定寝室", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (boundRoom.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("🏠 $boundRoom", fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            boundRoom = ""; boundRoomInput = ""
                            PrefsHelper.boundRoom = ""
                        }) {
                            Text("取消绑定", color = AppColors.Danger, fontSize = 12.sp)
                        }
                    }
                } else {
                    Text("未绑定寝室，设备列表将显示全部设备",
                        color = AppColors.TextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = boundRoomInput,
                    onValueChange = { boundRoomInput = it },
                    label = {
                        Text("输入关键词（如：3号楼南/320）",
                            fontSize = 13.sp, color = AppColors.TextSecondary)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        if (boundRoomInput.trim().isNotEmpty()) {
                            val v = boundRoomInput.trim()
                            boundRoom = v; PrefsHelper.boundRoom = v
                            viewModel?.toastMessage = "已绑定寝室：$v"
                        }
                    }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                        Text("保存")
                    }
                    OutlinedButton(onClick = { showRoomPicker = true },
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Text("从附近设备选")
                    }
                }
            }
        }

        // 从附近设备选择寝室
        if (showRoomPicker) {
            val devices = viewModel?.nearbyDevices?.mapNotNull {
                val n = it.deviceInfo?.deviceName ?: it.name
                viewModel.extractLocationFromDevice(n).takeIf { it.isNotBlank() }
            }?.distinct() ?: emptyList()
            AlertDialog(
                onDismissRequest = { showRoomPicker = false },
                title = { Text("选择设备位置", fontWeight = FontWeight.Bold) },
                text = {
                    if (devices.isEmpty()) {
                        Text("附近暂无设备，请先到热水器旁扫描后再试", color = AppColors.TextSecondary)
                    } else {
                        LazyColumn(Modifier.height(300.dp)) {
                            items(devices) { loc ->
                                TextButton(
                                    onClick = {
                                        boundRoom = loc
                                        boundRoomInput = loc
                                        PrefsHelper.boundRoom = loc
                                        showRoomPicker = false
                                        viewModel?.toastMessage = "已绑定寝室：$loc"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(loc, color = AppColors.TextPrimary, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showRoomPicker = false }) { Text("关闭") } }
            )
        }

        Spacer(Modifier.height(24.dp))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("使用码", color = AppColors.TextSecondary, fontSize = 13.sp)
                    Text("后三位为手机号后三位", color = AppColors.TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                val code = useCode?.useCode
                if (!code.isNullOrEmpty()) {
                    val prefix = code.dropLast(3)
                    val suffix = code.takeLast(3)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = AppColors.TextPrimary)) {
                                append(prefix)
                            }
                            withStyle(SpanStyle(color = AppColors.Accent)) {
                                append(suffix)
                            }
                        },
                        fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp
                    )
                } else {
                    Text("加载中...", fontSize = 28.sp, fontWeight = FontWeight.Black,
                        color = AppColors.TextPrimary, letterSpacing = 6.sp)
                }
                Spacer(Modifier.height(4.dp))
                var localCodeOn by remember { mutableStateOf(useCode?.useCodeStatus == 1) }
                LaunchedEffect(useCode?.useCodeStatus) { useCode?.useCodeStatus?.let { localCodeOn = it == 1 } }
                val scope = rememberCoroutineScope()
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (localCodeOn) "使用码已开启" else "使用码已关闭",
                        color = AppColors.TextSecondary, fontSize = 12.sp)
                    Switch(checked = localCodeOn, onCheckedChange = { newVal ->
                        localCodeOn = newVal
                        scope.launch {
                            try {
                                NetworkModule.apiService.updateUseCodeStatusSafe(
                                    status = if (newVal) 1 else 0,
                                    auth = NetworkModule.authFields()
                                )
                            } catch (e: Exception) {
                                localCodeOn = !newVal
                                val msg = e.message ?: ""
                                viewModel?.toastMessage = when {
                                    msg.contains("Unable to resolve host", ignoreCase = true) ||
                                    msg.contains("No address associated", ignoreCase = true) ||
                                    msg.contains("Failed to connect", ignoreCase = true) ->
                                        "网络连接失败，请检查网络设置"
                                    else -> "操作失败，请重试"
                                }
                            }
                        }
                    }, colors = SwitchDefaults.colors(checkedThumbColor = AppColors.Accent,
                        checkedTrackColor = AppColors.Accent.copy(alpha = 0.5f)))
                }
                Text("在热水器物理键盘上输入此码",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)) {
            Text("退出登录")
        }

        Spacer(Modifier.height(8.dp))
        Text("Hualala v1.0 · 哗啦啦啦啦让我去淋浴~", color = AppColors.TextSecondary, fontSize = 12.sp)
    }

    if (showSchoolDialog) {
        AlertDialog(
            onDismissRequest = { showSchoolDialog = false },
            title = { Text("修改学校名称", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = schoolInput,
                    onValueChange = { schoolInput = it },
                    label = { Text("学校名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    PrefsHelper.schoolName = schoolInput
                    showSchoolDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSchoolDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.TextSecondary)
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}

@Composable
private fun EditableInfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                Text("修改", fontSize = 12.sp, color = AppColors.Accent)
            }
        }
    }
}
