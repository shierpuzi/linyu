# 趣智Lite (QuzhiLite) — 项目文档

## 项目概述

**趣智Lite** 是一款趣智校园第三方 Android 客户端。相比官方 App，它提供了更简洁的界面和更流畅的操作体验。

> ⚠️ 项目原名「淋浴 / LinYu」，包名 `com.hualala.linyu`。2026-10-24（开发日志 #30）起改名为
> **趣智Lite**、包名 **`com.quzhi.lite`**。代码里的 `LinYu*` 类名 / 资源名**刻意保留**
> （只是品牌改名，不是重构）。Git 仓库仍叫 `linyu`、根目录仍是 `linyu/`。

它**不只做洗浴**——按计费模型覆盖四类设备：

| 设备 | 计费 | 说明 |
|---|---|---|
| 热水器（热水表）/ 洗手台 | 按量（预扣 + 结算） | 可中途关阀，走按量计费全屏操作页 |
| 洗衣机 / 吹风机 | **按套餐一次性扣全款** | 无停止 / 退款接口，服务端到点自动结束 |
| 饮水机 | 按量（与洗衣同一项目，但计费不同） | 未实机验证 |
| 蓝牙水表 | 按量（**手机当 BLE 网关**） | 部分学校的表不联网，由手机直连开阀 / 关阀 / 结算 |

此外还有桌面小组件（1x1 / 2x2 / 2x4）、ColorOS 流体云胶囊倒计时、收藏设备、消费趋势与消费合并。

| 项 | 值 |
|---|---|
| 应用名称 | 趣智Lite（dev flavor 为「趣智Lite·测试版」） |
| 包名 | `com.quzhi.lite`（dev flavor：`com.quzhi.lite.dev`，可与官方 App 共存） |
| 版本 | **v3.2.1（versionCode 18）**；dev 包版本名为 `3.2.1-dev` |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 |
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 16 (API 36) |
| APK 体积 | 正式版 `app-store-release.apk` **12.77 MB**（R8 混淆 + 资源压缩）；dev 测试包 `app-dev-debug.apk` **21.11 MB** |
| 签名 | 正式版 **release**（RSA 2048 / SHA384withRSA，证书 SHA-256 `81:7F:13:F0:...:E7`，可自行核对）；dev 包仍 debug。keystore **不在仓库内** |
| 支持的 ABI | 仅 `arm64-v8a` |
| 后端 API | 趣智校园 `v3-api.china-qzxy.cn`（请求里伪装客户端 `version=6.5.28`） |
| 代码仓库 | GitHub `shierpuzi/linyu`（Gitee 镜像已置空，见「已知限制」） |
| 适用范围 | 使用趣智校园系统的学校（学校名称 / 板块列表由接口下发） |

其它文档：[交接文档.md](交接文档.md)（业务模型与代码地图，**接手先读这份**）、
[开发日志.md](开发日志.md)、[CHANGELOG.md](CHANGELOG.md)、
[API-qzxy.md](API-qzxy.md)、[开发者指南.md](开发者指南.md)、
[SECURITY.md](SECURITY.md)、[图标资产说明.md](图标资产说明.md)。

---

## 功能清单

### 核心功能

| 功能 | 说明 |
|---|---|
| 多板块（项目）切换 | 一个手机号可绑**多个独立项目**（实测 4000 洗浴 / 3356 洗衣饮水吹风），各有独立钱包、账单、设备。`loginCode` 全局一套，但 `projectId` + `accountId` **必须成对切换**，只换 projectId 会报「没有项目权限」。入口在首页标题下方，绑定 2 个以上项目时才显示 |
| 密码登录/登出 | 手机号 + 密码登录，MD5 加密，loginCode 加密持久化，挤号检测 |
| 短信验证码登录 | v2.1.0 通用化：`secret = MD5(前3位+后4位+"klcx")` 由手机号推导，任何手机号可用 |
| loginCode 直登 | 粘贴含 `loginCode`/`userId` 的 URL 或手填字段直接登录，免验证码（登录页链到[抓包教程](WIKI-抓包获取loginCode.md)） |
| 蓝牙扫描设备 | BLE 低功耗蓝牙扫描附近设备，按信号强度显示（强/中/弱） |
| 蓝牙水表直连 | v3.1.0：部分学校的表不联网（服务端 `isBle` 为真），手机当网关通过 BLE 直连设备开阀 / 关阀 / 结算（下发费率、采集消费、上传结算） |
| 蓝牙水表体验 | 关阀即结清、遗留订单自动补结算、App 被杀重开能恢复用水；通知栏「结束使用」与桌面小组件都能走蓝牙关阀（App 被杀也能关）。v3.1.1 起 `0x23` 状态帧同时支持 **28 字节短布局**与 31 字节及以上原布局 |
| 扫码绑定设备 | 扫描设备二维码，直接弹出设备详情（无需蓝牙） |
| 绑定寝室 | 在「选择附近」里选一间寝室（**没有手工输入**）。寝室键从设备名「掐头去尾」得到，同一间寝室的热水器和洗手台收敛成同一条。首页**和桌面小组件**三处都按它筛 |
| 设备入口统一分流 | 三条入口（首页卡片 / 扫码 / 收藏）统一走 `MainViewModel.routeAfterDeviceInfo()`：**套餐设备直达选套餐页**；**按量计费设备进全屏操作页**（仍要二次确认，不会点一下就出水） |
| 按量计费全屏操作页 | `ui/MeteredDeviceScreen.kt`：承载「未开始」「使用中」两态，替代原来的小弹窗；右上角心形按钮可收藏 |
| 套餐下单（洗衣机 / 吹风机） | `ui/ModeOrderScreen.kt` 同页两阶段：选套餐（价格 / 时长 / 「确认下单 ¥x.xx」）→ 使用中（剩余倒计时 + 刷新核对）。**故意没有「停止」按钮**——服务端没有停止 / 取消 / 退款接口 |
| 下单前二次确认 | `ui/ConfirmStartDialog.kt` 两种计费共用：按量形态显示设备名 + 预扣金额，套餐形态显示设备名 + 套餐名 + 金额 + 时长。确认后才真正发请求 |
| 收藏设备 | `data/FavoriteStore.kt`，**与「上次使用」是两回事**：多条（上限 30）、用户主动写入（附近设备长按 / 使用页右上角心形）、字段全落盘因此**离线也能渲染并分流**、杀后台保留；「上次使用」只有 1 条、每次操作被覆盖 |
| 备份 / 恢复 | 收藏与登录凭证可**分别或一起**备份；凭证**强制加密**（PBKDF2-HMAC-SHA256 21 万次 + AES-256-GCM，数据层强制）；走 SAF 选位置，**零存储权限**；恢复收藏**按 mac 合并**不覆盖；恢复凭证**先鉴权再落盘**。两个入口：设置页卡片、登录页「从备份包恢复登录 →」。⚠️ 密码忘了没有后门 |
| 双更新通道 | dev 测试包只看 pre-release、正式包只看正式版（`DEV_CHANNEL` buildConfigField + sealed `ReleaseResult`）。以前空列表同时表示「网络失败」和「该通道没版本」，一律提示「检测失败」 |
| 开始洗澡 | 调用 downRate API 开启设备，并轮询确认开阀成功 |
| 停止洗澡 | 调用 closeOrder API 关闭设备，确认关闭成功，失败有提示 |
| 洗澡中界面 | 全屏沉浸式界面，显示计时器、预扣金额、设备位置 |
| 自动关停倒计时 | 解析 autoDisConTime 显示闲置自动关闭倒计时 |
| 自动关停确认弹窗 | 设备自动关闭时弹确认框（设备名 + 时长 + 消费金额） |
| 待确认页防抢占 | 用户停在选套餐页 / 确认弹窗 / 按量计费页犹豫时，每秒查一次占用；查到的 `Busy` 显示顶部横幅并禁用按钮。**两种计费查不同接口、判据不能混用**（套餐看 `washer/status` 的 `errorCode==307`，按量看 `query/rateOrder/using` 的 `orderNo != null`），请求失败不算被抢 |
| 消费结算 | 关阀后直接调 `/order/consumeOrder/result/query` 问这一单结算了多少钱（约 1~2 秒），通知先显示「结算中」再原地更新成金额。⚠️ 该接口金额单位是**厘** |
| MQTT 实时推送 | 连接 MQTT 服务器接收实时消费金额更新（套餐设备的 orderNo 也走这条路） |
| 使用码启动检测 | 通过物理键盘使用码启动设备后，刷新可自动发现使用中的设备 |
| 多设备支持 | 支持同时管理多个活跃设备订单 |
| 上次使用设备 | 记住上次使用的设备，整卡可点（不再有「开始」按钮，避免列表滑动误触直接出水）；有活跃订单则恢复使用页、不重复扣费，否则走统一分流 |
| 饮水机支持 | 按 `bigTypeId == 5` 识别直饮水机，绿主题 + ❄️/♨️ 图标 + 名称精简（未实机验证）。⚠️ 饮水机在「洗衣饮水吹风」项目里，但**计费是按量**，必须走按量通道 |
| 桌面小组件 | 1x1 / 2x2 / 2x4 三种尺寸，桌面直接启停热水；深色液态玻璃卡片，计时由 `Chronometer` 驱动，App 不在也能走秒。列表行的设备头像仍是 emoji |
| ColorOS 流体云胶囊 | 套餐倒计时与「正在用水」常驻通知申请提升为状态栏胶囊 + 锁屏卡片（`android.requestPromotedOngoing` + `shortCriticalText`）。⚠️ 需用户手动在系统设置里给本 App 开「流体云 / 实时动态」，没开就完全没有胶囊 |
| 胶囊内容按模块区分（#36） | 「正在用水」常驻通知的小图标改成**当前模块的 `ic_device_*` 矢量图**（原先一律水滴）、标题改成**短模块名**（`饮水机` / `洗衣机` / `热水器`…），正文 `正在使用 · 已用 N 分钟`。原先标题是 `正在使用 · 一长串设备名`，胶囊单侧文本被截断、左右不配平。⚠️ **没有「暂停」按钮**：后端没有 pause 接口（按量只有 `closeOrder`，一调就结束并结算；套餐连停止接口都没有） |
| 通知倒计时刷新 | **倒计时通知（1006）** 的胶囊文本**永远由 App 提供**：剩余 ≥2 分钟显示分钟级（`3分`，30 秒重发一次），<2 分钟切秒级（`01:30`，1 秒重发）。ColorOS 不实现 `when` 的回退渲染，这条不能改回「交给系统」。⚠️ 按量通知（1001）是**正计时**，反过来交给系统 Chronometer 走秒，**不写** `shortCriticalText`（写了会把走秒盖成静态串） |
| 通知自检入口 | 设置 → 通知 → 详细设置里两个按钮（测试倒计时通知 / 测试事件通知），**零成本验证通知链路，不用下单花钱** |
| 主动挤号检测 | 25 秒心跳轮询，被挤下线能及时弹提示（跟随 Activity 生命周期，退后台自动停） |
| 后台用水监控 | 前台服务 `ShowerWatchService`：超时自动关停与订单轮询脱离界面，退出 App、划掉最近任务都不影响。⚠️ **只服务按量计费设备**，套餐设备接进来会误判 |
| 系统通知 | 用水期间常驻状态条（带「结束用水」按钮），结束 / 超时 / 设备被占用 / 开阀失败各有一条；总开关 + 细分开关、横幅单独开关 |
| 个人信息卡片 | 姓名、学号、学校自动获取（三个接口互为兜底）；「账号管理」折叠区可换手机号、改密码 |
| 更换手机号 | 验证码发到**新**号（`typeId=5`），改完即时生效 |
| 修改密码 | 密码方式走 `/user/password/update`；**没设过密码 / 忘了密码**可走 `/user/password/forget` 用短信验证码重置。⚠️ 登录页没有「忘记密码」入口，得先进 App 在设置里改 |

### 钱包与账单

| 功能 | 说明 |
|---|---|
| 一卡通真实余额 | 读 `/settlement/campus/userInfo` 显示校园卡实际余额；未签约免密支付时回落到趣智校园钱包，再拿不到才用「初始余额 − 账单消费」的本地估算并在界面注明 |
| 余额口径按板块 | 设备页显示的余额取**该设备所属板块**的钱包（`data/DeviceBoardPolicy.kt`），不是当前板块——停在洗衣板块点开热水器时，显示的是洗浴钱包 |
| 余额估算 | 手动填写初始余额，根据账单自动扣减（真实余额拿不到时的兜底） |
| 请求代扣 | 残留账单（12 点后结束、余额不足导致没扣成）可在账单上手动补扣，走 `/order/thirdTrade/single/json/pay`。超时 / 取消按「结果未知」处理，不诱导重复扣款 |
| 账单查询 | 查看当月最近 20 条消费记录；刷新时优先显示账号隔离的本地快照 |
| 消费趋势 | `ui/SpendingTrendCard.kt`：近 7 天 / 本月切换、**柱形 / 折线**切换、峰值标注、卡片可折叠；**选择会被记住**，退出再进来不复位 |
| 消费合并 | `data/BillMerger.kt`：把多个板块的账单拼成一份，回答「今天一共花了多少」；趋势图带「合并」角标。⚠️ 合并列表**只用于展示**，关阀 / 结算 / 对账必须用当前板块自己的账单（跨板块 orderNo 可能撞号） |
| 账单详情 | 点击账单查看设备名、设备类型、消费金额、消费时间、订单号 |
| 下拉刷新 | 主页和钱包页面均支持下拉刷新 |

### 使用码

| 功能 | 说明 |
|---|---|
| 使用码显示 | 显示当前使用码，后三位蓝色高亮（与手机号后三位相同）；没领过时显示「尚未领取」而不是一直「加载中」 |
| 使用码领取 | 预览式领取：`generate` 只出候选码，`set` 才生效，所以**取消是零成本的**；「换一个」每天 20 次，换出的码有 3 分钟领取时限 |
| 使用码开关 | 远程开启/关闭使用码功能 |

### 用户体验

| 功能 | 说明 |
|---|---|
| 深浅主题 | 手动切换浅色/深色模式，圆形揭示过渡动画，设置持久化保存 |
| 悬浮胶囊导航栏 | 弹簧滑块跟随选中项，点击无水波纹（仅滑块滑动反馈） |
| 页面切换动画 | 三大主页之间左右视差滑移 + 淡入淡出弹簧动画 |
| 液态玻璃卡片 | 半透明底色 + 微光描边 + 零阴影 |
| 背景装扮 | 主页 / 使用页各一套独立背景，自动提取主题色，透明度 / 模糊 / 亮度可调；记住上次在看的范围 |
| 卡片自定义 | 「我的」页卡片可上下排序、隐藏显示，状态持久化 |
| 我的页改版 | 收敛为个人信息 / 使用码 / 绑定寝室；通知、背景装扮、应用信息、关于项目移到右上角设置按钮，设置页与背景页独立 |
| 消费金额显示到厘 | `0.025` 就是 `0.025`（原先只保留两位、四舍五入成 `0.03`），全项目统一走 `MoneyFormat.milliToYuan()` |
| 应用内更新 | 读取 GitHub Release 按版本比对、折叠展示更新日志，并可在应用内下载安装。**双通道**：dev 包只看 pre-release、正式包只看正式版。⚠️ **Gitee 镜像已置空**（`GITEE_REPO=""`），下载走 GitHub 直链，大陆实测约 100 KB/s；日志默认只列最近 3 个版本 |
| 内置运行日志 | 环形缓冲 + 文件滚动 + 敏感信息脱敏 + 崩溃捕获 + 一键导出（`ui/LogViewerDialog.kt` + `utils/AppLogger.kt`） |
| 扫码手电筒 | 扫码界面提供手电筒，开启后变暖黄 + 光晕 + 填充图标 |
| 扫码相册识别 | 从相册选图识别二维码（`PickVisualMedia`，不需要存储权限） |
| 加密存储 | 登录凭证用 EncryptedSharedPreferences 加密存储 |
| 自动填充 | 登录输入框标注自动填充身份，手机密码管理器可保存 / 回填账号密码 |
| 扫描权限按需申请 | 不再登录后自动弹窗；Android 12+ 用 `neverForLocation` 免掉定位权限 |
| 学校名称自动获取 | 读 `/project/info/triple` 的 `projectName`，不用手填 |
| 网络异常提示 | 断网时显示友好提示（自定义 Toast，带应用图标） |
| 挤号检测 | 在其他设备登录同一账号时弹出强制下线提示（25 秒心跳轮询，主动发现） |
| 屏幕旋转 | 使用 rememberSaveable 保持登录状态和当前页面 |
| 退出确认 | 洗澡中退出登录时弹出警告提示 |

---

## 文件清单与项目结构

### 仓库根目录

```
linyu/
├── app/                    # 唯一 Gradle 模块（Compose App）
├── assets/                 # README 配图（主页 / 钱包 / 账号 / 设备页 / 小组件截图）
├── design/                 # 设计稿（device-icons/ 的 SVG 源 + README）
├── docs/                   # 全部文档（本文、交接文档、开发日志、CHANGELOG、API、SECURITY、
│                           #   开发者指南、图标资产说明、WIKI-抓包获取loginCode、交付说明）
│   └── icons/              # 图标参考图（四档对比图 + 5 张设备单图）
├── tools/                  # 开发期脚本（不属于 App 代码）：抓包过滤器、在线会话、
│                           #   矢量图标预览器、图标生成器、夹角回归断言、凭证审计 verify_apk.py
├── gradle/                 # libs.versions.toml（版本目录）+ wrapper
├── build.gradle.kts        # 顶层构建脚本
├── settings.gradle.kts     # rootProject.name = "QuzhiLite"，include(":app")
├── gradle.properties
├── gradlew / gradlew.bat
├── LICENSE
├── README.md
└── .github/workflows/ci.yml  # GitHub Actions：编译 + 单测 + Lint
```

> `.gitignore` 排除 `local.properties`（含 `sdk.dir` + dev 包预置凭证）、
> `_icon_preview/`（图标预览输出）等。`_icon_preview/` 由 `tools/preview_vector.py` 生成，
> 要长期留存的图应拷到 `docs/icons/`。

### App 源码树（`app/src/main/`，共 98 个 Kotlin 文件）

```
app/src/main/
├── AndroidManifest.xml                    # 权限声明 + 3 个 widget receiver + 2 个前台服务 + FileProvider
├── java/com/quzhi/lite/
│   ├── MainActivity.kt                    # 主 Activity，底部导航、主题管理、返回键/返回手势
│   ├── QrScanActivity.kt                  # 扫码界面（CameraX + ML Kit，支持相册选图 / 手电筒）
│   │
│   ├── api/                               # 网络层
│   │   ├── QzxyService.kt                 # Retrofit 接口定义，35 个端点（全部返回 Call<ResponseBody>）
│   │   ├── NetworkModule.kt               # OkHttp + Retrofit 单例，认证拦截器（authFields + Config-Project 头）
│   │   ├── SafeApi.kt                     # xxxSafe() 扩展：awaitString + 手动 JSON 解析（绕开 R8 泛型问题）
│   │   └── GithubApi.kt                   # GitHub Release / 仓库信息；REPO_SLUG 是仓库地址的单一出处
│   │
│   ├── ble/                               # BLE 直连（蓝牙水表）
│   │   ├── BleController.kt               # GATT 连接 / 写指令 / 读响应、四条命令码、连接结果三态
│   │   ├── BleFrame.kt                    # 帧协议（纯函数）：0x60 帧头 + len + cmd + data + crc + 0x16
│   │   └── DeviceQueryState.kt            # 0x23 状态帧解析：28 字节短布局 / ≥31 字节原布局
│   │
│   ├── data/                              # 数据层（业务编排 + 可单测的纯逻辑）
│   │   ├── AuthRepository.kt              # 登录认证（密码 / 短信 / loginCode 三种）
│   │   ├── ShowerController.kt            # 按量计费开阀 / 关阀 / 结算共享层（App 与小组件共用）
│   │   ├── SettlementEngine.kt            # 结算查询引擎（结算结果回退、订单补写、金额换算）
│   │   ├── ShowerEvents.kt                # 服务 → 界面的进程内事件（无 replay，App 不在就丢）
│   │   ├── ShowerSafetyPolicy.kt          # 用水安全判据（纯逻辑）
│   │   ├── BalanceEstimator.kt            # 余额三档优先级 + 标题（真实值优先，含板块名）
│   │   ├── DeviceBoardPolicy.kt           # ★ 余额取哪个板块（按设备归属，纯逻辑可单测）
│   │   ├── ActiveOrderRepository.kt       # 活跃订单读写（锁 + 原子化，消除并发丢失）
│   │   ├── PendingSettlement.kt           # 待结算登记（用水时长在登记时就定下来）
│   │   ├── BillDateParser.kt              # 账单日期解析（java.time，线程安全）
│   │   ├── BillHistoryCache.kt            # 账号隔离的本地账单快照（刷新期间先显示它）
│   │   ├── BillMerger.kt                  # ★ 跨板块账单合并（可测；只做合并，不做切换）
│   │   ├── SpendingAnalytics.kt           # 消费趋势统计（近 7 天 / 本月，按设备时区）
│   │   ├── DeviceInfoCache.kt             # /device/info/mac 的短 TTL 缓存（60 秒）
│   │   ├── FavoriteStore.kt               # ★ 收藏设备（多条、上限 30、字段全落盘）
│   │   ├── BackupFormat.kt                # ★ 备份包格式（魔数 + version + 两种负载；format 无默认值）
│   │   ├── BackupCodec.kt                 # ★ PBKDF2-HMAC-SHA256 21 万次 + AES-256-GCM（纯 JVM 可测）
│   │   ├── BackupService.kt               # ★ 备份负载收集 / 写回 Prefs / 凭证落盘（收藏按 mac 合并）
│   │   ├── BackupFileIo.kt                # ★ SAF 读写（零存储权限；读前挡 >16MB）
│   │   ├── MacFormat.kt                   # 蓝牙空口广播地址 ↔ 服务端台账 MAC 换算
│   │   ├── KeyedTaskRegistry.kt           # 带 key 的任务登记（同 key 只跑一个）
│   │   ├── ModeOrderController.kt         # ★ 套餐下单 / orderNo 获取 / washer 状态三态
│   │   ├── ModeOrderPolicy.kt             # ★ 套餐纯逻辑：deviceKind 与 billingModel 的唯一判据
│   │   ├── ProjectRepository.kt           # ★ 板块切换 + withBoardContext
│   │   ├── BleShowerController.kt         # 蓝牙表开阀 / 关阀 / 结算（BLE 直连设备）
│   │   ├── BleArbiter.kt                  # 蓝牙操作仲裁器（App 与小组件互斥）
│   │   └── BleSignBuilder.kt              # 蓝牙水表通道的签名构造
│   │
│   ├── model/                             # 数据模型
│   │   ├── LoginModels.kt                 # BaseResponse<T>、LoginData、UserAccount、WalletData、
│   │   │                                  # OrderStatus、BillItem、BillDTO、UseCodeData、BillDetail、
│   │   │                                  # DownRateResult、CloseOrderResult
│   │   ├── DeviceModels.kt                # DeviceInfo（含 projectId/projectName 设备归属、
│   │   │                                  # billingModel、typeName/typeEmoji/typeLabel/typeColor、
│   │   │                                  # colorForEmoji）、NearbyDevice
│   │   ├── ProjectModels.kt               # UserProject / ProjectModule
│   │   ├── ModeOrderModels.kt             # WashMode / ModeOrderInfo
│   │   ├── ActiveOrder.kt                 # 活跃订单模型
│   │   ├── MqttModels.kt                  # MQTT 消息模型
│   │   ├── BleModels.kt                   # 蓝牙数据模型（指令 / 响应 / 会话）
│   │   └── WidgetCache.kt                 # 小组件离线快照（附近设备 / 账单）
│   │
│   ├── service/                           # 前台服务
│   │   ├── ShowerWatchService.kt          # 按量计费用水监控（超时自动关停 / 订单轮询，脱离界面）
│   │   └── WidgetBleService.kt            # 小组件蓝牙开阀 / 关阀（App 被杀也能关）
│   │
│   ├── ui/                                # UI 层
│   │   ├── LoginScreen.kt                 # 登录页（密码 / 验证码 / loginCode 三种 + 抓包教程链接
│   │   │                                  #   + 「从备份包恢复登录」入口）
│   │   ├── LoginViewModel.kt              # 登录状态机（isSmsMode + isCodeMode）
│   │   ├── LoginCodePolicy.kt             # loginCode 校验 + 从抓包 URL 抠参数（纯函数，可测）
│   │   ├── LoginSecretPolicy.kt           # 登录输入框字段清理策略
│   │   ├── MainScreen.kt                  # 首页：板块切换 + 收藏区块 + 附近设备 + 全屏页路由 + 确认弹窗
│   │   ├── MainViewModel.kt               # ★ 主 ViewModel（约 3600 行，改动要克制）
│   │   ├── ShowerScreen.kt                # 洗澡中界面（计时器、自动关停倒计时）
│   │   │                                  # ⚠️ 已被 MeteredDeviceScreen 取代，当前无调用点（死代码保留）
│   │   ├── MeteredDeviceScreen.kt          # ★ 按量计费全屏操作页（未开始 / 使用中两态）
│   │   ├── ModeOrderScreen.kt             # ★ 套餐下单 / 使用页（选套餐 → 剩余倒计时，无停止按钮）
│   │   ├── ConfirmStartDialog.kt          # ★ 下单前确认弹窗（两种计费共用一套外壳）
│   │   ├── DeductConfirmDialog.kt         # 代扣确认（两处入口共用一份文案）
│   │   ├── WalletScreen.kt                # 钱包页（真实余额、账单列表、请求代扣、下拉刷新）
│   │   ├── SpendingTrendCard.kt           # ★ 消费趋势图（柱形 / 折线 + 合并角标）
│   │   ├── TrendDisplayPolicy.kt          # 趋势图坐标 / 刻度 / 可见点（纯函数）
│   │   ├── TrendMergeLink.kt              # 趋势 ↔ 合并联动规则（纯函数，UI 与测试共用）
│   │   ├── UserScreen.kt                  # 我的页面（可排序卡片 + 进程级缓存）
│   │   ├── UserSettingsCards.kt           # 设置页卡片（通知 / 背景 / 应用信息 / 关于 / 趋势与合并）
│   │   ├── BackupCards.kt                 # ★ 设置页「备份 / 恢复」卡片（SAF 选位置，零存储权限）
│   │   ├── BackupRestoreScreen.kt         # ★ 登录页「从备份包恢复登录」（先鉴权再落盘）
│   │   ├── UserCardLayoutPolicy.kt        # 卡片排序 / 隐藏的纯逻辑
│   │   ├── UseCodeSection.kt              # 使用码卡片 + 兑换弹窗
│   │   ├── ProjectSelectionPolicy.kt      # 板块选择纯逻辑（配对校验 + 标签兜底）
│   │   ├── FloatingPillNavBar.kt          # 悬浮胶囊导航栏（弹簧滑块，点击无波纹）
│   │   ├── AppBackgroundLayer.kt          # 自定义背景渲染层（透明度 / 模糊 / 亮度）
│   │   ├── CustomBackgroundScreen.kt      # 背景装扮设置页（主页 / 使用页切换）
│   │   ├── DeviceDetailDialog.kt          # 设备详情弹窗（SN、MAC、预扣金额、状态）
│   │   ├── DeviceGlyph.kt                 # emoji → 矢量图标（#36 起只转发 utils/DeviceVisual）
│   │   ├── LogViewerDialog.kt             # 内置日志查看器
│   │   ├── LinYuToast.kt                  # 自定义 Toast（应用图标 + 深色背景）
│   │   ├── TailEllipsisText.kt            # 单行文本，放不下先缩字号、再省**开头**（设备名辨识度在房号）
│   │   └── theme/
│   │       ├── Theme.kt                   # 深浅主题配色方案（液态玻璃卡片）
│   │       └── CircularRevealTheme.kt     # 圆形揭示主题切换容器
│   │
│   ├── widget/                            # 桌面小组件
│   │   ├── LinYuWidgetProvider.kt         # Provider 基类 + LinYuWidget1x1/2x2/2x4 + 状态推送
│   │   ├── WidgetRenderer.kt              # 状态推断 + RemoteViews 渲染（无反射调用）
│   │   └── WidgetActions.kt               # 小组件点击动作（PendingIntent + 布局映射）
│   │
│   └── utils/                             # 工具层
│       ├── PrefsHelper.kt                 # 所有本地存储（认证、设备、余额、主题、寝室绑定、
│       │                                  # 板块缓存、收藏、倒计时、背景）
│       ├── PrefillAuth.kt                 # dev 包预置登录态（公开构建恒为空）
│       ├── MqttManager.kt                 # MQTT 连接管理（Paho 客户端）
│       ├── BluetoothScanner.kt            # BLE 扫描（按广播名过滤蓝牙水表）
│       ├── ScanPermission.kt              # 蓝牙扫描权限（按系统版本分流）
│       ├── MD5Utils.kt                    # 密码加密（MD5 取后 10 位）
│       ├── SignUtils.kt                   # 短信验证码 secret 计算（按手机号推导）
│       ├── KlcxkjCrypto.kt                # 蓝牙指令加解密（AES）
│       ├── KlcxkjSigner.kt                # 蓝牙指令签名（HMAC）
│       ├── Notifier.kt                    # 系统通知（含套餐倒计时 ID 1006 / 1007、胶囊提升、勿扰查询）
│       ├── NotifyTextPolicy.kt            # 通知文案 / 胶囊文本 / 重发节奏（纯函数，可测）
│       ├── DeviceVisual.kt                # ★ emoji → 图标 id / 模块短名（#36 起的唯一出处，
│       │                                  #   Compose 界面 / 小组件 / 通知三处都转发到这里）
│       ├── AppLogger.kt                   # 运行日志（脱敏 / 滚动 / 崩溃捕获）
│       ├── BackgroundManager.kt           # 背景图存取（主页 / 使用页两套配置）
│       ├── BackgroundState.kt             # 背景配置状态（Compose State）
│       ├── MoneyFormat.kt                 # 金额格式化 / 换算（**金额换算只用这个**，厘→元）
│       └── ApkUpdater.kt                  # 更新包下载 + 校验（包名 / 签名证书 / SHA-256）+ 调起安装器
│
├── res/                                   # 见下表
└── ../test/java/com/quzhi/lite/           # 纯 JVM 单测：34 个套件 / 286 用例（v3.2.1 实测）
```

### 资源目录（`app/src/main/res/`）

```
res/
├── drawable/
│   ├── ic_device_shower.xml               # 花洒（热水器）
│   ├── ic_device_sink.xml                 # 牙刷（洗手台）
│   ├── ic_device_washer.xml               # 滚筒洗衣机（#30 新增）
│   ├── ic_device_dryer.xml                # 吹风机（#30 新增）
│   ├── ic_device_faucet.xml               # 饮水机（机身版：竖长机身 + 外伸出水头 + 内凹接水槽）
│   ├── ic_launcher_foreground.xml / ic_launcher_monochrome.xml   # 桌面图标（Material You）
│   ├── ic_theme_dark.xml / ic_theme_light.xml / ic_room.xml / ic_star.xml / ic_close.xml
│   ├── ic_drop.xml / ic_warning.xml       # 水滴 / 警告（替代装饰性 emoji）
│   ├── ic_qr_code_scanner.xml / ic_receipt_long.xml / ic_photo_library.xml / ic_flashlight*.xml
│   ├── ic_notify_shower.xml / ic_scan_location.xml / ic_scan_signal.xml / ic_visibility*.xml
│   ├── ic_widget_*.xml                    # 小组件矢量图标（描边风格）
│   ├── widget_glass.xml / widget_inset.xml / widget_avatar.xml / widget_nav_active.xml
│   ├── widget_badge_{idle,using,occupied}.xml / widget_btn_{pill,stop}.xml
│   └── widget_tile_1x1_{idle,using,occupied}.xml
├── layout/
│   ├── widget_linyu_1x1.xml               # 1x1 整块一个开关，四态叠放切 visibility
│   ├── widget_linyu_2x2.xml               # 2x2 只此一套布局，靠 layout_weight 自适应
│   └── widget_linyu_2x4.xml               # 2x4 含三页（设备控制 / 附近设备 / 账单）
├── drawable-nodpi/
│   ├── app_logo.png                       # 登录页 logo
│   └── widget_preview_{1x1,2x2,2x4}.png   # 组件选择器预览图
├── mipmap-anydpi-v26/                     # 三层自适应图标（background / foreground / monochrome）
├── values/                                # strings.xml（app_name = 趣智Lite）、colors.xml、themes.xml
├── values-night/themes.xml                # 深色主题
└── xml/                                   # network_security_config / file_paths / backup_rules /
                                           # data_extraction_rules / widget_info_{1x1,2x2,2x4}
```

> ⚠️ `mipmap-*dpi/` 下的位图已删除（minSdk 26 起 anydpi-v26 全量覆盖，位图是死代码）。
> 改图标前先读[图标资产说明.md](图标资产说明.md)：里面有文件 ↔ 设备 ↔ **数据层 emoji key** ↔ 底色的
> 完整对应表、7 条硬约束、实测被否决的造型禁区、换图步骤。
> **`typeEmoji` 的 emoji 取值是持久化标识，不要改**（老用户 Prefs 与小组件快照里存的是旧值）。

---

## 技术架构

### 架构模式

项目采用简化的 MVVM 架构：

```
┌─────────────────────────────────────────┐
│  UI 层 (Compose)                        │
│  LoginScreen / MainScreen               │
│  MeteredDeviceScreen（按量全屏操作页）  │
│  ModeOrderScreen（套餐选单/使用页）     │
│  WalletScreen / UserScreen              │
│  SpendingTrendCard / UseCodeSection     │
│  ShowerScreen（v3.1.0 起已无调用点）    │
│  FloatingPillNavBar / AppBackgroundLayer│
│  ConfirmStartDialog / DeductConfirm     │
│  （+ QrScanActivity，扫码页，非 Compose）│
├─────────────────────────────────────────┤
│  主题层                                 │
│  Theme (AppColors 组合局部)             │
│  CircularRevealThemeHost (圆形揭示过渡) │
├─────────────────────────────────────────┤
│  ViewModel 层                           │
│  LoginViewModel / MainViewModel（3600行）│
│  ★ routeAfterDeviceInfo() 统一设备分流  │
├─────────────────────────────────────────┤
│  服务层（脱离界面）                     │
│  ShowerWatchService 前台服务：超时关停  │
│                      + 订单轮询         │
│  WidgetBleService   小组件蓝牙开/关阀   │
│  ShowerEvents 服务 → 界面的进程内事件   │
├─────────────────────────────────────────┤
│  业务编排层（两条计费通道各一套）       │
│  按量：ShowerController / BleShower...  │
│        SettlementEngine                 │
│  套餐：ModeOrderController              │
│  板块：ProjectRepository                │
│        (withBoardContext)               │
│  纯逻辑（可单测）：                     │
│  ModeOrderPolicy / DeviceBoardPolicy    │
│  BillMerger / FavoriteStore             │
│  SpendingAnalytics / NotifyTextPolicy   │
│  BackupCodec / BackupFormat             │
├─────────────────────────────────────────┤
│  数据层                                 │
│  AuthRepository / NetworkModule         │
│  QzxyService(35 端点) / SafeApi         │
│  GithubApi / PrefsHelper                │
│  MqttManager / BluetoothScanner         │
│  BleArbiter / BleSignBuilder            │
│  BackgroundManager / Notifier           │
│  BackupService / BackupFileIo           │
│  AppLogger / ApkUpdater                 │
├─────────────────────────────────────────┤
│  BLE 层                                 │
│  BleController（GATT 连接/写/读）       │
│  BleFrame（帧协议，纯函数）             │
│  DeviceQueryState（28B / ≥31B 布局）    │
│  KlcxkjCrypto / KlcxkjSigner（加签）    │
├─────────────────────────────────────────┤
│  模型层                                 │
│  BaseResponse / LoginData / DeviceInfo  │
│  BillItem / UseCodeData / UnpaidBill    │
│  AccountInfo / CampusUserInfo / Project │
│  UserProject / ProjectModule / WashMode │
│  ModeOrderInfo / ActiveOrder / MqttMsg  │
│  BleModels / WidgetCache                │
└─────────────────────────────────────────┘

> 架构图只列主要的。UI 层还有 `QrScanActivity`（唯一非 Compose 的页面——扫码）、
> `DeviceGlyph`（设备图标，emoji 与图标的渲染层映射）、`TailEllipsisText`、
> `LogViewerDialog`、`ProjectSelectionPolicy`、`TrendMergeLink` 等。
```

### 两条计费通道（核心架构分界）

分流依据是**计费模型**，不是设备类型（`ModeOrderPolicy.billingModel`，有单测锁着）：

| | 按量计费 `METERED` | 套餐计费 `PACKAGE` |
|---|---|---|
| 设备 | 热水器 / 洗手台 / **饮水机** / 蓝牙水表 | 洗衣机（bigTypeId 6）/ 吹风机（7） |
| 下单 | `downRate/rateOrder`（蓝牙表走 `downRate/bluetooth/rateOrder`） | `downRate/modeOrder` |
| 扣费 | 先预扣、按量结算 | **一次性扣全款，不可退** |
| 停止 | `closeOrder` 可以停 | **没有停止 / 取消 / 退款接口**，服务端到点自动结束 |
| 状态 | `query/rateOrder/using`（`orderNo == null` 才是空闲） | `washer/status`（`errorCode==307` 或 `success && data != null`） |
| orderNo | `downRateResult` 给 | 只能 MQTT / `lastOrder/byMac`（约 1 小时窗口） |
| 界面 | `MeteredDeviceScreen` | `ModeOrderScreen` |
| 后台监控 | 接 `ShowerWatchService` | **不接**（轮询信号源不对） |

> ⚠️ 四个「不能混」：饮水机同项目但按量、两种占用判据不能互换、套餐设备不能接用水监控、
> 套餐下单拿不到 orderNo 不能报失败（钱可能已扣）。详见[交接文档.md](交接文档.md) 第二、七节。

### 多板块（项目）模型

- 一个手机号可绑多个**独立项目**（实测 4000 洗浴 / 3356 洗衣饮水吹风），各自有 accountId、
  钱包、账单、设备；`loginCode` + `userId` 全局一套
- `projectId` + `accountId` **必须成对切换**（`ui/ProjectSelectionPolicy.kt` 收口配对逻辑），
  只换 projectId 服务端返回「没有项目权限」，界面上只表现为"数据是空的"
- `ProjectRepository.withBoardContext()` 临时借另一板块认证参数跑**只读**逻辑，**finally 必定还原**
- `ProjectRepository.withDeviceBoard()` 临时借另一板块认证参数跑**设备操作**（开阀/关阀/下单/结算），
  同样 finally 还原。与前者有两点不同：**失败也照样执行**（不让按钮静默失效）、
  **同板块/判断不出时直接透传**（零开销）
- ⚠️ **设备操作必须按设备自己的 `projectId` 走**：服务端校验设备归属，拿错板块回
  `errorCode=534 设备信息和项目信息不匹配`。详见「已知限制」P0 与
  [API-qzxy.md](API-qzxy.md) 的 534 说明
- ⚠️ **各校 projectId 编码不同**（不一定是 4000/3356），所以 `DeviceBoardPolicy` 只比较
  相不相等，**不含任何硬编码 id**；曾经有的 `BATH_PROJECT_ID = 4000L` 兜底已删除
- ⚠️ 已知技术债：合并/借板块期间认证参数是**全局的**，其他请求可能带错参数
  （已有 `isMerging` 挡重入 + 只发 GET + 失败静默降级，但没根治）

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
- 动画类的东西一律没有（RemoteViews 跑不了）。**但 `ProgressBar` 的不确定态是系统进程画的**，
  所以「正在开启…」的转圈是挂一个 `ProgressBar`，不是自己画动画
- **三种尺寸**（v3.0.1 加 1x1）：
  - `1x1` 整块就是一个开关，四态（`widget_tile_idle` / `using` / `occupied` / `busy`）叠在同一位置切 visibility
  - `2x2` **只有一套布局**，被拉宽拉高都靠 `layout_weight` 自适应。
    v2.2.1 曾按宽高比切成「按钮在右侧」的横向版，但横向版里卡片固定 96dp、圆球固定 58dp，
    都不跟尺寸缩放，拉大只会多出空白；判据 `minWidth > minHeight` 又过于灵敏，
    稍微一拉就跳过去，v2.2.2 已整体移除
  - `2x4` 带侧边导航，三个页面
- **1x1 的两处特殊处理**：
  - 「空闲」那块是三种情况共用的（真·空闲 / 未登录 / 没选过设备），只有顶部那行小字不同
  - 布局根节点加了内边距对齐应用图标的视觉大小。`minWidth/minHeight` 不是「组件多大」
    而是「至少需要多大」，桌面据此给格子后会把组件**拉伸填满**，不加内边距玻璃块会顶到格子边缘
- `DisabledReason.isNotice` 区分「过程」和「结果」：**过程**才转圈、才禁点击；
  失败/占用这类**结果**照常可点（用户多半想重试）
- ⚠️ **PendingIntent 的目标组件必须是 Manifest 里注册过的 receiver**。
  基类 `LinYuWidgetProvider` 没有注册，把广播发给它会**被系统静默丢弃**（无异常、无日志），
  表现就是「点按钮毫无反应」。渲染时需反查该 widget id 属于哪个尺寸的 receiver——
  **每加一个尺寸都要在 `forEachWidget` 的登记表里加一项**，否则那个尺寸收不到重绘
- 设备类型**在小组件里仍是 emoji**（账单行 / 附近设备行的圆头像）。App 界面用的是图标，
  两边的取舍见 `design/device-icons/README.md`
- 通知：横幅类走 `linyu_alert` 渠道（`IMPORTANCE_HIGH`）。⚠️ 渠道 importance 创建后改不了，
  所以「关掉横幅」是另建一条 DEFAULT 渠道二选一，不是改原来那条
- 开阀 / 关阀逻辑与 App 共用 `data/ShowerController.kt`；小组件侧只额外限制确认轮询预算（6 秒），
  超时返回「状态未知」并提供手动刷新，而不是谎报成功或失败
- **蓝牙水表**（v3.1.0 / v3.1.1）：RemoteViews 自身做不了 BLE，小组件的开阀 / 关阀交给前台服务
  `service/WidgetBleService.kt`（配合 `data/BleArbiter.kt` 与 App 互斥、`data/BleShowerController.kt`
  重连设备并用状态帧重建会话），因此 **App 被杀也能关阀**
- 状态推送：App 内进入 / 退出洗澡、自动关停时调用 `LinYuWidget.refreshAll(context)`
- ⚠️ v3.1.1 起小组件的 emoji 位从 `TextView` 改成 `ImageView`（图标 = 描边风格矢量，
  与设备图标实心风格不统一，属有意保留）：**已添加的桌面小组件更新后可能需要重新添加**才能正常渲染

### 通知与 ColorOS 流体云要点

- 统一入口 `Notifier.requestLiveUpdate(chipText)`，所有常驻通知共用，别各处复制
- **设备图标 / 模块名只有一个出处**：`utils/DeviceVisual`（`iconRes` / `moduleName`）。
  `ui/DeviceGlyph`、`widget/WidgetRenderer`、`utils/Notifier` 三处**全部转发**它，
  **不要在任何一处再写一份 `when`**——#31 就是这么出岔子的，
  「同一台设备在 App 里是洗衣机、在桌面和胶囊上是热水器」。
  映射放 `utils/` 而不是 `ui/` 的原因：`DeviceGlyph` 依赖 Compose，通知与 RemoteViews 碰不了 Compose

**按量常驻通知（ID 1001）的内容（#36）**

| 位置 | 内容 |
|---|---|
| 小图标 | 当前模块的 `ic_device_*` 单色矢量图（原先一律水滴 `ic_notify_shower`） |
| 大图标 | 同一个模块矢量图（标准样式的 `setLargeIcon`，**不是**自定义 RemoteViews） |
| 标题 | 短模块名 `饮水机` / `洗衣机` / `吹风机` / `洗手台` / `热水器`（原先 `正在使用 · 一长串设备名`，被截断且左右不配平） |
| 正文 | `正在使用 · 已用 N 分钟` |
| 右侧时间 | 系统 Chronometer，**不写** `shortCriticalText`（写了会把走秒盖成静态串） |
| 操作 | 只有「结束使用」。**故意没有「暂停」**：后端没有 pause 接口，按量只有 `closeOrder`（一调就结束并结算），套餐连停止接口都没有 |

- **套餐倒计时通知（ID 1006）只换了小图标**，标题/正文/chipText/`timeoutAfter` 一律不动
  （用户已确认那套样式满意，且套餐没有停止接口）
- 准入条件：标准样式 / `BigTextStyle` / `CallStyle` / `ProgressStyle` / `MetricStyle`；
  声明 `POST_PROMOTED_NOTIFICATIONS`（API 36.1 新增，compileSdk 36 的 android.jar 里没有，
  Manifest 只能按字符串写）；`EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升；`ongoing` + 有 `contentTitle`；
  胶囊文本 **≤7 字符**
- **胶囊要显示 + 走秒需要两条独立依赖**：① 用户在系统设置里开「流体云 / 实时动态」权限（代码无法代劳）；
  ② 通知带 `requestPromotedOngoing`（**不需要** `shortCriticalText` 也能提升）
- 但 ColorOS 16 **不实现 `when` 的回退渲染**，所以**倒计时那条**（1006）的胶囊文本**永远由 App 提供**：
  剩余 ≥2 分钟给分钟级（`3分`，30 秒重发），<2 分钟给秒级（`01:30`，1 秒重发）。
  ⚠️ 这条**只对倒计时成立**：按量的正计时（1001）反过来，**不能写** `shortCriticalText`，
  否则系统 Chronometer 的走秒会被盖成静态串
- `contentText` 里的「剩余 X 分 X 秒」是普通文本兜底，任何 ROM 都会渲染
- 通知 ID：`1001` 用水常驻、`1002` 结束、`1003` 自动关停、`1004` 被占用、`1005` 开阀失败、
  `1006` 套餐倒计时、`1007` 套餐结束。**套餐不能复用 1001**，否则与热水器通知互相覆盖
- 勿扰穿透是**特殊权限**（`ACCESS_NOTIFICATION_POLICY`），没授予时 `setBypassDnd(true)`
  会被系统**静默忽略**，所以代码只做「尽力而为」

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

### 接口一览（35 个端点）

全部定义在 `api/QzxyService.kt`，统一返回 `Call<ResponseBody>`（防 R8 擦泛型），
并配 `api/SafeApi.kt` 的 `xxxSafe()` 扩展。字段级细节、请求 / 响应示例见
[API-qzxy.md](API-qzxy.md) 与 [开发者指南.md](开发者指南.md)。

| 分组 | 端点 |
|---|---|
| 登录 / 账号 | `/user/login`、`/user/registerAndLogin`、`/user/verification/code/get`、`/account/info`、`/account/card/getBindCardInfo`、`/user/phone/update`、`/user/password/update`、`/user/password/forget` |
| 板块（项目） | `/user/project/list`、`/project/module/list`、`/project/info/triple` |
| 设备 | `/device/info/mac`、`/device/mode/list` |
| 按量计费通道 | `/order/tcpDevice/downRate/rateOrder`、`/order/tcpDevice/query/downRateResult`、`/order/tcpDevice/closeOrder`、`/order/tcpDevice/closeOrder/result/query`、`/order/tcpDevice/query/rateOrder/using`、`/order/consumeOrder/result/query` |
| 套餐计费通道 | `/order/tcpDevice/downRate/modeOrder`、`/order/tcpDevice/washer/status`、`/order/query/lastOrder/byMac` |
| 蓝牙水表 | `/order/downRate/bluetooth/rateOrder`、`/order/upload/bluetooth/data`、`/order/upload/bluetooth/fail` |
| 钱包 / 账单 | `/account/wallet`、`/settlement/campus/userInfo`、`/order/query/account/bill/list`、`/order/query/account/bill/detail` |
| 支付 / 代扣 | `/order/weixinScorePay/unPay/queryBill`、`/order/thirdTrade/single/json/pay` |
| 使用码 | `/account/useCode/new`、`/account/useCode/new/generate`、`/account/useCode/new/set`、`/account/useCode/new/status/update` |
| 更新检测（非趣智接口） | GitHub REST（`api/GithubApi.kt`，不引入 SDK，失败一律降级返回 null / 空列表） |

> ⚠️ 两个「不能混」：`rateOrder` 与 `modeOrder` 是两条完全不同的计费通道；
> `thirdTrade/single/json/pay` 是**会真扣钱**的接口，调用前必须有用户确认。

### R8 混淆兼容方案

R8 full mode 会擦除 Kotlin suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
1. Retrofit 接口返回 `Call<ResponseBody>`（非 suspend，非泛型）
2. `SafeApi.kt` 中定义扩展函数，使用 `suspendCancellableCoroutine` 桥接回调
3. 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段
4. 完全不依赖 Gson 的泛型反射，R8 无法破坏

同样的思路也用在 `GithubApi.kt` 上：更新检测不引入 GitHub SDK，直接解析 GitHub REST 返回的 JSON，任何一步失败都返回 null / 空列表，绝不阻塞主流程（国内网络访问 GitHub 常失败）。

### 网络安全配置

- HTTPS API（`v3-api.china-qzxy.cn`）：正常 HTTPS，**无证书固定**
- MQTT（`tcp://47.107.37.60:1883`，`utils/MqttManager.kt` 硬编码）：明文 TCP（趣智校园原始协议，无法修改）。⚠️ 实测吹风机的 broker 是 `39.108.147.136:1883`，与硬编码值不同，见「已知限制」
- 其他所有明文流量：禁止（`res/xml/network_security_config.xml` 只对 MQTT 开放明文）
- 蓝牙水表通道：签名算法（`utils/KlcxkjCrypto.kt` / `utils/KlcxkjSigner.kt`）**密钥在客户端内**，可被逆向提取。详见 [SECURITY.md](SECURITY.md)

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

然后在项目根目录创建 `local.properties`（该文件在 `.gitignore` 里，不入库）：

```properties
# Android SDK 路径
sdk.dir=C\:\\Android

# release 签名（四项齐全且 keystore 文件存在才会创建 signingConfig）
KEYSTORE_FILE=../your-key.jks
KEYSTORE_PASSWORD=你的密钥库密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密钥密码
```

⚠️ **四项缺任意一项（或 keystore 文件路径不存在）时，`app/build.gradle.kts` 走
「Release APK 保持未签名」分支**——只打一条 warn 日志，Debug 与测试任务不受影响。
**这条现在是真会咬人的**：默认 `assembleStoreRelease` 会真的签名，但配置不全时它**静默产出未签名的包**，
构建显示 SUCCESS、装的时候报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`。所以出包后必须验一次：

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs `
    app\build\outputs\apk\store\release\app-store-release.apk
```

**本项目的实际配置（v3.2.1 起）**：四项都已配齐，keystore 文件是 `quzhi-lite-release.jks`，
**放在项目目录之外**（不在仓库里），别名 `quzhi-lite`，RSA 2048 / SHA384withRSA，有效期至 2054-03-16。
正式版证书 SHA-256 指纹：

```
81:7F:13:F0:56:B0:08:1F:C2:36:83:74:31:5F:BD:7C:CD:8E:0C:8B:AC:7C:2C:04:06:7E:21:EC:A5:AE:BA:E7
```

⚠️ **不要把 keystore 密码写进任何文档 / 提交**。指纹是公开信息，可以写；密码只存在
`local.properties`（gitignore）里，另有一份 AES-256 加密备份在私有仓库 `shierpuzi/quzhi-lite-keys`。
**丢了这把钥匙就再也无法给已装用户发更新**——只能换包名重来。

### 两个 Flavor（`store` / `dev`）

`flavorDimensions += "dist"`，产物名带 flavor 前缀：

| flavor | applicationId | 应用名 | 版本名 | 更新通道 | 用途 |
|---|---|---|---|---|---|
| `store` | `com.quzhi.lite` | 趣智Lite | `3.2.1` | 只看**正式版**（`DEV_CHANNEL=false`） | 原版，**不带任何凭证**（`PREFILL_*` 显式给空串）；**release 构建 + 真签名** |
| `dev` | `com.quzhi.lite.dev` | 趣智Lite·测试版 | `3.2.1-dev` | 只看 **pre-release**（`DEV_CHANNEL=true`） | 可与官方趣智校园 App 共存；可从 `local.properties` 注入预置登录态；debug 签名 |

**为什么有 dev 包**：三个 App（官方 / 原版 / 改过的）共用同一个 `loginCode`，
任何一个登录都会把另外两个挤下线。想一边保持官方 App 登录、一边验证第三方客户端，
就必须用不同包名。

**dev 包预置登录态**（`utils/PrefillAuth.kt` 在 `PrefsHelper.init()` 之后 `seed()`，已登录则跳过）：

```properties
QZXY_PREFILL_LOGIN_CODE=<登录后拿到的 32 位 loginCode>
QZXY_PREFILL_USER_ID=<userId>
QZXY_PREFILL_ACCOUNT_ID=<该板块的 accountId>
QZXY_PREFILL_PROJECT_ID=<该板块的 projectId>
QZXY_PREFILL_TELEPHONE=<手机号>
```

⚠️ 凭证**只在 dev flavor 注入**。历史教训：第一版把 `buildConfigField` 写在 `defaultConfig` 里，
结果原版包的 dex 里真的能搜到 `loginCode`——编译器不报、测试不报，只有扫产物才发现。
**发布前务必跑 `python tools/verify_apk.py` 审计**（脚本已入库，见根目录 `tools/`）。

### 构建命令

```bash
# 单元测试（⚠️ 任务名带 flavor，testDebugUnitTest 会失败）
./gradlew :app:testDevDebugUnitTest :app:testStoreDebugUnitTest
# Windows：
.\gradlew.bat :app:testDevDebugUnitTest :app:testStoreDebugUnitTest

# Debug 构建
./gradlew :app:assembleStoreDebug        # 产物：app/build/outputs/apk/store/debug/app-store-debug.apk
./gradlew :app:assembleDevDebug          # 产物：app/build/outputs/apk/dev/debug/app-dev-debug.apk

# Release 构建（R8 混淆 + 资源压缩；配置齐全时自动签名）
./gradlew :app:assembleStoreRelease
./gradlew :app:assembleDevRelease

# 若因网络无法下载 lint 依赖而失败，可跳过 lint 检查：
./gradlew :app:assembleStoreRelease -x lintVitalRelease
```

> **测试规模**（**v3.2.1 实测**）：**34 个套件 / 286 个用例 / 0 失败**。
> 这个数字**会随开发变化**，以实际运行结果为准，不要当成固定指标。
> 演进：10-24 末是 29 / 235 → #36 加 `DeviceVisualTest`(8) 后 32 / 266 →
> #37 加 `BackupCodecTest`(17) + `BackupDecodeMessageTest`(3) 后 **34 / 286**。
> 单测全部是纯 JVM 测试（`app/src/test/java/com/quzhi/lite/`），覆盖纯逻辑层：
> `ModeOrderPolicyTest` / `ProjectSelectionPolicyTest` / `BillMergerTest` /
> `FavoriteStoreTest` / `SpendingAnalyticsTest` / `NotifyTextPolicyTest` /
> `DeviceBoardPolicyTest` / `PreemptBothBillingTest` / `SkipDetailPolicyTest` /
> `TrendMergeLinkTest` / `AppLoggerMaskingTest` / `DeviceVisualTest` /
> `BackupCodecTest` / `BackupDecodeMessageTest` 等。

### APK 输出位置

实测体积（v3.2.1）：

```
app/build/outputs/apk/
├── store/
│   ├── debug/app-store-debug.apk        # 原版 Debug（未混淆、不含凭证）        21.58 MB
│   └── release/app-store-release.apk    # 原版 Release（R8 混淆 + 资源压缩，已签名）12.77 MB
└── dev/
    ├── debug/app-dev-debug.apk          # dev 包（预置登录态，可与官方 App 共存）21.11 MB
    └── release/app-dev-release.apk
```

### Release 构建配置

`app/build.gradle.kts` 中的 Release 配置（注意 `signingConfig` 是**条件**赋值的）：

```kotlin
val releaseSigningConfig = if (releaseSigningReady) {
    signingConfigs.create("release") {
        storeFile = file(releaseKeystorePath!!)
        storePassword = releaseSigningProperties.getProperty("KEYSTORE_PASSWORD")
        keyAlias = releaseSigningProperties.getProperty("KEY_ALIAS")
        keyPassword = releaseSigningProperties.getProperty("KEY_PASSWORD")
    }
} else {
    logger.warn("未找到完整且有效的 release 签名配置，Release APK 将保持未签名；Debug 与测试任务不受影响。")
    null
}

buildTypes {
    release {
        isMinifyEnabled = true        // 启用 R8 代码混淆
        isShrinkResources = true      // 移除未使用的资源
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = releaseSigningConfig   // 可能为 null → 未签名
    }
}
```

### 其它构建事实

- `compileSdk = 36`、`targetSdk = 36`、`minSdk = 26`
- ABI 只打 `arm64-v8a`（ML Kit 的 x86/x86_64 so 合计约 11.5 MB，真机永远用不到）。
  ⚠️ 代价：**纯 32 位老机型装不上，x86 模拟器也装不上**
- `resourceConfigurations += listOf("zh", "en")`：界面只有中文，防止 androidx / material
  自带的几十种翻译全进 `resources.arsc`
- Java / Kotlin 目标版本 11；AGP 8.13.2 + Kotlin 2.0.21 + Gradle 9.4.1

### CI

`.github/workflows/ci.yml`：push / PR / 手动触发时在 `ubuntu-latest` 上跑
**编译 → 单元测试 → Lint**，失败也会上传报告（`app/build/reports/`）。

- JDK 17（AGP 8.13 要求）+ 镜像自带 Android SDK（不用已停维护的 `setup-android`）
- 缓存 `~/.gradle/caches` 与 `~/.gradle/wrapper`
- 同一分支连续 push 用 `concurrency` 取消上一次，省额度
- 真机蓝牙、设备兼容性、UI 外观这些 CI 管不到，仍需人工验证
- ⚠️ 注意 CI 里跑的是 `./gradlew testDebugUnitTest`（无 flavor 前缀，历史配置），
  本地开发请用带 flavor 的任务名

---

## 已知限制

> 以下按 [交接文档.md](交接文档.md) 第六节（P0 / P1 / P2）对齐，并保留原文档仍然成立的条目。

### P0 · 会误导用户

| 限制 | 说明 |
|---|---|
| **跨板块设备操作需真机验证（#35）** | 设备操作现在按设备自己的 `projectId` 借板块认证参数，修掉了 `errorCode=534 设备信息和项目信息不匹配`。**「服务端是否真的接受借来的板块参数」尚未真机实测**——这是该修复的成败点。需验：① 洗浴板块开饮水机 ② 关阀能结算 ③ 洗浴板块给洗衣机下单 |
| **MQTT broker 硬编码不匹配** | `utils/MqttManager.kt` 硬编码 `tcp://47.107.37.60:1883`，而吹风机实测 broker 是 `39.108.147.136:1883`。后果是套餐设备的 orderNo 拿不到 MQTT 推送，只能退到 `lastOrder/byMac`（慢几秒，功能不挂） |
| **ColorOS 流体云胶囊需用户手动开权限** | 代码无法代劳。没在系统设置 → 通知与状态栏 → 流体云里给本 App 开「实时动态」权限，就**完全没有胶囊**。已加文字兜底 + 30 秒 / 1 秒重发，胶囊本体的真机表现仍待验证。⚠️ #36 把胶囊内容改成「模块矢量图 + 短模块名」后，**仍未真机验证**——胶囊是否真的左右配平、图标是否显示，要用户自己开权限后看 |
| **#36 按量通知的 `setLargeIcon` 可能妨碍提升** | 用户要求通知栏右侧方图也换成模块矢量图，用的是标准样式的 `Icon.createWithResource`（OPPO 禁止的是自定义 RemoteViews，这个是合法的）。但「合法」不等于「这台 ROM 一定认」。若真机发现胶囊退化成普通通知，**删掉 `showInUse` 里 `setLargeIcon(...)` 那一行即可**，其余字段都不依赖它 |
| **签名只证明同源，不证明正版** | v3.2.1 起正式版已是 **release 签名**（证书 SHA-256 `81:7F:13:F0:...:E7`，可自行 `apksigner verify --print-certs` 核对）。但别人可以用**自己的密钥**造一个同样「签名自洽」的包，所以**下载渠道仍是唯一身份凭据**。详见 [SECURITY.md](SECURITY.md) 第二节 |
| **`assembleStoreRelease` 缺签名配置时静默产出未签名包** | 四项 `KEYSTORE_*` 缺任意一项（或 keystore 路径不存在）时，Gradle **只 warn、然后照常 SUCCESS**，产出的 APK 装不上。出包后必须跑一次 `apksigner verify --print-certs` |
| **饮水机未实机验证** | 识别与 UI 已实现（`bigTypeId == 5`），但作者所在学校无直饮水机，实际控制流程未验证。且它在「洗衣饮水吹风」项目里却**按量计费**，不能并进套餐通道 |
| **Gitee 镜像已置空** | `api/GithubApi.kt` 的 `GITEE_REPO = ""`，检查更新与下载都走 GitHub 直链，大陆实测约 100 KB/s。要恢复加速需在 Gitee 用同名 tag + 同名附件发版 |

### P1 · 技术债

| 限制 | 说明 |
|---|---|
| **合并期间认证参数是全局的** | `withBoardContext` 临时改全局认证参数跑只读逻辑，其他并发请求可能带错参数。已有 `isMerging` 挡重入 + 只发 GET + 失败静默降级，但没根治。根治要给每板块一套 `NetworkModule` |
| `bill/detail?consumeDate=` 未接入 | 它是**无时间窗口**的 orderNo 兜底（比 `lastOrder/byMac` 稳），但手机时钟与服务端时钟差几十秒就查不到，要 ±2 分钟试探，目前没做 |
| `MainViewModel.kt` 约 3600 行 | 改动要极度克制；少 import 符号时 Kotlin 会报「String 不匹配 String」这种完全看不出问题的错误 |
| 套餐设备的「到点提醒」用 `viewModelScope` | App 被杀后提醒不会触发（通知栏 Chronometer 仍会走） |
| `selectedDevice` 的 setter 会发网络请求 | 好处是所有入口自动生效，代价是隐藏副作用：任何赋值都会触发一次跨板块余额查询 |
| `DeviceBoardPolicy.BATH_PROJECT_ID = 4000` 是硬编码兜底 | 只在服务端不下发 `projectId` 时才走到；换学校可能不对。已标 `ponytail:` |

### P2 · 体验 / 其它已知限制

| 限制 | 说明 |
|---|---|
| 仅 `arm64-v8a` | x86 模拟器、纯 32 位老机型都装不上 |
| 项目名首次是兜底名 | `knownBoardName` 把 4000→洗浴 / 3356→洗衣饮水吹风，切一次后才是服务端真名 |
| `/project/module/list` 用 4000 调报「没有项目权限」 | 不能靠它判断板块业务 |
| 老收藏条目没有 `projectId` | #29 之后新增的才有。从收藏进设备页会补查一次台账，网络不通则余额退回当前板块；取消再重新收藏一次即可彻底干净 |
| 登录页没有「忘记密码」入口 | 只有进 App 后在设置里能改。密码登录卡住时是死路（`/user/password/forget` 接口已实现，缺入口） |
| **备份密码忘了没有救** | 加密备份包只能用那个密码打开，App 里没有后门也没有找回入口——这是设计而不是遗漏。密码请存进密码管理器 |
| **明文备份是真明文** | 只备份收藏、且用户没勾加密时，文件里就是明文的设备列表（不含账号，这是有意选的默认值） |
| 蓝牙水表关阀要重连一次 | 约多 1~2 秒——设备只在**新连接**里给出可结算的记录；补结算完成后需要再点一次开阀 |
| 蓝牙水表「离线」状态未提前提示 | 缺服务端样本，判据未确认前不做判断，以免把可用设备误标为离线 |
| 各校部署差异 | 已在多所学校被实际使用。各校接入的设备类型、BLE 广播名、MQTT 地址可能不同：多数学校装发行版即可，少数需自行改代码 |
| projectId 不需手动配置 | 登录响应里的 `userAccount.projectId` 会被自动保存并沿用；代码中没有硬编码任何学校的 projectId（例外见 `BATH_PROJECT_ID`） |
| 挤号检测有最多 25 秒延迟 | 靠 25 秒心跳轮询实现（v2.2.0 前是完全发现不了） |
| 小组件不显示实时消费 | 小组件不连 MQTT，使用中只显示预扣金额 |
| 小组件不支持收藏设备 | `WidgetRenderer` 仍只看 `lastDevice` |
| Android 12 以下仍需定位权限 | 系统对蓝牙发现的硬性规定，无法绕过 |
| 自动填充依赖厂商 ROM | 不同厂商密码管理器行为差异较大，未在多机型验证 |
| 实时扣费 | MQTT 仅在订单结束时推送消费金额，洗澡中无实时扣费（官方 App 也是如此） |
| 结算延迟 | **已经很快了**：不再翻账单列表，直接问 `/order/consumeOrder/result/query`，实测关阀后第一次查就有结果（约 1~2 秒）。只有这个接口整个失败时才回退到账单列表——那条路仍然慢且容易拿不到 |
| 一卡通余额需已签约 | 走 `/settlement/campus/userInfo` 能直接拿到真实余额（趣智校园代理了易校园，**不用破签名**）。**没签约免密支付**时服务端不给 `amount`，回落到趣智校园钱包 → 本地估算 |
| MQTT 明文 | 趣智校园 MQTT 服务器不支持 TLS，通信内容未加密 |
| 密码安全 | 趣智校园使用 MD5 取后 10 位作为密码，安全性较低（官方协议限制） |
| 学校适配 | 多数学校装发行版即可（projectId 自动下发）。部署方式特殊的才要改 **BLE 过滤名 / MQTT 地址** |
| 深色模式 | 登录页硬编码浅色，不跟随系统也不跟随 App 主题；洗澡页 / 按量操作页读 App 内的 `ThemeMode`（不跟随系统）；扫码页固定深色（相机画面上深浅模式没有意义） |
| 1x1 图标对齐 | 占用仍是**一格**（和应用图标一样），只是靠内边距让视觉大小接近。各家桌面的格子尺寸和图标内边距都不同，做不到像素级一致，6dp 是折中值 |
| 小组件仍是 emoji | 账单行 / 附近设备行的设备头像用的是 emoji，和 App 内的图标不一致（那两个列表里可能有饮水机，换了会图标与 emoji 混排）|
| 小组件图标是描边风格 | `ic_widget_*` 与设备图标的实心风格不统一（小组件底色深，描边更清楚），暂不强行统一 |
| 设备图标小尺寸可辨识度 | 14dp 下吹风机只剩剪影（两道格栅槽变成两个白点）、饮水机接水槽糊成约 1px，20dp 起才清晰。这是 24dp 缩到 14dp 的固有代价，已试 6 版无法再优化 |
| 多语言 | 仅支持中文（资源语言限定 `zh` / `en`） |
| 关阀结果无法完全确认 | 断网时只能知道「请求没发出去」，无法知道服务端**在断网前有没有收到**。这时按未确认处理并把本地状态放回去（显示为仍在用水）——宁可多显示一次使用中，也不谎报「已结束」|
| 「占用中」徽章可能提前消失 | 徽章的是非对错取决于「对方什么时候用完」，而那个时间客户端拿不到。目前用 **3 分钟** TTL 兜底，所以对方洗得久时徽章会先消失——点一下仍会正常提示被占用 |
| 老用户升级不自动 | `com.hualala.linyu.dev` 与 `com.quzhi.lite.dev` 是**两个独立 App**，旧包不会自动覆盖，需手动卸载旧包、重登一次（数据不迁移，属有意取舍） |

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
| 小组件账单页余额偏高 | ✅ v2.2.3：只减了 2 笔账单，App 用 20 笔，减数偏小导致虚高 |
| 预发布被当成正式版 | ✅ v2.2.3：`fetchReleases()` 没过滤 `prerelease` |
| 「上次消费」不跟设备走 | ✅ v2.2.3：金额按 `snCode` 分开存 |
| 结算后小组件仍显示旧的上次消费 | ✅ v3.0.4：结算完成后立即刷新；金额延迟到账时按 `orderNo` 在账单同步后补写 |
| 余额只能手动估算 | ✅ v3.0.0：接 `/settlement/campus/userInfo` 拿真实余额 |
| 退出使用页后超时关停失效 | ✅ v3.0.0：倒计时和订单轮询搬进前台服务 |
| 通知栏点「结束用水」后使用页不退出 | ✅ v3.0.0：`doFinish` 漏发结束事件 |
| 消费金额显示成上一次账单的金额 | ✅ v3.0.0：`settleAmount` 的时间过滤条件写反了 |
| 小组件开阀后没有「使用中」通知 | ✅ v3.0.0：`runCatching` 把服务启动失败吞了 |
| 换手机号验证码发到旧号 | ✅ v3.0.0：实测是发到**新**号（`typeId=5`），代码写反了 |
| 姓名拿不到 | ✅ v3.0.1：`/account/info` 依赖学校同步学籍，改为三个接口互为兜底 |
| 首次加载无余额时闪错数字 | ✅ v3.0.1：账单没到位不显示估算值 |
| 开阀失败什么都不显示 | ✅ v3.0.2：`showerError` 以前**只写不读**，现在弹窗 + 小组件 + 横幅三处联动 |
| 代扣可能重复扣款 | ✅ v3.0.2：超时/取消被当成「失败」会诱导重试；列表刷新无乱序守卫；批量没去重 |
| 重新领取使用码显示八个短横线 | ✅ v3.0.2：自动换码的条件写成「只在没码时才换」 |
| 结束通知永远显示「无消费」 | ✅ v3.0.3：`orderId`(7位) 拿去比 `orderNo`(20位)，永远不成立；时间窗又被本单自己滤掉。改问 `/order/consumeOrder/result/query` |
| 消费金额放大 1000 倍 | ✅ v3.0.3：那个接口的金额单位是**厘**、账单列表是**元**，同名字段差 1000 倍 |
| 零消费要等 8 秒 | ✅ v3.0.3：5 轮 + 3 轮固定重试纯白等，实测第 1 轮就是终值。改成 2 轮 + 1 轮 |
| 组件的「选择附近」列出的名字匹配不上设备 | ✅ v3.0.3：末段是数字就**凭空补「房」**，真实设备名里没这个字。改成从原始名「掐头去尾」取寝室键（issue #6）|
| 小组件完全没读绑定寝室 | ✅ v3.0.3：主屏显示的是「上次开过阀的设备」，开阀也不看绑定。三处都加了寝室闸门 |
| 绑定寝室后小组件一直显示「请先选择设备」 | ✅ v3.0.3：闸门拿 `lastDeviceName`（**格式化过**、楼层被去掉）算键，和原始名算出来的对不上。新增 `lastDeviceRawName` |
| 首页取消绑定后要等一会儿才刷新 | ✅ v3.0.3：`PrefsHelper.boundRoom` 是普通 pref 不是 Compose 状态，改它不触发重组。改成 ViewModel 里的 State |
| `sync-all.sh` 上传 APK 静默跳过 | ✅ v3.0.3：第 3 步 `cd` 换了工作目录，第 5 步相对路径失效，打印「没给 APK」就过去了。改成先转绝对路径 |
| 关阀没成功却报「已结束」 | ✅ v3.0.4：确认循环无论结果如何都 `clearDeviceState` + 返回 `Closed`；且它查的 `closeOrder/result/query` 实测**恒返回 `data:null`**，那段"确认"实际只验证了 HTTP 成功。新增 `CloseOutcome.Unconfirmed` |
| 关阀未确认时本地状态对不上 | ✅ v3.0.4：停止流程先清本地状态（为了点完即时反馈），失败时不回滚 → 界面显示空闲、设备还在跑，且 `startedAt` 已被清、计时从 0 重来。新增 `restoreActiveOrder` + `ShowerEvents.ordersRestored` |
| MQTT 把 orderNo 写成设备序列号 | ✅ v3.0.4：`showerSnCode?.let { updateOrderNo(it, it) }` 内层 `it` 遮蔽外层，两个参数都成了 snCode |
| 小组件「占用中」永久残留 | ✅ v3.0.4：`occupiedSnCode` 全项目只有小组件自己读写、且无过期机制。App 侧清除 + 3 分钟 TTL |
| 断网点小组件开启无任何反馈 | ✅ v3.0.4：`openValve` 第一步就发请求且自身无 try/catch，异常被冒到接收器吞掉，卡片闪一下就没。改为「网络异常」+ 横幅 |
| 「选用」失败文案笼统 | ✅ v3.0.4：`pickDevice` 返回 `Boolean`，「设备不存在」和「网络不通」说不出区别。改为三态 `PickResult` |
| 更新包只校验字节长度 | ✅ v3.0.4：补包名、签名证书、可选 SHA-256；不通过则**阻止安装** |
| 加密存储静默降级为同名明文 | ✅ v3.0.4：回退文件与加密文件同名，加密恢复后会读到格式不符的同名文件 → 「登录态莫名丢失」。改用独立文件 `linyu_prefs_plain` |
| 备份规则是未改动的 Android 模板 | ✅ v3.0.4：内容全被注释，等于没排除任何东西，凭证会进云备份/设备迁移 |
| 蓝牙扫描失败后重复回调 | ✅ v3.0.4：超时任务没撤销，失败后还会再回调一次「完成」，上层把空列表当有效快照 |
| 日志共享非线程安全的日期格式 | ✅ v3.0.4：`SimpleDateFormat` 共享实例 + 文件读写无锁。改用 `java.time` 并加锁 |
| 干净环境跑不了测试任务 | ✅ v3.0.4：`signingConfigs` 在配置阶段无条件读 `local.properties`，缺文件连 `testDebugUnitTest` 都失败；v3.1.0 改为**条件创建** signingConfig（缺配置只 warn） |
| 洗衣 / 吹风机显示成「热水器」 | ✅ v3.1.0（#30）：`typeName` 没有分支，洗衣/吹风落进 `else -> "热水器"`。改用 `ModeOrderPolicy.deviceKind(bigTypeId, smallTypeId)`，并补齐 `typeEmoji`/`typeLabel`/`typeColor` |
| 头像底色按 emoji 各写一份 `when` | ✅ v3.1.0（#30）：「附近设备」和「上次使用」各有一份，加类型时漏改 → 头像顶默认蓝。收口到 `DeviceInfo.colorForEmoji()` |
| 饮水机冷热判反 | ✅ v3.1.0（#30）：旧逻辑「名字含热」，而服务端**不下发任何冷热字段**，开水器被显示成「正在接凉水」。改为**只有明确写了「冷水」才算冷水机** |
| 设备页余额取错板块 | ✅ v3.1.0（#28）：停在洗衣板块点开热水器，显示的是洗衣钱包余额。改为按 `DeviceInfo.projectId` 取（`DeviceBoardPolicy`） |
| 修 bug 只修一半（收藏路径没修到） | ✅ v3.1.0（#29）：`loadDeviceBoardWallet()` 只加在 `openMeteredScreen()` 上。改为收口到 `selectedDevice` setter，并把 `projectId` 存进收藏快照 |
| 余额标题分不清属于哪个板块 | ✅ v3.1.0（#27）：标题带上板块名，按来源显示「一卡通余额」/「趣智校园余额」 |
| 「上次使用」点一下直接开阀 | ✅ v3.1.0（#21）：按钮在首页列表里，滑动误触就出水。改为去掉按钮、整卡可点，走统一分流，有活跃订单则恢复使用页 |
| 板块切换入口消失 | ✅ v3.1.0（#23）：`ProjectSwitcher` 被误删，编译器不报、测试全绿（UI 没单测），表现只是「入口消失」。已加「改 `MainScreen.kt` 必查清单」 |
| 按量计费设备点开就出水 | ✅ v3.1.0（#19/#20）：改为全屏操作页 + **二次确认弹窗**，`SkipDetailPolicyTest` 锁着「按量不能跳过」 |
| 待确认页设备被抢却不提示 | ✅ v3.1.0（#21/#22）：加每秒轮询 + 顶部横幅 + 按钮禁用，**只降级不打断**；两种计费判据分开，`PreemptBothBillingTest` 锁着 |
| 倒计时变负数不消失 / 胶囊不倒数 / 通知一直挂着 | ✅ v3.1.0（#14）：`setTimeoutAfter(剩余+60s)` + `startModeOrderAutoRefresh()` 每 30 秒重发 + 启动时 `cancelStaleModeOrder()` |
| 通知发不出去、也没日志 | ✅ v3.1.0（#9）：渠道懒加载，向不存在的渠道发通知会被系统**静默丢弃**。改为先 `ensureChannels`，并加两个自检按钮（ID 1006 / 1007，零成本） |
| 趋势 / 合并开关状态不同步 | ✅ v3.1.0（#6）：开关 state 未提升（`remember` 不重算）+ 关趋势不联动置 false。抽 `TrendMergeLink` 纯函数，改受控组件 |
| 消费合并会拿到错板块的认证参数 | ⚠️ v3.1.0（#5）：已有 `isMerging` 挡重入 + 只发 GET + 失败静默降级，**未根治**（见 P1） |
| 蓝牙水表状态帧被判「应答过短」丢掉 | ✅ v3.1.1：部分学校升级成 **28 字节**短载荷，旧代码丢弃 → 蓝牙连上了但点开阀没反应。现按各自字段偏移解析，31 字节及以上行为不变，其余长度报未知 |
| SECURITY.md 与实现不符 | ✅ v3.0.4：声称「Release 不包含请求/响应体日志」，实际自有拦截器照记（最多 300/400 字符，已脱敏）|

---

## 版本历史

### v3.2.1（2026-10-29，当前版本）

**首个使用真正 release 签名的正式版。** `versionCode 18` / `versionName 3.2.1`，
正式版 `12.77 MB`（R8 + 资源压缩），dev 测试包同步 `3.2.1-dev`。

**备份 / 恢复（#37）**

- `data/BackupFormat.kt` / `BackupCodec.kt` / `BackupService.kt` / `BackupFileIo.kt` 四层分工：
  格式 / 加解密 / App 状态胶水 / 文件读写，加解密是纯 JVM 所以能单测
- **含登录凭证的备份强制加密**：PBKDF2-HMAC-SHA256 21 万次 + AES-256-GCM，
  IV 每次随机 12 字节、salt 每次随机 16 字节。强制在**数据层**（`encode` 直接拒绝明文凭证）
- **SAF 读写，零存储权限**：`openOutputStream(uri,"wt")` 截断，读前挡 >16MB
- 恢复收藏**按 mac 合并**不覆盖；恢复凭证**先鉴权再落盘**（失败一个字节都不写，且要还原网络层）
- 两个入口：设置页 `ui/BackupCards.kt`、登录页 `ui/BackupRestoreScreen.kt`
- 单测 `BackupCodecTest`(17) + `BackupDecodeMessageTest`(3)

**双更新通道（#37）**

- `DEV_CHANNEL` buildConfigField（dev=true / store=false）+ `GithubApi.fetchReleases(devChannel)`
- sealed `ReleaseResult { Ok / Failed }` 分开「网络失败」与「该通道没版本」
- 失败结果不进缓存

**签名（#37）**

- 正式版改为 **release 签名**（RSA 2048 / SHA384withRSA，有效期至 2054-03-16），
  证书 SHA-256 `81:7F:13:F0:56:B0:08:1F:C2:36:83:74:31:5F:BD:7C:CD:8E:0C:8B:AC:7C:2C:04:06:7E:21:EC:A5:AE:BA:E7`
- keystore **不在仓库内**，AES-256 加密备份在私有仓库；密码不入库不入文档
- ⚠️ 缺 `KEYSTORE_*` 时 Gradle 只 warn 并静默产出未签名包，出包后必须 `apksigner verify`

### v3.1.0-dev（2026-10-24/25，历史开发线）

**本仓库改名后的第一个开发线，也是最大的一次功能扩展。** 从「只做洗浴的热水器客户端」
变成覆盖洗浴 / 洗衣 / 吹风 / 饮水 / 蓝牙水表的趣智校园客户端，共 33 轮改动（开发日志 #1~#33）。

**品牌与工程**

- **改名 + 包名迁移（#30）**：应用名「淋浴」→ **趣智Lite**，包名 `com.hualala.linyu` →
  `com.quzhi.lite`（dev 包 `com.quzhi.lite.dev`，可与官方 App 共存），`rootProject.name` → `QuzhiLite`，
  仓库指向收到 `REPO_SLUG = "shierpuzi/linyu"` 单一出处，Gitee 镜像置空。
  代码里的 `LinYu*` 类名 / 资源名**刻意保留**（改名要动 124 个文件，收益为零）
- **两个 flavor（#4）**：`store`（原版，凭证恒空）/ `dev`（`.dev` 后缀 + `local.properties`
  预置登录态）；测试任务名随之变成 `testDevDebugUnitTest` / `testStoreDebugUnitTest`
- **CI**：新增 `.github/workflows/ci.yml`，push / PR 自动跑编译 + 单测 + Lint
- **图标体系（#30/#31/#32）**：桌面图标换 Material You 自适应图标（矢量 + monochrome，删掉 15 个位图）；
  设备图标按真实造型做单色矢量（花洒 / 牙刷 / 滚筒洗衣机 / 吹风机 / 饮水机机身版）；
  界面残留装饰性 emoji 全部换成矢量图标。文档见[图标资产说明.md](图标资产说明.md)

**多板块与套餐计费**

- **多板块切换（#3）**：一个手机号可绑多个独立项目（实测 4000 洗浴 / 3356 洗衣饮水吹风），
  各有独立钱包、账单、设备；`projectId` + `accountId` **必须成对切换**（`ProjectRepository` +
  `ProjectSelectionPolicy`）
- **洗衣 / 吹风机按套餐下单（#3/#7）**：`ModeOrderController` + `ModeOrderScreen`，
  选套餐 → 一次性扣全款 → 剩余倒计时，**故意没有停止按钮**（服务端无停止 / 取消 / 退款接口）
- **按计费模型分流（#7/#19/#20）**：`BillingModel.METERED` / `PACKAGE` 是 UI 分流的第一性依据，
  **不看设备类型、不看名字**。套餐设备点开直达选套餐页；按量设备进全屏操作页并二次确认
- **收藏设备（#20）**：`FavoriteStore`，多条（上限 30）、用户主动写入、字段全落盘因此离线也能渲染与分流。
  与「上次使用」（单条易失）是两回事
- **消费趋势与消费合并（#5/#6）**：近 7 天 / 本月、柱形 / 折线、峰值标注、选择记忆；
  `BillMerger` 跨板块合并账单，趋势图带「合并」角标（**只用于展示**）
- **余额口径按设备归属（#27/#28/#29）**：设备页显示该设备所属板块的钱包，标题带板块名

**蓝牙水表（手机当网关）**

- 部分学校的表**不联网**（服务端 `isBle` 为真），只能由手机通过 BLE 直连下发费率、关阀与结算
- 完整实现 BLE 通道：帧协议（`ble/BleFrame`）、状态帧解析、加解密与签名（`KlcxkjCrypto` /
  `KlcxkjSigner` / `BleSignBuilder`）、费率下发、消费采集、结算上传、`0x86` 写回
- **关阀即结清**、遗留订单自动补结算、App 被杀重开能恢复用水；
  通知栏「结束使用」与桌面小组件都能走蓝牙关阀（`WidgetBleService` + `BleArbiter`，**App 被杀也能关**）

**通知与 ColorOS 流体云**

- 所有通知按 OPPO 实时更新规范改造（#11）：常驻通知申请提升为状态栏胶囊 + 锁屏卡片，
  事件类走 `BigTextStyle` + `CATEGORY_EVENT`，告警走 `CATEGORY_ERROR`
- 倒计时三处修复（#14）：负数不消失 / 胶囊不倒数 / 通知一直挂着
- 胶囊文本**永远由 App 提供**（#18 定案）：≥2 分钟分钟级（30 秒重发）、<2 分钟秒级（1 秒重发）。
  ColorOS 16 不实现 `when` 回退渲染，这条走过六版弯路，别改回去
- **胶囊内容按模块区分（#36）**：按量常驻通知的小图标换成当前模块的 `ic_device_*` 矢量图
  （原先一律水滴），标题换成短模块名（`饮水机` / `洗衣机` / `热水器`…），正文 `正在使用 · 已用 N 分钟`；
  设备 emoji → 图标/模块名的映射同时收口到 `utils/DeviceVisual.kt`（原先 `ui/DeviceGlyph` 与
  `widget/WidgetRenderer` 各写一份、通知没接）。套餐倒计时通知只换小图标，样式一律不动。
  ⚠️ 两种通知都**没有「暂停」按钮**——后端没有 pause 接口
- 两个**零成本自检按钮**（设置 → 通知 → 详细设置）：不用下单就能验证通知链路

**其它体验**

- loginCode 直登（#26）、余额标题带板块名、消费金额显示到厘、账单先显示本地快照、
  背景装扮记住上次范围、我的页改版（设置入口收到右上角）、登录页教程链接
- 工程加固：设备信息 60 秒缓存、蓝牙操作互斥、订单读写原子化、通知按钮按设备稳定分配请求码
- **安全**：登录请求结束后立即清除密码与验证码；相机声明为非必需硬件

**已知性质**

- 已发布的 `v3.1.0-dev` 测试包是 **dev flavor + debug 签名**（当时无 release keystore——**v3.2.1 已补齐**），
  包名 `com.quzhi.lite.dev`、`versionCode 16` / `versionName 3.1.0-dev`、**不含任何预置凭证**
- ⚠️ 收藏、全屏操作页、两个确认弹窗、改名后的图标与文案等**多项功能只到编译 + 单测**，
  未逐一真机验证；状态见[交接文档.md](交接文档.md) 第五节

### v3.1.1（已写入 CHANGELOG，当时未升版本号）

蓝牙水表兼容性修复，来自社区 PR（`@f3ngka0` 的 #11、`@Asyreale` 的 #8 / #9）。

> 📌 历史注记：写这一节时 `app/build.gradle.kts` 里还是 `versionCode 16` / `versionName "3.1.0"`，
> 即 **v3.1.1 的内容已进代码与 [CHANGELOG.md](CHANGELOG.md)，但当时版本号尚未上调**。
> **后来 v3.2.0(17) 与 v3.2.1(18) 已经把这笔账补上了**，现在版本号与内容是对齐的。

- **28 字节短载荷状态帧**：部分学校近期把 `0x23` 状态查询改成 28 字节短布局，
  旧代码当作「应答过短」直接丢弃 → 表现是「蓝牙连上了、通知也正常，但点开阀没反应」，
  只提示「设备无响应」——设备本身没坏，是 App 把有效回包扔了。
  现在按各自字段偏移解析（`ble/DeviceQueryState.kt`），31 字节及以上行为完全不变，
  其余长度（27 / 29 / 30 等）一律报未知，不拿错的偏移去读
- 查询 / 关阀 / 采集三条命令补上官方 SDK 的零字节参数（此前比设备期望的少一个 `00`）
- 结算上传前先校验消费体（长度不足或首字节不是成功标记时不上传，避免把半截数据当成一条消费记录）
- **台账 MAC 与空口广播地址对不上**：部分学校的水表按随机静态地址派生出广播地址，
  与服务端台账存的**不是同一个 MAC**；按广播地址查会拿到「成功但 `data` 为空」，
  看起来像「点了卡片什么都不发生」。现在按候选依次尝试（原地址优先，不增加现有学校的请求量），
  查不到时**明确说明原因**，不再静默
- 蓝牙体验：小组件开阀时补「正在开启…」反馈（蓝牙开阀要 4~6 秒，此前毫无反馈）；
  关阀不再永久卡在「正在关闭…」；小组件 / 通知栏的关阀请求不再被丢弃
  （改为重连设备、用状态帧重建会话后关）；修掉「订单在、计时为 0」的计时器从 00:00 重走

> 28 字节短布局已在某某学院（projectId=513）真机闭环验证；其它学校若也是短布局但字段位置不同，
> 仍需各自抓帧确认。详见 [CHANGELOG.md](CHANGELOG.md)。

### v3.0.4 (2026-09-20)

**质量修复版本，无新增功能。** 16 个文件，改动集中在异常路径与工程加固——正常使用下的行为与 v3.0.3 一致。

- **关阀结果不再谎报** — `closeValve` 的确认循环无论结果如何都 `clearDeviceState` + 返回 `Closed`；而且它查的 `closeOrder/result/query` 实测**恒返回 `data:null`**，那段"确认"实际只验证了 HTTP 成功。新增 `CloseOutcome.Unconfirmed`，关阀请求本身失败时如实提示。判据取「一点证据都没有」而不是「确认得很完美」——后者会让网络稍有抖动就报未确认，那种噪声会淹没真正的异常
- **未确认时回滚本地状态** — 「先清状态再关阀」的顺序保留（那是为了点完即时反馈），改为失败时 `restoreActiveOrder` 把活跃订单与 `startedAt` 一并放回。`startedAt` 是关键，少了它计时会从 0 重来。回滚发生在 `notifyFinished` 之后，App 内存副本已删设备，故补 `ShowerEvents.ordersRestored` 事件同步
- **MQTT orderNo** — 内层 `it` 遮蔽外层，`updateOrderNo(it, it)` 把 orderNo 写成了 snCode
- **小组件「占用中」** — 加 App 侧清除 + 3 分钟 TTL（原来只有小组件自己读写、且无过期，会永久残留）；断网点开启加「网络异常」提示与横幅（原来异常被吞、界面无反馈）；「选用」失败区分设备不存在/网络异常（`pickDevice` 由 `Boolean` 改三态 `PickResult`）；同一占用周期内只弹一次横幅
- **更新包校验** — 包名比对 + 签名证书 SHA-256 比对 + 可选 Release SHA-256。不通过则阻止安装。GitHub `digest` 不可用时（如 Gitee 下载）不阻止安装，但签名校验不跳过
- **认证数据** — 备份规则此前是未改动的 Android 模板（等于没排除任何内容），现排除两份 prefs 与日志；加密初始化失败的回退改用独立文件 `linyu_prefs_plain`（原与加密文件同名）
- **日志** — 共享的 `SimpleDateFormat` 改用线程安全的 `java.time`，文件截断与追加共用一把锁
- 其余：下载复用与旧版清理、蓝牙扫描失败后的双回调、Release 签名配置改为条件创建、`SECURITY.md` 如实修订

### v3.0.3 (2026-09-18)

- **消费金额终于对了** — 结束通知几乎总是「无消费」，两个 bug 叠在一起：`orderId`(7 位) 拿去比 `orderNo`(20 位)（永远不成立），时间窗 `consumeDate >= startedAt` 又把本单自己滤掉（账单记的是**下单**时刻，比开阀确认早 1~2 秒）。改问 `/order/consumeOrder/result/query`
- **金额单位是厘** — 那个接口的 `consumeMoney` 是**厘**、账单列表同名字段是**元**，差 1000 倍且**静默**错。两笔独立订单用 `dealDate` 对齐验证过
- **结算体积感** — 通知先显示「用时 X · 结算中…」，**同一个通知 id**，拿到金额后原地更新成「消费 ¥x.xx」；零消费从 8.4 秒缩短到约 1.5 秒（实测 27 次调用第 1 轮就是终值，原来 5+3 轮固定重试纯白等）
- **绑定寝室重做（issue #6）** — 关键词不再「取最后一段 + 凭空补房字」，改成从原始设备名「掐头去尾」，同一寝室的两台设备收敛成同一条、和扫到哪台无关，也不会再把隔壁楼同号房串进来
- **小组件开始认绑定了** — 以前主屏、附近设备页、开阀动作三处**都没读** `boundRoom`。现在都筛；用水中的卡片不受影响（那是唯一的停止入口），provider 的闸门只拦开阀、停止和刷新放行
- **绑定后清理寝室外的上次设备** — `lastDeviceSnCode` 是小组件主按钮的开阀依据，留着等于留一条「点一下就开别寝室的水」的路。正在用水的不清
- 手工输入关键词的输入框去掉，只能从「选择附近」选
- 界面：设备详情卡片换回 emoji（那处没底色，白图标压在浅色背景上看不见）、花洒图标下面那条水流线加长到和上面等长、提示气泡改单行头部省略
- 首屏取消绑定后要等一会儿才刷新（`PrefsHelper.boundRoom` 不是 Compose 状态，改它不触发重组）
- `sync-all.sh` 上传 APK 静默跳过（第 3 步 `cd` 换了工作目录，相对路径失效）
- `design/device-icons/svg2vector.py` 不再随仓库分发（避免语言统计出现 Python）

### v3.0.2 (2026-09-18)

- **设备图标** — 🚿 🪥 从 emoji 换成单色 vector，用在主视觉位（主界面卡片、使用页、设备详情）。数据层仍是 emoji 字符串，只在渲染时映射，老用户零迁移；小组件的列表行**不换**
- **开阀失败三处联动** — `showerError` 以前只写不读，失败时什么都不显示。现在 App 弹窗（含欠费清单 + 一键补扣）/ 小组件提示 / **横幅通知**三处都有
- **横幅提醒** — 新增 `linyu_alert` 渠道（`IMPORTANCE_HIGH`）。之前三条渠道最高才 DEFAULT，**全 App 一个横幅都没有**；渠道 importance 创建后改不了，所以用两条渠道按开关切
- **代扣的一批重复扣款隐患** — 请求超时/被取消被当成「失败」会诱导重试（可能扣两次）→ 新增 `DeductResult` 三态，「结果未知」重查服务端定夺；未支付列表加乱序守卫；批量补扣去重
- **欠费提示的误导与状态残留** — 只有服务端明确拒绝才引导补扣（断网不再引导去扣钱）；笔数金额口径统一；金额识别不了时禁用确认；登出清状态
- **使用码两个问题** — 打开弹窗就自动换一个（以前只在没码时才换，有码的看到八个短横线）；待领码只有后三位高亮（原先整串一个颜色）
- 代码收口：两套代扣确认弹窗合并、四条通知并进 `postEvent`、清掉三处死代码

### v3.0.1 (2026-09-17)

- **新增 1x1 小组件** — 一格大小，点一下开关热水。顶部小字标状态（空闲 / 使用中 / 占用中），操作中显示转圈（用系统 `ProgressBar`，因为 RemoteViews 跑不了动画）
- **姓名拿不到的根因修掉了** — `/account/info` 的姓名依赖学校同步学籍，没同步的账号返回 `null`。现在三个来源汇总：新增 `/account/card/getBindCardInfo`，并补上从 `/settlement/campus/userInfo` 取 `studentName`（原来只取了学号）
- 修复：首页「使用中的设备」名字长了换行把卡片撑变形、点「正在使用」通知回到首页而不是使用页、通知权限提示块没占满一行、关掉通知总开关后仍提示打开权限
- 详见 [CHANGELOG](CHANGELOG.md)

### v3.0.0 (2026-09-17)

2.0 以来最大的一次更新。三个"一直做不到"的限制被解掉了。

- **一卡通真实余额** — 走 `GET /settlement/campus/userInfo` 直接读校园卡账户。趣智校园代理了易校园的接口，所以不用破那套 HMAC-SHA256 native 签名。未签约免密支付时自动回退到本地估算
- **后台用水监控** — 前台服务 `ShowerWatchService`。原先超时自动关停和订单轮询写在 `MainViewModel.timerJob` 里，退出使用页或被划掉就停摆；现在跟界面彻底解耦
- **系统通知** — 常驻状态条（带「结束用水」按钮）+ 结束/超时提醒。关掉开关只是把它收进静默渠道，后台监控照常跑
- **个人信息卡片** — 姓名/学号/学校自动获取；「账号管理」折叠区里可换手机号、改密码
- **修改密码的短信验证分支** — 走 `/user/password/forget`，用验证码注册的账号（没有旧密码）终于能改密码了
- **使用码改版** — 预览式领取：`generate` 只出候选码，`set` 才生效，所以「取消」零成本
- **扫码页重写** — Compose 重做，支持相册选图识别、取景框、震动反馈、手电筒状态反馈
- 修复 14 项问题，详见 [CHANGELOG](CHANGELOG.md)

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

- **桌面小组件**（1x1 / 2x2 / 2x4，桌面直接启停热水，Chronometer 实时计时）
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

## 致谢

| 贡献者 | 内容 |
|---|---|
| **[yehu-imei](https://github.com/yehu-imei)** | **★ 本项目的原始作者：基础框架与全部原始实现**——热水表开阀 / 关阀 / 结算链路、MQTT 协议、短信 `secret` 算法、一卡通余额代理接口。本仓库是在他的代码基础上继续开发的 |
| [@Cainite07](https://github.com/Cainite07) | 短信登录的实现 |
| [@SubmergeDQ](https://github.com/SubmergeDQ) | 蓝牙阀控制的实现 |
| [@f3ngka0](https://github.com/f3ngka0) | 蓝牙水表 28 字节状态帧支持 |
| [@Asyreale](https://github.com/Asyreale) | 蓝牙表广播地址与小组件状态修复 |
| [@XiaoLoad](https://github.com/XiaoLoad) | 项目优化的建议 |

---

## 免责声明

本项目仅供学习和研究用途。使用者应自行遵守趣智校园及相关服务的使用条款。
开发者不对因使用本项目产生的任何后果承担责任。
