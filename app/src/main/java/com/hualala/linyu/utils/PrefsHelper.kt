package com.hualala.linyu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hualala.linyu.model.ActiveOrder

object PrefsHelper {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    /** 是否已经 init 过。小组件可能在没有 Activity 的新进程里被唤起，需要一个幂等的判断 */
    val isInitialized: Boolean get() = ::prefs.isInitialized

    fun init(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                "linyu_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // 降级：如果加密初始化失败（如设备不支持），退回明文存储，避免崩溃
            prefs = context.getSharedPreferences("linyu_prefs", Context.MODE_PRIVATE)
        }
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
        try {
            // 注意：EncryptedSharedPreferences 的 edit().clear() 有已知崩溃 bug
            // （内部遍历解密所有 key，遇无法解密的 key 抛 SecurityException）。
            // 改用逐个 remove() 静态 key，每个 remove 只处理单个 key，不会触发全部遍历。
            val editor = prefs.edit()
            editor.remove("loginCode").remove("userId").remove("accountId")
                .remove("projectId").remove("telephone").remove("userName")
                .remove("lastDeviceName").remove("lastDeviceMac").remove("lastDeviceSnCode")
                .remove("lastDeviceEmoji").remove("boundRoom").remove("activeOrders")
            // startedAt_/autoDiscon_ 的 key 是「前缀 + snCode」，不是固定名，
            // 原来写成 remove("startedAt_") 是删不掉的——换个账号登录后，
            // 上一任的计时器还在，界面会显示莫名其妙的已用时长。这里按前缀扫掉。
            prefs.all.keys
                .filter { it.startsWith("startedAt_") || it.startsWith("autoDiscon_") }
                .forEach { editor.remove(it) }
            editor.apply()
        } catch (_: Exception) {
            // 兜底：即使加密存储清理异常也不崩溃，登录态由内存态管理
        }
        manualBalance = bal; manualBalanceTime = btime
    }

    // ── Last device ──
    var lastDeviceName: String get() = prefs.getString("lastDeviceName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceName", v).apply()
    var lastDeviceMac: String get() = prefs.getString("lastDeviceMac", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceMac", v).apply()
    var lastDeviceSnCode: String get() = prefs.getString("lastDeviceSnCode", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceSnCode", v).apply()
    var lastDeviceEmoji: String get() = prefs.getString("lastDeviceEmoji", "🚿") ?: "🚿"; set(v) = prefs.edit().putString("lastDeviceEmoji", v).apply()

    // ── 绑定的寝室（设备筛选关键词） ──
    var boundRoom: String get() = prefs.getString("boundRoom", "") ?: ""; set(v) = prefs.edit().putString("boundRoom", v).apply()

    // ── 「我的」页面卡片顺序 / 已隐藏卡片（逗号分隔的枚举名） ──
    var userCardOrder: String get() = prefs.getString("userCardOrder", "") ?: ""; set(v) = prefs.edit().putString("userCardOrder", v).apply()
    var userHiddenCards: String get() = prefs.getString("userHiddenCards", "") ?: ""; set(v) = prefs.edit().putString("userHiddenCards", v).apply()

    // ── 自定义背景：两套独立配置（scope = home / shower），与深浅模式完全无关 ──
    fun bgGetBool(scope: String, name: String, def: Boolean) = prefs.getBoolean("bg_${scope}_$name", def)
    fun bgPutBool(scope: String, name: String, v: Boolean) = prefs.edit().putBoolean("bg_${scope}_$name", v).apply()
    fun bgGetFloat(scope: String, name: String, def: Float) = prefs.getFloat("bg_${scope}_$name", def)
    fun bgPutFloat(scope: String, name: String, v: Float) = prefs.edit().putFloat("bg_${scope}_$name", v).apply()
    fun bgGetInt(scope: String, name: String, def: Int) = prefs.getInt("bg_${scope}_$name", def)
    fun bgPutInt(scope: String, name: String, v: Int) = prefs.edit().putInt("bg_${scope}_$name", v).apply()

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

    // ── 自动关停倒计时（以毫秒时间戳持久化，App 重启后可恢复） ──
    private fun autoDisconKey(snCode: String) = "autoDiscon_$snCode"

    /** 剩余秒数，依据持久化的截止时间戳计算；无记录返回 0 */
    fun getAutoDisconRemain(snCode: String): Int {
        val deadline = prefs.getLong(autoDisconKey(snCode), 0L)
        if (deadline <= 0L) return 0
        val remain = ((deadline - System.currentTimeMillis()) / 1000).toInt()
        return if (remain > 0) remain else 0
    }

    /** 设置剩余秒数，转换为截止时间戳保存 */
    fun setAutoDisconRemain(snCode: String, seconds: Int) {
        if (seconds <= 0) {
            prefs.edit().remove(autoDisconKey(snCode)).apply()
        } else {
            prefs.edit().putLong(autoDisconKey(snCode), System.currentTimeMillis() + seconds * 1000L).apply()
        }
    }

    fun clearAutoDiscon(snCode: String) = prefs.edit().remove(autoDisconKey(snCode)).apply()
}
