package com.quzhi.lite.data

import android.content.Context
import android.net.Uri
import com.quzhi.lite.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 备份文件的**读写**，走 SAF（Storage Access Framework）。
 *
 * ## 为什么是 SAF 而不是直接写 `/sdcard`
 *
 * 用户明确担心过「要不要给存储权限」。SAF 的回答是：**一个权限都不要**。
 * 用户在系统文件选择器里挑了一个位置，系统就把那一个 Uri 的读写权限授给本 App
 * （`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` 会带 `FLAG_GRANT_*`），
 * 用完即止，App 拿不到别的任何文件。
 *
 * 对比一下如果要自己写文件：
 * - API 29+ 要 `MANAGE_EXTERNAL_STORAGE`（上架会被审核拒，用户也会被吓到）
 * - 或者 `WRITE_EXTERNAL_STORAGE` + 一堆版本分支
 * 两条路都换不到任何好处——备份是**用户主动选位置**的一次性动作，
 * 文件选择器正好是那个交互。
 *
 * ## 为什么返回 Result 而不是抛异常
 *
 * 这里失败的原因几乎都是「外部世界的问题」（用户取消、U 盘拔了、目标 Uri 失效、
 * 网盘客户端没登录），不是程序 bug。用 Result 把「失败」变成正常返回值，
 * 调用方在 UI 里翻译成人话就行；抛异常的话每个调用点都要包 try/catch，
 * 漏一个就是崩溃。
 */
object BackupFileIo {

    /**
     * 读文件的上限。
     *
     * 文件选择器虽然限定了 `application/json`，但部分三方文件管理器不守规矩
     * （或者用户把视频改名成 .json）。备份包实际只有几 KB~几十 KB，
     * 16MB 已经宽松到不可能误伤，而 `readText()` 把几百 MB 读进内存会 OOM——
     * OOM 是 Error 不是 Exception，**catch 不住**，直接崩掉 App。
     */
    private const val MAX_BACKUP_BYTES = 16L * 1024 * 1024

    /** 建议文件名，转发到 [BackupFormat.suggestFileName]（格式定义只留一处） */
    fun suggestedFileName(nowMs: Long): String = BackupFormat.suggestFileName(nowMs)

    /**
     * 把文本写进用户选定的 Uri。
     *
     * @return 成功 / 失败。失败时 [Result.exceptionOrNull] 里的 message 可直接给用户看
     */
    suspend fun writeToUri(context: Context, uri: Uri, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // "wt" = 写入并**截断**。别用默认的 "w"：某些 DocumentsProvider
                // 对已存在的文档不截断，于是覆盖一个更短的内容会在文件末尾留下
                // 上一版的残余（JSON 就被截断成半截，恢复时报「损坏」，
                // 而且从文件内容根本看不出是 app 的锅）。
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext Result.failure(IOException("无法打开目标文件（可能已被系统回收）"))
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                AppLogger.i("备份已写入：${text.length} 字符")
                Result.success(Unit)
            } catch (e: Exception) {
                // Result.failure 要的是可读信息，异常类名对用户没意义
                AppLogger.w("备份写入失败：${e.javaClass.simpleName}: ${e.message}")
                Result.failure(toUserFacing(e, "写入"))
            }
        }

    /**
     * 从用户选定的 Uri 读出文本（UTF-8）。
     *
     * 不用 `runCatching`：它会连 [kotlinx.coroutines.CancellationException] 一起吞掉，
     * 用户切走页面、协程被取消时反而变成「读出成功了」。
     */
    suspend fun readFromUri(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ensureNotTooLarge(context, uri)
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IOException("无法打开所选文件"))
                // UTF-8 是备份文件的既定编码（BackupCodec 也用这个），不能靠平台默认
                val text = stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
                AppLogger.i("已读取备份文件：${text.length} 字符")
                Result.success(text)
            } catch (e: Exception) {
                AppLogger.w("备份读取失败：${e.javaClass.simpleName}: ${e.message}")
                Result.failure(toUserFacing(e, "读取"))
            }
        }

    /**
     * 读之前先看文件有多大。长度未知（-1，少见：某些流式 provider）就放过去，
     * 反正真正的备份文件很小。
     *
     * `ponytail:` 只挡「能报长度的大文件」；长度未知的超大流仍会读到内存里，
     * 升级路径是改成分块读到上限就停。
     */
    private fun ensureNotTooLarge(context: Context, uri: Uri) {
        val size = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (size > MAX_BACKUP_BYTES) {
            throw IOException("文件太大（${size / 1024 / 1024}MB），这看起来不是备份包")
        }
    }

    /**
     * 把 IOException 的英文 message 换成能直接展示的中文。
     *
     * 保留原 message 拼在后面：出问题时那串英文（`ENOENT`、`Permission Denial`）
     * 是我们排查的唯一线索，而前面的中文保证用户知道下一步能做什么。
     */
    private fun toUserFacing(e: Exception, action: String): IOException = when (e) {
        is FileNotFoundException -> IOException("找不到该文件，可能已被删除或移动", e)
        is SecurityException -> IOException("没有访问该文件的权限，请重新选择一次", e)
        else -> IOException("$action 失败：${e.message ?: e.javaClass.simpleName}", e)
    }
}
