"""Self-check for preview_vector.py (arc conventions + even-odd holes).

Run after touching the parser:  python tools/_arc_check.py

These checks are geometric on purpose: they assert where a point lands relative
to the sweep direction, not against hand-computed magic numbers. The old arc
sign bug survived for months precisely because the wrong arc still had the right
bounding box, so bbox assertions are useless here.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preview_vector as pv

EPS = 0.2
fails = []


def check(name, cond, detail=""):
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{('  ' + detail) if detail else ''}")
    if not cond:
        fails.append(name)


def mid(d):
    """Point halfway along the flattened subpath (skips the moveto vertex)."""
    pts = pv.flatten(d)[0][1:]
    return pts[len(pts) // 2]


# --- 1. A SMALL-arc quarter circle must stay inside the box spanned by its
#        endpoints. (large=1 is the 270-degree way round and legitimately
#        leaves the box; check 2 pins it down instead.)
for sweep in (0, 1):
    p = mid(f"M18,4A2,2 0 0 {sweep} 20,6")
    ok = 18 - EPS <= p[0] <= 20 + EPS and 4 - EPS <= p[1] <= 6 + EPS
    check(f"small quarter arc sweep={sweep} stays between endpoints", ok,
          f"({p[0]:.2f},{p[1]:.2f})")
    q = mid(f"M18,4A2,2 0 1 {sweep} 20,6")
    far = max(abs(q[0] - 19), abs(q[1] - 5))
    check(f"large quarter arc sweep={sweep} detours away from the box", far > 1.5,
          f"({q[0]:.2f},{q[1]:.2f}) - {far:.2f}u from centre")

# --- 2. sweep direction: going from (18,4) to (20,6) the sweep=1 arc must pass
#        on the (18,6)-corner side of the chord... measure it by the signed area
#        relative to the chord instead. chord direction (+2,+2); a point left of
#        it has cross(chord, p-a) > 0.
a, b = (18.0, 4.0), (20.0, 6.0)
for sweep in (1, 0):
    p = mid(f"M18,4A2,2 0 0 {sweep} 20,6")
    cross = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
    # sweep=1 is positive-angle = clockwise in y-down = the point sits on the
    # side that makes the cross product negative here.
    want_neg = sweep == 1
    ok = cross < 0 if want_neg else cross > 0
    check(f"sweep={sweep} bends the correct side of the chord", ok,
          f"cross={cross:+.2f}")

# --- 3. large-arc flag: chord (4,12)-(16,12) with r=8 and sweep=1.
#     Both valid arcs bulge the same way (same sweep direction) but one is
#     2.71u off the chord and the other wraps 13.29u the other way round, so
#     assert the deviation and that both stay on one side.
small = pv.flatten("M4,12A8,8 0 0 1 16,12")[0]
large = pv.flatten("M4,12A8,8 0 1 1 16,12")[0]
ss = max(abs(p[1] - 12) for p in small)
sl = max(abs(p[1] - 12) for p in large)
same_side = (max(p[1] for p in small) - 12) * (max(p[1] for p in large) - 12) > 0 or \
            (min(p[1] for p in small) - 12) * (min(p[1] for p in large) - 12) > 0
check("large-arc flag picks the long arc", abs(ss - 2.71) < 0.2 and abs(sl - 13.29) < 0.3,
      f"deviation small={ss:.2f} (want 2.71) large={sl:.2f} (want 13.29)")
check("same sweep flag bulges the same side", same_side, "")

# --- 4. A circle spelled as two A commands must close on itself: the rim is
#        solid, the middle is hollow under even-odd, outside is empty.
ring = pv.flatten("M12,6A6,6 0 1 1 11.99,6M12,9A3,3 0 1 0 12.01,9")
# probe the middle of the band (centre is (12,12); outer r=6, inner r=3)
check("ring band solid (4.5u from centre)", pv.point_in_polys(12.0, 7.5, ring), "(12,7.5)")
check("ring band solid (left side)", pv.point_in_polys(7.5, 12.0, ring), "(7.5,12)")
check("ring hole empty", not pv.point_in_polys(12.0, 12.0, ring), "(12,12) centre")
check("ring outside empty", not pv.point_in_polys(2.0, 12.0, ring), "(2,12)")
# a plain disc must be solid in the middle (guards against over-eager holes)
disc = pv.flatten("M12,4A8,8 0 1 1 11.99,4")
check("disc solid at centre", pv.point_in_polys(12.0, 12.0, disc), "(12,12)")

# --- 5. Real icons: even-odd frame / hollow body, and group transforms landing
#        inside the canvas (the shower is authored in a 1000-unit space).
def elems(name):
    return pv.collect_paths(pv.ET.parse(pv.RES / f"{name}.xml").getroot())


washer = elems("ic_device_washer")[0]
check("washer body rim solid", pv.point_in_polys(12.0, 1.99 + 0.7, washer), "(12, 2.7)")
check("washer body interior hollow", not pv.point_in_polys(12.0, 12.0, washer), "(12,12)")
check("washer body outside empty", not pv.point_in_polys(2.0, 12.0, washer), "(2,12)")

shower = elems("ic_device_shower")
pts = [p for polys in shower for poly in polys for p in poly]
xs = [p[0] for p in pts]
ys = [p[1] for p in pts]
check("shower inside 24u canvas", 0 <= min(xs) and max(xs) <= 24 and 0 <= min(ys) and max(ys) <= 24,
      f"x[{min(xs):.1f},{max(xs):.1f}] y[{min(ys):.1f},{max(ys):.1f}]")
check("shower fills most of the canvas (>16u wide or tall)",
      max(max(xs) - min(xs), max(ys) - min(ys)) > 16,
      f"{max(xs) - min(xs):.1f}u x {max(ys) - min(ys):.1f}u")

print()
print("RESULT:", "PASS" if not fails else f"FAIL ({len(fails)}): {', '.join(fails)}")
raise SystemExit(1 if fails else 0)
