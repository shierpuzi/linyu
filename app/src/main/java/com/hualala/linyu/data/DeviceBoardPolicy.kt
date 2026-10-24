package com.hualala.linyu.data

/**
 * 「这个页面上的钱该取哪个板块的」——纯逻辑，不碰网络也不碰 Prefs，所以能直接单测。
 *
 * ## 要解决的问题
 *
 * 一个账号可以绑多个独立项目（4000 洗浴 / 3356 洗衣饮水吹风），各有**独立钱包**。
 * 而「当前板块」是用户手动切的：他停在洗衣板块时点开一台热水器，那台热水器属于
 * 洗浴板块，余额就该显示洗浴钱包的钱。照当前板块取就是**拿错钱包**。
 *
 * 实际表现（用户反馈）：洗浴使用页显示 ¥24.84，而那是洗衣饮水吹风板块的余额。
 *
 * ## 判定顺序
 *
 * 1. **设备自己报的板块**（`/device/info/mac` 的 `projectId`）——唯一权威来源
 * 2. 设备没报 → 回退到调用方给的 `fallbackProjectId`
 * 3. 都给不出 → null，此时**不显示余额**，比显示一个错的钱包好
 */
object DeviceBoardPolicy {

    /**
     * 洗浴板块的 projectId。
     *
     * ⚠️ 这是**硬编码兜底**，只在前两步都失败、且设备确实像热水器时才用。
     * 不同学校 id 不同（见 `CONTEXT-任务包.md`：本项目实测校是 4000 洗浴 / 3356 洗衣饮水吹风），
     * 所以它只该作为最后一道保险，不能当主判据。
     */
    const val BATH_PROJECT_ID = 4000L

    /**
     * 决定余额取哪个板块。
     *
     * @param deviceProjectId 设备自报的 projectId（权威）
     * @param currentProjectId 当前正在用的板块（设备没报时的回退）
     * @return 该用的 projectId；null 表示无法判断
     */
    fun resolve(deviceProjectId: Long?, currentProjectId: Long?): Long? {
        deviceProjectId?.let { if (it > 0L) return it }
        currentProjectId?.let { if (it > 0L) return it }
        return null
    }

    /**
     * [resolve] 之后还要不要**实际去借那个板块的数据**。
     *
     * 设备报的板块 == 当前板块时，界面上的 `walletBalance` 已经是那个板块的钱，
     * 不必再发请求借一次（`withBoardContext` 会让全局认证参数短暂变身，
     * 虽然会还原，但没必要为此冒并发串号的风险）。
     */
    fun needsBorrow(targetProjectId: Long?, currentProjectId: Long?): Boolean {
        if (targetProjectId == null) return false
        return targetProjectId != currentProjectId
    }
}
