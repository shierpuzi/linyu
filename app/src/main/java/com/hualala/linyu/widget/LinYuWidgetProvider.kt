package com.hualala.linyu.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.OpenOutcome
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面小组件。
 *
 * 只做三件事：渲染、承接按钮点击、调 [ShowerController]。
 * 所有业务逻辑都在 ShowerController 里，和 App 内是同一份。
 *
 * ## 为什么不用 WorkManager / Service
 * 小组件的点击是用户主动触发的广播，`goAsync()` 就能撑住几秒的网络往返，
 * 没必要为这点事引入后台任务框架。真正需要长时间跑的场景（实时消费推送）
 * 小组件也不做，见下方「已知取舍」。
 *
 * ## 已知取舍
 * - 不连 MQTT，所以使用中看不到实时消费金额，只显示预扣
 * - 停止后不做账单结算（要轮询最多 7 秒，超出广播预算），打开 App 会正常结算
 * - 开阀/关阀的确认轮询有 6 秒预算，超时显示「状态未知」而不是假装成功
 */
open class LinYuWidgetProvider : AppWidgetProvider() {

    /** 子类指定自己的尺寸 */
    open val size: WidgetSize get() = WidgetSize.SMALL

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetBridge.ensureInit(context)
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, render(context, id))
        }
    }

    /**
     * 用户拖动改变了小组件尺寸。
     *
     * 2x2 需要按宽高比在「按钮在下方」和「按钮在右侧」之间切换，
     * 不重绘的话布局会一直停在添加时的那个方向。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        WidgetBridge.ensureInit(context)
        WidgetBridge.render(context, appWidgetId, size)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 清掉这个 widget 的过渡态，免得反复增删后残留一堆无用条目
        appWidgetIds.forEach {
            WidgetBridge.forget(it)
            PrefsHelper.clearWidgetTab(it)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 先让父类处理 APPWIDGET_UPDATE / ENABLED / DISABLED / DELETED 这些系统广播
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (action != ACTION_START && action != ACTION_STOP &&
            action != ACTION_REFRESH && action != ACTION_SET_TAB
        ) return

        val id = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // 切换 2x4 页面：存下选中项再重绘，不需要走网络，同步处理即可
        if (action == ACTION_SET_TAB) {
            WidgetBridge.ensureInit(context)
            PrefsHelper.setWidgetTab(id, intent.getIntExtra(EXTRA_TAB, 0))
            WidgetBridge.renderId(context, id)
            return
        }

        // goAsync：告诉系统"这个广播还没处理完，别回收进程"，最多约 10 秒
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAction(context, id, action)
            } catch (e: Exception) {
                // 小组件里没有地方弹错误，只能落日志；界面靠重新渲染回落到真实状态
                AppLogger.e("Widget action failed: $action", e)
            } finally {
                WidgetBridge.clearBusy()
                // 必须刷新桌面上的**所有**小组件，不能只刷被点的那个：
                // 2x2 和 2x4 可能同时摆在桌面上，它们读的是同一份 Prefs，
                // 只刷一个的话另一个会一直停在旧状态（点了 2x2 开阀，2x4 还显示「空闲」）。
                WidgetBridge.renderAll(context)
                pending.finish()
            }
        }
    }

    private suspend fun handleAction(context: Context, appWidgetId: Int, action: String) {
        WidgetBridge.ensureInit(context)
        WidgetBridge.markBusy(
            when (action) {
                ACTION_STOP -> WidgetRenderer.DisabledReason.STOPPING
                ACTION_REFRESH -> WidgetRenderer.DisabledReason.REFRESHING
                else -> WidgetRenderer.DisabledReason.STARTING
            }
        )
        // 先画一次"进行中"，让用户点下去立刻有反馈。
        // 必须是 renderAll：操作状态是全局的，桌面上每个淋浴小组件都该同时进入
        // 「正在开启…/正在关闭…」，只重绘被点的那个会让另一个看起来没反应
        WidgetBridge.renderAll(context)

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty() || !PrefsHelper.isLoggedIn) {
            WidgetBridge.clearBusy()
            return
        }

        when (action) {
            ACTION_START -> when (val outcome = ShowerController.openValve(snCode)) {
                // Opened / Resumed：状态已落盘，重新渲染就会变成「使用中」
                is OpenOutcome.Opened, is OpenOutcome.Resumed ->
                    AppLogger.i("Widget 开阀成功 ($snCode)")
                is OpenOutcome.Failed ->
                    AppLogger.w("Widget 开阀失败: ${outcome.message}")
                // 预算耗尽：不谎报成功也不谎报失败，改成"点击刷新"由用户手动对齐
                OpenOutcome.Unknown -> {
                    AppLogger.w("Widget 开阀结果未知（预算耗尽）")
                    WidgetBridge.markUnknown()
                }
            }

            ACTION_STOP -> when (val outcome = ShowerController.closeValve(snCode, "")) {
                is CloseOutcome.Closed -> AppLogger.i("Widget 关阀成功 ($snCode)")
                is CloseOutcome.Failed -> AppLogger.w("Widget 关阀失败: ${outcome.message}")
            }

            // 「状态未知」状态下用户点按钮：按一次服务端真实状态，把本地对齐
            ACTION_REFRESH -> {
                val running = ShowerController.reconcile(snCode)
                AppLogger.i("Widget 手动刷新：服务端状态 running=$running")
                WidgetBridge.clearUnknown()
            }
        }
    }

    /** 渲染单个 widget（不含"进行中"覆盖） */
    private fun render(context: Context, id: Int): android.widget.RemoteViews {
        val disabled = WidgetBridge.busyReason()
        return WidgetRenderer.build(context, size, WidgetRenderer.readState(), id, disabled)
    }

    companion object {
        const val EXTRA_APPWIDGET_ID = "appWidgetId"
        const val ACTION_START = "com.hualala.linyu.widget.ACTION_START"
        const val ACTION_STOP = "com.hualala.linyu.widget.ACTION_STOP"
        const val ACTION_REFRESH = "com.hualala.linyu.widget.ACTION_REFRESH"
        const val ACTION_SET_TAB = "com.hualala.linyu.widget.ACTION_SET_TAB"
        const val EXTRA_TAB = "tab"
    }
}

/** 2x2 小组件入口 */
class LinYuWidget2x2 : LinYuWidgetProvider() {
    override val size = WidgetSize.SMALL
}

/** 2x4 小组件入口 */
class LinYuWidget2x4 : LinYuWidgetProvider() {
    override val size = WidgetSize.WIDE
}

/**
 * 供外部（Provider、App 内部）复用的工具方法。
 *
 * 拆出来是为了让 App 侧不用持有 Provider 实例也能刷新桌面——
 * AppWidgetProvider 由系统实例化，自己 new 一个语义上是错的。
 */
object LinYuWidget {

    /** App 内状态变化时调用，把桌面上的小组件全部刷新一遍 */
    fun refreshAll(context: Context) {
        WidgetBridge.ensureInit(context)
        // App 刚把状态对齐过，桌面上残留的"状态未知"不再成立。
        // 注意小组件自己操作完走的 [WidgetBridge.renderAll] 不带这一步——
        // 那时其他 widget 的"状态未知"仍然成立，不该被顺手清掉。
        WidgetBridge.clearAllUnknown()
        WidgetBridge.forEachWidget(context) { id, size ->
            WidgetBridge.render(context, id, size)
        }
    }
}

/** Provider 与 App 共用的内部实现 */
internal object WidgetBridge {

    private var initialized = false

    /**
     * 小组件可能在一个全新的进程里被唤起（App 完全没运行过），
     * 那时 PrefsHelper / NetworkModule 都还没初始化，必须先补上。
     */
    fun ensureInit(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        if (!PrefsHelper.isInitialized) PrefsHelper.init(app)
        AppLogger.init(app)
        NetworkModule.restoreFromPrefs()
        initialized = true
    }

    // ── 进行中状态 ──
    // 只存在内存里：进程被杀就丢，丢了也只是少一次"正在开启…"的过渡动画，不影响正确性
    // 全局一份，不按 widget id 分：
    // 一次操作本来就只可能有一个，按 id 分会造成"只有被点的那个显示正在关闭，
    // 另一个要等结束才同步"——用户明确反馈过这个问题
    private var busy: WidgetRenderer.DisabledReason? = null
    private var unknown = false

    fun markBusy(reason: WidgetRenderer.DisabledReason) {
        unknown = false
        busy = reason
    }

    fun markUnknown() {
        busy = null
        unknown = true
    }

    fun clearUnknown() {
        unknown = false
    }

    fun clearBusy() {
        busy = null
        // unknown 不清：它表示"上一次操作结果未知"，要等用户手动刷新或 App 对齐后才消
    }

    /** widget 被从桌面删除时调用 */
    fun forget(id: Int) {
        // 状态是全局的，单个 widget 被删不影响它
    }

    /** App 侧刚对过账，桌面上残留的"状态未知"已经不准了，清掉 */
    fun clearAllUnknown() {
        unknown = false
    }

    fun busyReason(): WidgetRenderer.DisabledReason? =
        busy ?: if (unknown) WidgetRenderer.DisabledReason.UNKNOWN else null

    /** 遍历桌面上所有「淋浴」小组件 */
    fun forEachWidget(context: Context, block: (id: Int, size: WidgetSize) -> Unit) {
        val manager = AppWidgetManager.getInstance(context)
        val entries = listOf(
            LinYuWidget2x2::class.java to WidgetSize.SMALL,
            LinYuWidget2x4::class.java to WidgetSize.WIDE
        )
        entries.forEach { (cls, size) ->
            manager.getAppWidgetIds(ComponentName(context, cls)).forEach { block(it, size) }
        }
    }

    fun render(context: Context, id: Int, size: WidgetSize) {
        val views = WidgetRenderer.build(
            context, size, WidgetRenderer.readState(), id, busyReason(),
            tab = PrefsHelper.widgetTab(id)
        )
        AppWidgetManager.getInstance(context).updateAppWidget(id, views)
    }

    /** 重绘桌面上所有「淋浴」小组件（2x2 与 2x4 一起） */
    fun renderAll(context: Context) {
        forEachWidget(context) { id, size -> render(context, id, size) }
    }

    /** 只重绘指定 id 的那个；没匹配到说明已被用户删除，顺手清掉它的过渡态 */
    fun renderId(context: Context, id: Int) {
        var matched = false
        forEachWidget(context) { wid, size ->
            if (wid == id) { render(context, id, size); matched = true }
        }
        if (!matched) clearBusy()
    }
}

