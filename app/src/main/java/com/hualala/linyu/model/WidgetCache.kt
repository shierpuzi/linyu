package com.hualala.linyu.model

/**
 * 桌面小组件的「离线快照」。
 *
 * 小组件跑在广播接收器里，既扫不了蓝牙也拉不了账单——所以 2x4 的
 * 「附近设备」和「账单」两页展示的是**上一次 App 运行时同步到的数据**，
 * 界面上会标出同步时间，避免让人误以为是实时的。
 */

/**
 * 附近设备的一条（小组件用）。
 *
 * ⚠️ 字段一律声明为**可空**：这些对象是 Gson 从本地 JSON 反序列化出来的，
 * 而 Gson 是绕过构造函数、直接写字段的——**不认 Kotlin 的非空类型**。
 * 只要 JSON 是旧版本写的（少一个字段），取出来就是 null，
 * 在非空类型上调用 `isNotEmpty()` 之类会直接 NPE 让小组件崩掉。
 * 取值时记得 `?: ""`。
 */
data class CachedDevice(
    val emoji: String? = null,
    val name: String? = null,
    val desc: String? = null,
    val rssi: Int = 0,
    /** MAC 地址：小组件的「选用」要把这个带回 App 才能绑设备 */
    val mac: String? = null
)

/** 账单的一条（小组件用）。字段可空的原因同上 */
data class CachedBill(
    val emoji: String? = null,
    val name: String? = null,
    val timeText: String? = null,
    val moneyText: String? = null
)
