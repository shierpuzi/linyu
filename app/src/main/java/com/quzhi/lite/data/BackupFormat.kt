package com.quzhi.lite.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 备份包的数据模型与格式约定。
 *
 * ## 为什么要有单独的「格式」定义
 *
 * 备份文件会被**用户在设备之间搬来搬去**、也可能被手工改。所以：
 * - 必须有 `version` 字段，将来改结构时能分辨新旧
 * - 必须有 `format` 魔数，防止用户误选了一个随便的 JSON 文件
 * - 解密后的校验必须能区分「密码错」和「文件坏了」——两者处理方式完全不同
 *
 * ## 两种负载，可单独也可一起
 *
 * | 负载 | 是否加密 | 说明 |
 * |---|---|---|
 * | [favorites] 收藏列表 | **默认不加密**，用户可选择加密 | 纯本地数据，泄漏也不涉及账号 |
 * | [credentials] 登录凭证 | **强制加密** | loginCode 等价于会话令牌，明文落盘是事故 |
 *
 * ⚠️ 「凭证必须加密」是硬约束：`BackupPayload` 里只要有 credentials，
 * [BackupCodec.encode] 就拒绝以明文写出（见那里的实现）。这不是 UI 层的礼貌，
 * 而是数据层兜底——UI 忘了加密码也不会写出明文凭证。
 */
object BackupFormat {

    /**
     * 格式魔数。
     *
     * 用户很可能误选到别的 JSON（比如导出的账单）。文件里没这个字段就直说
     * 「这不是备份包」，而不是回一句「密码错误」让人去怀疑自己记错了密码。
     */
    const val MAGIC = "quzhi-lite-backup"

    /** 结构版本。加字段不用升；删字段/改语义才升，并保留旧版解析分支 */
    const val VERSION = 1

    /** 文件名建议（用户可在文件选择器里改，这里只是默认值） */
    fun suggestFileName(nowMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "quzhi-lite-backup-${sdf.format(java.util.Date(nowMs))}.json"
    }
}

/**
 * 备份文件的最外层结构。
 *
 * ## 明文模式 vs 加密模式
 *
 * 两种模式**共用同一个 JSON 外壳**，靠 [encrypted] 区分：
 *
 * ```
 * 明文（只备份收藏，且用户没选加密）:
 * { "format":"quzhi-lite-backup", "version":1, "encrypted":false,
 *   "payload": { "favorites":[...] } }
 *
 * 加密（备份凭证，或用户主动选了加密）:
 * { "format":"quzhi-lite-backup", "version":1, "encrypted":true,
 *   "cipher":"AES-256-GCM", "iterations":210000, "salt":"base64", "iv":"base64",
 *   "ciphertext":"base64" }
 * ```
 *
 * 外壳（format/version/encrypted/salt/iv）**永远明文**——不这样的话，
 * 打开文件时连「有没有加密」都不知道，没法提示用户输入密码。
 *
 * ## 为什么 salt / iterations 也写在文件里
 *
 * 密码派生（PBKDF2）需要 salt 才能从同一个密码得到同一把密钥。
 * salt 每次备份都随机生成，随文件一起存——它**不需要保密**，
 * 只要保证「同一密码在不同文件里派生出不同密钥」即可，这是防彩虹表的关键。
 */
data class BackupFile(
    /**
     * ⚠️ **这个字段刻意不给默认值**（其余字段都有）。
     *
     * 原因：所有字段都有默认值时，Kotlin 会额外生成一个**无参构造器**，
     * 而 Gson 优先走无参构造器实例化 —— 于是 `format` 一出生就带着正确魔数，
     * `BackupCodec.parseFile()` 里 `if (file.format != MAGIC)` 那句校验**永远不成立**。
     * 实测：`fromJson("""{"foo":1}""", BackupFile::class.java).format == "quzhi-lite-backup"`。
     *
     * 后果是「用户选错文件」会被提示成「备份包里没有可恢复的内容」，
     * 而不是「这不是趣智Lite的备份包」——不致命但会让人困惑。
     *
     * 去掉默认值后 Kotlin 不再生成无参构造器，Gson 只能走全参构造器，
     * 缺失的 `format` 就是 null，校验才真的能拦住。
     * ⚠️ 别为了「方便」把它改回带默认值。
     */
    @SerializedName("format") val format: String,
    @SerializedName("version") val version: Int = BackupFormat.VERSION,
    @SerializedName("encrypted") val encrypted: Boolean = false,
    /** 加密算法标识。将来换算法时靠它分流；明文时为空 */
    @SerializedName("cipher") val cipher: String? = null,
    /** PBKDF2 迭代次数。**必须存**：以后调高迭代数，旧文件仍要能解开 */
    @SerializedName("iterations") val iterations: Int? = null,
    /** PBKDF2 salt（Base64）。明文不保密，见类注释 */
    @SerializedName("salt") val salt: String? = null,
    /** AES-GCM 的 IV（Base64）。**每次加密都必须重新随机**，复用 IV 会毁掉 GCM 的安全性 */
    @SerializedName("iv") val iv: String? = null,
    /** 密文（Base64）。加密时 payload 为 null */
    @SerializedName("ciphertext") val ciphertext: String? = null,
    /** 明文负载。加密时为 null */
    @SerializedName("payload") val payload: BackupPayload? = null
)

/**
 * 解密后的实际内容。
 *
 * 两个字段都可为 null，分别对应「只备份了收藏」「只备份了凭证」。
 * 两者同时为 null 视为无效备份包（解码时拒绝）。
 */
data class BackupPayload(
    /** 收藏列表。不加密时是明文对象数组，字段与 `FavoriteDevice` 一致 */
    @SerializedName("favorites") val favorites: List<FavoriteDevice>? = null,
    /** 登录凭证。**只要存在就强制加密**，见 [BackupFormat] 的说明 */
    @SerializedName("credentials") val credentials: BackupCredentials? = null,

    /**
     * 备份时的 App 版本（`BuildConfig.VERSION_NAME`）。
     *
     * 只用于恢复时提示「这个包来自更新的版本，可能有字段读不出来」，
     * **不参与任何判断**——不要拿它做兼容门禁，那会让老包永远恢复不了。
     */
    @SerializedName("appVersion") val appVersion: String? = null,
    /** 备份时刻（epoch ms），展示用 */
    @SerializedName("createdAtMs") val createdAtMs: Long = 0L
) {
    val hasFavorites: Boolean get() = !favorites.isNullOrEmpty()
    val hasCredentials: Boolean get() = credentials != null

    /** 空包没有恢复价值，解码时直接判无效 */
    val isEmpty: Boolean get() = !hasFavorites && !hasCredentials
}

/**
 * 登录凭证。
 *
 * ## 为什么这几项必须一起备份
 *
 * [loginCode] 是会话令牌，但服务端校验时**必须同时带上 [projectId] + [accountId]**：
 * 换成别的 projectId 时不换 accountId，接口会回「没有项目权限，请确认身份」。
 * 详见 `model/ProjectModels.kt` 里 `UserProject` 的注释。
 * 所以这四项是一个整体，缺任何一个都登不上，不能拆开存。
 *
 * ⚠️ [telephone] 不是登录必需项（[loginCode] 登录用不到），但它决定了
 * 恢复后界面显示的手机号。没有它，用户会看到一个空号码，比不恢复更困惑。
 */
data class BackupCredentials(
    @SerializedName("loginCode") val loginCode: String = "",
    @SerializedName("userId") val userId: String = "",
    @SerializedName("accountId") val accountId: String = "",
    @SerializedName("projectId") val projectId: String = "",
    @SerializedName("telephone") val telephone: String = "",

    /**
     * 凭证所属账号的板块名（可选，仅用于恢复后展示「恢复的是哪个板块」）。
     * 老备份包可能没有，恢复时按当前板块兜底。
     */
    @SerializedName("projectName") val projectName: String? = null
) {
    /** 四项认证字段齐全才算有效；少一个都登不上，按无效处理 */
    val isComplete: Boolean
        get() = loginCode.isNotBlank() && userId.isNotBlank() &&
            accountId.isNotBlank() && projectId.isNotBlank()
}

/** 备份包的 Gson（宽松解析：字段缺失不抛，由各处的 isComplete/isEmpty 兜底） */
internal val backupGson: Gson by lazy { Gson() }
