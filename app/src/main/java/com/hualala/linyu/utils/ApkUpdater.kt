package com.hualala.linyu.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.hualala.linyu.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** APK 下载状态 */
sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    /** [percent] 在 0~100；总长度未知时为 -1 */
    data class Running(val downloaded: Long, val total: Long, val percent: Int) : ApkDownloadState
    data class Done(val file: File) : ApkDownloadState
    data class Failed(val message: String) : ApkDownloadState
}

/**
 * 应用内下载更新包并调起系统安装器。
 *
 * 两个刻意的设计：
 *
 * 1. **下载跑在进程级作用域上**，不挂在 Compose 的 `rememberCoroutineScope` 上。
 *    否则用户切个 tab（页面被 AnimatedContent 销毁重建）就会把下载掐断。
 *    状态用 Compose State 暴露，界面回来时能直接接着显示进度。
 *
 * 2. **失败不抛异常**，都收敛成 [ApkDownloadState.Failed]。
 *    国内直连 GitHub 下 40MB 失败是常态，调用方需要拿到原因好引导用户走浏览器。
 */
object ApkUpdater {

    /** 供界面观察的下载状态 */
    var state by mutableStateOf<ApkDownloadState>(ApkDownloadState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 是否正在下载（界面用来禁掉重复点击） */
    val isDownloading: Boolean get() = state is ApkDownloadState.Running

    /** 清掉状态（下载完或失败后，用户再次点检查更新时调用） */
    fun reset() {
        if (state is ApkDownloadState.Running) return
        state = ApkDownloadState.Idle
    }

    /**
     * 开始下载。
     * @param fileName 保存到 cacheDir/updates/ 下的文件名，用发行版里的原始名
     */
    fun start(context: Context, url: String, fileName: String) {
        if (isDownloading) return
        val app = context.applicationContext
        job?.cancel()
        state = ApkDownloadState.Running(0, 0, 0)
        job = scope.launch {
            try {
                val file = download(app, url, fileName) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                    state = ApkDownloadState.Running(done, total, pct)
                }
                state = ApkDownloadState.Done(file)
            } catch (e: Exception) {
                state = ApkDownloadState.Failed(friendlyError(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = ApkDownloadState.Idle
    }

    /**
     * 下载（用 OkHttp 的阻塞式 execute，由调用方的 IO 协程承载）。
     *
     * 两个必要处理：
     * - 每读一块检查一次协程是否被取消。阻塞式 IO 本身不响应协程取消，
     *   不检查的话点了「取消下载」后台还会一直下完 40MB。
     * - 先写 `.part` 再改名，中途断了不会留下一个看着完整、实际是坏的文件。
     */
    private suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.part")

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "LinYu-Android")
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("响应为空")
                val total = body.contentLength()

                temp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 取消或失败都别留下半截文件占着缓存
            temp.delete()
            throw e
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /** 下载失败原因转成人话 */
    private fun friendlyError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("Unable to resolve host", true) ||
                m.contains("No address associated", true) -> "无法连接 GitHub，请检查网络或使用浏览器下载"
            m.contains("timeout", true) || m.contains("timed out", true) -> "下载超时，请重试或用浏览器下载"
            m.contains("Network is unreachable", true) -> "网络不可用"
            m.startsWith("HTTP ") -> "服务器返回 $m，请稍后重试"
            else -> e.message ?: "下载失败"
        }
    }

    // ════════════════════════════════════════════
    //  安装
    // ════════════════════════════════════════════

    /**
     * 调起系统安装器。
     *
     * Android 8.0 起安装未知来源应用需要单独授权，没授权时先把用户送到设置页，
     * 而不是直接抛 ActivityNotFoundException。
     */
    fun installApk(context: Context, file: File) {
        if (!file.exists()) return

        // Android 8.0+：检查「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** 用系统浏览器打开下载页（国内直连失败时兜底） */
    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 带 v 前缀的版本号比较：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionNumbers(latest)
        val b = versionNumbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** "v2.2.0" / "2.2.0" / "2.2.0-beta" → [2, 2, 0] */
    private fun versionNumbers(tag: String): List<Int> =
        tag.trim().trimStart('v', 'V')
            .split('.', '-', '+')
            .mapNotNull { it.toIntOrNull() }

    /** 当前 App 版本，形如 "v2.2.0" */
    val currentVersion: String get() = "v${BuildConfig.VERSION_NAME}"
}
