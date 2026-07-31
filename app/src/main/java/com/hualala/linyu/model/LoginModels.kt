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
