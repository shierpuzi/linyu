"""Find user-visible emoji in the app sources, classified by where they live.

Comments full of ⚠️ / → are deliberate documentation and are NOT reported.
A hit is user-visible when it sits inside a Kotlin string literal, a Kotlin
`Text(...)`/emoji-returning property, or an XML attribute value.

Usage: python tools/scan_emoji.py
Exit code 1 if any user-visible emoji remains.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "linyu" / "app" / "src" / "main"

EMOJI = re.compile(
    "["
    "\U0001F300-\U0001FAFF"      # pictographs, symbols, emoji
    "\U00002600-\U000027BF"      # misc symbols + dingbats (🚿 is 1F6BF; ♨ 2668, ❄ 2744)
    "\U00002B00-\U00002BFF"
    "\U0000FE0F"                 # VS16
    "\U0001F1E6-\U0001F1FF"      # flags
    "]"
)


def scan_kotlin(path):
    hits = []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for n, line in enumerate(lines, 1):
        if not EMOJI.search(line):
            continue
        stripped = line.lstrip()
        is_comment = stripped.startswith("//") or stripped.startswith("*")
        # block comment bodies are handled below; inline trailing comments too
        if is_comment:
            continue
        # a line that is code + emoji: only care about emoji inside "..."
        stringy = re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', line)
        where = "string" if any(EMOJI.search(s) for s in stringy) else "code/other"
        hits.append((n, where, line.strip()))
    return hits


def in_block_comment(lines):
    """Return line numbers inside /* ... */ blocks."""
    inside, out = False, set()
    for n, line in enumerate(lines, 1):
        if "/*" in line and not inside:
            inside = True
            out.add(n)
        elif inside:
            out.add(n)
        if "*/" in line and inside:
            inside = False
    return out


def scan_xml(path):
    hits = []
    text = path.read_text(encoding="utf-8", errors="replace")
    culled = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"),
                    text, flags=re.S)
    for n, line in enumerate(culled.splitlines(), 1):
        if EMOJI.search(line):
            hits.append((n, "xml", line.strip()))
    return hits


def main():
    files = sorted(list(SRC.rglob("*.kt")) + list(SRC.rglob("*.xml")))
    total = 0
    for p in files:
        if p.suffix == ".kt":
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
            block = in_block_comment(lines)
            hits = [h for h in scan_kotlin(p) if h[0] not in block]
        else:
            hits = scan_xml(p)
        if not hits:
            continue
        total += len(hits)
        rel = p.relative_to(ROOT)
        for n, where, snippet in hits:
            print(f"{rel}:{n}: [{where}] {snippet[:110]}")
    print()
    print(f"{total} user-visible emoji site(s)")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
