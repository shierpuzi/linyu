package com.quzhi.lite.api

import com.google.gson.JsonParser
import com.quzhi.lite.BuildConfig
import com.quzhi.lite.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 本项目的 GitHub 仓库（`owner/name` 形式）。
 *
 * 「关于项目」的仓库链接、作者名，以及「检查更新」拉 Release 都用这一个常量，
 * 不再各处硬编码——以前 owner 与仓库名散落在 3 个文件里，改名得改好几处。
 *
 * ## 2026-10-30 从 `shierpuzi/linyu` 改成 `shierpuzi/quzhiLite`
 *
 * 项目早就不只是「淋浴」了（洗衣/吹风/饮水/备份恢复/双通道），原名成了误导。
 *
 * ⚠️ **改名对老用户是安全的，但依赖 GitHub 的重定向**：
 * 已装 v3.2.1 / v3.2.2 的用户手机里存的是旧 slug，GitHub 对改名后的旧路径
 * 返回 301 并**一直有效**（除非有人用旧名新建了仓库）。改名前实测过：
 * `api.github.com/repos/shierpuzi/linyu` 仍返回 200 且 `full_name` 已是新名。
 *
 * 所以这里改了，但**不要**顺手去动 `local.properties`、签名、包名——那些和仓库名无关。
 */
const val REPO_SLUG = "shierpuzi/quzhiLite"

/** 仓库主页（网络不通时的降级跳转目标） */
const val REPO_URL = "https://github.com/$REPO_SLUG"

/**
 * Gitee 上的镜像仓库（`owner/name`）。
 *
 * **检查更新仍走 GitHub API**（`api.github.com` 实测能通，0.6 秒），
 * 只有**下载安装包**这一步走这里——GitHub 的 Release 附件在大陆走
 * `objects.githubusercontent.com`，实测只有约 100 KB/s，Gitee 约 2 MB/s。
 *
 * ## ⚠️ 三条硬约束（少一条这个旁路就废了）
 *
 * 1. **必须与 GitHub 同名 tag、同名附件**。这里不额外发请求，地址是**拼**出来的
 *    （见 [GithubRelease.giteeApkUrl]）：`gitee.com/<slug>/releases/download/<tag>/<附件名>`。
 *    少一个就是 404，且 App 里没有自动探测。
 * 2. **路径大小写敏感**：`quzhiLite` ≠ `quzhilite`。填错只有真机下载时才暴露。
 * 3. **仓库必须公开**：Gitee 私有仓库的 Release 附件**不能匿名下载**，
 *    除非把令牌塞进 App——那是安全事故，绝对不要做。
 *
 * 置空即关闭该旁路（下载回落到 GitHub 直链），不会导致崩溃。
 * 用户也可以在设置里手动切「国内镜像下载」开关（`PrefsHelper.updateUseMirror`）。
 */
private const val GITEE_REPO = "shierpuzi/quzhiLite"

/** 发行版里的一个附件（这里主要用 APK） */
data class GithubAsset(
    val name: String,
    /** 浏览器直链，OkHttp 下载也走这个 */
    val downloadUrl: String,
    val sizeBytes: Long
)

/** GitHub 最新 Release 信息 */
data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String,
    val assets: List<GithubAsset> = emptyList(),
    /**
     * 预发布（GitHub 上勾了 "pre-release" 的 Release）。
     *
     * 更新通道靠它分流：dev 包只看 true 的，正式包只看 false 的。
     * 见 [GithubApi.fetchReleases]。
     */
    val prerelease: Boolean = false
) {
    /** 发行版里的 APK 附件，取第一个 */
    val apkAsset: GithubAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", true) }

    /**
     * Gitee 镜像的下载直链。
     *
     * 不用额外发请求——两边的 tag 和附件名是一致的，直接拼就行：
     *   https://gitee.com/<owner>/<repo>/releases/download/v2.2.2/xxx.apk
     *
     * ⚠️ 前提是发版时 Gitee 那边用了**同名 tag、同名附件**。少一个就拼不出来，
     * 调用方要能回落到 [apkAsset] 的 GitHub 地址。
     *
     * 本项目暂无 Gitee 镜像（[GITEE_REPO] 为空），这里直接返回 null，
     * 下载走 GitHub 直链。
     */
    val giteeApkUrl: String?
        get() {
            if (GITEE_REPO.isBlank()) return null
            val a = apkAsset ?: return null
            if (tagName.isEmpty()) return null
            return "https://gitee.com/$GITEE_REPO/releases/download/$tagName/${a.name}"
        }
}

/**
 * 一次「按通道拉 Release」的结果。
 *
 * ⚠️ 存在的唯一理由：**把「请求失败」和「该通道没有版本」区分开**。
 * 以前 `fetchReleases()` 两种情况都返回 emptyList，界面只能一律显示
 * 「检测失败（多为网络原因）」——明明只是没发版，用户以为网络坏了。
 * 这里用 sealed 而不是 `List?`，是因为可空列表太容易被当成空列表处理，
 * 那正是上面这个 bug 的成因。
 */
sealed interface ReleaseResult {
    /** 拉取成功。[list] 可能为空：该通道确实没有版本，不是失败 */
    data class Ok(val list: List<GithubRelease>) : ReleaseResult
    /** 网络 / 解析失败，原因未知（国内直连 api.github.com 常这样） */
    data object Failed : ReleaseResult
}

/** GitHub 仓库信息 */
data class GithubRepoInfo(
    val fullName: String,
    val description: String,
    val stars: Int,
    val ownerLogin: String,
    val ownerAvatar: String,
    val htmlUrl: String
)

/**
 * GitHub 公开 API 客户端（读取本项目的 Release / 仓库信息）。
 *
 * ⚠️ 国内网络访问 api.github.com 可能失败，所有方法失败时返回 null，
 * 调用方应做降级处理（提示用户手动访问仓库）。
 */
object GithubApi {
    private const val BASE = "https://api.github.com"
    private const val REPO = REPO_SLUG

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request =
        Request.Builder()
            .url("$BASE$path")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "QuzhiLite-Android")
            .build()

    private suspend fun getJson(path: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(path)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.w("GitHub API ${resp.code} $path")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            // 国内直连常失败，属预期情况，仅记录不抛出
            AppLogger.w("GitHub API 请求失败 $path: ${e.message}")
            null
        }
    }

    /** 最新 Release；失败返回 null */
    suspend fun fetchLatestRelease(): GithubRelease? {
        val json = getJson("/repos/$REPO/releases/latest") ?: return null
        return try {
            parseRelease(JsonParser.parseString(json).asJsonObject)
        } catch (e: Exception) {
            AppLogger.e("解析 GitHub release 失败", e)
            null
        }
    }

    /**
     * 按更新通道拉 Release（最新在前，最多 10 条）。
     *
     * @param devChannel `true` 只返回 pre-release（测试版），`false` 只返回正式版。
     *   默认取当前安装包的 [BuildConfig.DEV_CHANNEL]，所以调用方不用关心自己是哪个 flavor。
     *
     * 两个包看到的列表**互不相交**：测试包只认测试版，正式包只认正式版。
     * 这也是「共存包」要求的一部分——测试版不该把普通用户拉下水，
     * 正式版也不该被一个 pre-release 提示「有新版本」。
     *
     * 失败返回 [ReleaseResult.Failed]，成功但该通道没版本返回 `Ok(emptyList())`，
     * 两者界面文案不同，别再合并成空列表。
     */
    suspend fun fetchReleases(
        devChannel: Boolean = BuildConfig.DEV_CHANNEL
    ): ReleaseResult {
        val json = getJson("/repos/$REPO/releases?per_page=10") ?: return ReleaseResult.Failed
        return try {
            val all = JsonParser.parseString(json).asJsonArray
                .map { el -> parseRelease(el.asJsonObject) }
            ReleaseResult.Ok(all.filter { it.prerelease == devChannel })
        } catch (e: Exception) {
            AppLogger.e("解析 GitHub releases 失败", e)
            ReleaseResult.Failed
        }
    }

    private fun parseRelease(o: com.google.gson.JsonObject): GithubRelease = GithubRelease(
        tagName = o.get("tag_name")?.asString ?: "",
        name = o.get("name")?.asString ?: "",
        body = o.get("body")?.asString ?: "",
        publishedAt = o.get("published_at")?.asString ?: "",
        htmlUrl = o.get("html_url")?.asString ?: "",
        prerelease = o.get("prerelease")?.asBoolean ?: false,
        assets = o.getAsJsonArray("assets")?.mapNotNull { el ->
            val a = el.asJsonObject
            val url = a.get("browser_download_url")?.asString
            val name = a.get("name")?.asString
            // 没有下载直链的附件直接跳过——没它就没法下载
            if (url.isNullOrEmpty() || name.isNullOrEmpty()) null
            else GithubAsset(name, url, a.get("size")?.asLong ?: 0L)
        } ?: emptyList()
    )

    /** 仓库信息；失败返回 null */
    suspend fun fetchRepoInfo(): GithubRepoInfo? {
        val json = getJson("/repos/$REPO") ?: return null
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            val owner = o.getAsJsonObject("owner")
            GithubRepoInfo(
                fullName = o.get("full_name")?.asString ?: REPO,
                description = o.get("description")?.asString ?: "",
                stars = o.get("stargazers_count")?.asInt ?: 0,
                ownerLogin = owner?.get("login")?.asString ?: "",
                ownerAvatar = owner?.get("avatar_url")?.asString ?: "",
                htmlUrl = o.get("html_url")?.asString ?: "https://github.com/$REPO"
            )
        } catch (e: Exception) {
            AppLogger.e("解析 GitHub 仓库信息失败", e)
            null
        }
    }

    /**
     * 仓库主页（降级跳转用，不依赖网络）。
     *
     * 指向文件级常量 [com.quzhi.lite.api.REPO_URL]，保持单一出处。
     */
    const val REPO_URL = com.quzhi.lite.api.REPO_URL
}
