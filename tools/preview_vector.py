#!/usr/bin/env python3
"""Android VectorDrawable -> PNG previewer (pure python, no deps).

Why this exists: there is no SVG rasteriser on this machine (no cairosvg,
no inkscape, no resvg), and the built-in preview tools render Android vector
XML poorly. This parses the subset of pathData that this project actually uses
(M/m L/l H/h V/v C/c S/s A/a Z/z), flattens it to polygons, and fills with the
even-odd rule so we can eyeball an icon before building it into an APK.

Usage:
    python tools/preview_vector.py <name1> [name2 ...]
    python tools/preview_vector.py --all
    python tools/preview_vector.py --all --sheet    # + 14/20/26/48dp contact sheet

Reads  linyu/app/src/main/res/drawable/<name>.xml
Writes _icon_preview/<name>.png (384x384, blue rounded tile + white glyph)
       _icon_preview/device_icons_final.png (with --sheet)

Self-check after editing this file:  python tools/_arc_check.py

History (2026-10): the arc branch negated its centre coefficient, which flipped
the effect of the sweep and large flags. Straight-edged paths were unaffected,
so it went unnoticed, but every rounded corner bent the wrong way: an outlined
ring's inner hole bulged outward and swallowed the ring, and an evenOdd frame
read as a solid blob. Run the self-check, don't eyeball it.
"""
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent

# 本脚本两种摆放位置都要能用：
#   1) <repo>/linyu/tools/preview_vector.py  -> RES 在 ROOT/app/...
#   2) <repo>/tools/preview_vector.py        -> RES 在 ROOT/linyu/app/...
# 之前硬编码成第 2 种，脚本挪进 linyu/ 之后就找不到资源目录，静默输出空列表。
if (ROOT / "app" / "src" / "main" / "res" / "drawable").is_dir():
    RES = ROOT / "app" / "src" / "main" / "res" / "drawable"
    OUT = ROOT / "_icon_preview"
else:
    RES = ROOT / "linyu" / "app" / "src" / "main" / "res" / "drawable"
    OUT = ROOT / "_icon_preview"

NS = "{http://schemas.android.com/apk/res/android}"
VIEW = 24.0
SCALE = 16                      # px per viewport unit -> 384x384
W = H = int(VIEW * SCALE)
SS = 2                          # supersampling factor


# ---------------------------------------------------------------- path parsing
def tokenize(d):
    out, i, n = [], 0, len(d)
    num = re.compile(r"-?\d*\.\d+(?:[eE][-+]?\d+)?|-?\d+(?:[eE][-+]?\d+)?")
    while i < n:
        ch = d[i]
        if ch in "MmLlHhVvCcSsQqTtAaZz":
            out.append(ch)
            i += 1
        elif ch in " ,\t\r\n":
            i += 1
        else:
            m = num.match(d, i)
            if not m:
                i += 1
                continue
            out.append(float(m.group(0)))
            i = m.end()
    return out


def _arc_points(x1, y1, rx, ry, rot_deg, large, sweep, ex, ey):
    """Endpoint -> center parameterisation (SVG spec F.6.5)."""
    if rx == 0 or ry == 0:
        return [(ex, ey)]
    phi = math.radians(rot_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x1 - ex) / 2.0, (y1 - ey) / 2.0
    x1p = cp * dx2 + sp * dy2
    y1p = -sp * dx2 + cp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    # SVG spec F.6.5.2: fA starts at +1 and is negated when fA == fS.
    # i.e. the centre goes on the opposite side of the chord only when the
    # large-arc and sweep flags AGREE. Negating when they differ (what this
    # file did until 2026-10) sends every arc round the wrong side, which is
    # invisible on straight-edged paths but turns rings into blobs.
    if large == sweep:
        coef = -coef
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + ex) / 2.0
    cy = sp * cxp + cp * cyp + (y1 + ey) / 2.0

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / (n or 1.0))))
        return -a if (ux * vy - uy * vx) < 0 else a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(6, math.ceil(abs(dt) / (math.pi / 24)))
    pts = []
    for k in range(1, n + 1):
        t = t1 + dt * k / n
        pts.append((
            cp * rx * math.cos(t) - sp * ry * math.sin(t) + cx,
            sp * rx * math.cos(t) + cp * ry * math.sin(t) + cy,
        ))
    return pts


def flatten(d, cubic_steps=12):
    """Return a list of polygons (each a list of (x, y)) for one path element."""
    t = tokenize(d)
    i, n = 0, len(t)
    cur = start = (0.0, 0.0)
    prev_ctrl = None
    polys, pts = [], []
    cmd = None

    def nxt():
        nonlocal i
        v = t[i]
        i += 1
        return v

    def have(k):
        return i + k <= n

    def finish():
        nonlocal pts
        if len(pts) > 1:
            polys.append(pts)
        pts = []

    while i < n:
        if isinstance(t[i], str):
            cmd = t[i]
            i += 1
            if cmd in "Zz":
                if pts:
                    pts.append(start)
                    finish()
                cur = start
                prev_ctrl = None
                continue
        if cmd is None:
            i += 1
            continue
        upper, rel = cmd.upper(), cmd.islower()

        if upper == "M":
            if not have(2):
                break
            x, y = nxt(), nxt()
            if rel:
                x, y = cur[0] + x, cur[1] + y
            finish()
            cur = start = (x, y)
            pts = [cur]
            cmd = "L" if upper == "M" else "l"      # implicit lineto after moveto
            prev_ctrl = None
        elif upper in ("L", "H", "V"):
            if upper == "L":
                if not have(2):
                    break
                x, y = nxt(), nxt()
                if rel:
                    x, y = cur[0] + x, cur[1] + y
            elif upper == "H":
                if not have(1):
                    break
                x = nxt()
                x = cur[0] + x if rel else x
                y = cur[1]
            else:
                if not have(1):
                    break
                y = nxt()
                y = cur[1] + y if rel else y
                x = cur[0]
            cur = (x, y)
            pts.append(cur)
            prev_ctrl = None
        elif upper in ("C", "S"):
            if not have(4 if upper == "S" else 6):
                break
            if upper == "S":
                v = [nxt() for _ in range(4)]
                c1 = prev_ctrl if prev_ctrl else cur
                c2 = (cur[0] + v[0], cur[1] + v[1]) if rel else (v[0], v[1])
                ex, ey = (cur[0] + v[2], cur[1] + v[3]) if rel else (v[2], v[3])
            else:
                v = [nxt() for _ in range(6)]
                if rel:
                    c1, c2 = (cur[0] + v[0], cur[1] + v[1]), (cur[0] + v[2], cur[1] + v[3])
                    ex, ey = cur[0] + v[4], cur[1] + v[5]
                else:
                    c1, c2, ex, ey = (v[0], v[1]), (v[2], v[3]), v[4], v[5]
            for k in range(1, cubic_steps + 1):
                tt = k / cubic_steps
                mt = 1 - tt
                pts.append((
                    mt**3 * cur[0] + 3 * mt**2 * tt * c1[0] + 3 * mt * tt**2 * c2[0] + tt**3 * ex,
                    mt**3 * cur[1] + 3 * mt**2 * tt * c1[1] + 3 * mt * tt**2 * c2[1] + tt**3 * ey,
                ))
            prev_ctrl, cur = c2, (ex, ey)
        elif upper == "A":
            if not have(7):
                break
            v = [nxt() for _ in range(7)]
            ex, ey = (cur[0] + v[5], cur[1] + v[6]) if rel else (v[5], v[6])
            pts.extend(_arc_points(cur[0], cur[1], v[0], v[1], v[2],
                                   int(round(v[3])), int(round(v[4])), ex, ey))
            prev_ctrl, cur = None, (ex, ey)
        else:                                        # Q/T: unused in this project
            i += 1
    finish()
    return [p for p in polys if len(p) > 1]


def point_in_polys(px, py, polys):
    """Even-odd test across all subpaths of one path element."""
    inside = False
    for poly in polys:
        m = len(poly)
        j = m - 1
        for k in range(m):
            xi, yi = poly[k]
            xj, yj = poly[j]
            if (yi > py) != (yj > py):
                xint = (xj - xi) * (py - yi) / (yj - yi) + xi
                if px < xint:
                    inside = not inside
            j = k
    return inside


# ------------------------------------------------------------- group transform
def compose(parent, sx, sy, tx, ty, rot, px, py):
    """parent * (translate . scale-about-pivot . rotate-about-pivot)."""
    a = math.radians(rot)
    ca, sa = math.cos(a), math.sin(a)
    ga, gb, gc = sx * ca, -sx * sa, sx * (px - px * ca + py * sa) + tx
    gd, ge, gf = sy * sa, sy * ca, sy * (py - py * ca - px * sa) + ty
    pa, pb, pc, pd, pe, pf = parent
    return (pa * ga + pb * gd, pa * gb + pb * ge, pa * gc + pb * gf + pc,
            pd * ga + pe * gd, pd * gb + pe * ge, pd * gc + pe * gf + pf)


IDENTITY = (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)


def collect_paths(elem, m=IDENTITY):
    """Walk <group> trees, composing affine matrices, and collect pathData.

    Android nests <path> inside <group scaleX scaleY translateX translateY
    rotation pivotX pivotY>. Ignoring the transform renders the glyph off-canvas
    (the shower icon turns into a blank image), so this can't be skipped.
    """
    polys = []
    for child in list(elem):
        tag = child.tag.split("}")[-1]
        if tag == "group":
            def num(attr, default):
                return float(child.get(f"{NS}{attr}", default))

            local = compose(m, num("scaleX", "1"), num("scaleY", "1"),
                            num("translateX", "0"), num("translateY", "0"),
                            num("rotation", "0"), num("pivotX", "0"), num("pivotY", "0"))
            polys.extend(collect_paths(child, local))
        elif tag == "path":
            d = child.get(f"{NS}pathData")
            if not d:
                continue
            a, b, c, dd, e, f = m
            polys.append([[(a * x + b * y + c, dd * x + e * y + f) for (x, y) in poly]
                          for poly in flatten(d)])
    return polys


# --------------------------------------------------------------------- render
def mask_of(name):
    """Return (mask L image at 1x, path element count) for one drawable."""
    src = RES / f"{name}.xml"
    if not src.exists():
        print(f"  !! missing {src}")
        return None
    paths = collect_paths(ET.parse(src).getroot())
    if not paths:
        print(f"  !! no pathData in {name}")
        return None

    w, h = W * SS, H * SS
    pixels = bytearray(w * h)
    # Each <path> element is filled on its own (even-odd across ITS OWN
    # subpaths) and painted over the previous ones. Android's default fillType is
    # nonZero; the icons here spell their holes with opposite winding, which
    # fills identically under even-odd, so one rule covers both. Per-element
    # isolation is what stops one element's hole punching through another.
    for polys in paths:
        for py_i in range(h):
            py = (py_i + 0.5) / (SCALE * SS)
            row = py_i * w
            for px_i in range(w):
                if point_in_polys((px_i + 0.5) / (SCALE * SS), py, polys):
                    pixels[row + px_i] = 255
    mask = Image.frombytes("L", (w, h), bytes(pixels)).resize((W, H), Image.LANCZOS)
    return mask, len(paths)


def glyph(name, fg="#FFFFFF"):
    r = mask_of(name)
    if r is None:
        return None
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.paste(Image.new("RGBA", (W, H), fg), (0, 0), r[0])
    return img


def tile(g, size, bg="#2563EB"):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, size - 1, size - 1],
                                        radius=int(size * 0.22), fill=bg)
    if g is not None:
        im.alpha_composite(g.resize((size, size), Image.LANCZOS))
    return im


def render(name, out_dir=OUT, bg="#2563EB", fg="#FFFFFF"):
    g = glyph(name, fg)
    if g is None:
        return None
    out_dir.mkdir(exist_ok=True)
    dst = out_dir / f"{name}.png"
    tile(g, W, bg).save(dst)
    print(f"  {name:26s} -> {dst.name}")
    return dst


def contact_sheet(names, sizes=(14, 20, 26, 48), dst=None, bg="#2563EB"):
    """names x sizes grid: blue rounded tile + white glyph, drawn at 4x."""
    dst = dst or (OUT / "device_icons_final.png")
    dpp, gap, pad, label = 4, 24, 34, 28
    cells = [int(s * dpp) for s in sizes]
    colw = max(cells)
    total_w = pad * 2 + len(names) * (colw + gap) - gap
    total_h = pad * 2 + sum(c + label + gap for c in cells) - gap
    sheet = Image.new("RGB", (total_w, total_h), (245, 246, 248))
    dr = ImageDraw.Draw(sheet)
    glyphs = {n: glyph(n) for n in names}
    y = pad
    for size, cell in zip(sizes, cells):
        dr.text((pad, y - 18), f"{size}dp", fill=(90, 96, 106))
        x = pad
        for n in names:
            t = tile(glyphs[n], cell, bg)
            sheet.paste(t.convert("RGB"), (x, y), t.getchannel("A"))
            x += colw + gap
        y += cell + label + gap
    dst.parent.mkdir(exist_ok=True)
    sheet.save(dst)
    print(f"  contact sheet -> {dst}")
    return dst


def main(argv):
    args = argv[1:]
    sheet = "--sheet" in args
    names = [a for a in args if not a.startswith("--")]
    if not names:
        names = sorted(p.stem for p in RES.glob("ic_device*.xml"))
    print("rendering:")
    for n in names:
        render(n)
    if sheet:
        contact_sheet(names)
    print(f"\noutput dir: {OUT}")


if __name__ == "__main__":
    main(sys.argv)
