"""把用户提供的 7 张真机截图加工成 README 用图（Pillow，无外部依赖）。

产出（写入 assets/）：
  preview.png            总览拼图（4 列 × 2 行，7 张）
  home.png               首页 / 收藏设备
  wallet.png             余额 + 消费趋势（合并）
  account.png            我的账号（使用码 / 绑定寝室）
  settings.png           设置页
  mode_order_washer.png  洗衣机选套餐
  mode_order_dryer.png   吹风机选套餐
  metered_device.png     饮水机「准备就绪」（占用态）

单图统一缩到宽 540（README 里按 230~260 显示，2x 屏够清晰），
拼图用浅灰底、图间留白，和原 assets/preview.png 的观感一致。

一次性脚本：README 图片改好后可以删，或留着方便重跑。
"""

from pathlib import Path

from PIL import Image

SRC = Path(r"E:\work\pythondata\newprogram\quzhixiaoyuan\图")
OUT = Path(__file__).resolve().parent.parent / "assets"

# 按文件名的时刻排序就是用户截图的顺序，这里显式写死，避免以后新增截图打乱对应关系
SHOTS = {
    "Screenshot_2026-10-25-12-22-38-93_b06158dd5f867a.jpg": "home.png",
    "Screenshot_2026-10-25-12-22-43-42_b06158dd5f867a.jpg": "wallet.png",
    "Screenshot_2026-10-25-12-23-08-28_b06158dd5f867a.jpg": "account.png",
    "Screenshot_2026-10-25-12-23-27-66_b06158dd5f867a.jpg": "settings.png",
    "Screenshot_2026-10-25-12-23-39-23_b06158dd5f867a.jpg": "mode_order_washer.png",
    "Screenshot_2026-10-25-12-23-44-45_b06158dd5f867a.jpg": "mode_order_dryer.png",
    "Screenshot_2026-10-25-12-23-51-48_b06158dd5f867a.jpg": "metered_device.png",
}

SINGLE_W = 540          # 单图宽度
COLS, ROWS = 4, 2       # 拼图网格（7 张，第 8 格留空）
CELL_W = 340            # 拼图里每格图宽
GAP = 24                # 格间距 / 边距
BG = (244, 245, 247)    # 和 App 浅色底接近


def crop_status_bar(im: Image.Image) -> Image.Image:
    """去掉顶部状态栏（时间 / 信号 / 电量）。

    保留它没有信息量，还会把「12:22、67%」这类无关细节带进 README。
    截图上状态栏高度约 100px（1264 宽），切 110 留一点余量。
    """
    return im.crop((0, 110, im.width, im.height))


def save_single(im: Image.Image, name: str) -> None:
    h = round(im.height * SINGLE_W / im.width)
    im.resize((SINGLE_W, h), Image.LANCZOS).save(OUT / name, optimize=True)
    print(f"  {name:22s} {SINGLE_W}x{h}")


def build_preview(images: list[Image.Image]) -> None:
    cells = []
    for im in images:
        h = round(im.height * CELL_W / im.width)
        cells.append(im.resize((CELL_W, h), Image.LANCZOS))
    cell_h = max(c.height for c in cells)

    w = GAP + COLS * (CELL_W + GAP)
    h = GAP + ROWS * (cell_h + GAP)
    sheet = Image.new("RGB", (w, h), BG)
    for i, c in enumerate(cells):
        col, row = i % COLS, i // COLS
        x = GAP + col * (CELL_W + GAP)
        y = GAP + row * (cell_h + GAP)
        sheet.paste(c, (x, y))
    sheet.save(OUT / "preview.png", optimize=True)
    print(f"  {'preview.png':22s} {w}x{h}")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    loaded = []
    for src_name, dst_name in SHOTS.items():
        p = SRC / src_name
        if not p.is_file():
            raise SystemExit(f"找不到截图：{p}")
        im = crop_status_bar(Image.open(p).convert("RGB"))
        save_single(im, dst_name)
        loaded.append(im)
    build_preview(loaded)
    print("完成 →", OUT)


if __name__ == "__main__":
    main()
