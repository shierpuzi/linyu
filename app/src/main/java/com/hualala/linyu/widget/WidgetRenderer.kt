package com.hualala.linyu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.google.gson.Gson
import com.hualala.linyu.MainActivity
import com.hualala.linyu.R
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.model.CachedBill
import com.hualala.linyu.model.CachedDevice
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.ScanPermission

/** 小组件尺寸 */
enum class WidgetSize(val label: String) {
    /** 2x2，可被拉宽，宽高比决定走竖向还是横向布局 */
    SMALL("2x2"),

    /** 2x4，带左侧图标导航与三个页面 */
    WIDE("2x4")
}

/** 小组件展示状态 */
sealed interface WidgetState {
    data object LoggedOut : WidgetState
    data object NoDevice : WidgetState

    data class Idle(
        val deviceName: String,
        val deviceDesc: String,
        val emoji: String
    ) : WidgetState

    data class Running(
        val deviceName: String,
        val deviceDesc: String,
        val emoji: String,
        val preDeduct: Double,
        val startedAtMs: Long
    ) : WidgetState
}

/**
 * 小组件渲染。
 *
 * 设计稿：`gemini-code-1789438284508.html`（Kyant0 液态玻璃风格）。
 *
 * ## 与设计稿的强制差异（RemoteViews 限制，不是偷懒）
 * - 设计稿的 `backdrop-filter: blur()` 无法实现 → 用「对角渐变 + 顶部高光」两层叠加近似
 * - `:active` 缩放、状态切换过渡等动画一律没有
 * - 运行时不能换背景（框架对反射调用有白名单）→ 一切"同一位置两种外观"都拆成两个控件切 visibility
 * - 只有这些控件可用：LinearLayout / FrameLayout / TextView / ImageView / Chronometer 等
 *
 * ## 配色
 * 跟随**壁纸明暗**而不是 App 内的主题设置：小组件是半透明的，压在深色壁纸上的浅色卡
 * 会让深色文字糊掉，反之亦然。
 */
object WidgetRenderer {

    private val gson = Gson()

    /** App 底部导航的三个 tab 序号，跳转用 */
    const val TAB_HOME = 0
    const val TAB_BILL = 1

    fun readState(): WidgetState {
        if (!PrefsHelper.isLoggedIn) return WidgetState.LoggedOut

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty()) return WidgetState.NoDevice

        val name = PrefsHelper.lastDeviceName.ifEmpty { "上次使用的设备" }
        val desc = deviceDesc()
        val emoji = PrefsHelper.lastDeviceEmoji.ifEmpty { "🚿" }

        return if (ShowerController.isRunning(snCode)) {
            WidgetState.Running(
                deviceName = name,
                deviceDesc = desc,
                emoji = emoji,
                preDeduct = ShowerController.activeOrderFor(snCode)?.preDeduct ?: 0.0,
                startedAtMs = ShowerController.startedAt(snCode)
            )
        } else {
            WidgetState.Idle(name, desc, emoji)
        }
    }

    /**
     * @param disabled 非空表示正在操作或状态未知。这个状态是**全局共享**的，
     *                 所以桌面上所有淋浴小组件会同时进入「正在开启…」，不会只有一个在转
     * @param tab      仅 2x4 使用：当前显示第几页
     */
    fun build(
        context: Context,
        size: WidgetSize,
        state: WidgetState,
        appWidgetId: Int,
        disabled: DisabledReason? = null,
        tab: Int = 0
    ): RemoteViews {
        // 只有一套固定样式，不分深浅：
        // 小组件压在用户的壁纸上，深浅切换要么看不出变化、要么和壁纸撞色，
        // 不如固定成一个自带背景的高对比卡片——任何壁纸下都一样清楚。
        val wide = size == WidgetSize.SMALL && isWide(context, appWidgetId)
        val views = RemoteViews(context.packageName, layoutRes(size, wide))

        // 整张卡片 → 打开 App 首页
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, appWidgetId, TAB_HOME))

        val useSphere = size == WidgetSize.SMALL && wide
        fun bindAct(running: Boolean, disabled: DisabledReason?, action: String) {
            val onClick = actionIntent(context, appWidgetId, disabled, action)
            if (useSphere) bindSphereAction(views, running, onClick)
            else bindPillAction(views, running, onClick)
        }

        // 竖向 2x2 空间窄，只显示完整设备名的最后一个词（「某小区 3号楼南 320房」→「320房」）；
        // 拉宽成横向、以及 2x4，位置够就显示完整名
        val rawName = when (state) {
            is WidgetState.Idle -> state.deviceName
            is WidgetState.Running -> state.deviceName
            else -> ""
        }
        val shownName = if (size == WidgetSize.SMALL && !wide) shortName(rawName) else rawName

        when (state) {
            WidgetState.LoggedOut -> {
                bindHeader(views, "未登录", "点此打开淋浴", "🔒", running = false, showDot = false)
                bindPanels(context, views, appWidgetId, idleText = "—", runningText = null, busyText = disabled?.text)
                bindAct(running = false, disabled = disabled, action = "")
            }

            WidgetState.NoDevice -> {
                bindHeader(views, "还没有用过设备", "点此打开淋浴", "🚿", running = false, showDot = false)
                bindPanels(context, views, appWidgetId, idleText = "—", runningText = null, busyText = disabled?.text)
                bindAct(running = false, disabled = disabled, action = "")
            }

            is WidgetState.Idle -> {
                bindHeader(views, shownName, state.deviceDesc, state.emoji,
                    running = false, showDot = true)
                bindPanels(
                    context, views, appWidgetId,
                    idleText = lastConsumeText(),
                    runningText = null,
                    busyText = disabled?.text
                )
                bindAct(running = false, disabled = disabled, action = LinYuWidgetProvider.ACTION_START)
            }

            is WidgetState.Running -> {
                bindHeader(views, shownName, state.deviceDesc, state.emoji,
                    running = true, showDot = true)
                bindPanels(
                    context, views, appWidgetId,
                    idleText = null,
                    runningText = "预扣 · ¥%.2f".format(state.preDeduct),
                    timerText = null,
                    chronometerBase = chronometerBase(state.startedAtMs),
                    busyText = disabled?.text
                )
                bindAct(running = true, disabled = disabled, action = LinYuWidgetProvider.ACTION_STOP)
            }
        }

        if (size == WidgetSize.WIDE) {
            bindTabs(views, context, appWidgetId, tab)
            bindPage(context, views, appWidgetId, tab)
        }
        return views
    }

    // ── 头部：状态点 + 设备名 + 状态徽章 ──

    private fun bindHeader(
        views: RemoteViews,
        name: String,
        desc: String,
        emoji: String,
        running: Boolean,
        showDot: Boolean
    ) {
        views.setTextViewText(R.id.widget_device, name)
        views.setTextViewText(R.id.widget_device_desc, desc)

        if (showDot) {
            views.setTextViewText(R.id.widget_status_dot, "●")
            views.setTextColor(R.id.widget_status_dot, if (running) COLOR_USING else COLOR_IDLE)
        } else {
            views.setTextViewText(R.id.widget_status_dot, "")
        }

        // 徽章底色不同，靠两个控件切 visibility（运行时改不了背景）
        views.setViewVisibility(R.id.widget_badge_idle, if (running) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_badge_using, if (running) View.VISIBLE else View.GONE)
    }

    /**
     * 内嵌玻璃槽：空闲态显示「上次消费」，使用中显示「走秒 + 预扣」。
     *
     * 操作进行中（[busyText] 非空）时，这里改显示「正在开启…/正在关闭…」并**占满整个卡片**——
     * 用户点完按钮最想看到的是"它在动"，而不是原来那个数字。
     *
     * 点击行为按状态分：
     * - 空闲 → 打开 App 的账单页（上次消费是账单信息，点进去看明细最自然）
     * - 使用中 → 直接关阀（不用再去找那个小圆球）
     */
    private fun bindPanels(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        idleText: String?,
        runningText: String?,
        timerText: String? = null,
        chronometerBase: Long = 0L,
        busyText: String? = null
    ) {
        // 三种面板叠在同一位置，切 visibility。
        // 忙碌时优先显示转圈面板——点完按钮最想看到的是"它在转"
        val showBusy = busyText != null
        val showIdle = idleText != null && !showBusy
        views.setViewVisibility(R.id.widget_panel_busy, if (showBusy) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_panel_idle, if (showIdle) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.widget_panel_running,
            if (!showBusy && !showIdle) View.VISIBLE else View.GONE
        )
        if (showBusy) {
            views.setTextViewText(R.id.widget_busy_text, busyText ?: "")
            // 转圈期间卡片点击统一回 App，不做别的动作
            views.setOnClickPendingIntent(
                R.id.widget_panel_busy,
                openAppIntent(context, appWidgetId, TAB_BILL)
            )
            return
        }

        if (showIdle) {
            // 忙碌时把「上次消费」这个小标题藏掉，免得和「正在开启…」并排读起来别扭
            views.setTextViewText(R.id.widget_panel_label, if (busyText != null) "" else "上次消费")
            views.setTextViewText(R.id.widget_lastconsume, busyText ?: idleText ?: "—")
            views.setOnClickPendingIntent(
                R.id.widget_panel_idle,
                openAppIntent(context, appWidgetId, TAB_BILL)
            )
        } else {
            views.setTextViewText(R.id.widget_prededuct, busyText ?: runningText ?: "")
            if (busyText != null) {
                views.setChronometer(R.id.widget_timer, 0L, null, false)
                views.setTextViewText(R.id.widget_timer, busyText)
            } else if (timerText != null) {
                views.setChronometer(R.id.widget_timer, 0L, null, false)
                views.setTextViewText(R.id.widget_timer, timerText)
            } else {
                views.setChronometer(R.id.widget_timer, chronometerBase, null, true)
            }
            // 使用中点卡片 = 关阀（正在操作时不响应，避免重复触发）
            views.setOnClickPendingIntent(
                R.id.widget_panel_running,
                if (busyText != null) null
                else actionIntent(context, appWidgetId, null, LinYuWidgetProvider.ACTION_STOP)
            )
        }
    }

    /**
     * 胶囊按钮（竖向 2x2 与 2x4）：**只有图标，没有文字**。
     * 图标已经写在布局的 drawableStart 里（开=电源、关=停止方块），这里只负责切显示和挂点击。
     * 显式写 null 清掉上一轮的点击，否则禁用态还会带着旧动作。
     */
    private fun bindPillAction(
        views: RemoteViews,
        running: Boolean,
        onClick: PendingIntent?
    ) {
        val visibleId = if (running) R.id.widget_action_stop else R.id.widget_action_start
        val hiddenId = if (running) R.id.widget_action_start else R.id.widget_action_stop

        views.setViewVisibility(hiddenId, View.GONE)
        views.setViewVisibility(visibleId, View.VISIBLE)
        views.setOnClickPendingIntent(visibleId, onClick)
    }

    /**
     * 水滴圆球按钮（横向 2x2）。同样只有图标。
     *
     * ⚠️ 它和胶囊按钮**刻意用不同的 id**。之前两者共用 id，靠"布局不同、id 相同"来省分支，
     * 结果给 ImageView 调了 setTextViewText —— RemoteViews 反射找不到 setText 会抛
     * ActionException，桌面上只显示「载入窗口小部件时出现问题」，App 侧既不报错也不崩溃。
     */
    private fun bindSphereAction(
        views: RemoteViews,
        running: Boolean,
        onClick: PendingIntent?
    ) {
        val visibleId = if (running) R.id.widget_sphere_stop else R.id.widget_sphere_start
        val hiddenId = if (running) R.id.widget_sphere_start else R.id.widget_sphere_stop

        views.setViewVisibility(hiddenId, View.GONE)
        views.setViewVisibility(visibleId, View.VISIBLE)
        views.setOnClickPendingIntent(visibleId, onClick)
    }

    // ── 2x4 侧边栏与三个页面 ──

    private fun bindTabs(views: RemoteViews, context: Context, appWidgetId: Int, tab: Int) {
        for (i in 0..2) {
            val onId = when (i) {
                0 -> R.id.widget_tab0_on
                1 -> R.id.widget_tab1_on
                else -> R.id.widget_tab2_on
            }
            val offId = when (i) {
                0 -> R.id.widget_tab0_off
                1 -> R.id.widget_tab1_off
                else -> R.id.widget_tab2_off
            }
            val selected = i == tab
            views.setViewVisibility(onId, if (selected) View.VISIBLE else View.GONE)
            views.setViewVisibility(offId, if (selected) View.GONE else View.VISIBLE)
            views.setOnClickPendingIntent(
                if (selected) onId else offId,
                tabIntent(context, appWidgetId, i)
            )
        }
    }

    private fun bindPage(context: Context, views: RemoteViews, appWidgetId: Int, tab: Int) {
        views.setViewVisibility(R.id.widget_page0, if (tab == 0) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_page1, if (tab == 1) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_page2, if (tab == 2) View.VISIBLE else View.GONE)

        when (tab) {
            1 -> bindNearby(context, views, appWidgetId)
            2 -> {
                bindBills(views)
                // 账单页整页可点 → 进 App 的账单页看明细
                views.setOnClickPendingIntent(
                    R.id.widget_page2,
                    openAppIntent(context, appWidgetId, TAB_BILL)
                )
            }
        }
    }

    /**
     * 附近设备页：读 App 上次扫描存的快照。
     *
     * 小组件扫不了蓝牙，所以显示不了实时结果；但"为什么没有设备"是能判断的——
     * 没权限 / 蓝牙没开 / 扫过但没结果，这三种情况提示完全不同，
     * 一律显示「未扫描到附近设备」会让人以为是 App 的问题。
     */
    private fun bindNearby(context: Context, views: RemoteViews, appWidgetId: Int) {
        val blocker = nearbyBlocker(context)
        val list = readCache(PrefsHelper.widgetNearbyJson, Array<CachedDevice>::class.java)
        val showList = blocker == null && list.isNotEmpty()

        views.setTextViewText(R.id.widget_near_synced, "（${syncedAgoText()}）")
        views.setViewVisibility(R.id.widget_near_list, if (showList) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_near_empty, if (showList) View.GONE else View.VISIBLE)
        if (!showList) {
            views.setTextViewText(R.id.widget_near_empty, blocker ?: "未扫描到附近设备")
            return
        }

        val rowIds = arrayOf(
            intArrayOf(R.id.widget_near0_emoji, R.id.widget_near0_name,
                R.id.widget_near0_desc, R.id.widget_near0_signal, R.id.widget_near0_rssi),
            intArrayOf(R.id.widget_near1_emoji, R.id.widget_near1_name,
                R.id.widget_near1_desc, R.id.widget_near1_signal, R.id.widget_near1_rssi)
        )
        val pickIds = intArrayOf(R.id.widget_near0_pick, R.id.widget_near1_pick)

        for (i in 0..1) {
            val d = list.getOrNull(i)
            if (d == null) {
                // 只扫到一台时第二行留空，不要塞「—」显得像出错
                rowIds[i].forEach { views.setTextViewText(it, "") }
                views.setOnClickPendingIntent(pickIds[i], null)
            } else {
                // 一律兜底：缓存里可能是旧版本写的 JSON，缺字段就是 null
                views.setTextViewText(rowIds[i][0], d.emoji ?: "🚿")
                views.setTextViewText(rowIds[i][1], d.name ?: "")
                views.setTextViewText(rowIds[i][2], d.desc ?: "")
                // 信号点 + dB 数值：沿用 App 首页的强/中/弱配色。
                // 光一个圆点看不出强弱差多少，补上具体的 dB 值（和 App 内是同一份 rssi）
                views.setTextViewText(rowIds[i][3], "●")
                views.setTextColor(rowIds[i][3], signalColor(d.rssi))
                views.setTextViewText(rowIds[i][4], "${d.rssi} dBm")
                views.setTextColor(rowIds[i][4], signalColor(d.rssi))
                // 「选用」→ 打开 App 并直接弹出这台设备的详情。
                // 小组件自己绑不了设备：绑定要拿完整设备信息，得回 App 请求。
                val mac = d.mac ?: ""
                views.setOnClickPendingIntent(
                    pickIds[i],
                    if (mac.isNotEmpty()) openBindIntent(context, appWidgetId, mac) else null
                )
            }
        }
    }

    /**
     * 判断附近设备页现在"卡"在哪一步，返回要显示的提示；返回 null 表示条件都满足。
     * 顺序很重要：没权限时连蓝牙状态都读不到，必须先判权限。
     */
    private fun nearbyBlocker(context: Context): String? {
        if (!ScanPermission.granted(context)) return "请在 App 中开启扫描权限"
        if (!isBluetoothOn(context)) return "请打开蓝牙"
        return null
    }

    /** 读蓝牙开关状态。API 31+ 没 BLUETOOTH_CONNECT 会抛 SecurityException，所以要先判权限 */
    private fun isBluetoothOn(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        bm?.adapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    /** 账单页：读 App 上次拉取的账单快照 */
    private fun bindBills(views: RemoteViews) {
        val balance = PrefsHelper.manualBalance.toDoubleOrNull()
        views.setTextViewText(
            R.id.widget_balance,
            if (balance != null && balance > 0) "¥ %.2f".format(balance) else "¥ —"
        )

        val list = readCache(PrefsHelper.widgetBillJson, Array<CachedBill>::class.java)
        val rowIds = arrayOf(
            intArrayOf(R.id.widget_bill0_emoji, R.id.widget_bill0_name,
                R.id.widget_bill0_time, R.id.widget_bill0_price),
            intArrayOf(R.id.widget_bill1_emoji, R.id.widget_bill1_name,
                R.id.widget_bill1_time, R.id.widget_bill1_price)
        )
        for (i in 0..1) {
            val b = list.getOrNull(i)
            if (b == null) {
                views.setTextViewText(rowIds[i][0], "—")
                views.setTextViewText(rowIds[i][1], if (i == 0) "在 App 里刷新账单" else "—")
                views.setTextViewText(rowIds[i][2], "")
                views.setTextViewText(rowIds[i][3], "")
            } else {
                views.setTextViewText(rowIds[i][0], b.emoji ?: "🚿")
                views.setTextViewText(rowIds[i][1], b.name ?: "")
                views.setTextViewText(rowIds[i][2], b.timeText ?: "")
                views.setTextViewText(rowIds[i][3], b.moneyText ?: "")
            }
        }
    }

    private fun <T> readCache(json: String, cls: Class<Array<T>>): List<T> = try {
        if (json.isBlank()) emptyList() else gson.fromJson(json, cls)?.toList() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 「上次扫描」距今多久。
     * 小组件扫不了蓝牙，页面上看到的永远是快照——不标出来会被当成实时数据。
     */
    private fun syncedAgoText(): String {
        val t = PrefsHelper.widgetNearbyTime
        if (t <= 0L) return "尚未扫描"
        val mins = (System.currentTimeMillis() - t) / 60_000L
        return when {
            mins < 1 -> "刚刚"
            mins < 60 -> "$mins 分钟前"
            else -> "${mins / 60} 小时前"
        }
    }

    /** 信号强度配色，与 App 首页的强/中/弱一致 */
    private fun signalColor(rssi: Int): Int = when {
        rssi >= -70 -> COLOR_IDLE      // 强
        rssi >= -85 -> COLOR_WARN      // 中
        else -> COLOR_DANGER           // 弱
    }

    private fun lastConsumeText(): String {
        val money = PrefsHelper.lastConsumeMoney
        return if (money > 0f) "¥ %.2f".format(money) else "¥ —"
    }

    /** 早期版本写进去的是「热水器 / 洗手台 / 饮水机」这种简写，认出来就用 emoji 重推一遍 */
    private val BARE_TYPE_NAMES = setOf("热水器", "洗手台", "饮水机")

    /**
     * 副标题（卫生间热水器 / 洗手台热水器 / 直饮水机 · 冷水…）。
     *
     * `lastDeviceTypeName` 只在**开始洗澡**时才写入，所以老用户升级上来存的还是旧值。
     * 这里发现是简写就按 emoji 反推，不用等下一次开阀。
     */
    private fun deviceDesc(): String {
        val stored = PrefsHelper.lastDeviceTypeName
        if (stored.isNotEmpty() && stored !in BARE_TYPE_NAMES) return stored
        return when (PrefsHelper.lastDeviceEmoji) {
            "🪥" -> "洗手台热水器"
            "❄️" -> "直饮水机 · 冷水"
            "♨️" -> "直饮水机 · 热水"
            else -> "卫生间热水器"
        }
    }

    /**
     * 取完整设备名的最后一个词。
     * 设备名经过 DeviceInfo.formatDeviceName 处理过，分段之间用空格连接
     * （「某小区 3号楼南 320房」），所以按空白取最后一段即可。
     */
    private fun shortName(full: String): String =
        full.trim().split(' ', '　', '-').lastOrNull { it.isNotBlank() } ?: full

    /**
     * 把「挂钟时间戳」换算成 Chronometer 要的基准。
     *
     * Chronometer 用 SystemClock.elapsedRealtime()（开机以来）算差值，而 startedAt 存的是
     * System.currentTimeMillis()（1970 以来），原点不同，必须换算。
     *
     * ⚠️ 关键：换算必须用「**开机那一刻的挂钟时间**」，不能用「现在」。
     * 如果用 `elapsedRealtime() - (now - startedAt)`，那每次重绘算出来的 base 都会
     * 跟着渲染时刻平移——两个小组件渲染相差几百毫秒，base 就差几百毫秒，
     * 而 Chronometer 是按 base 的秒边界跳的，于是两边每秒里有一小段时间显示不同的秒数
     * （点一下侧边栏就会复现）。
     * 改成 `startedAt - bootWallMs` 后 base 只由 startedAt 决定，所有小组件同相位。
     */
    private fun chronometerBase(startedAtMs: Long): Long {
        if (startedAtMs <= 0L) return SystemClock.elapsedRealtime()
        val bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return startedAtMs - bootWallMs
    }

    /** 操作进行中时按钮要显示成什么 */
    enum class DisabledReason(val text: String) {
        STARTING("正在开启…"),
        STOPPING("正在关闭…"),
        REFRESHING("正在刷新…"),
        UNKNOWN("状态未知，点此刷新")
    }

    private fun actionIntent(
        context: Context,
        appWidgetId: Int,
        disabled: DisabledReason?,
        action: String
    ): PendingIntent? = when (disabled) {
        null -> toggleIntent(context, appWidgetId, action)
        DisabledReason.UNKNOWN -> toggleIntent(context, appWidgetId, LinYuWidgetProvider.ACTION_REFRESH)
        else -> null
    }

    private fun isWide(context: Context, appWidgetId: Int): Boolean = try {
        val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        w > 0 && h > 0 && w > h
    } catch (_: Exception) {
        false
    }

    private fun layoutRes(size: WidgetSize, wide: Boolean): Int = when {
        size == WidgetSize.WIDE -> R.layout.widget_linyu_2x4
        wide -> R.layout.widget_linyu_2x2_wide
        else -> R.layout.widget_linyu_2x2
    }

    // ── PendingIntent ──
    // requestCode 必须每个 widget、每个动作都不同，否则会互相覆盖

    private fun openAppIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
        }
        return PendingIntent.getActivity(
            context, 1000 + tab * 100 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 打开 App 并直接弹出这台设备的详情（小组件侧绑不了设备，只能把 mac 带回去） */
    private fun openBindIntent(context: Context, appWidgetId: Int, mac: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, TAB_HOME)
            putExtra(MainActivity.EXTRA_BIND_MAC, mac)
        }
        return PendingIntent.getActivity(
            context, 3000 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * ⚠️ 目标组件必须是 Manifest 里注册过的 receiver。
     * 指向未注册的基类会让广播被系统静默丢弃，表现就是「点按钮毫无反应」。
     */
    private fun toggleIntent(context: Context, appWidgetId: Int, action: String): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            this.action = action
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val code = when (action) {
            LinYuWidgetProvider.ACTION_START -> 2000
            LinYuWidgetProvider.ACTION_STOP -> 2500
            else -> 4000
        }
        return PendingIntent.getBroadcast(
            context, code + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 切换 2x4 的页面 */
    private fun tabIntent(context: Context, appWidgetId: Int, tab: Int): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            action = LinYuWidgetProvider.ACTION_SET_TAB
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(LinYuWidgetProvider.EXTRA_TAB, tab)
        }
        return PendingIntent.getBroadcast(
            context, 5000 + tab * 100 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun providerClass(context: Context, appWidgetId: Int): Class<*> {
        val manager = AppWidgetManager.getInstance(context)
        val small = manager.getAppWidgetIds(
            android.content.ComponentName(context, LinYuWidget2x2::class.java)
        )
        return if (appWidgetId in small) LinYuWidget2x2::class.java else LinYuWidget2x4::class.java
    }

    // 与设计稿 CSS 变量一致的状态色
    private const val COLOR_IDLE = 0xFF22C55E.toInt()
    private const val COLOR_USING = 0xFF3B82F6.toInt()
    private const val COLOR_USING_TEXT = 0xFF60A5FA.toInt()
    private const val COLOR_WARN = 0xFFF59E0B.toInt()
    private const val COLOR_DANGER = 0xFFEF4444.toInt()
}
