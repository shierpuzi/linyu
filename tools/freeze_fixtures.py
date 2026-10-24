#!/usr/bin/env python3
"""把过滤结果冻结成固定 fixture，供 selftest.py 长期使用。

为什么需要：`filter_capture.py` 每次跑都会覆盖 `filtered/fixtures/http.json`，
所以拿它当测试基准会「今天测洗衣机、明天测吹风机」，selftest 的期望值就失效了。
这里把两份抓包各冻结一份独立副本。

用法：python freeze_fixtures.py
"""
import json
import os
import shutil
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))      # linyu/tools
# 抓包 zip 放在**仓库外**的上一级（quzhixiaoyuan/），因为它是用户数据、不入库
CAPTURE_DIR = os.path.dirname(os.path.dirname(HERE))

SOURCES = {
    "washer": "洗衣机消费抓包.zip",
    "dryer": "吹风机.zip",
}

sys.path.insert(0, HERE)
import filter_capture as F  # noqa: E402


def main():
    made = []
    for tag, zipname in SOURCES.items():
        zp = os.path.join(CAPTURE_DIR, zipname)
        if not os.path.isfile(zp):
            print(f"⏭ 跳过 {zipname}（不存在）")
            continue
        tmp = os.path.join(HERE, f"_freeze_{tag}")
        argv = sys.argv
        sys.argv = ["filter_capture.py", zp, f"--out={tmp}"]
        try:
            F.main()
        finally:
            sys.argv = argv
        src = os.path.join(tmp, "fixtures", "http.json")
        dst = os.path.join(HERE, f"fixtures_{tag}.json")
        shutil.copyfile(src, dst)
        shutil.rmtree(tmp, ignore_errors=True)
        n = len(json.load(open(dst, encoding="utf-8"))["http"])
        print(f"✅ fixtures_{tag}.json  ({n} 个业务请求)")
        made.append(dst)
    return 0 if made else 1


if __name__ == "__main__":
    sys.exit(main())
