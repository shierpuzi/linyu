# dev 包：预置登录态 + 改包名共存

## 为什么需要这个

三个 App 共用**同一个 `loginCode`**，而它是单点的——任何一处登录，其余立即失效：

| App | 包名 |
|---|---|
| 官方趣智校园 | `com.klcxkj.zqxy` |
| 原版 linyu | `com.hualala.linyu` |
| **测试包（本次）** | **`com.hualala.linyu.dev`** |

要同时验证「官方 App 保持登录」+「我们的改动能用」，手机上必须能装三个互不干扰的包。
所以：

1. **换包名** → `applicationIdSuffix = ".dev"`，可以和原版同时装
2. **预置 loginCode** → 启动即登录，不用在手机上再登一次（一登就把你手上的挤掉了）

## 产物

```
linyu/app/build/outputs/apk/dev/debug/app-dev-debug.apk
→ 已复制到：E:\work\pythondata\newprogram\quzhixiaoyuan\linyu-dev-洗衣机吹风机版.apk
```

| 项 | 值 |
|---|---|
| 包名 | `com.hualala.linyu.dev` |
| 应用名 | 淋浴·测试版 |
| 版本 | 3.1.0-dev (16) |
| 大小 | 20.96 MB |

装上去之后桌面会同时有两个「淋浴」图标，测试版带「·测试版」后缀。

## 怎么构建

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2"
$env:ANDROID_HOME = "C:\Android"; $env:ANDROID_SDK_ROOT = "C:\Android"
cd linyu
.\gradlew.bat :app:assembleDevDebug
```

⚠️ **任务名变了**：加了 flavor 之后是 `assembleDevDebug` / `assembleStoreDebug`，
不再是 `assembleDebug`。同理测试是 `testDevDebugUnitTest` / `testStoreDebugUnitTest`，
**`testDebugUnitTest` 会报 ambiguous 错误**。

## 凭证从哪来

`linyu/local.properties`（**在 .gitignore 里，不入库**）：

```properties
QZXY_PREFILL_LOGIN_CODE=b1faba...
QZXY_PREFILL_USER_ID=10000001
QZXY_PREFILL_ACCOUNT_ID=10000101
QZXY_PREFILL_PROJECT_ID=3356
QZXY_PREFILL_TELEPHONE=13800138000
```

通过 `buildConfigField` 注入到 `BuildConfig.PREFILL_*`，
再由 `utils/PrefillAuth.kt` 在 `MainActivity.onCreate` 里 `PrefsHelper.init()` 之后写入。

**只写认证字段**，不伪造任何业务数据。等价于「登录成功后调 `saveAuth`」。

### 不会踩的坑

- **本地已登录就不写**：你在设置里换过号、或手动登录过，`PrefillAuth.seed()` 直接 return
- **不校验有效性**：loginCode 过期了就正常提示「登录失效，请重新登录」，和真实过期一样
- **公开构建不受影响**：没配 `local.properties` 时这些字段是空串，`available` 为 false，no-op

## ⚠️ 踩过的一个真坑（已修）

一开始我把 `buildConfigField` 写在 `defaultConfig` 里，结果**原版包也带上了 loginCode**：

```
审计 3 个 APK...
  app-store-debug.apk  com.hualala.linyu  ✅ 干净（无凭证）  ← 修完之后
```

修之前实测 `app-store-debug.apk` 的 dex 里**真的能搜到 loginCode**。
这种错误编译器不报、测试不报，只能扫产物发现。

修法：`buildConfigField` 移进 `dev` flavor，`store` flavor 显式给空串。

## 安全审计脚本

```powershell
cd washer-probe
python verify_apk.py
```

输出：

```
审计 2 个 APK，监控 3 项敏感值

  app-dev-debug.apk     com.hualala.linyu.dev  3.1.0-dev  ✅ 含凭证（预期：dev 包）
  app-store-debug.apk   com.hualala.linyu      3.1.0      ✅ 干净（无凭证）

✅ 通过：凭证只在 dev 包中，原版包干净
```

它会解开 APK 扫所有 `.dex` 的原始字节，找 `loginCode` / 手机号 / userId。
**改完构建配置务必跑一次**——这次的泄漏就是它抓出来的。

## ⚠️ 这个包会挤掉你的官方 App

预置的 loginCode 一旦被 App 发起请求（启动就会拉钱包/板块列表），
**官方 App 那边可能被挤下线**。

所以顺序建议：
1. 先在官方 App 上把该看的东西看完
2. 再打开测试包验证
3. 如果官方 App 掉登录了，重登一次即可（测试包的 loginCode 会失效，但你已经看完要看的了）

> 这也是为什么要有这个包：**至少验证期间不用反复登录**。
> 一次性把要验的都验完，比每次登录拿新 code 划算得多。
