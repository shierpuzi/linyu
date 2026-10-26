package com.quzhi.lite.data

/**
 * 「这个页面上的钱该取哪个板块的」「这个设备操作该用哪个板块的身份」——纯逻辑，
 * 不碰网络也不碰 Prefs，所以能直接单测。
 *
 * ## 要解决的问题
 *
 * 一个账号可以绑多个独立项目（各校的 id 都不一样，实测校是 4000 洗浴 / 3356 洗衣饮水吹风），
 * 各有**独立钱包**。而「当前板块」是用户手动切的：他停在洗衣板块时点开一台热水器，
 * 那台热水器属于洗浴板块，余额就该显示洗浴钱包的钱。照当前板块取就是**拿错钱包**。
 *
 * 实际表现（用户反馈）：洗浴使用页显示 ¥24.84，而那是洗衣饮水吹风板块的余额。
 *
 * ## 两个问题，两套取舍
 *
 * | | [resolve]（显示余额） | [operationBoard]（开阀/关阀/下单） |
 * |---|---|---|
 * | 判断不出时 | 返回 null → 界面**不显示**这一行 | 返回 null → **保持当前板块**，一个字段都不动 |
 * | 理由 | 宁可不显示，也不显示一个错的钱包 | 别乱切；切错比不切更糟 |
 *
 * ## ⚠️ 绝不硬编码任何学校 id
 *
 * 各校 projectId 完全不同。写死一个「洗浴=4000」当兜底，换学校后要么查出
 * 一个不存在的板块（一直加载中），要么把请求打到别人的板块上。
 * 本文件所有判定都只比较**相不相等**，不依赖任何具体数值。
 */
object DeviceBoardPolicy {

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

    /**
     * **设备操作**（开阀 / 关阀 / 下单 / 查状态）该在哪个板块的认证参数下执行。
     *
     * 和 [resolve]（决定余额显示用哪个钱包）是两个不同的问题：
     * - 余额：判断不出就返回 null → 界面**不显示余额**（宁可不显示，也不错显示）
     * - 操作：判断不出也返回 null，但语义是**「沿用当前板块，别切」**——
     *   调用方（`ProjectRepository.withDeviceBoard`）见到 null 就直接执行，
     *   一个字段都不动。这样单板块学校 / 老数据完全无感。
     *
     * ## 为什么必须区分「显示」和「操作」
     *
     * 服务端会校验设备归属：拿 A 板块的认证参数去操作属于 B 板块的设备，
     * `downRate/rateOrder` 直接回 **`errorCode=534 设备信息和项目信息不匹配`**。
     *
     * 表现是「收藏的饮水机点开能看、余额也对，但一开阀就报设备信息和项目信息不匹配」——
     * 余额那条路早就借对了板块（[resolve] + `withBoardContext`），
     * 而**真正下发指令的那条路一直用的是当前板块**，两边不是一回事。
     *
     * ## 判据（**完全自适应，不依赖任何具体学校 id**）
     *
     * 1. 设备自己报了 `projectId`，且**与当前板块不同** → 返回它（该切）
     * 2. 设备没报 `projectId` → null（不切，沿用当前板块）
     * 3. 设备报的就是当前板块 → 返回它（调用方会发现相同 → 不切，零开销）
     *
     * ⚠️ **绝不返回任何硬编码的板块 id**。各校的 projectId 完全不同
     * （实测校是 4000/3356，别的学校是别的数字），写死一个值只会在换学校时
     * 把请求打到错误的板块上——那比不切更糟。
     *
     * @param deviceProjectId 设备自报的 `projectId`（`/device/info/mac` 下发）
     * @param currentProjectId 当前正在用的板块（认证参数里那个）
     * @return 该用的板块；**null = 判断不出，保持当前板块别动**
     */
    fun operationBoard(
        deviceProjectId: Long?,
        currentProjectId: Long?
    ): Long? {
        // 设备没报 → 不知道，别乱切
        val device = deviceProjectId?.takeIf { it > 0L } ?: return null
        // 就是当前板块 → 返回它，调用方会判定「相同」而跳过切换
        if (device == currentProjectId) return device
        // 跨板块 → 用设备的板块
        return device
    }
}
