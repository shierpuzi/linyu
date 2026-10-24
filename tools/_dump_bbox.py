"""Dump per-path / per-subpath bboxes of one drawable after group transforms."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preview_vector as pv

name = sys.argv[1] if len(sys.argv) > 1 else "ic_device_shower"
root = pv.ET.parse(pv.RES / f"{name}.xml").getroot()
paths = pv.collect_paths(root)
print(f"{name}: element bbox at 24u canvas scale")
for ei, polys in enumerate(paths):
    for si, poly in enumerate(polys):
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        print(f"  path[{ei}] sub[{si}] n={len(poly):4d} "
              f"x[{min(xs):6.2f},{max(xs):6.2f}] y[{min(ys):6.2f},{max(ys):6.2f}]")
