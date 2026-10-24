# 在线会话工具 · 使用说明

不用出门、不用抓包，直接在电脑上调接口。**只读，绝不花钱。**

## 一、已经做完了什么

2026-10-24 00:38 用验证码登录成功（`13800138000`），loginCode 存在
`washer-probe/.qzxy_session.json`（已加入 `.gitignore`，不会入库）。

拿到 loginCode 后补掉的免费缺口：

| 缺口 | 状态 |
|---|---|
| 月度账单列表（两次抓包都没有） | ✅ 拿到 32 条（10 月）+ 23 条（9 月） |
| `lastOrder/byMac` 对洗衣机灵不灵 | ✅ 不灵，返回 `null`（没历史单） |
| 账单里设备类型怎么区分 | ✅ **`consumeType` 字段直接给中文类型名** |
| 是否有「枚举设备」接口 | ❌ 试了 14 个候选路径全 404，只能逐台拿 MAC |

## 二、多设备并发（6 台也很快）

**实测：6 台设备一轮 0.7 秒**（串行约 4 秒）。
实现是 `ThreadPoolExecutor`，一轮耗时 = 最慢的那台，不随设备数线性变长。

```powershell
cd washer-probe
python live_session.py poll
```

输出：

```
并发轮询 6 台设备，每 15s 一轮，最多 90 分钟
并发度 6 台/轮。撞到占用态自动存盘。Ctrl+C 停止。

  [01:15:17] 第 1 轮：6 台全空闲 （耗时 0.7s）
  [01:15:22] 第 2 轮：6 台全空闲 （耗时 0.7s）
```

一旦某台被占用：

```
  [01:23:05] 🔴 洗衣机3      占用！💾 洗衣机3_20261024_012305.json
```

同一台设备连续占用时 **60 秒内只存一次**，不会刷出几十个文件。

环境变量：

```powershell
$env:QZXY_POLL_INTERVAL = "15"    # 轮询间隔（秒）
$env:QZXY_POLL_MINUTES  = "180"   # 最长挂多久
```

## 三、加设备：不用记 MAC，扫个码就行

### 最省事的方式

```powershell
python live_session.py add "KLCXKJ-Water,M,AABBCCDDEEFF"
python live_session.py add "AA:BB:CC:DD:EE:FF"
python live_session.py add "AA:BB:CC:DD:EE:FF" --name 3号楼洗衣机1
```

它会自动：

1. 从扫码内容里解析 12 位设备码（兼容 `KLCXKJ-Water,M,XXX`、纯 MAC、带冒号、小写、URL 参数）
2. 调 `/device/info/mac` 反查台账
3. 打印 **bigTypeId / smallTypeId / 设备全名 / 在线状态**
4. 写进 `devices.json`

```
解析结果: sn=866064204595  mac=86:60:64:20:45:95
✅ 洗衣机-洗衣机-3号宿舍楼-6层-洗衣房-4
   bigTypeId=6 (洗衣机)  smallTypeId=3 (洗衣机)
   snCode=866064204595  online=1

已写入 devices.json，当前共 1 台设备
```

`bigTypeId` 不是 6/7 时会警告（那台不是洗衣机/吹风机）。

### 你出门只需要做一件事

**对着每台洗衣机的机身二维码扫一下，记下那串文字**，回来一次性喂给脚本：

```powershell
python live_session.py add "KLCXKJ-Water,M,AABBCCDDEEFF"
python live_session.py add "KLCXKJ-Water,M,AABBCCDDEE01"
python live_session.py add "KLCXKJ-Water,M,AABBCCDDEE02"
```

不用现场抓包、不用等页面加载、不用记 MAC 格式。

### `devices.json` 长这样

```json
{
  "洗衣机1": {"sn": "866064204595", "mac": "86:60:64:20:45:95", "big": 6, "small": 3},
  "洗衣机2": {"sn": "...", "mac": "...", "big": 6, "small": 3}
}
```

也可以手写，`mac` 带不带冒号都认。

### 顺手看看历史设备

```powershell
python live_session.py discover
```

从近 3 个月账单里翻出用过哪些设备（`deviceId` + 名称）。
**但账单不给 MAC**，所以只能帮你回忆有哪些设备，不能直接转成可轮询的。

## 四、全部命令

```powershell
python live_session.py send-code              # 发验证码（打印 secret 算法结果）
python live_session.py submit-code 123456     # 提交验证码，存 loginCode
python live_session.py status                 # 并发查所有设备当前状态
python live_session.py poll                   # 并发轮询，撞到占用态自动存盘
python live_session.py add <扫码内容|MAC>      # 加设备（自动反查类型）
python live_session.py discover               # 从账单翻历史设备
```

密码登录（不需要验证码）：

```powershell
$env:QZXY_PASSWORD = "你的密码"
python live_session.py login --password
```

## 五、安全边界写死在代码里

`live_session.py` 有 `READONLY` 白名单，不在名单里的接口**直接抛异常**：

```python
READONLY = {
    "/order/tcpDevice/washer/status",
    "/order/query/lastOrder/byMac",
    "/account/wallet",
    "/device/mode/list",
    "/device/info/mac",
    "/order/query/account/bill/list",
    ...
}
```

下单接口（`modeOrder` / `rateOrder` / `closeOrder` / `pay`）**在这个文件里连字符串都不存在**，
所以不可能因为手滑或循环写错而扣钱。

> 白名单是故意严格的：第一次跑连 `/account/info` 都被拦下来了。
> 宁可多拦一次，也不要少拦一次。

## 六、轮询在等什么

等一台**正在被使用**的洗衣机或吹风机，落盘到：

```
live_records/洗衣机3_20261024_012305.json
```

里面是那一刻 `washer_status` 的完整响应 + `lastOrder/byMac`。
**「占用中」时 `washer/status` 到底返回什么** —— 这决定新功能的状态判据怎么写。

判据保守：只要 `data` 不是 `null`/空，就当「有情况」记下来，宁可多记不漏。

### 凌晨抓不到是正常的

实测 00:39~01:16 连续 37 分钟、几十轮，两台设备**全空闲**。宿舍楼这个点没人洗衣服。
真要撞上，得等**晚上 8~11 点**。挂在后台跑就行，不用守着。

## 七、注意：loginCode 会过期

过期后返回：

```json
{"success": false, "errorCode": 8, "errorMessage": "登录失效，请重新登录"}
```

**HTTP 状态码仍然是 200** —— 趣智校园老毛病（linyu 文档里记过）。
`raise_for_status()` 抓不到它，所以**判断成功必须看 `success` 字段**。

过期了重跑 `login` 即可。

## 八、还没验证的

`isUse` 的语义。两次抓包里设备都空闲，`isUse` 都是 `1`，
所以它**大概不是「是否在使用中」**，可能是「是否启用/可用」。
占用态样本到手前，代码里不要拿它做状态判据。
