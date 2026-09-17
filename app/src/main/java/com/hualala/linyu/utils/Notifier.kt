package com.hualala.linyu.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hualala.linyu.MainActivity
import com.hualala.linyu.R

/**
 * 系统通知。
 *
 * 两条渠道，按「要不要打扰人」分开：
 * - [CHANNEL_IN_USE] **低优先级**：用水期间常驻的状态条，不该响铃震动，
 *   跟音乐播放器那种常驻通知是同一类。用户可以单独把它静音而不影响别的通知。
 * - [CHANNEL_EVENTS] **默认优先级**：真正有事发生的通知（结束、自动关停、设备被占）。
 *
 * 每种通知都由 [PrefsHelper] 里对应的开关控制，用户在「我的 → 通知」里能单独关掉。
 */
object Notifier {

    private const val CHANNEL_IN_USE = "linyu_in_use"
    private const val CHANNEL_IN_USE_QUIET = "linyu_in_use_quiet"
    private const val CHANNEL_EVENTS = "linyu_events"

    /** 常驻那条的固定 id：同一个 id 反复 post 就是「更新」而不是「堆叠」 */
    const val ID_IN_USE = 1001
    const val ID_FINISHED = 1002
    const val ID_AUTO_CLOSED = 1003
    const val ID_OCCUPIED = 1004

    /** 通知里「结束使用」按钮的动作，由 ShowerWatchService 接 */
    const val ACTION_STOP_SHOWER = "com.hualala.linyu.notify.ACTION_STOP_SHOWER"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IN_USE,
                "用水状态",
                // LOW：不出声、不震动、不在锁屏弹，只在通知栏静静挂着
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "用水期间显示已用时间" }
        )
        // 「用水状态通知」关掉时用这条：IMPORTANCE_MIN 会让它折叠到通知栏最底部，
        // 不占状态栏图标、不打扰。
        //
        // ⚠️ 这里**不能直接不发通知**。Android 强制要求前台服务必须挂一条通知，
        // 没有它就起不来——而前台服务正是「App 被划掉也能自动关停」的唯一保障。
        // 所以关掉开关的语义是「把它收起来」，不是「关掉后台监控」。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_IN_USE_QUIET,
                "用水状态（静默）",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "关闭「用水状态通知」后，状态会折叠到这里" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "用水提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "开始、结束、自动关停等提醒" }
        )
    }

    /**
     * 通知能不能发。
     *
     * Android 13 起 `POST_NOTIFICATIONS` 是运行时权限，用户拒绝了这里就是 false。
     * 调用方据此决定要不要引导用户去设置里开——**不能假设通知一定能发出去**。
     */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 用水期间常驻的状态条。重复调用即更新同一条。
     *
     * **即使 [PrefsHelper.notifyInUse] 关着也照样发**，只是换到静默渠道——
     * 前台服务没有通知就起不来，而它是自动关停的唯一保障。见渠道创建的注释。
     */
    fun showInUse(context: Context, deviceName: String, startedAtMs: Long): android.app.Notification {
        ensureChannels(context)

        // 关掉时（总开关，或单独关掉「用水状态通知」）不发内容，
        // 只给一条满足 startForeground 要求的最小通知；服务拿到后会立刻摘掉，
        // 用户其实看不到（见 ShowerWatchService.attachForeground）
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) return minimal(context)

        val channel = if (PrefsHelper.notifyInUse) CHANNEL_IN_USE else CHANNEL_IN_USE_QUIET
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("正在使用 · ${shortName(deviceName)}")
            // Chronometer 由系统自己走秒，不需要我们每秒刷新通知
            .setUsesChronometer(true)
            .setWhen(if (startedAtMs > 0) startedAtMs else System.currentTimeMillis())
            .setShowWhen(true)
            .setOngoing(true)                        // 划不掉，跟服务同生共死
            .setOnlyAlertOnce(true)                  // 更新时不重复提醒
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, tab = 0))
            .addAction(stopAction(context))
            .build()

        notify(context, ID_IN_USE, n)
        return n
    }

    /**
     * 「正在结束…」：关阀确认 + 账单结算期间挂的过渡通知。
     *
     * 有它是因为停止流程被搬到了服务里——先挂上这条，服务才是前台服务；
     * 结算完再换成带金额的结束通知。
     */
    fun showStopping(context: Context, deviceName: String): android.app.Notification {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_IN_USE)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("正在结束 · $deviceName")
            .setContentText("确认关阀并结算中…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(context, ID_IN_USE, n)
        return n
    }

    fun cancelInUse(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_IN_USE) }
    }

    /** 手动停止 */
    fun showFinished(context: Context, deviceName: String, elapsedSec: Int, money: Double) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        showResult(context, ID_FINISHED, CHANNEL_EVENTS, "使用结束 · ${shortName(deviceName)}",
            elapsedSec, money, auto = false)
    }

    /** 设备超时自动关停 */
    fun showAutoClosed(context: Context, deviceName: String, elapsedSec: Int, money: Double) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyAutoClose) return
        showResult(context, ID_AUTO_CLOSED, CHANNEL_EVENTS, "设备已自动关停 · ${shortName(deviceName)}",
            elapsedSec, money, auto = true)
    }

    /** 想开的水正被别人用着 */
    fun showOccupied(context: Context, deviceName: String) {
        if (!PrefsHelper.notifyEnabled || !canNotify(context)) return
        ensureChannels(context)

        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("${shortName(deviceName)} 正在被他人使用")
            .setContentText("等对方用完再试，或换一台设备")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${shortName(deviceName)} 正在被他人使用。等对方用完再试，或者在小组件上「选用」换一台设备。"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, tab = 0))
            .build()

        notify(context, ID_OCCUPIED, n)
    }

    private fun showResult(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        elapsedSec: Int,
        money: Double,
        auto: Boolean
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)

        val timeText = formatDuration(elapsedSec)
        val moneyText = if (money > 0) "¥%.2f".format(money) else "无消费"
        val body = "用时 $timeText · 消费 $moneyText"

        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, tab = if (auto) 1 else 0))
            .build()

        notify(context, id, n)
    }

    /**
     * 设备名太长就省略**前面**，保留结尾的房号。
     *
     * 通知是系统渲染的，用不了 App 里的 TailEllipsisText，只能自己截。
     * 思路和它一致：「某小区 3号楼南 320房」把前面截掉留「…南 320房」，
     * 一眼能认出是哪间；反过来截尾巴只剩「某小区…」就白搭了。
     */
    private fun shortName(full: String, maxLen: Int = 14): String {
        val s = full.trim()
        return if (s.length <= maxLen) s else "…" + s.takeLast(maxLen - 1)
    }

    /** 总开关关掉时用的占位通知，只为了满足 startForeground，随即会被摘掉 */
    private fun minimal(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_IN_USE_QUIET)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("淋浴")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** 时长文案：1小时02分 / 12分34秒 / 45秒 */
    fun formatDuration(sec: Int): String {
        if (sec <= 0) return "0 秒"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "%d 小时 %02d 分".format(h, m)
            m > 0 -> "%d 分 %d 秒".format(m, s)
            else -> "%d 秒".format(s)
        }
    }

    private fun openApp(context: Context, tab: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
        }
        return PendingIntent.getActivity(
            context, 7000 + tab, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 「结束使用」按钮：把动作回传给正在跑的 ShowerWatchService */
    private fun stopAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, com.hualala.linyu.service.ShowerWatchService::class.java).apply {
            action = ACTION_STOP_SHOWER
        }
        val pi = PendingIntent.getService(
            context, 7100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_widget_stop, "结束使用", pi
        ).build()
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        // 权限可能在运行时被撤销，post 会抛 SecurityException
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
