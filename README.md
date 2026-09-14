# 🚿 淋浴 (LinYu)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/Version-v2.2.1-orange.svg)](https://github.com/yehu-imei/linyu/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**⬇️ [下载最新 APK (v2.2.1)](https://github.com/yehu-imei/linyu/releases/latest)**

趣智校园第三方 Android 客户端，用于控制校园热水器与直饮水机。相比官方 App，提供更简洁的界面和更流畅的操作体验。

## 📱 预览

<img src="assets/screenshots.png" width="1000" alt="淋浴 App 截图" />

## ✨ 功能

### 🚿 设备控制

- 🔐 **手机号登录** — 密码登录 / **短信验证码登录**（已通用化），支持系统自动填充保存账号密码，登录状态持久化与自动恢复
- 📡 **蓝牙扫描** — BLE 扫描附近设备，按信号强度排序；权限按需申请，Android 12+ 不需要定位权限
- 📷 **扫码绑定** — 扫描设备二维码，直接弹出设备详情，无需蓝牙
- 🔦 **扫码手电筒** — 光线不足时扫码补光
- 🏠 **绑定寝室** — 绑定寝室关键词，设备列表只显示寝室内的设备
- 🚿 **一键洗澡** — 选择设备即可开始，支持停止和恢复；开阀确认，失败有提示
- ⏳ **自动关停倒计时** — 显示闲置自动关闭倒计时，关闭时弹出确认框
- 💰 **消费结算** — 关阀后通过账单自动显示本次消费金额
- 🚰 **饮水机支持** — 自动识别直饮水机（冷水 ❄️ / 热水 ♨️），绿色主题区分，设备名智能精简
- 🧩 **桌面小组件** — 2x2（按钮在下方，拉宽后自动移到右侧）/ 2x4（大圆形按钮）两种尺寸，样式对齐 App 内卡片；在桌面直接启停热水，计时由系统 Chronometer 驱动，App 不在也能实时走秒

### 💰 钱包与账单

- 💰 **余额估算** — 手动输入初始余额，根据账单自动扣减
- 📋 **账单查询** — 查看当月消费记录和详情，按设备类型着色

### 🎨 个性化

- 🖼 **背景装扮** — 主页与使用页各一套独立背景，自动提取主题色（网易云式），支持透明度 / 模糊 / 亮度调节
- 🌓 **深浅主题** — 圆形揭示切换动画，设置自动保存
- 🧩 **卡片自定义** — 「我的」页面卡片可上下排序、隐藏显示
- 📶 **断网提示** — 网络异常时友好提示
- 👥 **挤号检测** — 多设备登录自动提醒，25 秒心跳轮询，被挤下线能及时弹出提示

### 🔧 其他

- 📜 **内置运行日志** — 内存 + 文件双缓冲，敏感信息自动打码，崩溃自动捕获，可一键分享导出
- 🔄 **应用内更新** — 自动读取 GitHub Release 比对版本、展开查看更新日志，并可直接在应用内下载安装；下载失败或想挂代理时可改用浏览器
- 🔐 **加密存储** — 登录凭证加密存储（EncryptedSharedPreferences）

## 🏗 架构

```
UI (Compose) → ViewModel → Repository → Retrofit API (v3-api.china-qzxy.cn)
                              ↓
   MQTT / BLE 扫描 / 扫码(CameraX) / 账单结算 / 加密存储 / 日志 / 背景管理
```

| 层级 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 状态管理 | ViewModel + StateFlow / Compose State |
| HTTP | Retrofit 2.9 + OkHttp |
| 实时推送 | Eclipse Paho MQTT |
| 蓝牙 / 扫码 | Android BLE API / CameraX + ML Kit |
| 加密存储 | EncryptedSharedPreferences |
| 日志 | 自研 AppLogger（环形缓冲 + 文件滚动 + 脱敏） |
| 桌面小组件 | AppWidgetProvider + RemoteViews |
| 混淆 | R8 Full Mode |

## 📁 项目结构

```
app/src/main/java/com/hualala/linyu/
├── MainActivity.kt         # 主 Activity（导航、弹窗、边到边、状态栏）
├── QrScanActivity.kt       # 扫码界面 (CameraX + ML Kit + 手电筒)
├── api/                    # 网络层
│   ├── QzxyService.kt      # Retrofit 接口
│   ├── NetworkModule.kt    # OkHttp + 认证拦截器
│   ├── SafeApi.kt          # 手动 JSON 解析 (避 R8 泛型擦除)
│   └── GithubApi.kt        # GitHub Release / 仓库信息（更新检测）
├── data/                   # 数据层
│   ├── AuthRepository.kt   # 登录认证（密码 / 短信）
│   └── ShowerController.kt # 开阀/关阀/结算共享层（App 与小组件共用）
├── model/                  # 数据模型
│   ├── LoginModels.kt      # 含账单设备类型判定（热水器 / 饮水机）
│   ├── DeviceModels.kt     # 含饮水机识别与设备名格式化
│   ├── ActiveOrder.kt
│   └── MqttModels.kt
├── ui/                     # 界面
│   ├── LoginScreen.kt      # 登录（密码 / 短信两种方式）
│   ├── MainScreen.kt       # 主页 + 设备列表 + 扫码
│   ├── ShowerScreen.kt     # 洗澡中 (含自动关停倒计时)
│   ├── WalletScreen.kt     # 钱包 + 账单
│   ├── UserScreen.kt       # 我的（可排序卡片）
│   ├── FloatingPillNavBar.kt    # 悬浮胶囊导航栏（弹簧滑块）
│   ├── AppBackgroundLayer.kt    # 自定义背景渲染层
│   ├── CustomBackgroundScreen.kt# 背景装扮设置页
│   ├── LogViewerDialog.kt       # 内置日志查看器
│   ├── LinYuToast.kt
│   ├── DeviceDetailDialog.kt
│   └── theme/
│       ├── Theme.kt             # 配色方案（液态玻璃卡片）
│       └── CircularRevealTheme.kt # 圆形揭示主题切换
├── widget/                 # 桌面小组件
│   ├── LinYuWidgetProvider.kt # Provider（2x2 / 2x4 共用逻辑）+ 状态推送
│   └── WidgetRenderer.kt      # 状态推断 + RemoteViews 渲染
└── utils/                  # 工具
    ├── PrefsHelper.kt      # 加密存储
    ├── MqttManager.kt      # MQTT 管理
    ├── BluetoothScanner.kt # 蓝牙扫描
    ├── MD5Utils.kt         # 密码加密
    ├── SignUtils.kt        # 短信验证码 secret 计算
    ├── AppLogger.kt        # 日志（脱敏 / 滚动 / 崩溃捕获）
    ├── BackgroundManager.kt# 背景图存取（主页 / 使用页两套）
    ├── BackgroundState.kt  # 背景配置状态
    ├── ScanPermission.kt   # 蓝牙扫描权限（按系统版本分流）
    └── ApkUpdater.kt       # 更新包下载 + 调起安装器
```

res/ 额外包含 `drawable/ic_flashlight.xml`（扫码手电筒图标）、`drawable/app_logo.png`（应用 logo）、
`xml/file_paths.xml`（日志导出 FileProvider），以及小组件用的
`layout/widget_linyu_2x2_*.xml`、`layout/widget_linyu_2x4_*.xml`、`xml/widget_info_*.xml`、`drawable/widget_*.xml`。

## 🚀 构建

### 环境要求

- Android Studio（推荐自带 JBR/JDK 21）
- JDK 17+（实测使用 Android Studio 自带 JBR/JDK 21 构建）
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
# 若因网络无法下载 lint 依赖而失败，可跳过 lint 检查：
./gradlew assembleRelease -x lintVitalRelease
```

## 📖 文档

| 文档 | 说明 |
|---|---|
| [PROJECT.md](PROJECT.md) | 完整项目文档、技术架构、功能清单 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新日志 |
| [API-qzxy.md](API-qzxy.md) | 趣智校园 API 逆向工程完整参考 |
| [开发者指南.md](开发者指南.md) | 面向第三方开发者的开发指南 |

## 🔧 适配你的学校

本项目仅在**金华职业技术大学（projectId=905）**的男生宿舍测试过，其他学校使用前需要修改以下内容：

### 1. 短信登录

✅ **v2.1.0 起短信登录已通用化**，任何手机号都可以直接收验证码登录，无需抓包。

逆向发现官方 App 的 `secret` 并非随机值，而是按手机号计算得来（见 `utils/SignUtils.kt`）：

```
secret = MD5( 手机号前3位 + 手机号后4位 + "klcx" )
```

由于算法在本地即可算出，所有用户都能正常使用短信登录。若你的学校接口签名算法不同，可在此处替换。

### 2. 修改 projectId

每所学校的 projectId 不同，从 `/user/login` 响应的 `userAccount.projectId` 获取。

### 3. 可能需要修改的位置

| 位置 | 当前值 | 说明 |
|---|---|---|
| `projectId` | 905 | 学校唯一标识 |
| BLE 设备名过滤 | `KLCXKJ-Water` | 设备蓝牙广播名 |
| MAC 地址前缀 | `C4:7F:0E` | 凯路创新科技厂商码 |
| MQTT 服务器 | `tcp://47.107.37.60:1883` | 不同学校可能不同 |

---

## ⚠️ 已知问题与限制

### ✅ 已修复

| 原问题 | 状态 |
|---|---|
| ~~短信登录 secret 绑定账号~~ | ✅ **已解决 (v2.1.0)**：secret 由手机号推导，任何手机号可用 |
| ~~被挤号后重新登录又被弹出、需要登两次~~ | ✅ **已解决 (v2.2.0)**：旧会话在途请求会清掉新会话凭证，改为会话级作用域整体取消 |
| ~~热水器自动关停无感知~~ | ✅ **已解决 (v1.2.0)**：解析 `autoDisConTime` 显示闲置倒计时，自动关闭时弹确认框 |
| ~~关闭失败无提示~~ | ✅ **已解决 (v1.2.0)**：关阀后通过 `closeOrderResult` 确认，失败会提示重试 |
| ~~切到「我的」页面卡顿~~ | ✅ **已解决 (v2.1.0)**：卡片顺序 / Release 数据改为进程级缓存 |
| ~~设备名残留「表」字~~ | ✅ **已解决 (v2.1.0)**：修正正则处理顺序，`热水表-xxx` 不再被截成 `表 xxx` |
| ~~使用页退出按钮点击无响应~~ | ✅ **已解决 (v2.1.0)**：修正组件层级，按钮不再被上层 Column 拦截点击 |
| ~~Android 12+ 被迫要定位权限~~ | ✅ **已解决 (v2.2.0)**：`BLUETOOTH_SCAN` 声明 `neverForLocation`，且改由用户主动触发申请 |

### ❌ 仍未解决

| 问题 | 说明 |
|---|---|
| **挤号检测有最多 25 秒延迟** | 靠心跳轮询实现，被挤下线最多 25 秒后才提示（此前是完全发现不了） |
| **无实时扣费** | MQTT 仅在订单结束时推送消费金额，洗澡中看不到实时扣费。官方 App 也是如此 |
| **消费金额结算延迟** | 账单生成有延迟，最长需等待约 20 秒 |
| **一卡通余额无法获取** | 易校园 API 有 HMAC-SHA256 native 签名保护，只能手动估算余额 |
| **小组件不显示实时消费** | 小组件不连 MQTT，使用中只显示预扣金额；停止后也不做账单结算，回 App 才会显示 |

### 🏫 兼容性

| 问题 | 说明 |
|---|---|
| **仅在一所学校测试** | 只在金华职业技术大学（projectId=905）男生宿舍测试过几次，其他学校能否使用未知 |
| **饮水机功能未实机验证** | 饮水机识别与 UI 已实现，但作者所在学校无直饮水机，实际控制流程未验证 |
| **Android 11 及以下仍需定位权限** | 系统对蓝牙发现的硬性规定，无法绕过；引导卡片里说明了原因 |
| **自动填充依赖手机密码管理器** | 不同厂商 ROM 行为差异较大，未在多数机型上验证 |

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
