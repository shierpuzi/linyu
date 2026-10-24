#!/usr/bin/env python3
"""趣智校园洗衣机链路：请求构造 + 离线回放（默认一分钱不花）。

设计前提：`POST /order/tcpDevice/downRate/modeOrder` 一旦真的发出去，
服务端立刻预扣套餐费（抓包里是 4000 厘 = 4.00 元），**不可撤销**。
所以本模块默认 `offline=True`，只按抓包构造请求体、从 fixtures 取响应；
真要联网，必须同时满足 `offline=False` + `allow_charge=True` 两个显式开关，
而且读接口和扣钱接口分开授权。

用法：
    python qzxy_washer.py                     # 离线走一遍完整链路
    python qzxy_washer.py --live --read-only  # 联网只调查询类接口（不扣钱）
"""
from __future__ import annotations

import json
import os
import sys
import urllib.parse

sys.stdout.reconfigure(encoding="utf-8")
BASE_URL = "https://v3-api.china-qzxy.cn"
HERE = os.path.dirname(os.path.abspath(__file__))
# 离线回放用的 fixture。**优先用冻结的 fixtures_washer.json**——
# 它跟着仓库走、内容稳定；`filtered/fixtures/http.json` 是 filter_capture.py
# 的运行时产物（不入库），只在冻结文件缺失时兜底。
_FROZEN = os.path.join(HERE, "fixtures_washer.json")
_RUNTIME = os.path.join(HERE, "filtered", "fixtures", "http.json")
FIXTURES = _FROZEN if os.path.isfile(_FROZEN) else _RUNTIME

CONFIG_KEYS = (
    "module_list,advertise_type,question_list,service_phone_list,"
    "banner_list_app,activity_list_app,aliCard_popup_config"
)

# 抓包里每个端点参数的**原始顺序**。官方客户端是 okhttp 表单，服务端不校验顺序，
# 但保持原样才能离线逐字节比对抓包，证明「复现的是同一串请求」。
FIELD_ORDER = {
    "GET /device/info/mac": ["accountId", "macAddress", "telPhone", "phoneSystem",
                             "loginCode", "telephone", "isNew", "isHaier",
                             "projectId", "userId", "version"],
    "GET /device/mode/list": ["accountId", "telPhone", "phoneSystem", "smallTypeId",
                              "loginCode", "telephone", "projectId", "userId", "version"],
    "GET /device/smallType/id": ["accountId", "telPhone", "phoneSystem", "smallTypeId",
                                 "loginCode", "telephone", "projectId", "userId", "version"],
    "GET /account/wallet": ["accountId", "telPhone", "phoneSystem", "loginCode",
                            "telephone", "projectId", "userId", "version"],
    "POST /order/tcpDevice/washer/status": ["accountId", "telPhone", "snCode",
                                            "phoneSystem", "loginCode", "telephone",
                                            "isHaier", "projectId", "userId", "version"],
    "POST /order/tcpDevice/downRate/modeOrder": ["xfModel", "accountId", "telPhone",
                                                 "snCode", "phoneSystem", "loginCode",
                                                 "telephone", "isHaier", "projectId",
                                                 "userId", "version"],
}

# 会扣钱的端点：只有它们需要 allow_charge
CHARGING_ENDPOINTS = {"/order/tcpDevice/downRate/modeOrder"}


class ChargeBlocked(RuntimeError):
    """没拿到显式授权就想花用户的钱。"""


class WasherSession:
    def __init__(self, auth: dict, offline: bool = True, allow_charge: bool = False,
                 fixtures: str | None = None):
        self.auth = auth
        self.offline = offline
        self.allow_charge = allow_charge
        self.fixtures = fixtures or FIXTURES
        self._fx = None
        self.calls: list[tuple[str, str]] = []

    # ── 请求构造（纯函数，不联网）──

    def build(self, key: str, **overrides) -> tuple[str, str, str]:
        """返回 (method, url_or_path, form_body)。params 全来自 auth + overrides。"""
        method, path = key.split(" ", 1)
        params = dict(self.auth)
        params.update(overrides)
        order = FIELD_ORDER[key]
        missing = [k for k in order if params.get(k) is None]
        if missing:
            raise KeyError(f"{key} 缺参数: {missing}")

        # 官方客户端 phoneSystem/version/isNew/isHaier 是常量，不在 auth 里时补默认
        pairs = [(k, str(params[k])) for k in order]
        body = urllib.parse.urlencode(pairs)

        if method == "GET":
            return method, f"{BASE_URL}{path}?{body}", ""
        return method, f"{BASE_URL}{path}", body

    def headers(self) -> dict:
        return {
            "User-Agent": "okhttp/4.2.2",
            "Config-Project": str(self.auth["projectId"]),
            "Config-Keys": CONFIG_KEYS,
            "Content-Type": "application/x-www-form-urlencoded",
        }

    # ── 传输 ──

    def _fixture(self, path: str) -> dict:
        """同一路径在抓包里可能出现多次（wallet 抓了两次），按顺序取。"""
        if self._fx is None:
            with open(self.fixtures, encoding="utf-8") as f:
                self._fx = json.load(f)
            self._queue = {}
            for item in self._fx["http"]:
                self._queue.setdefault(item["path"], []).append(item["resp_body"])
        q = self._queue.get(path)
        if not q:
            raise LookupError(f"fixtures 里没有 {path} 的响应了")
        return json.loads(q.pop(0))

    def call(self, key: str, **overrides) -> dict:
        method, url, body = self.build(key, **overrides)
        path = "/" + url.split(BASE_URL + "/", 1)[1].split("?", 1)[0]
        self.calls.append((key, path))

        if path in CHARGING_ENDPOINTS and not (not self.offline and self.allow_charge):
            raise ChargeBlocked(
                f"{path} 会让服务端预扣套餐费。"
                f"离线模式/未授权时一律拦下——请用 --live --allow-charge 显式确认。"
            )

        if self.offline:
            return self._fixture(path)

        import requests

        if method == "GET":
            r = requests.get(url, headers=self.headers(), timeout=15)
        else:
            r = requests.post(url, data=body, headers=self.headers(), timeout=15)
        r.raise_for_status()
        return r.json()


# ── 链路编排 ──────────────────────────────────────────────────────────

def milli_to_yuan(milli) -> float:
    """趣智的金额字段多数是「厘」。1000 厘 = 1 元。"""
    return int(milli or 0) / 1000.0


def walk(sess: WasherSession, sn: str, mode_id: int | None = None, dry_order: bool = True):
    """走一遍「套餐设备」链路（洗衣机 bigTypeId=6 和吹风机 bigTypeId=7 共用）。

    两者下单/套餐/结算接口完全相同，只有 smallTypeId（3 / 5）和套餐档位不同。
    """
    steps = []

    dev = sess.call("GET /device/info/mac", macAddress=sn, isNew="1", isHaier="0")
    d = dev["data"]
    big, small = d["bigTypeId"], d["smallTypeId"]
    steps.append(f"设备: {d['deviceName']}  bigTypeId={big} smallTypeId={small} "
                 f"sn={d['snCode']} online={d['onlineStatusId']}")

    st = sess.call("GET /device/smallType/id", smallTypeId=str(small))
    sd = st["data"]
    steps.append(f"小类: {sd['smallTypeName']} / {sd['bigTypeName']}  "
                 f"预扣={milli_to_yuan(sd.get('withholdMoney')):.2f}元  "
                 f"isAllowClose={sd.get('isAllowClose')}")

    modes = sess.call("GET /device/mode/list", smallTypeId=str(small))
    table = [(m["modeId"], m["modeName"], milli_to_yuan(m["modeMoney"]), m["modeTimeMinute"])
             for m in modes["data"]]
    steps.append("套餐: " + " | ".join(f"{mid}:{n} {p:.2f}元/{t}分" for mid, n, p, t in table))

    stat = sess.call("POST /order/tcpDevice/washer/status", snCode=sn, isHaier="0")
    steps.append(f"设备状态: {json.dumps(stat['data'], ensure_ascii=False)}")

    wallet_before = sess.call("GET /account/wallet")["data"]
    steps.append(f"余额(下单前): {wallet_before['money']} 元")

    chosen = mode_id if mode_id is not None else table[0][0]
    price = next(p for mid, _, p, _ in table if mid == chosen)

    _, urlo, body = sess.build("POST /order/tcpDevice/downRate/modeOrder",
                              xfModel=str(chosen), snCode=sn, isHaier="0")
    steps.append(f"下单请求(未发送): xfModel={chosen} → 预计扣 {price:.2f} 元")
    if dry_order:
        steps.append(f"  body = {body}")
    else:
        sess.call("POST /order/tcpDevice/downRate/modeOrder",
                  xfModel=str(chosen), snCode=sn, isHaier="0")
        steps.append("  → 已下单（注意：钱已经扣了）")
    return steps


def main():
    offline = "--live" not in sys.argv
    read_only = "--read-only" in sys.argv
    allow = "--allow-charge" in sys.argv

    if not offline and "--read-only" not in sys.argv and "--allow-charge" not in sys.argv:
        sys.exit("联网模式必须显式二选一：--read-only（只查询）或 --allow-charge（会扣钱）")

    auth = {
        "accountId": os.environ.get("QZXY_ACCOUNT_ID", "10000101"),
        "telPhone": os.environ.get("QZXY_PHONE", "13800138000"),
        "phoneSystem": "android",
        "loginCode": os.environ.get("QZXY_LOGIN_CODE", "00000000000000000000000000000002"),
        "telephone": os.environ.get("QZXY_PHONE", "13800138000"),
        "projectId": os.environ.get("QZXY_PROJECT_ID", "3356"),
        "userId": os.environ.get("QZXY_USER_ID", "10000001"),
        "version": "6.5.28",
    }
    sess = WasherSession(auth, offline=offline, allow_charge=allow)

    print(f"模式: {'离线回放' if offline else ('联网·只查询' if read_only else '联网·允许扣钱')}")
    try:
        for line in walk(sess, sn=os.environ.get("QZXY_SN", "866064204595"),
                         dry_order=read_only or offline):
            print("  " + line)
    except ChargeBlocked as e:
        print(f"  ⛔ {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
