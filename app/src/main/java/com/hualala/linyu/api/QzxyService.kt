package com.hualala.linyu.api

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface QzxyService {

    @FormUrlEncoded
    @POST("/user/login")
    fun login(
        @Field("telephone") telephone: String,
        @Field("password") password: String,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("type") type: Int = 0,
        @Field("version") version: String = "6.5.24"
    ): Call<ResponseBody>

    @GET("/account/wallet")
    fun getWallet(): Call<ResponseBody>

    @GET("/device/info/mac")
    fun getDeviceInfo(
        @Query("macAddress") mac: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/downRate/rateOrder")
    fun downRate(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder")
    fun closeOrder(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/downRateResult")
    fun downRateResult(
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder/result/query")
    fun closeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/consumeOrder/result/query")
    fun consumeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/rateOrder/using")
    fun queryUsing(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/list")
    fun getBillList(
        @Query("month") month: String,
        @Query("billRequestType") billRequestType: Int = 2
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/detail")
    fun getBillDetail(
        @Query("orderId") orderId: String,
        @Query("consumeDate") consumeDate: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/account/useCode/new/status/update")
    fun updateUseCodeStatus(
        @Field("useCodeStatus") status: Int,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/account/useCode/new")
    fun getUseCode(): Call<ResponseBody>

    /**
     * 「换一个」使用码。
     *
     * 换出来的码**还没生效**，要再调 [setUseCode] 才作数；3 分钟内不领取就作废。
     * 每天 20 次额度，返回里的 `remainTimes` 是剩余次数。
     */
    @FormUrlEncoded
    @POST("/account/useCode/new/generate")
    fun generateUseCode(
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 「确定领取」——把 [generateUseCode] 换出来的码正式生效。
     *
     * 这才是真正改服务端当前使用码的那一步，也是**唯一**会改的一步：
     * 只要不调它，换多少次都不影响手上在用的码。
     */
    @FormUrlEncoded
    @POST("/account/useCode/new/set")
    fun setUseCode(
        @Field("useCode") useCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 账号信息：姓名 / 学号 / 校园卡绑定状态。GET 会自动带上认证参数 */
    @GET("/account/info")
    fun getAccountInfo(): Call<ResponseBody>

    /**
     * 一卡通余额与免密支付签约状态。
     *
     * 没签约（signStatus = 0）时服务端不给 amount，调用方要能回退。
     */
    @GET("/settlement/campus/userInfo")
    fun getCampusUserInfo(): Call<ResponseBody>

    /**
     * 更换手机号。
     *
     * [code] 是发到**新手机号**的验证码（`typeId = 5`）。抓包实测：
     * `telephone=新号&typeId=5`，旧号只出现在认证参数 `telPhone` 里——
     * 发到旧号用户根本收不到。
     *
     * 验证码失效时返回 errorCode 29，新号已被注册返回 39。
     */
    @FormUrlEncoded
    @POST("/user/phone/update")
    fun updatePhone(
        @Field("newTelephone") newTelephone: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 修改密码。两个密码都是 MD5 取后 10 位大写，和登录用的是同一套 */
    @FormUrlEncoded
    @POST("/user/password/update")
    fun updatePassword(
        @Field("oldPassword") oldPassword: String,
        @Field("password") password: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 重置密码（手机验证码方式）——**不需要旧密码**。
     *
     * 这是「没设过密码 / 忘了密码」唯一的出路：`/user/password/update` 必须带
     * `oldPassword`，而 SMS 注册的账号根本没有旧密码可用。
     *
     * 验证码要先走 [getVerificationCode] 且 `typeId = 2` 发到**当前绑定手机号**。
     * [password] 同样是 MD5 取后 10 位大写。
     */
    @FormUrlEncoded
    @POST("/user/password/forget")
    fun forgetPassword(
        @Field("password") password: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 项目（学校）信息。`projectName` 就是学校名，用来填个人信息卡片的「学校」，
     * 不用再让用户手输。
     */
    @GET("/project/info/triple")
    fun getProjectInfo(): Call<ResponseBody>

    // ── 短信验证码 ──
    // secret 由 SignUtils.smsSecret(telephone) 按手机号动态计算，不再硬编码
    @GET("/user/verification/code/get")
    fun getVerificationCode(
        @Query("telephone") telephone: String,
        @Query("secret") secret: String,
        @Query("typeId") typeId: Int = 3,
        @Query("platform") platform: Int = 1
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/user/registerAndLogin")
    fun registerAndLogin(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: Int = 5,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = "6.5.24"
    ): Call<ResponseBody>

    companion object {
        const val BASE_URL = "https://v3-api.china-qzxy.cn"
    }
}
