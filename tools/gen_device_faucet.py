#!/usr/bin/env python3
"""Generate res/drawable/ic_device_faucet.xml — 饮水机/水龙头 设备头像.

Why this generator exists: three cabinet-with-a-recessed-panel designs were
tried and all failed the same way — the recess plus the tap closes into an ink
loop, so at 14-20dp the icon reads as a letter (b / 6 / T / U) or a vending
machine. This one drops the cabinet entirely and draws the tap as a tap:
base plate + riser + horizontal arm + down spout + knob. Nothing spans below
the spout, so no loop can close and no letterform can form.

Sameness with the other four icons: 24dp canvas, viewport 24, single
#FF000000 fill, no stroke / alpha / gradient, every rounded corner is an A
command (no C/S), every feature >= 1.2 viewport units.

Run: python tools/gen_device_faucet.py [--dry]
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "linyu" / "app" / "src" / "main" / "res" / "drawable" / "ic_device_faucet.xml"

MIN_DETAIL = 1.2
TARGET = 19.6                       # longest side of the ink, in viewport units


def f(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def p(x, y):
    return f"{f(x)},{f(y)}"


def rrect(x0, y0, x1, y1, r, sx=1.0, ox=0.0, oy=0.0):
    """Rounded rect, clockwise, corners as A arcs."""
    def X(v):
        return v * sx + ox

    def Y(v):
        return v * sx + oy

    rx = r * sx
    return (f"M{p(X(x0 + r), Y(y0))}"
            f"H{f(X(x1 - r))}A{f(rx)},{f(rx)} 0 0 1 {p(X(x1), Y(y0 + r))}"
            f"V{f(Y(y1 - r))}A{f(rx)},{f(rx)} 0 0 1 {p(X(x1 - r), Y(y1))}"
            f"H{f(X(x0 + r))}A{f(rx)},{f(rx)} 0 0 1 {p(X(x0), Y(y1 - r))}"
            f"V{f(Y(y0 + r))}A{f(rx)},{f(rx)} 0 0 1 {p(X(x0 + r), Y(y0))}Z")


# Laying out directly in viewport units, then fitting to TARGET.
# Thickness is deliberately heavy: at 14dp the sibling icons (washer 0.31,
# dryer 0.34 ink) are solid masses, and a 2.7u tube read visibly lighter than
# them in the side-by-side sheet. 3.4u brings this to ~0.28.
BASE = dict(x0=2.6, x1=9.8, y0=18.4, y1=21.2, r=1.1)     # floor plate
RISER = dict(x0=4.9, x1=8.3, y0=7.9, y1=19.4, r=1.7)     # vertical body
ARM = dict(x0=4.9, x1=18.6, y0=6.1, y1=9.5, r=1.7)       # horizontal arm
SPOUT = dict(x0=15.2, x1=18.6, y0=7.9, y1=14.2, r=1.7)   # downward outlet
KNOB = dict(x0=4.4, x1=8.8, y0=4.2, y1=7.0, r=1.4)       # lever cap on top

# Overlaps are deliberate: same fill colour, so a shape's rounded end hides
# inside its neighbour and the union has no notch. All >= 0.5u.
PARTS = [BASE, RISER, ARM, SPOUT, KNOB]


def features():
    """Every dimension that could vanish at 14dp, in viewport units."""
    return {
        "arm thickness": ARM["y1"] - ARM["y0"],
        "riser width": RISER["x1"] - RISER["x0"],
        "spout width": SPOUT["x1"] - SPOUT["x0"],
        "spout below arm": SPOUT["y1"] - ARM["y1"],
        "knob width": KNOB["x1"] - KNOB["x0"],
        "knob above arm": ARM["y0"] - KNOB["y0"],
        "base height": BASE["y1"] - BASE["y0"],
        "base right of riser": BASE["x1"] - RISER["x1"],
        "base left of riser": RISER["x0"] - BASE["x0"],
        "riser -> spout gap": SPOUT["x0"] - RISER["x1"],
    }


def bbox():
    x0 = min(c["x0"] for c in PARTS)
    x1 = max(c["x1"] for c in PARTS)
    y0 = min(c["y0"] for c in PARTS)
    y1 = max(c["y1"] for c in PARTS)
    return x0, y0, x1, y1


def build():
    x0, y0, x1, y1 = bbox()
    s = TARGET / max(x1 - x0, y1 - y0)
    ox = 12 - (x0 + x1) / 2 * s
    oy = 12 - (y0 + y1) / 2 * s
    paths = [rrect(c["x0"], c["y0"], c["x1"], c["y1"], c["r"], s, ox, oy)
             for c in PARTS]
    return paths, s, ((x1 - x0) * s, (y1 - y0) * s)


NOTE = """饮水机 / 水龙头（设备头像）——热水 / 凉水 / 未知三种状态共用这一个图标。

单色实心，颜色由使用处 tint 决定（设备头像上是白色）。冷热差异不进图形：
26dp 单色塞不下，且界面文案（「正在接热水」/「正在接凉水」）和类型说明
（「直饮水机 · 热水」）已经写明。

造型：落地底座 + 立柱 + 横臂 + 下弯出水嘴 + 立柱顶上的小旋钮。

⚠️ 不要再画「立式机柜 + 正面凹面板 + 居中水嘴」：凹面板和水嘴之间的负空间
   在 14-20dp 会闭合成圈，整枚图标读成字母 b / 6 / T / U 或售货机屏幕（实测
   三轮都栽在这里，_final_gen_faucet 那几版就是）。
⚠️ 保持「出水嘴下方是空的」：底座只铺在立柱那一侧，不要向右延伸去接水嘴，
   否则同样的圈又闭合了。
⚠️ 旋钮别做成横向杠杆：突出立柱外的那一截在 14dp 下读成枪机 / 天线。

由 tools/gen_device_faucet.py 生成，别手改数字——改生成器再跑一遍。
"""


def emit(paths):
    L = ['<?xml version="1.0" encoding="utf-8"?>', "<!--"]
    L += [f"  {ln}" if ln else "  " for ln in NOTE.splitlines()]
    L += ["-->",
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
          '    android:width="24dp"', '    android:height="24dp"',
          '    android:viewportWidth="24"', '    android:viewportHeight="24">']
    for d in paths:
        L += ["    <path",
              '        android:fillColor="#FF000000"',
              f'        android:pathData="{d}" />']
    L.append("</vector>")
    return "\n".join(L) + "\n"


def main(argv):
    paths, s, (w, h) = build()
    print(f"ink {w:.2f} x {h:.2f}  scale {s:.4f}")
    bad = False
    for k, v in features().items():
        flag = "  <-- TOO THIN" if v < MIN_DETAIL else ""
        bad |= v < MIN_DETAIL
        print(f"  {k:26s} {v:5.2f}u{flag}")
    if bad:
        print(f"*** FAIL: a feature is under {MIN_DETAIL}u")
        return 1
    print(f"  PASS: all {len(features())} features >= {MIN_DETAIL}u")
    if "--dry" in argv:
        print("(dry run, not written)")
        return 0
    OUT.write_text(emit(paths), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
