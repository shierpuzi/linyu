package com.hualala.linyu.model

import com.google.gson.annotations.SerializedName

data class BaseResponse<T>(
    val success: Boolean,
    val data: T?,
    @SerializedName("errorCode") val errorCode: Int = 0,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    val msg: String? = null
) {
    val displayMessage: String?
        get() = errorMessage ?: msg
}

data class LoginData(
    val userId: Long,
    val loginCode: String,
    val userAccount: UserAccount
)

data class UserAccount(
    val accountId: Long,
    val name: String,
    val projectId: Long,
    val accountRealMoney: Double
)

data class WalletData(
    val accountRealMoney: Double,
    val accountGivenMoney: Double,
    val money: String
)

data class OrderStatus(
    val orderNo: String? = null,
    val state: Int? = null,
    val snCode: String? = null,
    val isOwner: Boolean = true
)

data class BillItem(
    val consumeBillDTO: BillDTO
)

data class BillDTO(
    val orderId: String,
    val consumeDate: String,
    val consumeMoney: String,
    val description: String
) {
    /** 设备名：某小区 3号楼南 320房 */
    val displayDesc: String get() {
        val name = description.substringAfter(":")
        return if (name.isNotEmpty()) DeviceInfo.formatDeviceName(name) else description
    }

    /** 设备类型标签：卫生间热水器 / 洗手台热水器 */
    val deviceTypeLabel: String get() {
        val name = description.substringAfter(":")
        return when {
            name.startsWith("洗手台") || description.contains("洗手台", ignoreCase = true) -> "洗手台热水器"
            else -> "卫生间热水器"
        }
    }
}

data class UseCodeData(
    val useCode: String = "",
    val useCodeStatus: Int = 0,
    val useCodeRandom: String = "",
    val resetAvailability: Int = 0,
    val resetAvailabilityWarMark: String? = null
)

data class BillDetail(
    val orderId: String? = null,
    val consumeDate: String? = null,
    val consumeMoney: Double = 0.0,
    val description: String? = null,
    val deviceSnCode: String? = null,
    val orderNo: String? = null,
    val preDeductMoney: Double = 0.0
)

/**
 * 开阀结果查询 (/order/tcpDevice/query/downRateResult)
 *
 * 用于确认 downRate 开阀是否真正成功，同时可携带 autoDisConTime（自动关停秒数）。
 */
data class DownRateResult(
    /** 订单号 */
    val orderNo: String? = null,
    /** 自动关停时间（秒），如 600 = 10 分钟 */
    val autoDisConTime: Int? = null,
    /** 状态码，0 通常表示开阀成功 */
    val state: Int? = null,
    val result: Int? = null,
    /** 预扣金额 */
    val preDeductMoney: Double? = null,
    val preDeductMoneySend: Double? = null,
    /** 费率 */
    val rate: Double? = null,
    /** 设备序列号 */
    val snCode: String? = null
)

/**
 * 关阀结果查询 (/order/tcpDevice/closeOrder/result/query)
 *
 * 用于确认 closeOrder 是否真正执行成功。服务器对成功/失败的字段
 * 命名可能因学校而异，故用 @SerializedName 做多字段容错。
 */
data class CloseOrderResult(
    /** 订单号 */
    val orderNo: String? = null,
    /** 订单状态：1=使用中，0=已关闭（部分服务器用 state） */
    val state: Int? = null,
    /** 订单状态（部分服务器用 status） */
    val status: Int? = null,
    /** 操作结果码，0 通常表示成功 */
    val result: Int? = null,
    /** 最终消费金额（元，数字形式） */
    val consumeMoney: Double? = null,
    /** 最终消费金额（元，字符串形式，部分学校返回） */
    @SerializedName("consumeMoneyStr") val consumeMoneyStr: String? = null,
    /** 结算时间 */
    val consumeTime: String? = null,
    /** 设备序列号 */
    val deviceSnCode: String? = null
)
