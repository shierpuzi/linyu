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
        appWidgetIds.forEach { WidgetBridge.forget(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 先让父类处理 APPWIDGET_UPDATE / ENABLED / DISABLED / DELETED 这些系统广播
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (action != ACTION_START && action != ACTION_STOP && action != ACTION_REFRESH) return

        val id = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // goAsync：告诉系统"这个广播还没处理完，别回收进程"，最多约 10 秒
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAction(context, id, action)
            } catch (e: Exception) {
                // 小组件里没有地方弹错误，只能落日志；界面靠重新渲染回落到真实状态
                AppLogger.e("Widget action failed: $action", e)
            } finally {
                WidgetBridge.clearBusy(id)
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
            appWidgetId,
            when (action) {
                ACTION_STOP -> WidgetRenderer.DisabledReason.STOPPING
                ACTION_REFRESH -> WidgetRenderer.DisabledReason.REFRESHING
                else -> WidgetRenderer.DisabledReason.STARTING
            }
        )
        // 先画一次"进行中"，让用户点下去立刻有反馈
        WidgetBridge.renderId(context, appWidgetId)

        val snCode = PrefsHelper.lastDeviceSnCode
        if (snCode.isNullOrEmpty() || !PrefsHelper.isLoggedIn) {
            WidgetBridge.clearBusy(appWidgetId)
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
                    WidgetBridge.markUnknown(appWidgetId)
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
                WidgetBridge.clearUnknown(appWidgetId)
            }
        }
    }

    /** 渲染单个 widget（不含"进行中"覆盖） */
    private fun render(context: Context, id: Int): android.widget.RemoteViews {
        val disabled = WidgetBridge.busyReason(id)
        return WidgetRenderer.build(context, size, WidgetRenderer.readState(), id, disabled)
    }

    companion object {
        const val EXTRA_APPWIDGET_ID = "appWidgetId"
        const val ACTION_START = "com.hualala.linyu.widget.ACTION_START"
        const val ACTION_STOP = "com.hualala.linyu.widget.ACTION_STOP"
        const val ACTION_REFRESH = "com.hualala.linyu.widget.ACTION_REFRESH"
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
    private val busy = mutableMapOf<Int, WidgetRenderer.DisabledReason>()
    private val unknown = mutableSetOf<Int>()

    fun markBusy(id: Int, reason: WidgetRenderer.DisabledReason) {
        unknown.remove(id)
        busy[id] = reason
    }

    fun markUnknown(id: Int) {
        busy.remove(id)
        unknown.add(id)
    }

    fun clearUnknown(id: Int) {
        unknown.remove(id)
    }

    fun clearBusy(id: Int) {
        busy.remove(id)
        // unknown 不清：它表示"上一次操作结果未知"，要等用户手动刷新或 App 对齐后才消
    }

    /** widget 被从桌面删除时调用 */
    fun forget(id: Int) {
        busy.remove(id)
        unknown.remove(id)
    }

    /** App 侧刚对过账，桌面上残留的"状态未知"已经不准了，一律清掉 */
    fun clearAllUnknown() {
        unknown.clear()
    }

    fun busyReason(id: Int): WidgetRenderer.DisabledReason? = when {
        busy.containsKey(id) -> busy[id]
        unknown.contains(id) -> WidgetRenderer.DisabledReason.UNKNOWN
        else -> null
    }

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
            context, size, WidgetRenderer.readState(), id, busyReason(id)
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
        if (!matched) clearBusy(id)
    }
}

