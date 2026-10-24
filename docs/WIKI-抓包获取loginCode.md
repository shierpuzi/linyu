# WIKI · 怎么通过抓包拿到 loginCode 和 userId

> 适用：趣智校园（`com.klcxkj.zqxy`）及其第三方客户端调试
> 本文所有「实证」标注均来自两次真实抓包：
> `洗衣机消费抓包.zip`（2026-10-05，App 6.5.28）和 `吹风机.zip`（2026-10-24，App 6.5.29）
>
> ⚠️ 仅用于学习研究，抓的是**你自己的账号**。别用别人的。
---

## 0. 一句话结论

**本 App 的「loginCode 登录」需要两个东西一起给：`loginCode` + `userId`。**
少一个都进不去——服务端把这两个当作一对来识别会话。

| 你的状态 | 怎么拿这两个 | 要不要重新登录 |
|---|---|---|
| App **已登录** | 从**任意一条业务请求**的 URL 里一起抠出来 | ❌ 不用 |
| App 未登录 | 抓**登录请求的响应**，`loginCode` 和 `userId` 都在里面 | ✅ 要登录一次 |

**这两个都是明文参数**，不加密、不签名，就躺在每条请求的 URL query 或 POST form 里。
抓包工具里搜 `loginCode` 就能同时看到旁边的 `userId`。

---

## 1. loginCode 和 userId 是什么

### 先搞清楚：为什么要用这种登录方式？

趣智校园的账号是**单点登录**的：同一个账号在任何一处重新登录，之前已经登录的
会话**立刻失效**（这就是 App 里那句「账号在别处登录」）。

而官方的登录方式只有两种——短信验证码、密码，**每登录一次都会生成一份全新的
`loginCode`（和配套的 `userId`）**，旧的当场作废。这就带来一个绕不开的矛盾：

- 本 App 没有做充值接口（出于安全考虑，所有涉及付钱的入口都不碰），
  所以**充值必须回趣智校园官方 App 去做**
- 但只要你在官方 App 里登录一次，本 App 的登录态就掉了；反过来在本 App 登录，
  官方 App 又掉了

结果是两边反复互踢，谁都用不踏实。

**「loginCode 登录」就是为了解决这个矛盾**：它不向服务端发起新的登录，
而是**直接沿用你手上那份已经在用的会话凭证**。拿官方 App 当前正在用的
`loginCode` + `userId` 填进来，本 App 就相当于「借」用了同一个会话，
官方 App 那边**完全不受影响、不会被踢下线**。

> 一句话：验证码登录会抢会话，loginCode 登录是共享会话。
> 日常两边都想用的时候，走这条。

### 这两个值分别是什么

| 项 | `loginCode` | `userId` |
|---|---|---|
| 格式 | **32 位十六进制**，如 `00000000000000000000000000000001` | 纯数字，如 `10000001` |
| 性质 | **会话令牌**，每次登录都会换新的 | **用户 ID**，同一个账号固定不变 |
| 来源 | 只有登录接口会下发 | 登录接口下发，也出现在每条业务请求里 |
| 有效期 | 短。**会被新登录挤掉**（见 §6） | 跟着账号走，不随登录变化 |
| 传递方式 | GET → URL query；POST → form-urlencoded body | 同上，和 `loginCode` 总是成对出现 |

### 为什么必须两个一起给（实测）

2026-10-24 用真实 loginCode 逐个接口验证过：

| 只传什么 | 结果 |
|---|---|
| 只有 `loginCode` | ❌ `errorCode=8 未获取到用户信息，请重新登录` |
| `loginCode` + `accountId`、或 + `projectId`、或 `userId=0` | ❌ 同样 `8`，其中 `userId=0` 报「登录失效」 |
| **`loginCode` + `userId`** | ✅ `success`，`/user/project/list` 直接返回绑定的全部板块 |

服务端是拿 `loginCode` **和** `userId` 一起认人的。而 `loginCode` 是 32 位随机
十六进制，**从它反推不出 `userId`**，所以两个都得拿到——好在它俩永远挨在一起，
抓一次就有。

> 好消息：只要这两个凑齐，其余参数（`accountId` / `projectId` / `telephone`）本 App
> 会自己解析出来，**不用你也去抓**。

### 同一账号两次抓包，loginCode 完全不同（实证）

| 抓包 | 时间 | loginCode | userId |
|---|---|---|---|
| 洗衣机 | 2026-10-05 10:25 | `00000000000000000000000000000002` | `10000001` |
| 吹风机 | 2026-10-24 00:12 | `00000000000000000000000000000001` | `10000001` |

同一个账号（`accountId=10000101` / `153****2282`），`loginCode` 两回事、
`userId` 一模一样 → 印证了「`loginCode` 是每次登录新生成的会话，`userId` 是账号本身」。

---

## 2. 取法 A：App 已登录 → 从任意业务请求里抠（推荐，最省事）

### 为什么这条最省事

**一条业务请求里就带齐了全套认证参数**，`loginCode` 和 `userId` 挨在一起，
不需要重新登录、不会挤掉正在用的 App。

实证：吹风机抓包 `seq 92`，`GET /account/wallet` 的完整 URL

```
https://v3-api.china-qzxy.cn/account/wallet
  ?accountId=10000101
  &telPhone=153****2282
  &phoneSystem=android
  &loginCode=00000000000000000000000000000001     ← 要的就是这个
  &telephone=153****2282
  &projectId=3356
  &userId=10000001                                ← 还有这个，缺一不可
  &version=6.5.29
```

这一条就把 **`loginCode` + `userId`**（本 App 登录要的两个）以及
`accountId` / `projectId` / `telephone`（本 App 会自动解析的）全给了。

### 操作步骤

1. **打开抓包工具，让它开始抓**（这时 App 保持已登录状态，**不要重新登录**）
2. 在 App 里随便做一个会产生网络请求的动作。最轻的三个：
   - 下拉刷新首页（触发 `/account/wallet`）
   - 打开「我的」页（触发 `/account/info`）
   - 点任意一台设备（触发 `/device/info/mac`）
3. 在抓包列表里找目标：**只留 `v3-api.china-qzxy.cn`**（过滤规则见 §4）
4. 点开任意一条，看 **Request**：
   - GET 请求 → 看 URL 里的 query
   - POST 请求 → 看 body（`Content-Type: application/x-www-form-urlencoded`）
5. 搜 `loginCode=`，抄下后面那 32 位；**同一条请求里再搜 `userId=`**，抄下它

> ⚠️ 两个值要**分别、准确**地抄下来，只填那串字符本身。
> 不要把整条 URL 直接粘进输入框——那样会校验不通过。

### 拿到之后怎么验证这两个能配对

最省事的验证请求（**不需要任何业务权限，只读**）：

```bash
curl 'https://v3-api.china-qzxy.cn/user/project/list?loginCode=<你的>&userId=<你的>' \
  -H 'User-Agent: okhttp/4.2.2'
```

成功的样子：

```json
{"success":true,"errorCode":0,"errorMessage":"成功",
 "data":[{"userId":10000001,"projectId":3356,"accountId":10000101},
         {"userId":10000001,"projectId":4000,"accountId":3987}]}
```

- 返回 `success:true` → 两个都有效、且配对正确，`accountId` + `projectId` 也顺手拿到了
- 返回 `errorCode=8 未获取到用户信息` → 大概率是 **userId 漏了或填错**（不是 loginCode 过期）
- 返回 `errorCode=8 登录失效，请重新登录` → loginCode 真的失效/被挤了

> ⚠️ 这两个错误文案只差后半句，**别混**：
> 「未获取到用户信息」= 缺参数 / userId 不对；「登录失效」= 令牌本身死了。

---

### POST 请求长什么样（实证）

`POST /order/tcpDevice/downRate/modeOrder`，吹风机下单，body 逐字节如下：

```
xfModel=29&accountId=10000101&telPhone=153****2282&snCode=C47F0E2351BE
&phoneSystem=android&loginCode=00000000000000000000000000000001
&telephone=153****2282&isHaier=0&projectId=3356&userId=10000001&version=6.5.29
```

请求头（**复现时这两个头不能少**）：

```
Content-Type: application/x-www-form-urlencoded
Config-Project: 3356
Config-Keys: module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config
User-Agent: okhttp/4.2.2
Host: v3-api.china-qzxy.cn
```

> `Config-Project` 必须等于 `projectId`。缺这两个头时，某些接口（尤其蓝牙结算）会报 226「加密校验失败」。

---

## 3. 取法 B：抓「登录那一刻」的响应（需要重新登录）

### 什么时候才需要这条

- 你**没有** App 的已登录状态（比如手上只有账号密码）
- 想顺便拿到 `loginCode` / `userId` 之外的登录侧字段（`tags`、`hasPassword`、真实姓名等）

### ⚠️ 先看这个警告

**趣智不支持多设备同时在线。** 你重新登录一次 → 正在用的 App **立刻被踢下线**（会弹「挤号」提示）。
反过来，App 一旦重新登录，你手上那个 `loginCode` 立刻作废。

所以：**要用取法 B，就挑不用 App 的时候做。**
平时两边都要用的人，请走取法 A，别用这条。

### 只有两个接口会下发 loginCode 和 userId

| 接口 | 方法 | 用途 |
|---|---|---|
| `/user/login` | POST | 手机号 + 密码登录 |
| `/user/registerAndLogin` | POST | 手机号 + 短信验证码登录 |

**除了这两个，没有任何接口返回 loginCode。** 我把两次抓包的全部响应体扫过一遍：
`/account/info`、`/account/wallet`、账单、设备信息……**全是 0 命中**（连任何 32 位十六进制串都没有）。

> `userId` 则不同：它除了在登录响应里，还出现在**每条业务请求**中——
> 所以取法 A 能免费拿到它。

### B-1 短信验证码登录（App 默认走这条）

用验证码注册的账号根本没有密码，所以官方 App 默认走短信登录。

**第 1 步：发验证码**

```
GET /user/verification/code/get
    ?telephone=153****2282
    &secret=f000554d9cb90c44bd0ae2ded9a847aa
    &typeId=3
    &platform=1
```

| 参数 | 值 | 说明 |
|---|---|---|
| `telephone` | 11 位手机号 | |
| `secret` | 见附录 A | **能本地算，不用抓包** |
| `typeId` | `3` | 登录/注册用 3；`5`=换号，`2`=重置密码 |
| `platform` | `1` | 固定 |

**第 2 步：提交验证码（loginCode 和 userId 都在这一步的响应里）**

```
POST /user/registerAndLogin
Content-Type: application/x-www-form-urlencoded

telephone=153****2282&smsCode=396948&type=5&phoneSystem=android&version=6.5.29
```

> ⚠️ 这个接口**不带** `loginCode` —— 它就是用来换 loginCode 的。

**响应（`linyu/API-qzxy.md` 的逆向记录，非本次抓包实证）**

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "userId": 10000001,          ← 两个都要抄走
    "telephone": "153****2282",
    "loginCode": "00000000000000000000000000000001",   ← 这个也是
    "tags": "alLyrelease,999",
    "hasPassword": true,
    "userAccount": {
      "projectId": 3356,
      "accountId": 10000101,
      "userId": 10000001,
      "name": "张三",
      "accountRealMoney": 0.0
    }
  }
}
```

### B-2 密码登录

```
POST /user/login
Content-Type: application/x-www-form-urlencoded

telephone=153****2282&password=XXXXXXXXXX&phoneSystem=android&type=0&version=6.5.29
```

`password` = **MD5(明文) 取后 10 位大写**。例：

```
明文 mypassword → MD5 → 34819d7beeabb9260a5c854bc85b3e44 → 后10位大写 → C854BC85B3
```

响应结构与 B-1 **完全一致**，`loginCode` 和 `userId` 同样在 `data` 里。

> 限制：验证码注册的账号**没有密码**，所以密码登录对它无效。

### 抓包时要看的界面

| 你做的动作 | 抓包列表里应该出现的请求 |
|---|---|
| 输入手机号点「发送验证码」 | `GET /user/verification/code/get` |
| 输入验证码点「登录」 | `POST /user/registerAndLogin` ← **响应里有 loginCode 和 userId** |
| 输密码点「登录」 | `POST /user/login` ← **响应里有 loginCode 和 userId** |

---

## 4. 抓包工具设置

### 过滤规则（省得被广告淹）

只留两个目标：

```
v3-api.china-qzxy.cn          ← 全部业务 HTTP
47.107.37.60:1883             ← MQTT（洗衣机）
39.108.147.136:1883           ← MQTT（吹风机）
```

> **MQTT 地址不是固定的。**两次抓包就是两台不同的 broker。
> 如果只要 `loginCode` + `userId`，可以不抓 MQTT，但要注意：洗衣机/吹风机的**订单号只从 MQTT 推送来**，
> 想复现下单就必须抓。

### 必须过滤掉的东西（实测占比）

吹风机那次抓包：**101 个会话里 80 个是噪音**。主要来源：

| 域名 | 是什么 |
|---|---|
| `*.pangolin-sdk-toutiao*.com`、`pglstatp-toutiao.com` | 穿山甲广告 SDK |
| `log-api.pangolin-sdk-toutiao.com` | 穿山甲日志 |
| `apmplus.volces.com`、`mon.zijieapi.com`、`ibytedapm.com` | 字节系 APM 埋点 |
| `*.ecombdimg.com`、`d.alicdn.com` | 广告素材 CDN |
| `analytics.oceanengine.com` | 巨量引擎统计 |
| `*.jpush.cn` | 极光推送 |
| UDP `:33434~33523`、`:19000` | 同一 SDK 在刷常量包（**载荷完全一样**） |

### 证书

App 对 `v3-api.china-qzxy.cn` **没有做证书绑定（certificate pinning）**——
两次抓包都直接抓到了明文 HTTPS，不需要 root、不需要 Frida/EdXposed。
HttpCanary / Reqable 这类「装个 CA 证书 + 走 VPN 抓包」的工具就够。

---

## 5. 拿到之后怎么验证、怎么用

### 5.1 先读 `success`，不要只看 HTTP 状态码

**趣智的接口失败也返回 HTTP 200**，错误全在 body 里。

实证（刚用两天前的 loginCode 打 `/account/wallet`）：

```
HTTP/1.1 200
{"success":false,"errorCode":8,"errorMessage":"登录失效，请重新登录","data":null}
```

**状态码 200，但登录已失效。** 只看 HTTP code 会以为成功了。

### 5.2 最省事的验证请求

```bash
curl 'https://v3-api.china-qzxy.cn/account/wallet?accountId=10000101&telPhone=153****2282&phoneSystem=android&loginCode=<你的>&telephone=153****2282&projectId=3356&userId=10000001&version=6.5.29' \
  -H 'User-Agent: okhttp/4.2.2' \
  -H 'Config-Project: 3356' \
  -H 'Config-Keys: module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config'
```

成功的样子（实证）：

```json
{"success":true,"errorCode":0,"data":{
  "projectId":3356,"accountId":10000101,
  "accountRealMoney":22140,"accountGivenMoney":4000,
  "money":"26.140", ...
}}
```

### 5.3 常见错误码

| errorCode | 含义 | 怎么办 |
|---|---|---|
| `0` | 成功 | |
| `1` | 参数缺失（如「手机号不能为空」） | **补 `telPhone`**，见下 |
| `8` | 登录失效 | 先确认 `userId` 带没带对；都不行就是 loginCode 过期或被挤，重新取 |
| `12` | 手机号或密码错误 | |
| `29` | 验证码失效 | 重发 |
| `46` | 验证码错误 | |
| `53` | 请勿频繁获取验证码 | 等一会儿 |

### 5.4 在本 App 里怎么填

拿到这两个值之后，在登录页：

1. 点登录按钮下方的 **「通过 loginCode + userId 登录」**
2. **loginCode** 框：填那 32 位（纯十六进制，不带 `loginCode=` 前缀）
3. **userId** 框：填那个纯数字（不带 `userId=` 前缀）
4. 点「用 loginCode 登录」

> 两个框都只接受**值本身**，不要粘整条 URL。
> 填错格式时会有「32 位十六进制」/「一串数字」的提示。

登录成功后，`accountId` / `projectId` / `telephone` 全由 App 自己解析，
**你不需要填、也不需要再去抓**。

> 这一步不会向服务端发起新登录，所以**官方 App 不会被踢下线**。

---

## 6. 五个必须知道的坑

### 坑 1：会把自己的 App 挤下线

同一账号不能双端在线。你用**取法 B**（重新登录一次）之后，**正在用的官方 App 立刻被踢**。
linyu 为此专门做了 25 秒心跳轮询来发现被挤号（`MainViewModel.kt:2524`）。

**所以：用取法 A（从业务请求里抠）、再走本 App 的 loginCode 登录，就完全不会影响官方 App。**
这也是推荐这条路的核心理由——想两边同时用，就只有这一种办法。

### 坑 2：`telephone` 和 `telPhone` 两个都要传

值相同，但**少一个就可能报错**，而且报得莫名其妙：

```
GET /settlement/campus/userInfo（少 telPhone）
→ HTTP 200，{"success":false,"errorCode":1,"errorMessage":"手机号不能为空"}
```

这是趣智的历史遗留设计，照抄完整参数最省事。
（本 App 的 loginCode 登录会自动补齐这两个，不用你操心。）

### 坑 3：`projectId` 不是你随便填的

登录响应里的 `userAccount.projectId` 才是当前板块。一个手机号可能绑定**多个项目/学校**，
每个项目有各自的 `accountId`：

```
（换板块 → accountId / projectId 成对变化 → 钱包和账单都是各自独立的）
```

从**业务请求**里抠的好处就是：那个 `projectId` 一定是你当时正在用的板块，不用猜。
本 App 会自动拉 `/user/project/list` 把板块对齐，所以这块也不用手填。

### 坑 4：没有刷新 / 续期接口

我在 `linyu/api/QzxyService.kt` 里查过：**没有 logout、没有 refresh token**。
loginCode 要么还能用、要么就是死了。想要新的只有一个办法：**重新登录**
（或者从官方 App 当前请求里再抠一份新的）。

### 坑 5：`version` 号跟着 App 走

实测：洗衣机抓包是 `6.5.28`，吹风机抓包是 `6.5.29`（App 更新了）。
服务端目前对 `version` 不严格校验，但**照抄抓包里的值最稳**。

---

## 7. 快速决策表

```
我要 loginCode + userId
│
├── 官方 App 现在是登录状态？（推荐走这条）
│   │
│   ├── 是 → 【取法 A】开抓包 → App 里下拉刷新 → 找 v3-api 的请求
│   │        → 一条 URL 里同时有 loginCode= 和 userId= → 都复制
│   │        （不影响官方 App，不花钱，30 秒搞定）
│   │
│   └── 否 / 手上没 App → 【取法 B】开抓包 → 走登录流程
│            → 抓 /user/registerAndLogin（或 /user/login）的【响应】
│            → data.loginCode + data.userId
│            （会踢掉官方 App，做完记得在官方 App 重新登录）
```

> 想让**官方 App 和本 App 同时可用**，只能走取法 A。取法 B 会互踢。

---

## 附录 A：`secret` 算法（不抓包也能算）

短信验证码接口的 `secret` **不是随机值**，只跟手机号有关：

```
raw    = 手机号前3位 + 手机号后4位 + "klcx"
secret = MD5(raw)         // 32 位小写十六进制
```

验证（实算）：

```
13800138000 → "138" + "8000" + "klcx" = "1388000klcx" → f000554d9cb90c44bd0ae2ded9a847aa
153****2282 → "153" + "2282" + "klcx" = "1532282klcx" → f000554d9cb90c44bd0ae2ded9a847aa
```

因为算法里**不含任何设备密钥**，所以任何手机号都能在本地算出自己的 secret。
这也是为什么 `linyu` 敢把短信登录做成默认方式（实现见 `utils/SignUtils.kt`）。

> `secret` **不是身份凭据** —— 真正的身份校验发生在提交验证码那一步。

---

## 附录 B：完整认证参数清单

从实证请求里整理，**一个都不能少**：

| 参数 | 示例值 | 说明 | 本 App 登录要你填吗 |
|---|---|---|---|
| `loginCode` | `00000000000000000000000000000001` | 会话令牌 | ✅ 要 |
| `userId` | `10000001` | 用户 ID | ✅ 要 |
| `accountId` | `10000101` | 账户 ID（跟 projectId 成对） | ❌ 自动解析 |
| `projectId` | `3356` | 项目/学校 ID | ❌ 自动解析 |
| `telephone` | `153****2282` | 手机号 | ❌ 自动解析 |
| `telPhone` | `153****2282` | **同上，必须也传** | ❌ 自动解析 |
| `phoneSystem` | `android` | 固定 | ❌ 固定值 |
| `version` | `6.5.29` | 跟 App 版本 | ❌ 固定值 |

**HTTP 头**：

| 头 | 值 |
|---|---|
| `Config-Project` | = `projectId` |
| `Config-Keys` | `module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config` |
| `User-Agent` | `okhttp/4.2.2` |
| `Content-Type` | POST 时 `application/x-www-form-urlencoded` |

---

## 附录 C：本次 wiki 的事实来源

| 结论 | 来源 | 可信度 |
|---|---|---|
| loginCode 和 userId 都在每条业务请求里 | `吹风机.zip` 里 **18 条** `v3-api` 业务请求，逐条均含这两个 | ✅ 抓包实证 |
| 只给 loginCode 登录不了，必须配 userId | 2026-10-24 逐个接口组合实测 | ✅ 联网实证 |
| 同一账号两次 loginCode 不同、userId 不变 | 两份抓包对比 | ✅ 抓包实证 |
| 响应体里没有其他 32 位令牌 | 两份抓包全响应扫描，0 命中 | ✅ 抓包实证 |
| 旧 loginCode 已失效（errorCode=8） | 实测 `/account/wallet` | ✅ 联网实证 |
| 登录响应结构（含 loginCode / userId） | `linyu/API-qzxy.md` 逆向记录 | ⚠️ 文档记录，本次抓包未覆盖登录环节 |
| `secret` 算法 | `linyu/utils/SignUtils.kt` + 本地实算验证 | ✅ 算法可复现 |
| 无 refresh / logout 接口 | `linyu/api/QzxyService.kt` 全接口扫描 | ✅ 代码实证 |
| MQTT broker 不固定 | 两次抓包（47.107.37.60 / 39.108.147.136） | ✅ 抓包实证 |
