# 淋浴 (LinYu) — 项目文档

## 项目概述

**淋浴 (LinYu)** 是一款趣智校园第三方 Android 客户端，专注于校园热水器控制功能。相比官方 App，淋浴提供了更简洁的界面和更流畅的操作体验。

| 项 | 值 |
|---|---|
| 应用名称 | 淋浴 |
| 包名 | `com.hualala.linyu` |
| 版本 | v1.2.0 |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 |
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 16 (API 36) |
| APK 体积 | ~17 MB |
| 后端 API | 趣智校园 `v3-api.china-qzxy.cn` |
| 适用范围 | 使用趣智校园系统的学校（学校名称可手动修改） |

---

## 功能清单

### 核心功能

| 功能 | 说明 |
|---|---|
| 登录/登出 | 手机号 + 密码登录，MD5 加密，loginCode 持久化，挤号检测 |
| 蓝牙扫描设备 | BLE 低功耗蓝牙扫描附近热水器，按信号强度显示（强/中/弱） |
| 开始洗澡 | 选择设备后调用 downRate API 开启热水器 |
| 停止洗澡 | 调用 closeOrder API 关闭热水器 |
| 洗澡中界面 | 全屏沉浸式界面，显示计时器、预扣金额、设备位置 |
| MQTT 实时推送 | 连接 MQTT 服务器接收实时消费金额更新 |
| 使用码启动检测 | 通过物理键盘使用码启动设备后，刷新可自动发现使用中的设备 |
| 多设备支持 | 支持同时管理多个活跃设备订单 |
| 上次使用设备 | 记住上次使用的设备，一键快速开始 |

### 钱包与账单

| 功能 | 说明 |
|---|---|
| 余额估算 | 手动输入初始余额，根据账单自动扣减估算当前余额 |
| 账单查询 | 查看当月最近 20 条消费记录 |
| 账单详情 | 点击账单查看设备名、设备类型、消费金额、消费时间、订单号 |
| 下拉刷新 | 主页和钱包页面均支持下拉刷新 |

### 使用码

| 功能 | 说明 |
|---|---|
| 使用码显示 | 显示当前使用码，后三位蓝色高亮（与手机号后三位相同） |
| 使用码开关 | 远程开启/关闭使用码功能 |

### 用户体验

| 功能 | 说明 |
|---|---|
| 深浅主题 | 手动切换浅色/深色模式，设置持久化保存 |
| 学校名称编辑 | 用户可手动修改学校名称 |
| 网络异常提示 | 断网时显示友好提示（自定义 Toast，带应用图标） |
| 挤号检测 | 在其他设备登录同一账号时弹出强制下线提示 |
| 屏幕旋转 | 使用 rememberSaveable 保持登录状态和当前页面 |
| 退出确认 | 洗澡中退出登录时弹出警告提示 |

---

## 文件清单与项目结构

```
app/src/main/
├── AndroidManifest.xml                    # 应用清单，权限声明
├── java/com/hualala/linyu/
│   ├── MainActivity.kt                    # 主 Activity，导航、弹窗、主题管理
│   │
│   ├── api/                               # 网络层
│   │   ├── QzxyService.kt                 # Retrofit 接口定义（11 个 API）
│   │   ├── NetworkModule.kt               # OkHttp + Retrofit 单例，认证拦截器
│   │   └── SafeApi.kt                     # 扩展函数，手动 JSON 解析（绕开 R8 泛型问题）
│   │
│   ├── data/                              # 数据层
│   │   └── AuthRepository.kt              # 登录认证逻辑
│   │
│   ├── model/                             # 数据模型
│   │   ├── LoginModels.kt                 # BaseResponse<T>、LoginData、UserAccount、
│   │   │                                  # WalletData、OrderStatus、BillItem、BillDTO、
│   │   │                                  # UseCodeData、BillDetail
│   │   ├── DeviceModels.kt                # DeviceInfo、NearbyDevice
│   │   ├── ActiveOrder.kt                 # 活跃订单模型
│   │   └── MqttModels.kt                  # MQTT 消息模型
│   │
│   ├── ui/                                # UI 层
│   │   ├── LoginScreen.kt                 # 登录页面（渐变背景 + 卡片式表单）
│   │   ├── LoginViewModel.kt              # 登录 ViewModel（网络异常友好提示）
│   │   ├── MainScreen.kt                  # 主页（设备列表、蓝牙扫描、余额显示）
│   │   ├── MainViewModel.kt               # 主 ViewModel（蓝牙、MQTT、洗澡控制、
│   │   │                                  # 钱包、账单、设备发现、挤号检测）
│   │   ├── ShowerScreen.kt                # 洗澡中界面（计时器、预扣金额、深色适配）
│   │   ├── WalletScreen.kt                # 钱包页面（余额估算、账单列表、下拉刷新）
│   │   ├── UserScreen.kt                  # 我的页面（账号信息、使用码、主题切换）
│   │   ├── DeviceDetailDialog.kt          # 设备详情弹窗（SN、MAC、预扣金额、状态）
│   │   ├── LinYuToast.kt                  # 自定义 Toast 组件（应用图标 + 深色背景）
│   │   └── theme/
│   │       └── Theme.kt                   # 深浅主题配色方案
│   │
│   └── utils/                             # 工具层
│       ├── PrefsHelper.kt                 # SharedPreferences 工具（认证、设备、余额、主题）
│       ├── MqttManager.kt                 # MQTT 连接管理（Paho 客户端）
│       ├── BluetoothScanner.kt            # BLE 蓝牙扫描（过滤 KLCXKJ-Water 设备）
│       └── MD5Utils.kt                    # 密码加密（MD5 取后 10 位）
│
└── res/
    ├── xml/
    │   ├── network_security_config.xml    # 网络安全配置（仅允许 MQTT 明文）
    │   ├── backup_rules.xml               # 备份规则
    │   └── data_extraction_rules.xml      # 数据提取规则
    ├── values/
    │   ├── strings.xml                    # 字符串资源（app_name = 淋浴）
    │   ├── colors.xml                     # 颜色资源
    │   └── themes.xml                     # 浅色主题（Material Components）
    ├── values-night/
    │   └── themes.xml                     # 深色主题
    └── mipmap-*/                          # 应用图标
```

---

## 技术架构

### 架构模式

项目采用简化的 MVVM 架构：

```
┌─────────────────────────────────────────┐
│  UI 层 (Compose)                        │
│  LoginScreen / MainScreen / ShowerScreen│
│  WalletScreen / UserScreen              │
├─────────────────────────────────────────┤
│  ViewModel 层                           │
│  LoginViewModel / MainViewModel         │
├─────────────────────────────────────────┤
│  数据层                                 │
│  AuthRepository / NetworkModule         │
│  QzxyService (Retrofit) / SafeApi       │
│  PrefsHelper / MqttManager              │
│  BluetoothScanner                       │
├─────────────────────────────────────────┤
│  模型层                                 │
│  BaseResponse / LoginData / DeviceInfo  │
│  ActiveOrder / MqttOrderMsg             │
└─────────────────────────────────────────┘
```

### 关键依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| Jetpack Compose BOM | 2024.02.01 | 声明式 UI 框架 |
| Material 3 | BOM 管理 | Material Design 3 组件 |
| Material | BOM 管理 | pullRefresh 下拉刷新组件 |
| Retrofit | 2.9.0 | HTTP 客户端 |
| Gson | Retrofit 内置 | JSON 序列化/反序列化 |
| OkHttp Logging | 4.12.0 | HTTP 日志（仅 Debug） |
| Eclipse Paho MQTT | 1.2.5 / 1.1.1 | MQTT 客户端 |

### R8 混淆兼容方案

R8 full mode 会擦除 Kotlin suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
1. Retrofit 接口返回 `Call<ResponseBody>`（非 suspend，非泛型）
2. `SafeApi.kt` 中定义扩展函数，使用 `suspendCancellableCoroutine` 桥接回调
3. 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段
4. 完全不依赖 Gson 的泛型反射，R8 无法破坏

### 网络安全配置

- HTTPS API（`v3-api.china-qzxy.cn`）：正常 HTTPS
- MQTT（`tcp://47.107.37.60:1883`）：明文 TCP（趣智校园原始协议，无法修改）
- 其他所有明文流量：禁止

---

## 构建说明

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 11+
- Android SDK 36
- Gradle 9.4.1

### 签名配置

**开源版本不包含签名密钥。** 你需要自行生成签名密钥：

```bash
keytool -genkey -v -keystore your-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your-alias
```

然后在项目根目录创建 `local.properties`：

```properties
KEYSTORE_FILE=../your-key.jks
KEYSTORE_PASSWORD=你的密钥库密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密钥密码
```

### 构建命令

```bash
# Debug 构建（用于开发测试）
./gradlew assembleDebug

# Release 构建（R8 混淆 + 资源压缩 + 签名）
./gradlew assembleRelease
```

### APK 输出位置

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk          # Debug 版本（未签名，未混淆）
└── release/
    └── app-release.apk        # Release 版本（已签名，已混淆，已压缩）
```

### Release 构建配置

`app/build.gradle.kts` 中的 Release 配置：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true        // 启用 R8 代码混淆
        isShrinkResources = true      // 移除未使用的资源
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
}
```

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 测试范围 | 仅在金华职业技术大学（projectId=905）男生宿舍测试过几次，其他学校未测试 |
| 短信登录 | SMS 验证码接口需要 secretKey，每台设备不同，需自行抓包获取 |
| 自动关停 | 热水器闲置超时后自动关闭，App 不会检测，洗澡界面会一直保持 |
| 关闭失败 | 网络波动时 closeOrder 可能失败但无错误提示，用户以为关了实际还开着 |
| 实时扣费 | MQTT 仅在订单结束时推送消费金额，洗澡中无实时扣费（官方 App 也是如此） |
| 一卡通余额 | 无法获取真实余额（易校园 API 有签名保护），仅支持手动估算 |
| MQTT 明文 | 趣智校园 MQTT 服务器不支持 TLS，通信内容未加密 |
| 密码安全 | 趣智校园使用 MD5 取后 10 位作为密码，安全性较低（官方协议限制） |
| 学校适配 | 不同学校的趣智校园服务器可能不同，需修改 projectId、BLE 过滤名、MQTT 地址 |
| 深色模式 | 登录页面和洗澡页面的深色模式适配为硬编码颜色切换，非完全动态 |
| 多语言 | 仅支持中文 |

---

## 版本历史

### v1.2.0

- 开阀确认（开始洗澡时确认开阀成功）
- 自动关停倒计时与确认弹窗
- 消费金额结算（账单接口，异步获取）
- 主页扫码绑定设备 + 扫码手电筒
- 绑定寝室与设备列表筛选
- 加密存储（EncryptedSharedPreferences）
- 退出登录闪退修复
- 挤号重登触发优化

### v1.1.0

- 最低 SDK 提升至 API 26
- Kotlin 2.0.21 + AGP 8.13.2 升级

### v1.0 (2026-05-30)

- 初始版本
- 登录/登出、挤号检测
- 蓝牙扫描设备
- 开始/停止洗澡
- MQTT 实时消费推送
- 使用码显示/开关
- 多设备活跃订单管理
- 余额手动估算
- 账单查询
- 深浅主题切换
- 网络异常友好提示
- R8 混淆 + 资源压缩

---

## 免责声明

本项目仅供学习和研究用途。使用者应自行遵守趣智校园及相关服务的使用条款。
开发者不对因使用本项目产生的任何后果承担责任。
