package com.quzhi.lite.ui

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

    /** 教程教用户从抓包 URL 里复制，所以整条 URL 粘进来必须能抠出来 */
    @Test
    fun `extract loginCode and userId from a full request url`() {
        val url = "https://v3-api.china-qzxy.cn/account/wallet" +
            "?accountId=10000101&telPhone=13800138000&phoneSystem=android" +
            "&loginCode=$code&telephone=13800138000&projectId=3356" +
            "&userId=10000001&version=6.5.29"

        assertEquals(code, LoginCodePolicy.extractLoginCode(url))
        assertEquals("10000001", LoginCodePolicy.extractUserId(url))
    }

    @Test
    fun `extract falls back to bare values`() {
        assertEquals(code, LoginCodePolicy.extractLoginCode(code))
        assertEquals("10000001", LoginCodePolicy.extractUserId("10000001"))
        assertEquals("", LoginCodePolicy.extractLoginCode("not-a-code"))
        assertEquals("", LoginCodePolicy.extractUserId("not-a-user-id"))
    }
}
