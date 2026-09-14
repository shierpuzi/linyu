package com.hualala.linyu.api

import com.google.gson.JsonParser
import com.hualala.linyu.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** GitHub 最新 Release 信息 */
data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val htmlUrl: String
)

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
    private const val REPO = "yehu-imei/linyu"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request =
        Request.Builder()
            .url("$BASE$path")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LinYu-Android")
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
            val o = JsonParser.parseString(json).asJsonObject
            GithubRelease(
                tagName = o.get("tag_name")?.asString ?: "",
                name = o.get("name")?.asString ?: "",
                body = o.get("body")?.asString ?: "",
                publishedAt = o.get("published_at")?.asString ?: "",
                htmlUrl = o.get("html_url")?.asString ?: ""
            )
        } catch (e: Exception) {
            AppLogger.e("解析 GitHub release 失败", e)
            null
        }
    }

    /** 全部 Release（最新在前，最多 10 条）；失败返回空列表 */
    suspend fun fetchReleases(): List<GithubRelease> {
        val json = getJson("/repos/$REPO/releases?per_page=10") ?: return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.map { el ->
                val o = el.asJsonObject
                GithubRelease(
                    tagName = o.get("tag_name")?.asString ?: "",
                    name = o.get("name")?.asString ?: "",
                    body = o.get("body")?.asString ?: "",
                    publishedAt = o.get("published_at")?.asString ?: "",
                    htmlUrl = o.get("html_url")?.asString ?: ""
                )
            }
        } catch (e: Exception) {
            AppLogger.e("解析 GitHub releases 失败", e)
            emptyList()
        }
    }

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

    /** 仓库主页（降级跳转用，不依赖网络） */
    const val REPO_URL = "https://github.com/$REPO"
}
