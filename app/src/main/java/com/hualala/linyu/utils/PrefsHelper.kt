package com.hualala.linyu.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hualala.linyu.model.ActiveOrder

object PrefsHelper {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("linyu_prefs", Context.MODE_PRIVATE)
    }

    // ── Auth ──
    var loginCode: String get() = prefs.getString("loginCode", "") ?: ""; set(v) = prefs.edit().putString("loginCode", v).apply()
    var userId: String get() = prefs.getString("userId", "") ?: ""; set(v) = prefs.edit().putString("userId", v).apply()
    var accountId: String get() = prefs.getString("accountId", "") ?: ""; set(v) = prefs.edit().putString("accountId", v).apply()
    var projectId: String get() = prefs.getString("projectId", "") ?: ""; set(v) = prefs.edit().putString("projectId", v).apply()
    var telephone: String get() = prefs.getString("telephone", "") ?: ""; set(v) = prefs.edit().putString("telephone", v).apply()
    var userName: String get() = prefs.getString("userName", "") ?: ""; set(v) = prefs.edit().putString("userName", v).apply()
    var schoolName: String get() = prefs.getString("schoolName", "金华职业技术大学") ?: "金华职业技术大学"; set(v) = prefs.edit().putString("schoolName", v).apply()
    val isLoggedIn: Boolean get() = loginCode.isNotEmpty()
    fun saveAuth(lc: String, uid: String, aid: String, pid: String, phone: String, name: String?) {
        loginCode = lc; userId = uid; accountId = aid; projectId = pid; telephone = phone; userName = name ?: ""
    }
    fun clear() {
        // 保留余额和填写时间
        val bal = manualBalance; val btime = manualBalanceTime
        prefs.edit().clear().apply()
        manualBalance = bal; manualBalanceTime = btime
    }

    // ── Last device ──
    var lastDeviceName: String get() = prefs.getString("lastDeviceName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceName", v).apply()
    var lastDeviceMac: String get() = prefs.getString("lastDeviceMac", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceMac", v).apply()
    var lastDeviceSnCode: String get() = prefs.getString("lastDeviceSnCode", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceSnCode", v).apply()
    var lastDeviceEmoji: String get() = prefs.getString("lastDeviceEmoji", "🚿") ?: "🚿"; set(v) = prefs.edit().putString("lastDeviceEmoji", v).apply()

    // ── Active orders list ──
    fun getActiveOrders(): MutableList<ActiveOrder> {
        val json = prefs.getString("activeOrders", "[]") ?: "[]"
        return try {
            val array = JsonParser().parse(json).asJsonArray
            val result = mutableListOf<ActiveOrder>()
            for (item in array) {
                result.add(gson.fromJson(item, ActiveOrder::class.java))
            }
            result
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveActiveOrders(orders: List<ActiveOrder>) {
        prefs.edit().putString("activeOrders", gson.toJson(orders)).apply()
    }

    fun clearActiveOrders() = prefs.edit().remove("activeOrders").apply()

    var themeMode: String
        get() = prefs.getString("themeMode", "LIGHT") ?: "LIGHT"
        set(value) = prefs.edit().putString("themeMode", value).apply()

    var manualBalance: String get() = prefs.getString("manualBalance", "") ?: ""; set(v) = prefs.edit().putString("manualBalance", v).apply()
    var manualBalanceTime: Long get() = prefs.getLong("manualBalanceTime", 0L); set(v) = prefs.edit().putLong("manualBalanceTime", v).apply()

    // ── Per-device timer ──
    fun getStartedAt(snCode: String): Long = prefs.getLong("startedAt_$snCode", 0L)
    fun setStartedAt(snCode: String, v: Long) = prefs.edit().putLong("startedAt_$snCode", v).apply()
}
