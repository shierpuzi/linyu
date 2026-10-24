package com.hualala.linyu.ui

/**
 * loginCode 登录的输入校验（纯函数，不碰网络也不碰 Prefs，所以能直接单测）。
 *
 * ## 为什么是 loginCode + userId 两个输入
 *
 * 服务端把 `loginCode` 和 `userId` 当作**一对**来识别会话：只传 `loginCode`
 * 时所有接口都返回 `errorCode=8 未获取到用户信息`（实测 6 个接口全如此），
 * 而 `loginCode` 是 32 位随机 hex，反推不出 `userId`。
 *
 * 拿到这两个之后其余认证参数全由程序自己解析，用户不用填：
 * `/user/project/list` → `accountId` + `projectId`，`/account/info` → `telephone`。
 */
internal object LoginCodePolicy {

    /**
     * loginCode 是 32 位十六进制（抓包里看到的是小写，但大写也照收，
     * 反正 [normalize] 会统一成小写）。
     */
    private val LOGIN_CODE = Regex("^[0-9a-fA-F]{32}$")

    /** userId 是纯数字，实测是 8 位左右的整数（如 10000001） */
    private val USER_ID = Regex("^\\d{1,20}$")

    fun isValidLoginCode(raw: String): Boolean = LOGIN_CODE.matches(raw.trim())

    fun isValidUserId(raw: String): Boolean = USER_ID.matches(raw.trim())

    /** 存进 Prefs / 发请求之前统一小写去空白，避免同一份码大小写不同被当成两个 */
    fun normalize(raw: String): String = raw.trim().lowercase()
}
