package com.quzhi.lite.data

import com.quzhi.lite.BuildConfig
import com.quzhi.lite.utils.AppLogger
import com.quzhi.lite.utils.PrefsHelper

/**
 * 「备份 / 恢复」在 **App 状态**这一层的胶水：从 Prefs 拼出 [BackupPayload]，
 * 或把 [BackupPayload] 写回 Prefs。
 *
 * ## 它不做什么（这些都是故意划出去的）
 *
 * | 不管的事 | 归谁管 | 为什么 |
 * |---|---|---|
 * | 文件的选/读写 | [BackupFileIo]（SAF） | 本类不碰 Uri，方便单测和以后换存储位置 |
 * | 加密 / 解密的算法细节 | [BackupCodec] | 只调 `encode`/`decode`，绝不自己拼 JSON |
 * | 恢复凭证后「这个 loginCode 还能用吗」 | UI 层（调登录校验接口） | 那是网络请求，而且要先把用户的话说清楚（失效时提示重登），本类只管写本地 |
 *
 * ## 为什么恢复收藏要「合并」而不是「覆盖」
 *
 * 用户多半是「换手机后从备份恢复」，但也很可能是「误点了恢复」。覆盖会**直接清掉**
 * 他这台机器上好不容易攒的收藏（其中可能还有备份之后新加的），而且无法撤销。
 * 合并的代价只是可能多几条重复——而重复由 [FavoriteStore.add] 按 mac 去重，
 * 上限由 [FavoriteStore.MAX_FAVORITES] 兜住，没有数据损失。
 */
object BackupService {

    /**
     * 恢复结果，给 UI 拼提示文案用。
     *
     * [credentials] 是把备份包里那份**原样**回传，不含任何验证结论——
     * UI 拿它去调登录校验，成功才真正算「恢复了这个账号」，然后调 [saveCredentials] 落盘。
     * （`restore()` 默认**不写**凭证，理由见那里的注释。）
     */
    data class RestoreResult(
        /** 本次**新增**的收藏条数（已存在的按 mac 合并更新，不重复计数） */
        val favoritesRestored: Int,
        /** 是否把凭证写进了本地 Prefs。默认 false——包里有凭证时应当由 UI 先鉴权 */
        val credentialsRestored: Boolean,
        /** 备份包里的凭证（可能为 null：这个包只有收藏） */
        val credentials: BackupCredentials?
    )

    /**
     * 从当前 App 状态收集一个备份负载。
     *
     * @param favorites 是否备份收藏列表
     * @param credentials 是否备份登录凭证
     */
    fun collect(favorites: Boolean, credentials: Boolean): BackupPayload = BackupPayload(
        favorites = if (favorites) PrefsHelper.favorites() else null,
        credentials = if (credentials) currentCredentials() else null,
        // 版本号只用于「这个包来自更新的版本」这类提示，不参与任何兼容判断
        appVersion = BuildConfig.VERSION_NAME,
        createdAtMs = System.currentTimeMillis()
    )

    /**
     * 把备份包写回本地 App 状态。
     *
     * ⚠️ **不发起任何网络请求**：恢复凭证后要不要拿它去校验、校验失败怎么提示，
     * 由 UI 层决定（见 `ui/BackupCards.kt` / `LoginScreen` 的调用处）。
     * 本类一旦联网，就变成「恢复一下就悄悄换掉了登录态」，用户完全不知情。
     *
     * ## ⚠️ 含凭证的包请**不要**传 writeCredentials=true
     *
     * 正确顺序是：`restore(payload)` → 拿 `result.credentials` 去调登录校验 →
     * 校验通过再调 [saveCredentials]。默认值刻意取 false 就是这个原因：
     * 「忘了传参数」的后果必须是「凭证没写进去」而不是「写了份已失效的凭证」。
     * 后者会让用户一进主页就被踢下线，是这套功能里体验最差的失败方式。
     *
     * @param writeCredentials 是否在本方法里就把凭证写进 Prefs（仅供「已确认可写」的场景）
     */
    fun restore(payload: BackupPayload, writeCredentials: Boolean = false): RestoreResult {
        val favoritesRestored = payload.favorites
            ?.takeIf { it.isNotEmpty() }
            ?.let(::mergeFavorites)
            ?: 0

        val cred = payload.credentials
        if (cred != null && !cred.isComplete) {
            AppLogger.w("备份包里的登录凭证不完整（缺 loginCode/userId/accountId/projectId），已忽略")
        }
        val credentialsRestored = writeCredentials && saveCredentials(cred)
        if (!credentialsRestored && cred?.isComplete == true) {
            AppLogger.i("备份包含登录凭证，交由 UI 层先鉴权，通过后再调 saveCredentials")
        }

        return RestoreResult(favoritesRestored, credentialsRestored, cred)
    }

    /**
     * 把**已通过鉴权**的凭证写进 Prefs。UI 在校验成功后调用这一个方法就够了。
     *
     * 为什么不让 UI 直接调 `PrefsHelper.saveAuth`：那里最后一个参数是 `name: String?`，
     * 内部写的是 `name ?: ""` —— 传 null 会把用户已有的显示名**清成空串**，
     * 「我的」页会短暂显示一个空名字。备份包里没有 userName 字段（它不是认证参数），
     * 所以这里必须显式沿用当前值。把这条坑收在一个地方，比让两个 UI 各自记得更靠谱。
     *
     * @return 凭证是否有效并被写入（不完整的一律拒收，理由见 [BackupCredentials.isComplete]）
     */
    fun saveCredentials(cred: BackupCredentials?): Boolean {
        if (cred == null || !cred.isComplete) return false
        PrefsHelper.saveAuth(
            lc = cred.loginCode,
            uid = cred.userId,
            aid = cred.accountId,
            pid = cred.projectId,
            phone = cred.telephone,
            name = PrefsHelper.userName
        )
        AppLogger.i("已写入备份包里的登录凭证（板块 ${cred.projectId}）")
        return true
    }

    /**
     * 按 mac 合并收藏：已有的保留（内容就地更新），新的追加，总数受上限约束。
     *
     * 直接复用 [FavoriteStore.add] 逐个加入——**不要**在这里另写一遍去重/截断逻辑：
     * 两处实现迟早分叉（比如一处忽略大小写另一处不忽略），而收藏判重用大小写敏感的
     * 版本会在用户眼里变成「同一台设备出现两次」。
     */
    private fun mergeFavorites(incoming: List<FavoriteDevice>): Int {
        var list = PrefsHelper.favorites()
        val before = list.size
        for (item in incoming) {
            // mac 为空的条目 add 会原样返回（无法渲染也没法下发指令），正好过滤掉坏数据
            list = FavoriteStore.add(list, item)
        }
        PrefsHelper.saveFavorites(list)
        val added = (list.size - before).coerceAtLeast(0)
        AppLogger.i("恢复收藏：备份包 ${incoming.size} 条，新增 $added 条，当前共 ${list.size} 条")
        return added
    }

    /**
     * 组装当前登录凭证。**没登录 / 信息不全时返回 null**。
     *
     * 为什么不是一个「半份」凭证：备份一个登不上的凭证没有任何意义，
     * 用户下次恢复时还会以为「备份过了就能直接登录」，结果卡在登录页，
     * 比包里没凭证更让人困惑。宁可什么都不放，恢复时提示「这个包没有凭证，请登录」。
     */
    private fun currentCredentials(): BackupCredentials? {
        val projectId = PrefsHelper.projectId
        val cred = BackupCredentials(
            loginCode = PrefsHelper.loginCode,
            userId = PrefsHelper.userId,
            accountId = PrefsHelper.accountId,
            projectId = projectId,
            telephone = PrefsHelper.telephone,
            // 板块名只用于恢复后展示「恢复的是哪个板块」。取不到就是 null，
            // 上层按当前板块兜底——不要为了填它去发网络请求。
            projectName = PrefsHelper.projectNames()[projectId.toLongOrNull()]
        )
        if (!cred.isComplete) {
            // ⚠️ 日志里**不能**出现 loginCode / userId / 手机号：这是会进日志文件的内容
            AppLogger.w("当前未登录或登录信息不完整，本次不备份登录凭证")
            return null
        }
        return cred
    }
}
