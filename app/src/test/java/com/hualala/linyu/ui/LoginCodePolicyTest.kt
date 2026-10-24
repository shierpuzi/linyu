package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCodePolicyTest {

    private val code = "00000000000000000000000000000001"

    @Test
    fun `32 hex chars is a valid login code`() {
        assertTrue(LoginCodePolicy.isValidLoginCode(code))
        // 大写也照收，normalize 会揉平
        assertTrue(LoginCodePolicy.isValidLoginCode(code.uppercase()))
        assertTrue(LoginCodePolicy.isValidLoginCode("  $code  "))
    }

    @Test
    fun `wrong length or non-hex is rejected`() {
        assertFalse(LoginCodePolicy.isValidLoginCode(""))
        assertFalse(LoginCodePolicy.isValidLoginCode(code.dropLast(1)))       // 31 位
        assertFalse(LoginCodePolicy.isValidLoginCode(code + "0"))             // 33 位
        assertFalse(LoginCodePolicy.isValidLoginCode("z" + code.drop(1)))      // 非 hex
    }

    /** 整条抓包 URL 不是合法输入——登录框只收值本身 */
    @Test
    fun `a full request url is rejected as login code`() {
        val url = "https://v3-api.china-qzxy.cn/account/wallet?loginCode=$code&userId=10000001"

        assertFalse(LoginCodePolicy.isValidLoginCode(url))
        assertFalse(LoginCodePolicy.isValidUserId(url))
    }

    @Test
    fun `normalize lowercases and trims`() {
        assertEquals(code, LoginCodePolicy.normalize("  ${code.uppercase()} "))
    }

    @Test
    fun `userId is digits only`() {
        assertTrue(LoginCodePolicy.isValidUserId("10000001"))
        assertTrue(LoginCodePolicy.isValidUserId(" 10000001 "))
        assertFalse(LoginCodePolicy.isValidUserId(""))
        assertFalse(LoginCodePolicy.isValidUserId("abc"))
        assertFalse(LoginCodePolicy.isValidUserId("10000001x"))
    }
}
