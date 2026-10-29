package com.quzhi.lite.utils

import com.quzhi.lite.R

/**
 * 设备 emoji → **渲染用的图标 / 模块名**。
 *
 * ## 为什么要收口成一个文件
 *
 * 这套映射原来在三个地方各写了一份 `when`：
 * `ui/DeviceGlyph.deviceIconRes`（Compose）、`widget/WidgetRenderer.setDeviceIcon`（RemoteViews）、
 * 还有通知（`Notifier` 里一直用固定水滴图标）。三份必须一致，改一处漏两处就会
 * 「同一台设备在 App 里是洗衣机、在桌面和胶囊上是热水器」。
 *
 * 所以这里做**唯一出处**，其余各处只准转发，不准再写 `when`。
 *
 * ## 数据层为什么还是 emoji
 *
 * 设备类型在全链路是 emoji 字符串（`ActiveOrder.deviceEmoji`、`PrefsHelper.lastDeviceEmoji`、
 * 小组件快照 JSON）。把存量值换成图标 id 会让老用户的数据全部认不出来，
 * 所以数据层一个字节不动，这里只做**渲染时映射**。
 *
 * 未知值兜底成热水器：显示成一台设备，好过空白或一个彩色 emoji。
 */
object DeviceVisual {

    /**
     * emoji → `ic_device_*` 矢量图资源 id。**通知小图标也用它**（单色，系统会染白）。
     *
     * ⚠️ 饮水机三种取值（热水 ♨️ / 冷水 ❄️ / 未知 🚰）共用同一个图标：
     * 24dp 单色图塞不下冷热差异，而冷热已经由文案表达。
     */
    fun iconRes(emoji: String?): Int = when (emoji) {
        "🪥" -> R.drawable.ic_device_sink
        "🧺" -> R.drawable.ic_device_washer
        "💨" -> R.drawable.ic_device_dryer
        "♨️", "❄️", "🚰" -> R.drawable.ic_device_faucet
        else -> R.drawable.ic_device_shower
    }

    /**
     * emoji → **模块名**（流体云胶囊左侧文字、展开卡标题）。
     *
     * 与 `DeviceInfo.typeName` 的措辞保持一致，但**不依赖设备名**——
     * 通知链路上只有 emoji，拿不到 `DeviceInfo`。
     *
     * ⚠️ 必须是**短名**（2~3 字）：OPPO 流体云规范要求胶囊单侧文本精简，
     * 超过 5 个字符会被截成「…」，那正是改造前胶囊不对称的观感。
     */
    fun moduleName(emoji: String?): String = when (emoji) {
        "🪥" -> "洗手台"
        "🧺" -> "洗衣机"
        "💨" -> "吹风机"
        "♨️", "❄️", "🚰" -> "饮水机"
        else -> "热水器"
    }
}
