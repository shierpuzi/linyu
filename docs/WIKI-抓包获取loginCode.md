# WIKI · 怎么通过抓包拿到 loginCode

> 适用：趣智校园（`com.klcxkj.zqxy`）及其第三方客户端调试
> 本文所有「实证」标注均来自两次真实抓包：
> `洗衣机消费抓包.zip`（2026-10-05，App 6.5.28）和 `吹风机.zip`（2026-10-24，App 6.5.29）
>
> ⚠️ 仅用于学习研究，抓的是**你自己的账号**。别用别人的。

---

## 0. 一句话结论

| 你的状态 | 怎么拿 | 要不要重新登录 |
|---|---|---|
| App **已登录** | 从**任意一条业务请求**里抠出来 | ❌ 不用 |
| App 未登录 | 抓**登录请求的响应** | ✅ 要登录一次 |

**`loginCode` 是明文参数**，不加密、不签名，就躺在每条请求的 URL query 或 POST form 里。
抓包工具里搜 `loginCode` 就能看到。

---

## 1. loginCode 是什么

| 项 | 值 |
|---|---|
| 格式 | **32 位小写十六进制**，如 `00000000000000000000000000000001` |
| 性质 | **会话令牌**，不是账号固定值 |
| 来源 | 只有登录接口会下发 |
| 有效期 | 短。**会被新登录挤掉**（见 §6） |
| 传递方式 | GET → URL query；POST → form-urlencoded body |

### 同一账号两次抓包，loginCode 完全不同（实证）

| 抓包 | 时间 | loginCode |
|---|---|---|
| 洗衣机 | 2026-10-05 10:25 | `00000000000000000000000000000002` |
| 吹风机 | 2026-10-24 00:12 | `00000000000000000000000000000001` |

同一个账号（`accountId=10000101` / `153****2282`），令牌两回事 → 说明它是**每次登录新生成的会话**。

---

## 2. 取法 A：App 已登录 → 从任意业务请求里抠（推荐，最省事）

### 为什么这条最省事

**一条业务请求里就带齐了全套认证参数**，不需要重新登录、不会挤掉正在用的 App。

实证：吹风机抓包 `seq 92`，`GET /account/wallet` 的完整 URL

```
https://v3-api.china-qzxy.cn/account/wallet
  ?accountId=10000101
  &telPhone=153****2282
  &phoneSystem=android
  &loginCode=00000000000000000000000000000001     ← 就是它
  &telephone=153****2282
  &projectId=3356
  &userId=10000001
  &version=6.5.29
```

这一条就把 `loginCode` / `userId` / `accountId` / `projectId` / `telephone` 全给了。

### ⚠️ 只拿 loginCode 不够——还必须同时给 `userId`

实测（2026-10-24，本账号真实 loginCode 逐个接口验证）：

| 只传什么 | 结果 |
|---|---|
| 只有 `loginCode` | ❌ `errorCode=8 未获取到用户信息，请重新登录` |
| `loginCode` + `accountId`、或 + `projectId`、或 `userId=0` | ❌ 同样 `8`，其中 `userId=0` 报「登录失效」 |
| **`loginCode` + `userId`** | ✅ `success`，`/user/project/list` 直接返回绑定的全部板块 |

**服务端把 `loginCode` 和 `userId` 当作一对来识别会话。** 而 `loginCode` 是 32 位随机
十六进制，**从它反推不出 `userId`**，所以两个都得拿到。

> 好消息：只要这两个凑齐，其余参数（`accountId` / `projectId` / `telephone`）全能自己解析，
> 不用也去抓——见下面「userId 从哪来」。

### userId 从哪来（四种取法，按省事程度排）

**取法 1：从同一条业务请求里一起拿（推荐，和 loginCode 同一处）**

上一条 `GET /account/wallet` 的 URL 里本来就有 `userId=10000001`，
和 `loginCode` 挨着。抓包工具里那条请求的 query 从头看到尾即可，**不需要另外操作**。

**取法 2：登录响应里直接有 `data.userId`**

`POST /user/registerAndLogin`（或 `POST /user/login`）的响应体：

```json
{
  "success": true,
  "data": {
    "userId": 10000001,        ← 这里
    "loginCode": "...",
    "userAccount": { "accountId": 10000101, "projectId": 3356, ... }
  }
}
```

`userId` 和 `loginCode` 在同一次响应里，一起复制走的成本为零。

**取法 3：App 内「我的」页 / 日志里的接口响应**

linyu 自己的日志会把每个请求打进去（自动脱敏了 `loginCode`/`userId`，但**服务端响应体
如果没被脱敏**，`/user/project/list` 的响应里有 `userId`）。公开构建下不可靠，
**只建议调试包用**。

**取法 4：`/user/project/list` 的响应（需要已有 userId，鸡生蛋，不适用首次）**

---

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
5. 搜 `loginCode=`，后面的 32 位就是

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
- 想顺便拿到 `loginCode` 之外的登录侧字段（`tags`、`hasPassword`、真实姓名等）

### ⚠️ 先看这个警告

**趣智不支持多设备同时在线。** 你重新登录一次 → 正在用的 App **立刻被踢下线**（会弹「挤号」提示）。
反过来，App 一旦重新登录，你手上那个 `loginCode` 立刻作废。

所以：**要用取法 B，就挑不用 App 的时候做。**

### 只有两个接口会下发 loginCode

| 接口 | 方法 | 用途 |
|---|---|---|
| `/user/login` | POST | 手机号 + 密码登录 |
| `/user/registerAndLogin` | POST | 手机号 + 短信验证码登录 |

**除了这两个，没有任何接口返回 loginCode。** 我把两次抓包的全部响应体扫过一遍：
`/account/info`、`/account/wallet`、账单、设备信息……**全是 0 命中**（连任何 32 位十六进制串都没有）。

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

**第 2 步：提交验证码（loginCode 在这一步的响应里）**

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
    "userId": 10000001,
    "telephone": "153****2282",
    "loginCode": "00000000000000000000000000000001",
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

`password` = **MD5(明文密码) 取后 10 位大写**。例：

```
明文 mypassword → MD5 → 34819d7beeabb9260a5c854bc85b3e44 → 后10位大写 → C854BC85B3
```

响应结构与 B-1 **完全一致**。

> 限制：验证码注册的账号**没有密码**，所以密码登录对它无效。

### 抓包时要看的界面

| 你做的动作 | 抓包列表里应该出现的请求 |
|---|---|
| 输入手机号点「发送验证码」 | `GET /user/verification/code/get` |
| 输入验证码点「登录」 | `POST /user/registerAndLogin` ← **响应里有 loginCode** |
| 输密码点「登录」 | `POST /user/login` ← **响应里有 loginCode** |

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
> 如果只要 loginCode 可以不抓 MQTT，但要注意：洗衣机/吹风机的**订单号只从 MQTT 推送来**，
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

## 5. 拿到之后怎么验证

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
| `8` | 登录失效 | loginCode 过期或被挤，重新取 |
| `12` | 手机号或密码错误 | |
| `29` | 验证码失效 | 重发 |
| `46` | 验证码错误 | |
| `53` | 请勿频繁获取验证码 | 等一会儿 |

---

## 6. 五个必须知道的坑

### 坑 1：会把自己的 App 挤下线

同一账号不能双端在线。你用取法 B 登录一次，**正在用的 App 立刻被踢**。
linyu 为此专门做了 25 秒心跳轮询来发现被挤号（`MainViewModel.kt:2524`）。

**所以：只用取法 A（从业务请求里抠），就完全不会影响 App。**

### 坑 2：`telephone` 和 `telPhone` 两个都要传

值相同，但**少一个就可能报错**，而且报得莫名其妙：

```
GET /settlement/campus/userInfo（少 telPhone）
→ HTTP 200，{"success":false,"errorCode":1,"errorMessage":"手机号不能为空"}
```

这是趣智的历史遗留设计，照抄完整参数最省事。

### 坑 3：`projectId` 不是你随便填的

登录响应里的 `userAccount.projectId` 才是当前板块。一个手机号可能绑定**多个项目/学校**，
每个项目有各自的 `accountId`：

```
（换板块 → accountId / projectId 成对变化 → 钱包和账单都是各自独立的）
```

从**业务请求**里抠的好处就是：那个 `projectId` 一定是你当时正在用的板块，不用猜。

### 坑 4：没有刷新 / 续期接口

我在 `linyu/api/QzxyService.kt` 里查过：**没有 logout、没有 refresh token**。
loginCode 要么还能用、要么就是死了。想要新的只有一个办法：**重新登录**。

### 坑 5：`version` 号跟着 App 走

实测：洗衣机抓包是 `6.5.28`，吹风机抓包是 `6.5.29`（App 更新了）。
服务端目前对 `version` 不严格校验，但**照抄抓包里的值最稳**。

---

## 7. 快速决策表

```
我要 loginCode
│
├── App 现在是登录状态？
│   │
│   ├── 是 → 【取法 A】开抓包 → App 里下拉刷新 → 找 v3-api 的请求
│   │        → 搜 loginCode= → 复制
│   │        （不影响 App，不花钱，30 秒搞定）
│   │
│   └── 否 / 手上没 App → 【取法 B】开抓包 → 走登录流程
│            → 抓 /user/registerAndLogin（或 /user/login）的【响应】
│            → data.loginCode
│            （会踢掉其它设备）
```

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

| 参数 | 示例值 | 说明 |
|---|---|---|
| `loginCode` | `00000000000000000000000000000001` | 会话令牌 |
| `userId` | `10000001` | 用户 ID |
| `accountId` | `10000101` | 账户 ID（跟 projectId 成对） |
| `projectId` | `3356` | 项目/学校 ID |
| `telephone` | `153****2282` | 手机号 |
| `telPhone` | `153****2282` | **同上，必须也传** |
| `phoneSystem` | `android` | 固定 |
| `version` | `6.5.29` | 跟 App 版本 |

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
| loginCode 藏在每条业务请求里 | `吹风机.zip` 里 **18 条** `v3-api` 业务请求，**18 条全含** `loginCode=` | ✅ 抓包实证 |
| 同一账号两次 loginCode 不同 | 两份抓包对比 | ✅ 抓包实证 |
| 响应体里没有其他 32 位令牌 | 两份抓包全响应扫描，0 命中 | ✅ 抓包实证 |
| 旧 loginCode 已失效（errorCode=8） | 实测 `/account/wallet` | ✅ 联网实证 |
| 登录响应结构（含 loginCode） | `linyu/API-qzxy.md` 逆向记录 | ⚠️ 文档记录，本次抓包未覆盖登录环节 |
| `secret` 算法 | `linyu/utils/SignUtils.kt` + 本地实算验证 | ✅ 算法可复现 |
| 无 refresh / logout 接口 | `linyu/api/QzxyService.kt` 全接口扫描 | ✅ 代码实证 |
| MQTT broker 不固定 | 两次抓包（47.107.37.60 / 39.108.147.136） | ✅ 抓包实证 |
