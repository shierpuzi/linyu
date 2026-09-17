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

    /**
     * 设备类型说明，给小组件副标题这类"小字"位置用。
     * 措辞与账单里的 [com.hualala.linyu.model.BillDTO.deviceTypeLabel] 保持一致。
     */
    val typeLabel: String get() = when {
        isDrinkingWater -> if (isHotWater) "直饮水机 · 热水" else "直饮水机 · 冷水"
        deviceName.startsWith("洗手台") -> "洗手台热水器"
        else -> "卫生间热水器"
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

        /** 设备名里表示「这是什么设备」的词，选寝室时要剥掉 */
        private val DEVICE_TYPE_WORDS = listOf(
            "热水器", "热水表", "洗手台", "卫生间", "洗漱台", "浴室", "淋浴间", "淋浴",
            "饮水机", "直饮水", "开水机", "开水器", "水龙头", "水房"
        )

        /**
         * 从设备名里抽出「房间」那一截，用于「绑定寝室」的选择列表。
         *
         * 同一间房会有热水器、洗手台、卫生间好几台设备，名字各不相同但房间是同一个——
         * 直接截最后一段会得到「320洗手台」「320卫生间」这种，看着像三个不同寝室。
         * 这里把设备类型的词剥掉，剩下的纯数字补个「房」，两个都收敛成「320房」，
         * 调用方 `distinct()` 一下就只剩一条。
         *
         * 抽不出东西（名字里只有类型词）时返回 null，由调用方过滤掉。
         */
        fun roomLabel(name: String): String? {
            val formatted = formatDeviceName(name)
            var last = formatted.split(' ', '　', '-', '_')
                .lastOrNull { it.isNotBlank() } ?: return null
            DEVICE_TYPE_WORDS.forEach { last = last.replace(it, "") }
            last = last.trim(' ', '　', '-', '_')
            if (last.isEmpty()) return null
            // 剥完只剩数字，说明这就是房间号——补个「房」字，和「320房」这类的写法对齐
            return if (last.all { it.isDigit() }) "${last}房" else last
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
