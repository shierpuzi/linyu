# -*- coding: utf-8 -*-
"""把 SVG 转成 Android vector drawable。

踩过的坑，改这个脚本时别踩回去：

1. **包围盒必须走真正的路径解析**。`m`/`l`/`c`/`a` 后面的数字是相对偏移，
   圆弧的参数里还混着半径和标志位——直接两两配对当坐标会算出离谱的框。
2. **圆弧会鼓出端点之外**。只记终点会让包围盒偏小 → 缩放系数算大 → 内容被画布裁掉
   （牙刷的刷毛就是因为这个缺了一截）。这里用「两端点各扩一个半径」保守包住。
3. **子路径开头的 `m` 是相对上一段终点的**，单独解析会当成从原点起算；
   **一旦砍掉中间某几段，后面所有段的相对起点就全错位**。必须改成绝对 `M`。
4. **`m-26.339-143.517` 里负号本身就是分隔符**，正则要求必须有空白/逗号会漏掉，
   一漏包围盒就会算到画布外面去。
"""
import io
import math
import re

TOKEN = re.compile(r'([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)')
ARGC = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}

NUM = r'[-+]?(?:\d*\.\d+|\d+\.?)'
HEAD = re.compile(r'([Mm])\s*(%s)\s*,?\s*(%s)' % (NUM, NUM))


def parse(d):
    """pathData -> [(cmd, [nums])]，把「省略重复命令字母」的写法也拼回来"""
    flat = []
    cur = None
    for m in TOKEN.finditer(d):
        if m.group(1):
            cur = m.group(1)
            if cur in 'Zz':
                flat.append((cur, []))
        elif cur is not None:
            flat.append((cur, [float(m.group(2))]))

    merged = []
    for cmd, nums in flat:
        n = ARGC[cmd.upper()]
        if n == 0:
            merged.append((cmd, []))
        elif merged and merged[-1][0] == cmd and len(merged[-1][1]) < n:
            merged[-1][1].extend(nums)
        else:
            merged.append((cmd, list(nums)))

    out = []
    for cmd, nums in merged:
        n = ARGC[cmd.upper()]
        if n == 0:
            out.append((cmd, []))
        else:
            for i in range(0, len(nums) - n + 1, n):
                out.append((cmd, nums[i:i + n]))
    return out


def arc_bounds(x1, y1, x2, y2, rx, ry, phi, large_arc, sweep):
    """圆弧的包围盒关键点。

    ⚠️ 这段的三种做法，两种都不行：

    - **只记端点**：圆弧会鼓出端点之外 → 包围盒偏小 → 缩放系数算大 →
      内容被画布裁掉（牙刷的刷毛就是这么缺了一截的）
    - **两端点各扩一个半径**：对短弧太松。花洒那条 `a141.933` 半径 142、弧很短，
      一扩就把框撑开、图标被压小 12%
    - **取所在椭圆的外接矩形**：对**短弧 + 大半径**同样太松。牙刷刷柄的
      `a12.282` 半径 12、两端只隔 4，椭圆框直接把整张图撑到 34x47

    所以这里走正解：做端点参数 → 中心参数转换，算出弧**真正扫过的角度区间**，
    只在区间内的 0°/90°/180°/270° 极值点上取样。既不会漏鼓包，也不会虚胖。
    """
    if rx == 0 or ry == 0:
        return [(x1, y1), (x2, y2)]
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p = cos_p * dx + sin_p * dy
    y1p = -sin_p * dx + cos_p * dy

    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:                       # 半径不够，按规范等比放大
        k = math.sqrt(lam)
        rx, ry = rx * k, ry * k

    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large_arc == sweep:
        coef = -coef
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cxc = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2.0
    cyc = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2.0

    # 起止角（椭圆自身的参数坐标下）
    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry

    def ang(ax, ay):
        a = math.atan2(ay, ax)
        return a if a >= 0 else a + 2 * math.pi

    theta1 = ang(ux, uy)
    delta = ang(vx, vy) - theta1
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi

    pts = [(x1, y1), (x2, y2)]
    for quarter in (0.0, math.pi / 2, math.pi, 3 * math.pi / 2):
        # 这个极值角在不在弧扫过的范围里？
        rel = quarter - theta1
        if delta > 0:
            while rel < 0:
                rel += 2 * math.pi
        else:
            while rel > 0:
                rel -= 2 * math.pi
        if not (min(0.0, delta) <= rel <= max(0.0, delta)):
            continue
        ex, ey = rx * math.cos(quarter), ry * math.sin(quarter)
        pts.append((cxc + ex * cos_p - ey * sin_p, cyc + ex * sin_p + ey * cos_p))
    return pts


def points(d):
    """路径上的关键点（含控制点与圆弧鼓包，作包围盒的**保守**估计）"""
    pts = []
    cx = cy = sx = sy = 0.0
    for cmd, a in parse(d):
        u, rel = cmd.upper(), cmd.islower()
        if u == 'M':
            cx, cy = (cx + a[0], cy + a[1]) if rel else (a[0], a[1])
            sx, sy = cx, cy
        elif u == 'L':
            cx, cy = (cx + a[0], cy + a[1]) if rel else (a[0], a[1])
        elif u == 'H':
            cx = cx + a[0] if rel else a[0]
        elif u == 'V':
            cy = cy + a[0] if rel else a[0]
        elif u in ('C', 'S', 'Q'):
            step = 2 if u in ('S', 'Q') else 6
            for i in range(0, step, 2):
                pts.append((cx + a[i], cy + a[i + 1]) if rel else (a[i], a[i + 1]))
            cx, cy = ((cx + a[step - 2], cy + a[step - 1]) if rel
                      else (a[step - 2], a[step - 1]))
        elif u == 'T':
            cx, cy = (cx + a[0], cy + a[1]) if rel else (a[0], a[1])
        elif u == 'A':
            # 圆弧参数是 rx ry rotation large-arc sweep x y
            rx, ry, phi = abs(a[0]), abs(a[1]), math.radians(a[2])
            ex, ey = (cx + a[5], cy + a[6]) if rel else (a[5], a[6])
            pts.extend(arc_bounds(cx, cy, ex, ey, rx, ry, phi, a[3], a[4]))
            cx, cy = ex, ey
        elif u == 'Z':
            cx, cy = sx, sy
        pts.append((cx, cy))
    return pts


def bbox(d):
    p = points(d)
    if not p:
        return None
    return (min(x for x, _ in p), min(y for _, y in p),
            max(x for x, _ in p), max(y for _, y in p))


def split_subpaths(d):
    """按 m/M 拆子路径，并把每段开头的 `m` 改写成**绝对 `M`**（见文件头第 3 条）"""
    idx = [m.start() for m in re.finditer(r'[Mm]', d)]
    if not idx:
        return [d]
    idx.append(len(d))
    raw = [d[idx[i]:idx[i + 1]].strip() for i in range(len(idx) - 1)]

    out, cx, cy = [], 0.0, 0.0
    for seg in raw:
        head = HEAD.match(seg)
        if not head:
            out.append(seg)
            pp = points(seg)
            if pp:
                cx, cy = pp[-1]
            continue
        dx, dy = float(head.group(2)), float(head.group(3))
        ax, ay = (cx + dx, cy + dy) if head.group(1) == 'm' else (dx, dy)
        rest = seg[head.end():].lstrip()
        fixed = 'M%.4f %.4f%s' % (ax, ay, (' ' + rest) if rest else '')
        out.append(fixed)
        pp = points(fixed)
        cx, cy = pp[-1] if pp else (ax, ay)
    return out


def pick_spread(subs, keep):
    """按起点几何位置均匀挑 keep 条（不是按下标）——平行的水流线按下标挑会挤在一角"""
    def key(s):
        p = points(s)
        x, y = p[0] if p else (0.0, 0.0)
        return x + y
    ordered = sorted(subs, key=key)
    if len(ordered) <= keep:
        return ordered
    return [ordered[round(i * (len(ordered) - 1) / (keep - 1))] for i in range(keep)]


def shift(d, ox, oy):
    """整体平移一段子路径：只改开头的绝对 M，后面的命令都是相对它算的"""
    m = re.match(r'M\s*(%s)\s*,?\s*(%s)' % (NUM, NUM), d)
    if not m:
        return d
    return 'M%.4f %.4f%s' % (float(m.group(1)) + ox, float(m.group(2)) + oy,
                             d[m.end():])


def convert(src, out, title, stream_path=None, keep_stream=None,
            stream_offset=(0, 0), color="#FF000000", box=20.0, canvas=24.0):
    """stream_offset 是**源坐标单位**的平移量，只作用在水流那条 path 上。"""
    text = io.open(src, encoding='utf-8').read()
    paths = re.findall(r'<path[^>]*?d="([^"]+)"', text)

    head, stream = [], []
    for i, d in enumerate(paths):
        subs = split_subpaths(d)
        if stream_path is not None and i == stream_path and len(subs) > keep_stream:
            print('    path%d: %d tiao -> keep %d' % (i + 1, len(subs), keep_stream))
            stream = pick_spread(subs, keep_stream)
        else:
            head.extend(subs)

    moved = [shift(d, *stream_offset) for d in stream]
    if moved and stream_offset != (0, 0):
        print('    liu shui pian yi (%d, %d)' % stream_offset)

    # 包围盒用**平移后**的，否则移出去的部分会被画布裁掉
    bb = None
    for d in head + moved:
        b = bbox(d)
        if not b:
            continue
        bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]),
                                   max(bb[2], b[2]), max(bb[3], b[3]))
    x0, y0, x1, y1 = bb
    w, h = x1 - x0, y1 - y0
    s = box / max(w, h)
    tx = (canvas - w * s) / 2 - x0 * s
    ty = (canvas - h * s) / 2 - y0 * s
    print('    bbox (%.1f, %.1f, %.1f, %.1f)  %.0fx%.0f  scale %.4f'
          % (x0, y0, x1, y1, w, h, s))

    def paths_xml(items, indent):
        pad = ' ' * indent
        lines = []
        for d in items:
            lines.append('%s<path' % pad)
            lines.append('%s    android:pathData="%s"' % (pad, d.strip()))
            lines.append('%s    android:fillColor="%s" />' % (pad, color))
        return '\n'.join(lines)

    blocks = []
    if head:
        blocks.append(paths_xml(head, 8))
    if moved:
        # 水流单独一个 group：嵌套时内层先变换，所以这里是源坐标下的平移，
        # 之后跟着外层一起缩放。调位置只改 main 里的 stream_offset 一个数
        blocks.append('<group\n'
                      '            android:translateX="%.4f"\n'
                      '            android:translateY="%.4f">\n'
                      '%s\n'
                      '        </group>'
                      % (stream_offset[0], stream_offset[1], paths_xml(moved, 12)))

    body = '\n        '.join(blocks)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!--\n  ' + title + '\n\n'
           '  由 SVG 转换而来：按内容包围盒缩放并居中到 24dp 画布里的 20x20 区域，\n'
           '  单色填充，实际颜色由使用处 tint 决定。\n\n'
           '  ⚠️ 变换放在 <group> 里，pathData 保持和源文件一致——换图重新转一次即可。\n'
           '  ⚠️ 包围盒把圆弧**真正扫过的角度范围**算进去了（不是只取端点，也不是\n'
           '     取整个椭圆）——少算会把系数算大、内容被画布裁掉；多算会把图标压小。\n'
           '-->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp"\n'
           '    android:height="24dp"\n'
           '    android:viewportWidth="24"\n'
           '    android:viewportHeight="24">\n'
           '    <group\n'
           '        android:scaleX="%.6f"\n'
           '        android:scaleY="%.6f"\n'
           '        android:translateX="%.4f"\n'
           '        android:translateY="%.4f">\n'
           '        %s\n'
           '    </group>\n'
           '</vector>\n' % (s, s, tx, ty, body))

    io.open(out, 'w', encoding='utf-8', newline='\n').write(xml)
    print('    -> %s' % out)


if __name__ == '__main__':
    D = r'C:\Users\22763\Downloads'
    R = r'C:\Users\22763\Documents\AdroidProjects\app\src\main\res\drawable'

    print('shower:')
    convert(D + r'\淋浴喷头.svg', R + r'\ic_device_shower.xml',
            '热水器 / 淋浴（设备头像）。',
            stream_path=2, keep_stream=3, stream_offset=(-30, 30))

    print('sink:')
    convert(D + r'\牙刷图标.svg', R + r'\ic_device_sink.xml',
            '洗手台 / 洗漱（设备头像）。')
