package com.hualala.linyu.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.BillDetail
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.model.OrderStatus
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.model.WalletData
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val gson = Gson()

private suspend fun Call<ResponseBody>.awaitString(): String {
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { this.cancel() }
        this.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        cont.resume(body.string())
                    } else {
                        cont.resumeWithException(Exception("Empty response body"))
                    }
                } else {
                    cont.resumeWithException(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                cont.resumeWithException(t)
            }
        })
    }
}

private fun <T> parse(json: String, dataClass: Class<T>): BaseResponse<T> {
    val obj = JsonParser().parse(json).asJsonObject
    val success = obj.get("success")?.asBoolean ?: false
    val errorCode = obj.get("errorCode")?.asInt ?: 0
    val errorMessage = obj.get("errorMessage")?.let { if (it.isJsonNull) null else it.asString }
    val msg = obj.get("msg")?.let { if (it.isJsonNull) null else it.asString }
    val dataElement = obj.get("data")
    val data: T? = if (dataElement != null && !dataElement.isJsonNull) {
        gson.fromJson(dataElement, dataClass)
    } else null
    return BaseResponse(success, data, errorCode, errorMessage, msg)
}

private fun <T> parseList(json: String, elementClass: Class<T>): BaseResponse<List<T>> {
    val obj = JsonParser().parse(json).asJsonObject
    val success = obj.get("success")?.asBoolean ?: false
    val errorCode = obj.get("errorCode")?.asInt ?: 0
    val errorMessage = obj.get("errorMessage")?.let { if (it.isJsonNull) null else it.asString }
    val msg = obj.get("msg")?.let { if (it.isJsonNull) null else it.asString }
    val dataElement = obj.get("data")
    val data: List<T>? = if (dataElement != null && !dataElement.isJsonNull && dataElement.isJsonArray) {
        val result = mutableListOf<T>()
        for (item in dataElement.asJsonArray) {
            result.add(gson.fromJson(item, elementClass))
        }
        result
    } else null
    return BaseResponse(success, data, errorCode, errorMessage, msg)
}

suspend fun QzxyService.loginSafe(
    telephone: String,
    password: String,
    phoneSystem: String = "android",
    type: Int = 0,
    version: String = "6.5.24"
): BaseResponse<LoginData> = parse(login(telephone, password, phoneSystem, type, version).awaitString(), LoginData::class.java)

suspend fun QzxyService.getWalletSafe(): BaseResponse<WalletData> =
    parse(getWallet().awaitString(), WalletData::class.java)

suspend fun QzxyService.getDeviceInfoSafe(mac: String): BaseResponse<DeviceInfo> =
    parse(getDeviceInfo(mac).awaitString(), DeviceInfo::class.java)

suspend fun QzxyService.downRateSafe(
    xfModel: Int = 0,
    snCode: String,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(downRate(xfModel, snCode, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.closeOrderSafe(
    snCode: String,
    orderNo: String,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(closeOrder(snCode, orderNo, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.queryUsingSafe(
    xfModel: Int = 0,
    snCode: String,
    auth: Map<String, String>
): BaseResponse<OrderStatus> = parse(queryUsing(xfModel, snCode, auth).awaitString(), OrderStatus::class.java)

suspend fun QzxyService.getBillListSafe(
    month: String,
    billRequestType: Int = 2
): BaseResponse<List<BillItem>> = parseList(getBillList(month, billRequestType).awaitString(), BillItem::class.java)

suspend fun QzxyService.getBillDetailSafe(
    orderId: String,
    consumeDate: String
): BaseResponse<BillDetail> = parse(getBillDetail(orderId, consumeDate).awaitString(), BillDetail::class.java)

suspend fun QzxyService.updateUseCodeStatusSafe(
    status: Int,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(updateUseCodeStatus(status, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.getUseCodeSafe(): BaseResponse<UseCodeData> =
    parse(getUseCode().awaitString(), UseCodeData::class.java)

suspend fun QzxyService.generateUseCodeSafe(
    auth: Map<String, String>
): BaseResponse<UseCodeData> = parse(generateUseCode(auth).awaitString(), UseCodeData::class.java)

suspend fun QzxyService.getVerificationCodeSafe(telephone: String): BaseResponse<Unit> =
    parse(getVerificationCode(telephone).awaitString(), Unit::class.java)

suspend fun QzxyService.registerAndLoginSafe(telephone: String, smsCode: String): BaseResponse<LoginData> =
    parse(registerAndLogin(telephone, smsCode).awaitString(), LoginData::class.java)
