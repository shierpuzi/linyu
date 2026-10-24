#!/usr/bin/env python3
"""自检：把本模块构造出的请求，与抓包里的原始字节逐字比对。

这是「复现抓到的请求」这件事唯一的验收标准——构造逻辑一旦跑偏，
这里必须失败。跑法：

    python selftest.py
"""
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

from filter_capture import clean_path, load_json, ensure_capture_dir
from qzxy_washer import ChargeBlocked, WasherSession

FAILS = []
CAP = None  # 由 main() 里 ensure_capture_dir 决定
HERE = os.path.dirname(os.path.abspath(__file__))      # linyu/tools
# 抓包 zip 在仓库外的上一级
CAPTURE_DIR = os.path.dirname(os.path.dirname(HERE))


def check(name, got, want):
    ok = got == want
    print(f"  {'✅' if ok else '❌'} {name}")
    if not ok:
        FAILS.append(name)
        print(f"       want: {want!r}")
        print(f"       got : {got!r}")


def captured_from_fixtures(fixture_path):
    """从**脱敏后的** fixtures JSON 里取原始抓包请求（逐字节比对用）。

    ## 为什么不再从 zip 里读

    fixtures 已经脱敏入库（手机号/userId/loginCode 换成等长占位值），
    而 zip 里是真实数据。两边比对必然全红。

    fixtures 本身就是「抓包原文的脱敏快照」，结构和参数顺序完全保留，
    用它当基准自洽——而且**不需要用户手里有抓包 zip** 就能跑这个自检。
    """
    data = load_json(fixture_path)
    out = {}
    for item in data.get("http", []):
        out[item["path"]] = {
            "method": item["method"],
            "url": item["url"],
            "body": item.get("req_body", ""),
            "headers": item.get("req_headers", {}),
        }
    return out


def captured():
    """当前平台（洗衣机）的抓包请求，取自 fixtures_washer.json"""
    return captured_from_fixtures(os.path.join(HERE, "fixtures_washer.json"))


def main():
    global CAP
    # 抓包目录由 filter_capture 决定（它按输入文件名分目录，不会拿错包）
    CAP = ensure_capture_dir(os.path.join(CAPTURE_DIR, "洗衣机消费抓包.zip"))
    cap = captured()
    auth = {
        "accountId": "10000101", "telPhone": "13800138000", "phoneSystem": "android",
        "loginCode": "00000000000000000000000000000002", "telephone": "13800138000",
        "projectId": "3356", "userId": "10000001", "version": "6.5.28",
    }
    # ⚠️ fixtures 文件是按抓包生成的，filter_capture.py 跑过吹风机后会变成吹风机数据。
    # 所以这里必须显式指向洗衣机那份，否则本测试会「用自己的期望值去比别人的数据」。
    washer_fx = os.path.join(HERE, "fixtures_washer.json")
    if not os.path.isfile(washer_fx):
        print(f"⏭ 缺少 {washer_fx}，跳过（先跑 python freeze_fixtures.py）")
        return 0
    s = WasherSession(auth, offline=True, fixtures=washer_fx)

    print("1) 请求体与抓包逐字节一致（这是「复现」的硬标准）")
    cases = [
        ("GET /device/info/mac",
         dict(macAddress="866064204595", isNew="1", isHaier="0")),
        ("GET /device/mode/list", dict(smallTypeId="3")),
        ("GET /device/smallType/id", dict(smallTypeId="3")),
        ("GET /account/wallet", {}),
        ("POST /order/tcpDevice/washer/status", dict(snCode="866064204595", isHaier="0")),
        ("POST /order/tcpDevice/downRate/modeOrder",
         dict(xfModel="33", snCode="866064204595", isHaier="0")),
    ]
    for key, over in cases:
        _, url, body = s.build(key, **over)
        path = "/" + url.split("?")[0].split("/", 3)[3]
        c = cap.get(path)
        if c is None:
            check(f"{key} 抓包存在", False, True)
            continue
        if key.startswith("GET"):
            check(f"{key} query 顺序", url.split("?", 1)[1], c["url"].split("?", 1)[1])
        else:
            check(f"{key} body", body, c["body"])

    print("\n2) 会扣钱的接口默认被拦（不能扣用户的钱）")
    try:
        s.call("POST /order/tcpDevice/downRate/modeOrder",
               xfModel="33", snCode="866064204595", isHaier="0")
        check("modeOrder 离线被拦", False, True)
    except ChargeBlocked as e:
        check("modeOrder 离线被拦", True, True)
        print(f"       拦下理由: {str(e)[:60]}…")

    print("\n3) 离线回放能解析出真实业务字段")
    dev = s.call("GET /device/info/mac", macAddress="866064204595", isNew="1", isHaier="0")["data"]
    check("bigTypeId=6（洗衣机大类）", dev["bigTypeId"], 6)
    check("smallTypeId=3（洗衣机小类）", dev["smallTypeId"], 3)
    check("snCode == macAddress（无冒号）", dev["snCode"], dev["macAddress"].replace(":", ""))

    modes = s.call("GET /device/mode/list", smallTypeId="3")["data"]
    check("套餐四档", [m["modeId"] for m in modes], [33, 34, 35, 36])
    check("modeMoney 单位=厘（标准洗 4000）", modes[0]["modeMoney"], 4000)

    print("\n4) 抓包里下单响应是 null —— 成功只能靠 MQTT 推送确认")
    r = s.call("POST /order/tcpDevice/washer/status", snCode="866064204595", isHaier="0")
    check("washer/status data=null（拿不到状态）", r["data"], None)

    # ── 吹风机（2026-10-24 抓包）──
    dryer_fx = os.path.join(HERE, "fixtures_dryer.json")
    if os.path.isfile(dryer_fx):
        print("\n5) 吹风机：请求体同样逐字节一致")
        d_auth = dict(auth, loginCode="00000000000000000000000000000003", version="6.5.29")
        d = WasherSession(d_auth, offline=True, fixtures=dryer_fx)
        dry_cap = captured_from_fixtures(os.path.join(HERE, "fixtures_dryer.json"))
        for key, over in [
            ("GET /device/info/mac", dict(macAddress="C47F0E2351BE", isNew="1", isHaier="0")),
            ("GET /device/mode/list", dict(smallTypeId="5")),
            ("POST /order/tcpDevice/washer/status", dict(snCode="C47F0E2351BE", isHaier="0")),
            ("POST /order/tcpDevice/downRate/modeOrder",
             dict(xfModel="29", snCode="C47F0E2351BE", isHaier="0")),
        ]:
            _, url, body = d.build(key, **over)
            path = "/" + url.split("?")[0].split("/", 3)[3]
            c = dry_cap.get(path)
            if c is None:
                check(f"{key} 抓包存在", False, True)
                continue
            if key.startswith("GET"):
                check(f"{key} query 顺序", url.split("?", 1)[1], c["url"].split("?", 1)[1])
            else:
                check(f"{key} body", body, c["body"])

        print("\n6) 吹风机业务事实")
        dv = d.call("GET /device/info/mac", macAddress="C47F0E2351BE",
                    isNew="1", isHaier="0")["data"]
        check("bigTypeId=7（吹风机）", dv["bigTypeId"], 7)
        check("smallTypeId=5（吹风机）", dv["smallTypeId"], 5)
        ms = d.call("GET /device/mode/list", smallTypeId="5")["data"]
        check("三档套餐", [m["modeId"] for m in ms], [29, 30, 31])
        check("0.6元 = 600 厘", ms[0]["modeMoney"], 600)
        check("3分钟 = 180 秒", ms[0]["modeTimeQuantity"], 180)

    print("\n" + "=" * 60)
    if FAILS:
        print(f"❌ {len(FAILS)} 项失败: {FAILS}")
        return 1
    print("✅ 全部通过：构造的请求与抓包一致，扣钱接口默认被拦")
    return 0


if __name__ == "__main__":
    sys.exit(main())
