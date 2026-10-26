package com.quzhi.lite.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.quzhi.lite.data.PendingSettlement
import com.quzhi.lite.data.BillHistoryCache
import com.quzhi.lite.data.shouldPreserveForRecovery
import com.quzhi.lite.model.ActiveOrder
import com.quzhi.lite.model.BillItem
import com.quzhi.lite.model.UserProject

object PrefsHelper {
    /** 加密存储的文件名 */
    private const val FILE_ENCRYPTED = "linyu_prefs"

    /**
     * 降级用的明文文件名。
     *
     * ⚠️ **必须和加密那份不同名**。以前两者都叫 `linyu_prefs`，于是：
     * - 加密初始化失败退回明文时，写的是**同名文件**，上层完全无感知
     * - 更要命的是反过来——某次加密又初始化成功了，它会去读**同一份明文文件**，
     *   发现格式不对（不是加密格式）后要么抛异常、要么当成空文件，
     *   用户看到的是「登录态莫名其妙没了」
     *
     * 分开之后两种情况各自独立：加密能用了就读加密那份（哪怕它是空的、
     * 需要重新登录），明文那份就静静躺在那儿不再被碰。
     *
     * ⚠️ **升级影响**：极少数设备上（老版本曾降级过）`linyu_prefs` 里其实是**明文**。
     * 这次改动之后，`EncryptedSharedPreferences` 会去解它、解不开，于是落到新的空文件上，
     * 那台设备会**要求重新登录一次**。
     *
     * 这是**有意不做迁移**的：那份数据本来就是明文躺在一个「看起来已加密」的文件里，
     * 把它原样抄进新的明文文件只会让这个状态延长。宁可让用户重登一次。
     */
    private const val FILE_PLAIN = "linyu_prefs_plain"

    private lateinit var prefs: SharedPreferences

    /** 本次运行是否退到了明文存储。给「我的 → 关于」之类的诊断位置留的观察口 */
    @Volatile var usingPlaintextFallback: Boolean = false
        private set

    private val gson = Gson()

    /** 是否已经 init 过。小组件可能在没有 Activity 的新进程里被唤起，需要一个幂等的判断 */
    val isInitialized: Boolean get() = ::prefs.isInitialized

    fun init(context: Context) {
        val encrypted = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密初始化失败（设备不支持 / keystore 损坏）不能崩，否则 App 直接起不来。
            // 但也不能像以前那样**悄悄**退回一个同名明文文件——那是「看起来在加密、其实没有」，
            // 而且下次加密恢复后会读到格式不对的同名文件。
            null
        }

        if (encrypted != null) {
            prefs = encrypted
            usingPlaintextFallback = false
        } else {
            prefs = context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            usingPlaintextFallback = true
            // 日志脱敏只认手机号之类，这里只写一句「发生了降级」，不带任何凭证
            AppLogger.w("加密存储初始化失败，本次运行使用明文存储（$FILE_PLAIN）")
        }
    }

    // ── Auth ──
    var loginCode: String get() = prefs.getString("loginCode", "") ?: ""; set(v) = prefs.edit().putString("loginCode", v).apply()
    var userId: String get() = prefs.getString("userId", "") ?: ""; set(v) = prefs.edit().putString("userId", v).apply()
    var accountId: String get() = prefs.getString("accountId", "") ?: ""; set(v) = prefs.edit().putString("accountId", v).apply()
    var projectId: String get() = prefs.getString("projectId", "") ?: ""; set(v) = prefs.edit().putString("projectId", v).apply()
    var telephone: String get() = prefs.getString("telephone", "") ?: ""; set(v) = prefs.edit().putString("telephone", v).apply()
    var userName: String get() = prefs.getString("userName", "") ?: ""; set(v) = prefs.edit().putString("userName", v).apply()

    /**
     * 学号（`/account/info` 的 idCardNumber）。
     *
     * 登录响应里其实也有（`userAccount.idCardNumber`），但我们的模型以前没解析，
     * 所以老用户的 Prefs 里是空的——拉一次 /account/info 就会补上。
     */
    var userStudentId: String get() = prefs.getString("userStudentId", "") ?: ""
        set(v) = prefs.edit().putString("userStudentId", v).apply()

    /**
     * 一卡通**真实余额**（`/settlement/campus/userInfo` 的 amount）。
     *
     * 空串 = 拿不到，两种情况：学生没签约免密支付、或者压根还没拉过。
     * 这时界面回退到「初始余额 − 账单消费」的本地估算。
     */
    var campusBalance: String get() = prefs.getString("campusBalance", "") ?: ""
        set(v) = prefs.edit().putString("campusBalance", v).apply()

    var campusBalanceTime: Long get() = prefs.getLong("campusBalanceTime", 0L)
        set(v) = prefs.edit().putLong("campusBalanceTime", v).apply()

    /**
     * 趣智校园**钱包**余额（`/account/wallet` 的 `money`，单位已是元）。
     *
     * ⚠️ 这不是一卡通，是趣智校园自己的账户余额。两者是两个不同的东西：
     *
     * | 接口 | 含义 | 什么情况下拿得到 |
     * |---|---|---|
     * | `/settlement/campus/userInfo` | **一卡通**（校园卡） | 学校接入了校园卡免密支付 |
     * | `/account/wallet` | **趣智校园钱包** | 几乎所有学校都有 |
     *
     * 之前只认一卡通，于是「没接一卡通的学校」（如某工商职业学院，该接口返回
     * `errorCode 12「无效支付配置」`）会一路回退到**用户手填的估算值**——
     * 而钱包余额明明就在手边：它每 25 秒被挤号心跳拉一次，只是数字从来没被用过。
     *
     * 现在作为一卡通拿不到时的第二来源，见 [com.quzhi.lite.data.BalanceEstimator]。
     */
    var walletBalance: String get() = prefs.getString("walletBalance", "") ?: ""
        set(v) = prefs.edit().putString("walletBalance", v).apply()
    var schoolName: String get() = prefs.getString("schoolName", "") ?: ""; set(v) = prefs.edit().putString("schoolName", v).apply()
    val isLoggedIn: Boolean get() = loginCode.isNotEmpty()

    // ── 多板块（项目）──
    //
    // 一个手机号可能绑定多个**独立项目**（4000 洗浴 / 3356 洗衣饮水吹风），
    // 各有自己的 accountId、钱包、账单。loginCode 全局一套，但 projectId 与
    // accountId 必须成对切换——见 data/ProjectRepository.kt。

    /**
     * 用户**选中的**项目 id。
     *
     * 和上面那个 [projectId] 是两件事：
     * - [projectId] 是认证参数，永远是"当前真正在用的"那一个
     * - 这个是"用户想要的那个"，重新登录后用它把板块还原回去
     *
     * 分开存是为了**不破坏老用户**：升级后本项为空，就退回登录响应里的
     * projectId（= 他原来在用的那个），行为完全不变。
     */
    var currentProjectId: String get() = prefs.getString("currentProjectId", "") ?: ""
        set(v) = prefs.edit().putString("currentProjectId", v).apply()

    /** `/user/project/list` 的原始 JSON 快照。冷启动时先用它渲染板块列表，不用等网络 */
    var projectListJson: String get() = prefs.getString("projectListJson", "") ?: ""
        set(v) = prefs.edit().putString("projectListJson", v).apply()

    /**
     * 已知的 `projectId → 项目名`（JSON）。
     *
     * ⚠️ `/project/info/triple` 只能问**当前项目**的名字，而切换列表要显示所有板块的名字，
     * 所以每切到一个板块就把名字记下来。没去过的板块只能显示「项目 3356」——
     * `ponytail:` 代价是首次使用有占位文案，换来的是绝不为取名字临时改全局认证状态
     * （那会让并发的钱包/账单请求打到错误的板块上，真的会串号）。
     */
    var projectNamesJson: String get() = prefs.getString("projectNamesJson", "") ?: ""
        set(v) = prefs.edit().putString("projectNamesJson", v).apply()

    fun cachedProjects(): List<UserProject> = runCatching {
        JsonParser().parse(projectListJson).asJsonArray.map {
            gson.fromJson(it, UserProject::class.java)
        }
    }.getOrDefault(emptyList())

    fun saveCachedProjects(list: List<UserProject>) {
        projectListJson = gson.toJson(list)
    }

    fun projectNames(): Map<Long, String> = runCatching {
        JsonParser().parse(projectNamesJson).asJsonObject.entrySet().mapNotNull { e ->
            e.key.toLongOrNull()?.let { it to e.value.asString }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun rememberProjectName(projectId: Long, name: String) {
        if (projectId <= 0L || name.isBlank()) return
        val map = projectNames().toMutableMap()
        if (map[projectId] == name) return
        map[projectId] = name
        projectNamesJson = gson.toJson(map.mapKeys { it.key.toString() })
    }

    /**
     * 切换板块时**必须**清掉的缓存。
     *
     * 这些数据全是"按项目维度"的，不清就会串号——最典型的是账单缓存：
     * [BillHistoryCache] 的 key 是 `billCache_<userId>`，两个板块**同一个 userId**，
     * 不清的话切过去先显示上一个板块的账单，余额估算跟着一起错。
     *
     * ⚠️ **不清** pendingSettlements / activeOrders / consume_* / startedAt_*：
     * 那些是在途的钱和设备计时，丢了就找不回来了。所以调用方必须先确认没有进行中的订单。
     */
    fun clearProjectScopedCache() {
        try {
            val editor = prefs.edit()
            editor.remove("walletBalance")
                .remove("campusBalance").remove("campusBalanceTime")
                .remove("schoolName")
                // 小组件快照是上个板块的设备/账单
                .remove("widgetNearbyJson").remove("widgetNearbyTime")
                .remove("widgetBillJson").remove("widgetBillTime")
                // 占用标记属于上个板块的设备
                .remove("occupiedSnCode").remove("occupiedAtMs")
                // 「上次使用设备」整个是项目内的概念
                .remove("lastDeviceName").remove("lastDeviceMac").remove("lastDeviceSnCode")
                .remove("lastDeviceRawName").remove("lastDeviceEmoji")
                .remove("lastDeviceWithholdMoney").remove("lastDeviceTypeName")
            // billCache_* 的 key 只带 userId（两个板块相同），必须逐条清
            prefs.all.keys.filter { it.startsWith("billCache_") }.forEach { editor.remove(it) }
            // 按套餐计费的单（洗衣机/吹风机）：切板块后不该带到另一个板块
            // ⚠️ 但**只清已完成/无效的**是不现实的——调用方必须先确认没有在途套餐单
            //    （见 ProjectRepository.switchTo 的前置检查）
            prefs.all.keys.filter { it.startsWith("mode_order_") }.forEach { editor.remove(it) }
            editor.apply()
        } catch (_: Exception) {
            // 同 clear()：加密存储偶发异常时不崩，界面状态由调用方自己复位
        }
    }
    fun saveAuth(lc: String, uid: String, aid: String, pid: String, phone: String, name: String?) {
        loginCode = lc; userId = uid; accountId = aid; projectId = pid; telephone = phone; userName = name ?: ""
    }

    /**
     * 通用字符串读写。给 `data/ModeOrderController` 这类「自己管键名」的组件用。
     *
     * ⚠️ [writeRaw] 收到**空串表示删除**——不删的话 `prefs.all` 里会留一堆空值键，
     * 而且「没有值」和「值是空串」两种状态会让调用方多写一层判断。
     */
    fun readRaw(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: Exception) {
        null
    }

    fun writeRaw(key: String, value: String) {
        try {
            if (value.isEmpty()) {
                prefs.edit().remove(key).apply()
            } else {
                prefs.edit().putString(key, value).apply()
            }
        } catch (_: Exception) {
            // 加密存储偶发异常时不崩：丢一条本地记录不致命，
            // ModeOrderController 的 pendingOrder() 返回 null 会退化成「重新查服务端」
        }
    }
    fun clear(preserveActiveRecovery: Boolean = false) {
        // 保留余额和填写时间
        val bal = manualBalance; val btime = manualBalanceTime
        try {
            // 注意：EncryptedSharedPreferences 的 edit().clear() 有已知崩溃 bug
            // （内部遍历解密所有 key，遇无法解密的 key 抛 SecurityException）。
            // 改用逐个 remove() 静态 key，每个 remove 只处理单个 key，不会触发全部遍历。
            val editor = prefs.edit()
            editor.remove("loginCode").remove("userId").remove("accountId")
                .remove("projectId").remove("telephone").remove("userName")
                .remove("boundRoom")
                // 板块列表和当前板块是**账号级**的，换账号必须重拉
                .remove("currentProjectId").remove("projectListJson").remove("projectNamesJson")
                .remove("lastConsumeMoney").remove("lastConsumeTime")
                .remove("widgetNearbyJson").remove("widgetBillJson")
                .remove("widgetNearbyTime").remove("widgetBillTime")
                .remove("pendingSettlements")
                // 占用状态是跟设备走的，换账号后不适用
                .remove("occupiedSnCode")
                // 学号和余额是账号数据，换账号必须清掉，否则会显示上一任的
                .remove("userStudentId").remove("campusBalance").remove("campusBalanceTime")
                .remove("walletBalance")
            prefs.all.keys
                .filter {
                    it.startsWith("consume_") ||
                        it.startsWith("billCache_") ||
                        (!preserveActiveRecovery && shouldPreserveForRecovery(it))
                }
                .forEach { editor.remove(it) }
            editor.apply()
        } catch (_: Exception) {
            // 兜底：即使加密存储清理异常也不崩溃，登录态由内存态管理
        }
        manualBalance = bal; manualBalanceTime = btime
    }

    // ── Last device ──
    var lastDeviceName: String get() = prefs.getString("lastDeviceName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceName", v).apply()

    /**
     * 上次使用设备的**原始**设备名（`DeviceInfo.deviceName`），只用来做寝室筛选。
     *
     * ⚠️ 不能拿 [lastDeviceName] 去筛寝室。[lastDeviceName] 存的是
     * `DeviceInfo.displayName`，也就是 [com.quzhi.lite.model.DeviceInfo.formatDeviceName]
     * 处理过的**显示名**——它会把 `-3层-` 里的楼层去掉、把结尾的「洗手台」换成「房」。
     *
     * 而寝室键是从**原始名**取出来的（`某小区-1栋-1层-101`）。两边算出来的键
     * 一个带楼层一个不带，永远不相等：绑定寝室后小组件会一直显示「请先选择设备」，
     * 哪怕这台设备就在绑定的寝室里。实测就是这么挂的。
     *
     * 老用户没有这个字段（空串），调用方回退到 [lastDeviceName]——用老的短绑定值
     * （`320房`）时靠 `endsWith` 兜底仍然能匹配上。
     */
    var lastDeviceRawName: String get() = prefs.getString("lastDeviceRawName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceRawName", v).apply()

    var lastDeviceMac: String get() = prefs.getString("lastDeviceMac", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceMac", v).apply()
    var lastDeviceSnCode: String get() = prefs.getString("lastDeviceSnCode", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceSnCode", v).apply()
    var lastDeviceEmoji: String get() = prefs.getString("lastDeviceEmoji", "🚿") ?: "🚿"; set(v) = prefs.edit().putString("lastDeviceEmoji", v).apply()

    /**
     * 上次使用设备**所属的板块**（`DeviceInfo.projectId`）。
     *
     * 存它是为了让「设备操作」能在正确的板块下执行：服务端校验设备归属，
     * 拿错板块开会回 `errorCode=534 设备信息和项目信息不匹配`。
     * 桌面小组件那条路手上只有 `snCode`，拿不到 `DeviceInfo`，
     * 只能靠这里记下的值；`ActiveOrder.projectId` 也是从它回落。
     *
     * ⚠️ 0 / 空串都表示「不知道」，调用方应当退回当前板块。
     */
    var lastDeviceProjectId: Long?
        get() = prefs.getLong("lastDeviceProjectId", 0L).takeIf { it > 0L }
        set(v) = prefs.edit().putLong("lastDeviceProjectId", v ?: 0L).apply()

    // ── 绑定的寝室（设备筛选关键词） ──
    var boundRoom: String get() = prefs.getString("boundRoom", "") ?: ""; set(v) = prefs.edit().putString("boundRoom", v).apply()

    // ── 已确认需要蓝牙开阀的设备 ──
    //
    // 服务端**不提供**任何能区分"云端表 / 蓝牙表"的字段：
    //   · `smallTypeId` —— 两校的 4G 表也都是 1，无区分能力
    //   · `isBle`       —— 服务端压根不返回
    // 参考项目也没做这个判断（它本身就是纯蓝牙客户端）。
    //
    // 所以只能**让用户来告诉 App**：云端开阀返回 306 时问一句，
    // 用户选"是"就把 snCode 记在这里，以后直接走蓝牙。
    //
    // ⚠️ 记的是 **snCode** 而不是设备名或 MAC：
    //   · 设备名会变（各校命名不同），MAC 有虚拟/真实之分（见 DeviceInfo.realMac）
    //   · snCode 是开阀接口真正用的标识，最稳
    private fun bleDevicesKey() = "bleDevices"

    /** 该设备是否已被用户确认需要蓝牙开阀 */
    fun isBleDevice(snCode: String): Boolean {
        if (snCode.isEmpty()) return false
        val raw = prefs.getString(bleDevicesKey(), "") ?: ""
        if (raw.isEmpty()) return false
        return raw.split(',').any { it == snCode }
    }

    /** 记住「这台需要蓝牙」（用户确认后、或蓝牙开阀成功后调用） */
    fun markBleDevice(snCode: String) {
        if (snCode.isEmpty()) return
        val cur = (prefs.getString(bleDevicesKey(), "") ?: "")
            .split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (cur.add(snCode)) {
            prefs.edit().putString(bleDevicesKey(), cur.joinToString(",")).apply()
            AppLogger.i("已记住蓝牙设备：$snCode")
        }
    }

    /**
     * 撤销「这台需要蓝牙」的标记。
     *
     * 用在**确定猜错**的时候：蓝牙连上了但找不到 FF00/FF01/FF02 服务，
     * 说明这台设备根本没有水控协议——不是蓝牙表。自动撤销，
     * 免得以后每次都白试一遍蓝牙。
     */
    fun unmarkBleDevice(snCode: String) {
        if (snCode.isEmpty()) return
        val cur = (prefs.getString(bleDevicesKey(), "") ?: "")
            .split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (cur.remove(snCode)) {
            prefs.edit().putString(bleDevicesKey(), cur.joinToString(",")).apply()
            AppLogger.i("已撤销蓝牙设备标记（找不到水控服务）：$snCode")
        }
    }

    // ── 「我的」页面卡片顺序 / 已隐藏卡片（逗号分隔的枚举名） ──
    var userCardOrder: String get() = prefs.getString("userCardOrder", "") ?: ""; set(v) = prefs.edit().putString("userCardOrder", v).apply()
    var userHiddenCards: String get() = prefs.getString("userHiddenCards", "") ?: ""; set(v) = prefs.edit().putString("userHiddenCards", v).apply()
    var userProfileCardOrder: String get() = prefs.getString("userProfileCardOrder", "") ?: ""; set(v) = prefs.edit().putString("userProfileCardOrder", v).apply()
    var userProfileHiddenCards: String get() = prefs.getString("userProfileHiddenCards", "") ?: ""; set(v) = prefs.edit().putString("userProfileHiddenCards", v).apply()
    var userSettingsCardOrder: String get() = prefs.getString("userSettingsCardOrder", "") ?: ""; set(v) = prefs.edit().putString("userSettingsCardOrder", v).apply()
    var userSettingsHiddenCards: String get() = prefs.getString("userSettingsHiddenCards", "") ?: ""; set(v) = prefs.edit().putString("userSettingsHiddenCards", v).apply()

    // ── 自定义背景：两套独立配置（scope = home / shower），与深浅模式完全无关 ──
    fun bgGetBool(scope: String, name: String, def: Boolean) = prefs.getBoolean("bg_${scope}_$name", def)
    fun bgPutBool(scope: String, name: String, v: Boolean) = prefs.edit().putBoolean("bg_${scope}_$name", v).apply()
    fun bgGetFloat(scope: String, name: String, def: Float) = prefs.getFloat("bg_${scope}_$name", def)
    fun bgPutFloat(scope: String, name: String, v: Float) = prefs.edit().putFloat("bg_${scope}_$name", v).apply()
    fun bgGetInt(scope: String, name: String, def: Int) = prefs.getInt("bg_${scope}_$name", def)
    fun bgPutInt(scope: String, name: String, v: Int) = prefs.edit().putInt("bg_${scope}_$name", v).apply()

    // ── Active orders list ──
    //
    // ⚠️ 这三个都加了 [@Synchronized]：活跃订单会被 UI、前台服务、桌面小组件
    // 三处的后台协程同时读写，而调用方普遍是「读出来 → 改 → 写回去」的模式。
    // 没有锁时，A 加设备 1、B 加设备 2，两者基于同一份旧列表各自写回，
    // 后写的会整份覆盖先写的——直接丢一条订单。
    //
    // 这只是**缓解**，不是完整解：锁的粒度是单次 get / 单次 save，
    // 「读-改-写」这三步作为一个整体仍然不是原子的。真正的解法是
    // 收口成一个 `ActiveOrderRepository`，用 `Mutex` 串行化并提供
    // `update { old -> new }` 形式的原子操作。在那之前，加锁能消掉
    // 最常见的一类竞态，而且成本只有几个注解。
    @Synchronized
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

    @Synchronized
    fun saveActiveOrders(orders: List<ActiveOrder>) {
        prefs.edit().putString("activeOrders", gson.toJson(orders)).apply()
    }

    @Synchronized
    fun clearActiveOrders() = prefs.edit().remove("activeOrders").apply()

    /**
     * 蓝牙会话（JSON 字符串）。**小组件服务与 App 共用**——蓝牙关阀/结算要靠会话里的
     * mac / randomNumber / protocolType，而小组件是另一个进程入口，读不到 ViewModel 内存。
     */
    var bleSessionJson: String get() = prefs.getString("bleSessionJson", "") ?: ""
        set(v) = prefs.edit().putString("bleSessionJson", v).apply()

    /**
     * 通知里「结束使用」按钮的 `PendingIntent` requestCode（按 snCode **稳定分配**）。
     *
     * ⚠️ 不能用 `snCode.hashCode()`：两台设备的哈希一旦碰撞，它们的按钮会共享同一个
     * PendingIntent，**点 A 的按钮可能停了 B 的设备**。这里按 snCode 递增分配并存进
     * Prefs —— 既保证不同设备不撞，又保证重启后数值不变（否则旧通知的按钮会失效）。
     */
    @Synchronized
    fun notifyRequestCode(snCode: String): Int {
        val key = "notifyReqCode_$snCode"
        val existing = prefs.getInt(key, -1)
        if (existing > 0) return existing
        val next = prefs.getInt("notifyReqCodeNext", 7101)
        prefs.edit().putInt(key, next).putInt("notifyReqCodeNext", next + 1).apply()
        return next
    }

    @Synchronized
    fun getPendingSettlements(): MutableList<PendingSettlement> {
        val json = prefs.getString("pendingSettlements", "[]") ?: "[]"
        return try {
            JsonParser().parse(json).asJsonArray.mapTo(mutableListOf()) {
                gson.fromJson(it, PendingSettlement::class.java)
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun savePendingSettlements(items: List<PendingSettlement>) {
        prefs.edit().putString("pendingSettlements", gson.toJson(items)).apply()
    }

    @Synchronized
    fun recordPendingSettlement(item: PendingSettlement) {
        val items = getPendingSettlements().filterNot { it.snCode == item.snCode } + item
        savePendingSettlements(items)
    }

    @Synchronized
    fun clearPendingSettlement(snCode: String) {
        savePendingSettlements(getPendingSettlements().filterNot { it.snCode == snCode })
    }

    fun getCachedBills(accountKey: String): List<BillItem> =
        BillHistoryCache.decode(prefs.getString(BillHistoryCache.keyFor(accountKey), "") ?: "")

    fun saveCachedBills(accountKey: String, bills: List<BillItem>) {
        prefs.edit().putString(BillHistoryCache.keyFor(accountKey), BillHistoryCache.encode(bills)).apply()
    }

    var themeMode: String
        get() = prefs.getString("themeMode", "LIGHT") ?: "LIGHT"
        set(value) = prefs.edit().putString("themeMode", value).apply()

    /**
     * 下载更新包时是否走国内镜像（Gitee）。
     *
     * 默认开。大陆实测 GitHub 的 Release 附件约 100 KB/s，Gitee 约 2 MB/s，差近 20 倍。
     * 但 Gitee 那边的仓库不如 GitHub 稳（审核、限流都可能让地址失效），
     * 所以留个开关，镜像下不动时用户能切回 GitHub。
     */
    var useMirrorDownload: Boolean get() = prefs.getBoolean("useMirrorDownload", true)
        set(v) = prefs.edit().putBoolean("useMirrorDownload", v).apply()

    // ── 通知开关（「我的」页面的通知卡片）──
    // 三个独立开关。关掉「使用中通知」只是不挂那条常驻通知，
    // 后台监控照常跑——自动关停和结束通知不受影响。

    /**
     * 通知总开关。
     *
     * 关掉后**一条通知都不发**，包括前台服务那条必须挂的——
     * 服务会先挂上再立刻摘掉（Android 不允许前台服务没有通知）。
     * 代价是服务降级成普通后台服务，更容易被系统回收。
     */
    var notifyEnabled: Boolean get() = prefs.getBoolean("notifyEnabled", true)
        set(v) = prefs.edit().putBoolean("notifyEnabled", v).apply()

    /** 用水期间在通知栏常驻一条，显示已用时间 */
    var notifyInUse: Boolean get() = prefs.getBoolean("notifyInUse", true)
        set(v) = prefs.edit().putBoolean("notifyInUse", v).apply()

    /** 用水结束时通知（手动停止） */
    var notifyFinished: Boolean get() = prefs.getBoolean("notifyFinished", true)
        set(v) = prefs.edit().putBoolean("notifyFinished", v).apply()

    /** 设备超时自动关停时通知 */
    var notifyAutoClose: Boolean get() = prefs.getBoolean("notifyAutoClose", true)
        set(v) = prefs.edit().putBoolean("notifyAutoClose", v).apply()

    /**
     * 「横幅提醒」——开阀失败、设备被占用、超时自动关停要从屏幕顶上弹出来。
     *
     * 靠**两条渠道二选一**实现（HIGH / DEFAULT），而不是改渠道优先级：
     * 系统禁止 App 修改已存在渠道的 importance，改不动。见 `Notifier.alertChannel`。
     */
    var notifyAlert: Boolean get() = prefs.getBoolean("notifyAlert", true)
        set(v) = prefs.edit().putBoolean("notifyAlert", v).apply()

    /**
     * 「消费趋势」卡片（钱包页那张统计图）是否显示。
     *
     * 开关放在「设置 → 消费趋势」卡片里，控制 [com.quzhi.lite.ui.SpendingTrendCard]
     * 在钱包页的显隐。默认开启，跟历史行为保持一致。
     */
    var trendEnabled: Boolean get() = prefs.getBoolean("trendEnabled", true)
        set(v) = prefs.edit().putBoolean("trendEnabled", v).apply()

    /**
     * 「消费合并」——把多个板块（洗浴 / 洗衣饮水吹风）的账单合成一份，
     * 看到**今天实际花了多少钱**，而不是各算各的。
     *
     * ## 与「消费趋势」的关系：**联动关闭**
     *
     * 合并结果只喂给钱包页那张趋势图（[com.quzhi.lite.ui.SpendingTrendCard]），
     * 趋势关掉时合并没有展示出口。所以：
     *
     * - 设置页关趋势时**同时把本值置 false**（见 `TrendAndMergeCards`）
     * - 这一条是用户实测后明确要求的（2026-10-24）：原来"关趋势不动合并"的做法
     *   会让用户重开趋势时看到合并又亮了，以为开关坏了
     *
     * 因此 [canMergeConsume] 就是两个值的与——两边都开才真合并。
     *
     * @see canMergeConsume 实际生效值
     */
    var mergeConsume: Boolean get() = prefs.getBoolean("mergeConsume", false)
        set(v) = prefs.edit().putBoolean("mergeConsume", v).apply()

    /**
     * 消费合并**是否真的生效**。
     *
     * 取交集而不是只读 [mergeConsume]：万一存储值因为某种原因和趋势不一致
     * （旧版本升级上来、或外部改了 Prefs），这里兜住，不会出现
     * 「趋势关着却在偷偷拉别的板块账单」。
     */
    val canMergeConsume: Boolean get() = trendEnabled && mergeConsume

    /**
     * 消费趋势卡片的**视图状态**（跨会话保留）。
     *
     * ⚠️ 这三个原来用 `remember` 存在 Composable 里，退出「我的」页再进来就复位成
     * 「7 天 / 折线 / 折叠」——用户每次都要重新点一遍。存进 Prefs 就能记住上次的选择。
     */
    var trendRange: String get() = prefs.getString("trendRange", "LAST_7_DAYS") ?: "LAST_7_DAYS"
        set(v) = prefs.edit().putString("trendRange", v).apply()

    var trendChartType: String get() = prefs.getString("trendChartType", "LINE") ?: "LINE"
        set(v) = prefs.edit().putString("trendChartType", v).apply()

    var trendCollapsed: Boolean get() = prefs.getBoolean("trendCollapsed", true)
        set(v) = prefs.edit().putBoolean("trendCollapsed", v).apply()

    /**
     * 背景装扮页上次在看的范围（`home` / `shower`）。
     * 同样是「退出重进就复位」的浏览偏好，存起来省得每次重新点。
     */
    var backgroundScope: String get() = prefs.getString("backgroundScope", "home") ?: "home"
        set(v) = prefs.edit().putString("backgroundScope", v).apply()

    /**
     * 「占用中」标记的有效期。
     *
     * 3 分钟是用户定的。**这不是推导出来的数**——理论上界应该是设备的自动关停窗口
     * （对方不可能用超过那个时长），但那个值是从服务端读的 `autoDisConTime`，
     * 代码里没有兜底、也没有实测样本，所以只能按经验取。
     *
     * 取值理由：徽章**不禁用按钮**（占用中仍然点得动，服务端才是最终裁判），
     * 所以「设短」只是标签早消失一会儿，用户点一下就知道了；
     * 而「设长」会让人看到「占用中」干脆不去点——那个代价更大。**宁可短。**
     */
    private const val OCCUPIED_TTL_MS = 3 * 60 * 1000L

    /**
     * 已知「被他人占用」的设备 snCode；空串表示当前没有。
     *
     * 小组件上点开始、发现设备正被别人用着时写入，徽章随之变成「占用中」。
     *
     * ⚠️ 小组件渲染是纯本地同步的，发不了网络请求，所以它**没法自己知道
     * 设备什么时候空出来**。以前只靠「用户下次点」这个时机刷新，导致
     * 用户从此不再点小组件的话，桌面会**永远**挂着「占用中」。现在有两条出路：
     *
     * 1. App 侧查到设备空了会调 [clearOccupied]（见 `MainViewModel.refreshDeviceStatus`）
     * 2. 兜底：超过 [OCCUPIED_TTL_MS] 自动失效（见 [occupiedFor]）
     */
    var occupiedSnCode: String get() = prefs.getString("occupiedSnCode", "") ?: ""
        set(v) = prefs.edit()
            .putString("occupiedSnCode", v)
            .putLong("occupiedAtMs", System.currentTimeMillis())
            .apply()

    /**
     * 这台设备**现在**是不是还该显示「占用中」。
     *
     * 除了 snCode 对得上，还要求标记没过期——过期时**顺手把陈旧标记清掉并记一条日志**
     * （只记一次，因为清完再进来 snCode 就是空串了）。
     * 那条日志是给以后调这个 3 分钟用的：真实数据比再猜一轮靠谱。
     */
    fun occupiedFor(snCode: String): Boolean {
        if (snCode.isEmpty()) return false
        if (prefs.getString("occupiedSnCode", "") != snCode) return false
        val at = prefs.getLong("occupiedAtMs", 0L)
        if (at > 0L && System.currentTimeMillis() - at <= OCCUPIED_TTL_MS) return true
        clearOccupied()
        AppLogger.i("「占用中」标记超过 ${OCCUPIED_TTL_MS / 60000} 分钟，自动失效")
        return false
    }

    /** 清掉「占用中」标记。App 查到设备已空闲、或小组件开阀成功时调用 */
    fun clearOccupied() {
        if (prefs.getString("occupiedSnCode", "")!!.isEmpty()) return
        prefs.edit().remove("occupiedSnCode").remove("occupiedAtMs").apply()
    }

    var manualBalance: String get() = prefs.getString("manualBalance", "") ?: ""; set(v) = prefs.edit().putString("manualBalance", v).apply()
    var manualBalanceTime: Long get() = prefs.getLong("manualBalanceTime", 0L); set(v) = prefs.edit().putLong("manualBalanceTime", v).apply()

    /**
     * 上次使用设备的预扣金额。
     * 小组件开阀时拿不到 DeviceInfo（它只有 snCode），而预扣金额只存在于设备信息里，
     * 所以单独存一份——否则小组件开阀后卡片上的预扣永远是 ¥0.00。
     */
    var lastDeviceWithholdMoney: Float get() = prefs.getFloat("lastDeviceWithholdMoney", 0f)
        set(v) = prefs.edit().putFloat("lastDeviceWithholdMoney", v).apply()

    /** 上次使用设备的类型名（热水器 / 洗手台 / 饮水机），小组件副标题用 */
    var lastDeviceTypeName: String get() = prefs.getString("lastDeviceTypeName", "") ?: ""
        set(v) = prefs.edit().putString("lastDeviceTypeName", v).apply()

    // ── 收藏设备 ──
    //
    // 和上面那组 lastDevice* 的区别：那是「1 个槽位、被每次操作覆盖」的易失记录，
    // 收藏是**多条、用户主动添加、杀后台也不丢**的持久列表。
    // 渲染卡片需要的字段全部存进 JSON，**不依赖联网回查**（离线也要能显示）。

    /** 收藏列表的 JSON。读写请走 [com.quzhi.lite.data.FavoriteStore]，别直接解析 */
    var favoritesJson: String get() = prefs.getString("favoritesJson", "") ?: ""
        set(v) = prefs.edit().putString("favoritesJson", v).apply()

    /** 收藏列表（已排序：新收藏在前）。数据损坏时返回空列表，不抛异常 */
    fun favorites(): List<com.quzhi.lite.data.FavoriteDevice> =
        com.quzhi.lite.data.FavoriteStore.sorted(
            com.quzhi.lite.data.FavoriteStore.decode(favoritesJson)
        )

    fun saveFavorites(list: List<com.quzhi.lite.data.FavoriteDevice>) {
        favoritesJson = com.quzhi.lite.data.FavoriteStore.encode(list)
    }

    // ── 小组件离线快照 ──
    // 小组件自己扫不了蓝牙、拉不了账单，所以由 App 在干活时把结果存下来，
    // 小组件的「附近设备 / 账单」两页读这里，并显示同步时间避免被当成实时数据。

    var widgetNearbyJson: String get() = prefs.getString("widgetNearbyJson", "") ?: ""
        set(v) = prefs.edit().putString("widgetNearbyJson", v).apply()

    var widgetNearbyTime: Long get() = prefs.getLong("widgetNearbyTime", 0L)
        set(v) = prefs.edit().putLong("widgetNearbyTime", v).apply()

    var widgetBillJson: String get() = prefs.getString("widgetBillJson", "") ?: ""
        set(v) = prefs.edit().putString("widgetBillJson", v).apply()

    var widgetBillTime: Long get() = prefs.getLong("widgetBillTime", 0L)
        set(v) = prefs.edit().putLong("widgetBillTime", v).apply()

    // 2x4 小组件的当前 tab（每个 widget id 各一份，用 "tab_<id>" 作 key）
    fun widgetTab(id: Int): Int = prefs.getInt("widgetTab_$id", 0)
    fun setWidgetTab(id: Int, tab: Int) { prefs.edit().putInt("widgetTab_$id", tab).apply() }

    /** 该 widget 是否还在桌面上；被删掉时清掉它的 tab 记录 */
    fun clearWidgetTab(id: Int) { prefs.edit().remove("widgetTab_$id").apply() }

    // ── 上次消费 ──
    // 持久化下来给桌面小组件显示用：小组件不能为了一个数字去轮询账单接口，
    // 所以由 App（结算成功、或拉到账单列表时）写入，小组件只读。

    /** 上次消费时间戳（毫秒），0 表示还没有记录 */
    var lastConsumeTime: Long get() = prefs.getLong("lastConsumeTime", 0L); set(v) = prefs.edit().putLong("lastConsumeTime", v).apply()

    /**
     * 某台设备的「上次消费」金额，0 表示还没有记录。
     *
     * 原先只有一个全局值，结果在小组件上用「选用」换到另一台设备之后，
     * 卡片会顶着新设备的名字显示上一台的消费金额。改成按 snCode 分开存，
     * key 是「前缀 + snCode」，和 startedAt_ / autoDiscon_ 同一个套路。
     */
    fun lastConsumeFor(snCode: String): Float =
        if (snCode.isEmpty()) 0f else prefs.getFloat("consume_$snCode", 0f)

    /** 记一笔消费。金额为 0 时不动已有记录，避免「本次无消费」把历史值抹掉 */
    fun recordConsume(snCode: String, amount: Double, atMs: Long = System.currentTimeMillis()) {
        if (amount <= 0 || snCode.isEmpty()) return
        prefs.edit()
            .putFloat("consume_$snCode", amount.toFloat())
            .putLong("lastConsumeTime", atMs)
            .apply()
    }

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
