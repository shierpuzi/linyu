# 淋浴 (LinYu) v1.2.0 更新日志

发布日期：2026-08-08

---

## 版本信息

| 项 | 值 |
|---|---|
| 版本号 | 1.2.0 |
| versionCode | 3 |
| 包名 | `com.hualala.linyu` |
| 构建类型 | Release（R8 混淆 + 签名） |
| APK 文件 | `app/build/outputs/apk/release/app-release.apk` |
| GitHub Release | https://github.com/yehu-imei/linyu/releases/tag/v1.2.0 |

---

## 新增功能

### 1. 开阀确认
开始洗澡时，调用 `downRate` 后轮询 `/order/tcpDevice/query/downRateResult` 确认开阀成功，才进入洗澡界面。避免设备离线时用户以为开了实际没出水。
- 开阀期间显示「正在开启热水器...」加载框
- 开阀失败提示「开阀未确认成功，请确认热水器是否已开启」

### 2. 自动关停倒计时 + 确认弹窗
- 解析 `downRate` 响应的 `autoDisConTime` 字段，洗澡界面显示「闲置约 X 分 X 秒后自动关闭」
- 倒计时持久化（时间戳存储），App 重启可恢复
- 自动关闭时弹出模态确认框：设备名 + 使用时长 + 消费金额 + 确认按钮，点确认才退出

### 3. 消费金额结算
- 关阀确认成功后，通过账单接口 `/order/query/account/bill/list` 获取本次消费金额
- 改为后台异步轮询（最长 20 秒），不阻塞关闭流程，结算完成弹 toast「已停止，本次消费 ¥xx」
- 按开阀时间过滤账单，避免读到上一次消费

### 4. 主页扫码绑定设备
- 主页标题栏新增 📷 扫码按钮（与刷新并排）
- 扫热水器上的 `KLCXKJ-Water` 二维码，直接查询设备信息并弹出设备详情
- 无需蓝牙扫描即可绑定设备

### 5. 扫码手电筒
- 扫码界面右上角手电筒按钮（线条图标）
- 光线不足时补光，退出自动关灯

### 6. 绑定寝室 + 设备筛选
- 「我的」页面新增「绑定寝室」卡片
- 两种绑定方式：手动输入关键词 / 从附近设备选择
- 绑定后主页设备列表只显示匹配寝室的设备（`deviceName.contains` 匹配，忽略大小写/空格/连字符）
- 主页显示「🏠 已筛选：xxx」状态，可一键取消筛选

### 7. 加密存储
- loginCode 等敏感数据改用 `EncryptedSharedPreferences`（AES 加密）
- 设备不支持时自动降级明文，避免崩溃

---

## Bug 修复

| 问题 | 修复 |
|---|---|
| 退出登录闪退 | `PrefsHelper.clear()` 改用逐个 remove() 替代 `edit().clear()`（EncryptedSharedPreferences 已知崩溃 bug），并加 try-catch 兜底 |
| 挤号后重新登录重复弹窗 | 挤号场景 `stopShower(skipNetwork = true)` 跳过失效请求，避免重新触发挤号 |
| 消费金额显示 0.00 | 账单异步轮询等待服务器结算完成 |
| 结束使用加载卡顿 | 金额查询改为后台异步，不再阻塞关闭流程 |
| 自动关停无提示 | 新增确认弹窗替代无声 toast |

---

## 已知限制（仍未解决）

1. **挤号检测是被动的**：需触发网络请求（刷新/操作）才检测到被挤号，打开 App 不操作不会主动发现
2. **实时扣费不可得**：服务器不提供中间消费数据，只能在关闭后查最终金额
3. **消费金额结算延迟**：账单生成有延迟（最长等 20 秒）
4. **一卡通余额**：易校园 API 有签名保护，仍只能手动估算
5. **仅适配金华职业技术大学**（projectId=905），其他学校需改 projectId / BLE 过滤名

---

## 构建说明

Release 构建需跳过 lint（网络下载 lint 依赖失败）：

```powershell
.\gradlew.bat assembleRelease -x lintVitalRelease
```

使用 Android Studio 自带 JBR（JDK 21）：

```powershell
$env:JAVA_HOME="D:\Android Studio\jbr"
.\gradlew.bat assembleRelease -x lintVitalRelease
```

GitHub 推送使用 SOCKS5 代理：

```bash
git config http.proxy socks5h://127.0.0.1:7890
git config https.proxy socks5h://127.0.0.1:7890
git -c http.sslBackend=openssl push origin master
```

---

## 版本历史

- **v1.2.0** (2026-08-08)：本次更新
- **v1.1.0**：最低 SDK 提升至 API 26，Kotlin 2.0.21 + AGP 8.13.2
- **v1.0** (2026-05-30)：初始版本
