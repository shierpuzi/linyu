"""校验 docs/ 与 README 里的内部相对链接是否都存在。

只查本地链接（跳过 http / 锚点 / mailto），顺便检查图片文件是否真的存在。
一次性校验脚本，可反复跑。
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = (
    [os.path.join("docs", f) for f in os.listdir(os.path.join(ROOT, "docs")) if f.endswith(".md")]
    + ["README.md", os.path.join("design", "device-icons", "README.md")]
)

LINK = re.compile(r"\]\(([^)\s]+)\)")
HTML_IMG = re.compile(r'<img[^>]+src="([^"]+)"')
IMG = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")


def check(rel_path: str) -> list[str]:
    full = os.path.join(ROOT, rel_path)
    if not os.path.isfile(full):
        return [f"{rel_path}  (文件本身不存在)"]
    text = open(full, encoding="utf-8").read()
    base = os.path.dirname(full)
    problems = []
    for pattern in (LINK, HTML_IMG, IMG):
        for m in pattern.finditer(text):
            target = m.group(1)
            if target.startswith(("http", "#", "mailto:", "data:")):
                continue
            target = target.split("#")[0]
            if not target:
                continue
            resolved = os.path.normpath(os.path.join(base, target))
            if not os.path.exists(resolved):
                line = text[: m.start()].count("\n") + 1
                problems.append(f"{rel_path}:{line}  ->  {target}")
    return problems


def main() -> int:
    all_problems = []
    for rel in sorted(set(TARGETS)):
        all_problems += check(rel)
    if all_problems:
        print(f"[FAIL] {len(all_problems)} broken refs:")
        for p in all_problems:
            print("   ", p)
        return 1
    print(f"[OK] all links/images resolve ({len(set(TARGETS))} md files checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
