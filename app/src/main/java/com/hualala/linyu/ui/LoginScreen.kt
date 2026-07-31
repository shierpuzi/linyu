package com.hualala.linyu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hualala.linyu.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: (String) -> Unit
) {
    var animated by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (animated) 1f else 0.8f, tween(400))

    LaunchedEffect(viewModel.loginResult) {
        val result = viewModel.loginResult
        if (result != null && result.isSuccess) {
            onLoginSuccess(viewModel.phone)
            viewModel.resetResult()
        }
    }

    LaunchedEffect(Unit) { animated = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A73E8),
                        Color(0xFF0D47A1),
                        Color(0xFF051D40)
                    )
                )
            )
    ) {
        // 装饰圆
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = (-120).dp, y = (-80).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.04f))
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 60.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .offset(y = (-50).dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 90.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Logo
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "logo",
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(18.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text("淋 浴", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Hualala · 校园热水", fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(20.dp))

            // 登录卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("欢迎回来", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                    Text("请使用校园账号登录", fontSize = 13.sp, color = Color.Gray,
                        modifier = Modifier.padding(bottom = 20.dp, top = 4.dp))

                    // 密码/验证码切换
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { viewModel.switchMode() }) {
                            Text(if (viewModel.isSmsMode) "密码登录" else "验证码登录",
                                color = Color(0xFF1A73E8), fontSize = 13.sp)
                        }
                    }

                    OutlinedTextField(
                        value = viewModel.phone,
                        onValueChange = { viewModel.phone = it },
                        label = { Text("手机号") },
                        leadingIcon = { Icon(Icons.Default.Phone, null, tint = Color(0xFF1A73E8)) },
                        trailingIcon = { if (viewModel.phone.isNotEmpty()) TextButton(onClick = { viewModel.phone = "" }) { Text("✕", color = Color.Gray) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1A73E8),
                            focusedLabelColor = Color(0xFF1A73E8)
                        )
                    )
                    Spacer(Modifier.height(14.dp))

                    if (viewModel.isSmsMode) {
                        // 验证码输入
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = viewModel.smsCode,
                                onValueChange = { viewModel.smsCode = it },
                                label = { Text("验证码") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF1A73E8),
                                    focusedLabelColor = Color(0xFF1A73E8)
                                )
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = { viewModel.sendSmsCode() },
                                enabled = !viewModel.isSendingCode && viewModel.countdown == 0,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1A73E8),
                                    disabledContainerColor = Color(0xFF90CAF9)
                                )
                            ) {
                                Text(if (viewModel.countdown > 0) "${viewModel.countdown}s" else "发送",
                                    fontSize = 13.sp, color = Color.White)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = viewModel.password,
                            onValueChange = { viewModel.password = it },
                            label = { Text("密码") },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Color(0xFF1A73E8)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF1A73E8),
                                focusedLabelColor = Color(0xFF1A73E8)
                            )
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.login() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = !viewModel.isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A73E8),
                            disabledContainerColor = Color(0xFF90CAF9)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        if (viewModel.isLoading) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("登 录", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    viewModel.errorMessage?.let { msg ->
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFEBEE)
                        ) {
                            Text(msg, modifier = Modifier.padding(12.dp),
                                color = Color(0xFFC62828), fontSize = 13.sp,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
