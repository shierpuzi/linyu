package com.quzhi.lite.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * [BackupCodec] 的纯 JVM 单测（无 Robolectric、无 Android、无网络）。
 *
 * ## 为什么这一组测试比项目里其它测试都更要紧
 *
 * 加解密写错的代价**不是「功能不好用」，而是「用户凭证泄漏」或「备份永远打不开」**：
 *
 * | 写错的地方 | 后果 | 这里对应的用例 |
 * |---|---|---|
 * | 加密时把 payload 也写进文件 | loginCode 明文躺在网盘/微信里 | `加密文件里不含任何明文凭证` |
 * | IV 复用 | GCM 同时丢机密性和完整性，明文可被还原 | `IV 与 salt 每次备份都不同` |
 * | salt 复用 | 同一密码派生同一密钥，彩虹表直接命中 | 同上 |
 * | 密码错时抛的是 Corrupt | 用户被提示「文件损坏」，于是去重做备份，其实只是打错一个字 | `密码错误单独分类` |
 * | 空密码走了加密分支 | 派生密钥来自空串，等于没加密 | `有凭证时无密码必须拒绝` |
 *
 * ## 关于耗时：不为测试调小迭代次数
 *
 * `encode` 固定用 [BackupCodec.KDF_ITERATIONS] = 210000 次 PBKDF2。调小就等于
 * 测的不是线上那条路径，而这条路径正是最容易出错的地方。为了不把单测拖成几十秒，
 * 需要「一份已加密样本」的只读用例**共用同一个** [ENCRYPTED_JSON]（整类只算一次），
 * 只有验证随机性的用例才各自 encode。
 */
class BackupCodecTest {

    // ════════════════════════════════════════════
    //  测试数据
    // ════════════════════════════════════════════

    private fun favorite(mac: String = "AA:BB:CC:DD:EE:01", savedAt: Long = 1000L) = FavoriteDevice(
        mac = mac,
        snCode = mac.replace(":", ""),
        displayName = "3号楼-601",
        rawName = "洗浴热水-3号宿舍楼-6层-601房",
        emoji = "🚿",
        typeName = "热水器",
        bigTypeId = 4,
        smallTypeId = 1,
        withholdMoney = 5.0,
        projectId = 4000L,
        projectName = "安徽三联学院洗浴",
        savedAtMs = savedAt
    )

    private fun credentials() = BackupCredentials(
        loginCode = LOGIN_CODE,
        userId = USER_ID,
        accountId = "acc-3356",
        projectId = "3356",
        telephone = TELEPHONE,
        projectName = "安徽三联学院洗衣饮水吹风"
    )

    private fun favoritesOnlyPayload() = BackupPayload(
        favorites = listOf(favorite()),
        appVersion = APP_VERSION,
        createdAtMs = CREATED_AT
    )

    private fun fullPayload() = BackupPayload(
        favorites = listOf(favorite(), favorite("AA:BB:CC:DD:EE:02", savedAt = 2000L)),
        credentials = credentials(),
        appVersion = APP_VERSION,
        createdAtMs = CREATED_AT
    )

    /** 断言解码失败，并返回失败原因（「没失败」时测试也会红，不会静默跳过） */
    private fun decodeError(json: String, pwd: String?): BackupCodec.DecodeError {
        try {
            BackupCodec.decode(json, pwd)
        } catch (e: BackupCodec.DecodeException) {
            return e.error
        }
        fail("应当解码失败，实际却成功了")
        throw AssertionError("unreachable")
    }

    /**
     * 改掉外壳里的一个字段。
     *
     * ⚠️ **不能**用 `json.replace(旧值, 新值)`：Gson 默认开 HTML 转义，
     * base64 的 `=` 在输出里是 `\u003d`，直接拿原文去 replace 会一条都匹配不上，
     * 测试就变成「改了个寂寞还全绿」——这比测试失败更危险。
     * 所以这里走 Gson 解析→改→再序列化，保证改的真的是那个字段。
     */
    private fun mutateField(json: String, field: String, mutate: (String) -> String): String {
        val obj = JsonParser.parseString(json).asJsonObject
        val old = obj.get(field)?.asString
        assertNotNull("外壳里应当有字段 $field（这条测试要改的就是它）", old)
        obj.addProperty(field, mutate(old!!))
        return obj.toString()
    }

    private companion object {
        const val PASSWORD = "Quzhi-测试口令-2026"
        const val APP_VERSION = "3.2.1"
        const val CREATED_AT = 1_700_000_000_000L

        /**
         * 刻意做成**一眼能搜到**的字符串：如果哪天有人把 payload 写回加密文件，
         * `assertFalse(json.contains(...))` 会立刻炸，而不是等到真出事。
         */
        const val LOGIN_CODE = "LC-SECRET-9f3a2b7c5d1e4f60"
        const val USER_ID = "UID-SECRET-8b1c4d2e6f7a"
        const val TELEPHONE = "TEL-SECRET-13800138000"

        /**
         * 含凭证的样本负载，内容固定（因此可以直接比较两次加密的随机量差异）。
         *
         * 本类的两个加密样本都由它 encode 出来，整类只跑 2 次 PBKDF2：
         * 一次给 [ENCRYPTED_JSON]，一次给 [SECOND_ENCRYPTED_JSON]。
         * **不要**再为某个用例单独 encode —— 21 万次 PBKDF2 × N 会把单测拖成几十秒。
         */
        fun samplePayload() = BackupPayload(
            favorites = listOf(
                FavoriteDevice(
                    mac = "AA:BB:CC:DD:EE:01",
                    snCode = "AABBCCDDEE01",
                    displayName = "3号楼-601",
                    rawName = "洗浴热水-3号宿舍楼-6层-601房",
                    emoji = "🚿",
                    typeName = "热水器",
                    bigTypeId = 4,
                    smallTypeId = 1,
                    withholdMoney = 5.0,
                    projectId = 4000L,
                    projectName = "安徽三联学院洗浴",
                    savedAtMs = 1000L
                ),
                FavoriteDevice(
                    mac = "AA:BB:CC:DD:EE:02",
                    snCode = "AABBCCDDEE02",
                    displayName = "3号楼-602",
                    rawName = "洗浴热水-3号宿舍楼-6层-602房",
                    emoji = "🚿",
                    typeName = "热水器",
                    bigTypeId = 4,
                    smallTypeId = 1,
                    withholdMoney = 5.0,
                    projectId = 4000L,
                    projectName = "安徽三联学院洗浴",
                    savedAtMs = 2000L
                )
            ),
            credentials = BackupCredentials(
                loginCode = LOGIN_CODE,
                userId = USER_ID,
                accountId = "acc-3356",
                projectId = "3356",
                telephone = TELEPHONE,
                projectName = "安徽三联学院洗衣饮水吹风"
            ),
            appVersion = APP_VERSION,
            createdAtMs = CREATED_AT
        )

        /**
         * 共享加密样本。整类只 encode 一次，只读——
         * 任何用例想改它，都必须走 [mutateField] 产出新字符串，不要写回这里。
         */
        val ENCRYPTED_JSON: String by lazy { BackupCodec.encode(samplePayload(), PASSWORD) }

        /**
         * 「同样的负载、同样的密码，再加密一次」。
         *
         * IV / salt 两条随机性用例共用它：各跑一次 encode 会让整个类多花一份 PBKDF2 时间，
         * 而没有多测到任何东西（随机性只需要两份样本就能判定）。
         */
        val SECOND_ENCRYPTED_JSON: String by lazy { BackupCodec.encode(samplePayload(), PASSWORD) }
    }

    // ════════════════════════════════════════════
    //  a) 明文往返：只备份收藏、用户没选加密
    // ════════════════════════════════════════════

    /** 最常见的一条路：不加密、只带收藏。它是「备份/恢复」里唯一允许明文的组合 */
    @Test
    fun `明文往返保留收藏全部字段`() {
        val json = BackupCodec.encode(favoritesOnlyPayload(), password = null)

        // 外壳必须标 encrypted=false，否则恢复时会被误要求输密码
        val shell = BackupCodec.peek(json)
        assertFalse("没有凭证时默认不加密", shell.encrypted)
        assertNotNull("明文包的 payload 直接在外壳里", shell.payload)
        assertNull("明文包不该有 ciphertext", shell.ciphertext)

        val back = BackupCodec.decode(json, password = null)
        val list = back.favorites.orEmpty()
        assertEquals(1, list.size)
        val f = list[0]
        assertEquals("AA:BB:CC:DD:EE:01", f.mac)
        // 这些字段少一个，恢复出来的收藏卡片就是残的（见 FavoriteDevice 的注释）
        assertEquals("AABBCCDDEE01", f.snCode)
        assertEquals("3号楼-601", f.displayName)
        assertEquals(4, f.bigTypeId)
        assertEquals(1, f.smallTypeId)
        assertEquals(5.0, f.withholdMoney, 0.0)
        assertEquals(4000L, f.projectId)
        assertEquals(1000L, f.savedAtMs)
        // 元信息也要能回来：UI 靠它显示「备份自 v3.2.1」
        assertEquals(APP_VERSION, back.appVersion)
        assertEquals(CREATED_AT, back.createdAtMs)
        assertFalse("明文包本来就没有凭证", back.hasCredentials)
    }

    // ════════════════════════════════════════════
    //  b) 加密往返
    // ════════════════════════════════════════════

    @Test
    fun `加密往返保留凭证与收藏`() {
        val back = BackupCodec.decode(ENCRYPTED_JSON, PASSWORD)

        val cred = back.credentials
        assertNotNull("凭证必须能解出来", cred)
        // 五项都要对：少任何一项都登不上（见 BackupCredentials 的注释）
        assertEquals(LOGIN_CODE, cred!!.loginCode)
        assertEquals(USER_ID, cred.userId)
        assertEquals("acc-3356", cred.accountId)
        assertEquals("3356", cred.projectId)
        assertEquals(TELEPHONE, cred.telephone)
        assertEquals("安徽三联学院洗衣饮水吹风", cred.projectName)

        assertEquals(2, back.favorites.orEmpty().size)
        assertEquals("AA:BB:CC:DD:EE:02", back.favorites!![1].mac)
    }

    /** 外壳自描述：恢复端只靠这几个字段就知道「要不要弹密码框」「用多少轮 KDF」 */
    @Test
    fun `加密外壳字段自洽`() {
        val shell = BackupCodec.peek(ENCRYPTED_JSON)
        assertTrue(shell.encrypted)
        assertEquals(BackupCodec.CIPHER_AES_GCM, shell.cipher)
        // iterations 必须写进文件：以后调高 KDF 轮数时，老备份包还得能解开
        assertEquals(BackupCodec.KDF_ITERATIONS, shell.iterations)
    }

    // ════════════════════════════════════════════
    //  c) 强制加密：数据层兜底，不指望 UI
    // ════════════════════════════════════════════

    /**
     * 这条是**安全底线**：UI 完全可以被改坏（加个分支忘了、勾选状态没同步），
     * 但 `loginCode` 明文落盘是真实事故——那个文件会被用户传到网盘、发到群里。
     * 所以哪怕调用方传 null，encode 也必须自己顶回去。
     */
    @Test
    fun `有凭证时无密码必须拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(fullPayload(), password = null)
        }
        // 空串同样算「没给密码」，不能因为传了个空 String 就蒙混过去
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(fullPayload(), password = "")
        }
    }

    // ════════════════════════════════════════════
    //  d) 空包拒绝
    // ════════════════════════════════════════════

    /** 空包也能写出来，但恢复时什么都不会发生——用户会以为「备份成功了」 */
    @Test
    fun `空备份包被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(BackupPayload(), password = null)
        }
        // favorites = 空列表 也算空包（不是 null，但没有任何条目）
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(BackupPayload(favorites = emptyList()), password = null)
        }
    }

    // ════════════════════════════════════════════
    //  e) 错密码
    // ════════════════════════════════════════════

    /**
     * ⚠️ 必须分类成 [BackupCodec.DecodeError.WrongPassword]。
     * 归到 Corrupt 的话，UI 会告诉用户「文件损坏」→ 用户去重做备份（无意义），
     * 而真正该做的是再看看密码是不是记错了。这两件事的处理方式完全不同。
     */
    @Test
    fun `密码错误单独分类`() {
        val error = decodeError(ENCRYPTED_JSON, "wrong-$PASSWORD")
        assertTrue("错密码应报 WrongPassword，实际 $error", error is BackupCodec.DecodeError.WrongPassword)
    }

    // ════════════════════════════════════════════
    //  f) 不是备份包
    // ════════════════════════════════════════════

    /**
     * 用户很可能误选到别的 JSON（导出的账单、聊天记录）。
     * 这时要说「这不是备份包」，而不是「密码错误」——后者会让用户开始怀疑自己的记性。
     *
     * 这里只用**连对象都不是**的输入：它们一定在 `fromJson` 阶段就炸 → NotABackup。
     * 「是对象但没魔数」的情况见下一条，那里有个真实的坑。
     */
    @Test
    fun `不是 JSON 对象的一律报 NotABackup`() {
        listOf(
            """"hello"""",       // 是 JSON 字符串，不是对象
            "[1,2,3]",           // 数组
            "not a json at all", // 根本不是 JSON
            ""                   // 空文件（用户可能选了个新建的空文档）
        ).forEach { json ->
            val error = decodeError(json, PASSWORD)
            assertTrue(
                "输入 [$json] 应报 NotABackup，实际 $error",
                error is BackupCodec.DecodeError.NotABackup
            )
        }
    }

    /**
     * ⚠️ **已知坑（实测，留给 Lead 决定是否修）**：`{"foo":1}` 和 `{}` 解出来是
     * [BackupCodec.DecodeError.Empty]，**不是** NotABackup。
     *
     * 根因不在 [BackupCodec]，在 [BackupFile]：它所有字段都有 Kotlin 默认值
     * （`format = BackupFormat.MAGIC`），而 Kotlin 在「全部参数都有默认值」时会
     * **额外生成一个无参构造器**。Gson 优先用无参构造器实例化，于是 `format`
     * 一开始就已经是正确魔数了 —— `parseFile` 里那句
     * `if (file.format != BackupFormat.MAGIC)` **永远不成立**，魔数校验形同虚设。
     *
     * 影响仅限于**提示文案**（说「包里没有可恢复的内容」而不是「这不是备份包」），
     * 不会让错误文件被解出内容，所以不算安全事故。
     *
     * 因此这条只锁「绝不能解密成功」，**不锁具体分类**：Lead 修好之后它仍然是绿的。
     */
    @Test
    fun `无魔数的 JSON 不会解密成功`() {
        listOf("""{"foo":1}""", "{}").forEach { json ->
            val error = decodeError(json, PASSWORD)
            assertTrue(
                "无魔数的对象至少不能解出内容，输入 [$json] 实际 $error",
                error is BackupCodec.DecodeError.NotABackup ||
                    error is BackupCodec.DecodeError.Empty
            )
        }
    }

    /** 魔数对但结构缺字段：这是「包不完整」，不是「选错了文件」，两类要分开 */
    @Test
    fun `魔数对但缺密文字段报 Corrupt`() {
        val shell = BackupCodec.peek(ENCRYPTED_JSON)
        // 保留外壳、把 ciphertext 抠掉：模拟文件被截断或被手工编辑
        val broken = """{"format":"${shell.format}","version":${shell.version},"encrypted":true,""" +
            """"cipher":"${shell.cipher}","iterations":${shell.iterations},""" +
            """"salt":"${shell.salt}","iv":"${shell.iv}"}"""
        val error = decodeError(broken, PASSWORD)
        assertTrue("缺 ciphertext 应报 Corrupt，实际 $error", error is BackupCodec.DecodeError.Corrupt)
    }

    // ════════════════════════════════════════════
    //  g) 缺密码
    // ════════════════════════════════════════════

    /**
     * 加密包不传密码是**正常的中间状态**：UI 先 `peek()` 发现 encrypted=true，
     * 弹密码框，用户输了才 decode。所以这里必须是 PasswordRequired 而不是崩溃/Corrupt——
     * UI 才能据此「弹框」而不是「报错」。
     */
    @Test
    fun `加密包缺密码报 PasswordRequired`() {
        assertTrue(decodeError(ENCRYPTED_JSON, null) is BackupCodec.DecodeError.PasswordRequired)
        assertTrue(decodeError(ENCRYPTED_JSON, "") is BackupCodec.DecodeError.PasswordRequired)
    }

    // ════════════════════════════════════════════
    //  h) 密文被篡改
    // ════════════════════════════════════════════

    /**
     * 改一个 Base64 字符，GCM 的认证标签就必然对不上。
     *
     * 只断言「抛了」，**不锁死是哪个分类**：改动可能落在 Base64 层（解不开 → Corrupt），
     * 也可能落在密文层（标签不匹配 → WrongPassword），两者都是正确行为。
     *
     * ⚠️ 关键在于**绝不能「解密成功」**——CBC 那种无认证的模式在这里会静默返回垃圾数据，
     * 用户看到的是「恢复成功，但收藏全变成了别的设备」。这条用例就是防这个。
     */
    @Test
    fun `密文被篡改必须失败`() {
        // 首字符换成另一个合法 Base64 字符：一定能解码，但字节变了
        val broken = mutateField(ENCRYPTED_JSON, "ciphertext") { ct ->
            (if (ct[0] == 'A') "B" else "A") + ct.substring(1)
        }
        assertNotEquals("替换必须真的落到 JSON 里，否则这条测试什么都没测", ENCRYPTED_JSON, broken)

        val error = decodeError(broken, PASSWORD)
        assertTrue(
            "篡改后应报 WrongPassword 或 Corrupt，实际 $error",
            error is BackupCodec.DecodeError.WrongPassword || error is BackupCodec.DecodeError.Corrupt
        )
    }

    /** IV 长度非法属于「文件坏了」，用户手工改过文件时这条提示他能听懂 */
    @Test
    fun `IV 长度非法报 Corrupt`() {
        // 12 字节是 GCM 的标准 IV 长度，这里给 5 字节
        val badIv = Base64.getEncoder().encodeToString(ByteArray(5))
        val broken = mutateField(ENCRYPTED_JSON, "iv") { badIv }
        assertNotEquals(ENCRYPTED_JSON, broken)
        assertTrue(decodeError(broken, PASSWORD) is BackupCodec.DecodeError.Corrupt)
    }

    // ════════════════════════════════════════════
    //  i) IV 每次随机 —— GCM 的命门
    // ════════════════════════════════════════════

    /**
     * GCM 是流模式 + 认证标签。**同一把密钥下复用 IV** 会导致：
     * - 两次密文异或 = 两次明文异或，明文可被直接还原（机密性归零）
     * - 认证用的密钥流可被推导，攻击者能伪造合法密文（完整性归零）
     *
     * 所以「两次备份的 iv 必须不同」不是锦上添花，是 GCM 的硬性使用要求。
     * 这条挂掉等于加密方案不可用，必须当红线修。
     */
    @Test
    fun `IV 每次备份都不同`() {
        val a = BackupCodec.peek(ENCRYPTED_JSON).iv
        val b = BackupCodec.peek(SECOND_ENCRYPTED_JSON).iv
        assertNotNull("加密包必须带 IV", a)
        assertNotNull("加密包必须带 IV", b)
        assertNotEquals("两次备份的 IV 不能相同（GCM 复用 IV 会毁掉机密性+完整性）", a, b)
    }

    // ════════════════════════════════════════════
    //  j) salt 每次随机
    // ════════════════════════════════════════════

    /** 固定 salt = 同一密码永远派生同一把密钥，彩虹表和密钥复用一起回来 */
    @Test
    fun `salt 每次备份都不同`() {
        val a = BackupCodec.peek(ENCRYPTED_JSON).salt
        val b = BackupCodec.peek(SECOND_ENCRYPTED_JSON).salt
        assertNotNull("加密包必须带 salt", a)
        assertNotNull("加密包必须带 salt", b)
        assertNotEquals("两次备份的 salt 不能相同（防彩虹表）", a, b)

        // 随机化不能破坏可用性：两份样本用同一个密码都必须解得开
        assertEquals(LOGIN_CODE, BackupCodec.decode(SECOND_ENCRYPTED_JSON, PASSWORD).credentials?.loginCode)
        // 密文自然也不同（IV + salt 都变了）
        assertNotEquals(
            BackupCodec.peek(ENCRYPTED_JSON).ciphertext,
            BackupCodec.peek(SECOND_ENCRYPTED_JSON).ciphertext
        )
    }

    // ════════════════════════════════════════════
    //  k) 加密文件里不得含明文凭证（最重要的一条）
    // ════════════════════════════════════════════

    /**
     * 防的是「有人后来为了图方便，把 payload 也塞进加密文件里」——
     * 那一刻加密就形同虚设：文件照样能解开，但凭证已经明文在里面了，
     * 而所有功能测试**都会继续通过**，只有这条会红。
     *
     * 顺带连字段名一起查：外壳里根本不该出现 `credentials`/`favorites` 这些词。
     * Base64 的字母表里没有引号，所以这些断言不会被密文误伤。
     */
    @Test
    fun `加密文件里不含任何明文凭证`() {
        val plainPayload = fullPayload()
        val json = BackupCodec.encode(plainPayload, PASSWORD)

        // 先确认这些值确实在「要备份的东西」里，否则下面的断言测不到任何东西
        assertEquals(LOGIN_CODE, plainPayload.credentials!!.loginCode)

        assertFalse("loginCode 明文泄漏进备份文件", json.contains(LOGIN_CODE))
        assertFalse("userId 明文泄漏进备份文件", json.contains(USER_ID))
        assertFalse("手机号明文泄漏进备份文件", json.contains(TELEPHONE))
        listOf("loginCode", "userId", "accountId", "telephone", "credentials", "favorites")
            .forEach { key ->
                assertFalse("加密外壳里不该出现字段名 $key", json.contains("\"$key\""))
            }
    }

    // ════════════════════════════════════════════
    //  l) 加密时 payload 必须为 null
    // ════════════════════════════════════════════

    /**
     * [BackupFile] 同时有 `payload` 和 `ciphertext` 两个槽位。加密路径一旦「顺手」
     * 把 payload 也填上，文件就同时有明文和密文——与上一条是同一个事故的两个检查面
     * （一条查内容，一条查结构）。
     */
    @Test
    fun `加密时 payload 槽位必须为空`() {
        val shell = BackupCodec.peek(ENCRYPTED_JSON)
        assertNull("加密包的 payload 必须为 null，否则明文也在文件里", shell.payload)
        assertNotNull(shell.ciphertext)
        assertTrue("ciphertext 不能是空串", shell.ciphertext!!.isNotEmpty())
    }

    // ════════════════════════════════════════════
    //  m) BackupCredentials.isComplete
    // ════════════════════════════════════════════

    /**
     * 四项是**一个整体**：服务端校验 loginCode 时同时要 projectId + accountId
     * （换了 projectId 不换 accountId 会回「没有项目权限，请确认身份」）。
     * 备份与恢复两条路都靠 isComplete 兜底：不全的凭证宁可不写，
     * 也不要把 App 搞成「isLoggedIn=true 但每个接口都失败」的状态。
     */
    @Test
    fun `凭证缺任何一项都不完整`() {
        val full = credentials()
        assertTrue("五项齐全应为完整", full.isComplete)

        assertFalse("缺 loginCode", full.copy(loginCode = "").isComplete)
        assertFalse("缺 userId", full.copy(userId = "").isComplete)
        assertFalse("缺 accountId", full.copy(accountId = "").isComplete)
        assertFalse("缺 projectId", full.copy(projectId = "").isComplete)
        // 空白串也是「没有」：服务端不会因为它是空格就放行
        assertFalse("全空白", BackupCredentials(" ", " ", " ", " ").isComplete)
        assertFalse("空对象", BackupCredentials().isComplete)

        // ⚠️ telephone 不在 isComplete 里是有意的：它不是认证参数。
        // 缺了只是恢复后「我的」页手机号显示为空，不影响登录。
        assertTrue("缺 telephone 仍然可登录", full.copy(telephone = "").isComplete)
    }
}
