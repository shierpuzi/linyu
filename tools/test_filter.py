#!/usr/bin/env python3
"""过滤脚本自检：用真实抓包 + 人工构造的「未来抓包」验证过滤逻辑。

跑法：python test_filter.py

覆盖的回归点，每一个都是真踩过的坑：
  1. 洗衣机抓包：11 HTTP + 1 MQTT + 130 噪音，一条都不许飘
  2. MQTT 变长长度：574 字节的 PUBLISH 必须解开，topic 不能把长度字节吞进去
  3. 余额轨迹按**时间**排，不是按会话序号（序号 47 比 84 晚）
  4. 吹风机场景：换个域名 / 新接口 / 新设备类型，必须被认出来而不是丢掉
"""
import json
import os
import shutil
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import filter_capture as F

FAILS = []


def check(name, got, want):
    ok = got == want
    print(f"  {'✅' if ok else '❌'} {name}")
    if not ok:
        FAILS.append(name)
        print(f"       期望: {want!r}")
        print(f"       实际: {got!r}")


def run_filter(inp, out):
    """直接调 main，避免 subprocess 反复起 python。inp 为空 = 用默认抓包。"""
    argv = sys.argv
    sys.argv = ["filter_capture.py"] + ([inp] if inp else []) + [f"--out={out}"]
    try:
        F.main()
    finally:
        sys.argv = argv
    with open(os.path.join(out, "sessions.json"), encoding="utf-8") as f:
        return json.load(f)


def test_real_washer_capture():
    print("\n1) 真实洗衣机抓包：分类数量与内容")
    out = tempfile.mkdtemp(prefix="flt_washer_")
    try:
        data = run_filter("", out)
        check("业务 HTTP 数", len(data["http"]), 11)
        check("业务 MQTT 数", len(data["mqtt"]), 1)
        check("噪音数", len(data["noise"]), 130)
        check("靠嗅探救回", len(data["sniffed"]), 0)

        paths = [h["path"] for h in data["http"]]
        check("抓到扣钱接口", "/order/tcpDevice/downRate/modeOrder" in paths, True)
        check("抓到洗衣机状态", "/order/tcpDevice/washer/status" in paths, True)

        print("\n2) 时间排序与余额差额（序号 47 比 84 晚，必须按 time 排）")
        w = data["wallets"]
        check("余额第一次是 16.680", w[0]["money"], "16.680")
        check("余额最后一次是 12.680", w[-1]["money"], "12.680")
        delta = float(w[-1]["money"]) - float(w[0]["money"])
        check("差额 = -4.000（扣了钱而不是加钱）", round(delta, 3), -4.0)
        times = [h["time"] for h in data["http"]]
        check("业务请求按时间递增", times == sorted(times), True)

        print("\n3) MQTT 变长长度解码（574 字节 PUBLISH）")
        m = data["mqtt"][0]
        # 期望值从抓包自身反推：clientId 形如 android_<手机号>。
        # 不写死具体号码——仓库里不该出现真实手机号，而且这样对任何抓包都成立。
        phone = (m["clientId"] or "").removeprefix("android_")
        check("clientId 形如 android_<手机号>", (m["clientId"] or "").startswith("android_"), True)
        check("clientId 里的手机号长度合理", len(phone), 11)
        check("订阅数", len(m["subscriptions"]), 1)
        # topic 必须是干净的 `app_downRate_<手机号>`，不能把 MQTT 长度字节吃进去
        sub = m["subscriptions"][0]
        check("topic 干净无粘连", sub.startswith("app_downRate_") and sub.endswith(phone), True)
        pub = m["publishes"][0]
        check("payload 解析成 JSON", pub["parsed"] is not None, True)
        check("orderNo", pub["parsed"]["orderNo"], "13202610051024562144")
        check("套餐名", pub["parsed"]["xfModelName"], "标准洗")
        check("预扣 4000 厘", pub["parsed"]["preDeductMoney"], 4000)

        print("\n4) 设备画像")
        dv = data["devices"][0]
        check("deviceId 正确（不是 None）", dv["deviceId"], 1259)
        check("bigTypeId=6", dv["bigTypeId"], 6)
        check("smallTypeId=3", dv["smallTypeId"], 3)
    finally:
        shutil.rmtree(out, ignore_errors=True)


def make_fake_capture(root):
    """手工造一份「吹风机」抓包：新域名、新接口、新设备类型 + 老噪音。"""
    def sess(name, meta_name, meta, resp_body=None, req_body=None, bins=None):
        d = os.path.join(root, str(name))
        os.makedirs(d)
        with open(os.path.join(d, meta_name), "w", encoding="utf-8") as f:
            json.dump(meta, f, ensure_ascii=False)
        if resp_body is not None:
            with open(os.path.join(d, "response_body.json"), "w", encoding="utf-8") as f:
                f.write(json.dumps(resp_body, ensure_ascii=False))
            with open(os.path.join(d, "response.json"), "w", encoding="utf-8") as f:
                json.dump({"code": 200}, f)
        if req_body is not None:
            with open(os.path.join(d, "request_body.bin"), "wb") as f:
                f.write(req_body.encode())

    # 吹风机设备台账：bigTypeId=7 / smallTypeId=8（编的，就是为了证明脚本不认死数字）
    sess(1, "request.json", {
        "protocol": "HTTP/1.1", "method": "GET", "time": "2026-11-01 22:00:01",
        "headers": {}, "remoteIp": "203.107.60.105", "remotePort": 443,
        "url": "https://v3-api.china-qzxy.cn/device/info/mac?macAddress=AABBCCDDEEFF",
    }, {"success": True, "errorCode": 0, "data": {
        "deviceId": 9001, "deviceName": "吹风机-吹风机-5号楼-2层-洗漱间-1",
        "bigTypeId": 7, "bigTypeName": "吹风设备", "smallTypeId": 8,
        "smallTypeName": "吹风机", "snCode": "AABBCCDDEEFF",
        "macAddress": "AA:BB:CC:DD:EE:FF", "isUse": 0, "onlineStatusId": 1,
    }})

    # 吹风机专属新接口（已知清单里没有 → 必须打 🆕）
    sess(2, "request.json", {
        "protocol": "HTTP/1.1", "method": "POST", "time": "2026-11-01 22:00:05",
        "headers": {}, "remoteIp": "203.107.60.105", "remotePort": 443,
        "url": "https://v3-api.china-qzxy.cn/order/tcpDevice/hairDryer/start",
    }, {"success": True, "errorCode": 0, "data": {"orderNo": "13202611012200051111"}},
        req_body="snCode=AABBCCDDEEFF&loginCode=abc")

    sess(3, "request.json", {
        "protocol": "HTTP/1.1", "method": "GET", "time": "2026-11-01 22:00:02",
        "headers": {}, "remoteIp": "203.107.60.105", "remotePort": 443,
        "url": "https://v3-api.china-qzxy.cn/account/wallet",
    }, {"success": True, "errorCode": 0, "data": {"money": "20.000", "accountRealMoney": 20000}})

    sess(4, "request.json", {
        "protocol": "HTTP/1.1", "method": "GET", "time": "2026-11-01 22:09:00",
        "headers": {}, "remoteIp": "203.107.60.105", "remotePort": 443,
        "url": "https://v3-api.china-qzxy.cn/account/wallet",
    }, {"success": True, "errorCode": 0, "data": {"money": "18.500", "accountRealMoney": 18500}})

    # 老噪音：穿山甲
    sess(5, "request.json", {
        "protocol": "h2", "method": "POST", "time": "2026-11-01 22:00:09",
        "url": "https://api-access.pangolin-sdk-toutiao1.com/api/ad/union/sdk/stats/batch/",
        "remoteIp": "1.2.3.4", "remotePort": 443, "headers": {},
    })
    # 老噪音：UDP 常量刷包
    for i, port in enumerate((33434, 33435)):
        sess(6 + i, "udp.json", {
            "protocol": "UDP", "time": "2026-11-01 22:00:10",
            "remoteIp": "1.95.21.140", "remotePort": port,
        })
        with open(os.path.join(root, str(6 + i), "1.bin"), "wb") as f:
            f.write(b"ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_")

    # ⚠️ 关键用例：一个**不认识的域名**，但载荷里有 loginCode
    #    —— 这是「服务端换了新网关」的情况，绝不能被当噪音丢掉
    sess(8, "request.json", {
        "protocol": "HTTP/1.1", "method": "POST", "time": "2026-11-01 22:00:06",
        "headers": {}, "remoteIp": "5.6.7.8", "remotePort": 443,
        "url": "https://v4-api.china-qzxy.cn/order/tcpDevice/hairDryer/status",
    }, {"success": True, "errorCode": 0, "data": None},
        req_body="snCode=AABBCCDDEEFF&loginCode=deadbeef")


def test_fake_hairdryer_capture():
    print("\n5) 吹风机场景：新设备类型 / 新接口 / 新域名必须被认出来")
    root = tempfile.mkdtemp(prefix="flt_dryer_in_")
    out = tempfile.mkdtemp(prefix="flt_dryer_out_")
    try:
        make_fake_capture(root)
        data = run_filter(root, out)
        paths = {h["path"]: h for h in data["http"]}

        check("吹风机台账被采集", "/device/info/mac" in paths, True)
        dv = data["devices"][0]
        check("新设备 bigTypeId=7 照样提取", dv["bigTypeId"], 7)
        check("新设备 smallTypeId=8 照样提取", dv["smallTypeId"], 8)

        check("新接口 hairDryer/start 被认出是业务",
              "/order/tcpDevice/hairDryer/start" in paths, True)
        check("新接口标记为未知（要人工看）", paths["/order/tcpDevice/hairDryer/start"]["known"], False)
        check("新接口语义自动归类为「下单」",
              "★" in paths["/order/tcpDevice/hairDryer/start"]["semantic"], True)

        check("换域名 v4-api 也认（域名含 qzxy）",
              "/order/tcpDevice/hairDryer/status" in paths, True)

        check("吹风机也扣了 1.5 元",
              round(float(data["wallets"][-1]["money"]) - float(data["wallets"][0]["money"]), 3), -1.5)

        noise_hosts = {(n["host"], n["ip"]) for n in data["noise"]}
        check("穿山甲仍归噪音", any("pangolin" in (h or "") for h, _ in noise_hosts), True)
        check("UDP 刷包仍归噪音", any(ip == "1.95.21.140" for _, ip in noise_hosts), True)

        with open(os.path.join(out, "report.md"), encoding="utf-8") as f:
            report = f.read()
        check("报告里点名了新接口", "🆕" in report, True)
        check("报告里列出吹风机画像", "bigTypeId=7" in report or "| 7 |" in report, True)
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(out, ignore_errors=True)


def test_sniff_rescue():
    print("\n6) 内容嗅探：不认识的目标里出现业务字段时不能静默丢弃")
    root = tempfile.mkdtemp(prefix="flt_sniff_in_")
    out = tempfile.mkdtemp(prefix="flt_sniff_out_")
    try:
        d = os.path.join(root, "1")
        os.makedirs(d)
        with open(os.path.join(d, "tcp.json"), "w", encoding="utf-8") as f:
            json.dump({"protocol": "TCP", "time": "2026-11-01 22:00:00",
                       "remoteIp": "9.9.9.9", "remotePort": 9999}, f)
        # 一个完全不认识的目标，但帧里带 deviceSnCode
        with open(os.path.join(d, "1.bin"), "wb") as f:
            f.write(b"\x00\x01\x02" + b'{"deviceSnCode":"AABBCC","orderNo":"123"}')
        data = run_filter(root, out)
        check("靠嗅探升级成业务会话", len(data["sniffed"]), 1)
        check("没有被当噪音丢掉", data["sniffed"][0]["ip"], "9.9.9.9")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(out, ignore_errors=True)


def test_separate_extract_dirs():
    print("\n7) 换抓包文件必须先重新解压（不能静默复用上次的数据）")
    import zipfile
    d1 = tempfile.mkdtemp(prefix="zip_a_")
    d2 = tempfile.mkdtemp(prefix="zip_b_")
    try:
        # 两个内容不同的 zip，文件名也不同
        for d, name, body in ((d1, "洗衣机消费抓包.zip", b'{"deviceName":"washer"}'),
                              (d2, "吹风机抓包.zip", b'{"deviceName":"dryer"}')):
            s = os.path.join(d, "1")
            os.makedirs(s)
            with open(os.path.join(s, "tcp.json"), "w", encoding="utf-8") as f:
                json.dump({"protocol": "TCP", "remoteIp": "1.1.1.1", "remotePort": 1,
                           "url": "https://v3-api.china-qzxy.cn/x", "time": "t"}, f)
            with open(os.path.join(s, "1.bin"), "wb") as f:
                f.write(b"loginCode" + body)
            with zipfile.ZipFile(os.path.join(d, name), "w") as z:
                z.write(os.path.join(s, "tcp.json"), "1/tcp.json")
                z.write(os.path.join(s, "1.bin"), "1/1.bin")

        a = F.ensure_capture_dir(os.path.join(d1, "洗衣机消费抓包.zip"))
        b = F.ensure_capture_dir(os.path.join(d2, "吹风机抓包.zip"))
        check("两个 zip 解压到不同目录", a != b, True)
        check("a 里是洗衣机数据",
              b"washer" in open(os.path.join(a, "1", "1.bin"), "rb").read(), True)
        check("b 里是吹风机数据",
              b"dryer" in open(os.path.join(b, "1", "1.bin"), "rb").read(), True)

        # 同一个 zip 再解压一次应当复用（不重复解压）
        a2 = F.ensure_capture_dir(os.path.join(d1, "洗衣机消费抓包.zip"))
        check("同一个 zip 复用目录", a2, a)
    finally:
        shutil.rmtree(d1, ignore_errors=True)
        shutil.rmtree(d2, ignore_errors=True)


def test_real_hairdryer_capture():
    """真实吹风机抓包（2026-10-24）。这是换设备后的第一份真样本。"""
    print("\n0) 真实吹风机抓包")
    zp = os.path.join(os.path.dirname(os.path.dirname(HERE)), "吹风机.zip")
    if not os.path.isfile(zp):
        print("  ⏭ 没有 吹风机.zip，跳过")
        return
    out = tempfile.mkdtemp(prefix="flt_dryer_real_")
    try:
        data = run_filter(zp, out)
        check("业务 HTTP 数", len(data["http"]), 20)
        check("业务 MQTT 数", len(data["mqtt"]), 1)

        print("\n  ── MQTT：换了 broker + 多帧粘连的 PUBLISH")
        m = data["mqtt"][0]
        check("broker 是新的 39.108.147.136", m["remote"], "39.108.147.136:1883")
        check("连上了且订阅成功", len(m["subscriptions"]), 1)
        check("订阅的是 app_downRate_<自己手机号>",
            m["subscriptions"][0].startswith("app_downRate_"), True)
        # ⚠️ 这条是回归重点：SUBACK 和 PUBLISH 挤在同一个 .bin 里，
        #    解析器必须逐帧遍历，否则 publishes 会是空列表
        check("PUBLISH 没被 SUBACK 吞掉", len(m["publishes"]), 1)
        pub = m["publishes"][0]["parsed"]
        check("订单号", pub["orderNo"], "13202610240012244626")
        check("套餐名", pub["xfModelName"], "电吹风模式一")
        check("预扣 600 厘 = 0.6 元", pub["preDeductMoney"], 600)

        print("\n  ── 设备画像")
        dv = data["devices"][0]
        check("bigTypeId=7（吹风机）", dv["bigTypeId"], 7)
        check("smallTypeId=5（吹风机）", dv["smallTypeId"], 5)
        check("deviceId", dv["deviceId"], 1252)

        print("\n  ── 扣款 0.6 元")
        w = data["wallets"]
        check("余额 26.740 → 26.140", (w[0]["money"], w[-1]["money"]),
              ("26.740", "26.140"))
        check("差额 -0.6", round(float(w[-1]["money"]) - float(w[0]["money"]), 3), -0.6)

        print("\n  ── 新接口被点名，且只读接口不混进「花钱」")
        paths = {h["path"] for h in data["http"]}
        check("认出了 lastOrder/byMac", "/order/query/lastOrder/byMac" in paths, True)
        check("认出了 bill/detail", "/order/query/account/bill/detail" in paths, True)
        lo = next(h for h in data["http"] if h["path"] == "/order/query/lastOrder/byMac")
        check("lastOrder 是 GET（只读）", lo["method"], "GET")

        print("\n  ── 关键业务缺口：全程没有停止/取消接口")
        stops = [h["path"] for h in data["http"]
                 if any(k in h["path"].lower()
                        for k in ("close", "stop", "cancel", "refund", "shutdown"))]
        check("确认抓包里没有停止接口（说明按套餐一次性计费、不能中途停）",
              stops, [])
    finally:
        shutil.rmtree(out, ignore_errors=True)


def main():
    test_real_washer_capture()
    test_real_hairdryer_capture()
    test_fake_hairdryer_capture()
    test_sniff_rescue()
    test_separate_extract_dirs()

    print("\n" + "=" * 62)
    if FAILS:
        print(f"❌ {len(FAILS)} 项失败：")
        for f in FAILS:
            print(f"   - {f}")
        return 1
    print("✅ 全部通过：真实抓包分类正确，吹风机场景不会丢包")
    return 0


if __name__ == "__main__":
    # main() 里会临时替换 sys.argv，用 --out 之外的空输入表示「用默认抓包」
    if len(sys.argv) > 1 and sys.argv[1] == "":
        pass
    sys.exit(main())
