package com.hualala.linyu.model

import androidx.compose.ui.graphics.Color

data class DeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val snCode: String,
    val macAddress: String,
    val withholdMoney: Double,
    val onlineStatusId: Int,
    // 服务器返回的设备大类（饮水机 = 5），用于精确识别；缺失时回退到名称判断
    val bigTypeId: Int? = null,
    val bigTypeName: String? = null
) {
    val displayName: String get() = formatDeviceName(deviceName)
    val locationOnly: String get() = displayName

    /** 是否直饮水机：bigTypeId == 5，或设备名含相关关键词 */
    val isDrinkingWater: Boolean get() =
        bigTypeId == 5 ||
            deviceName.contains("饮水") || deviceName.contains("直饮") ||
            deviceName.contains("冷水") ||
            (deviceName.contains("热水") && !deviceName.startsWith("热水器") && !deviceName.startsWith("热水表"))

    /** 饮水机是否出热水（否则为冷水） */
    val isHotWater: Boolean get() =
        deviceName.contains("热") || deviceName.contains("开水") || bigTypeName?.contains("热") == true

    /** 设备类型名称 */
    val typeName: String get() = when {
        isDrinkingWater -> "饮水机"
        deviceName.startsWith("洗手台") -> "洗手台"
        else -> "热水器"
    }

    val typeEmoji: String get() = when {
        isDrinkingWater -> if (isHotWater) "♨️" else "❄️"
        deviceName.startsWith("洗手台") -> "🪥"
        else -> "🚿"
    }

    val typeColor: Color get() = when {
        isDrinkingWater -> Color(0xFF10B981) // 饮水机：绿色
        deviceName.startsWith("洗手台") -> Color(0xFFFFCC80)
        else -> Color(0xFF2563EB)
    }

    val statusText: String get() = when {
        isDrinkingWater -> if (isHotWater) "正在接热水" else "正在接凉水"
        deviceName.startsWith("洗手台") -> "正在洗漱中"
        else -> "正在沐浴中"
    }

    companion object {
        fun formatDeviceName(name: String): String {
            val formatted = name
                // 1. 必须先处理「热水器 / 热水表」——否则下面按"热水"开头的规则会先吃掉"热水"，
                //    导致「热水表-xxx」被处理成「表 xxx」
                .replace(Regex("^热水[器表][- ]*"), "")
                // 2. 饮水机前缀（直饮冷水 / 直饮热水 等）
                .replace(Regex("^直饮[- ]*(开水|冷水|热水|温水)[- ]*"), "")
                .replace(Regex("^(开水|冷水|温水)[- ]*"), "")
                .replace(Regex("^(平衡|直饮水?机?)[- ]*"), "")
                // 3. 洗手台前缀
                .replace(Regex("^洗手台\\d*[- ]*"), "")
                // 4. 去掉尾部的水类型
                .replace(Regex("[- ](直饮)?(开水|冷水|热水|温水)$"), "")
                .replace(Regex("-\\d+层-"), "-")
                .replace(Regex("洗手台$"), "房")
                .replace("-", " ")
                .trim()
            return formatted.ifEmpty { name }
        }
    }
}

data class NearbyDevice(
    val name: String,
    val mac: String,
    val rssi: Int,
    val deviceInfo: DeviceInfo? = null
) {
    val displayName: String get() = deviceInfo?.displayName ?: DeviceInfo.formatDeviceName(name)
    val signalText: String get() = "信号强度: $rssi dBm"

    // 优先从 deviceInfo 获取类型，扫描阶段默认 🚿
    val typeEmoji: String get() = deviceInfo?.typeEmoji ?: "🚿"
    val typeColor: Color get() = deviceInfo?.typeColor ?: if (name.startsWith("洗手台")) Color(0xFFFFCC80) else Color(0xFF2563EB)
}
