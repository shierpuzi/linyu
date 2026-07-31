package com.hualala.linyu.api

import com.hualala.linyu.BuildConfig
import com.hualala.linyu.utils.PrefsHelper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {
    private var loginCode: String = ""
    private var userId: String = ""
    private var accountId: String = ""
    private var projectId: String = ""
    private var telephone: String = ""

    /** 从 SharedPreferences 恢复登录态 */
    fun restoreFromPrefs(): Boolean {
        loginCode = PrefsHelper.loginCode
        userId = PrefsHelper.userId
        accountId = PrefsHelper.accountId
        projectId = PrefsHelper.projectId
        telephone = PrefsHelper.telephone
        return loginCode.isNotEmpty()
    }

    fun updateAuth(
        loginCode: String,
        userId: String,
        accountId: String,
        projectId: String,
        telephone: String
    ) {
        this.loginCode = loginCode
        this.userId = userId
        this.accountId = accountId
        this.projectId = projectId
        this.telephone = telephone
    }

    fun authFields(): Map<String, String> = mapOf(
        "loginCode" to loginCode,
        "userId" to userId,
        "accountId" to accountId,
        "projectId" to projectId,
        "telephone" to telephone,
        "telPhone" to telephone,
        "phoneSystem" to "android",
        "version" to "6.5.24"
    )

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        if (loginCode.isNotEmpty() && originalRequest.method == "GET" && !originalUrl.encodedPath.contains("verification")) {
            val urlBuilder = originalUrl.newBuilder()
                .addQueryParameter("loginCode", loginCode)
                .addQueryParameter("userId", userId)
                .addQueryParameter("accountId", accountId)
                .addQueryParameter("projectId", projectId)
                .addQueryParameter("telephone", telephone)
                .addQueryParameter("phoneSystem", "android")
                .addQueryParameter("version", "6.5.24")

            chain.proceed(originalRequest.newBuilder().url(urlBuilder.build()).build())
        } else {
            chain.proceed(originalRequest)
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .apply {
            if (com.hualala.linyu.BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
            addInterceptor(Interceptor { chain ->
                val req = chain.request()
                android.util.Log.i("LinYu", "→ ${req.method} ${req.url}")
                val resp = chain.proceed(req)
                val body = resp.peekBody(Long.MAX_VALUE).string()
                android.util.Log.i("LinYu", "← ${resp.code} ${req.url} body=${body.take(500)}")
                resp
            })
            }
        }
        .build()

    val apiService: QzxyService = Retrofit.Builder()
        .baseUrl(QzxyService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(QzxyService::class.java)
}
