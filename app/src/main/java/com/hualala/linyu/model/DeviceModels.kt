package com.hualala.linyu.model

import androidx.compose.ui.graphics.Color

data class DeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val snCode: String,
    val macAddress: String,
    val withholdMoney: Double,
    val onlineStatusId: Int
) {
    val displayName: String get() = formatDeviceName(deviceName)
    val locationOnly: String get() = displayName

    val typeEmoji: String get() = when {
        deviceName.startsWith("热水器") || deviceName.startsWith("热水表") -> "🚿"
        deviceName.startsWith("洗手台") -> "🪥"
        else -> "🚿"
    }

    val typeColor: Color get() = when {
        deviceName.startsWith("洗手台") -> Color(0xFFFFCC80)
        else -> Color(0xFF2563EB)
    }

    val statusText: String get() = when {
        deviceName.startsWith("洗手台") -> "正在洗漱中"
        else -> "正在沐浴中"
    }

    companion object {
        fun formatDeviceName(name: String): String {
            return name
                .replace(Regex("^热水[器表]-"), "")
                .replace(Regex("^洗手台\\d*-"), "")
                .replace(Regex("-\\d+层-"), "-")
                .replace(Regex("洗手台$"), "房")
                .replace("-", " ")
                .trim()
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
