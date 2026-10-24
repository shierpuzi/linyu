package com.hualala.linyu.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.AppLogger

/**
 * 收藏的设备。
 *
 * ## 和「上次使用」的区别（这是本功能存在的理由）
 *
 * 「上次使用」（`PrefsHelper.lastDevice*`）是**单条易失记录**：只有一个槽位，
 * 存的是最后一次操作过的设备，而且用户杀后台/清缓存后容易丢，
 * 导致首页那个「上次使用设备」卡片时有时无。
 *
 * 收藏是**持久化的列表**：
 *
 * | | 上次使用 | 收藏 |
 * |---|---|---|
 * | 数量 | 1 条 | 多条 |
 * | 写入时机 | 每次操作设备（被覆盖） | 用户主动点收藏（不覆盖） |
 * | 需要联网 | 是（要查设备台账） | **否**（字段全存本地） |
 * | 杀后台 | 可能丢 | 保留 |
 *
 * 所以收藏必须把**渲染卡片所需的全部字段**都存下来，不能只存 MAC 再回查——
 * 离线时回查会失败，卡片就变成一行 MAC 了。
 */
data class FavoriteDevice(
    /** MAC（`AA:BB:CC:DD:EE:FF` 带冒号，和 `DeviceInfo.macAddress` 一致） */
    val mac: String,
    /** snCode（下发指令用） */
    val snCode: String,
    /** 格式化后的显示名 */
    val displayName: String,
    /** 原始设备名（寝室筛选用，见 PrefsHelper.lastDeviceRawName 的注释） */
    val rawName: String,
    val emoji: String,
    val typeName: String,
    /**
     * 计费模型。**必须存**：收藏列表要能直接分流（套餐设备进选套餐页、
     * 按量计费设备进全屏使用页），而分流判据 `billingModel` 依赖 bigTypeId。
     */
    val bigTypeId: Int?,
    val smallTypeId: Int?,
    /** 预扣金额（元）。按量计费设备用得到 */
    val withholdMoney: Double = 0.0,
    /** 收藏时刻（毫秒），用于排序 */
    val savedAtMs: Long = 0L
) {
    /** 供 UI 直接当 [DeviceInfo] 用（只填渲染和分流需要的字段） */
    fun toDeviceInfo(): DeviceInfo = DeviceInfo(
        deviceId = 0,
        deviceName = rawName.ifEmpty { displayName },
        snCode = snCode,
        macAddress = mac,
        withholdMoney = withholdMoney,
        onlineStatusId = 1,
        bigTypeId = bigTypeId,
        bigTypeName = typeName,
        smallTypeId = smallTypeId,
        smallTypeName = typeName
    )
}

/**
 * 把 `DeviceInfo` 转成可长期保存的收藏条目。
 *
 * ⚠️ **必须把计费模型依赖的 `bigTypeId`/`smallTypeId` 一起存**：
 * 收藏列表要能离线分流（套餐设备进选套餐页、按量计费进全屏页），
 * 而 `billingModel` 判据正是这两个 id。只存名字会在离线时失效。
 */
fun DeviceInfo.toFavorite(nowMs: Long): FavoriteDevice = FavoriteDevice(
    mac = macAddress,
    snCode = snCode.orEmpty(),
    displayName = displayName,
    rawName = deviceName,
    emoji = typeEmoji,
    typeName = typeName,
    bigTypeId = bigTypeId,
    smallTypeId = smallTypeId,
    withholdMoney = withholdMoney,
    savedAtMs = nowMs
)

/**
 * 收藏设备的**纯逻辑**（可单测）：增删、判重、排序、序列化。
 *
 * 存储层在 `PrefsHelper`（单个 JSON 字符串），这里只保证数据一致。
 */
object FavoriteStore {
    private val gson = Gson()
    private val listType = object : TypeToken<List<FavoriteDevice>>() {}.type

    /** 收藏上限。防止某些人狂点把 Prefs 撑爆，也不至于挡住正常使用 */
    const val MAX_FAVORITES = 30

    fun encode(list: List<FavoriteDevice>): String = gson.toJson(list, listType)

    /**
     * 解析。**任何异常都返回空列表**，绝不抛——
     * 收藏数据坏了顶多少几个卡片，不能让首页崩掉。
     */
    fun decode(json: String?): List<FavoriteDevice> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val raw: List<FavoriteDevice>? = gson.fromJson(json, listType)
            // Gson 绕过 Kotlin 非空约束：老数据/坏数据可能出现 null 字段，过滤掉
            raw.orEmpty().filter { it.mac.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.w("收藏列表解析失败，已忽略：${e.message}")
            emptyList()
        }
    }

    /**
     * 加一个收藏。
     *
     * - **按 mac 判重**（同一台设备只有一个条目，不按 snCode——用户看到的是 MAC）
     * - 已存在则**更新内容并保持原位置**（设备可能改名，但用户不想要它跳到最后）
     * - 超出 [MAX_FAVORITES] 时挤掉**最早收藏**的
     */
    fun add(list: List<FavoriteDevice>, item: FavoriteDevice): List<FavoriteDevice> {
        if (item.mac.isBlank()) return list
        val idx = list.indexOfFirst { it.mac.equals(item.mac, ignoreCase = true) }
        if (idx >= 0) {
            // 保留原来的 savedAtMs，位置不变
            val merged = list.toMutableList()
            merged[idx] = item.copy(savedAtMs = list[idx].savedAtMs)
            return merged
        }
        val appended = list + item
        return if (appended.size <= MAX_FAVORITES) appended
        else appended.sortedByDescending { it.savedAtMs }.take(MAX_FAVORITES)
            .sortedByDescending { it.savedAtMs }
    }

    /** 移除一个收藏（按 mac）。不存在时原样返回 */
    fun remove(list: List<FavoriteDevice>, mac: String): List<FavoriteDevice> =
        list.filterNot { it.mac.equals(mac, ignoreCase = true) }

    /** 是否已收藏 */
    fun contains(list: List<FavoriteDevice>, mac: String): Boolean =
        list.any { it.mac.equals(mac, ignoreCase = true) }

    /**
     * 排序：新收藏的在前。
     *
     * **不按设备名/在线状态排**——收藏是用户自己摆的，突然因为联网状态变动顺序
     * 会让人觉得"我收藏的东西跑了"。
     */
    fun sorted(list: List<FavoriteDevice>): List<FavoriteDevice> =
        list.sortedByDescending { it.savedAtMs }
}
