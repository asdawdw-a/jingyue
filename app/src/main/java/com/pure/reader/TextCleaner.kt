package com.pure.reader

/**
 * 格式适配 + 无用文本过滤（容器优先 + 保留段落结构）
 * extractContentText：只取“正文容器”，并按块级元素还原段落（\n 分隔）
 * clean：逐段格式清洗 + 强标记段整段删除（URL/反转码/版权声明等），不动正文常见词
 */
object TextCleaner {

    private val KNOWN_CONTENT_ATTR = Regex(
        "id=[\"'](content|chaptercontent|booktext|htmlContent|read-content|nr1|text|BookText|chapter|article|book_con|content1)[\"']" +
        "|class=[\"'][^\"']*(content|con|showtxt|neirong|article|read-content|book-text|booktext|txt|zhangjie|nr1)[^\"']*[\"']",
        RegexOption.IGNORE_CASE
    )

    // 正文容器 HTML（平衡扫描 + 评分）
    private fun pickContentHtml(html: String): String {
        val tagRe = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>")
        val ms = tagRe.findAll(html).toList()
        val n = html.length
        val pre = IntArray(n + 1)
        var inTag = false
        for (i in 0 until n) {
            val ch = html[i]
            if (ch == '<') { inTag = true; pre[i + 1] = pre[i] }
            else if (ch == '>') { inTag = false; pre[i + 1] = pre[i] }
            else pre[i + 1] = pre[i] + (if (inTag) 0 else 1)
        }
        val aPos = Regex("<a\\b", RegexOption.IGNORE_CASE).findAll(html).map { it.range.first }.toList().toIntArray()
        fun linksIn(lo: Int, hi: Int): Int {
            var c = 0; var i = 0
            while (i < aPos.size && aPos[i] < lo) i++
            while (i < aPos.size && aPos[i] < hi) { c++; i++ }
            return c
        }
        val blockTags = setOf("div", "article", "section", "td", "dd", "p")
        val stack = ArrayDeque<Pair<Int, String>>()
        var best = Triple(-1, -1, -1.0)
        for (m in ms) {
            val pos = m.range.first
            val isClose = m.groupValues[1].isNotEmpty()
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (isClose) {
                val idx = stack.indexOfLast { it.second == tag }
                if (idx >= 0) {
                    val (start, t) = stack[idx]
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                    if (t in blockTags) {
                        val end = m.range.last + 1
                        val len = (pre[end] - pre[start]).toDouble()
                        val links = linksIn(start, end)
                        val score = len - links * 60 + (if (KNOWN_CONTENT_ATTR.containsMatchIn(attrs)) 400 else 0)
                        if (len > 50 && score > best.third) best = Triple(start, end, score)
                    }
                }
            } else if (tag in blockTags) {
                stack.add(Pair(pos, tag))
            }
        }
        return if (best.first < 0) html else html.substring(best.first, best.second)
    }

    /** 只取正文容器文本，并按块级元素还原段落（\n 分隔）。 */
    fun extractContentText(html: String): String {
        val inner = pickContentHtml(html)
        return htmlToParagraphs(inner)
    }

    private fun htmlToParagraphs(h2: String): String {
        var h = h2.replace(Regex("(?i)<br\\s*/?>"), "\n")
        h = h.replace(Regex("(?i)</(p|div|article|section|td|dd|li|h[1-6]|blockquote|ul|ol|dl|tr)>"), "\n")
        h = h.replace(Regex("(?s)<[^>]+>"), " ")
        val lines = h.split("\n").map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    // ---------- 格式：实体/零宽/反转码斜杠/空白 ----------
    private val ZERO = Regex("[\\u200b\\u200c\\u200d\\ufeff\\u3000]")
    private val CJK_SLASH = Regex("(?<=[\\u4e00-\\u9fff])/(?=[\\u4e00-\\u9fff])")
    private val ENTITY = Regex("&(nbsp|amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);", RegexOption.IGNORE_CASE)

    /** 逐段清洗（只做格式适配，保留 \n 段落分隔；不做任何关键词过滤）。 */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.split("\n")
        val out = mutableListOf<String>()
        var last = ""
        for (line in lines) {
            var l = line
            if (l.isBlank()) continue
            l = decodeEntities(l)
            l = ZERO.replace(l, "")
            l = CJK_SLASH.replace(l, "")
            l = l.replace(Regex("\\s+"), " ").trim()
            if (l.length < 2) continue
            if (l.length > 1500) continue
            if (l == last) continue
            out.add(l)
            last = l
        }
        return out.joinToString("\n")
    }

    // 章节标题清洗：取“_”前；过长截断
    fun cleanTitle(raw: String): String {
        var t = raw.split("_").firstOrNull()?.trim() ?: raw.trim()
        t = t.replace(Regex("[|｜\\n]"), "")
        if (t.length !in 2..30) t = raw.trim().take(24)
        return t
    }

    private fun decodeEntities(s: String): String =
        ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when (e.lowercase()) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    try {
                        val n = when {
                            e.lowercase().startsWith("#x") -> e.substring(2).toInt(16)
                            e.startsWith("#") -> e.substring(1).toInt()
                            else -> return@replace m.value
                        }
                        if (n in 32..0xFFFF) Char(n).toString() else ""
                    } catch (ex: Exception) { "" }
                }
            }
        }
}
