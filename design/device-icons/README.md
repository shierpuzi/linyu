# 设备图标源文件

这里保存**设备类型图标**的 SVG 原图，以及它们在 Android 工程中的对应关系。

> ⚠️ **当前图标体系已全部矢量图化（#30~#32），不再是这份文件里描述的 emoji 方案。**
> 完整的对应关系表（文件 ↔ 设备 ↔ 数据层 emoji key ↔ 底色 ↔ 造型要求）、7 条硬约束、
> 实测被否决的造型禁区、换图步骤，全部在 **[`docs/图标资产说明.md`](../../docs/图标资产说明.md)**。
> **改图标先读那份**，本文件只说明 `design/` 里这两张历史源图。

## 文件

| 文件 | 说明 |
|---|---|
| `shower.svg` | 热水器 / 淋浴——最早的源图（多色 emoji 风格），**历史遗留** |
| `sink.svg` | 洗手台 / 洗漱——最早的源图，**历史遗留** |

## 现在这套图标是谁生成的

| 图标 | 生成方式 |
|---|---|
| `ic_device_shower.xml` / `ic_device_sink.xml` | 由上面两张 SVG 转出的第一版（单色化 + 砍细节） |
| `ic_device_washer.xml` / `ic_device_dryer.xml` | 由 `tools/_icon_preview/_final_gen.py` 生成；**改数字要改生成器重跑**，不要手改 XML |
| `ic_device_faucet.xml` | 由 `tools/gen_device_faucet.py` 生成（带 ≥1.2u 尺寸断言） |

预览：`python tools/preview_vector.py --all --sheet`（输出到 `_icon_preview/`，已 gitignore）。
圆弧渲染回归断言：`python tools/_arc_check.py`。

## 为什么这两张是 emoji 图，不是图标

它们原本是 **emoji 风格的彩色图**（多色 + 细节丰富），不是为 24dp 单色图标设计的。
转 vector 时做了两项适配：

1. **合并成单色**——颜色全部丢掉，实际颜色由使用处的 `tint` 决定
2. **砍细节**——花洒原本有 7 条水流线，缩到 14dp 会糊成一片，砍到 3 条

## 两条渲染链路（改图标必看）

```
DeviceInfo.typeEmoji（数据层，落 Prefs + 小组件 JSON 快照）
  ├─→ ui/DeviceGlyph.deviceIconRes()          Compose 界面
  └─→ widget/WidgetRenderer.setDeviceIcon()   RemoteViews 小组件
```

⚠️ **两处映射必须同时改**，漏一边的表现是「App 里是洗衣机、桌面上是热水器」。

⚠️ **数据层 emoji 取值不能改**（`🚿 🪥 🧺 💨 🚰/❄️/♨️`）——老用户的
`PrefsHelper.lastDeviceEmoji` 和小组件快照里存着旧值，改了会一律显示成默认类型。
换图标只换 `drawable` 文件，**代码零改动**。
