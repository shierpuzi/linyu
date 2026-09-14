package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun WalletScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { 
        viewModel.loadBills()
    }
    var detailBill by remember { mutableStateOf<BillDTO?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(PrefsHelper.manualBalance.ifEmpty { "0" }) }

    var pullRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.pullRefresh()
            scope.launch {
                kotlinx.coroutines.delay(1000)
                pullRefreshing = false
            }
        }
    )

    // 计算累计消费
    val balanceTime = PrefsHelper.manualBalanceTime
    val newBills = viewModel.billList.filter {
        val timeStr = it.consumeBillDTO.consumeDate.replace(" ", "T")
        try { java.time.LocalDateTime.parse(timeStr).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() > balanceTime }
        catch (_: Exception) { false }
    }
    val totalNewSpent = newBills.sumOf { (it.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0) }
    val initialBalance = PrefsHelper.manualBalance.toDoubleOrNull() ?: 0.0
    val displayBalance = if (PrefsHelper.manualBalance.isEmpty()) 0.0 else initialBalance - totalNewSpent

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullState)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("一卡通余额", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("账户余额 (元)", color = AppColors.TextSecondary, fontSize = 14.sp)
                        TextButton(onClick = { showEditDialog = true }) {
                            Text("手动填写", color = AppColors.Accent, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("¥ %.2f".format(displayBalance),
                        fontSize = 36.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("仅通过初始金额和账单进行估算，并非真实余额",
                        color = AppColors.TextSecondary, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("账单", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text("最近20次消费记录", fontSize = 12.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.height(12.dp))

            if (viewModel.isLoadingBills) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.Accent, strokeWidth = 2.dp)
                }
            } else if (viewModel.billList.isEmpty()) {
                Text("暂无账单数据", color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.billList.forEach { bill ->
                        BillCard(bill) { detailBill = bill.consumeBillDTO }
                    }
                }
            }

            Spacer(Modifier.height(100.dp)) // 底部留出悬浮导航栏空间
        }

        PullRefreshIndicator(
            refreshing = pullRefreshing,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = AppColors.SolidSurface, // 不透明，避免半透明叠加导致内外不一致
            contentColor = AppColors.Accent
        )
    }

    // 手动余额编辑弹窗
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("手动填写余额", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请输入一卡通当前余额", color = AppColors.TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("此操作仅用于本地估算，不会修改一卡通真实余额",
                        color = AppColors.TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { v ->
                            // 最多9位整数 + 2位小数
                            if (v.isEmpty() || v.matches(Regex("\\d{0,9}(\\.\\d{0,2})?"))) editValue = v
                        },
                        label = { Text("余额 (元)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    PrefsHelper.manualBalance = editValue
                    PrefsHelper.manualBalanceTime = System.currentTimeMillis()
                    showEditDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
    }

    // 账单详情弹窗
    if (detailBill != null) {
        val dto = detailBill!!
        AlertDialog(
            onDismissRequest = { detailBill = null },
            title = { Text("账单详情", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val name = dto.description.substringAfter(":")
                    val dname = if (name.isNotEmpty()) DeviceInfo.formatDeviceName(name) else dto.description
                    WDetailRow("设备名", dname)
                    WDetailRow("设备类型", dto.deviceTypeLabel)
                    WDetailRow("消费金额", "-¥ ${dto.consumeMoney}")
                    WDetailRow("消费时间", dto.consumeDate.take(19))
                    WDetailRow("订单号", dto.orderId)
                }
            },
            confirmButton = { Button(onClick = { detailBill = null }) { Text("确定") } }
        )
    }
}

@Composable
private fun BillCard(bill: BillItem, onClick: () -> Unit) {
    val dto = bill.consumeBillDTO
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${dto.deviceEmoji} ${dto.displayDesc}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                val tc = when {
                    dto.isDrinkingWater -> Color(0xFF10B981)          // 饮水机：绿色
                    dto.deviceTypeLabel.contains("洗手台") -> Color(0xFFFF9800)
                    else -> AppColors.Accent
                }
                Text(dto.deviceTypeLabel, fontSize = 12.sp, color = tc)
                Spacer(Modifier.height(2.dp))
                Text(dto.consumeDate.take(16), fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Text("-¥ ${dto.consumeMoney}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Danger)
        }
    }
}

@Composable
private fun WDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.TextSecondary, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, fontSize = 14.sp)
    }
}
