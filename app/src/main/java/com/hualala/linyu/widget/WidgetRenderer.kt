package com.hualala.linyu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.hualala.linyu.MainActivity
import com.hualala.linyu.R
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.utils.PrefsHelper

/** 小组件尺寸 */
enum class WidgetSize(val label: String) {
    /** 2x2，可被拉宽，宽高比决定按钮放在下方还是右侧 */
    SMALL("2x2"),

    /** 2x4 */
    WIDE("2x4")
}

/**
 * 小组件的展示状态。
 *
 * 小组件**不持有任何状态**，每次渲染都从 PrefsHelper 现读现算——
 * 这样 App 在别处改了状态（比如用户在 App 里开始洗澡），
 * 小组件下次一渲染就自动对上了，两边不需要任何同步机制。
 */
sealed interface WidgetState {
    /** 没有登录 */
    data object LoggedOut : WidgetState

    /** 登录了但从没用过设备（Prefs 里没有上次使用设备） */
    data object NoDevice : WidgetState

    /** 空闲，可以开始 */
    data class Idle(val deviceName: String, val emoji: String) : WidgetState

    /** 使用中 */
    data class Running(
        val deviceName: String,
        val emoji: String,
        val preDeduct: Double,
        val startedAtMs: Long
    ) : WidgetState
}

/**
 * 负责「读状态」和「画界面」。
 *
 * 刻意不含任何网络逻辑——渲染必须随时可调用，网络只发生在 [LinYuWidgetProvider] 里。
 *
 * ⚠️ 这里**一律只用 RemoteViews 的一等公民 API**（setTextViewText / setViewVisibility /
 * setChronometer / setOnClickPendingIntent）。不用 `setInt(id, "setXxx", ...)` 那种反射写法：
 * 框架对反射方法有 `@RemotableViewMethod` 白名单，赌错会让整个小组件渲染失败。
 */
object WidgetRenderer {

    /** 从持久化状态推断当前该显示什么 */
    fun readState(): WidgetState {
        if (!PrefsHelper.isLoggedIn) return WidgetState.LoggedOut

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty()) return WidgetState.NoDevice

        val name = PrefsHelper.lastDeviceName.ifEmpty { "上次使用的设备" }
        val emoji = PrefsHelper.lastDeviceEmoji.ifEmpty { "🚿" }

        return if (ShowerController.isRunning(snCode)) {
            WidgetState.Running(
                deviceName = name,
                emoji = emoji,
                preDeduct = ShowerController.activeOrderFor(snCode)?.preDeduct ?: 0.0,
                startedAtMs = ShowerController.startedAt(snCode)
            )
        } else {
            WidgetState.Idle(name, emoji)
        }
    }

    /** 按状态、主题和实际宽高比构建 RemoteViews */
    fun build(
        context: Context,
        size: WidgetSize,
        state: WidgetState,
        appWidgetId: Int,
        disabled: DisabledReason? = null
    ): RemoteViews {
        val dark = PrefsHelper.themeMode.equals("DARK", ignoreCase = true)
        val wide = size == WidgetSize.SMALL && isWide(context, appWidgetId)
        val views = RemoteViews(context.packageName, layoutRes(size, dark, wide))

        // 点整张卡片 → 打开 App
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, appWidgetId))

        when (state) {
            WidgetState.LoggedOut -> {
                views.setTextViewText(R.id.widget_device, "未登录")
                views.setTextViewText(R.id.widget_emoji, "🔒")
                // 未登录时不给按钮事件：点击会冒泡到整张卡片的「打开 App」，正好引导用户去登录
                bindButton(views, stopStyle = false, label = "开", onClick = null)
                bindSubLabel(views, size, "点此登录")
                bindMirrorToStatus(views, size, "未登录")
            }

            WidgetState.NoDevice -> {
                views.setTextViewText(R.id.widget_device, "还没有用过设备")
                views.setTextViewText(R.id.widget_emoji, "🚿")
                bindButton(views, stopStyle = false, label = "开", onClick = null)
                bindSubLabel(views, size, "点此打开淋浴")
                bindMirrorToStatus(views, size, "未绑定")
            }

            is WidgetState.Idle -> {
                views.setTextViewText(R.id.widget_device, state.deviceName)
                views.setTextViewText(R.id.widget_emoji, state.emoji)
                bindButton(
                    views, stopStyle = false, label = "开",
                    onClick = actionIntent(context, appWidgetId, disabled, LinYuWidgetProvider.ACTION_START)
                )
                bindSubLabel(views, size, disabled?.text ?: "上次使用的设备")
                bindMirrorToStatus(views, size, disabled?.text ?: "空闲")
            }

            is WidgetState.Running -> {
                views.setTextViewText(R.id.widget_device, state.deviceName)
                views.setTextViewText(R.id.widget_emoji, state.emoji)
                bindButton(
                    views, stopStyle = true, label = "关",
                    onClick = actionIntent(context, appWidgetId, disabled, LinYuWidgetProvider.ACTION_STOP)
                )
                bindSubLabel(views, size, disabled?.text ?: "上次使用的设备")
                bindMirrorToStatus(
                    views, size,
                    if (disabled != null) disabled.text else null,
                    chronometerBase = chronometerBase(state.startedAtMs)
                )
            }
        }
        return views
    }

    /**
     * 把「挂钟时间戳」换算成 Chronometer 要的基准。
     *
     * Chronometer 内部用 `SystemClock.elapsedRealtime()`（开机以来的毫秒数）算差值，
     * 而 startedAt 存的是 `System.currentTimeMillis()`（1970 年以来的毫秒数）。
     * 两个时钟原点差了十万八千里，直接传进去会显示成天文数字或负数。
     */
    private fun chronometerBase(startedAtMs: Long): Long {
        if (startedAtMs <= 0L) return SystemClock.elapsedRealtime()
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        return SystemClock.elapsedRealtime() - elapsed
    }

    /** 操作进行中时按钮要显示成什么 */
    enum class DisabledReason(val text: String) {
        STARTING("正在开启…"),
        STOPPING("正在关闭…"),
        REFRESHING("正在刷新…"),
        UNKNOWN("状态未知，点此刷新")
    }

    // ── 各控件的写入 ──

    /**
     * 圆形按钮：布局里放了「开」「关」两个 TextView，靠 visibility 切换。
     * 这样就不需要反射改背景（见类注释）。
     */
    private fun bindButton(
        views: RemoteViews,
        stopStyle: Boolean,
        label: String,
        onClick: PendingIntent?
    ) {
        val visibleId = if (stopStyle) R.id.widget_action_stop else R.id.widget_action_start
        val hiddenId = if (stopStyle) R.id.widget_action_start else R.id.widget_action_stop

        views.setViewVisibility(hiddenId, View.GONE)
        views.setViewVisibility(visibleId, View.VISIBLE)
        views.setTextViewText(visibleId, label)
        // 显式写 null 清掉上一次残留的点击：RemoteViews 复用同一个 View 对象，
        // 不写的话"正在开启…"这种禁用态还会带着上一轮的点击动作
        views.setOnClickPendingIntent(visibleId, onClick)
    }

    /** 2x2 的第二行说明文字；2x4 没有这个控件，跳过 */
    private fun bindSubLabel(views: RemoteViews, size: WidgetSize, text: String) {
        if (size != WidgetSize.SMALL) return
        views.setTextViewText(R.id.widget_sublabel, text)
    }

    /**
     * 2x4 右上角的状态位。
     * [text] 不为空时写死文本（空闲 / 进行中提示）；为 null 时改成走秒的计时器。
     * 必须先 setChronometer 再 setTextViewText，否则会被它自己的 updateText 覆盖。
     */
    private fun bindMirrorToStatus(
        views: RemoteViews,
        size: WidgetSize,
        text: String?,
        chronometerBase: Long = 0L
    ) {
        if (size != WidgetSize.WIDE) return
        if (text != null) {
            views.setChronometer(R.id.widget_status, 0L, null, false)
            views.setTextViewText(R.id.widget_status, text)
        } else {
            views.setChronometer(R.id.widget_status, chronometerBase, null, true)
        }
    }

    /**
     * 决定按钮点下去干什么。
     * - 空闲/使用中 → 对应的启停动作
     * - 正在开启/关闭 → 清掉点击，避免操作没结束就重复触发（点击会冒泡到整张卡片打开 App）
     * - 状态未知 → 给一个「刷新」，去服务端确认一次，而不是让用户自己猜
     */
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

    /**
     * 判断小组件当前是不是"宽"的（宽 > 高），用来决定 2x2 用竖向还是横向布局。
     * 首选情况下系统会给到当前尺寸；拿不到（为 0）时保守按竖向处理。
     */
    private fun isWide(context: Context, appWidgetId: Int): Boolean = try {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        w > 0 && h > 0 && w > h
    } catch (_: Exception) {
        false
    }

    private fun layoutRes(size: WidgetSize, dark: Boolean, wide: Boolean): Int = when {
        size == WidgetSize.WIDE ->
            if (dark) R.layout.widget_linyu_2x4_dark else R.layout.widget_linyu_2x4_light
        wide ->
            if (dark) R.layout.widget_linyu_2x2_wide_dark else R.layout.widget_linyu_2x2_wide_light
        else ->
            if (dark) R.layout.widget_linyu_2x2_dark else R.layout.widget_linyu_2x2_light
    }

    // ── PendingIntent ──
    // requestCode 必须每个 widget、每个动作都不同：
    // Android 按 (requestCode, Intent) 判重，重复会把先建的覆盖掉，
    // 表现就是"两个小组件的按钮互相触发对方的动作"。

    private fun openAppIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 1000 + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * ⚠️ 目标组件必须是 **Manifest 里注册过的 receiver**。
     *
     * 之前这里指向基类 [LinYuWidgetProvider]，而 Manifest 注册的是两个子类，
     * 广播发到一个没注册的组件会被系统静默丢弃——表现就是「点按钮毫无反应」。
     */
    private fun toggleIntent(context: Context, appWidgetId: Int, action: String): PendingIntent {
        val intent = Intent(context, providerClass(context, appWidgetId)).apply {
            this.action = action
            putExtra(LinYuWidgetProvider.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val code = when (action) {
            LinYuWidgetProvider.ACTION_START -> 2000
            LinYuWidgetProvider.ACTION_STOP -> 3000
            else -> 4000
        }
        return PendingIntent.getBroadcast(
            context, code + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 反查这个 widget id 属于哪个已注册的 Provider 子类 */
    private fun providerClass(context: Context, appWidgetId: Int): Class<*> {
        val manager = AppWidgetManager.getInstance(context)
        val small = manager.getAppWidgetIds(
            android.content.ComponentName(context, LinYuWidget2x2::class.java)
        )
        return if (appWidgetId in small) LinYuWidget2x2::class.java
        else LinYuWidget2x4::class.java
    }
}
