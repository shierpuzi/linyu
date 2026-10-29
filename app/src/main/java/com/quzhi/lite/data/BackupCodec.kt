package com.quzhi.lite.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的**编码/解码与加解密**。纯 JVM，不碰 Android，可单测。
 *
 * ## 加密方案（为什么是这几个参数）
 *
 * | 环节 | 选择 | 理由 |
 * |---|---|---|
 * | 密码派生 | **PBKDF2-HMAC-SHA256**，210000 次 | 用户密码强度不可控，只能靠慢哈希加成本。21 万次是 OWASP 2023 对 PBKDF2-SHA256 的建议下限。手机上约 100~300ms，用户点「备份」时感知不到，但暴力破解成本被放大五个数量级 |
 * | 加密 | **AES-256-GCM** | 带认证标签。密码错时**解密直接抛异常**，不需要自己存校验位——存校验位反而会泄漏信息给攻击者 |
 * | IV | 每次加密**随机 12 字节** | GCM 复用 IV 会同时毁掉机密性和完整性。12 字节是 GCM 的标准长度 |
 * | salt | 每次加密**随机 16 字节** | 同一密码在不同文件里派生出不同密钥，防彩虹表。salt 不需要保密，随文件存 |
 *
 * ⚠️ **不要改成 ECB/CBC**：ECB 会泄漏明文模式；CBC 没有完整性校验，
 * 密文被篡改后解密出垃圾数据而不报错，用户会以为"恢复成功但数据全乱"。
 *
 * ## 为什么不用 javax.crypto 之外的东西
 *
 * 没有第三方依赖可用（也不该加）：`javax.crypto` + `java.util.Base64` 就够，
 * 而且这两个在 Android API 26+ 都有。不要再引 BouncyCastle。
 */
object BackupCodec {

    /** 当前使用的算法标识。写进文件，将来换成 Argon2 之类时靠它分流 */
    const val CIPHER_AES_GCM = "AES-256-GCM"

    /**
     * PBKDF2 迭代次数。
     *
     * ⚠️ 调高它会让**老备份包解不开**吗？不会——次数存在文件里（[BackupFile.iterations]），
     * 解码时用文件里的值。所以以后可以放心调高，只影响新备份。
     */
    const val KDF_ITERATIONS = 210_000

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    /** 编码/解码失败的原因。**必须区分**，否则会把「密码错」和「文件坏了」都提示成同一句话 */
    sealed interface DecodeError {
        /** 不是备份包（没有 format 魔数 / 不是合法 JSON） */
        data object NotABackup : DecodeError
        /** 备份包结构损坏（魔数对但字段缺失/非法） */
        data object Corrupt : DecodeError
        /** 需要密码但没给 */
        data object PasswordRequired : DecodeError
        /** 密码错误（GCM 认证失败） */
        data object WrongPassword : DecodeError
        /** 解密成功但里面两个负载都是空的 */
        data object Empty : DecodeError
        /** 其他意外（IO/算法不可用），message 给日志用 */
        data class Failed(val message: String) : DecodeError
    }

    class DecodeException(val error: DecodeError) : Exception(error.toString())

    // ════════════════════════════════════════════
    //  编码
    // ════════════════════════════════════════════

    /**
     * 把负载编码成 JSON 字符串。
     *
     * ## 强制加密规则（数据层兜底，不是 UI 的事）
     *
     * [BackupPayload.credentials] 非空时**必须**有密码，否则抛异常。
     * 这条不在 UI 做，因为 UI 完全可以被改坏（加个分支忘了、勾选状态没同步），
     * 而 loginCode 明文落盘是真实事故：那个文件会被用户传到网盘、发到群里。
     *
     * @param password 空/null 表示不加密（仅当没有凭证时允许）
     */
    fun encode(payload: BackupPayload, password: String?): String {
        require(!payload.isEmpty) { "空备份包没有意义，调用方应先判断" }
        if (payload.hasCredentials) {
            require(!password.isNullOrEmpty()) {
                "备份包含登录凭证，必须提供密码加密——这是硬约束，见 BackupFormat 的注释"
            }
        }

        val file = if (password.isNullOrEmpty()) {
            require(!payload.hasCredentials) { "凭证不允许明文写盘" }
            // ⚠️ format 必须显式传：BackupFile.format 故意没有默认值（见那个字段的注释），
            //    为的是让 Gson 不再走无参构造器，否则魔数校验形同虚设
            BackupFile(format = BackupFormat.MAGIC, encrypted = false, payload = payload)
        } else {
            encryptPayload(payload, password)
        }
        return backupGson.toJson(file)
    }

    private fun encryptPayload(payload: BackupPayload, password: String): BackupFile {
        val plain = backupGson.toJson(payload).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, KDF_ITERATIONS)

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)

        val b64 = Base64.getEncoder()
        return BackupFile(
            format = BackupFormat.MAGIC,
            encrypted = true,
            cipher = CIPHER_AES_GCM,
            iterations = KDF_ITERATIONS,
            salt = b64.encodeToString(salt),
            iv = b64.encodeToString(iv),
            ciphertext = b64.encodeToString(ciphertext),
            // ⚠️ payload 必须留空：否则等于把明文又写回了加密文件里
            payload = null
        )
    }

    // ════════════════════════════════════════════
    //  解码
    // ════════════════════════════════════════════

    /**
     * 解析备份文件。
     *
     * @param password 加密包必传；明文包传 null 即可
     * @throws DecodeException 原因见 [DecodeError]，调用方据此给不同提示
     */
    fun decode(json: String, password: String?): BackupPayload {
        val file = parseFile(json)

        if (!file.encrypted) {
            val p = file.payload
            if (p == null || p.isEmpty) throw DecodeException(DecodeError.Empty)
            return p
        }

        // ── 加密包 ──
        if (password.isNullOrEmpty()) throw DecodeException(DecodeError.PasswordRequired)

        val iterations = file.iterations ?: KDF_ITERATIONS
        if (iterations <= 0) throw DecodeException(DecodeError.Corrupt)

        val b64 = Base64.getDecoder()
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            salt = b64.decode(file.salt ?: throw DecodeException(DecodeError.Corrupt))
            iv = b64.decode(file.iv ?: throw DecodeException(DecodeError.Corrupt))
            ciphertext = b64.decode(file.ciphertext ?: throw DecodeException(DecodeError.Corrupt))
        } catch (e: IllegalArgumentException) {
            // Base64 解不开 = 文件被改过/截断，不是密码问题
            throw DecodeException(DecodeError.Corrupt)
        }
        if (iv.size != IV_BYTES) throw DecodeException(DecodeError.Corrupt)

        val key = deriveKey(password, salt, iterations)
        val plain = try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            // GCM 认证失败：密码错，或密文被改过。
            // 这两种情况无法区分（正是 GCM 的设计目标），统一提示"密码错误"更实用——
            // 用户改文件的可能性远低于打错密码。
            throw DecodeException(DecodeError.WrongPassword)
        } catch (e: Exception) {
            throw DecodeException(DecodeError.Failed(e.javaClass.simpleName + ": " + e.message))
        }

        val payload = try {
            backupGson.fromJson(String(plain, Charsets.UTF_8), BackupPayload::class.java)
        } catch (e: Exception) {
            throw DecodeException(DecodeError.Corrupt)
        }
        if (payload == null || payload.isEmpty) throw DecodeException(DecodeError.Empty)
        return payload
    }

    /** 只看外壳，不解密。给 UI 用：先判断「要不要弹密码框」 */
    fun peek(json: String): BackupFile = parseFile(json)

    private fun parseFile(json: String): BackupFile {
        val file = try {
            backupGson.fromJson(json, BackupFile::class.java)
        } catch (e: Exception) {
            throw DecodeException(DecodeError.NotABackup)
        } ?: throw DecodeException(DecodeError.NotABackup)

        // 魔数不对 → 用户多半选错文件了，明确说「不是备份包」。
        // ⚠️ 这里能真的拦住，全靠 BackupFile.format 没有默认值——见那个字段的注释，
        //    别把它改回带默认值，否则 Gson 走无参构造器，这句永远不成立。
        if (file.format != BackupFormat.MAGIC) throw DecodeException(DecodeError.NotABackup)
        if (file.version > BackupFormat.VERSION) {
            // 新版本写的包，老 App 读不懂。不能猜，直接拒绝并说明原因
            throw DecodeException(DecodeError.Corrupt)
        }
        return file
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val bytes = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()   // 用完清掉，别让它留在堆里等到 GC
        }
        return SecretKeySpec(bytes, "AES")
    }
}
