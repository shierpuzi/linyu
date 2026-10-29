package com.quzhi.lite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新日志在界面上「显示为空白」的回归测试。
 *
 * ## 为什么这个测试必须存在
 *
 * 2026-10-29 线上故障：已发布的 `v3.1.0-dev` / `v3.2.0-dev` 在 App 里展开后
 * 全部显示「本版本未填写更新详情。」，而 GitHub 上那两份说明是完整的
 * （1670 / 2101 字符）。用户以为是没写日志，实际是**过滤器把内容吃光了**。
 *
 * 这类错误的特点：编译不报、单测不红（当时根本没有这个函数）、只有真机看界面才发现。
 * 所以这里用**真实发布过的 body**（截取代表性结构）当输入，而不是随手编的字符串。
 */
class ReleaseNotesTest {

    // ════════════════════════════════════════════
    //  回退路径：这是本次修复的核心
    // ════════════════════════════════════════════

    /**
     * 实际出问题的那种说明：标题是「本次更新内容」「安装说明」，一个都不含
     * 「新增 / 功能 / 修复 / BUG」→ 摘录为空 → **必须回退全文，不能返回空**。
     */
    @Test
    fun `没有命中关键词的说明要回退成全文而不是空白`() {
        val body = """
            ## 趣智Lite v3.2.0-dev（测试版）

            ### 安装说明

            包名 `com.quzhi.lite.dev`，需要自己登录。

            ### 本次更新内容

            - 模块矢量图
            - 图形停止按钮

            ### 已知问题

            吹风机图标在 14dp 下会糊。
        """.trimIndent()

        val out = ReleaseNotes.render(body)

        // 绝对不能是「未填写」占位
        assertNotPlaceholder(out)
        // 也不能是空
        assertTrue("回退全文不应为空", out.isNotBlank())
        // 应当真的带出正文里的信息（这是内容没被丢掉的正向证据）
        assertTrue("应包含正文要点，实际=[$out]", out.contains("模块矢量图"))
        assertTrue("应包含正文要点，实际=[$out]", out.contains("图形停止按钮"))
    }

    /** 回退全文时必须去掉 `#` 和 `**`，否则界面上会露出 Markdown 标记 */
    @Test
    fun `回退全文要清掉 Markdown 标记`() {
        val body = """
            ### 安装说明

            **包名** `com.quzhi.lite.dev`，用到 `__加粗__` 和星号。
        """.trimIndent()

        val out = ReleaseNotes.render(body)

        assertFalse("不该残留 # 标题符：[$out]", out.contains("#"))
        assertFalse("不该残留 ** ：[$out]", out.contains("**"))
        assertFalse("不该残留反引号：[$out]", out.contains("`"))
    }

    /** 分隔线 `---` 转成纯文本只是一串短横线，没意义，要过滤掉 */
    @Test
    fun `回退全文要过滤水平分隔线`() {
        val body = "### 说明\n\n第一段\n\n---\n\n第二段"
        val out = ReleaseNotes.render(body)

        assertFalse("不该出现 --- ：[$out]", out.contains("---"))
        assertTrue(out.contains("第一段"))
        assertTrue(out.contains("第二段"))
    }

    /** 超长说明要截断，且**必须带可见提示**（悄悄截一半比不显示更糟） */
    @Test
    fun `超长说明要截断并明确提示去哪看全文`() {
        val body = buildString {
            appendLine("### 安装说明")
            repeat(60) { appendLine("这是第 $it 行说明内容") }
        }

        val out = ReleaseNotes.render(body)
        val lineCount = out.lines().size

        assertNotPlaceholder(out)
        assertTrue("应提示去看完整说明，实际结尾=[${out.takeLast(60)}]",
            out.contains("完整更新说明"))
        // 截断后总行数不该超过上限太多（上限 + 空行 + 提示行）
        assertTrue("截断后行数过多：$lineCount", lineCount <= ReleaseNotes.FALLBACK_MAX_LINES + 4)
        // 前几行要保留（证明是「截尾」而不是「截头」）
        assertTrue("应保留开头内容：[$out]", out.contains("第 0 行说明内容"))
    }

    /** 刚好等于上限时不截断，也不该出现提示 */
    @Test
    fun `未超上限时不应出现截断提示`() {
        val body = buildString {
            appendLine("### 说明")
            repeat(ReleaseNotes.FALLBACK_MAX_LINES - 1) { appendLine("行 $it") }
        }

        val out = ReleaseNotes.render(body)
        assertFalse("未超上限不该有截断提示：[$out]", out.contains("完整更新说明"))
    }

    // ════════════════════════════════════════════
    //  摘录路径：原设计意图仍然有效
    // ════════════════════════════════════════════

    /** 标题命中关键词时只摘那几段，安装说明/已知问题应被排除（这才是原设计想要的短日志） */
    @Test
    fun `命中关键词时只摘录相关段落`() {
        val body = """
            ### 安装说明

            这一段不该出现。

            ### 新增：备份与恢复

            - 支持收藏备份
            - 凭证强制加密

            ### 已知问题

            这一段也不该出现。
        """.trimIndent()

        val out = ReleaseNotes.render(body)

        assertTrue("应保留新增段落内容：[$out]", out.contains("支持收藏备份"))
        assertTrue("应保留新增段落内容：[$out]", out.contains("凭证强制加密"))
        assertFalse("安装说明不该进摘录：[$out]", out.contains("这一段不该出现"))
        assertFalse("已知问题不该进摘录：[$out]", out.contains("这一段也不该出现"))
    }

    /** 「修复」和「BUG」也算命中词，别只认「新增」 */
    @Test
    fun `修复与 BUG 标题也属于摘录范围`() {
        assertTrue(ReleaseNotes.render("### 修复\n\n修了一个崩溃").contains("修了一个崩溃"))
        assertTrue(ReleaseNotes.render("### BUG 修复\n\n修了一个崩溃").contains("修了一个崩溃"))
        assertTrue(ReleaseNotes.render("### 新增功能\n\n加了个按钮").contains("加了个按钮"))
    }

    // ════════════════════════════════════════════
    //  边界
    // ════════════════════════════════════════════

    /** 真的没写说明时才用占位文案 */
    @Test
    fun `空 body 才显示未填写`() {
        assertEquals(ReleaseNotes.EMPTY_PLACEHOLDER, ReleaseNotes.render(""))
        assertEquals(ReleaseNotes.EMPTY_PLACEHOLDER, ReleaseNotes.render("   \n\n  \t "))
    }

    /**
     * 回归护栏：任何**非空**的 body 都不允许返回占位文案。
     *
     * 这条是这次故障的直接断言——只要这条绿着，「内容被过滤器吃光」
     * 就不可能再表现为「未填写更新详情」。
     */
    @Test
    fun `任何非空说明都不允许显示未填写`() {
        val samples = listOf(
            "### 安装说明\n包名 x",
            "没有标题的一行纯文本",
            "### 本次更新内容\n\n- a\n- b",
            "## 趣智Lite v3.1.0-dev（测试版）\n\n### 登录方式三种\n\n1. 验证码"
        )
        samples.forEach { body ->
            val out = ReleaseNotes.render(body)
            assertTrue("body=[$body] 不应显示占位，实际=[$out]",
                out != ReleaseNotes.EMPTY_PLACEHOLDER && out.isNotBlank())
        }
    }

    private fun assertNotPlaceholder(out: String) {
        assertFalse(
            "不该显示「未填写更新详情」——数据是有的，是提取逻辑把内容丢了",
            out.contains("未填写更新详情")
        )
    }

    // ════════════════════════════════════════════
    //  Markdown 噪音清洗（表格 / 引用 / 链接）
    // ════════════════════════════════════════════
    //
    // 这几条对应真机反馈：「可以正常看了，但是貌似不能正常渲染 markdown」。
    // 实测四份真实说明里表格行 8~17 行、引用 3~6 行，覆盖了大半可见内容，
    // 不处理的话界面上满屏 `| 项 | 值 |` 和 `> ⚠️`。

    /** 表格数据行要变成「a · b」，而不是原样露出竖线 */
    @Test
    fun `表格行要转成间隔点而不是保留竖线`() {
        val body = """
            ### 安装说明

            | 项 | 值 |
            |---|---|
            | 包名 | com.quzhi.lite.dev |
            | 大小 | 21.07 MB |
        """.trimIndent()

        val out = ReleaseNotes.render(body)

        assertFalse("不该残留竖线：[$out]", out.contains("|"))
        assertTrue("应保留单元格内容：[$out]", out.contains("包名"))
        assertTrue("应保留单元格内容：[$out]", out.contains("com.quzhi.lite.dev"))
        assertTrue("单元格之间应用间隔点：[$out]", out.contains("包名 · com.quzhi.lite.dev"))
    }

    /** 表格分隔行 `|---|---|` 必须整行丢弃——它没有任何内容 */
    @Test
    fun `表格分隔行要被丢弃`() {
        val out = ReleaseNotes.render("| 项 | 值 |\n|:---|---:|\n| a | b |")

        assertFalse("不该出现分隔行残留：[$out]", out.contains("---"))
        assertFalse("不该出现对齐冒号：[$out]", out.contains(":"))
    }

    /** 引用行的 `>` 要去掉，改用缩进表达「这是补充说明」 */
    @Test
    fun `引用行去掉尖括号并保留缩进`() {
        val body = "> ⚠️ 这是测试版，使用 debug 签名。"

        val out = ReleaseNotes.render(body)

        assertFalse("不该残留 > ：[$out]", out.startsWith(">"))
        assertTrue("内容要保留：[$out]", out.contains("这是测试版"))
        assertTrue("应以缩进表达引用：[$out]", out.startsWith("  "))
    }

    /** 链接只留文字、丢掉 URL（窄屏上 URL 会占两三行且没人会去读） */
    @Test
    fun `链接只保留文字丢掉 URL`() {
        val body = "详见 [更新说明](https://github.com/shierpuzi/linyu/releases) 页面。"

        val out = ReleaseNotes.render(body)

        assertTrue("应保留链接文字：[$out]", out.contains("更新说明"))
        assertFalse("不该露出 URL：[$out]", out.contains("https://"))
        assertFalse("不该残留方括号：[$out]", out.contains("["))
        assertFalse("不该残留圆括号语法：[$out]", out.contains("]("))
    }

    /** 列表符号要保留——它是「这是一组要点」的结构感，去掉反而更难读 */
    @Test
    fun `列表符号要保留`() {
        val out = ReleaseNotes.render("- 第一条\n- 第二条")
        assertTrue("列表符号应保留：[$out]", out.contains("- 第一条"))
    }

    /**
     * 综合回归：用贴近真实的结构跑一遍，确认三类噪音同时消失。
     *
     * 这条对应真机截图里那个「满屏竖线和尖括号」的观感。
     */
    @Test
    fun `真实结构综合清洗`() {
        val body = """
            ## 趣智Lite v3.2.0-dev（测试版）

            > ⚠️ 这是**测试版**，使用 debug 签名。

            ### 安装说明

            | 项 | 值 |
            |---|---|
            | 包名 | `com.quzhi.lite.dev` |

            详见 [Releases](https://github.com/x/y) 页面。
        """.trimIndent()

        val out = ReleaseNotes.render(body)

        assertNotPlaceholder(out)
        assertFalse("残留竖线：[$out]", out.contains("|"))
        assertFalse("残留引用符：[$out]", out.contains("> "))
        assertFalse("残留加粗标记：[$out]", out.contains("**"))
        assertFalse("残留反引号：[$out]", out.contains("`"))
        assertFalse("残留 URL：[$out]", out.contains("https://"))
        assertFalse("残留标题符：[$out]", out.contains("##"))
        assertTrue("正文要点要保留：[$out]", out.contains("com.quzhi.lite.dev"))
    }
}
