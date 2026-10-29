package com.quzhi.lite.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.quzhi.lite.MainActivity
import com.quzhi.lite.R
import com.quzhi.lite.utils.MoneyFormat

/**
 * 系统通知。
 *
 * 两条渠道，按「要不要打扰人」分开：
 * - [CHANNEL_IN_USE] **低优先级**：用水期间常驻的状态条，不该响铃震动，
 *   跟音乐播放器那种常驻通知是同一类。用户可以单独把它静音而不影响别的通知。
 * - [CHANNEL_EVENTS] **默认优先级**：结束提醒这类「知道了就行」的通知。
 * - [CHANNEL_ALERT] **高优先级**：开阀失败 / 设备被占用 / 超时自动关停，会弹横幅。
 *   横幅必须 IMPORTANCE_HIGH，DEFAULT 不会弹——所以单独建了两条渠道按开关切。
 *
 * 每种通知都由 [PrefsHelper] 里对应的开关控制，用户在「我的 → 通知」里能单独关掉。
 */
object Notifier {

    /**
     * 渠道创建一次就固定了，之后 `createNotificationChannel` 对已存在的渠道**只会更新名字/描述**，
     * `setBypassDnd` 这类属性改了也不生效（Android 的行为：用户可改的设置不允许 App 偷偷改回）。
     *
     * 所以需要「穿透勿扰」这种新属性时，只能**换一个渠道 id**。
     *
     * ⚠️ 以后再加这类"用户可改"的渠道属性，继续递增后缀，不要试图改旧渠道。
     */
    private const val CHANNEL_IN_USE = "linyu_in_use"

    /**
     * **套餐计费设备（洗衣机 / 吹风机）专用渠道**，可穿透勿扰。
     *
     * ## ⚠️ 为什么带 `_v2` 后缀（重要，别改回去）
     *
     * Android 的 `NotificationChannel` **一旦创建，属性就固定了**：
     * 再调 `createNotificationChannel` 只会更新名字/描述，
     * `setBypassDnd` / `setSound` 这类「用户可改」的属性**不会应用**。
     *
     * 2026-10-24 实测：
     * 第一版用 `linyu_mode_order` 且当时没有 `ACCESS_NOTIFICATION_POLICY` 权限，
     * 渠道以 `mBypassDnd=false` 被创建并落盘。之后即使补上权限+声明，
     * 用同一个 id 再创建也不会变成 true —— 只能换新 id。
     *
     * 所以这次改成 `linyu_mode_order_v2`，让它重新走一遍创建流程。
     * **以后再加这类渠道属性，继续递增后缀，不要原地改。**
     *
     * 代价：老 id 的渠道会残留在系统设置里（用户可能看到两条同名渠道）。
     * 可以用 `deleteNotificationChannel` 清掉旧的，见 [ensureChannels]。
     */
    private const val CHANNEL_MODE_ORDER = "linyu_mode_order_v2"

    /** 作废的旧渠道 id，创建完新渠道后删掉，避免系统设置里出现重复项 */
    private const val CHANNEL_MODE_ORDER_OLD = "linyu_mode_order"
    private const val CHANNEL_IN_USE_QUIET = "linyu_in_use_quiet"
    private const val CHANNEL_EVENTS = "linyu_events"

    /** 会弹横幅的那一类（IMPORTANCE_HIGH） */
    private const val CHANNEL_ALERT = "linyu_alert"

    /** 关掉「横幅提醒」后改走这条（IMPORTANCE_DEFAULT，同一条通知不弹横幅） */
    private const val CHANNEL_ALERT_QUIET = "linyu_alert_quiet"

    /** 常驻那条的固定 id：同一个 id 反复 post 就是「更新」而不是「堆叠」 */
    const val ID_IN_USE = 1001
    const val ID_FINISHED = 1002
    const val ID_AUTO_CLOSED = 1003
    const val ID_OCCUPIED = 1004
    const val ID_OPEN_FAILED = 1005

    /**
     * 套餐计费设备（洗衣机 / 吹风机）的常驻倒计时通知。
     *
     * 单独一个 ID：它和热水器的「用水状态」是两条独立通道，
     * 共用 ID 会互相覆盖——同时用热水和吹风机时通知会打架。
     */
    const val ID_MODE_ORDER = 1006

    /** 套餐结束提醒（普通通知，可划掉） */
    const val ID_MODE_ORDER_DONE = 1007

    /**
     * 请求把通知提升为 Android 16 Live Update / ColorOS 流体云。
     *
     * 值取自 AOSP `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`，与 androidx
     * `NotificationCompat.Builder#setRequestPromotedOngoing` 写的是同一个键。
     * **这里用字面量而不是常量引用**：那个常量是 API 36.1 才加的，
     * 本工程 compileSdk 36 编译不到，且 androidx 1.12 也没有包装方法。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /** 状态栏胶囊极简文本（API 36 的 `setShortCriticalText` 写的就是这个键） */
    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    /** 通知里「结束使用」按钮的动作，由 ShowerWatchService 接 */
    const val ACTION_STOP_SHOWER = "com.quzhi.lite.notify.ACTION_STOP_SHOWER"

    /**
     * 本 App **能不能**让通知穿透勿扰。
     *
     * ## 现状：不需要它也能工作
     *
     * 用户明确选了「让它安静待着」（2026-10-24）：开了勿扰就是不想被打扰，
     * 强求穿透是越权。所以套餐倒计时**默认不穿透**，勿扰时段安静待着。
     *
     * 这个函数保留是为了：
     * 1. 日志里能说清「为什么勿扰时段没看到通知」
     * 2. 将来如果用户确实想在勿扰里看到倒计时，App 内可以提供入口跳去系统设置
     *
     * ⚠️ 不要用它去做「没权限就报错」之类的强校验——这是可选能力。
     */
    fun canBypassDnd(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
    }

    /**
     * 跳转到「勿扰模式访问权限」设置页。
     *
     * 只在用户主动点「我想在勿扰时也看到倒计时」时才调。
     * ⚠️ 必须 try/catch：部分 ROM 这个 Intent 可能不存在或被改过，崩溃不值得。
     */
    fun openDndAccessSettings(context: Context) {
        try {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // 打不开就算了，用户还能自己去系统设置里找
        }
    }

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
        // 套餐倒计时专用渠道。
        //
        // IMPORTANCE_DEFAULT：这条是「你正在被按分钟计费」的反馈，值得被看见一次。
        // 不用 IMPORTANCE_MIN：那种渠道会被 ROM 折叠到底部，用户根本找不到。
        //
        // ⚠️ **故意不设 setBypassDnd(true)**（用户 2026-10-24 的选择）：
        //    开勿扰就是不想被打扰，强求穿透是越权。
        //    勿扰时段倒计时安静待着，页面上仍正常显示剩余时间——功能没丢。
        //    另外设了也需要 ACCESS_NOTIFICATION_POLICY 特殊权限，没有权限时
        //    系统会**静默忽略**（实测渠道建出来 mBypassDnd 仍是 false）。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MODE_ORDER,
                "套餐倒计时",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "洗衣/吹风套餐的剩余时间"
                enableVibration(false)
                setSound(null, null)
            }
        )
        AppLogger.i(
            "套餐倒计时渠道就绪（设备支持勿扰穿透=" + canBypassDnd(context) + "，当前策略：不主动穿透）"
        )
        runCatching { nm.deleteNotificationChannel(CHANNEL_MODE_ORDER_OLD) }
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
            ).apply { description = "开始、结束等提醒" }
        )

        // ── 重要提醒：会**弹横幅**（heads-up）的那一类 ──
        //
        // ⚠️ 横幅要 IMPORTANCE_HIGH。DEFAULT 只是响一声、不会从屏幕顶上弹出来——
        // 之前三条渠道最高才 DEFAULT，所以全 App 一个横幅都没有。
        //
        // ⚠️ 渠道的 importance **创建之后改不了**（系统不允许 App 改已存在渠道），
        // 所以「关掉横幅」不能靠改这条渠道，只能另建一条 DEFAULT 的，
        // 发送时按开关二选一——和上面 IN_USE / IN_USE_QUIET 是同一套办法。
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "重要提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "开阀失败、设备被占用、超时自动关停，会从屏幕顶部弹出横幅" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT_QUIET,
                "重要提醒（不弹横幅）",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "关闭「横幅提醒」后，这些通知只响一声，不再弹横幅" }
        )
    }

    /**
     * 通知能不能发。
     *
     * Android 13 起 `POST_NOTIFICATIONS` 是运行时权限，用户拒绝了这里就是 false。
     * 调用方据此决定要不要引导用户去设置里开——**不能假设通知一定能发出去**。
     */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 用水期间常驻的状态条。重复调用即更新同一条。
     *
     * **即使 [PrefsHelper.notifyInUse] 关着也照样发**，只是换到静默渠道——
     * 前台服务没有通知就起不来，而它是自动关停的唯一保障。见渠道创建的注释。
     *
     * ## 标题为什么只剩模块名（2026-10-29 改）
     *
     * 以前是「正在使用 · 某小区 3号楼南 320房」。流体云胶囊左边固定画小图标、
     * 右边塞这一长串，结果是右侧被截断、左右极不配平（用户截图的原话）。
     * 胶囊单侧文本按 OPPO 规范必须精简，所以拆成三份：
     *
     * | 位置 | 内容 | 谁渲染 |
     * |---|---|---|
     * | 小图标 | 当前模块的 `ic_device_*` 单色矢量图 | 系统（会染白，所以只能用单色图） |
     * | 标题 | [DeviceVisual.moduleName]：饮水机 / 洗衣机 / 热水器（2 字） | 系统 |
     * | 正文 | 「正在使用 · 已用 N 分钟」 | 系统（不支持胶囊的 ROM 靠它兜底） |
     * | 右侧时间 | 系统 Chronometer，`setWhen(startAt)` 自己走秒 | 系统 |
     *
     * ⚠️ 设备名**从文案里去掉了**：一台设备在用时它是冗余信息（用户刚从那个页面出来），
     * 多台设备共用一个通知位置时走 [showInUseSummary]。参数保留是为了不动调用方签名
     * （服务那边本来就拿着 `deviceName`），将来若要恢复成带设备名的标题也不用改调用方。
     *
     * @param deviceName 现仅占位，不再进通知文案（见上）
     * @param emoji 当前模块的 emoji（`ActiveOrder.deviceEmoji` / `PrefsHelper.lastDeviceEmoji`），
     *        交给 [DeviceVisual] 映射成图标与模块名；传 null 兜底成热水器。
     * @param nowMs 当前时刻，测试可注入；生产走默认值。
     */
    fun showInUse(
        context: Context,
        snCode: String,
        deviceName: String,
        startedAtMs: Long,
        emoji: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): android.app.Notification {
        ensureChannels(context)

        // 关掉时（总开关，或单独关掉「用水状态通知」）不发内容，
        // 只给一条满足 startForeground 要求的最小通知；服务拿到后会立刻摘掉，
        // 用户其实看不到（见 ShowerWatchService.attachForeground）
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) return minimal(context)

        val startAt = if (startedAtMs > 0) startedAtMs else nowMs
        val elapsedSec = (((nowMs - startAt) / 1000L).coerceAtLeast(0L)).toInt()
        val iconRes = DeviceVisual.iconRes(emoji)
        val n = NotificationCompat.Builder(context, CHANNEL_IN_USE)
            // 胶囊左侧那个图标 = 当前模块的矢量图，不再是一律水滴
            .setSmallIcon(iconRes)
            // 大图标（展开态卡片左边那个大图）= 同一个模块的矢量图。
            // 用户原话「两个图标换成当前模块的 xml 矢量图」。
            // 这是**标准样式的 setLargeIcon**，不是自定义 RemoteViews——OPPO 流体云
            // 准入条件禁止的是后者。传矢量资源 id，系统自己按卡片尺寸缩放。
            //
            // ## 为什么**故意**不用定尺寸位图（Icon.createWithBitmap + Canvas 渲染）
            //
            // 2026-10-29 第一轮真机：大图标与右侧圆形按钮「大小不一致」的观感，
            // 根因不在图标本身——那张图是**文本按钮**排版（见 stopAction 的注释表格），
            // 两行网格根本没生成。改成图形按钮后排版整体换一套，尺寸得重新看。
            //
            // 而换成位图会有两个真实的坏处：
            // 1. **丢主题染色**：ic_device_* 是单色 #FF000000 矢量图，现在由 SystemUI
            //    按卡片明暗染色——2026-10-29 真机截图里，深色卡片上那两个图标是**白色**的，
            //    而源文件明明是黑色，说明染色确实发生了。
            //    一旦烘焙成位图就没这个通道：烘焙成黑色在深色卡片上直接看不见，
            //    烘焙成白色在浅色系统主题下又看不见。
            // 2. **对尺寸没用**：Android 通知模板把大图标塞进固定尺寸的 ImageView，
            //    矢量和位图都会被缩放到那个槽位，intrinsic 尺寸不影响最终大小。
            //    真正决定「大图标 vs 圆形按钮」大小和 y 轴对齐的是 ColorOS 的卡片布局。
            //
            // 所以这里保持最简实现。真机上如果确实要微调，唯一有意义的旋钮是
            // **给图标自身加内边距**（让图形占比变小），而不是改这段的位图尺寸。
            .setLargeIcon(
                android.graphics.drawable.Icon.createWithResource(context, iconRes)
            )
            // 胶囊左侧文字 = 短模块名。别改回「正在使用 · 设备名」，那段见上面的表格说明
            .setContentTitle(DeviceVisual.moduleName(emoji))
            // 展开态副文案：被标题精简掉的「正在使用」放这里。
            // 时间按**分钟**、不精确到秒（理由见 NotifyTextPolicy.REFRESH_INTERVAL_MS）
            .setContentText("正在使用 · " + NotifyTextPolicy.elapsedLine(elapsedSec))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("正在使用 · " + NotifyTextPolicy.elapsedLine(elapsedSec))
            )
            // Chronometer 由系统自己走秒，不需要我们每秒刷新通知
            .setUsesChronometer(true)
            .setWhen(startAt)
            .setShowWhen(true)
            .setOngoing(true)                        // 划不掉，跟服务同生共死
            .setOnlyAlertOnce(true)                  // 更新时不重复提醒
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 点它就是想回使用页看当前状态，不是回首页
            .setContentIntent(openApp(context, tab = 0, showShower = true))
            .addAction(stopAction(context, snCode))
            // ⚠️ 这里**故意没有「暂停」按钮**，不是漏了。
            //    后端没有 pause 接口：按量计费只有 closeOrder，一调就真结束并结算；
            //    套餐设备连停止接口都没有。放个点不动的按钮比没有更糟——
            //    用户会以为水停了，其实还在计费。
            //
            // 正向计时：不传 chipText，让系统用 Chronometer 自己走秒。
            // （传了会写进 shortCriticalText，把走秒盖成静态串，见 requestLiveUpdate 的优先级说明）
            .requestLiveUpdate()
            .build()

        notify(context, ID_IN_USE, n)
        return n
    }

    /** 多台设备共用一个前台通知位置，只显示汇总并引导回 App。 */
    fun showInUseSummary(context: Context, count: Int): android.app.Notification {
        ensureChannels(context)
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) return minimal(context)

        val n = NotificationCompat.Builder(context, CHANNEL_IN_USE)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("正在使用 $count 台设备")
            .setContentText("点按查看进行中的用水")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, tab = 0))
            .build()

        notify(context, ID_IN_USE, n)
        return n
    }

    /**
     * 「正在结束…」：关阀确认 + 账单结算期间挂的过渡通知。
     *
     * 有它是因为停止流程被搬到了服务里——先挂上这条，服务才是前台服务；
     * 结算完再换成带金额的结束通知。
     *
     * 小图标跟着当前模块走（不然点「结束使用」后胶囊图标会从饮水机跳回水滴，
     * 视觉上像是换了台设备）。标题仍是完整设备名：这条只在结算那一两秒出现，
     * 不会进流体云胶囊，也不存在被截断的问题。
     *
     * @param emoji 当前模块的 emoji，见 [DeviceVisual]；传 null 兜底成热水器。
     */
    fun showStopping(
        context: Context,
        deviceName: String,
        emoji: String? = null
    ): android.app.Notification {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_IN_USE)
            .setSmallIcon(DeviceVisual.iconRes(emoji))
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

    /**
     * 把一条常驻通知**请求提升为 Live Update / ColorOS 流体云**。
     *
     * ## 依据（逐条对上了官方准入条件）
     *
     * Android 官方《创建实时更新通知》列出的硬性要求，本函数只负责其中「靠 extras/分类」的两条，
     * 其余由调用方的 Builder 保证：
     *
     * | 要求 | 谁负责 |
     * |---|---|
     * | 标准样式（不允许自定义 RemoteViews） | 本函数旁边**绝不** `setCustomContentView` |
     * | 必须 `ongoing` | 调用方 `setOngoing(true)` |
     * | 必须设置 `contentTitle` | 调用方 |
     * | 不得是 group summary | 调用方不设 group |
     * | 不得 `setColorized(true)` | 调用方不设 |
     * | 渠道不得 `IMPORTANCE_MIN` | [ensureChannels]（DEFAULT） |
     * | 用 `EXTRA_REQUEST_PROMOTED_ONGOING` 请求提升 | **本函数** |
     * | 状态栏胶囊文本 `setShortCriticalText` | **本函数** |
     *
     * ⚠️ 注意「标准样式」禁的是 `setCustomContentView`（自绘 RemoteViews），
     * **不禁 `setLargeIcon` / `setStyle(BigTextStyle)`** —— 后者是系统自己渲染的标准件。
     * 所以 [showInUse] 里传模块矢量图当 largeIcon 是合规的。
     *
     * ## 为什么用 extras 字符串键，而不是 `setRequestPromotedOngoing()`
     *
     * 那个 Builder 方法是 **androidx.core 1.19.x** 才有的，而 1.19.x 要求
     * `minCompileSdk=37` / AGP 9.1 —— 本工程是 compileSdk 36 / AGP 8.13.2，升不上去。
     *
     * 但底层实现就是一个字符串键：`android.requestPromotedOngoing`（见 androidx 1.19.1
     * 里 `setRequestPromotedOngoing` 的字节码，它只做 `getExtras().putBoolean(...)`；
     * AOSP 同名常量 `EXTRA_REQUEST_PROMOTED_ONGOING` 值也是这个字符串）。
     * 所以用 `addExtras` 等价写进去，**不需要升依赖**。
     *
     * 注意 `EXTRA_REQUEST_PROMOTED_ONGOING` 本身是 **API 36.1** 才加的常量，
     * `android.permission.POST_PROMOTED_NOTIFICATIONS` 也是——两者在 compileSdk 36
     * 的 `android.jar` 里都没有，所以这里用字符串字面量 + manifest 里按字符串声明。
     *
     * ## ⚠️ 为什么不写 `shortCriticalText`（2026-10-24 修正，别再写回去）
     *
     * 第一版写了 `android.shortCriticalText`，真机实测**胶囊每 30 秒才跳一次**
     * （3:00 → 2:29），因为胶囊文本是静态字符串，只能靠重发刷新。
     *
     * 查 [Notification.Builder 文档](https://developer.android.google.cn/reference/android/app/Notification.Builder)
     * 确认了优先级（原文）：
     *
     * > Short critical text will always be **the highest precedence content for a chip**.
     * > A MetricStyle's critical metric is the next option.
     * > **A time (from Notification.when) is the final source of content for the chip.**
     * > If setUsesChronometer(true) is true, the chip content will be a chronometer,
     * > if positive (based on setChronometerCountDown).
     * > If setShowWhen(true) is true, the chip content will be the time remaining
     * > until when, if positive.
     *
     * 也就是说：**只要写了 `shortCriticalText`，它就把 Chronometer 挤掉了**。
     * 不写的话，系统会拿 `when` 自己算倒计时 —— 那个是**系统时钟驱动的**，
     * 每秒自己走，一次都不用重发。
     *
     * 所以现在：**默认不写胶囊文本**，交给 `setWhen(endAtMs)` + Chronometer。
     * 30 秒重发只用来刷新**正文**里的文字（那是给不支持芯片的 ROM 兜底的）。
     *
     * @param chipText 只在**确实需要自定义胶囊文案**时才传（正常倒计时传 `null`）
     */
    private fun NotificationCompat.Builder.requestLiveUpdate(
        chipText: String? = null
    ): NotificationCompat.Builder {
        val extras = android.os.Bundle().apply {
            // 提升请求：所有 Android 版本都写，系统不认识这个键时会直接忽略（无副作用）
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            // ⚠️ 只在调用方**明确要自定义胶囊文案**时才写。
            //    写了它就会压掉系统的 Chronometer 倒计时（见上面的优先级说明）。
            chipText?.let { putString(EXTRA_SHORT_CRITICAL_TEXT, it) }
        }
        addExtras(extras)
        // CATEGORY_STOPWATCH：官方和 OPPO 社区文档都点名这个分类，表示「计时/秒表」
        setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        return this
    }

    /**
     * 套餐计费设备（洗衣机 / 吹风机）运行中的常驻倒计时。
     *
     * ## 为什么用 Chronometer 而不是每秒重发通知
     *
     * `setUsesChronometer(true)` + `setChronometerCountDown(true)` 让**系统自己**每秒刷新，
     * 我们只需要发一次。每秒重发通知会被系统限流（尤其 Android 12+），
     * 而且耗电。热水器那条链路也是这么做的，保持一致。
     *
     * ## ⚠️ 但 Chronometer **不够**：必须有文字兜底
     *
     * 2026-10-24 真机实测（RMX3888 / Android 16 / ColorOS）：
     * 三个 Chronometer 元数据在 `dumpsys notification` 里全对
     * （`android.showChronometer=true` / `chronometerCountDown=true` / `when=` 正确），
     * 但通知栏右侧渲染出来的是**发送时刻**而不是倒计时芯片。
     * 即「交给系统走秒」在部分 OEM SystemUI 上不保证有 UI。
     *
     * 所以正文里显式写出「剩余 2分30秒」，由调用方按
     * [NotifyTextPolicy.REFRESH_INTERVAL_MS]（30 秒）重发刷新——
     * 芯片能画就画（更好看），画不出来用户也照样看得到剩余时间。
     *
     * ## 为什么没有「结束」按钮
     *
     * 套餐设备没有停止接口（扣全款、不可退），放个按钮点不动比没有更糟。
     * 只提供「查看」入口回 App。
     *
     * @param endAtMs 套餐**结束时刻**的绝对墙钟时间戳。
     *        ⚠️ 传绝对时刻而不是「总时长」，是为了让重发时倒计时**不会重置**——
     *        每次重发都按 `剩余 = endAt - now` 算，用户看到的就是连续倒数。
     * @param emoji 当前模块的 emoji（洗衣机 🧺 / 吹风机 💨），只用来换小图标。
     *        ⚠️ **只换图标，别顺手改别的**：标题/正文/chipText/timeoutAfter 这套
     *        套餐样式用户已经确认满意，2026-10-29 只加了这一处。
     *        另外——套餐设备**没有停止接口**（下单即扣全款、不可退），所以这条
     *        永远只有一个「查看」入口，不像按量那条有「结束使用」。
     */
    fun showModeOrderRunning(
        context: Context,
        deviceName: String,
        modeName: String,
        endAtMs: Long,
        priceYuan: String,
        emoji: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): android.app.Notification? {
        ensureChannels(context)
        if (!PrefsHelper.notifyEnabled) {
            AppLogger.w("套餐倒计时通知未发：总开关 notifyEnabled=false")
            return null
        }
        // Android 13+ 没这个权限，notify() 会静默失败（我们的 notify() 里直接 return）
        if (!canNotify(context)) {
            AppLogger.w("套餐倒计时通知未发：通知权限/系统开关不允许")
            return null
        }

        val remainingSec = (((endAtMs - nowMs) / 1000L).coerceAtLeast(0L)).toInt()
        // 到点后留给用户的宽限：到点不该立刻消失（用户可能还没看到），
        // 但**绝不能一直挂着**——实测 2026-10-24 10:08：一条 09:47 发的通知
        // 挂了 17 分钟，Chronometer 一直往下走变成负数。
        val lingerSec = 60
        val n = NotificationCompat.Builder(context, CHANNEL_MODE_ORDER)
            // 只换图标：一条通知里最能区分「这是洗衣机还是吹风机」的就是它
            .setSmallIcon(DeviceVisual.iconRes(emoji))
            .setContentTitle("$modeName · ${shortName(deviceName)}")
            // 正文 = 金额 + **文字剩余时间**（兜底，见上面的注释）
            .setContentText("¥$priceYuan · ${NotifyTextPolicy.remainingLine(remainingSec)} · 到点自动结束")
            // BigTextStyle：官方准入条件允许的标准样式之一，展开后不会被截断
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "¥$priceYuan · ${NotifyTextPolicy.remainingLine(remainingSec)} · 到点自动结束"
                )
            )
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endAtMs)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // ── 关键：到点后自动消失 ──
            //
            // `setOngoing(true)` 的通知系统**永远不会自己撤**，而 Chronometer 过了 when
            // 之后会继续往下数，显示成「-00:17」这种东西。必须自己安排撤销。
            //
            // ⚠️ 用 `setTimeoutAfter` 而不是在 ticker 里 cancel：
            //    ticker 活在 viewModelScope 里，App 被划掉就停了，那时没人撤通知。
            //    `setTimeoutAfter` 交给系统计时，**进程死了照样生效**——
            //    这是唯一不依赖 App 存活的方案。
            .setTimeoutAfter((remainingSec + lingerSec) * 1000L)
            // 用 DEFAULT（不是 LOW）：勿扰穿透要求渠道重要性足够高，
            // 而且这条是「正在计费」的反馈，值得被看见一次
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, tab = 0))
            // ── 胶囊文本：**永远由 App 提供** ──
            //
            // ⚠️ 这里最初是按官方文档「剩余 ≥2 分钟让系统按 when 自己渲染」做的，
            //    但 **ColorOS 16 实测不支持那条回退**：不写 shortCriticalText 时
            //    胶囊只有通知标题、没有时间（用户原话「大于2分钟还是这个样子」）。
            //
            // 所以现在无条件写：前段 `3分`（分钟级），最后 2 分钟 `01:30`（秒级）。
            // 粒度切换在 NotifyTextPolicy.chipText 里，这里不用管。
            .requestLiveUpdate(NotifyTextPolicy.chipText(remainingSec))
            .build()
        notify(context, ID_MODE_ORDER, n)
        return n
    }

    /**
     * 套餐时间到了的提醒。
     *
     * 用「大概率结束」的语气：服务端到点关停且不通知我们，
     * 本地只是按时间推算，所以文案是「预计已结束，请核对」而不是「已结束」。
     */
    fun showModeOrderFinished(context: Context, deviceName: String) {
        ensureChannels(context)
        cancelModeOrder(context)
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle("套餐时间到")
            .setContentText("${shortName(deviceName)} 预计已结束，请打开 App 核对")
            // 一次性提醒，标准样式（不请求流体云提升）
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, tab = 0))
            .build()
        notify(context, ID_MODE_ORDER_DONE, n)
    }

    fun cancelModeOrder(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_MODE_ORDER) }
        stopModeOrderAutoRefresh()
    }

    // ── 套餐倒计时通知的「自动刷新」循环 ──
    //
    // ## 为什么放在 Notifier 里，而不是 MainViewModel 的 ticker
    //
    // MainViewModel 的 ticker 活在 viewModelScope，**依赖界面活着**：
    // · 用户点了测试按钮后直接退出设置页 → ticker 没启动 → 倒计时胶囊永远停在 3:00
    // · App 被划掉 → ticker 停 → 通知留在那，Chronometer 变成负数
    //
    // 实测就是这两条：2026-10-24 用户看到「胶囊 3:00 不跟着走」和
    // 「倒计时变负数也不消失」。所以刷新循环必须**自己管自己**，
    // 用 Handler 挂在主线程（30 秒一次，开销可以忽略），生命周期由
    // `cancelModeOrder` / `setTimeoutAfter` 收尾。

    private var modeRefreshHandler: android.os.Handler? = null
    private var modeRefreshRunnable: Runnable? = null

    /**
     * 启动套餐倒计时通知的自刷新。
     *
     * 每 [NotifyTextPolicy.REFRESH_INTERVAL_MS]（30 秒）重发一次同一条通知，
     * 把「剩余 X 分 X 秒」正文和胶囊文本都更新掉；到点后自动停并撤通知。
     *
     * ⚠️ 幂等：重复调用只会重置计时节流，不会堆出多个 Handler。
     *
     * ⚠️ **emoji 必须一路透传**（2026-10-29 补）：这个循环每次重发都重新
     * `showModeOrderRunning(...)`，如果这里不接 emoji，第一次自刷新（30 秒后）
     * 小图标就悄悄退回默认热水器，而且全程无报错、只有肉眼能看出来。
     * 调用方只在开始/强制时进来这一次，后面几十次都是这个 Runnable 发的。
     *
     * @param endAtMs 结束的绝对时刻
     * @param deviceName / [modeName] / [priceYuan] 重发时要用的文案
     * @param emoji 当前模块的 emoji，只影响小图标；null 兜底热水器
     */
    fun startModeOrderAutoRefresh(
        context: Context,
        deviceName: String,
        modeName: String,
        endAtMs: Long,
        priceYuan: String,
        emoji: String? = null
    ) {
        stopModeOrderAutoRefresh()
        val app = context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        modeRefreshHandler = handler
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (now >= endAtMs) {
                    // 到点：撤掉倒计时，换成「时间到」提醒（如果通知开关允许）
                    AppLogger.i("套餐倒计时自刷新：到点，撤通知")
                    cancelModeOrderInternal(app)
                    stopModeOrderAutoRefresh()
                    return
                }
                // 没到点：重发一次刷新文字/胶囊。`setOnlyAlertOnce` 保证不响铃
                val left = ((endAtMs - now) / 1000L).toInt()
                val precise = NotifyTextPolicy.usePreciseChip(left)
                AppLogger.i(
                    "套餐倒计时自刷新：重发，剩余 ${left}s（${NotifyTextPolicy.remainingLine(left)}），" +
                        "胶囊=${NotifyTextPolicy.chipText(left)}" +
                        if (precise) "（秒级）" else "（分钟级）"
                )
                showModeOrderRunning(
                    app,
                    deviceName = deviceName,
                    modeName = modeName,
                    endAtMs = endAtMs,
                    priceYuan = priceYuan,
                    emoji = emoji,
                    nowMs = now
                )
                // 动态间隔：前段 30 秒（省电），最后 2 分钟 1 秒（秒级文本要准）
                handler.postDelayed(this, NotifyTextPolicy.refreshIntervalMs(left))
            }
        }
        modeRefreshRunnable = r
        val leftAtStart = ((endAtMs - System.currentTimeMillis()) / 1000L).toInt()
        handler.postDelayed(r, NotifyTextPolicy.refreshIntervalMs(leftAtStart))
        AppLogger.i(
            "套餐倒计时自刷新已启动：结束于 ${endAtMs}，剩余 ${leftAtStart}s，" +
                "首次刷新 +${NotifyTextPolicy.refreshIntervalMs(leftAtStart) / 1000}s"
        )
    }

    /** 只停循环，不动通知。给 [startModeOrderAutoRefresh] 内部和 [cancelModeOrder] 用 */
    private fun stopModeOrderAutoRefresh() {
        modeRefreshRunnable?.let { modeRefreshHandler?.removeCallbacks(it) }
        modeRefreshRunnable = null
        modeRefreshHandler = null
    }

    /** 撤通知但**不**停循环（循环自己会停下），避免 `cancelModeOrder` 递归 */
    private fun cancelModeOrderInternal(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_MODE_ORDER) }
    }

    /**
     * 清掉**已经过期**的套餐倒计时通知。
     *
     * ## 为什么需要它
     *
     * `setTimeoutAfter` 只在**发通知那一刻**排一次系统闹钟，而且只在
     * 「那条通知被 post 之后」有效。以下几种情况系统不会帮我们撤：
     *
     * 1. 老版本发的通知（那时还没有 `setTimeoutAfter`），升级后还挂着
     * 2. 用户在通知发出后手动改了系统时间 / 时区
     * 3. 进程被强杀，系统闹钟被 ROM 清掉（国产 ROM 常见）
     *
     * 所以 App 启动时主动核一次：通知栏里那条 1006 的 `when` 已经过去太久就撤掉。
     * **不依赖任何 ticker**。
     */
    fun cancelStaleModeOrder(context: Context) {
        val GRACE_MS = 2 * 60 * 1000L
        try {
            val nm = NotificationManagerCompat.from(context)
            val active = nm.activeNotifications ?: return
            val mine = active.firstOrNull { it.id == ID_MODE_ORDER } ?: return
            val endAt = mine.notification.`when`
            if (endAt > 0 && System.currentTimeMillis() - endAt > GRACE_MS) {
                AppLogger.i(
                    "撤掉过期的套餐倒计时通知：endAt=${endAt}，" +
                        "已过期 ${(System.currentTimeMillis() - endAt) / 1000} 秒"
                )
                cancelModeOrder(context)
            }
        } catch (_: Exception) {
            // 拿不到 activeNotifications（权限/ROM 差异）就算了，不影响主流程
        }
    }

    /**
     * **自检**：不花钱发一条「按量计费」常驻通知（ID 1001，[showInUse] 那条）。
     *
     * ## 为什么单独要这一条
     *
     * [debugProbeModeOrder] 发的是**套餐倒计时**（ID 1006，洗衣机/吹风机那种倒数），
     * 而按量那条（热水器/洗手台/饮水机，正向计时 + 「结束使用」按钮）是完全另一套
     * Builder。2026-10-29 改完图形按钮和模块矢量图后，这条在设置页**没有任何零成本入口**——
     * 想看它长什么样就只能真去开一次水（真扣钱）。所以补这个探针。
     *
     * 它走的是真实路径：同渠道 [CHANNEL_IN_USE]、同 ID [ID_IN_USE]、同样的
     * Chronometer 与 [stopAction] 图形按钮，所以「这里看到的」就是「真用起来看到的」。
     *
     * ⚠️ **不起前台服务**：自检只要通知本身，[showInUse] 内部已经 `notify()` 了。
     * 真起服务会去连 BLE / 轮询订单，反而危险。
     *
     * ⚠️ 这条通知**不会自己消失**（`setOngoing(true)`，模拟真实常驻），
     * 用户看完手动划掉；再点一次按钮就是原地更新同一条。
     */
    fun debugProbeInUse(context: Context): Result<android.app.Notification> = runCatching {
        AppLogger.i("通知自检（按量计量）：开始")
        ensureChannels(context)

        if (!PrefsHelper.notifyEnabled) {
            throw IllegalStateException("通知总开关是关的（设置-通知）")
        }
        // ⚠️ 这里必须查，不能让 showInUse 静默返回 minimal：
        //    `notifyInUse` 关着时 showInUse 会给一条「趣智Lite」占位通知（那是给
        //    startForeground 兜底的），用户点完自检只会看到一条空通知，
        //    会以为「功能坏了」而不是「开关没开」。
        if (!PrefsHelper.notifyInUse) {
            throw IllegalStateException("「用水状态通知」开关是关的，去设置里打开再试")
        }
        if (!canNotify(context)) {
            throw IllegalStateException("系统不允许本 App 通知，去系统设置里开")
        }

        // 🚰 = 饮水机。挑这个而不是热水器，是因为饮水机的图标（ic_device_faucet）
        // 和默认兜底的淋浴图标明显不同——这样一眼就能确认「模块矢量图真的传到位了」，
        // 万一 emoji 没传到、兜底成热水器，肉眼看得出差别。
        val emoji = "🚰"
        val n = showInUse(
            context,
            snCode = "DEBUG_PROBE_IN_USE",
            deviceName = "通知自检设备",
            // 从「现在」开始计时：用户要看的就是右侧那个 00:00 起跳的正计时，
            // 传过去的时刻会让它一上来就显示「已用 N 分钟」。
            startedAtMs = System.currentTimeMillis(),
            emoji = emoji
        )
        AppLogger.i(
            "通知自检（按量计量）：已发出 id=$ID_IN_USE，渠道=$CHANNEL_IN_USE，" +
                "emoji=$emoji → 模块名=${DeviceVisual.moduleName(emoji)}，" +
                "小图标=${DeviceVisual.iconRes(emoji)}"
        )
        n
    }

    /**
     * **自检**：不花钱发一条套餐倒计时通知，用来确认通知链路是否正常。
     *
     * ## 为什么需要
     *
     * 排查「倒计时通知不出来」时，正常路径必须先下一单（真扣钱，最低 0.6 元）。
     * 用户实测已经为此花了 1.8 元。这个探针把整条链路跑一遍：
     * `ensureChannels` -> `notify()` -> 系统是否真的呈现，**零成本**。
     *
     * 它发的就是真实那条（同一渠道、同一 ID、同样的 Chronometer），所以：
     * - 这里能出来、下单出不来 -> 问题在「下单后的调用时机」
     * - 这里也不出来 -> 问题在通知本身（渠道/权限/系统折叠）
     */
    fun debugProbeModeOrder(context: Context): Result<android.app.Notification?> = runCatching {
        AppLogger.i("通知自检：开始")
        ensureChannels(context)

        if (!PrefsHelper.notifyEnabled) {
            throw IllegalStateException("通知总开关是关的（设置-通知）")
        }
        if (!canNotify(context)) {
            throw IllegalStateException("系统不允许本 App 通知，去系统设置里开")
        }

        // 3 分钟，和「电吹风模式一」一致，肉眼能看出倒计时在动
        val endAt = System.currentTimeMillis() + 180_000L
        val n = showModeOrderRunning(
            context,
            deviceName = "通知自检设备",
            modeName = "测试套餐",
            endAtMs = endAt,
            priceYuan = "0.00",
            // 自检没有真实设备，emoji 传 null（兜底热水器图标）。这里**显式写出 null**
            // 而不是靠默认值省略：读代码的人才知道「这是刻意没有设备类型」，
            // 不是漏传。
            emoji = null
        )
        if (n == null) throw IllegalStateException("showModeOrderRunning 返回 null")
        // ⚠️ 必须启自动刷新：否则胶囊永远停在「3:00」、正文停在「剩余 3 分 0 秒」，
        //    到点也不会消失（实测 2026-10-24：一条 09:47 发的通知挂了 17 分钟，
        //    Chronometer 走成负数）。原因就是测试按钮只发通知、没人驱动刷新。
        startModeOrderAutoRefresh(
            context,
            deviceName = "通知自检设备",
            modeName = "测试套餐",
            endAtMs = endAt,
            priceYuan = "0.00",
            emoji = null
        )
        AppLogger.i("通知自检：已发出 id=$ID_MODE_ORDER，渠道=$CHANNEL_MODE_ORDER，已启自动刷新")
        n
    }

    /**
     * **自检**：零成本发一条「事件类」通知，用来核对普通通知的样式对不对。
     *
     * 和 [debugProbeModeOrder] 分工：那条验证**倒计时/流体云**那条链路，
     * 这条验证**普通通知**（结束提醒、失败提示）的标题/正文/横幅样式。
     *
     * 用 [ID_MODE_ORDER_DONE]（1007）而不是占用真实业务的那些 id，
     * 这样点一下就能划掉，不会跟真实的「使用结束」抢位置。
     */
    fun debugProbeEvent(context: Context): Result<android.app.Notification?> = runCatching {
        AppLogger.i("通知自检（事件类）：开始")
        ensureChannels(context)
        if (!PrefsHelper.notifyEnabled) {
            throw IllegalStateException("通知总开关是关的（设置-通知）")
        }
        if (!canNotify(context)) {
            throw IllegalStateException("系统不允许本 App 通知，去系统设置里开")
        }

        val title = "通知自检 · 事件类"
        val body = "这就是「使用结束 / 开阀失败」那类通知的标准样式：标题 + 正文 + 展开可看全文。"
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context, tab = 0))
            .build()
        notify(context, ID_MODE_ORDER_DONE, n)
        AppLogger.i("通知自检（事件类）：已发出 id=$ID_MODE_ORDER_DONE，渠道=$CHANNEL_EVENTS")
        n
    }

    /**
     * 「事件类」通知的**唯一出口**。
     *
     * 以前 showFinished / showAutoClosed / showOccupied / showOpenFailed 是四份
     * 各自十几行的 Builder 复制粘贴，渠道和优先级还得各写一遍——而这两件事**必须成对**，
     * 漏了优先级某些 ROM 就静默不弹（这个坑注释里记着，但复制时照样会漏）。
     *
     * @param alert true = 横幅类（开阀失败 / 设备被占用 / 超时关停），从屏幕顶上弹出
     * @param tab   点通知落到哪个 tab：0 首页 / 1 账单
     */
    private fun postEvent(
        context: Context,
        id: Int,
        alert: Boolean,
        title: String,
        body: String,
        tab: Int = 0
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)

        // 渠道和优先级**一次算出来**。分成两个函数的话，调用方得自己记住
        // 「传了 alert 渠道就得配 alert 优先级」，而类型上没有任何约束
        val (channel, priority) = if (!alert) {
            CHANNEL_EVENTS to NotificationCompat.PRIORITY_DEFAULT
        } else if (PrefsHelper.notifyAlert) {
            CHANNEL_ALERT to NotificationCompat.PRIORITY_HIGH
        } else {
            CHANNEL_ALERT_QUIET to NotificationCompat.PRIORITY_DEFAULT
        }

        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_shower)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // 事件类**不**请求流体云提升（见 requestLiveUpdate 的说明）：
            // 官方使用条件明确「已结束的事情属于标准通知」，
            // 把一次性提醒塞进流体云属于滥用，会被用户关掉提升权限甚至撤销通知权限。
            // 但分类要标对：失败类用 ERROR，其余用 EVENT——
            // 分类影响系统排序和「勿扰/优先会话」的判断，不标就落在无分类的低优档。
            .setCategory(if (alert) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(openApp(context, tab))
            .build()

        notify(context, id, n)
    }

    /**
     * 结果条正文。
     *
     * ⚠️ `money == null`（两条路都没拿到金额）和 `money == 0`（账单在、金额确实是 0）
     * **必须分开说**。以前一律显示「无消费」，于是「没查到」被说成了「没花钱」——
     * 用户明明用了热水，通知却告诉他无消费。
     */
    private fun resultBody(elapsedSec: Int, money: Double?): String {
        val timeText = formatDuration(elapsedSec)
        return when {
            money == null -> "用时 $timeText · 结算中"
            money > 0 -> "用时 $timeText · 消费 ${MoneyFormat.withSymbol(money)}"
            else -> "用时 $timeText · 无消费"
        }
    }

    /**
     * 「正在结算…」——关阀已经确认，账单金额还没回来。
     *
     * ⚠️ 用和结束通知**同一个 id**（[ID_FINISHED] / [ID_AUTO_CLOSED]），
     * 所以拿到金额后 `showFinished` / `showAutoClosed` 会把这一条**原地更新**掉——
     * 用户看到的是同一条通知从「结算中」变成「消费 ¥x.xx」，而不是先蹦一条再蹦一条。
     *
     * 不发横幅（`alert = false`）：结算只是个过渡态，没必要响两遍。
     */
    fun showSettling(context: Context, id: Int, title: String, elapsedSec: Int) {
        if (!PrefsHelper.notifyEnabled) return
        postEvent(context, id, alert = false, title = title,
            body = "用时 ${formatDuration(elapsedSec)} · 结算中…")
    }

    /**
     * 停止请求发出去了，但**没确认到设备真的停了**（多半是断网）。
     *
     * ⚠️ 这条不能省，也不能拿「使用结束」凑合。水可能还在流、钱还在扣，
     * 报一条「使用结束」就是**谎报**——用户据此走人，问题要到看账单时才发现。
     *
     * 文案刻意短：标题只写状态、正文只带设备名 + 后果。
     * 以前正文塞的是 `closeResult.message`，而那条消息长这样——
     * 「关阀请求没发出去：Unable to resolve host "v3-api.china-qzxy.cn"」，
     * 异常类名和主机名全在里面，通知栏折成三行，用户一个字都得不到有用信息。
     * 具体原因仍然进日志（`AppLogger.w`），那是排查用的，不是给用户看的。
     *
     * 用 [ID_FINISHED] 是对的：它和「结算中」「使用结束」抢同一个位置，
     * 三种结果互斥，不该在通知栏里并排躺着。
     */
    fun showCloseUnconfirmed(context: Context, deviceName: String) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        postEvent(context, ID_FINISHED, alert = true,
            title = "关阀状态未确认 · ${shortName(deviceName)}",
            body = "网络异常，设备可能仍在用水，请重试",
            tab = 0)
    }

    /** 服务端明确拒绝关阀，保留停止入口并展示服务端原因。 */
    fun showCloseFailed(context: Context, deviceName: String, reason: String) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        postEvent(context, ID_FINISHED, alert = true,
            title = "关阀失败 · ${shortName(deviceName)}",
            body = reason,
            tab = 0)
    }

    /** 手动停止。点通知去账单页——刚消费完，多半想看这笔账 */
    fun showFinished(context: Context, deviceName: String, elapsedSec: Int, money: Double?) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyFinished) return
        postEvent(context, ID_FINISHED, alert = false,
            title = "使用结束 · ${shortName(deviceName)}",
            body = resultBody(elapsedSec, money),
            tab = 1)
    }

    /** 设备超时自动关停。同样去账单页 */
    fun showAutoClosed(context: Context, deviceName: String, elapsedSec: Int, money: Double?) {
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyAutoClose) return
        postEvent(context, ID_AUTO_CLOSED, alert = true,
            title = "设备已自动关停 · ${shortName(deviceName)}",
            body = resultBody(elapsedSec, money),
            tab = 1)
    }

    /**
     * 想开的水正被别人用着。这条不是「某一单在用」，回首页就行。
     *
     * 正文刻意只留「正在被他人使用」。以前后面还跟着一句
     * 「等对方用完再试，或者在小组件上『选用』换一台设备」——通知栏放不下，
     * 折成两三行反而把「哪台设备被占了」这个关键信息挤没了。
     */
    fun showOccupied(context: Context, deviceName: String, alert: Boolean = true) {
        if (!PrefsHelper.notifyEnabled) return
        // 标题只写状态、正文才带设备名：设备名很长（「某小区 3号楼南 3层 320房」），
        // 放在标题里会把通知栏那一行占满，一眼看不出是"发生了什么"
        postEvent(context, ID_OCCUPIED, alert = alert,
            title = "设备占用中",
            body = "${shortName(deviceName)}正在被他人使用")
    }

    /**
     * 开阀失败（服务端拒绝：余额不足、有未扣账单、账户异常…）。
     *
     * 桌面小组件那边只显示得下「开阀失败」四个字，完整原因放不下，
     * 所以**由这条通知把服务端的原话带出来**——开阀失败时用户多半正在桌面上
     * 点小组件，横幅是他唯一能看到原因的地方。
     */
    fun showOpenFailed(context: Context, deviceName: String, reason: String) {
        if (!PrefsHelper.notifyEnabled) return
        postEvent(context, ID_OPEN_FAILED, alert = true,
            title = "开阀失败 · ${shortName(deviceName)}",
            body = reason)
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
            .setContentTitle("趣智Lite")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** 时长文案：1小时02分 / 12分34秒 / 45秒（实现在 [NotifyTextPolicy]，那里能纯 JVM 单测） */
    fun formatDuration(sec: Int): String = NotifyTextPolicy.formatDuration(sec)

    /**
     * 打开 App。
     *
     * @param showShower true = 点进来直接落到**使用页**而不是首页。
     *        点「正在使用」那条通知的人就是想看当前这单用水的状态，
     *        把他扔到首页还得自己再找一次。
     */
    private fun openApp(context: Context, tab: Int, showShower: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, tab)
            if (showShower) putExtra(MainActivity.EXTRA_SHOW_SHOWER, true)
        }
        // requestCode 要把 showShower 也带上：两个 PendingIntent 只有 extra 不同的话，
        // FLAG_UPDATE_CURRENT 会让后建的覆盖先建的，结果点哪个都进同一个页面
        return PendingIntent.getActivity(
            context, (if (showShower) 7600 else 7000) + tab, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 「结束使用」按钮：把动作回传给正在跑的 ShowerWatchService。
     *
     * ## ⚠️ 标题必须是 null —— 这是「图形按钮」，不是「文本按钮」（2026-10-29 真机定案）
     *
     * OPPO 流体云文档（open.oppomobile.com/documentation/page/info?id=12658）原文：
     * > 按钮支持**文本按钮和图形按钮**，开发者只能选用其中一种，图形按钮最多支持显示 2 个
     *
     * 也就是说这两种是**互斥的两套排版**，区分依据就是 action 有没有文字标题：
     *
     * | 标题 | ColorOS 渲染 | 真机实测（RMX3888 / Android 16 / ColorOS 16） |
     * |---|---|---|
     * | `"结束使用"` | **文本按钮**：卡片底部一条整行宽的胶囊 | 2026-10-29 就是这个：三行列表，左大图 + 中标题/时间 + 右小图 + 底部整行按钮 |
     * | `null` | **图形按钮**：右侧一个圆形按钮，里面画 action 图标 | 目标形态（用户给的图一） |
     *
     * 用户要的是图一那种两行网格（第一行 小图标+模块名 / 大号时间，第二行 大图 / 红色圆形按钮），
     * 所以这里**必须传 null**。⚠️ 别好心把文字加回去——它不会在图形按钮上显示，
     * 只会把整张卡片退回文本按钮排版，「胶囊不对称」的问题就复发了。
     *
     * ## 无障碍
     *
     * 图形按钮没有可见文字，`NotificationCompat.Action.Builder` 在 androidx 1.12 里
     * **没有** setContentDescription（平台侧也没有），只能退而求其次：
     * 1. 标题本身给 null 是**内容**问题，被读屏念出来的是图标资源的 contentDescription——
     *    矢量图资源没有，所以读屏对这颗按钮基本说不出名字；
     * 2. 补偿办法是把语义留在**能读的地方**：正文里的「正在使用 · 已用 N 分钟」+
     *    通知本身的标题（模块名），用户听到通知就知道发生了什么；
     * 3. `setSemanticAction` 在这里没有对应语义值（SEMANTIC_ACTION_NONE 是唯一选项），
     *    加了也只是噪音，所以不加。
     *
     * 真机上如果发现 ColorOS 不吃图形按钮（还是渲染成文本按钮），说明这台 ROM 的
     * SystemUI 只实现了文本按钮分支——那不是我们参数写错了，别再去调标题。
     */
    private fun stopAction(context: Context, snCode: String): NotificationCompat.Action {
        val intent = Intent(context, com.quzhi.lite.service.ShowerWatchService::class.java).apply {
            action = ACTION_STOP_SHOWER
            putExtra(com.quzhi.lite.service.ShowerWatchService.EXTRA_SNCODE, snCode)
        }
        val pi = PendingIntent.getService(
            // ⚠️ 用按 snCode 稳定分配的 id，不能是 hashCode()——碰撞会停错设备（见 PrefsHelper）
            context, PrefsHelper.notifyRequestCode(snCode), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // ⚠️ 用带 Context 的 IconCompat 重载，不要用 `Action.Builder(资源 id, ...)`：
        //    那个重载内部走 `IconCompat.createWithResource(null, "", id)`，包里没有包名，
        //    图标要跨进程交给 SystemUI 解析，空包名在部分 ROM 上会解析不到。
        //    带 Context 的版本会把本应用包名一起带上，SystemUI 才能把矢量图取出来。
        return NotificationCompat.Action.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(
                context, R.drawable.ic_notify_stop_filled
            ),
            null,        // ← 见上面注释：图形按钮 = 无标题。这一行就是「圆形按钮」的开关
            pi
        ).build()
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        // 权限可能在检查后、实际发送前被撤销，仍保留异常保护。
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            Unit
        }
    }
}
