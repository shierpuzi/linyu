# 趣智校园第三方客户端开发指南

本文档介绍如何通过趣智校园 API 开发自己的第三方校园客户端。基于淋浴 (LinYu) 项目的逆向工程成果。

> 覆盖面：**洗浴热水表**（按量计费）、**洗衣机 / 吹风机**（按套餐计费）、**饮水机**（按量计费，同套餐设备同项目）、
> **蓝牙水表**（手机当网关）、**多项目（板块）切换**、**残留账单代扣**。
> v3.1.0 起后四类都是新增的，接口详解见 4.12 ~ 4.16。

---

## 目录

1. [API 概览](#1-api-概览)
2. [认证机制](#2-认证机制)
3. [密码加密](#3-密码加密)
4. [API 接口详解](#4-api-接口详解)
5. [MQTT 实时推送](#5-mqtt-实时推送)
6. [蓝牙设备发现](#6-蓝牙设备发现)
7. [完整业务流程](#7-完整业务流程)
8. [数据模型参考](#8-数据模型参考)
9. [注意事项与踩坑记录](#9-注意事项与踩坑记录)

---

## 1. API 概览

### 基础信息

| 项 | 值 |
|---|---|
| Base URL | `https://v3-api.china-qzxy.cn` |
| 协议 | HTTPS |
| 数据格式 | JSON（GET 请求参数在 URL 中，POST 请求为 form-urlencoded）。**唯一例外**：`/order/thirdTrade/single/json/pay` 的 body 是 JSON，见 [4.14](#414-残留账单与手动代扣) |
| 认证方式 | 基于 loginCode 的会话认证 |

### 接口列表

| 接口 | 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|---|
| 登录 | POST | `/user/login` | ❌ | 获取 loginCode 等认证信息 |
| 钱包余额 | GET | `/account/wallet` | ✅ | 获取趣智校园钱包余额 |
| 设备信息 | GET | `/device/info/mac` | ✅ | 通过 MAC 地址获取设备详情 |
| 开始洗澡 | POST | `/order/tcpDevice/downRate/rateOrder` | ✅ | 开启热水器 |
| 开阀结果确认 | POST | `/order/tcpDevice/query/downRateResult` | ✅ | 确认开阀是否成功，**`orderNo` 在这里就能拿到**（v1.2.0 新增，v3.0.3 修正）|
| 停止洗澡 | POST | `/order/tcpDevice/closeOrder` | ✅ | 关闭热水器 |
| 关阀结果确认 | POST | `/order/tcpDevice/closeOrder/result/query` | ✅ | 确认关阀是否成功。实测 `data` 恒为 `null`（v1.2.0 新增）|
| 消费结果查询 | POST | `/order/consumeOrder/result/query` | ✅ | 查询消费结算结果。⚠️ `consumeMoney` 单位是**厘**，见 [4.11](#411-消费结果查询) |
| 查询进行中 | POST | `/order/tcpDevice/query/rateOrder/using` | ✅ | 查询设备是否有进行中的订单 |
| 账单列表 | GET | `/order/query/account/bill/list` | ✅ | 获取月度账单 |
| 账单详情 | GET | `/order/query/account/bill/detail` | ✅ | 获取单笔账单详情 |
| 获取使用码 | GET | `/account/useCode/new` | ✅ | 获取当前使用码 + 今日能否重新领取 |
| **换一个**使用码 | POST | `/account/useCode/new/generate` | ✅ | **只换候选码，不生效**，每天 20 次（v3.0.0 修正） |
| **确定领取**使用码 | POST | `/account/useCode/new/set` | ✅ | 把候选码设为当前生效的码（v3.0.0 新增） |
| 使用码开关 | POST | `/account/useCode/new/status/update` | ✅ | 开启/关闭使用码 |
| 发送短信验证码 | GET | `/user/verification/code/get` | ✅ | 发送验证码，secret 由手机号推导（v2.1.0） |
| 短信验证码登录 | POST | `/user/registerAndLogin` | ✅ | 用验证码注册/登录 |
| 账号信息 | GET | `/account/info` | ✅ | 姓名 / 学号 / 校园卡绑定状态（v3.0.0 新增） |
| **一卡通真实余额** | GET | `/settlement/campus/userInfo` | ✅ | 真实校园卡余额，不需破签名（v3.0.0 新增） |
| 一卡通签约状态 | GET | `/settlement/withhold/sign/status` | ✅ | 免密支付是否已签约。⚠️ **本项目未实现**（直接用 `userInfo` 的 `signStatus`），见 [4.9](#四9-账号信息与一卡通) |
| 项目信息 | GET | `/project/info/triple` | ✅ | 学校名 `projectName` 在这里（v3.0.0 新增） |
| 更换手机号 | POST | `/user/phone/update` | ✅ | 验证码发到**新**号（v3.0.0 新增） |
| 修改密码 | POST | `/user/password/update` | ✅ | 需要旧密码（v3.0.0 新增） |
| **重置密码** | POST | `/user/password/forget` | ✅ | 只需短信验证码，**不需要旧密码**（v3.0.0 新增） |
| 已绑卡账号信息 | GET | `/account/card/getBindCardInfo` | ✅ | `/account/info` 的**超集**，姓名/学号兜底（v3.0.1 新增，见 [4.16](#416-已绑卡信息兜底)）|
| 绑定项目列表 | GET | `/user/project/list` | ✅ | 一个手机号绑的多个**独立项目**，给 `projectId`+`accountId` 配对（v3.1.0 新增，见 [4.12](#412-多项目板块通道)）|
| 项目业务模块 | GET | `/project/module/list` | ✅ | 当前项目启用了哪些业务，`isUse == 1` 才显示。⚠️ 必须用**该项目自己**的上下文调 |
| 套餐列表 | GET | `/device/mode/list` | ✅ | 洗衣机 `smallTypeId=3` / 吹风机 `=5`，价格单位**厘**（v3.1.0 新增）|
| **按套餐下单** | POST | `/order/tcpDevice/downRate/modeOrder` | ✅ | 洗衣机/吹风机，**一发出就扣全款、不可退**（v3.1.0 新增，见 [4.13](#413-按套餐计费的设备)）|
| 套餐设备状态 | POST | `/order/tcpDevice/washer/status` | ✅ | 空闲 `data:null`；占用 `errorCode=307` + `expireTimes` 剩余秒数。⚠️ **吹风机也调它** |
| 按 MAC 查最近一单 | GET | `/order/query/lastOrder/byMac` | ✅ | **开发期免费拿 orderNo**。⚠️ 有时间窗口（实测介于 1 小时~19 天之间），不能当「设备空闲」判据 |
| 未支付账单 | POST | `/order/weixinScorePay/unPay/queryBill` | ✅ | 「残留账单」列表，**扁平结构**，不套 `consumeBillDTO`（v3.0.1 新增，见 [4.14](#414-残留账单与手动代扣)）|
| **手动代扣** | POST | `/order/thirdTrade/single/json/pay` | ✅ | ⚠️ **body 是 JSON**（全项目唯一），且用 `consumeDate` 定位账单。**真扣钱** |
| 蓝牙表下发费率 | POST | `/order/downRate/bluetooth/rateOrder` | ✅ | 手机当网关，响应 `downData` 是**要写进设备的费率包**（v3.1.0 新增，见 [4.15](#415-蓝牙水表通道)）|
| 蓝牙表上传消费 | POST | `/order/upload/bluetooth/data` | ✅ | 结算，`errorCode` 206/209 也算成功 |
| 蓝牙表上报失败 | POST | `/order/upload/bluetooth/fail` | ✅ | **必须实现**：蓝牙通道没有服务端兜底，不报这单会一直挂在预扣状态 |

---

## 2. 认证机制

登录有**两种方式，返回的字段完全一致**，拿到之后处理方式也一样：

| 方式 | 接口 | 说明 |
|---|---|---|
| 密码登录 | `POST /user/login` | 需要把密码 MD5 后取后 10 位大写（见 [3. 密码加密](#3-密码加密)） |
| 短信验证码登录 | `POST /user/registerAndLogin` | 需要 `secret`，但它由手机号本地推导，**任何手机号可用**。详见 [4.8 短信验证码登录](#48-短信验证码登录) |

### 密码登录流程

```
POST /user/login
Content-Type: application/x-www-form-urlencoded

telephone=13800001111
&password=A1B2C3D4E5          ← MD5 后取后 10 位大写
&phoneSystem=android
&type=0
&version=6.5.28
```

### 登录响应

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "userId": 12345678,
    "telephone": "13800001111",
    "loginCode": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "tags": "alLyrelease,999",
    "hasPassword": true,
    "userAccount": {
      "projectId": 999,
      "accountId": 12345,
      "userId": 12345678,
      "name": "张三",
      "accountRealMoney": 0.0
    }
  }
}
```

### 关键字段说明

| 字段 | 说明 | 用途 |
|---|---|---|
| `loginCode` | 会话令牌（32 位十六进制） | 后续所有认证请求都需要此值 |
| `userId` | 用户 ID | 认证参数 |
| `userAccount.accountId` | 账户 ID | 认证参数 |
| `userAccount.projectId` | 项目/学校 ID | 认证参数，不同学校值不同 |
| `userAccount.name` | 用户姓名 | 显示用 |

### 认证参数传递方式

**GET 请求**：认证参数作为 URL Query 参数传递

```
GET /account/wallet?loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&telephone=xxx&phoneSystem=android&version=6.5.28
```

**POST 请求**：认证参数作为 Form 字段传递（与业务参数一起）

```
POST /order/tcpDevice/downRate/rateOrder
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=xxx&loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&telephone=xxx&telPhone=xxx&phoneSystem=android&version=6.5.28
```

### 完整认证参数 Map

```kotlin
fun authFields(): Map<String, String> = mapOf(
    "loginCode" to loginCode,
    "userId" to userId,
    "accountId" to accountId,
    "projectId" to projectId,
    "telephone" to telephone,
    "telPhone" to telephone,      // ⚠️ 两个都要传，值相同
    "phoneSystem" to "android",
    "version" to "6.5.28"
)
```

> ⚠️ `telephone` 和 `telPhone` **两个都必须传**，值相同——这是趣智校园 API 的历史遗留设计。
> **GET 请求也一样**，而且更隐蔽：少传 `telPhone` 时 `/settlement/campus/userInfo`
> 会返回 `errorCode=1 手机号不能为空`，**HTTP 依然是 200**。详见 [9.8](#98-get-请求也要补-telphone)。

> ⚠️ **这三个参数不是常量**（v3.1.0 多板块后）：`accountId` 和 `projectId` **必须成对切换**，
> 只换 `projectId` 会报「没有项目权限，请确认身份」。
> `loginCode` / `userId` / `telephone` 是全局的。详见 [4.12](#412-多项目板块通道)。

> ⚠️ **唯一例外**：`/order/thirdTrade/single/json/pay` 的认证参数在 **JSON body** 里，
> 且 `phoneSystem` 传 `"WeChat"`（不是 `android`）。见 [4.14](#414-残留账单与手动代扣)。

> 💡 `version` 传什么：本项目硬编码 **`6.5.28`**（官方 App 抓包偶尔是 `6.5.29`），
> 两者实测都能正常调通。**照抄一个能用的值即可，别自己编。**

### 挤号检测

当 loginCode 失效（在其他设备登录）时，API 会返回包含以下关键词的错误信息：
- "登录"、"token"、"失效"、"过期"、"认证"、"未登录"、"请重新"

HTTP 状态码 401/403 也表示会话失效。

---

## 3. 密码加密

趣智校园使用 **MD5 取后 10 位大写** 作为密码传输格式：

```kotlin
fun encryptPassword(password: String): String {
    val md5 = MessageDigest.getInstance("MD5")
        .digest(password.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return if (md5.length >= 10) {
        md5.substring(md5.length - 10).uppercase()
    } else {
        md5.uppercase()
    }
}
```

**示例**：
- 输入密码：`mypassword`
- MD5 哈希：`34819d7beeabb9260a5c854bc85b3e44`
- 取后 10 位大写：`C854BC85B3`

> ⚠️ 这是趣智校园官方 App 的加密方式，安全性较低，但作为第三方客户端必须遵循。

---

## 4. API 接口详解

### 两种计费通道：`rateOrder` 与 `modeOrder` 不是一回事

v3.1.0 之前项目只有热水表一条路。现在多了洗衣机 / 吹风机，**它们走的是完全不同的一串接口**，
混用会静默出错（最典型：拿 `closeOrder` 去停洗衣机，接口返回成功但设备照转，钱也不退）。

分流依据**是计费模型，不是设备类型**（`ModeOrderPolicy.billingModel`，有单测）：

| | 按量计费（`METERED`） | 套餐计费（`PACKAGE`） |
|---|---|---|
| 哪些设备 | 热水器 / 洗手台 / **饮水机** | 洗衣机（`bigTypeId=6`）/ 吹风机（`bigTypeId=7`） |
| 下单接口 | `POST /order/tcpDevice/downRate/rateOrder` | `POST /order/tcpDevice/downRate/modeOrder` |
| `xfModel` 传什么 | 固定 `0` | **套餐的 `modeId`**（不是 `id`） |
| 扣费方式 | 先预扣，按实际用量结算 | **一次性扣套餐全款，不可退** |
| 能否中途停 | ✅ `closeOrder` | ❌ **服务端没有任何** stop / close / cancel / refund 接口 |
| 状态接口 | `POST /order/tcpDevice/query/rateOrder/using` | `POST /order/tcpDevice/washer/status` |
| `orderNo` 来源 | `query/downRateResult` 直接给（见 4.3.1） | 响应恒 `data:null`，只能 MQTT 或 `lastOrder/byMac` |
| 结束方式 | 用户主动 `closeOrder` | **服务端到点自动关停**，客户端只做本地倒计时 |
| 界面 | `ShowerScreen`（已用时长 + 结束按钮） | `ModeOrderScreen`（选套餐 + 剩余倒计时 + 无停止按钮） |

> ⚠️ **饮水机是例外中的例外**：它在洗衣饮水吹风项目（3356）里，和洗衣机吹风机同一个 `projectId`，
> 但计费是**按量**——账单实测 `preDeduct=2.0` 而 `consumeMoney=0.1/0.44`（**不相等**），
> 而洗衣/吹风是 `preDeduct == consumeMoney`（相等）。
> 判据一律用 `bigTypeId` / `smallTypeId` 数字，**不要看设备名**（服务端在台账里叫「洗衣机」、
> 账单里叫「洗烘设备」，同一个 `bigTypeId=6` 两个名字）。

> ⚠️ **两条通道的占用判据也不能混**：按量设备的 `data` **永远非空**（带 `orderNo`/`isOwner`），
> 空闲时只是 `orderNo == null`；套餐设备空闲时 `data` 就是 `null`。
> 照搬套餐的 `data != null` 会让热水器**永远显示被占用**。

### 4.1 钱包余额

```
GET /account/wallet
```

**响应**：
```json
{
  "success": true,
  "data": {
    "accountRealMoney": 0.0,
    "accountGivenMoney": 0.0,
    "money": "0.000"
  }
}
```

| 字段 | 说明 |
|---|---|
| `money` | 总余额（字符串格式） |
| `accountRealMoney` | 真实充值金额 |
| `accountGivenMoney` | 赠送金额 |

### 4.2 设备信息

```
GET /device/info/mac?macAddress=AA:BB:CC:DD:EE:FF
```

**响应**：
```json
{
  "success": true,
  "data": {
    "deviceId": 18224,
    "deviceName": "热水器-学生公寓-1号楼-3层-301",
    "snCode": "QZXY20230001",
    "macAddress": "AA:BB:CC:DD:EE:FF",
    "withholdMoney": 2.0,
    "onlineStatusId": 1
  }
}
```

| 字段 | 说明 |
|---|---|
| `deviceId` | 设备 ID |
| `deviceName` | 设备名称（包含位置信息） |
| `snCode` | 设备序列号（控制设备的关键标识） |
| `macAddress` | MAC 地址 |
| `withholdMoney` | 预扣金额（元） |
| `onlineStatusId` | 在线状态 |

**设备名称格式**：`热水器-{校区}-{楼栋}-{楼层}-{房间}` 或 `洗手台{编号}-{校区}-{楼栋}-{楼层}-{房间}`

### 4.3 开始洗澡

```
POST /order/tcpDevice/downRate/rateOrder
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `xfModel` | 消费模式，固定传 `0` |
| `snCode` | 设备序列号 |
| 认证参数 | 见认证机制 |

**响应**：
```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": null
}
```

> ⚠️ **`downRate` 本身确实不返回 orderNo，但紧接着的 `downRateResult` 会返回——不用等 MQTT，也不用轮询 `queryUsing`。**
>
> 这条结论以前写错了（说是"需要通过 MQTT 推送或轮询 `queryUsing` 获取"），
> 导致客户端多绕了一圈：拿到 orderNo 只用来判断"开没开"，落盘时写空串，
> 再靠一个独立的轮询任务事后补上。那个轮询要 11 轮 × 800ms，
> **"开完水马上停"时还没跑出结果，本地就是空的**——而关阀、结算这几个接口全都要 orderNo。
>
> 实测（09-18 15:06:28，见 4.3.1）：
>
> ```json
> {"success":true,"errorCode":0,"errorMessage":"成功","data":{
>   "deviceSnCode":"AABBCCDDEEFF",
>   "consumeDate":"20260918150627",
>   "orderNo":"13200000000000000000",
>   "preDeductMoney":0,"accountType":2,"consumeSceneType":4,
>   "state":1,"result":0,"createTime":"1789715217", ...}}
> ```

### 4.3.1 开阀结果确认

```
POST /order/tcpDevice/query/downRateResult
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

**响应（已确认开阀）**：

> ⚠️ 下面这份是**原样摘录**，不是整理过的样例——日志按行长截断，所以
> `liquidOrderStatusD...` 后面还有内容没记下来。里面**没有** `autoDisConTime`，
> 不等于服务端不返回它（见下方字段表）。

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "projectId": null,
    "accountId": 10001,
    "deviceSnCode": "AABBCCDDEEFF",
    "consumeDate": "20260918150655",
    "orderNo": "13200000000000000001",
    "preDeductMoney": 0,
    "accountType": 2,
    "consumeSceneType": 4,
    "xfModelName": null,
    "state": 1,
    "result": 0,
    "createTime": "1789715217",
    "liquidOrderNo": null,
    "liquidOrderStatus": null
  }
}
```

**响应（还没开好，要继续轮询）**：

```json
{"success":true,"errorCode":1,"errorMessage":"网络通讯慢,请稍后再试","data":null}
```

⚠️ 注意 `errorCode: 1` 时 **HTTP 仍然是 200**，不能只判 HTTP 状态码。

| 字段 | 说明 |
|---|---|
| `orderNo` | **开阀确认后这里就有**，不用等 MQTT / `queryUsing` |
| `state` / `result` | 实测成功时是 `state=1, result=0`——**`state` 不是 0**，所以判断不能只看 `state == 0`，要两个都看 |
| `preDeductMoney` | 预扣金额。**单位见 4.11** |
| `consumeDate` | 紧凑格式 `yyyyMMddHHmmss`，和账单接口的 `yyyy-MM-dd HH:mm:ss` 不一样 |
| `createTime` | 服务端当前时间（Unix 秒），**不是**下单时间（每次请求都在变） |
| `autoDisConTime` | 自动关停秒数，客户端用 `DownRateResult.autoDisConTime` 读它显示倒计时。⚠️ **本次抓到的日志里这一段被截断了，没有真正看到这个字段**，字段名是从客户端已有的行为反推的——如果你的学校拿不到倒计时，先来这里核对 |

### 4.4 停止洗澡

```
POST /order/tcpDevice/closeOrder
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&orderNo=1234567&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `snCode` | 设备序列号 |
| `orderNo` | 订单号（从 `queryUsing` / `downRateResult` / MQTT 获取，见 4.3.1） |
| 认证参数 | 见认证机制 |

**响应**：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":null}
```

`errorCode: 307` 或 `errorMessage` 含「已在」时，表示服务端已在关闭中——**同样按成功处理**。

### 4.4.1 关阀结果确认：这个接口**确认不了任何东西**

```
POST /order/tcpDevice/closeOrder/result/query
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&orderNo=13200000000000000001&loginCode=xxx&...
```

**响应（实测，每次都是这样）**：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":null}
```

> ⚠️ **`data` 恒为 `null`。** 客户端早期版本用
> `d == null || d.state == 0 || d.status == 0 || d.orderNo.isNullOrEmpty()`
> 判断"关阀成功"——`d == null` 永远成立，于是**第 1 轮就跳出循环**。
> 那段"确认"实际只验证了「HTTP 请求成功」，跟设备关没关没有半点关系。
>
> 更糟的是它给了人**已经确认过**的错觉：断网导致 5 次请求全部抛异常时，
> 循环照样走完、照样报「使用结束」，而水还在流。
>
> **这个接口可以调，但别拿它的返回值当"设备已关闭"的证据。**

#### 想知道设备到底关没关，用 `queryUsing`

唯一可靠的信号是 4.5 的 `/order/tcpDevice/query/rateOrder/using`：
**服务端说这台设备上没有订单了**，才是真的停了。这也是客户端判定"用水结束"用的信号。

判据必须写成**三态**，不能是 `Boolean`：

| 响应 | 含义 |
|---|---|
| `errorCode == 307` 或 `data.orderNo != null` | **有订单**（还在用）|
| `success == true` 且没有订单 | **没有订单**（确实停了）|
| 请求本身失败 | **不确定** —— 绝不能当成"没有订单" |

```kotlin
// ⚠️ 反面写法：catch 里返回 false，等于把"不知道"当成了"关好了"
try { ... } catch (_: Exception) { false }
```

### 4.5 查询进行中的订单

```
POST /order/tcpDevice/query/rateOrder/using
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

**响应（有进行中订单）**：
```json
{
  "success": true,
  "errorCode": 0,
  "data": {
    "orderNo": "1234567",
    "state": 1,
    "snCode": "QZXY20230001",
    "isOwner": true
  }
}
```

**响应（无进行中订单）**：
```json
{
  "success": true,
  "errorCode": 0,
  "data": {
    "orderNo": null,
    "isOwner": true
  }
}
```

**特殊 errorCode**：
- `307`：表示设备正在使用中（即使 `success` 为 false）

| 字段 | 说明 |
|---|---|
| `orderNo` | 订单号（null 表示无进行中订单） |
| `isOwner` | 是否为当前用户发起的订单。**为 false 时绝不能当成"自己的订单"恢复**——那会把别人的单记成自己的，卡片开始计时，用户点停止时还会拿别人的 orderNo 去关阀 |
| `errorCode: 307` | 设备正在使用中（此时 `success` 为 false，**不能只判 success**）|
| `preDeductMoney` | 预扣金额。⚠️ 单位见 4.11——那套接口是**厘** |
| `expireTimes` | 实测见过这个字段，值为 `null`（当时是本人自己的单）。**目前未被客户端使用**。如果**他人的单**会填它，就能直接拿到"还剩多久"，不必靠猜——值得后续抓一次确认 |

**这个接口是判断"设备关没关 / 还在不在用"的可靠信号**（见 4.4.1）。

### 4.6 账单列表

```
GET /order/query/account/bill/list?month=2026-05&billRequestType=2
```

| 参数 | 说明 |
|---|---|
| `month` | 月份，格式 `yyyy-MM` |
| `billRequestType` | 账单类型，固定传 `2` |

**响应**：
```json
{
  "success": true,
  "data": [
    {
      "consumeBillDTO": {
        "orderId": "1234567",
        "consumeDate": "2026-05-30 10:08:42",
        "consumeMoney": "0.08",
        "description": "热水器:学生公寓-1号楼-3层-301洗手台"
      }
    }
  ]
}
```

### 4.7 使用码

使用码是给热水器**物理键盘**用的：不想掏手机开阀时，在设备上直接输 8 位码。
一共**三个**接口，分工很容易搞混——v3.0.0 之前一直以为是两个。

#### 三个接口的分工

| 接口 | 干什么 | 会不会改当前生效的码 |
|---|---|---|
| `GET /account/useCode/new` | 看当前码 + 今日额度 | 不会 |
| `POST .../generate` | **换一个**（候选码） | **不会** |
| `POST .../set` | **确定领取** | **会**，且只有它会 |

> **这是 v3.0.0 抓包才确认的关键一点**：连换 7 次 `generate` 期间，
> 服务端生效的码一直没变，直到 `set` 那一下 `useCodeStartTime` 才被改写。
> 所以完全可以做成「先预览、再决定领不领」，**取消是零成本的**。

#### 获取当前使用码

```
GET /account/useCode/new
```

```json
{
  "success": true,
  "data": {
    "useCode": "27389960",
    "useCodeStatus": 1,
    "useCodeRandom": "960",
    "useCodeStartTime": "2026-09-17 18:12:05",
    "useCodeExpiringTime": null,
    "resetAvailability": 0,
    "resetAvailabilityWarMark": "1天只能领取一次使用码"
  }
}
```

| 字段 | 说明 |
|---|---|
| `useCode` | 使用码（8 位数字） |
| `useCodeStatus` | `1` = 已开启，`0` = 已关闭 |
| `useCodeRandom` | 后三位，与手机号后三位相同 |
| `useCodeStartTime` | 当前码的生效时间，**每次 `set` 都会被改写** |
| `resetAvailability` | 今天还能不能**重新领取**：`1` = 能，`0` = 不能 |
| `resetAvailabilityWarMark` | `resetAvailability` 为 0 时的原因文案 |

> ⚠️ **没领过使用码的用户，`useCode` 返回的是 `null`**（不是空串也不是 0）。
> 客户端要能把「还没拉到」和「服务端说这人没有码」区分开，
> 否则没领过码的人会永远卡在「加载中」。

#### 换一个（不生效）

```
POST /account/useCode/new/generate
Content-Type: application/x-www-form-urlencoded

loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&
telephone=xxx&telPhone=xxx&phoneSystem=android&version=6.5.28
```

```json
{"success": true, "data": {"useCode": "84663364", "remainTimes": 15}}
```

| 字段 | 说明 |
|---|---|
| `useCode` | 换出来的**候选码**，未生效 |
| `remainTimes` | **剩余可换次数**，每天 20 次，每换一次减 1 |

实测一次连续换码的 `remainTimes` 变化（本次会话共换 7 次）：

```
19 → 18 → 17 → 16 → 15 → 14 → 13
```

即从 20 起算，每调一次扣 1。

> `remainTimes` 和上面 `GET` 里的 `resetAvailability` **不是同一个东西**：
> 前者是「今天还能换几次」（20 次/天），后者是「今天还能不能**领取**」（1 次/天）。

#### 确定领取（唯一生效的一步）

```
POST /account/useCode/new/set
Content-Type: application/x-www-form-urlencoded

useCode=00740364&loginCode=xxx&userId=xxx&...
```

成功返回 `{"success": true, "data": null}`，此后 `GET` 到的 `useCodeStartTime` 会变成这次调用的时间。

> **换出来的码有 3 分钟领取时限**，超时不 `set` 就作废（官方 App 的行为）。
> 客户端应该在预览界面挂个倒计时，别让用户挑了半天再点确定却已经被服务端丢掉了。

#### 开关使用码

```
POST /account/useCode/new/status/update
Content-Type: application/x-www-form-urlencoded

useCodeStatus=1&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `useCodeStatus` | `1` = 开启，`0` = 关闭 |

> 这个接口**也会**改写 `useCodeStartTime`。

---

### 4.8 短信验证码登录

v2.1.0 起可用，**任何手机号都能用，不需要抓包**。关键在于 `secret` 参数并不是随机值，
而是完全由手机号推导出来的（见下方）。

#### 发送验证码

```
GET /user/verification/code/get?telephone={手机号}&typeId=3&platform=1&secret={secret}
```

| 参数 | 值 | 说明 |
|---|---|---|
| `telephone` | 11 位手机号 | |
| `typeId` | `3` | 固定值 |
| `platform` | `1` | 固定值 |
| `secret` | 见下方算法 | 官方 App 发验证码时带的签名 |

#### secret 算法（本项目的核心发现）

抓包时 `secret` 看起来像是每台设备各不相同的随机值，**实际上它只跟手机号有关**：

```
secret = MD5( 手机号前3位 + 手机号后4位 + "klcx" )      // 32 位小写十六进制
```

以 `13800000000` 为例：

```
MD5("185" + "3960" + "klcx")
 = MD5("1853960klcx")
 = e3d2220b920cca13499ea76328e24a4e
```

因为算法里不含任何设备侧密钥，**任何人、任何手机号都能在本地算出自己的 secret**，
所以短信登录可以通用实现。本项目实现见 `utils/SignUtils.kt`。

> 这也说明 `secret` 不是身份凭据——真正的身份校验发生在下一步提交验证码的时候。

#### 提交验证码登录

```
POST /user/registerAndLogin
Content-Type: application/x-www-form-urlencoded

telephone={手机号}&smsCode={验证码}&type=5&phoneSystem=android&version=6.5.28
```

| 参数 | 值 | 说明 |
|---|---|---|
| `smsCode` | 6 位数字 | 收到的短信验证码 |
| `type` | `5` | 固定值 |
| `phoneSystem` | `android` | |
| `version` | `6.5.28` | 与密码登录保持一致 |

> ⚠️ 这个接口**不需要** `loginCode` / `userId` 等认证参数——它本身就是用来换取这些的。

**响应**：与密码登录完全一致（`loginCode` / `userId` / `userAccount` 等），
拿到后按同样的方式保存即可，详见 [2. 认证机制](#2-认证机制)。

#### 与密码登录的差别

| | 密码登录 | 短信登录 |
|---|---|---|
| 接口 | `POST /user/login` | `POST /user/registerAndLogin` |
| 需要 secret | 否 | 是（按手机号本地计算） |
| 返回字段 | 完全一致 | 完全一致 |

---

### 4.9 账号信息与一卡通

v3.0.0 新增。**一卡通余额不再需要破签名**——趣智校园把易校园的接口代理了。

#### 账号信息

```
GET /account/info
```

```json
{
  "success": true,
  "data": {
    "projectId": 905,
    "accountId": 10001,
    "userId": 10000001,
    "telephone": "13800000001",
    "name": "张三",
    "genderName": "未知",
    "idCardNumber": "202400000000000",
    "isCard": 0,
    "cardStatus": -1,
    "cardStatusName": "未绑定"
  }
}
```

| 字段 | 说明 |
|---|---|
| `name` | 姓名。**学校没同步时会是 `null`** |
| `idCardNumber` | **学号**（不是身份证号） |
| `cardStatus` / `cardStatusName` | 校园卡绑定状态，和下面的免密签约是两回事 |

#### 一卡通真实余额

```
GET /settlement/campus/userInfo?projectId=905&telPhone={手机号}&userId=xxx&accountId=xxx&telephone={手机号}&...
```

```json
{
  "success": true,
  "data": {
    "studentNumber": "202400000000000",
    "studentName": "张三",
    "amount": "16.76",
    "signStatus": 1
  }
}
```

| 字段 | 说明 |
|---|---|
| `amount` | **一卡通余额，字符串**（要自己转 Double） |
| `signStatus` | `1` = 已签约免密支付，`0` = 未签约 |
| `studentNumber` / `studentName` | 和 `/account/info` 的学号、姓名一致 |

> ⚠️⚠️ **两个必须知道的坑：**
>
> **1. 这个接口出错也返回 HTTP 200。** 错误在 body 里：
> ```json
> {"success": false, "errorCode": 1, "errorMessage": "手机号不能为空", "data": null}
> ```
> 只看状态码会以为一切正常，然后 `data` 是 null、静默回退到估算余额，
> 表现成「功能没生效」。判断成功必须看 `success` 字段。
>
> **2. 它要的是 `telPhone`，不是 `telephone`。** 绝大多数接口只认 `telephone`，
> 这个偏偏要 `telPhone`（两个都带最保险）。少传就报上面那个「手机号不能为空」。
> 如果用拦截器统一补 GET 参数，**两个字段都要补**，见 9.2。

> 未签约（`signStatus == 0`）时服务端不给 `amount`，客户端只能回退到
> 「初始余额 − 账单消费」的本地估算。

#### 签约状态

```
GET /settlement/withhold/sign/status
```

返回是否已签约代扣。

> ⚠️ **本项目没有实现这个接口**（`QzxyService.kt` 里没有它）——签约与否是直接读
> `/settlement/campus/userInfo` 的 `signStatus` 字段判断的（见上一节）。
> 解约 / 签约要调 `POST /settlement/campus/agreement/open`（需要 `campusAccount` +
> `campusPassword`，即学校统一身份认证账密）和 `POST /settlement/campus/agreement/close`，
> 这两个我们**同样没实现，也从没实测过**——它们来自早期抓包目录，字段与返回结构均无样本。
> 列在这里只为了说明「这条路上还有什么」，**不要照抄去实现**。

#### 学校名

```
GET /project/info/triple
```

```json
{"success": true, "data": {"projectId": 905, "projectName": "某职业技术大学", ...}}
```

`projectName` 就是学校名，`projectDescription` 是学院名。

---

### 4.10 手机号与密码

v3.0.0 新增。**验证码的 `typeId` 决定用途**，用错就收不到码。

| `typeId` | 用途 | `telephone` 填哪个号 | 需要登录态 |
|---|---|---|---|
| `2` | **重置/修改密码** | 当前绑定号 | ✅ |
| `3` | 登录 / 注册 | 要登录的号 | ❌ |
| `5` | **更换手机号** | **新**号 | ✅ |

#### 更换手机号

```
# 1. 发验证码（注意 telephone 是新号！）
GET /user/verification/code/get?typeId=5&telephone={新号}&secret={secret}&...

# 2. 提交
POST /user/phone/update
Content-Type: application/x-www-form-urlencoded

newTelephone=13800000001&code=396948&loginCode=xxx&telephone={旧号}&telPhone={旧号}&...
```

> ⚠️ 验证码发到**新**号，旧号只出现在认证参数（`telephone` / `telPhone`）里。
> 写反了的话用户永远收不到码。这是 v3.0.0 修掉的一个真 bug。

错误码：`29` = 验证码失效，`39` = 新手机号已被其他账号绑定。

#### 修改密码（知道旧密码）

```
POST /user/password/update
Content-Type: application/x-www-form-urlencoded

oldPassword=A98BF854DD&password=16BAC22278&loginCode=xxx&...
```

两个密码都是 **MD5 取后 10 位大写**（见第 3 节），和登录用的是同一套。

#### 重置密码（不知道旧密码）

**这是用验证码注册的账号唯一的出路**——`/user/password/update` 必须带 `oldPassword`，
而短信注册的账号根本没有旧密码可填。

```
# 1. 发验证码（typeId=2，发到当前绑定号）
GET /user/verification/code/get?typeId=2&telephone={当前号}&secret={secret}&...

# 2. 重置
POST /user/password/forget
Content-Type: application/x-www-form-urlencoded

password=0AB7065F1C&code=198871&loginCode=xxx&...
```

**不需要 `oldPassword`。** 新密码同样是 MD5 取后 10 位大写。

### 4.11 消费结果查询

```
POST /order/consumeOrder/result/query
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&orderNo=13200000000000000001&loginCode=xxx&userId=xxx&...
```

**响应**：

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "consumeTime": "2026-09-18 15:06:27",
    "consumeDate": "2026-09-18 15:06:27",
    "consumeMoney": 0,
    "preDeductMoney": 0,
    "preDeductMoneyAfter": 0,
    "orderNo": "13200000000000000000",
    "deviceSnCode": "AABBCCDDEEFF",
    "orderAccountId": 10001,
    "createTime": "1789715194",
    "modeName": null,
    "liquidModeName": null,
    "liquidConsumeMoney": null,
    "leftModeMoney": null,
    "rightModeMoney": null,
    "leftModeName": null,
    "rightModeName": null,
    "clData": null,
    "telephone": "1xxxxxxxxxx"
  }
}
```

#### ⚠️ `consumeMoney` 的单位是**厘**，不是元

**这是这个接口最容易踩的坑。** 它和账单列表里**同名字段**的单位差 1000 倍：

| 接口 | 字段 | 类型 | 同一笔 0.04 元的账返回 |
|---|---|---|---|
| `consumeOrder/result/query` | `consumeMoney` | **数字** | `40` |
| `query/account/bill/list` | `consumeMoney` | **字符串** | `"0.04"` |

字段名一模一样、类型一个数字一个字符串、单位还差 1000 倍。忘了换算的后果不是报错，
而是**静默错 1000 倍**——用了 0.04 元，通知上写「消费 ¥40.00」。

实测证据（09-18 两笔独立订单，用 `dealDate` 对齐同一笔账）：

| `dealDate` | 本接口 | 账单列表 |
|---|---|---|
| `2026-09-18 17:16:28` | `40` | `"0.04"` |
| `2026-09-18 17:16:56` | `80` | `"0.08"` |

#### 为什么该用它，而不是翻账单列表

账单列表里**同名字段**是元，看着更省事，但它**赶不上**结算通知的时间窗：

- 账单是「先以占位的 `consumeMoney: "0.0"` 出现，`uploadDate` 为空，结算完成后才填金额」。
  实测同一笔账：`12:47:36` 是 `"0.0"`、`uploadDate` 空；`12:47:37` 变成 `"0.41"`、`uploadDate` 填上。
- 所以「在账单里查到这一单」≠「金额已经算好了」，拿它当准绳反而可能把真金额覆盖成 0。

这个接口是直接问结算结果，答案**一次就给全**。

#### 什么时候能拿到金额

实测 27 次调用，**第 1 轮的返回就是最终值，从来没有变过**：

- 有消费的单子，关阀后第一次查就返回真实金额（`consumeMoney` 为正）
- 没消费的单子（开完水马上停），第 1 轮就是 `consumeMoney: 0`，
  **且账单列表里始终没有这一单的账单**——两边对得上，这个 0 是真的

所以不需要像轮询账单那样重试很多轮：查一次，是 0 就再确认一次，够用了。

| 字段 | 说明 |
|---|---|
| `consumeMoney` | 消费金额，**单位：厘**（1 元 = 1000 厘）|
| `consumeTime` / `consumeDate` | 都是**下单/结算时刻**（`yyyy-MM-dd HH:mm:ss`），和 `downRateResult` 里紧凑格式的同名字段不是一回事 |
| `orderNo` | 就是请求时传进来的那个 |
| `createTime` | 服务端当前时间（Unix 秒），**每次请求都在变**，不是下单时间 |
| `preDeductMoneyAfter` | 结算后剩余的预扣额 |

> ⚠️ 响应里**没有** `consumeMoneyStr`，也**没有** `state` / `result` / `status`
> ——那几个是 `downRateResult` 的字段，别混。客户端早期版本照隔壁接口的模型解析这个响应，
> 属于没样本时的猜测。

### 4.12 多项目板块通道

v3.1.0 新增。**一个手机号可以绑定多个完全独立的项目**，不是「一个项目里的多个板块」。

实测某校（同一个 `userId`）：

| 板块 | `projectId` | `accountId` | 业务 | 计费 |
|---|---|---|---|---|
| 洗浴 | **4000** | 3987（实测，见下方 JSON） | 热水表 | 按量 |
| 洗衣饮水吹风 | **3356** | 15384（实测，见下方 JSON） | 洗烘 / 饮水 / 吹风机 | 套餐（饮水机除外） |

**两个钱包独立、账单独立、设备独立**，`loginCode` 全局只有一套。

> ⚠️ **`accountId` 一律运行时从 `/user/project/list` 读，不要硬编码。**
> 上面那两个值是本次核实账号的，换账号就变；而且 `docs/交接文档.md` 第二节里记的
> 是另一组数字（`4000 → 10000002`、`3356 → 10000101`），与这里的实拉 JSON 对不上。
> 以接口返回为准——本项目代码也从不硬编码它。

#### 绑定项目列表

```
GET /user/project/list
# 认证参数由拦截器自动追加
```

**响应 `data`**（2026-10-24 00:38 在线实测，原样摘录两条）：

```json
[{"userId":10000001,"projectId":3356,"accountId":15384},
 {"userId":10000001,"projectId":4000,"accountId":3987}]
```

| 字段 | 说明 |
|---|---|
| `projectId` | 项目（学校/板块）ID，**不同项目不同** |
| `accountId` | 该账号在**这个项目下**的账户 ID。⚠️ 同一账号在两个项目下的 `accountId` **不同**，别硬编码 |
| `userId` | 全局用户 ID，两个项目相同 |

> ⚠️⚠️ **`projectId` 和 `accountId` 必须成对切换。**
> 只换 `projectId` 不换 `accountId`，服务端回「**没有项目权限，请确认身份**」；
> 而这种错误在界面上只表现为「数据是空的」，极难排查。
> 配对逻辑必须收口在一处（本项目是 `ProjectSelectionPolicy.resolve()`），找不到配对就**放弃切换**，
> 绝不能拿当前板块的 `accountId` 去配新的 `projectId`。

> 💡 这个接口还顺带能**验证 loginCode 是否有效**：实测只带 `loginCode` 时任何接口都返回
> `errorCode=8 未获取到用户信息`，`loginCode + userId` 才拿得到板块列表。
> 服务端把这两个当**一对**识别会话，而 `loginCode` 是 32 位随机 hex，反推不出 `userId`。

#### 项目业务模块

```
GET /project/module/list
# 同样由拦截器带上 projectId/accountId
```

**实测（`projectId=3356`）返回 8 个模块，只有 3 个启用**：

> **未采到样本**：仓库里没有留存这个接口的原始响应报文，下面这张表是逐条在线核对出来的
> 模块/启用情况，字段名来自客户端模型而非响应样例。

| `moduleId` | `moduleName` | `isUse` |
|---|---|---|
| 3 | 洗烘 | ✅ 1 |
| 4 | 饮水 | ✅ 1 |
| 5 | 吹风机 | ✅ 1 |
| 1 / 2 / 6 / 7 / 8 | 公寓洗澡 / 澡堂洗澡 / 无线澡堂 / 充电 / 远传水电表 | ❌ 0 |

官方 App 首页那排图标就是按它渲染的，`isUse == 1` 才显示。
用它判断「这个学校有没有洗衣机业务」，比硬编码 `bigTypeId` 更靠谱。

| 字段 | 说明 |
|---|---|
| `moduleId` | 模块 ID |
| `moduleName` | 模块名（洗烘 / 饮水 / 吹风机 …） |
| `isUse` | `1` = **模块启用**；`0` 或其它 = 不启用。⚠️ 这个字段和设备侧的 `isUse` 不是一回事：设备台账里的 `isUse` **反直觉**，空闲设备也是 `1`，**两个都不能拿来判断「设备是否正在使用」** |
| `projectId` | 归属项目 |
| `bigTypeIds` | 模块对应的大类 id 列表，**服务端实测为 `null`，别依赖** |
| `moduleKey` / `openUseParagraph` / `restraintType` / `restraintContext` | 客户端未使用 |

> ⚠️ **必须用该项目自己的 `projectId`+`accountId` 调。**
> 实测拿 4000 调这个接口返回「没有项目权限，请确认身份」，
> 所以不能靠它判断板块业务——要用就必须先切到目标板块。

### 4.13 按套餐计费的设备

v3.1.0 新增，覆盖**洗衣机 / 吹风机**。四个接口自成一套，和 [两种计费通道](#两种计费通道rateorder-与-modeorder-不是一回事) 里那条按量通道没有任何交集。

#### 套餐列表

```
GET /device/mode/list?smallTypeId=3      # 洗衣机
GET /device/mode/list?smallTypeId=5      # 吹风机
```

| 参数 | 值 |
|---|---|
| `smallTypeId` | 洗衣机 `3`，吹风机 `5`（设备台账的 `smallTypeId` 原样传） |

**响应**（洗衣机，抓包原文，第 1 档 + 其余 3 档结构相同）：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":[
  {"projectId":3356,"id":2835,"modeId":33,"smallTypeId":3,
   "modeName":"标准洗","modeDesc":"一般日常的洗涤衣物",
   "modeMoney":4000,"modeMoneyYuan":4.0,
   "modeTimeMinute":"35","modeTimeQuantity":2100,
   "typeValue":"4.0元/35分钟","isSelected":1,
   "positionId":null,"goodsId":null}
]}
```

| 设备 | `modeId` | 名称 | 价格（厘） | 时长 |
|---|---|---|---|---|
| 洗衣机 | 33 / 34 / 35 / 36 | 标准洗 / 快速洗 / 单脱水 / 大件洗 | 4000 / 3000 / 1000 / 5000 | 2100 / 1500 / 600 / 2700 秒 |
| 吹风机 | 29 / 30 / 31 | 电吹风模式一 / 二 / 三 | 600 / 1000 / 2000 | 180 / 300 / 600 秒 |

| 字段 | 说明 |
|---|---|
| `modeId` | **下单时当 `xfModel` 传这个**，不是 `id` |
| `id` | 套餐记录行号，和 `modeId` 是两回事 |
| `modeMoney` | 价格，**单位厘**（1000 厘 = 1 元）。换算用 `MoneyFormat.milliToYuan()` |
| `modeMoneyYuan` | 同价格，单位元（Double）。冗余字段 |
| `modeTimeQuantity` | 时长，**秒** |
| `modeTimeMinute` | 时长，**字符串**分钟。冗余字段但界面要用 |
| `typeValue` | 官方展示串，如 `"0.6元/3分钟"` |
| `isSelected` | `1` = 官方默认高亮这一档 |

> ⚠️ **空列表不能当「这台设备没有套餐」**：断网时同样拿到空列表，
> 而两种情况处理完全不同（一个让用户重试，一个让他找管理员）。

#### 按套餐下单（★ 扣钱）

```
POST /order/tcpDevice/downRate/modeOrder
Content-Type: application/x-www-form-urlencoded

xfModel=29&snCode=C47F0E2351BE&isHaier=0&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `xfModel` | **套餐的 `modeId`**（不是 `id`，也不是固定 `0`） |
| `snCode` | 设备序列号 |
| `isHaier` | 固定 `"0"`（客户端默认值） |

**响应（实测，吹风机 0.6 元那单，00:12:24）**：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":null}
```

> ⚠️⚠️ **`data` 恒为 `null`，`orderNo` 不在这里。**
> `orderNo` 只有下面几条路（本项目用了前两条）：
>
> | 路径 | 说明 |
> |---|---|
> | MQTT `app_downRate_{phone}` | 最快（实测几十毫秒）。实测推送原文含 `orderNo` / `preDeductMoney` / `deviceSnCode` / `consumeSceneType:4` |
> | `GET /order/query/lastOrder/byMac` | 兜底，免费但慢（时间窗口实测介于 1 小时~19 天之间） |
> | `GET /order/query/account/bill/detail` | 无时间窗口，**本项目未接入**（手机时钟与服务端时钟差几十秒就查不到，要 ±2 分钟试探） |
>
> 而且 MQTT broker 实测换过（洗衣机 `47.107.37.60` / 吹风机 `39.108.147.136`），
> 连不上就一条推送都收不到——**byMac 这条兜底不是可选项**。

> ⚠️⚠️⚠️ **这是一发出就扣套餐全款、且不可退的接口，服务端没有任何 stop/close/cancel/refund。**
> 调用方必须自己做三件事（客户端替你做不了）：
>
> 1. **二次确认 UI**：点之前让用户看清「X 元 / N 分钟」，明说「立即扣款、不可退款、到点自动结束」
> 2. **防重复点击**：本地「已有进行中的单」能挡，但**挡不住双击造成的两次并发请求**
> 3. **文案不写「停止」**：没有这个功能，写「到点自动结束」
>
> 另外**请求超时/抛异常时不能报失败**：钱可能已经扣了，报失败会引导用户再点一次 = **扣第二份钱**。
> 正确做法是进入「已扣款、单号待补」状态，让界面显示「正在获取订单信息」并允许稍后重试。

> 💡 判断 byMac 查到的是不是**这一单**，要比对 `xfModel`（套餐档位）和 `consumeDate`
> （服务端时间，允许 5 分钟倒挂）——否则会把半小时前那单的单号贴到这次消费上。

#### 套餐设备状态

```
POST /order/tcpDevice/washer/status
Content-Type: application/x-www-form-urlencoded

snCode=C47F0E2351BE&isHaier=0&loginCode=xxx&userId=xxx&...
```

> ⚠️ 名字叫 `washer`，但**吹风机也调它**，不是洗衣机专用。

**响应（空闲，实测）**：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":null}
```

**响应（被占用，2026-10-24 09:51 实测样本，洗衣机被别人用着）**：

```json
{"success":true,"errorCode":307,"errorMessage":"设备正在使用中",
 "data":{"expireTimes":729,"orderNo":null,"isOwner":false,"accountId":null,
         "xfModelName":null,"preDeductMoney":null,"modeMoney":null,"modeTime":null,
         "liquidOrderNo":null,"liquidOrderStatus":null,"liquidModeName":null,
         "liquidConsumeMoney":null,"liquidOrderStatusDisplay":null,
         "leftGoodsModel":null,"rightGoodsModel":null,
         "leftModeMoney":null,"rightModeMoney":null,"leftModeName":null,"rightModeName":null,
         "leftGoodsName":null,"rightGoodsName":null,"relatePreDeductMoney":null}}
```

| 字段 | 说明 |
|---|---|
| `errorCode` | `307` = 设备正在使用中。⚠️ 此时 **`success` 仍然是 `true`**，只看 `success` 会误判 |
| `expireTimes` | **剩余秒数**。实测三轮 `729 → 649 → 589` 递减，与「快速洗 25 分钟」那单的到期时刻吻合到 2 秒 |
| `orderNo` | 别人的单这里是 **`null`**（服务端不给他人单号）。**别指望从这里取 orderNo** |
| `isOwner` | `false` = 别人的单。**自己的单未采样**（推测 `true`） |
| 其余字段 | 这一版服务端全填了 `null`，不要假设哪个字段一定有值 |

判据必须写成**三态**（`ModeOrderController.queryStatus()`）：

| 响应 | 含义 |
|---|---|
| `errorCode == 307` 或 `success && data != null` | **占用中** |
| `success` 且 `data == null` | **空闲** |
| 请求本身失败 / `success == false` | **不确定** —— 绝不能当成空闲 |

> ⚠️ **「不确定」绝不能返回空闲。** 断网时把「不知道」当「空闲」，会让用户站在别人正在用的机器前
> 看到「可使用」，点下去就扣他的钱。
>
> ⚠️ 本接口的 `data != null` 判据**不能套到按量设备上**：热水器的 `queryUsing` 空闲时 `data` 也非空
> （只是 `orderNo == null`），照搬会让热水器永远显示被占用。

#### 按 MAC 查最近一单

```
GET /order/query/lastOrder/byMac?macAddress=C47F0E2351BE&isHaier=0
```

| 参数 | 说明 |
|---|---|
| `macAddress` | 设备 MAC。⚠️ 两种写法实测都能查到：本项目传 12 位**不带冒号**（`snCode == mac 去冒号`）；官方 App 抓包传带冒号的形式（URL 里编码成 `C4%3A7F%3A0E%3A23%3A51%3ABE`） |
| `isHaier` | 固定 `0` |

**响应（实测，抓包原文）**：

```json
{"success":true,"errorCode":0,"errorMessage":"成功","data":{
  "orderId":11868418,"orderNo":"13202610240012244626","accountId":15384,
  "deviceId":1252,"smallTypeId":5,"macAddress":"C47F0E2351BE",
  "deviceName":"吹风机-吹风机-3号宿舍楼-6层-东边卫生间-1",
  "preDeductMoney":600,"xfModel":29,
  "consumeDate":"2026-10-24 00:12:24",
  "modeName":"电吹风模式一","modeMoney":600,"modeTime":180,
  "liquidModeName":null,"liquidConsumeMoney":null,"liquidOrderNo":null,"liquidOrderStatus":null}}
```

| 字段 | 说明 |
|---|---|
| `orderNo` | **20 位订单号**，关阀/结算/对账用的是它 |
| `orderId` | 账单**序号**（`11868418`）。⚠️ 和 `orderNo` 不是一回事，别互相比 |
| `preDeductMoney` / `modeMoney` | 单位**厘**（`600` = 0.6 元） |
| `modeTime` | 套餐时长，**秒** |
| `consumeDate` | `yyyy-MM-dd HH:mm:ss`，**服务端时间**（MQTT 里是紧凑格式，不是一回事） |
| `xfModel` | 套餐 `modeId`，用来核对「查到的是不是刚下这一单」 |

> ⚠️ **有时间窗口，不能当「设备空闲」判据。** 实测：1 小时前的单查得到，
> 19 天前的单返回 `data: null`，**乱填一个不存在的 MAC 也返回同样的 `null`**（不做错误区分）。
>
> 💡 **开发期「免费神器」**：不用真的下单就能拿到真实 `orderNo` / 套餐名 / 金额，
> 足以把解析和界面全部验证完。新设备类型接入时先调这个，能省掉反复花钱试错。

### 4.14 残留账单与手动代扣

v3.0.1 新增。12 点后结束用水、或一卡通余额不足时，服务端结算没扣成，
账单会留在**待扣状态**——俗称「残留账单」。两个接口一个查、一个扣。

#### 未支付账单列表

```
POST /order/weixinScorePay/unPay/queryBill
Content-Type: application/x-www-form-urlencoded

loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&telephone=xxx&telPhone=xxx&phoneSystem=android&version=6.5.28
```

> **未采到样本**：仓库里没有留存这个接口的完整原始响应，下面只有客户端解析的字段
> 和实测结论。字段名以实际抓包为准，别把这张表当响应样例。

**`data` 是扁平数组**（不像账单列表那样套一层 `consumeBillDTO`，两个模型不通用）：

| 字段 | 说明 |
|---|---|
| `consumeDate` | **最重要**：代扣接口唯一能定位一张账单的字段。为空的账单根本发不出去 |
| `consumeMoney` | 欠款金额（字符串，元） |
| `orderId` / `orderNo` | 账单序号 / 订单号，仅用于显示与匹配 |
| `description` | 账单描述（设备名） |

> ⚠️ **哪些账单能扣，由服务端说了算**，别去猜账单里 `consumeMoneyReal` / `upState` 那些字段的含义
> ——它们看着像，但都是猜的。

#### 手动代扣（JSON body）

```
POST /order/thirdTrade/single/json/pay
Content-Type: application/json; charset=utf-8
```

**这是全项目唯一一个非 form 的 POST**，照抄别的写法会失败。实测请求体（2026-09，抓包原文）：

```json
{"deductType":7,"consumeDate":"2026-09-16 21:03:17",
 "projectId":"905","accountId":"10000","loginCode":"...","userId":"10000000",
 "phoneSystem":"WeChat"}
```

| 字段 | 说明 |
|---|---|
| `consumeDate` | **账单定位字段**（不是 `orderNo`），值来自未支付列表 |
| `deductType` | 固定 `7`。**只有一个样本，不保证是常量**——若服务端按渠道变，会返回错误码而不是扣错钱 |
| `projectId` / `accountId` / `loginCode` / `userId` | 认证参数，从当前会话取 |
| `phoneSystem` | **`"WeChat"`**，不是 `android`。这个接口是官方 App 里那个 H5 页面调的，和原生请求不是一条路 |

> ⚠️ **`phoneSystem` 照抄 `WeChat` 的原因**：服务端万一校验这个字段，传 `android` 就会被拒；
> 照抄成功过的那份最稳。
>
> ⚠️ **值必须转义**，所以用 Gson 拼 JSON，不要字符串模板。

**响应**：成功为 `{"success":true}`（`data` 无业务含义，按 `Unit` 解析）。
本次未留存完整原始报文——**成功判据只看 `success`**。

> ⚠️ **这是动钱的接口，防重是第一要务。** 客户端必须分**三态**，不能只有成功/失败：
>
> | 结果 | 处置 |
> |---|---|
> | `success == true` | 成功，立刻把这条从未支付集合里摘掉（**不等网络往返**，否则刷新失败按钮还挂着，用户会以为没扣又点一次） |
> | 服务端明确拒绝（余额不足 / 账户异常…） | 失败，可以放心重试，并把服务端原文显示给用户 |
> | **超时 / 断连 / 协程被取消** | **结果未知**——不能报失败，也不能让用户重试，只能重查一次未支付列表来定夺 |
>
> 另外：请求进行中直接拒绝新请求、按钮置灰文案变「扣款中…」、点按钮先弹确认框显示金额。

> 实测故障样本（2026-09-16 00:02）：返回 `errorCode: -1`、`errorMessage`「账户异常」。
> **不是代码问题**——同一时刻 `/settlement/campus/userInfo` 也返回 `-1`、`接口请求异常`，
> 整个一卡通子系统在报错（疑似日终维护）。次日同一接口正常后立刻扣成功。

### 4.15 蓝牙水表通道

v3.1.0 新增。部分学校的表**不联网**，只能由**手机当网关**：
服务端只下发费率包，阀由手机通过 BLE 写进设备。

> ⚠️ **怎么识别一台表是蓝牙表**：服务端字段 `isBle`（`/device/info/mac`）。
> 但代码里记了实测结论——**某些学校压根不下发这个字段**，于是判据恒为 `false`，
> 这类设备会退回云端通道并收到 `306 设备不在线`。
> **别用 `smallTypeId == 1` 当蓝牙特征**：那个值的含义是「热水表」通用类型名，
> 实测某校 162 条设备全是 `smallTypeId == 1` 却都是正常的 4G 表，误判会导致**全校开不了阀**。
> 漏判只是少一个功能，误判是全校不可用——两者代价不对称，宁漏勿误。

| | `tcpDevice/*`（4G 表） | `bluetooth/*`（蓝牙表） |
|---|---|---|
| 控制通道 | 云端把指令推给设备 | **手机 BLE 直连** |
| 服务端角色 | 直接控制设备 | 只下费率包、记账 |
| 设备要求 | 自带 4G | **没有 4G** |
| 断连后果 | 云端能兜 | **设备可能停在「开了但没记账」** |
| 额外依赖 | 无 | BLE 帧协议 + 请求签名（纯 Kotlin，不需要 `.so`） |

> ⚠️ 对蓝牙表调 `tcpDevice/*` 必然回 `306 设备不在线`——两条通道不能混用。

三个接口的签名算法见 `utils/KlcxkjSigner`（嵌套 MD5，
内层 `MD5(raw + "&key=" + loginCode)`、外层 `MD5(内层 + "&key=sign-kailu-…")`），
**各接口参与签名的字段不一样**，写错就被判签名无效：

| 接口 | 参与签名的字段 |
|---|---|
| `downRate/bluetooth/rateOrder` | `telephone`、`deviceId`、`xfModel`、`randomNumber` |
| `upload/bluetooth/data` | `loginCode`、`telephone`、`xfData` |
| `upload/bluetooth/fail` | `consumeDate`、`loginCode`、`telephone` |

签名原文按 **key 字典序升序**拼成 `key1value1key2value2…`，`value` 为 `null` 时**整个字段跳过**
（不是当空串）。

#### 下发费率（开阀第一步）

```
POST /order/downRate/bluetooth/rateOrder
Content-Type: application/x-www-form-urlencoded

deviceId=1252&macAddress=C47F0E2351BE&macType={type}{a1}
&bigTypeId=7&smallTypeId=5&protocolType={状态帧}&randomNumber={状态帧 8 位 hex}
&xfModel=0&signature=xxxx&loginCode=xxx&...
```

| 参数 | 说明 |
|---|---|
| `deviceId` | 台账里的设备 ID（`/device/info/mac` 的 `deviceId`） |
| `macAddress` | 设备 MAC |
| `macType` | 由设备状态帧的**两个字节**拼成：`%02x%02x`（协议版本 `type` + `a1`），**小写** |
| `protocolType` / `randomNumber` | **都必须用设备状态帧里给的**，本地生成的会被服务端判 `226 加密校验失败` |
| `bigTypeId` / `smallTypeId` | 设备台账里的数字类型 |
| `xfModel` | 蓝牙表这条链路沿用 `0`，与 4G 表一致 |
| `signature` | 见上方签名表 |

**响应 `data`**（客户端解析的字段；**完整原始响应未采到样本**，仓库里没留存报文）：

| 字段 | 说明 |
|---|---|
| `downData` | ⭐ **要写进设备的费率包原文（HEX 字符串）**。必须原样 hex 解码后交给 BLE 层；解析失败或为空时**绝不能继续开阀** |
| `consumeDate` | 结算用的消费时间戳。⚠️ **失败上报要的就是它**，所以开阀成功时就要存下来 |
| `orderNo` | 蓝牙通道的单号（部分学校口径下可能落在 `liquidOrderNo`，两个都空也出现过） |
| `liquidOrderNo` | 上面那条的备用字段 |
| `perMoney` / `rate` | 费率，仅用于显示 / 日志 |

> ⚠️ **费率包必须来自服务端。** 自己造一个写进设备，后果不是「失败」，而是**可能多扣钱或损坏设备**。

#### 上传消费数据（结算）

```
POST /order/upload/bluetooth/data
Content-Type: application/x-www-form-urlencoded

protocolType={状态帧}&randomNumber={状态帧}&xfData=80xxxx（小写 hex）&signature=xxxx&loginCode=xxx&...
```

| 参数 | 说明 |
|---|---|
| `protocolType` / `randomNumber` | 取自设备状态帧 |
| `xfData` | 从设备采集到的消费数据（HEX）。⚠️ **必须小写**：签名用小写、上传字段也得小写，否则服务端重算签名对不上 → `170 签名错误` |

**响应 `data`**（结算结果；**完整原始响应未采到样本**）：

| 字段 | 说明 |
|---|---|
| `clData` | 设备域加密数据，解密后 `0x86` 写回设备清记录。本流程一般用不到 |
| `upMoney` | 本次消费金额。⚠️ 服务端在不同接口/学校用过 `upMoney` 和 `consumeMoney` 两个名字，两个都要接；**单位未确认**（无实测样本，显示前先跑一次真实结算并用账单列表交叉验证） |
| `upLeadMoney` | 结算后剩余的预扣额（别名 `preDeductMoneyAfter`） |
| `perMoney` | 预扣金额（别名 `preDeductMoney`） |
| `fishTime` | 消费时间（别名 `consumeTime`） |

**哪些错误码算成功**：实测 `0` / `206` / `209` 三种都被服务端当作成功（历史口径不一）。

> ⚠️ 已知的两个坑：
> - `170` = 签名与上传字段不一致（根因是 `xfData` 大小写，2026-09-29 定案）
> - `226` = 加密校验失败。**关阀后不能在同一连接里立刻采集**，必须断开重连再采集，
>   否则账单会停在 `0.0`（实测同一笔订单 97547 对比确认）
>
> 上传前还要校验消费体：长度不足或首字节不是成功标记 `0x80` 时**不要上传**——
> 半截数据会被服务端当成一条消费记录，轻则报错重则记错账。

#### 上报失败订单

```
POST /order/upload/bluetooth/fail
Content-Type: application/x-www-form-urlencoded

consumeDate={开阀时服务端给的}&signature=xxxx&loginCode=xxx&...
```

| 参数 | 说明 |
|---|---|
| `consumeDate` | 开阀时从费率响应里存下来的那个。**为空就别发**（发也定位不到单）。⚠️ 具体格式未见样本，原样透传、不要自己重组 |
| `signature` | 见上方签名表 |

**响应**：只判 `success`（`data` 无业务含义）。**完整原始响应未采到样本**；幂等性由服务端保证，
上报失败只记日志，**不能因此把已经关好的阀又「报成失败」**。

> ⚠️ **这个是必须实现的，不是可选项。** 蓝牙开阀**没有服务端兜底**：
> 费率包下发后如果没能正常结算（断连、采集失败、用户走远），
> 设备侧可能停在「开了但没记账」的状态，得靠它告诉服务端这一单作废。

### 4.16 已绑卡信息兜底

```
GET /account/card/getBindCardInfo
```

**存在的意义是兜底，不是替代 `/account/info`。** `/account/info` 的 `name` 要学校把学籍数据同步过来才有，
没同步的账号（实测同学的两个号都是）返回 `null`；这两个接口**数据来源不同**，一个没有另一个可能有。

字段是 [4.9 `/account/info`](#49-账号信息与一卡通) 的**超集**：姓名、学号都在，
还多了 `lastConsumeTime` / `useCount` / `cardCost`（本项目未用）。

> 客户端复用 `AccountInfo` 模型解析（Gson 只映射存在的字段），不另建模型。

| 字段 | 说明 |
|---|---|
| `name` | 姓名。和 `/account/info` 一样**可能为 `null`** |
| `idCardNumber` | **学号**（不是身份证号） |
| `lastConsumeTime` / `useCount` / `cardCost` | 上次消费时间 / 消费次数 / 卡消费额，本项目未用 |

> **未采到样本**：仓库里没留存这个接口的完整原始响应，只有「姓名/学号能拿到」这一条实测结论。
> 所以字段表是按客户端模型列的，**不是响应样例**。

> 💡 姓名实际有**三个来源**，谁有值用谁：`/account/info` → `/account/card/getBindCardInfo`
> → `/settlement/campus/userInfo`（后者的 `studentName`）；学号同理（`idCardNumber` / `studentNumber`）。
> 收口在一个函数里统一写 Prefs **和**界面 State——只写 Prefs 不会触发重组，数据回来了界面也不刷新。

---

## 5. MQTT 实时推送

### 连接信息

| 项 | 值 |
|---|---|
| 服务器 | `tcp://47.107.37.60:1883` |
| 协议 | MQTT 3.1.1（明文 TCP，无 TLS） |
| 认证 | 无（匿名连接） |
| ClientId | 随机生成 |

> ⚠️ **broker 不止一个。** 实测：洗衣机抓包是 `47.107.37.60:1883`，
> **吹风机抓包是 `39.108.147.136:1883`**。可能是按学校/项目分片，也可能旧地址已下线。
> 本项目客户端硬编码了前者，所以吹风机那条路上**很可能一条推送都收不到**——
> 这也是 [4.13](#413-按套餐计费的设备) 里 `lastOrder/byMac` 兜底不是可选项的原因。

### 订阅主题

开始洗澡后，需要订阅以下三个主题（`{phone}` 替换为用户手机号）：

| 主题 | 说明 |
|---|---|
| `app_downRate_{phone}` | 开始洗澡事件推送。⚠️ **套餐下单（洗衣机/吹风机）的 `orderNo` 也发在这个 topic 上**，见下 |
| `app_shutdownOrder_{phone}` | 停止洗澡事件推送 |
| `app_uploadData_{phone}` | 实时消费金额推送 |

> ⚠️ **同一个 topic 上混着两种单**：热水器的 `rateOrder` 和洗衣/吹风的 `modeOrder`
> 都推到 `app_downRate_{phone}`。如果只按 topic 收，会把水单的单号记到洗衣机头上。
>
> 过滤规则（按可靠性排序，本项目 `ModeOrderController.onMqttMessage`）：
>
> 1. 推送里有 `deviceSnCode` → 必须等于本机记着的那个 `snCode`（服务端给的设备标识最可信）
> 2. 没有 `deviceSnCode` → 退到 `consumeSceneType == 4`（唯一一个吹风机样本的取值）
> 3. 都判不了 → **丢弃**，让 `lastOrder/byMac` 兜底。**记错单号比暂时没有单号更糟**

### 消息格式

```json
{
  "orderNo": "1234567",
  "consumeMoney": 0.08,
  "state": 1,
  "result": 0
}
```

| 字段 | 说明 |
|---|---|
| `orderNo` | 订单号（开始洗澡后通过此字段获取） |
| `consumeMoney` | 当前已消费金额（元） |
| `state` | 订单状态 |
| `result` | 操作结果 |

### 使用场景

1. **获取 orderNo**：调用 `downRate` 后，MQTT 会推送包含 `orderNo` 的消息
2. **实时更新余额**：`app_uploadData` 主题会推送 `consumeMoney` 更新
3. **HTTP 轮询兜底**：如果 MQTT 连接失败，应使用 HTTP 轮询 `queryUsing` 作为备选方案

> ⚠️ **套餐设备（洗衣机 / 吹风机）的用法不一样**：它们**没有 `queryUsing` 这样的兜底**，
> 只有 MQTT 和 `lastOrder/byMac` 两条路；而且套餐单**全程不轮询**
> （实测 180 秒里 App 一个业务请求都没发），倒计时是**纯本地**的，服务端到点自己关停。
> 「结束」这个事件在服务端没有可等的东西。详见 [4.13](#413-按套餐计费的设备)。

---

## 6. 蓝牙设备发现

### BLE 扫描

趣智校园热水器通过 BLE（低功耗蓝牙）广播设备信息。

**设备名称过滤**：包含 `KLCXKJ-Water`（不区分大小写）

```kotlin
// Android BLE 扫描示例
val scanner = bluetoothAdapter.bluetoothLeScanner
val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .build()

scanner.startScan(null, settings, object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val name = result.device.name ?: return
        if (name.contains("KLCXKJ-Water", ignoreCase = true)) {
            // 发现热水器设备
            val mac = result.device.address   // MAC 地址
            val rssi = result.rssi            // 信号强度
        }
    }
})
```

### 信号强度参考

| RSSI 范围 | 信号等级 |
|---|---|
| ≥ -70 dBm | 强 |
| -70 ~ -85 dBm | 中 |
| < -85 dBm | 弱 |

### 设备发现流程

```
1. BLE 扫描 → 获取 MAC 地址
2. 调用 GET /device/info/mac?macAddress=xxx → 获取设备详情（snCode、名称等）
3. 调用 POST /order/tcpDevice/query/rateOrder/using → 检查是否有进行中订单
```

> 💡 v3.1.0 起 BLE **不只是发现**：蓝牙水表（`isBle` 为真）的**开阀 / 关阀 / 结算全靠 BLE 直连**，
> 手机是网关。控制流程见 [4.15](#415-蓝牙水表通道)，帧协议在项目的 `ble/` 包。

---

## 7. 完整业务流程

### 洗澡流程

> ⚠️ **v3.0.0 起，订单状态的轮询和超时关停由前台服务（`ShowerWatchService`）负责**，
> 不再是界面里的定时器。这是关键区别：以前退出使用页或被划掉，倒计时就停摆，
> 超时了也没人管。现在监控跟界面彻底解耦。

```
用户打开 App
    │
    ├── BLE 扫描附近设备
    │       │
    │       └── 获取 MAC → 查询设备信息 → 获取 snCode
    │
    ├── 选择设备 → 显示设备详情弹窗
    │       │
    │       ├── 查询 queryUsing → 检查是否已有进行中订单
    │       │       │
    │       │       ├── 有订单 + isOwner=true  → 直接进入洗澡中界面（恢复订单，不会重新开阀）
    │       │       ├── 有订单 + isOwner=false → 拒绝，提示「他人使用中」
    │       │       │
    │       │       └── 无订单 → 调用 downRate 开始洗澡
    │       │               │
    │       │               ├── 连接 MQTT 订阅推送
    │       │               ├── 进入洗澡中界面
    │       │               └── 轮询 queryUsing 获取 orderNo（最多 10 次，间隔 800ms）
    │       │
    │       ├── 启动前台服务 ShowerWatchService
    │       │       │
    │       │       ├── 挂常驻通知（带「结束用水」按钮）
    │       │       ├── 每 15 秒查询一次订单还在不在
    │       │       │     剩 ≤14 秒时改成精确等到超时那一刻
    │       │       ├── 查到订单没了 → 关阀 + 结算 + 发结束通知
    │       │       └── ⚠️ 网络失败时**什么都不做**，下一轮再说，
    │       │             绝不因为一次抖动就误判成「已结束」
    │       │
    │       └── 洗澡中界面
    │               │
    │               ├── 显示计时器（每秒刷新，起点是持久化的开阀时间戳）
    │               ├── 显示预扣金额
    │               └── MQTT 推送更新消费金额
    │
    ├── 用户点「结束使用」（界面 / 通知栏 / 桌面小组件，三个入口）
    │       │
    │       ├── 界面入口 → 通知服务
    │       ├── 通知 / 小组件入口 → 直接走服务
    │       │
    │       └── 服务统一执行：挂「正在结束…」→ closeOrder 轮询确认关阀
    │              → 查账单结算金额 → 发「使用结束」通知 → 撤通知、停服务
    │
    └── 使用码启动的设备（物理键盘操作）
            │
            └── 刷新时 queryUsing 发现进行中订单 → 自动添加到活跃列表
```

### 登录流程

**两种方式，返回的字段完全一致**，拿到之后处理方式也一样：

```
方式 A：手机号 + 密码（用户自己设过密码）
    │
    ├── 密码 MD5 加密（取后 10 位大写）
    ├── POST /user/login
    └── 成功 → 保存 loginCode / userId / accountId / projectId → 进主页

方式 B：手机号 + 短信验证码（v2.1.0 起，**App 默认用这个**）
    │
    ├── secret = MD5(手机号前3位 + 后4位 + "klcx")   ← 本地算，任何人可用
    ├── GET /user/verification/code/get?typeId=3&telephone=xxx&secret=xxx
    ├── POST /user/registerAndLogin   ← 没注册过的手机号**会自动注册**
    └── 成功 → 同上

自动登录：
    └── 从 EncryptedSharedPreferences 恢复认证信息 → 进主页
```

> 密码登录有个先天限制：**用验证码注册的账号根本没有密码**，所以 App 默认走验证码。
> 忘了密码也有出路，见 [4.10](#410-手机号与密码) 的重置密码分支。
>
> ⚠️ 两种方式都必须处理**挤号**：同一账号不能在多设备同时在线，被挤下去时
> 服务端返回含「登录 / token / 失效 / 过期」字样的错误，或 HTTP 401/403。

短信验证码登录（替代上面「输密码 + MD5 + /user/login」这三步）：

```
用户输入手机号
    │
    ├── 本地算 secret = MD5(前3位 + 后4位 + "klcx")     ← 不需要抓包
    │
    ├── GET /user/verification/code/get?telephone=...&typeId=3&platform=1&secret=...
    │       └── 服务器下发短信验证码
    │
    ├── 用户输入收到的验证码
    │
    └── POST /user/registerAndLogin  (telephone + smsCode + type=5)
            │
            └── 成功 → 返回字段与密码登录完全一致
                        → 同样保存 loginCode / userId / accountId / projectId
```

---

## 8. 数据模型参考

### 统一响应格式

所有 API 返回统一的 JSON 结构：

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | Boolean | 请求是否成功 |
| `errorCode` | Int | 错误码，0 表示成功 |
| `errorMessage` | String? | 错误信息 |
| `msg` | String? | 备用错误信息字段 |
| `data` | T? | 业务数据（类型因接口而异） |

### 特殊 errorCode

| errorCode | 说明 |
|---|---|
| `0` | 成功 |
| `1` | 参数缺失（如 `手机号不能为空`），**HTTP 仍是 200** |
| `8` | 未获取到用户信息（`loginCode` 与 `userId` 不成对，见 [4.12](#412-多项目板块通道)） |
| `12` | 手机号或密码错误 |
| `29` | 验证码已失效 |
| `39` | 该手机号已绑定其他账号 |
| `46` | 验证码错误 |
| `53` | 请勿频繁获取验证码 |
| `170` | 签名错误（蓝牙通道，根因是 `xfData` 大小写，见 [4.15](#415-蓝牙水表通道)） |
| `206` / `209` | 蓝牙上传消费**算成功**的错误码（历史口径不一） |
| `226` | 蓝牙加密校验失败（关阀后必须断开重连再采集） |
| `306` | 设备不在线（对蓝牙表调 `tcpDevice/*` 就是这个） |
| `307` | 设备正在使用中。**按量与套餐两条通道都用它**，且 `success` 可能仍是 `true` |
| `4004` | 设备不存在 |

---

## 9. 注意事项与踩坑记录

### 9.1 R8 混淆与 Gson 泛型

如果使用 Kotlin + R8 full mode + Retrofit suspend 函数，R8 会擦除 suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
- Retrofit 接口返回 `Call<ResponseBody>`（非 suspend）
- 用 `suspendCancellableCoroutine` 桥接回调到协程
- 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段

### 9.2 POST 请求的双重 telephone 字段

POST 请求的认证参数中，`telephone` 和 `telPhone` 两个字段都需要传，值相同。遗漏任一字段可能导致认证失败。

> **GET 请求有同样的问题**，而且更隐蔽——见 [9.8](#98-get-请求也要补-telphone)。

### 9.3 orderNo 的异步获取

调用 `downRate` 后不会立即返回 `orderNo`。需要：
1. 连接 MQTT 等待推送（优先）
2. 或轮询 `queryUsing` 接口（兜底，建议间隔 800ms，最多 10 次）

### 9.4 MQTT 连接失败的处理

MQTT 连接可能因网络原因失败。应实现 HTTP 轮询兜底方案，确保即使 MQTT 不可用也能正常控制设备。

### 9.5 使用码启动设备的检测

用户可能在热水器物理键盘上输入使用码启动设备，此时 App 并不知道。需要在刷新设备列表时，对每个发现的设备调用 `queryUsing` 检查是否有进行中的订单。

### 9.6 挤号检测

趣智校园不支持多设备同时在线。当用户在另一台设备登录时，当前设备的 loginCode 会失效。API 会返回包含"登录"、"token"、"失效"等关键词的错误信息，或返回 HTTP 401/403。

### 9.7 一卡通余额（v3.0.0 已解决，下面的老结论作废）

**老结论**（v3.0.0 之前）：一卡通余额由易校园/小付宝系统管理（`compus.xiaofubao.com`），
该 API 有签名保护（`sign` Header），签名算法在网易易盾加固的 native 层中，无法通过常规方式获取。

**真实情况**：**不用去碰易校园，趣智校园自己代理了这个接口。**

```
GET /settlement/campus/userInfo   →   {"amount":"16.76","signStatus":1,"studentNumber":"...","studentName":"..."}
```

本 App 从 v3.0.0 起直接读它显示真实余额，未签约时回退本地估算。

> 当初之所以判断「做不了」，是**只盯着易校园的域名**，没注意到趣智校园这边有现成代理。
> 教训：先在自己已经能认证的系统里找一遍，再去想怎么破第三方的签名。

**唯一的真限制**：学生**没签约免密支付**（`signStatus == 0`）时服务端不给 `amount`，
这种账号仍然只能本地估算。

详见 [4.9](#49-账号信息与一卡通)。

### 9.8 GET 请求也要补 `telPhone`

9.2 说的是 POST 请求体里 `telephone` 和 `telPhone` 要一起传。
**GET 请求同样有这个要求**——如果用拦截器统一往 URL 上补认证参数，
很容易只补了 `telephone`（因为绝大多数接口只认它），然后在
`/settlement/campus/userInfo` 上吃瘪：

```json
{"success": false, "errorCode": 1, "errorMessage": "手机号不能为空", "data": null}
```

**HTTP 还是 200。** 少了 `telPhone` 就报「手机号不能为空」，而状态码一切正常，
只能靠读 `errorMessage` 发现。两个都补上最省事。

### 9.9 别假设「HTTP 200 = 成功」

趣智校园这边，**接口失败也经常返回 HTTP 200**，错误全在 body 的 `success` / `errorCode` 里。
上面 9.8 那个「手机号不能为空」就是典型：状态码 200、`data` 是 `null`，
如果代码是 `resp.data ?: return` 这种写法，就会**静默降级**成「功能没生效」，
日志里还看不出来。判断成功一律看 `success` 字段。

---

## 附录：Retrofit 接口定义参考

```kotlin
interface QzxyService {

    @FormUrlEncoded
    @POST("/user/login")
    fun login(
        @Field("telephone") telephone: String,
        @Field("password") password: String,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("type") type: Int = 0,
        @Field("version") version: String = "6.5.28"
    ): Call<ResponseBody>

    @GET("/account/wallet")
    fun getWallet(): Call<ResponseBody>

    @GET("/device/info/mac")
    fun getDeviceInfo(@Query("macAddress") mac: String): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/downRate/rateOrder")
    fun downRate(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/downRateResult")
    fun downRateResult(
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder")
    fun closeOrder(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder/result/query")
    fun closeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/consumeOrder/result/query")
    fun consumeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/rateOrder/using")
    fun queryUsing(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/list")
    fun getBillList(
        @Query("month") month: String,
        @Query("billRequestType") billRequestType: Int = 2
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/detail")
    fun getBillDetail(
        @Query("orderId") orderId: String,
        @Query("consumeDate") consumeDate: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/account/useCode/new/status/update")
    fun updateUseCodeStatus(
        @Field("useCodeStatus") status: Int,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/account/useCode/new")
    fun getUseCode(): Call<ResponseBody>

    /** 「换一个」：只返回候选码，不生效。每天 20 次 */
    @FormUrlEncoded
    @POST("/account/useCode/new/generate")
    fun generateUseCode(@FieldMap auth: Map<String, String>): Call<ResponseBody>

    /** 「确定领取」：唯一会让候选码生效的一步 */
    @FormUrlEncoded
    @POST("/account/useCode/new/set")
    fun setUseCode(
        @Field("useCode") useCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    // ── 账号信息与一卡通（v3.0.0） ──

    /** 姓名 / 学号 / 校园卡绑定状态。GET 会自动带上认证参数 */
    @GET("/account/info")
    fun getAccountInfo(): Call<ResponseBody>

    /**
     * 一卡通真实余额。
     *
     * ⚠️ 它要的是 `telPhone`（不是 `telephone`），少传会返回
     * `errorCode=1 手机号不能为空`——而且 **HTTP 仍是 200**。
     * ⚠️ 未签约（signStatus=0）时不给 amount。
     */
    @GET("/settlement/campus/userInfo")
    fun getCampusUserInfo(): Call<ResponseBody>

    /** 项目（学校）信息，projectName 就是学校名 */
    @GET("/project/info/triple")
    fun getProjectInfo(): Call<ResponseBody>

    // ── 手机号与密码（v3.0.0） ──

    /** 更换手机号。code 是发到**新**号（typeId=5）的验证码 */
    @FormUrlEncoded
    @POST("/user/phone/update")
    fun updatePhone(
        @Field("newTelephone") newTelephone: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 修改密码。两个密码都是 MD5 取后 10 位大写 */
    @FormUrlEncoded
    @POST("/user/password/update")
    fun updatePassword(
        @Field("oldPassword") oldPassword: String,
        @Field("password") password: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 重置密码：**不需要旧密码**，只要 typeId=2 发来的验证码 */
    @FormUrlEncoded
    @POST("/user/password/forget")
    fun forgetPassword(
        @Field("password") password: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    // ── 短信验证码（v2.1.0：secret 由手机号推导，任何手机号可用） ──
    // secret = MD5(手机号前3位 + 手机号后4位 + "klcx")，见 utils/SignUtils.kt
    @GET("/user/verification/code/get")
    fun getVerificationCode(
        @Query("telephone") telephone: String,
        @Query("typeId") typeId: Int = 3,
        @Query("platform") platform: Int = 1,
        @Query("secret") secret: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/user/registerAndLogin")
    fun registerAndLogin(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: Int = 5,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = "6.5.28"
    ): Call<ResponseBody>

    // ══════ v3.1.0 新增（多板块 / 套餐计费 / 蓝牙表 / 代扣），详见 4.12~4.16 ══════

    /** 已绑卡账号信息：`/account/info` 的超集，姓名/学号兜底（4.16） */
    @GET("/account/card/getBindCardInfo")
    fun getBindCardInfo(): Call<ResponseBody>

    // ── 多项目（板块）通道（4.12）──
    // loginCode 全局一套，但 projectId 与 accountId 必须成对切换

    /** 账号绑定的项目列表，给 projectId/accountId 配对 */
    @GET("/user/project/list")
    fun getUserProjectList(): Call<ResponseBody>

    /** 当前项目启用的业务模块。⚠️ 必须用该项目自己的 projectId+accountId 调 */
    @GET("/project/module/list")
    fun getProjectModuleList(): Call<ResponseBody>

    // ── 按套餐计费的设备：洗衣机 / 吹风机（4.13）──
    // 与 tcpDevice/downRate/rateOrder（按量）是两条完全不同的路

    /** 套餐列表。洗衣机 smallTypeId=3 / 吹风机 =5 */
    @GET("/device/mode/list")
    fun getModeList(@Query("smallTypeId") smallTypeId: String): Call<ResponseBody>

    /** 按套餐下单：一发出就扣全款，响应恒为 data:null，orderNo 要另找 */
    @FormUrlEncoded
    @POST("/order/tcpDevice/downRate/modeOrder")
    fun modeOrder(
        @Field("xfModel") xfModel: String,        // 套餐的 modeId，不是 id
        @Field("snCode") snCode: String,
        @Field("isHaier") isHaier: String = "0",
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 套餐设备状态。⚠️ 吹风机也调它；占用时 success 仍为 true + errorCode=307 */
    @FormUrlEncoded
    @POST("/order/tcpDevice/washer/status")
    fun washerStatus(
        @Field("snCode") snCode: String,
        @Field("isHaier") isHaier: String = "0",
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 按 MAC 查最近一单。⚠️ 有时间窗口，且乱填 MAC 也返回 null */
    @GET("/order/query/lastOrder/byMac")
    fun lastOrderByMac(
        @Query("macAddress") mac: String,
        @Query("isHaier") isHaier: String = "0"
    ): Call<ResponseBody>

    // ── 残留账单的手动代扣（4.14）──

    /** 未支付账单列表。⚠️ 扁平结构，不套 consumeBillDTO */
    @FormUrlEncoded
    @POST("/order/weixinScorePay/unPay/queryBill")
    fun queryUnpaidBills(@FieldMap auth: Map<String, String>): Call<ResponseBody>

    /** 手动代扣。⚠️ body 是 **JSON**（全项目唯一），账单用 consumeDate 定位 */
    @POST("/order/thirdTrade/single/json/pay")
    fun requestDeduct(@Body body: RequestBody): Call<ResponseBody>

    // ── 蓝牙水表通道（4.15）：手机当网关，服务端只下发费率包 ──

    /** 下发费率。响应 downData 是要写进设备的费率包（HEX），consumeDate 留着失败上报用 */
    @FormUrlEncoded
    @POST("/order/downRate/bluetooth/rateOrder")
    fun bluetoothRateOrder(
        @Field("deviceId") deviceId: String,
        @Field("macAddress") macAddress: String,
        @Field("macType") macType: String,            // %02x%02x（状态帧的 type + a1）
        @Field("bigTypeId") bigTypeId: String,
        @Field("smallTypeId") smallTypeId: String,
        @Field("protocolType") protocolType: String,  // 设备状态帧给的
        @Field("randomNumber") randomNumber: String,  // 设备状态帧给的，不能用本地的
        @Field("xfModel") xfModel: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 上传消费数据（结算）。⚠️ xfData 必须小写，否则 170 签名错误 */
    @FormUrlEncoded
    @POST("/order/upload/bluetooth/data")
    fun bluetoothUploadData(
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("xfData") xfData: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 上报失败订单。⚠️ 必须实现：蓝牙通道没有服务端兜底 */
    @FormUrlEncoded
    @POST("/order/upload/bluetooth/fail")
    fun bluetoothFailOrder(
        @Field("consumeDate") consumeDate: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    companion object {
        const val BASE_URL = "https://v3-api.china-qzxy.cn"
    }
}
```
