# 淋浴 (LinYu) — 项目文档

## 项目概述

**淋浴 (LinYu)** 是一款趣智校园第三方 Android 客户端，专注于校园热水器控制功能。相比官方 App，淋浴提供了更简洁的界面和更流畅的操作体验。

| 项 | 值 |
|---|---|
| 应用名称 | 淋浴 |
| 包名 | `com.hualala.linyu` |
| 版本 | v2.2.3 |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 |
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 16 (API 36) |
| APK 体积 | 12.5 MB（含 ML Kit 条码识别 native 库） |
| 支持的 ABI | 仅 `arm64-v8a` |
| 后端 API | 趣智校园 `v3-api.china-qzxy.cn` |
| 适用范围 | 使用趣智校园系统的学校（学校名称可手动修改） |

---

## 功能清单

### 核心功能

| 功能 | 说明 |
|---|---|
| 密码登录/登出 | 手机号 + 密码登录，MD5 加密，loginCode 加密持久化，挤号检测 |
| 短信验证码登录 | v2.1.0 通用化：`secret = MD5(前3位+后4位+"klcx")` 由手机号推导，任何手机号可用 |
| 蓝牙扫描设备 | BLE 低功耗蓝牙扫描附近设备，按信号强度显示（强/中/弱） |
| 扫码绑定设备 | 扫描设备二维码，直接弹出设备详情（无需蓝牙） |
| 绑定寝室 | 绑定寝室关键词，设备列表只显示寝室内的设备 |
| 开始洗澡 | 调用 downRate API 开启设备，并轮询确认开阀成功 |
| 停止洗澡 | 调用 closeOrder API 关闭设备，确认关闭成功，失败有提示 |
| 洗澡中界面 | 全屏沉浸式界面，显示计时器、预扣金额、设备位置 |
| 自动关停倒计时 | 解析 autoDisConTime 显示闲置自动关闭倒计时 |
| 自动关停确认弹窗 | 设备自动关闭时弹确认框（设备名 + 时长 + 消费金额） |
| 消费结算 | 关阀后通过账单接口异步获取并显示本次消费金额 |
| MQTT 实时推送 | 连接 MQTT 服务器接收实时消费金额更新 |
| 使用码启动检测 | 通过物理键盘使用码启动设备后，刷新可自动发现使用中的设备 |
| 多设备支持 | 支持同时管理多个活跃设备订单 |
| 上次使用设备 | 记住上次使用的设备，一键快速开始 |
| 饮水机支持 | 按 `bigTypeId == 5` 识别直饮水机，绿主题 + ❄️/♨️ 图标 + 名称精简（未实机验证） |
| 桌面小组件 | 2x2 / 2x4 两种尺寸，桌面直接启停热水；深色液态玻璃卡片，计时由 `Chronometer` 驱动，App 不在也能走秒 |
| 主动挤号检测 | 25 秒心跳轮询，被挤下线能及时弹提示（跟随 Activity 生命周期，退后台自动停） |

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
| 深浅主题 | 手动切换浅色/深色模式，圆形揭示过渡动画，设置持久化保存 |
| 悬浮胶囊导航栏 | 弹簧滑块跟随选中项，点击无水波纹（仅滑块滑动反馈） |
| 页面切换动画 | 三大主页之间左右视差滑移 + 淡入淡出弹簧动画 |
| 液态玻璃卡片 | 半透明底色 + 微光描边 + 零阴影 |
| 背景装扮 | 主页 / 使用页各一套独立背景，自动提取主题色，透明度 / 模糊 / 亮度可调 |
| 卡片自定义 | 「我的」页卡片可上下排序、隐藏显示，状态持久化 |
| 应用内更新 | 读取 GitHub Release 比对版本、折叠展示更新日志，并可在应用内下载安装 |
| 内置运行日志 | 环形缓冲 + 文件滚动 + 敏感信息脱敏 + 崩溃捕获 + 一键导出 |
| 扫码手电筒 | 扫码界面提供手电筒，光线不足时补光 |
| 加密存储 | 登录凭证用 EncryptedSharedPreferences 加密存储 |
| 自动填充 | 登录输入框标注自动填充身份，手机密码管理器可保存 / 回填账号密码 |
| 扫描权限按需申请 | 不再登录后自动弹窗；Android 12+ 用 `neverForLocation` 免掉定位权限 |
| 学校名称编辑 | 用户可手动修改学校名称 |
| 网络异常提示 | 断网时显示友好提示（自定义 Toast，带应用图标） |
| 挤号检测 | 在其他设备登录同一账号时弹出强制下线提示（25 秒心跳轮询，主动发现） |
| 屏幕旋转 | 使用 rememberSaveable 保持登录状态和当前页面 |
| 退出确认 | 洗澡中退出登录时弹出警告提示 |

---

## 文件清单与项目结构

```
app/src/main/
├── AndroidManifest.xml                    # 应用清单，权限声明
├── java/com/hualala/linyu/
│   ├── MainActivity.kt                    # 主 Activity，导航、弹窗、主题管理
│   ├── QrScanActivity.kt                  # 扫码界面（CameraX + ML Kit + 手电筒）
│   │
│   ├── api/                               # 网络层
│   │   ├── QzxyService.kt                 # Retrofit 接口定义（16 个 API）
│   │   ├── NetworkModule.kt               # OkHttp + Retrofit 单例，认证拦截器
│   │   ├── SafeApi.kt                     # 扩展函数，手动 JSON 解析（绕开 R8 泛型问题）
│   │   └── GithubApi.kt                   # GitHub Release / 仓库信息（更新检测）
│   │
│   ├── data/                              # 数据层
│   │   ├── AuthRepository.kt              # 登录认证逻辑（密码 / 短信）
│   │   └── ShowerController.kt            # 开阀 / 关阀 / 结算共享层（App 与小组件共用）
│   │
│   ├── model/                             # 数据模型
│   │   ├── LoginModels.kt                 # BaseResponse<T>、LoginData、UserAccount、
│   │   │                                  # WalletData、OrderStatus、BillItem、BillDTO、
│   │   │                                  # UseCodeData、BillDetail、DownRateResult、
│   │   │                                  # CloseOrderResult（含账单设备类型判定）
│   │   ├── DeviceModels.kt                # DeviceInfo、NearbyDevice（含饮水机识别、
│   │   │                                  # 设备名格式化、类型 emoji / 颜色）
│   │   ├── WidgetCache.kt                 # 小组件离线快照（附近设备 / 账单）
│   │   ├── ActiveOrder.kt                 # 活跃订单模型
│   │   └── MqttModels.kt                  # MQTT 消息模型
│   │
│   ├── ui/                                # UI 层
│   │   ├── LoginScreen.kt                 # 登录页面（密码 / 短信双模式）
│   │   ├── LoginViewModel.kt              # 登录 ViewModel（网络异常友好提示）
│   │   ├── MainScreen.kt                  # 主页（设备列表、扫码、寝室筛选、余额显示）
│   │   ├── MainViewModel.kt               # 主 ViewModel（蓝牙、MQTT、洗澡控制、开阀/
│   │   │                                  # 关阀确认、消费结算、挤号检测）
│   │   ├── ShowerScreen.kt                # 洗澡中界面（计时器、自动关停倒计时）
│   │   ├── WalletScreen.kt                # 钱包页面（余额估算、账单列表、下拉刷新）
│   │   ├── UserScreen.kt                  # 我的页面（可排序卡片 + 进程级缓存）
│   │   ├── FloatingPillNavBar.kt          # 悬浮胶囊导航栏（弹簧滑块，点击无波纹）
│   │   ├── AppBackgroundLayer.kt          # 自定义背景渲染层（透明度/模糊/亮度）
│   │   ├── CustomBackgroundScreen.kt      # 背景装扮设置页（主页 / 使用页切换）
│   │   ├── LogViewerDialog.kt             # 内置日志查看器
│   │   ├── TailEllipsisText.kt            # 尾部优先省略的单行文本（设备名 / MAC）
│   │   ├── DeviceDetailDialog.kt          # 设备详情弹窗（SN、MAC、预扣金额、状态）
│   │   ├── LinYuToast.kt                  # 自定义 Toast 组件（应用图标 + 深色背景）
│   │   └── theme/
│   │       ├── Theme.kt                   # 深浅主题配色方案（液态玻璃卡片）
│   │       └── CircularRevealTheme.kt     # 圆形揭示主题切换容器
│   │
│   ├── widget/                            # 桌面小组件
│   │   ├── LinYuWidgetProvider.kt         # Provider 基类（2x2 / 2x4 共用）+ 状态推送
│   │   └── WidgetRenderer.kt              # 状态推断 + RemoteViews 渲染（无反射调用）
│   │
│   └── utils/                             # 工具层
│       ├── PrefsHelper.kt                 # 加密存储（EncryptedSharedPreferences，认证、
│       │                                  # 设备、余额、主题、寝室绑定、倒计时、背景）
│       ├── MqttManager.kt                 # MQTT 连接管理（Paho 客户端）
│       ├── BluetoothScanner.kt            # BLE 蓝牙扫描（过滤 KLCXKJ-Water 设备）
│       ├── MD5Utils.kt                    # 密码加密（MD5 取后 10 位）
│       ├── SignUtils.kt                   # 短信验证码 secret 计算（按手机号推导）
│       ├── AppLogger.kt                   # 运行日志（脱敏 / 滚动 / 崩溃捕获）
│       ├── BackgroundManager.kt           # 背景图存取（主页 / 使用页两套配置）
│       ├── BackgroundState.kt             # 背景配置状态（Compose State）
│       ├── ScanPermission.kt              # 蓝牙扫描权限（按系统版本分流）
│       └── ApkUpdater.kt                  # 更新包下载 + 调起系统安装器
│
└── res/
    ├── drawable/
    │   ├── app_logo.png                   # 应用 logo（Toast 图标）
    │   ├── ic_flashlight.xml              # 扫码手电筒图标
    │   ├── widget_glass.xml              # 小组件玻璃卡底（深色中性玻璃 + 高光 + 轮廓）
    │   ├── widget_inset.xml              # 内嵌玻璃槽（上次消费 / 计时）
    │   ├── widget_badge_*.xml            # 状态徽章（空闲 / 使用中）
    │   ├── widget_btn_*.xml              # 按钮底（主操作 / 停止）
    │   ├── widget_nav_active.xml         # 2x4 侧边栏选中底
    │   ├── widget_avatar.xml             # 设备头像圆底
    │   └── ic_widget_*.xml               # 小组件矢量图标
    ├── layout/
    │   ├── widget_linyu_2x2.xml          # 小组件布局（2x2，任何尺寸都用这套，靠 weight 自适应）
    │   └── widget_linyu_2x4.xml          # 小组件布局（2x4，含三页）
    ├── drawable-nodpi/
    │   ├── widget_preview_2x2.png        # 组件选择器预览图（不随屏幕密度缩放）
    │   └── widget_preview_2x4.png
    ├── xml/
    │   ├── network_security_config.xml    # 网络安全配置（仅允许 MQTT 明文）
    │   ├── file_paths.xml                 # 日志导出 FileProvider 路径
    │   ├── widget_info_2x2.xml            # 小组件配置（2x2）
    │   ├── widget_info_2x4.xml            # 小组件配置（2x4）
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
│  FloatingPillNavBar / AppBackgroundLayer│
├─────────────────────────────────────────┤
│  主题层                                 │
│  Theme (AppColors 组合局部)             │
│  CircularRevealThemeHost (圆形揭示过渡) │
├─────────────────────────────────────────┤
│  ViewModel 层                           │
│  LoginViewModel / MainViewModel         │
├─────────────────────────────────────────┤
│  数据层                                 │
│  AuthRepository / NetworkModule         │
│  QzxyService / SafeApi / GithubApi      │
│  PrefsHelper / MqttManager              │
│  BluetoothScanner / BackgroundManager   │
│  AppLogger                              │
├─────────────────────────────────────────┤
│  模型层                                 │
│  BaseResponse / LoginData / DeviceInfo  │
│  ActiveOrder / MqttOrderMsg             │
└─────────────────────────────────────────┘
```

### 主题与背景实现要点

- 配色通过 `CompositionLocal`（`LocalAppColors`）向下传递，主题切换时走**重组**而非重建，因此不会丢状态
- 强调色可被自定义背景提取出的主题色覆盖
- 背景配置分 `home` / `shower` 两个 scope 独立存储，`AppBackgroundLayer` 按当前是否洗澡中选取对应 scope 渲染
- 状态栏透明度跟随背景启用状态自动切换

### 桌面小组件实现要点

- 小组件**不持有状态**，每次渲染都从 `PrefsHelper` 现读现算，因此不需要与 App 做状态同步
- 计时用 `RemoteViews.setChronometer()`：`Chronometer` 由启动器进程驱动，App 未运行也能实时走秒
  - 注意 `startedAt` 存的是 `System.currentTimeMillis()`（挂钟），而 `Chronometer` 要的是
    `SystemClock.elapsedRealtime()`（开机以来）基准，两者原点不同，必须换算后再传入
- 全程只用 RemoteViews 一等公民 API（`setTextViewText` / `setViewVisibility` / `setChronometer` /
  `setOnClickPendingIntent`），**不用** `setInt(id, "setXxx", ...)` 反射写法——
  框架对反射方法有 `@RemotableViewMethod` 白名单，不通过会让整个小组件渲染失败
- 「开 / 关」两个圆形按钮做成两个 TextView 切换 visibility，规避上述反射限制
- 2x2 **只有一套布局**，被拉宽拉高都靠 `layout_weight` 自适应。
  v2.2.1 曾按宽高比切成「按钮在右侧」的横向版，但横向版里卡片固定 96dp、圆球固定 58dp，
  都不跟尺寸缩放，拉大只会多出空白；判据 `minWidth > minHeight` 又过于灵敏，
  稍微一拉就跳过去，v2.2.2 已整体移除
- ⚠️ **PendingIntent 的目标组件必须是 Manifest 里注册过的 receiver**。
  基类 `LinYuWidgetProvider` 没有注册，把广播发给它会**被系统静默丢弃**（无异常、无日志），
  表现就是「点按钮毫无反应」。渲染时需反查该 widget id 属于 `LinYuWidget2x2` 还是 `LinYuWidget2x4`
- 开阀 / 关阀逻辑与 App 共用 `data/ShowerController.kt`；小组件侧只额外限制确认轮询预算（6 秒），
  超时返回「状态未知」并提供手动刷新，而不是谎报成功或失败
- 状态推送：App 内进入 / 退出洗澡、自动关停时调用 `LinYuWidget.refreshAll(context)`

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
| Jetpack Security Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences 加密存储 |
| CameraX | 1.4.2 | 相机预览（扫码） |
| ML Kit Barcode | 17.3.0 | 二维码识别 |
| AndroidX Core | — | FileProvider（日志导出）、WindowCompat（边到边） |
| AppWidget / RemoteViews | 平台内置 | 桌面小组件（无额外依赖） |

### R8 混淆兼容方案

R8 full mode 会擦除 Kotlin suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
1. Retrofit 接口返回 `Call<ResponseBody>`（非 suspend，非泛型）
2. `SafeApi.kt` 中定义扩展函数，使用 `suspendCancellableCoroutine` 桥接回调
3. 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段
4. 完全不依赖 Gson 的泛型反射，R8 无法破坏

同样的思路也用在 `GithubApi.kt` 上：更新检测不引入 GitHub SDK，直接解析 GitHub REST 返回的 JSON，任何一步失败都返回 null / 空列表，绝不阻塞主流程（国内网络访问 GitHub 常失败）。

### 网络安全配置

- HTTPS API（`v3-api.china-qzxy.cn`）：正常 HTTPS
- MQTT（`tcp://47.107.37.60:1883`）：明文 TCP（趣智校园原始协议，无法修改）
- 其他所有明文流量：禁止

---

## 构建说明

### 环境要求

- Android Studio（推荐自带 JBR/JDK 21）
- JDK 17+（本项目实测使用 Android Studio 自带 JBR/JDK 21 构建）
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
| 各校部署差异 | 已在多所学校被实际使用。各校接入的设备类型、BLE 广播名、MQTT 地址可能不同：多数学校装发行版即可，少数需自行改代码 |
| projectId | **不需要手动配置**：登录响应里的 `userAccount.projectId` 会被自动保存并沿用到后续请求，代码中没有硬编码任何学校的 projectId |
| 挤号检测有最多 25 秒延迟 | 靠心跳轮询实现（v2.2.0 前是完全发现不了） |
| 饮水机未实机验证 | 识别与 UI 已实现，但作者所在学校无直饮水机，实际控制流程未验证 |
| 小组件无实时消费 | 小组件不连 MQTT，使用中只显示预扣金额；停止后也不做账单结算 |
| Android 11 及以下仍需定位权限 | 系统对蓝牙发现的硬性规定，无法绕过 |
| 自动填充依赖厂商 ROM | 不同厂商密码管理器行为差异较大，未在多机型验证 |
| 实时扣费 | MQTT 仅在订单结束时推送消费金额，洗澡中无实时扣费（官方 App 也是如此） |
| 结算延迟 | 账单生成有延迟，消费金额最长需等待约 20 秒 |
| 一卡通余额 | 无法获取真实余额（易校园 API 有 HMAC-SHA256 签名保护），仅支持手动估算 |
| MQTT 明文 | 趣智校园 MQTT 服务器不支持 TLS，通信内容未加密 |
| 密码安全 | 趣智校园使用 MD5 取后 10 位作为密码，安全性较低（官方协议限制） |
| 学校适配 | 不同学校的趣智校园服务器可能不同，需修改 projectId、BLE 过滤名、MQTT 地址 |
| 深色模式 | 登录页面和洗澡页面的深色模式适配为硬编码颜色切换，非完全动态 |
| 多语言 | 仅支持中文 |

### 历史问题的修复记录

| 原问题 | 状态 |
|---|---|
| 短信登录 secret 绑定账号 | ✅ v2.1.0：secret 由手机号推导（`SignUtils`），任何手机号可用 |
| 被挤号后重登又被弹出、需登两次 | ✅ v2.2.0：旧会话在途请求会清掉新会话凭证，改为会话级作用域整体取消 |
| Android 12+ 被迫要定位权限 | ✅ v2.2.0：`neverForLocation` + 改由用户主动触发申请 |
| 退出登录后上一任计时器残留 | ✅ v2.2.0：`clear()` 按前缀删除 `startedAt_` / `autoDiscon_` |
| 切到「我的」页面卡顿 | ✅ v2.1.0：卡片顺序 / Release / 仓库信息改为进程级缓存 |
| 设备名残留「表」字 | ✅ v2.1.0：修正正则顺序，`热水表-xxx` 不再被截断 |
| 使用页退出按钮点击无响应 | ✅ v2.1.0：修正组件层级，按钮不再被上层 Column 拦截 |
| 使用页「已预扣」卡片不透明色块 | ✅ v2.1.0：补 `Color.Transparent`（Surface 默认不透明） |
| 主题切换跳回首页 / 闪烁 | ✅ v2.1.0：状态移出过渡容器 + 遮罩先绘一帧 |
| 版本号硬编码 | ✅ v2.1.0：改读 `BuildConfig.VERSION_NAME` |

---

## 版本历史

### v2.2.3 (2026-09-16)

- **更新包支持国内镜像下载**：默认走 Gitee（实测 1.9 MB/s），GitHub 约 100 KB/s。检查更新仍走 GitHub API，只有下载换源；更新卡片里有开关可切回 GitHub
- 修复：小组件账单页余额偏高（只减了 2 笔账单，App 用 20 笔）、扫描失败清空附近设备快照、预发布被当成正式版、「上次消费」不跟设备走
- 增加 Gitee 镜像仓库，两个仓库互相加了链接

### v2.2.2 (2026-09-16)

- **包体积 42.6 MB → 12.5 MB**：
  - 图标原先同一个 1254×1254 PNG 被复制了 16 份（5 密度 × 3 名字 + app_logo），占 11.6 MB；按各密度重建并改用调色板 PNG
  - 只打包 `arm64-v8a`（x86 / x86_64 的 ML Kit so 合计 11.5 MB，只有模拟器用得到）
  - 移除 `material-icons-extended`（只用到 2 个图标，R8 却残留 10660 个图标类）
  - 资源语言限定 `zh` / `en`
  - ⚠️ 由此**不再支持纯 32 位设备**
- 小组件「选用」改为在桌面后台切换控制设备，不再跳回 App 弹详情
- 修复：小组件账单页余额不跟消费变化、选用点错设备（PendingIntent requestCode 冲突）、
  余额先闪初始值再跳变、短信倒计时延迟
- 蓝牙扫描 10 秒 → 5 秒；2x2 取消横向圆球布局，任何尺寸都用同一套卡片布局；
  附近设备页区分「扫过没有」与「没扫过」；密码框加一键清空

### v2.2.1 (2026-09-15)

- **桌面小组件重做**：改成深色中性液态玻璃 + 白字的固定配色，不再按壁纸明暗切深浅
- 小组件布局从 6 套（浅深各半）精简为 **3 套**（2x2 竖向 / 2x2 横向 / 2x4）
- 开关按钮只留图标；2x4 头部整行铺满、徽章贴最右
- 附近设备页补 dB 数值与「（x 分钟前）」扫描时间
- 操作状态全局同步 + 卡片转圈；卡片点击按状态分流（空闲→账单页，使用中→关阀）
- 深链：附近设备「选用」直接弹出设备详情；账单页 → App 账单页
- 修复：按钮点击无反应、2x2 拉宽后加载失败、附近设备页崩溃、预扣显示 ¥0.00、
  两个小组件计时错相位、跨布局徽章颜色不一致
- 首页设备名 / MAC 改为头部省略且不换行（系统字体放大也不会撑破卡片）

### v2.2.0 (2026-09-14)

- **桌面小组件**（2x2 / 2x4，桌面直接启停热水，Chronometer 实时计时）
- **扫描权限按需申请**（Android 12+ 不再需要定位权限）
- **登录页接入系统自动填充**（保存 / 回填账号密码）
- **挤号检测改为主动**（25 秒心跳轮询）
- 抽出 `data/ShowerController.kt` 共享网络层，App 与小组件共用开阀/关阀逻辑
- 修复：被挤号后重登又被弹出（需登两次）、退出登录后计时器残留、验证码框宽度跳变、
  更新日志显示 Markdown 星号、换背景图后参数被沿用
- 背景默认参数调整为 透明度 100% / 模糊 0 / 亮度 100%
- 关于项目卡片改版（显示 Star 数与联系方式）

### v2.1.0 (2026-09-14)

- **短信验证码登录通用化**（secret 由手机号推导，任何手机号可用）
- **饮水机支持**（识别 + 绿主题 + 冷热图标 + 设备名精简）
- **内置运行日志查看器**（脱敏 + 滚动 + 崩溃捕获 + 导出）
- **背景装扮**（主页 / 使用页双套配置 + 主题色提取 + 三项参数调节）
- **UI 升级**（悬浮胶囊导航栏、圆形揭示主题切换、页面弹簧滑移、液态玻璃卡片）
- 「我的」页面卡片可排序 / 可隐藏
- 应用信息卡片自动检测 GitHub 更新 + 折叠更新日志
- 关于项目卡片显示制作者与 Star 数
- 修复：切「我的」卡顿、设备名残留「表」字、使用页退出按钮无响应、使用页色块、主题切换丢状态与闪烁

### v1.2.0 (2026-08-08)

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
