# 🚿 淋浴 (LinYu)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

趣智校园第三方 Android 客户端，用于控制校园热水器（淋浴设备）。相比官方 App，提供更简洁的界面和更流畅的操作体验。

## 📱 预览

![淋浴 App 截图](assets/screenshots.jpg)

## ✨ 功能

- 🔐 **手机号登录** — 支持登录状态持久化和自动恢复
- 📡 **蓝牙扫描** — BLE 扫描附近热水器，按信号强度排序
- 🚿 **一键洗澡** — 选择设备即可开始，支持停止和恢复
- 💰 **余额估算** — 手动输入初始余额，根据账单自动扣减
- 📋 **账单查询** — 查看当月消费记录和详情
- 🔢 **使用码** — 显示/远程开关热水器使用码
- 🌓 **深浅主题** — 手动切换，设置自动保存
- 📶 **断网提示** — 网络异常时友好提示
- 👥 **挤号检测** — 多设备登录自动提醒

## 🏗 架构

```
UI (Compose) → ViewModel → Repository → Retrofit API
                              ↓
                    MQTT / BLE / PrefsHelper
```

| 层级 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 状态管理 | ViewModel + StateFlow |
| HTTP | Retrofit 2.9 + OkHttp |
| 实时推送 | Eclipse Paho MQTT |
| 蓝牙 | Android BLE API |
| 混淆 | R8 Full Mode |

## 📁 项目结构

```
app/src/main/java/com/hualala/linyu/
├── api/                    # 网络层
│   ├── QzxyService.kt      # Retrofit 接口 (11 个 API)
│   ├── NetworkModule.kt    # OkHttp + 认证拦截器
│   └── SafeApi.kt          # 手动 JSON 解析 (避 R8 泛型擦除)
├── data/                   # 数据层
│   └── AuthRepository.kt   # 登录认证
├── model/                  # 数据模型
│   ├── LoginModels.kt
│   ├── DeviceModels.kt
│   ├── ActiveOrder.kt
│   └── MqttModels.kt
├── ui/                     # 界面
│   ├── LoginScreen.kt      # 登录
│   ├── MainScreen.kt       # 主页 + 设备列表
│   ├── ShowerScreen.kt     # 洗澡中
│   ├── WalletScreen.kt     # 钱包 + 账单
│   ├── UserScreen.kt       # 用户 + 使用码
│   └── theme/Theme.kt      # 主题
└── utils/                  # 工具
    ├── PrefsHelper.kt      # 本地存储
    ├── MqttManager.kt      # MQTT 管理
    ├── BluetoothScanner.kt # 蓝牙扫描
    └── MD5Utils.kt         # 密码加密
```

## 🚀 构建

### 环境要求

- Android Studio Hedgehog+
- JDK 11+
- Android SDK 36
- Gradle 9.4.1

### 生成签名密钥

```bash
keytool -genkey -v -keystore your-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias your-alias
```

### 配置签名

创建 `local.properties`（已在 `.gitignore` 中）：

```properties
KEYSTORE_FILE=../your-key.jks
KEYSTORE_PASSWORD=你的密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密码
```

### 构建 APK

```bash
# Debug
./gradlew assembleDebug

# Release (混淆 + 压缩 + 签名)
./gradlew assembleRelease
```

## 📖 文档

| 文档 | 说明 |
|---|---|
| [PROJECT.md](PROJECT.md) | 完整项目文档、技术架构、功能清单 |
| [API-qzxy.md](API-qzxy.md) | 趣智校园 API 逆向工程完整参考 |
| [开发者指南.md](开发者指南.md) | 面向第三方开发者的开发指南 |

## 🔧 适配你的学校

本项目仅在**金华职业技术大学（projectId=905）**的男生宿舍测试过，其他学校使用前需要修改以下内容：

### 1. 获取 SecretKey（短信验证码登录需要）

代码中 `QzxyService.kt` 的 `getVerificationCode` 接口硬编码了 `secret` 参数，这个值每台设备不同，需要你自己抓包获取：

```
1. 安装 Reqable + LSPosed + TrustMeAlready（绕过 SSL Pinning）
2. 打开官方趣智校园 App，点击"发送验证码"
3. 在 Reqable 中找到 /user/verification/code/get 请求
4. 复制 URL 中 secret 参数的值
5. 替换 QzxyService.kt 第 90 行的 secret 默认值
```

> 💡 密码登录**可能**不需要 secretKey，但未充分验证。

### 2. 修改 projectId

每所学校的 projectId 不同，从 `/user/login` 响应的 `userAccount.projectId` 获取。

### 3. 可能需要修改的位置

| 位置 | 当前值 | 说明 |
|---|---|---|
| `projectId` | 905 | 学校唯一标识 |
| BLE 设备名过滤 | `KLCXKJ-Water` | 热水器蓝牙广播名 |
| MAC 地址前缀 | `C4:7F:0E` | 凯路创新科技厂商码 |
| MQTT 服务器 | `tcp://47.107.37.60:1883` | 不同学校可能不同 |

---

## ⚠️ 已知问题与限制

### 🚿 洗澡相关

| 问题 | 说明 |
|---|---|
| ~~热水器自动关停无感知~~ | ✅ **已解决 (v1.2.0)**：解析 `autoDisConTime` 显示闲置倒计时；自动关闭时弹出确认框（设备名 + 时长 + 消费金额） |
| ~~关闭失败无提示~~ | ✅ **已解决 (v1.2.0)**：关阀后通过 `closeOrderResult` 确认，失败会提示重试 |
| **无实时扣费** | MQTT 仅在订单结束时推送消费金额，洗澡中看不到实时扣费。官方 App 也是如此 |

### 🏫 兼容性

| 问题 | 说明 |
|---|---|
| **仅在一所学校测试** | 只在金华职业技术大学（projectId=905）男生宿舍测试过几次，其他学校能否使用未知 |
| **SMS 登录需 secretKey** | 短信验证码接口的 `secret` 参数每台设备不同，需要自己抓包获取 |
| **一卡通余额无法获取** | 易校园 API 有 HMAC-SHA256 native 签名保护，只能手动估算余额 |

### 🔒 安全

| 问题 | 说明 |
|---|---|
| **MQTT 明文** | 趣智校园 MQTT 服务器不支持 TLS，通信内容未加密 |
| **密码 MD5** | 趣智校园使用 MD5 取后 10 位，安全性低（官方协议限制） |

### 🎨 体验

| 问题 | 说明 |
|---|---|
| **深色模式不完整** | 登录页和洗澡页为硬编码颜色切换，非完全跟随系统 |
| **仅中文界面** | 无多语言支持 |

> 欢迎提 Issue 或 PR 帮助改进！

## 📄 许可证

[MIT License](LICENSE)

## ⚖️ 免责声明

本项目仅供学习和研究用途。使用者应自行遵守趣智校园及相关服务的使用条款。开发者不对因使用本项目产生的任何后果承担责任。
