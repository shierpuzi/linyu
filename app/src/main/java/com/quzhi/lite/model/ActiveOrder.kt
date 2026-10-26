package com.quzhi.lite.model

data class ActiveOrder(
    val snCode: String,
    val orderNo: String,
    val deviceName: String,
    val deviceMac: String,
    val deviceEmoji: String,
    val preDeduct: Double,
    /**
     * 设备所属板块（`/device/info/mac` 的 `projectId`）。
     *
     * ## 为什么订单要记它
     *
     * 服务端校验设备归属：跨板块设备（例：当前停在洗浴 4000，设备属于洗衣饮水吹风 3356）
     * 用错板块开会回 `errorCode=534 设备信息和项目信息不匹配`。关阀同理——
     * 关不掉比开不了更糟（水还在流、钱还在扣）。
     *
     * 关阀时手上只有 `snCode`（UI 早就不带 `DeviceInfo` 了），所以必须随订单一起存下来，
     * 否则只能靠 `selectedDevice` 猜，而它可能已经被清空或换成别的设备。
     *
     * ⚠️ 老数据没有这个字段，Gson 会留成 null → 关阀时退回当前板块（和修复前行为一致，不会崩）。
     */
    val projectId: Long? = null
)
