#!/usr/bin/env python3
"""趣智校园登录 + 设备状态轮询（只读，绝不碰下单接口）。

用途：不用出门、不用抓包，直接在电脑上轮询「某台设备现在被占用了吗」，
撞上占用态就落盘那一刻的响应——我们要的就是这个样本。

安全边界（硬性）：
  · 只调用 GET / 只读 POST（washer/status、lastOrder/byMac、wallet、mode/list）
  · 绝不调用 modeOrder / rateOrder / closeOrder / pay —— 本文件里没有这些字符串
  · loginCode 存到 .qzxy_session.json（已加进 .gitignore），不打印全文

用法：
  python live_session.py login              发验证码 → 交互输入 → 存 loginCode
  python live_session.py login --password   用密码登录（无需验证码）
  python live_session.py poll               轮询设备，撞到占用态就记录
  python live_session.py status             查一次所有设备的当前状态
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding="utf-8")

try:
    import requests
except ImportError:
    sys.exit("需要 requests: python -m pip install requests")

BASE = "https://v3-api.china-qzxy.cn"
HERE = os.path.dirname(os.path.abspath(__file__))
SESSION = os.path.join(HERE, ".qzxy_session.json")
RECORDS = os.path.join(HERE, "live_records")

PHONE = os.environ.get("QZXY_PHONE", "13800138000")
VERSION = "6.5.29"
CONFIG_KEYS = ("module_list,advertise_type,question_list,service_phone_list,"
               "banner_list_app,activity_list_app,aliCard_popup_config")

# ── 设备清单 ─────────────────────────────────────────────────────────
# 从 devices.json 读，方便你一次填 6 台而不用改代码。
# 格式：{"名称": {"sn": "12位snCode", "mac": "AA:BB:CC:DD:EE:FF", "big": 6, "small": 3}}
DEVICES_FILE = os.path.join(HERE, "devices.json")

DEFAULT_DEVICES = {
    "洗衣机": {"sn": "866064204595", "mac": "86:60:64:20:45:95", "big": 6, "small": 3},
    "吹风机": {"sn": "C47F0E2351BE", "mac": "C4:7F:0E:23:51:BE", "big": 7, "small": 5},
}


def load_devices():
    if os.path.isfile(DEVICES_FILE):
        with open(DEVICES_FILE, encoding="utf-8") as f:
            raw = json.load(f)
        out = {}
        for name, d in raw.items():
            mac = d.get("mac") or d.get("sn", "")
            out[name] = {
                "sn": d.get("sn") or mac.replace(":", "").upper(),
                "mac": mac if ":" in mac else ":".join(
                    mac[i:i + 2] for i in range(0, len(mac.replace(":", "")), 2)),
                "big": d.get("big"),
                "small": d.get("small"),
            }
        return out
    return DEFAULT_DEVICES

# 只读接口白名单：本脚本只准打这些
READONLY = {
    "/order/tcpDevice/washer/status",
    "/order/query/lastOrder/byMac",
    "/account/wallet",
    "/device/mode/list",
    "/device/info/mac",
    "/order/query/account/bill/list",
    "/order/query/account/bill/detail",
    "/account/info",
    "/account/card/getBindCardInfo",
    "/project/info/triple",
    "/settlement/campus/userInfo",
    # 多项目/板块探查（全是只读 GET）
    "/user/project/list",
    "/user/info",
    "/project/module/list",
    "/project/config",
    "/project/info/triple",
}


def sms_secret(phone: str) -> str:
    """secret = MD5(前3位 + 后4位 + "klcx")，见 linyu/utils/SignUtils.kt"""
    raw = phone[:3] + phone[-4:] + "klcx"
    return hashlib.md5(raw.encode()).hexdigest()


def headers(project_id="3356"):
    return {
        "User-Agent": "okhttp/4.2.2",
        "Config-Project": project_id,
        "Config-Keys": CONFIG_KEYS,
    }


def auth_params(sess: dict) -> dict:
    ua = sess.get("userAccount") or {}
    return {
        "loginCode": sess["loginCode"],
        "userId": str(sess["userId"]),
        "accountId": str(ua.get("accountId", "")),
        "projectId": str(ua.get("projectId", "3356")),
        "telephone": sess["telephone"],
        "telPhone": sess["telephone"],
        "phoneSystem": "android",
        "version": VERSION,
    }


def guard(path: str):
    if path not in READONLY:
        raise RuntimeError(
            f"⛔ {path} 不在只读白名单里。本脚本只做查询，绝不改状态、绝不花钱。")


def api_get(sess, path, **extra):
    guard(path)
    q = auth_params(sess); q.update(extra)
    r = requests.get(BASE + path, params=q, headers=headers(), timeout=20)
    r.raise_for_status()
    return r.json()


def api_post(sess, path, **extra):
    guard(path)
    q = auth_params(sess); q.update(extra)
    r = requests.post(BASE + path, data=q, headers=headers(), timeout=20)
    r.raise_for_status()
    return r.json()


# ── 登录 ─────────────────────────────────────────────────────────────

def send_code(phone=PHONE):
    sec = sms_secret(phone)
    print(f"secret = MD5({phone[:3]} + {phone[-4:]} + klcx) = {sec}")
    r = requests.get(BASE + "/user/verification/code/get",
                     params={"telephone": phone, "secret": sec,
                             "typeId": 3, "platform": 1},
                     headers=headers(), timeout=20)
    r.raise_for_status()
    return r.json()


def sms_login(phone, code):
    r = requests.post(BASE + "/user/registerAndLogin",
                      data={"telephone": phone, "smsCode": code, "type": 5,
                            "phoneSystem": "android", "version": VERSION},
                      headers=headers(), timeout=20)
    r.raise_for_status()
    return r.json()


def save_session(resp: dict):
    d = resp["data"]
    ua = d.get("userAccount") or {}
    sess = {
        "loginCode": d["loginCode"],
        "userId": d["userId"],
        "telephone": d.get("telephone") or PHONE,
        "userAccount": {
            "accountId": ua.get("accountId"),
            "projectId": ua.get("projectId"),
            "name": ua.get("name"),
        },
        "saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    with open(SESSION, "w", encoding="utf-8") as f:
        json.dump(sess, f, ensure_ascii=False, indent=1)
    try:
        os.chmod(SESSION, 0o600)
    except OSError:
        pass
    return sess


def load_session():
    if not os.path.isfile(SESSION):
        sys.exit("没有登录态，先跑：python live_session.py login")
    with open(SESSION, encoding="utf-8") as f:
        return json.load(f)


def mask(s: str) -> str:
    return s[:6] + "…" + s[-4:] if len(s) > 12 else s


# ── 查询 ─────────────────────────────────────────────────────────────

def probe_one(sess, name, dev):
    """查一台设备，永不抛异常。返回 (name, dev, st, lo, err)。"""
    try:
        st = api_post(sess, "/order/tcpDevice/washer/status",
                      snCode=dev["sn"], isHaier="0")
        lo = api_get(sess, "/order/query/lastOrder/byMac",
                     macAddress=dev["mac"].replace(":", ""), isHaier="0")
        return name, dev, st, lo, None
    except Exception as e:
        return name, dev, None, None, f"{type(e).__name__}: {e}"


def is_busy(st_resp) -> bool:
    """判断设备是否占用中。

    ⚠️ 判据保守：空闲时抓到的是 data=null。所以只要 data 非空，
    就当作「有情况」记录下来——宁可多记，不要漏掉唯一一次占用样本。
    """
    if not st_resp:
        return False
    d = st_resp.get("data")
    return d is not None and d != {} and d != []


def run_status(sess):
    devices = load_devices()
    print(f"\naccountId={sess['userAccount'].get('accountId')}  "
          f"共 {len(devices)} 台设备，并发查询中…")
    with ThreadPoolExecutor(max_workers=min(12, len(devices))) as ex:
        futs = [ex.submit(probe_one, sess, n, d) for n, d in devices.items()]
        results = [f.result() for f in futs]
    for name, dev, st, lo, err in results:
        print(f"\n── {name} (sn={dev['sn']}) ──")
        if err:
            print(f"  ❌ {err}")
            continue
        busy = is_busy(st)
        print(f"  washer/status: {'🔴占用/有数据' if busy else '⚪空闲(data=null)'}")
        print(f"    {json.dumps(st, ensure_ascii=False)[:400]}")
        ld = (lo or {}).get("data")
        if ld:
            print(f"  最近一单: orderNo={ld.get('orderNo')} "
                  f"{ld.get('modeName')} {ld.get('modeMoney')}厘 @{ld.get('consumeDate')}")
        else:
            print("  最近一单: 无")


def run_poll(sess, interval=15, minutes=90):
    """并发轮询所有设备。

    为什么并发：命中占用态靠的是运气，设备越多越该一起盯。
    6 台串行 × 每台 2 个请求 ≈ 一轮 2~3 秒，看起来还行；
    但设备越多、网络越差，轮次间隔就被拉得越长，容易正好错开那台被用的。
    并发后一轮耗时等于**最慢的那台**，不会随设备数线性变长。
    """
    devices = load_devices()
    os.makedirs(RECORDS, exist_ok=True)
    workers = min(16, max(4, len(devices)))
    print(f"并发轮询 {len(devices)} 台设备，每 {interval}s 一轮，最多 {minutes} 分钟")
    print(f"并发度 {workers} 台/轮。撞到占用态自动存盘。Ctrl+C 停止。\n")

    started = time.time()
    hits = 0
    rounds = 0
    # 同一台设备连续占用时，别每轮都存一个新文件
    last_saved = {}
    try:
        while time.time() - started < minutes * 60:
            rounds += 1
            t0 = time.time()
            with ThreadPoolExecutor(max_workers=workers) as ex:
                futs = [ex.submit(probe_one, sess, n, d) for n, d in devices.items()]
                results = [f.result() for f in futs]
            took = time.time() - t0
            ts = time.strftime("%Y-%m-%d %H:%M:%S")

            busy_names = []
            for name, dev, st, lo, err in results:
                if err:
                    print(f"  [{ts}] {name:12} ⚠️ {err[:80]}")
                    continue
                if is_busy(st):
                    busy_names.append(name)
                    # 同一台设备 60 秒内只存一次，避免刷出几十个文件
                    if time.time() - last_saved.get(name, 0) > 60:
                        last_saved[name] = time.time()
                        hits += 1
                        fn = os.path.join(
                            RECORDS, f"{name}_{ts.replace(':', '').replace(' ', '_')}.json")
                        with open(fn, "w", encoding="utf-8") as f:
                            json.dump({"device": name, "dev": dev, "time": ts,
                                       "washer_status": st, "lastOrder": lo},
                                      f, ensure_ascii=False, indent=2)
                        print(f"  [{ts}] 🔴 {name:12} 占用！💾 {os.path.basename(fn)}")

            if not busy_names:
                print(f"  [{ts}] 第 {rounds} 轮：{len(devices)} 台全空闲 "
                      f"（耗时 {took:.1f}s）")
            else:
                print(f"  [{ts}] 第 {rounds} 轮：占用 {busy_names}")

            elapsed = time.time() - t0
            time.sleep(max(0.5, interval - elapsed))
    except KeyboardInterrupt:
        print("\n已停止。")
    print(f"共 {rounds} 轮，记录 {hits} 个占用样本，在 {RECORDS}")


# ── CLI ──────────────────────────────────────────────────────────────

def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"

    if cmd == "login":
        if "--password" in sys.argv:
            pw = os.environ.get("QZXY_PASSWORD")
            if not pw:
                sys.exit("设个环境变量 QZXY_PASSWORD 再跑")
            md5 = hashlib.md5(pw.encode()).hexdigest()
            r = requests.post(BASE + "/user/login",
                              data={"telephone": PHONE,
                                    "password": md5[-10:].upper(),
                                    "phoneSystem": "android", "type": 0,
                                    "version": VERSION},
                              headers=headers(), timeout=20)
            resp = r.json()
            print(f"登录结果: success={resp.get('success')} "
                  f"err={resp.get('errorCode')} {resp.get('errorMessage')}")
            if not resp.get("success"):
                return 1
            s = save_session(resp)
            print(f"✅ 已保存登录态，loginCode={mask(s['loginCode'])}")
            return 0

        resp = send_code()
        print(f"发码结果: success={resp.get('success')} "
              f"err={resp.get('errorCode')} {resp.get('errorMessage')}")
        if not resp.get("success"):
            return 1
        print("\n👉 短信已发出，请把收到的验证码告诉我（或在下面直接输入）")
        code = input("验证码: ").strip()
        if not code:
            print("没输入验证码，退出")
            return 1
        lr = sms_login(PHONE, code)
        if not lr.get("success"):
            print(f"❌ 登录失败: {lr.get('errorCode')} {lr.get('errorMessage')}")
            return 1
        s = save_session(lr)
        print(f"✅ 登录成功，loginCode={mask(s['loginCode'])}，已存 {SESSION}")
        return 0

    if cmd == "send-code":
        resp = send_code()
        print(json.dumps(resp, ensure_ascii=False))
        return 0 if resp.get("success") else 1

    if cmd == "submit-code":
        if len(sys.argv) < 3:
            sys.exit("用法: python live_session.py submit-code 123456")
        lr = sms_login(PHONE, sys.argv[2])
        if not lr.get("success"):
            print(f"❌ 登录失败: {lr.get('errorCode')} {lr.get('errorMessage')}")
            return 1
        s = save_session(lr)
        print(f"✅ 登录成功，loginCode={mask(s['loginCode'])}，已存 {SESSION}")
        return 0

    sess = load_session()
    if cmd == "status":
        run_status(sess)
    elif cmd == "poll":
        interval = int(os.environ.get("QZXY_POLL_INTERVAL", "15"))
        minutes = int(os.environ.get("QZXY_POLL_MINUTES", "90"))
        run_poll(sess, interval, minutes)
    elif cmd == "add":
        return cmd_add(sess)
    elif cmd == "discover":
        return cmd_discover(sess)
    else:
        sys.exit(f"未知命令: {cmd}\n可用: login / send-code / submit-code / "
                 f"status / poll / add / discover")
    return 0


def parse_qr(raw: str):
    """从扫码内容里解析出设备标识。

    趣智的二维码形如 `KLCXKJ-Water,M,AABBCCDDEEFF`（见 linyu QrScanActivity.kt:96），
    更常见的是直接贴 MAC。这里尽量宽松：
      · 末尾 12 位十六进制 → sn = mac（去冒号大写）
      · 中间任意位置的 12 位十六进制也认（有的码带前缀）
    """
    import re
    s = (raw or "").strip()
    if not s:
        return None
    # 先去逗号分段，从后往前找 12 位十六进制
    for part in reversed([p.strip() for p in s.split(",") if p.strip()]):
        hexs = re.sub(r"[^0-9A-Fa-f]", "", part)
        if len(hexs) == 12:
            return hexs.upper()
    # 整串里找
    m = re.search(r"([0-9A-Fa-f]{2}[:\-]?){5}[0-9A-Fa-f]{2}", s)
    if m:
        hexs = re.sub(r"[^0-9A-Fa-f]", "", m.group(0))
        if len(hexs) == 12:
            return hexs.upper()
    return None


def to_mac_colon(sn: str) -> str:
    sn = sn.replace(":", "").upper()
    return ":".join(sn[i:i + 2] for i in range(0, 12, 2))


def cmd_add(sess):
    """添加设备：给扫码内容或 MAC，自动查出 bigTypeId / 名称后写进 devices.json。

    用法:
        python live_session.py add "KLCXKJ-Water,M,AABBCCDDEEFF"
        python live_session.py add AA:BB:CC:DD:EE:FF
        python live_session.py add AA:BB:CC:DD:EE:FF --name 3号楼洗衣机1
    """
    args = [a for a in sys.argv[2:] if not a.startswith("--")]
    if not args:
        sys.exit('用法: python live_session.py add "<扫码内容或MAC>" [--name 名称]')
    sn = parse_qr(args[0])
    if not sn:
        sys.exit(f"❌ 没能从 {args[0]!r} 里解析出 12 位设备码")

    name = None
    for i, a in enumerate(sys.argv):
        if a == "--name" and i + 1 < len(sys.argv):
            name = sys.argv[i + 1]

    mac = to_mac_colon(sn)
    print(f"解析结果: sn={sn}  mac={mac}")
    info = api_get(sess, "/device/info/mac", macAddress=sn, isNew="1", isHaier="0")
    d = info.get("data")
    if not d:
        print(f"❌ 服务端查不到这台设备（/device/info/mac 返回 data=null）")
        print(f"   可能原因：这台不是洗衣机/吹风机，或不在本校台账里")
        return 1
    print(f"✅ {d['deviceName']}")
    print(f"   bigTypeId={d['bigTypeId']} ({d.get('bigTypeName')})  "
          f"smallTypeId={d['smallTypeId']} ({d.get('smallTypeName')})")
    print(f"   snCode={d['snCode']}  online={d.get('onlineStatusId')}")

    if d["bigTypeId"] not in (6, 7):
        print(f"   ⚠️ bigTypeId={d['bigTypeId']} 不是洗衣机(6)/吹风机(7)，"
              f"仍然可以加，但这不是本功能的目标设备")

    devs = {}
    if os.path.isfile(DEVICES_FILE):
        with open(DEVICES_FILE, encoding="utf-8") as f:
            devs = json.load(f)
    key = name or d["deviceName"].split("-", 1)[-1]
    devs[key] = {"sn": d["snCode"], "mac": to_mac_colon(d["macAddress"] or sn),
                 "big": d["bigTypeId"], "small": d["smallTypeId"]}
    with open(DEVICES_FILE, "w", encoding="utf-8") as f:
        json.dump(devs, f, ensure_ascii=False, indent=2)
    print(f"\n已写入 {DEVICES_FILE}，当前共 {len(devs)} 台设备")
    return 0


def cmd_discover(sess):
    """在账单里翻出历史设备，自动补全 MAC——不用出门扫一圈。

    账单只给 deviceId，不给 MAC。但对每台设备，如果能从
    /device/info/mac 反查到，就能补上。这里先列出账单里出现过的设备。
    """
    print("从账单里翻历史设备（近 3 个月）…\n")
    found = {}
    for month in ("2026-10", "2026-09", "2026-08"):
        try:
            r = api_get(sess, "/order/query/account/bill/list",
                        month=month, billRequestType="2")
        except Exception as e:
            print(f"  {month}: 失败 {e}")
            continue
        for row in (r.get("data") or []):
            cb = row.get("consumeBillDTO") or {}
            ct = cb.get("consumeType") or ""
            key = cb.get("deviceId")
            if key and key not in found:
                found[key] = {"consumeType": ct,
                              "description": cb.get("description"),
                              "orderNo": cb.get("orderNo")}
    if not found:
        print("账单里没有设备记录")
        return 0
    print(f"账单里出现 {len(found)} 台设备：\n")
    for did, v in found.items():
        print(f"  deviceId={did:>6}  {v['consumeType']:<8} {v['description']}")
    print("\n⚠️ 账单不给 MAC，所以没法直接转成可轮询的设备。")
    print("   要加设备，还是得拿机身二维码 / MAC：")
    print('     python live_session.py add "扫码内容"')
    return 0


if __name__ == "__main__":
    sys.exit(main())
