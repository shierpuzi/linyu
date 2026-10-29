package com.quzhi.lite.ui

import com.quzhi.lite.data.BackupCodec.DecodeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包解码失败 → 用户提示文案。
 *
 * ## 为什么这份映射值得单测
 *
 * 用户明确提过一项要求：不同的失败要**分别**说清楚。这几种原因对应的下一步
 * 完全不同——「密码错」重输一次就行，「文件坏了」得重新拷一份，「不是备份包」
 * 得让他确认选对了文件。一旦有人把两个分支合并（或顺手改成同一个兜底文案），
 * 用户就只能反复试同一个密码，而代码看起来完全正常。
 *
 * 登录页那个入口也用同一个函数（[backupDecodeErrorMessage]），所以这里锁住的
 * 文案是两处共用的——测试挂了说明同一个坏文件在两个页面会说不一样的话。
 */
class BackupDecodeMessageTest {

    @Test
    fun `每种错误都有自己的文案，不会退化成同一个兜底`() {
        val all = listOf(
            DecodeError.NotABackup,
            DecodeError.PasswordRequired,
            DecodeError.WrongPassword,
            DecodeError.Corrupt,
            DecodeError.Empty,
            DecodeError.Failed("boom")
        )
        val messages = all.map(::backupDecodeErrorMessage)
        assertEquals("每种 DecodeError 都必须有独立文案", all.size, messages.distinct().size)
        assertTrue("文案不能为空", messages.all { it.isNotBlank() })
    }

    @Test
    fun `各原因的关键措辞`() {
        // 不是备份包：必须点明"选错文件了"，不能让人以为是自己密码记错
        assertTrue(
            backupDecodeErrorMessage(DecodeError.NotABackup).contains("这不是趣智Lite的备份包")
        )
        assertEquals("密码错误，请重试", backupDecodeErrorMessage(DecodeError.WrongPassword))
        assertTrue(
            backupDecodeErrorMessage(DecodeError.Corrupt).contains("备份包已损坏或不完整")
        )
        assertEquals(
            "备份包里没有可恢复的内容",
            backupDecodeErrorMessage(DecodeError.Empty)
        )
        assertTrue(
            backupDecodeErrorMessage(DecodeError.PasswordRequired).contains("密码")
        )
    }

    @Test
    fun `未预料到的异常会把原因带出来，便于排查`() {
        val message = backupDecodeErrorMessage(DecodeError.Failed("NoSuchAlgorithm"))
        assertTrue("要带上底层原因", message.contains("NoSuchAlgorithm"))
        // 不能把它说成密码问题
        assertNotEquals("密码错误，请重试", message)
    }
}
