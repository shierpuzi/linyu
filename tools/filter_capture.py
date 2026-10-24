#!/usr/bin/env python3
"""趣智校园抓包过滤器：从混杂的 HttpCanary 导出里，只留下业务流量。

为什么需要它：一次抓包 142 个会话里只有 12 个是趣智校园业务，其余全是广告联盟、
APM 埋点、UDP 探测包。手工挑既慢又容易漏——漏掉一个「洗完了」的请求，
整个功能就没法写。

设计原则（为了换设备时不翻车）：
  1. **业务判定不靠白名单硬编码**，而是「域名带 qzxy/klcxkj」或「MQTT」。
     吹风机的接口路径和洗衣机不同是必然的，但域名不会变。
  2. **不认识的流量绝不静默丢弃**：先扫内容里有没有 loginCode / snCode / orderNo
     这类业务标记，有就升级为业务会话并高亮告警；没有才归为噪音，且照样列出来。
  3. **新接口会被点名**：路径不在已知清单里就打 🆕，那正是新设备要研究的东西。
  4. **扣钱接口会被标红**：任何像「下单」的调用单独成节，附前后余额差。

用法：
    python filter_capture.py                         # 分析默认的 ../洗衣机消费抓包.zip
    python filter_capture.py 吹风机抓包.zip           # 换个抓包文件
    python filter_capture.py 抓包目录 --out filtered  # 输入也可以是已解压目录
    python filter_capture.py --keep-noise            # 连噪音也一起复制（归档用）

产物（默认写到本目录下 filtered/）：
    report.md           人读的报告
    sessions.json       机器读：所有业务会话的分类结果
    fixtures/http.json  喂给 qzxy_washer.py 做离线回放
    raw/<序号>/          只有当 --copy-sessions 时才复制原始会话文件
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import shutil
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))      # linyu/tools
# 抓包 zip 与解压目录都放在仓库外：那是用户数据，不该进版本库
CAPTURE_DIR = os.path.dirname(os.path.dirname(HERE))
DEFAULT_INPUT = os.path.join(CAPTURE_DIR, "洗衣机消费抓包.zip")
DEFAULT_OUT = os.path.join(HERE, "filtered")

# ── 业务判定 ─────────────────────────────────────────────────────────
# 域名里出现这些标记就认定是趣智校园（v3-api.china-qzxy.cn / oss.china-qzxy.cn / 各种 qzxy 子域）
BIZ_HOST_MARKERS = ("qzxy", "klcxkj")
# MQTT 推送（明文 TCP）。吹风机若换 broker，靠下面内容嗅探兜住。
MQTT_HINTS = (b"\x00\x04MQTT",)
# linyu 里硬编码的 broker。抓到别的地址要在报告里告警（别直接套用）
KNOWN_MQTT_HOSTS = {"47.107.37.60"}

# 内容嗅探标记：第三方 SDK 的埋点里也可能出现 "com.klcxkj.zqxy" 这个包名，
# 所以只认**业务字段**，不认包名。
BIZ_CONTENT_MARKERS = (b"loginCode", b"deviceSnCode", b'"snCode"', b"orderNo", b"app_downRate")

# ── 已知接口（QzxyService.kt + 洗衣机抓包新增）────────────────────────
# 不在表里的路径会打 🆕，提醒「这台设备有新的接口要研究」
KNOWN_ENDPOINTS = {
    # 登录与账号
    "/user/login": "密码登录",
    "/user/registerAndLogin": "短信验证码登录",
    "/user/verification/code/get": "发验证码",
    "/user/phone/update": "更换手机号",
    "/user/password/update": "修改密码",
    "/user/password/forget": "重置密码",
    "/account/info": "账号信息",
    "/account/card/getBindCardInfo": "绑定卡片信息",
    "/account/wallet": "钱包余额（验证扣款靠它）",
    "/account/useCode/new": "当前使用码",
    "/account/useCode/new/generate": "换一个使用码",
    "/account/useCode/new/set": "确定领取使用码",
    "/account/useCode/new/status/update": "使用码开关",
    "/settlement/campus/userInfo": "一卡通真实余额",
    "/settlement/withhold/sign/status": "免密支付签约状态",
    "/project/info/triple": "学校信息",
    # 设备
    "/device/info/mac": "按 MAC 查设备台账（bigTypeId/smallTypeId 在这里）",
    "/device/mode/list": "套餐列表（洗衣机；吹风机可能也有）",
    "/device/smallType/id": "小类信息（withholdMoney / isAllowClose）",
    # 通用下单/结算
    "/order/tcpDevice/downRate/rateOrder": "热水器开阀",
    "/order/tcpDevice/downRate/modeOrder": "★按套餐下单（洗衣机扣全款）",
    "/order/tcpDevice/query/downRateResult": "开阀结果确认",
    "/order/tcpDevice/closeOrder": "关阀",
    "/order/tcpDevice/closeOrder/result/query": "关阀结果",
    "/order/tcpDevice/query/rateOrder/using": "查询进行中订单",
    "/order/consumeOrder/result/query": "消费结算结果",
    "/order/query/account/bill/list": "账单列表",
    "/order/query/account/bill/detail": "★账单详情（单笔金额/套餐/设备都在这里）",
    # 吹风机抓包（2026-10-24）新增
    "/order/query/lastOrder/byMac": "★按 MAC 查最近一单（含 orderNo/xfModel/modeName）",
    "/billDetail": "H5 账单页（WebView，不给 JSON，别指望它）",
    "/favicon.ico": "WebView 图标，忽略",
    "/order/weixinScorePay/unPay/queryBill": "未支付账单",
    "/order/thirdTrade/single/json/pay": "手动代扣（动钱）",
    # 洗衣机专属
    "/order/tcpDevice/washer/status": "洗衣机状态",
    # 蓝牙水表
    "/order/downRate/bluetooth/rateOrder": "蓝牙表下发费率",
    "/order/upload/bluetooth/data": "蓝牙表上传消费",
    "/order/upload/bluetooth/fail": "蓝牙表上报失败",
    # 券与广告
    "/coupon/user/listByBigType": "可用券查询",
    "/coupon/order/givenByBigType/info": "订单赠送券",
    "/project/adv/title/get": "趣智自己的广告位（功能用不到）",
}

# 路径里的关键词 → 语义标签。新设备的新接口也能被自动归类。
# ⚠️ 关键词要**往宽了写**：吹风机可能是 /start、/begin、/open、/use，
# 猜窄了就会把「扣钱接口」漏成「未知用途」，而这一节是报告里最要紧的部分。
PATH_RULES = [
    ("cancel", "🛑 取消"), ("refund", "🛑 退款"), ("close", "🛑 停止/关闭"),
    ("stop", "🛑 停止/关闭"),
    ("downRate", "★下单/开阀"), ("rateOrder", "★下单/开阀"), ("modeOrder", "★下单/开阀"),
    ("start", "★下单/开阀"), ("begin", "★下单/开阀"), ("open", "★下单/开阀"),
    ("create", "★下单/开阀"), ("/use", "★下单/开阀"), ("pay", "★支付"),
    ("lastOrder", "★最近订单"), ("detail", "★账单详情"),
    ("status", "🔍 状态查询"), ("using", "🔍 状态查询"), ("progress", "🔍 状态查询"),
    ("consume", "💰 结算"), ("bill", "💰 账单"),
    ("mode/list", "📋 套餐"), ("info", "📋 信息"),
]

# ── 噪音归类（只做说明，不影响过滤结果）──────────────────────────────
NOISE_HINTS = [
    (("pangolin", "pangle", "toutiao"), "穿山甲广告 SDK"),
    (("volces.com", "byteimg", "bytedance", "snssdk"), "字节系 APM / 监控"),
    (("umeng", "getui", "igexin", "jpush"), "友盟 / 个推推送"),
    (("gdt.qq.com", "e.qq.com", "qq.com/gdt"), "腾讯广点通"),
    (("kuaishou", "gifshow"), "快手广告"),
    (("unityads", "applovin", "ironsource", "mintegral", "vungle",
      "chartboost", "adcolony", "sigmob", "pangle"), "海外/聚合广告 SDK"),
    (("baidu", "amap", "gaode"), "百度 / 高德定位"),
    (("sensorsdata", "sensors", "growingio", "appsflyer", "adjust.com"), "数据埋点"),
    (("firebase", "crashlytics", "bugly", "bugsnag", "sentry"), "崩溃监控"),
]


# ── 小工具 ───────────────────────────────────────────────────────────

def clean_path(url: str) -> str:
    """https://host/a/b?x -> /a/b"""
    no_query = url.split("?", 1)[0]
    if "://" not in no_query:
        return no_query
    rest = no_query.split("://", 1)[1]
    slash = rest.find("/")
    return rest[slash:] if slash >= 0 else "/"


def host_of(url: str) -> str:
    if "://" not in url:
        return ""
    return url.split("://", 1)[1].split("/", 1)[0].split(":", 1)[0]


def load_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def read_bytes(p):
    return open(p, "rb").read() if os.path.exists(p) else b""


def sniff(payload: bytes) -> str:
    """在（可能 gzip 过的）载荷里找业务字段。返回命中的标记，空串表示没有。"""
    chunks = [payload]
    i = payload.find(b"\x1f\x8b\x08")
    while i >= 0:
        try:
            chunks.append(gzip.decompress(payload[i:]))
        except Exception:
            pass
        i = payload.find(b"\x1f\x8b\x08", i + 1)
    for chunk in chunks:
        for m in BIZ_CONTENT_MARKERS:
            if m in chunk:
                return m.decode()
    return ""


def noise_reason(host: str, ip: str, payload: bytes) -> str:
    text = f"{host} {ip}".lower()
    for keys, label in NOISE_HINTS:
        if any(k in text for k in keys):
            return label
    # 同一个 32 字节块反复出现 = SDK 在自己刷心跳
    if len(payload) >= 64 and payload[:32] == payload[32:64]:
        return "重复常量载荷"
    return "未知第三方"


def semantic(path: str) -> str:
    if path in KNOWN_ENDPOINTS:
        return KNOWN_ENDPOINTS[path]
    for key, label in PATH_RULES:
        if key in path:
            return label
    return "❓ 未知用途"


# ── 输入 ─────────────────────────────────────────────────────────────

def ensure_capture_dir(inp: str) -> str:
    """输入可以是 zip，也可以是已解压目录。统一返回一个会话目录。

    ⚠️ 解压目录按**输入文件名**区分，绝不能共用一个固定目录：
    否则拿到「吹风机抓包.zip」时会静默复用上次解压的洗衣机数据，
    报告看起来一切正常，实际上分析的是旧包——这比报错难查一百倍。
    """
    if os.path.isdir(inp):
        return inp
    if not os.path.isfile(inp):
        sys.exit(f"找不到输入: {inp}\n用法: python filter_capture.py [抓包.zip|抓包目录]")

    stem = os.path.splitext(os.path.basename(inp))[0]
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in stem)
    out = os.path.join(CAPTURE_DIR, f"_capture_{safe}")

    # 只复用「同一个输入文件」解压出来的目录：比对 zip 大小 + 修改时间
    stamp = os.path.join(out, ".source")
    src_id = f"{os.path.getsize(inp)}:{int(os.path.getmtime(inp))}"
    if os.path.isdir(out) and os.path.exists(stamp):
        if open(stamp, encoding="utf-8").read().strip() == src_id:
            print(f"复用已解压的 {out}")
            return out
    if os.path.isdir(out):
        shutil.rmtree(out)
    print(f"解压 {os.path.basename(inp)} -> {out}")
    os.makedirs(out, exist_ok=True)
    with zipfile.ZipFile(inp) as z:
        z.extractall(out)
    with open(stamp, "w", encoding="utf-8") as f:
        f.write(src_id)
    return out


def session_meta(d: str):
    for f in ("request.json", "tcp.json", "udp.json"):
        p = os.path.join(d, f)
        if os.path.exists(p):
            return load_json(p)
    return None


def session_payloads(d: str) -> bytes:
    parts = []
    for f in sorted(os.listdir(d)):
        if f.endswith(".bin"):
            parts.append(read_bytes(os.path.join(d, f)))
    return b"".join(parts)


# ── 分类 ─────────────────────────────────────────────────────────────

def classify(d: str, meta: dict):
    """返回 (kind, reason)。kind ∈ biz | biz-mqtt | biz-sniffed | noise"""
    url = meta.get("url") or ""
    host = host_of(url)
    ip = meta.get("remoteIp") or ""
    port = meta.get("remotePort")
    payload = session_payloads(d)

    if any(h in host.lower() for h in BIZ_HOST_MARKERS):
        return "biz", f"域名含 qzxy/klcxkj"
    if any(h in payload for h in MQTT_HINTS):
        return "biz-mqtt", "MQTT CONNECT 帧"
    hit = sniff(payload)
    if hit:
        return "biz-sniffed", f"内容含业务字段 `{hit}`"
    return "noise", noise_reason(host, ip, payload)


# ── MQTT 解码 ────────────────────────────────────────────────────────

def mqtt_str(buf, i):
    if i + 1 >= len(buf):
        raise IndexError
    n = (buf[i] << 8) | buf[i + 1]
    i += 2
    return buf[i:i + n].decode("utf-8", "replace"), i + n


def mqtt_frame_len(buf, i=0):
    """从 buf[i] 起读一个固定头 + 变长剩余长度，返回 (type, body_start, body_len, total)。

    ⚠️ MQTT 的 Remaining Length 是 1~4 字节 varint（低 7 位有效，最高位是续位标记）。
    按固定 2 字节切，超过 127 字节的 PUBLISH 就会把长度字节吞进 topic。
    """
    if i >= len(buf):
        return None
    typ = buf[i] >> 4
    j = i + 1
    mult = 1
    length = 0
    while j < len(buf):
        c = buf[j]
        j += 1
        length += (c & 0x7F) * mult
        mult *= 128
        if not (c & 0x80):
            break
        if mult > 128 ** 3:
            return None
    total = (j - i) + length
    return typ, j, length, total


def decode_mqtt(bins):
    """解 CONNECT / SUBSCRIBE / SUBACK / PUBLISH，**逐个 bin 内逐帧遍历**。

    ⚠️ 第二版才修对的 bug：抓包工具会把**多个 MQTT 帧装进同一个 .bin**
    （httpcanary 按 TCP 段存，不是按 MQTT 帧存）。吹风机这次就是这个情况——
    `4.bin` 是 `SUBACK(5字节) + PUBLISH(582字节)` 粘在一起，
    只读第一个帧头等于整条下单推送全部丢掉，报告里表现成「订阅了但没收到推送」，
    看起来像是服务端没推，其实是解析器吞了。别改回「一个 bin 一个帧」。
    """
    out = {"clientId": None, "username": None, "password": None,
           "subscriptions": [], "publishes": []}
    for buf in bins:
        off = 0
        while off < len(buf) - 1:
            fr = mqtt_frame_len(buf, off)
            if not fr:
                break
            typ, bstart, blen, total = fr
            if total <= 0 or bstart + blen > len(buf):
                break                       # 尾巴被截断，放弃剩下的
            body = buf[bstart:bstart + blen]
            try:
                if typ == 1:  # CONNECT
                    _, i = mqtt_str(body, 0)
                    i += 4                  # level+flags, keepalive
                    out["clientId"], i = mqtt_str(body, i)
                    if len(body) > i:
                        out["username"], i = mqtt_str(body, i)
                    if len(body) > i:
                        out["password"], i = mqtt_str(body, i)
                elif typ == 8:  # SUBSCRIBE
                    i = 2
                    while i < len(body):
                        topic, i = mqtt_str(body, i)
                        out["subscriptions"].append(topic)
                        i += 1
                elif typ == 3:  # PUBLISH
                    topic, i = mqtt_str(body, 0)
                    raw = body[i:]
                    try:
                        parsed = json.loads(raw)
                    except Exception:
                        parsed = None
                    out["publishes"].append({"topic": topic, "parsed": parsed,
                                             "raw": raw.decode("utf-8", "replace")})
            except (IndexError, UnicodeDecodeError):
                pass
            off += total
    return out


# ── 主流程 ───────────────────────────────────────────────────────────

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    inp = args[0] if args else DEFAULT_INPUT
    outdir = DEFAULT_OUT
    for a in sys.argv[1:]:
        if a.startswith("--out="):
            outdir = a.split("=", 1)[1]
    copy_sessions = "--copy-sessions" in flags

    cap = ensure_capture_dir(inp)
    names = sorted([n for n in os.listdir(cap) if os.path.isdir(os.path.join(cap, n))],
                   key=lambda x: int(x) if x.isdigit() else 0)

    biz, mqtt_sessions, noise, sniffed = [], [], [], []
    devices, wallets = {}, []

    for name in names:
        d = os.path.join(cap, name)
        meta = session_meta(d)
        if not meta:
            continue
        kind, reason = classify(d, meta)
        seq = int(name) if name.isdigit() else name

        if kind == "noise":
            payload = session_payloads(d)
            noise.append({"seq": seq, "proto": meta.get("protocol"),
                          "ip": meta.get("remoteIp"), "port": meta.get("remotePort"),
                          "host": host_of(meta.get("url") or ""),
                          "reason": reason,
                          "payload_md5": hashlib.md5(payload).hexdigest()[:12] if payload else ""})
            continue

        if kind == "biz-sniffed":
            sniffed.append({"seq": seq, "ip": meta.get("remoteIp"),
                            "port": meta.get("remotePort"), "reason": reason})

        if kind == "biz-mqtt":
            bins = [read_bytes(os.path.join(d, f)) for f in sorted(os.listdir(d))
                    if f.endswith(".bin")]
            dec = decode_mqtt(bins)
            dec.update(seq=seq, time=meta.get("time"),
                       remote=f"{meta.get('remoteIp')}:{meta.get('remotePort')}")
            mqtt_sessions.append(dec)
            continue

        # ⚠️ 靠嗅探救回来的会话可能根本没有 url（自定义 TCP/UDP 通道），
        # 这里必须容忍，否则「不认识但含业务字段」的会话会让整个脚本崩掉，
        # 而那恰恰是最该被人看见的东西。
        url = meta.get("url") or ""
        path = clean_path(url) if url else f"(非HTTP) {meta.get('protocol')} {meta.get('remoteIp')}:{meta.get('remotePort')}"
        resp_raw = read_bytes(os.path.join(d, "response_body.json"))
        resp = resp_raw.decode("utf-8", "replace").strip()
        try:
            rj = json.loads(resp) if resp else {}
        except Exception:
            rj = {}

        item = {
            "seq": seq, "time": meta.get("time"), "method": meta.get("method") or meta.get("protocol"),
            "url": url, "path": path, "host": host_of(url),
            "query": url.split("?", 1)[1] if "?" in url else "",
            "req_headers": meta.get("headers", {}),
            "req_body": read_bytes(os.path.join(d, "request_body.bin")).decode("utf-8", "replace").strip(),
            "status": (load_json(os.path.join(d, "response.json")).get("code")
                       if os.path.exists(os.path.join(d, "response.json")) else None),
            "resp_body": resp,
            "known": path in KNOWN_ENDPOINTS,
            "semantic": semantic(path),
            "reason": reason,
        }
        biz.append(item)

        # 从设备台账里收集设备画像——这是判断「吹风机长什么样」最直接的地方
        if path == "/device/info/mac" and isinstance(rj.get("data"), dict):
            dv = rj["data"]
            devices[dv.get("deviceId") or dv.get("DevID")] = {
                "deviceId": dv.get("deviceId") or dv.get("DevID"),
                "name": dv.get("deviceName"), "bigTypeId": dv.get("bigTypeId"),
                "bigTypeName": dv.get("bigTypeName"), "smallTypeId": dv.get("smallTypeId"),
                "smallTypeName": dv.get("smallTypeName"), "snCode": dv.get("snCode"),
                "macAddress": dv.get("macAddress"), "isUse": dv.get("isUse"),
                "onlineStatusId": dv.get("onlineStatusId"),
                "withholdMoney": dv.get("withholdMoney"),
            }
        if path == "/account/wallet" and isinstance(rj.get("data"), dict):
            wallets.append({"seq": seq, "time": meta.get("time"),
                            "money": rj["data"].get("money"),
                            "raw_milli": rj["data"].get("accountRealMoney")})

    # ── 输出 ──
    os.makedirs(outdir, exist_ok=True)
    os.makedirs(os.path.join(outdir, "fixtures"), exist_ok=True)

    # ⚠️ 不能按会话序号排序：HttpCanary 的序号是导出顺序，不是时间顺序。
    # 实测序号 47 的时间（10:24:57）比序号 84（10:24:46）还晚，
    # 按序号排会让余额轨迹首尾颠倒，把「扣了 4 元」算成「+4 元」。
    tsort = lambda x: (x.get("time") or "", x.get("seq") or 0)
    biz.sort(key=tsort)
    wallets.sort(key=tsort)
    mqtt_sessions.sort(key=tsort)
    devices_list = list(devices.values())
    with open(os.path.join(outdir, "sessions.json"), "w", encoding="utf-8") as f:
        json.dump({"http": biz, "mqtt": mqtt_sessions, "noise": noise,
                   "sniffed": sniffed, "devices": devices_list,
                   "wallets": wallets}, f, ensure_ascii=False, indent=2)

    with open(os.path.join(outdir, "fixtures", "http.json"), "w", encoding="utf-8") as f:
        json.dump({"captured_at": f"{biz[0]['time']} ~ {biz[-1]['time']}" if biz else "",
                   "http": biz, "mqtt": mqtt_sessions}, f, ensure_ascii=False, indent=2)

    if copy_sessions:
        raw = os.path.join(outdir, "raw")
        os.makedirs(raw, exist_ok=True)
        keep = {str(i["seq"]) for i in biz} | {str(m["seq"]) for m in mqtt_sessions}
        for n in keep:
            src, dst = os.path.join(cap, n), os.path.join(raw, n)
            if os.path.isdir(src):
                shutil.copytree(src, dst, dirs_exist_ok=True)

    report = build_report(biz, mqtt_sessions, noise, sniffed, devices, wallets, cap)
    with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f:
        f.write(report)
    print(report)
    print(f"\n产物已写入 {outdir}")
    return 0


def build_report(biz, mqtts, noise, sniffed, devices, wallets, cap) -> str:
    L = []
    add = L.append
    total = len(biz) + len(mqtts) + len(noise)
    add("# 抓包过滤报告\n")
    add(f"- 会话总数：{total}")
    add(f"- 业务 HTTP：**{len(biz)}**　业务 MQTT：**{len(mqtts)}**　噪音：{len(noise)}")
    if biz:
        add(f"- 时间跨度：{biz[0]['time']} ~ {biz[-1]['time']}")
    add("")

    # 告警优先，放最前面
    new_eps = [b for b in biz if not b["known"]]
    if sniffed:
        add("## ⚠️ 靠内容嗅探救回来的会话（域名/IP 不认识，但载荷含业务字段）\n")
        for s in sniffed:
            add(f"- 序号 {s['seq']} `{s['ip']}:{s['port']}` — {s['reason']}")
        add("\n> 这些是新的服务端或新的通道，**必须人工确认**后再改代码。\n")

    # MQTT 两个最容易误判的点
    add("## 📡 MQTT 健康检查\n")
    if not mqtts:
        add("❌ **本次没抓到 MQTT。**下单响应是 `data:null`，订单号全靠 MQTT 推送，"
            "没抓到这个就等于没抓到下单结果。下次抓包务必放行 1883 端口。\n")
    else:
        hosts = {m["remote"] for m in mqtts}
        if len(hosts) > 1 or any(h.split(":")[0] not in KNOWN_MQTT_HOSTS for h in hosts):
            add(f"⚠️ **MQTT 服务器是 `{sorted(hosts)}`**，和 linyu 里硬编码的 "
                f"`tcp://47.107.37.60:1883` 不一致。")
            add(f"> 别直接套用现有 `MqttManager` 的地址——连错服务器会一条推送都收不到。"
                f"先确认是不是按学校/项目分的 broker。\n")
        for m in mqtts:
            pubs = m["publishes"]
            if not pubs:
                add(f"❌ 序号 {m['seq']} 连上了 `{m['remote']}`，订阅了 {m['subscriptions']}，"
                    f"**但一条 PUBLISH 都没收到。**")
                add("> 两种可能：①真的没推；②解码器把多帧粘连的 TCP 段吞了。"
                    "先确认 `decode_mqtt` 是逐帧遍历的（别退化成「一个 bin 一个帧」）。\n")
            else:
                add(f"✅ 序号 {m['seq']} `{m['remote']}` 收到 {len(pubs)} 条推送")
                for p in pubs:
                    if p["parsed"]:
                        keys = ("orderNo", "xfModelName", "preDeductMoney", "deviceSnCode",
                                "state", "result")
                        got = {k: p["parsed"].get(k) for k in keys if k in p["parsed"]}
                        if got:
                            add(f"  - `{p['topic']}` → {json.dumps(got, ensure_ascii=False)}")
        add("")

    if new_eps:
        seen = {}
        for b in new_eps:
            seen.setdefault(b["path"], []).append(b["seq"])
        add("## 🆕 本次新出现的接口（不在已知清单里）\n")
        add("| 路径 | 主机 | 疑似用途 | 出现序号 |")
        add("|---|---|---|---|")
        for p, seqs in seen.items():
            b = next(x for x in new_eps if x["path"] == p)
            add(f"| `{p}` | {b['host']} | {b['semantic']} | {seqs} |")
        add("\n> 新接口就是新设备功能所在的位置，**优先看这几个**。\n")

    # 扣钱风险：只有 POST 才可能改状态。GET 查询类（lastOrder/billDetail）不算，
    # 否则「花钱」这一节会被查询接口淹掉，真正扣钱的那条反而不显眼。
    money_calls = [b for b in biz
                   if "★" in b["semantic"] and str(b.get("method", "")).upper() == "POST"]
    add("## 💸 会被判定为「花钱」的调用\n")
    add("> 只列 POST。GET 查询类接口（`lastOrder/byMac`、`bill/detail`）"
        "虽然也是 ★ 标记，但只读不改状态，单独放在下面「只读但有用」。\n")
    if money_calls:
        add("| 时间 | 序号 | 方法 | 路径 | 请求体 | 响应 |")
        add("|---|---|---|---|---|---|")
        for b in money_calls:
            body = b["req_body"] or "—"
            resp = (b["resp_body"] or "—").replace("\n", " ")
            add(f"| {b['time']} | {b['seq']} | {b['method']} | `{b['path']}` "
                f"| `{body[:110]}` | `{resp[:110]}` |")
    else:
        add("（无）")
    add("")
    if wallets:
        add("### 余额轨迹（验证「到底扣了多少」的唯一硬证据）\n")
        add("| 时间 | 序号 | money | 原始值（厘/分） |")
        add("|---|---|---|---|")
        for w in wallets:
            add(f"| {w['time']} | {w['seq']} | {w['money']} | {w['raw_milli']} |")
        if len(wallets) >= 2:
            try:
                # 按抓包序号排序（= 时间顺序），首尾相减才是真实差额
                first, last = wallets[0], wallets[-1]
                d = float(last["money"]) - float(first["money"])
                add(f"\n**余额变化：{first['money']} → {last['money']}　差额 {d:+.3f} 元**")
                if d < 0:
                    add(f"> 一共花了 {-d:.3f} 元。把它拆到每次花钱的调用上，"
                        f"就能确认「点一下扣多少」——这是其它任何数据都替代不了的。")
                else:
                    add("> 余额没减少：本次抓包可能全程没真正下单，或扣的是赠送余额。")
            except (TypeError, ValueError, KeyError):
                pass
        add("")

    # 只读但信息量大的 GET：这些是「不用花钱就能验证功能」的入口
    readonly = [b for b in biz
                if "★" in b["semantic"] and str(b.get("method", "")).upper() == "GET"]
    if readonly:
        add("### 只读但有用（不用花钱就能拿到订单信息）\n")
        add("| 时间 | 序号 | 路径 | 关键响应 |")
        add("|---|---|---|---|")
        for b in readonly:
            add(f"| {b['time']} | {b['seq']} | `{b['path']}` | `{(b['resp_body'] or '')[:150]}` |")
        add("\n> **这几个是开发期的免费金矿**：不用真的消费就能拿到真实 orderNo、"
            "套餐名、金额，足以把解析和 UI 全部验证完。\n")

    # 设备画像
    if devices:
        add("## 🔎 设备画像（从 /device/info/mac 提取）\n")
        add("| deviceId | 名称 | bigTypeId | bigTypeName | smallTypeId | smallTypeName | isUse | 在线 |")
        add("|---|---|---|---|---|---|---|---|")
        for dv in devices.values():
            add(f"| {dv.get('deviceId')} | {dv.get('name')} | {dv.get('bigTypeId')} "
                f"| {dv.get('bigTypeName')} | {dv.get('smallTypeId')} "
                f"| {dv.get('smallTypeName')} | {dv.get('isUse')} | {dv.get('onlineStatusId')} |")
        add("\n> 新设备类型就看这张表：`bigTypeId` / `smallTypeId` 是写判定逻辑的唯一依据。\n")

    # 时序
    add("## ⏱ 业务请求时序\n")
    add("| 时间 | 序号 | 方法 | 路径 | 用途 | 响应摘要 |")
    add("|---|---|---|---|---|---|")
    for b in biz:
        resp = (b["resp_body"] or "").replace("\n", " ")
        add(f"| {b['time']} | {b['seq']} | {b['method']} | `{b['path']}` "
            f"| {b['semantic']} | `{resp[:120]}` |")
    add("")

    # MQTT
    add("## 📡 MQTT 推送\n")
    if not mqtts:
        add("（本次没抓到 MQTT）\n")
    for m in mqtts:
        add(f"### 序号 {m['seq']} · {m['remote']} · {m['time']}\n")
        add(f"- clientId=`{m['clientId']}` user=`{m['username']}` pass=`{m['password']}`")
        add(f"- 订阅：{m['subscriptions']}")
        for p in m["publishes"]:
            add(f"- 收到 `{p['topic']}`：")
            if p["parsed"]:
                add("```json")
                add(json.dumps(p["parsed"], ensure_ascii=False, indent=2))
                add("```")
            else:
                add(f"  `{p['raw'][:300]}`")
        add("")

    # 噪音
    add(f"## 🗑 已过滤的噪音（{len(noise)} 个会话）\n")
    groups = {}
    for n in noise:
        key = (n["proto"], n["host"] or n["ip"], n["reason"])
        g = groups.setdefault(key, {"ports": set(), "count": 0, "md5": set()})
        g["ports"].add(n["port"])
        g["count"] += 1
        g["md5"].add(n["payload_md5"])
    add("| 协议 | 目标 | 原因 | 次数 | 不同载荷数 |")
    add("|---|---|---|---|---|")
    for (proto, target, reason), g in sorted(groups.items(), key=lambda x: -x[1]["count"]):
        ports = sorted(x for x in g["ports"] if x is not None)
        ptxt = f":{ports[0]}" if len(ports) == 1 else (f":{ports[0]}~{ports[-1]}" if ports else "")
        md5n = len([x for x in g["md5"] if x])
        add(f"| {proto} | `{target}{ptxt}` | {reason} | {g['count']} | {md5n} |")
    add("\n> 「不同载荷数 = 1」说明是同一个 SDK 在刷同一个包，纯噪音，删了不影响任何结论。\n")

    return "\n".join(L)


if __name__ == "__main__":
    sys.exit(main())
