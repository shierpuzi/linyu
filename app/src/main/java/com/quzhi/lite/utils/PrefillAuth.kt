package com.quzhi.lite.utils

import com.quzhi.lite.BuildConfig
import com.quzhi.lite.api.NetworkModule

/**
 * 预置登录态（**只在本地调试包里生效**）。
 *
 * ## 为什么需要它
 *
 * 趣智校园的 `loginCode` 是**单点**的：同一个账号在另一处登录，前一个立即失效。
 * 而我们要同时验证三样东西：
 *
 * 1. 官方 App（你平时要用）
 * 2. 原版 linyu（`com.quzhi.lite`）
 * 3. 改过的 linyu（`com.quzhi.lite.dev`）
 *
 * 三者共用同一个 loginCode，谁登录谁挤掉别人，手机上根本没法并行验证。
 * 所以把一份 loginCode 直接打进 `dev` 包里，让它启动即可用、不需要再登录。
 *
 * ## ⚠️ 安全边界
 *
 * - 凭证来自 `local.properties`（**在 .gitignore 里，不入库**），经 `buildConfigField` 注入
 * - 没配这些属性的构建（包括所有公开构建、CI），这些字段是**空串**，
 *   [seed] 会直接 return，行为和原版一模一样
 * - `dev` flavor 的包名带 `.dev` 后缀，和平板/原版互不覆盖
 *
 * ## 它做什么 / 不做什么
 *
 * - 只写 `PrefsHelper` 的认证字段（等价于「登录成功后 saveAuth」），**不伪造任何业务数据**
 * - 不覆盖已有的登录态：本地已经登录过就什么都不做（避免把你手输的账号覆盖掉）
 * - 不校验 loginCode 是否还有效——失效了 App 会照常提示「登录失效，请重新登录」，
 *   和正常登录过期完全一样
 */
object PrefillAuth {

    /** 这套预置凭证是否可用（五个字段缺一不可） */
    val available: Boolean
        get() = BuildConfig.PREFILL_LOGIN_CODE.isNotBlank() &&
            BuildConfig.PREFILL_USER_ID.isNotBlank() &&
            BuildConfig.PREFILL_ACCOUNT_ID.isNotBlank() &&
            BuildConfig.PREFILL_PROJECT_ID.isNotBlank() &&
            BuildConfig.PREFILL_TELEPHONE.isNotBlank()

    /**
     * 在没有本地登录态时，把预置凭证写进去。
     *
     * 调用点：`MainActivity.onCreate` 里 `PrefsHelper.init()` 之后、读 `isLoggedIn` 之前。
     *
     * @return true 表示这次真的写入了（首次启动），false 表示不需要或不可用
     */
    fun seed(): Boolean {
        if (!available) return false
        // 已经登录过就不动 —— 用户在设置里换过号、或手动登录过，都要尊重
        if (PrefsHelper.isLoggedIn) return false

        PrefsHelper.saveAuth(
            lc = BuildConfig.PREFILL_LOGIN_CODE,
            uid = BuildConfig.PREFILL_USER_ID,
            aid = BuildConfig.PREFILL_ACCOUNT_ID,
            pid = BuildConfig.PREFILL_PROJECT_ID,
            phone = BuildConfig.PREFILL_TELEPHONE,
            name = null
        )
        // 立刻同步给网络层，否则首个请求不带 loginCode
        NetworkModule.restoreFromPrefs()
        AppLogger.i("已装载预置登录态（调试包），账号 ${maskPhone(BuildConfig.PREFILL_TELEPHONE)}")
        return true
    }

    /** 日志里别打完整手机号 */
    private fun maskPhone(p: String): String =
        if (p.length >= 7) "${p.take(3)}****${p.takeLast(4)}" else "***"
}
