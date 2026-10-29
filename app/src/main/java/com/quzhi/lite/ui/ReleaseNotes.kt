package com.quzhi.lite.ui

/**
 * 把 Release 说明（Markdown）变成「App 里能看的一段文字」。
 *
 * ## 为什么要把这段逻辑从 `UserScreen.kt` 里抽出来
 *
 * 它原来是一个 private 函数，只能靠肉眼看界面验证对不对。而它出过一次**很难发现的
 * 故障**：真实发布的 Release 说明在界面上全部显示「本版本未填写更新详情」，
 * 但 GitHub 上那几份说明其实是完整的（1600~2200 字符）。
 *
 * 原因是提取规则只保留标题里含「新增 / 功能 / 修复 / BUG」的段落，
 * 而实际写的标题是「本次更新内容」「更新通道隔离」这类 —— 一个都没命中，
 * 于是整段被丢光。**数据是好的，是过滤器把内容吃掉了。**
 *
 * 这种「静默丢内容」的错误编译不报、测试也不红，只有真机看到空白才暴露。
 * 所以现在：抽成纯函数 + 回退策略 + 单测锁住（见 `ReleaseNotesTest`）。
 *
 * ## 提取策略（两层）
 *
 * 1. **优先摘录**：只取标题命中「新增 / 功能 / 修复 / BUG」的段落，让更新日志短而聚焦。
 *    这是原本的设计意图，对写得很规范的说明依然有效。
 * 2. **摘不到就回退全文**：一个命中段落都没有时，不再输出空串，而是输出整个说明。
 *
 * 第 2 层是这次修的**根因**。原实现的 `ifBlank` 兜底只把 `""` 换成
 * 「未填写更新详情」——把「规则没匹配上」误报成「作者没写」。宁可在界面上多显示
 * 几段「安装说明」，也不要让用户以为没有更新日志。
 *
 * ## 为什么要有行数上限
 *
 * Release 说明里常带安装步骤、已知问题、开发者信息这些长内容。全量展示时
 * 卡片会被撑得很长，而用户此刻只是想看「这版改了什么」。所以回退路径会截断，
 * 并在末尾明确告诉用户「去 Releases 页面看全文」——**截断必须可见**，
 * 悄悄截掉一半比不显示更糟。
 */
object ReleaseNotes {

    /**
     * 命中这些词的标题，其所在段落会被**优先**摘出来。
     *
     * ⚠️ 加词可以让摘录更准，但**不要为了「更严格」而删词**：
     * 每删一个词，就多一批说明落到回退路径上。
     */
    private val HIGHLIGHT_KEYWORDS = listOf("新增", "功能", "修复", "BUG")

    /** 回退展示全文时最多留多少行（约等于一次能看完的量） */
    const val FALLBACK_MAX_LINES = 18

    /** 什么都没写时的占位（真的是空 body 才会用到） */
    const val EMPTY_PLACEHOLDER = "本版本未填写更新详情。"

    /**
     * @param body GitHub Release 的 `body`（Markdown 原文）
     * @return 可直接给 `Text` 渲染的纯文本；body 为空时返回 [EMPTY_PLACEHOLDER]
     */
    fun render(body: String): String {
        if (body.isBlank()) return EMPTY_PLACEHOLDER

        val highlights = extractHighlights(body)
        // 摘录到了就用摘录（短、聚焦）；摘不到就回退全文（绝不返回空白）
        if (highlights.isNotBlank()) return highlights

        return fallbackFullText(body)
    }

    /**
     * 只抽「新增 / 功能 / 修复 / BUG」段落。
     *
     * 按 Markdown 标题切段：遇到标题就切换 `keeping` 状态，非标题行在 `keeping`
     * 为 true 时才收。这样保留的是**整段**而不是单行，段落里的要点列不会丢。
     */
    private fun extractHighlights(body: String): String {
        val out = StringBuilder()
        var keeping = false
        body.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("#")) {
                val title = plainHeading(line)
                keeping = HIGHLIGHT_KEYWORDS.any { title.contains(it, ignoreCase = true) }
                if (keeping) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(stripMarkdown(title)).append('\n')
                }
            } else if (keeping) {
                // 与回退路径共用同一个行清洗：摘录段落里同样会有表格和引用，
                // 只有两边都清洗，界面上才不会出现「摘录路径干净、回退路径满是竖线」
                // 这种同一份数据两种观感的情况。
                val rendered = renderLine(line) ?: return@forEach
                out.append(rendered).append('\n')
            }
        }
        // ⚠️ 只 trimEnd：引用行的「行首缩进」是层级标记，trim() 会把它吃掉
        return out.toString().trimEnd()
    }

    /**
     * 回退：整篇说明都转成纯文本，超过 [FALLBACK_MAX_LINES] 行则截断并注明。
     *
     * ⚠️ 两步都不能省：
     * 1. **必须去掉 `#`**：回退路径走的是原文，不做这一步界面上会直接露出
     *    `### 安装说明` —— 单测第一次跑就抓到了这个（`回退全文要清掉 Markdown 标记`）。
     *    `plainHeading()` 不能直接用：它会过滤 emoji 和非中日韩文字，而正文里的
     *    emoji 应该保留，所以只做「去 #」这一件事。
     * 2. **截断提示不能省**：用户看到一半文字在没有说明的情况下断掉，会以为 App 坏了；
     *    明确写「更完整内容请看 Releases 页面」既解释了原因，也给了下一步动作。
     */
    private fun fallbackFullText(body: String): String {
        val lines = body.lineSequence()
            .map { it.trimEnd() }
            // 分隔线 `---` 转成纯文本就是一串短横线，没意义还可能被误读成列表
            .filterNot { it.trim() == "---" || it.trim() == "***" }
            // 表格 / 引用 / 链接 / 标题 / 强调统一在这一步清洗（与摘录路径共用同一函数）
            .mapNotNull { renderLine(it) }
            .toList()

        // 去掉开头连续空行（很多 body 以空行起头）
        val trimmed = lines.dropWhile { it.isBlank() }
        // 去掉结尾空行：否则「刚好 18 行 + 末尾空行」会被误判成超限而平白触发截断
        val cleaned = trimmed.dropLastWhile { it.isBlank() }

        // ⚠️ 这里只能 `trimEnd()`，**不能用 `trim()`**：
        //    引用行是靠「行首两空格缩进」表达层级的，`trim()` 会把首行的缩进吃掉，
        //    于是引用和正文长得一模一样（单测 `引用行去掉尖括号并保留缩进` 抓到的就是这个）。
        if (cleaned.size <= FALLBACK_MAX_LINES) return cleaned.joinToString("\n").trimEnd()

        val head = cleaned.take(FALLBACK_MAX_LINES).joinToString("\n").trimEnd()
        return "$head\n\n…（完整更新说明请到 Releases 页面查看）"
    }

    /**
     * 把**一行** Markdown 变成可读纯文本。返回 null 表示这行整行丢弃。
     *
     * ## 为什么只做这几种、不做完整 Markdown 渲染
     *
     * 这是一条**兜底路径**：正常的 Release 说明会被 [extractHighlights] 摘走，
     * 根本走不到这里。只有标题没写规范的老版本才落进来。
     *
     * 为一个低频兜底场景引入 Markdown 渲染库（几百 KB）不值，自己写完整解析器更不值。
     * 但**什么都不做又很难看**：实测四份真实说明里，表格行 8~17 行、引用 3~6 行，
     * 已经覆盖了大半可见内容，界面上满屏 `| 项 | 值 |` 和 `> ⚠️`。
     *
     * 所以只处理「噪音占比最大、且转成纯文本后语义不丢」的三种：
     *
     * | 语法 | 原文 | 转成 |
     * |---|---|---|
     * | 表格 | `\| 包名 \| com.quzhi.lite.dev \|` | `包名 · com.quzhi.lite.dev` |
     * | 表格分隔行 | `\|:--\|:--\|` | **整行丢弃** |
     * | 引用 | `> ⚠️ 这是测试版` | `  ⚠️ 这是测试版`（两空格缩进） |
     * | 链接 | `[文字](https://…)` | `文字` |
     *
     * 强调标记（`**` / `__` / 反引号）与标题 `#` 也在这里处理，见 [stripMarkdown]。
     *
     * ⚠️ **不处理**：有序/无序列表符号（`-` / `1.`）。它们转成纯文本后本来就通顺，
     * 去掉反而丢了「这是一组要点」的结构感。缩进层级也不做——纯文本看不出层级，
     * 硬加空格只会让窄屏更挤。
     */
    private fun renderLine(raw: String): String? {
        val line = raw.trimEnd()
        val trimmed = line.trim()

        // ── 表格分隔行 `|---|:--:|` 整行丢弃 ──
        // 它只描述对齐方式，没有任何内容；留着就是一串看不懂的短横线
        if (TABLE_SEPARATOR.matches(trimmed)) return null

        // ── 表格数据行：竖线 → 间隔点 ──
        if (trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2) {
            val cells = trimmed.trim('|').split('|').map { stripMarkdown(it).trim() }
            // 全空的行（比如 `| | |`）没意义，丢弃
            if (cells.all { it.isEmpty() }) return null
            return cells.filter { it.isNotEmpty() }.joinToString(" · ")
        }

        // ── 引用行：去掉 `>`，用缩进表达「这是补充说明」──
        if (trimmed.startsWith(">")) {
            val content = stripMarkdown(trimmed.trimStart('>').trimStart()).trim()
            if (content.isEmpty()) return null
            return "  $content"
        }

        return stripMarkdown(stripHeadingMarks(line))
    }

    /**
     * 去掉 Markdown 强调标记与链接语法。
     *
     * App 里是普通 `Text` 渲染（没有 Markdown 解析器），不处理的话：
     * - `**加粗**` 会把星号原样显示
     * - `` `反引号` `` 同上
     * - `[文字](https://很长的url)` 会把整串 URL 露出来，在窄屏上占两三行
     *
     * 链接**只保留文字、丢掉 URL**：URL 对看更新日志的人没有价值，
     * 而提示语已经指了「去 Releases 页面查看」——那是能点的地方。
     */
    private fun stripMarkdown(s: String): String =
        s.replace("**", "")
            .replace("__", "")
            .replace("`", "")
            // [文字](url) / [文字](url "title") → 文字
            .replace(LINK_REGEX, "$1")

    /** `[文字](目标)` 里的「文字」 */
    private val LINK_REGEX = Regex("""\[([^\]]*)\]\([^)]*\)""")

    /** `|---|:--:|` 这类表格分隔行 */
    private val TABLE_SEPARATOR = Regex("""^\|?[\s:|-]+\|?$""")

    /**
     * 去掉行首的 `#` 系列标题符。
     *
     * 只处理**行首**：行首的 # 才是真标题，正文中间出现的 `#` 极罕见，
     * 无差别删掉会把正常文字也弄坏。
     */
    private fun stripHeadingMarks(s: String): String =
        s.trimStart().trimStart('#').trimStart()

    /**
     * 去掉标题里的 `#`、emoji 和多余空白，只保留中日韩文字与 ASCII。
     *
     * ⚠️ 注意这与**回退全文**不同：回退路径不经过这里，所以那边正文里的
     * emoji 会保留。摘录路径过滤 emoji 是因为标题里的 emoji 会显得杂乱。
     */
    private fun plainHeading(line: String): String =
        line.trimStart().trimStart('#').trim()
            .filter { c ->
                c.code in 0x4E00..0x9FFF ||      // 中日韩统一表意文字
                c.code in 0x3000..0x303F ||      // 中日韩标点（「」等）
                c.code in 0x20..0x7E             // ASCII 可见字符
            }
            .trim()
}
