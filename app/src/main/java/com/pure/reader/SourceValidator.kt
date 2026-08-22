package com.pure.reader

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 检索管道（纯 HTTP，零浏览器）：
 * 搜索（360 多页 + 必应）→ 提取候选 → 分类校验（目录页/正文页/无效）
 * → 深验（25%/50%/75% + 随机章免费探测）→ 打分置顶 → 附带章节链接池。
 */
object SourceValidator {

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val HOST_BLACKLIST = Regex("(download|txt|bbs|forum|login|passport|pay|vip|pan|\\.rar|\\.zip|magnet|torrent|zhidao|jianshu|csdn|blog)")
    private val CHAPTER_RE = Regex("第[0-9零一二三四五六七八九十百千万]{1,8}[章节回卷集节话篇]")
    private val LOCK_RE = Regex("(本章需(付费|订阅)|付费章节|订阅本章|购买本章|需开通VIP|VIP章节|章节未解锁|解锁章节|阅读券|起点币|试读.{0,8}(结束|到此|完毕)|免费试读.{0,10}(内容|完毕)|本次阅读.{0,8}元)")
    private val ENGINE_HOST_RE = Regex("(^|\\.)(bing|baidu|sogou|so\\.com|sm|google|360|quark)\\.[a-z]+$", RegexOption.IGNORE_CASE)

    data class Chapter(val title: String, val url: String)

    data class Source(
        val url: String,
        val title: String,
        val host: String,
        val type: String,          // dir / chapter / none
        val chapterCount: Int,
        val chapters: List<Chapter>,
        val freeQ: Int,            // 0..100 估算免费覆盖
        val note: String,
        val usable: Boolean,
        val score: Double
    )

    // ---------- 搜索：360 多页 + 必应（广度可配置） ----------
    fun searchSources(query: String, pages: Int = 3, limit: Int = 30, use360: Boolean = true, useBing: Boolean = true): List<Source> {
        val cands = LinkedHashMap<String, Pair<String, String>>()
        val enc = java.net.URLEncoder.encode(query, "UTF-8")

        if (use360) {
            for (page in 1..pages) {
                val url = "https://www.so.com/s?q=$enc" + if (page > 1) "&pn=$page" else ""
                val html = fetchHtml(url) ?: continue
                extract360(html).forEach { if (!cands.containsKey(it.first)) cands[it.first] = it }
            }
        }
        if (useBing) {
            val bing = fetchHtml("https://www.bing.com/search?q=$enc&count=10")
            if (bing != null) extractBing(bing).forEach { if (!cands.containsKey(it.first)) cands[it.first] = it }
        }

        return validateAll(cands.values.toList().take(limit))
    }

    private fun extractBing(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val re = Regex("<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val url = m.groupValues[1]
            val title = Regex("<[^>]+>").replace(m.groupValues[2], "").replace(Regex("\\s+"), " ").trim()
            if (!url.startsWith("http") || title.length < 4) continue
            if (ENGINE_HOST_RE.containsMatchIn(urlHost(url))) continue
            out.add(url to title)
        }
        return out
    }

    private fun extract360(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val re = Regex("<a\\b[^>]*data-mdurl=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val url = m.groupValues[1]
            val title = Regex("<[^>]+>").replace(m.groupValues[2], "").replace(Regex("\\s+"), " ").trim()
            if (!url.startsWith("http") || title.length < 4) continue
            if (ENGINE_HOST_RE.containsMatchIn(urlHost(url))) continue
            out.add(url to title)
        }
        return out
    }

    private fun urlHost(u: String) = runCatching { URL(u).host }.getOrDefault("")

    // ---------- 校验（并发池 = 滑动窗口） ----------
    fun validateAll(cands: List<Pair<String, String>>): List<Source> {
        val n = cands.size.coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(n)
        val futures = cands.map { pool.submit(Callable { validate(it.first, it.second) }) }
        val results = mutableListOf<Source>()
        futures.forEach { f -> try { f.get()?.let { results.add(it) } } catch (e: Exception) { } }
        pool.shutdown()

        val best = HashMap<String, Source>()
        for (r in results) {
            val key = r.host.removePrefix("www.")
            val cur = best[key]
            if (cur == null || r.score > cur.score) best[key] = r
        }
        return best.values.sortedWith(compareByDescending<Source> { it.usable }.thenByDescending { it.score })
    }

    private fun validate(url: String, fallbackTitle: String): Source? {
        val html = fetchHtml(url) ?: return null
        val host = urlHost(url)
        val title = htmlTitle(html).ifBlank { fallbackTitle }
        val cls = classify(html, host)
        val type = if (cls.isDir) "dir" else if (cls.isChapter) "chapter" else "none"

        var chapters: List<Chapter> = emptyList()
        var freeQ = 0
        var probeOk = 0
        var probeTotal = 0

        if (cls.isDir) {
            chapters = extractToc(html, url)
            val probes = pickProbes(chapters)          // List<Pair<Chapter, Int(percent)>>
            probeTotal = probes.size
            for ((ch, pct) in probes) {
                if (isReadable(ch.url)) { probeOk++; if (pct > freeQ) freeQ = pct }
            }
            if (probes.isNotEmpty() && probeOk == probes.size) freeQ = 100
        } else if (cls.isChapter) {
            probeTotal = 1
            if (isReadable(url)) { probeOk = 1; freeQ = 100 }
        }

        val fullFree = freeQ >= 100
        val note = if (type == "none") "不可读页面"
        else if (fullFree) "全文免费"
        else if (freeQ >= 75) "约75%免费，后段或收费"
        else if (freeQ >= 50) "约50%免费，后段收费"
        else if (freeQ >= 25) "仅约25%免费，后段收费"
        else "疑似全收费/需登录"

        val score = cls.score + freeQ * 0.8 + (if (fullFree) 2 else 0) + probeOk * 0.3
        // 非免费源也保留：只要目录/正文结构成立即视为可用（排序时免费在前）
        val usable = (cls.isDir || cls.isChapter) && probeTotal >= 1

        return Source(url, title, host, type, maxOf(cls.chapterCount, chapters.size), chapters, freeQ, note, usable, score)
    }

    // 25%/50%/75% + 随机（返回 章节 + 位置百分比）
    private fun pickProbes(toc: List<Chapter>): List<Pair<Chapter, Int>> {
        if (toc.isEmpty()) return emptyList()
        val n = toc.size
        val out = LinkedHashMap<Chapter, Int>()
        for (q in listOf(25, 50, 75)) {
            val i = (n * q / 100).coerceIn(0, n - 1)
            out[toc[i]] = q
        }
        val rnd = (n * 10 / 100 + (0 until n * 80 / 100).random()).coerceIn(0, n - 1)
        out[toc[rnd]] = 50
        return out.entries.map { it.key to it.value }
    }

    private fun isReadable(url: String): Boolean {
        val html = fetchHtml(url) ?: return false
        val cls = classify(html, urlHost(url))
        val head = stripToText(html).take(2500)
        return cls.bodyLen > 150 && !LOCK_RE.containsMatchIn(head)
    }

    // ---------- 解析 ----------
    private class Cls(val title: String, val chapterCount: Int, val bodyLen: Int, val isDir: Boolean, val isChapter: Boolean, val score: Double)

    private fun classify(html: String, host: String): Cls {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val text = stripToText(html)
        val chapterCount = maxOf(
            CHAPTER_RE.findAll(text).count(),
            Regex("<a[^>]*>[^<]*第[0-9零一二三四五六七八九十百千万]{1,8}[章节回卷集]").findAll(html).count()
        )
        val bodyLen = text.length
        val hostBlack = HOST_BLACKLIST.containsMatchIn(host)

        val isDir = chapterCount >= 6
        val isChapter = bodyLen > 400 && (chapterCount > 0 || bodyLen > 1200)

        var score = 0.0
        if (isDir) score += 6
        if (isChapter) score += 4
        score += minOf(chapterCount, 60) * 0.4
        if (bodyLen > 800) score += 2
        if (hostBlack) score -= 99
        return Cls(title, chapterCount, bodyLen, isDir, isChapter, score)
    }

    fun extractToc(html: String, base: String): List<Chapter> {
        val out = mutableListOf<Chapter>()
        val seen = HashSet<String>()
        val re = Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        val titleRe = Regex("第[0-9零一二三四五六七八九十百千万]{1,8}[章节回卷集]|^(序章|楔子|引子|番外|后记|尾声|正文)")
        for (m in re.findAll(html)) {
            var url = m.groupValues[1]
            val title = Regex("<[^>]+>").replace(m.groupValues[2], "").replace(Regex("\\s+"), "").trim()
            if (title.isEmpty() || title.length > 40) continue
            if (!titleRe.containsMatchIn(title)) continue
            if (!url.startsWith("http")) { try { url = URL(URL(base), url).toString() } catch (e: Exception) { continue } }
            if (seen.add(url)) out.add(Chapter(title, url))
            if (out.size >= 2000) break
        }
        return out
    }

    fun htmlTitle(html: String): String =
        Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    fun stripToText(html: String): String =
        html.replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim()

    // ---------- 礼貌限速（防反爬）：每域名并发≤2、请求间隔≥350ms ----------
    private class HostGate(var lastMs: Long = 0, var inflight: Int = 0)
    private val gates = HashMap<String, HostGate>()
    private val gateLock = java.lang.Object()
    private const val MIN_DELAY_MS = 350L
    private const val MAX_HOST_CONCURRENCY = 2

    private fun ensurePermit(host: String) {
        synchronized(gateLock) {
            val g = gates.getOrPut(host) { HostGate() }
            while (g.inflight >= MAX_HOST_CONCURRENCY || System.currentTimeMillis() - g.lastMs < MIN_DELAY_MS) {
                gateLock.wait(80)
            }
            g.inflight++
            g.lastMs = System.currentTimeMillis()
        }
    }

    private fun releasePermit(host: String) {
        synchronized(gateLock) {
            gates[host]?.let { g -> g.inflight--; gateLock.notifyAll() }
        }
    }

    // ---------- HTTP ----------
    fun fetchHtml(url: String): String? {
        val host = urlHost(url)
        ensurePermit(host)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 7000
            conn.readTimeout = 9000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            val code = conn.responseCode
            if (code !in 200..399) return null
            val stream = conn.inputStream ?: return null
            val bytes = stream.readBytesLimited(2 * 1024 * 1024)
            val cs = detectCharset(conn.contentType, bytes)
            if (bytes.isEmpty()) return null
            String(bytes, Charset.forName(cs))
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
            releasePermit(host)
        }
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var read = 0
        while (read < max) {
            val n = read(tmp)
            if (n < 0) break
            buf.write(tmp, 0, n)
            read += n
        }
        return buf.toByteArray()
    }

    private fun detectCharset(contentType: String?, bytes: ByteArray): String {
        val fromHeader = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType ?: "")?.groupValues?.get(1)
        if (fromHeader != null) return normCharset(fromHeader)
        val head = String(bytes, 0, minOf(4096, bytes.size), Charset.forName("ISO-8859-1"))
        val fromMeta = Regex("<meta[^>]+charset[\"']?\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        if (fromMeta != null) return normCharset(fromMeta)
        return "UTF-8"
    }

    private fun normCharset(cs: String): String = when (cs.lowercase()) {
        "gb2312", "gb-2312", "gbk" -> "GBK"
        "gb18030" -> "GB18030"
        "big5", "big-5" -> "Big5"
        "utf8" -> "UTF-8"
        else -> cs.uppercase()
    }
}
