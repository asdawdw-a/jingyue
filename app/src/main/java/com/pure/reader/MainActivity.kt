package com.pure.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var searchInput: EditText
    private lateinit var searchPanel: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var configBox: LinearLayout
    private lateinit var btnSidebar: Button
    private lateinit var sidebar: LinearLayout
    private lateinit var sideTitle: TextView
    private lateinit var sideStatus: TextView
    private lateinit var sideList: ListView
    private lateinit var sideSearch: android.widget.EditText
    private var pmMenu: PopupMenu? = null
    // 目录主题（跟随阅读设置）
    private var themeFg = "#333333"
    private var themeSec = "#68707a"
    private var themeAcc = "#1a73e8"
    private lateinit var resultsPanel: LinearLayout
    private lateinit var sourceList: ListView
    private lateinit var resultsTitle: TextView
    private lateinit var loadBar: android.widget.ProgressBar
    private lateinit var sourceEmpty: TextView
    private lateinit var shelfPanel: android.widget.ScrollView
    private lateinit var shelfRows: LinearLayout
    private lateinit var shelfHint: TextView
    private var currentResults = listOf<SourceValidator.Source>()

    private var readabilityJs = ""
    private var readerJs = ""
    private var adblockJs = ""
    private val adHosts = mutableSetOf<String>()

    private var readingMode = false
    private var pendingAutoRead = false
    private var adblockEnabled = true
    private var rawViewOnce = false
    private var internalPage = false

    // 当前书籍与章节
    private var currentBook: SourceValidator.Source? = null
    private var currentChapterIndex = 0

    // 目录分组粒度
    private val GROUP = 50

    // 侧边栏数据
    data class Row(val isHeader: Boolean, val text: String, val chapterIndex: Int, val groupIndex: Int, var expanded: Boolean)
    private var rows = mutableListOf<Row>()
    private val expandedGroups = mutableSetOf<Int>()
    private lateinit var rowsAdapter: ChapterAdapter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        searchInput = findViewById(R.id.searchInput)
        searchPanel = findViewById(R.id.searchPanel)
        topBar = findViewById(R.id.topBar)
        configBox = findViewById(R.id.configBox)
        btnSidebar = findViewById(R.id.btnSidebar)
        sidebar = findViewById(R.id.sidebar)
        sideTitle = findViewById(R.id.sideTitle)
        sideStatus = findViewById(R.id.sideStatus)
        sideList = findViewById(R.id.sideList)
        sideSearch = findViewById(R.id.sideSearch)
        sourceList = findViewById(R.id.sourceList)
        resultsTitle = findViewById(R.id.resultsTitle)
        loadBar = findViewById(R.id.loadBar)
        sourceEmpty = findViewById(R.id.sourceEmpty)
        shelfPanel = findViewById(R.id.shelfPanel)
        shelfRows = findViewById(R.id.shelfRows)
        shelfHint = findViewById(R.id.shelfHint)

        readabilityJs = readAsset("readability.js")
        readerJs = readAsset("reader.js")
        adblockJs = readAsset("adblock.js")
        adblockEnabled = getPreferences(Context.MODE_PRIVATE).getBoolean("adblock", true)
        readAsset("adhosts.txt").lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { adHosts.add(it.lowercase()) }

        rowsAdapter = ChapterAdapter()
        sideList.adapter = rowsAdapter
        sideList.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            if (r.isHeader) {
                if (!expandedGroups.add(r.groupIndex)) expandedGroups.remove(r.groupIndex)
                rebuildRows()
                rowsAdapter.notifyDataSetChanged()
            } else openChapter(r.chapterIndex)
        }

        sourceList.adapter = SourceAdapter()
        sideSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                rebuildRows(); rowsAdapter.notifyDataSetChanged()
            }
        })

        sourceList.setOnItemClickListener { _, _, pos, _ -> searchPanel.visibility = View.GONE; startBook(currentResults[pos]) }

        setupWebView()
        setupTopBar()
        buildSearchConfig()

        val deep = intent?.data?.toString()
        if (deep != null) webView.loadUrl(deep) else showShelfPanel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.textZoom = 100
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(JSBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (adblockEnabled && request != null && !request.isForMainFrame) {
                    val host = request.url?.host?.lowercase()
                    if (host != null && isAdHost(host)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (readingMode) return true
                if (url.startsWith("http")) Stores.addHistory(this@MainActivity, view?.title.orEmpty(), url)
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                readingMode = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectScripts()
                if (adblockEnabled) injectAdblock()
                if (internalPage) { internalPage = false; return }
                if (pendingAutoRead) { pendingAutoRead = false; enterReading() }
                else if (rawViewOnce) { rawViewOnce = false }
                else enterReading()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true)
                    Toast.makeText(this@MainActivity, "加载失败：${error?.description?.toString().orEmpty()}", Toast.LENGTH_SHORT).show()
            }
        }
        webView.webChromeClient = WebChromeClient()
    }

    private fun setupTopBar() {
        btnSidebar.setOnClickListener { toggleSidebar(sidebar.visibility != View.VISIBLE) }
        findViewById<Button>(R.id.btnShelf).setOnClickListener { hideKeyboard(); showShelfPanel() }
        findViewById<Button>(R.id.btnSearchBack).setOnClickListener { hideKeyboard(); showShelfPanel() }
        findViewById<Button>(R.id.sideClose).setOnClickListener { toggleSidebar(false) }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnShelfSearch).setOnClickListener { showSearchPanel() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { submit() }

        searchInput.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) { submit(); true } else false
        }
    }

    // 功能容器：加入书架 / 历史 / 导入源 / 原网页（书架按钮独立在顶栏）
    private fun showMenu(anchor: View) {
        pmMenu?.dismiss()
        // 与目录互斥：开菜单先关侧边栏
        if (sidebar.visibility == View.VISIBLE) toggleSidebar(false)
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "加入书架")
        pm.menu.add(0, 2, 1, "历史记录")
        pm.menu.add(0, 3, 2, "导入源")
        pm.menu.add(0, 4, 3, "查看原网页")
        pm.menu.add(0, 5, 4, "阅读设置")
        pm.menu.add(0, 6, 5, if (currentBook != null && bmSet(currentBook!!.url).contains(currentChapterIndex)) "取消书签" else "添加书签")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { addCurrentToShelf(); true }
                2 -> { showHistory(); true }
                3 -> { showImport(); true }
                4 -> { rawViewOnce = true; webView.reload(); toast("已临时显示原网页，下次进入自动阅读"); true }
                5 -> { showReadingSettings(); true }
                6 -> { toggleBookmark(); true }
                else -> false
            }
        }
        pmMenu = pm
        pm.setOnDismissListener { pmMenu = null }
        pm.show()
    }

    private fun addCurrentToShelf() {
        val book = currentBook
        if (book == null) { toast("当前没有可加入书架的书"); return }
        Stores.addShelf(this, book.title, book.url, book.host)
        toast("已加入书架（进度随阅读记录）")
    }

    // ---------- 搜索配置（只保留广度与数量；引擎固定全部使用） ----------
    private data class SearchCfg(val pages: Int = 3, val limit: Int = 30)

    private fun readSearchConfig(): SearchCfg {
        val p = getPreferences(Context.MODE_PRIVATE)
        return SearchCfg(p.getInt("cfg_pages", 3), p.getInt("cfg_limit", 30))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(10), 0, dp(2))
        setTextColor(android.graphics.Color.parseColor("#333333"))
    }

    private fun desc(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(android.graphics.Color.parseColor("#888888"))
        setPadding(0, 0, 0, dp(6))
    }

    // 搜索页内的配置区（只保留广度与数量；改动即保存）
    private fun buildSearchConfig() {
        val cfg = readSearchConfig()
        configBox.removeAllViews()

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = android.view.Gravity.CENTER_VERTICAL
        val spPages = Spinner(this)
        spPages.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5"))
        spPages.setSelection((cfg.pages - 1).coerceIn(0, 4))
        val spLimit = Spinner(this)
        val lims = listOf("10", "20", "30", "50", "60")
        spLimit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lims)
        spLimit.setSelection(lims.indexOf(cfg.limit.toString()).coerceAtLeast(0))
        row2.addView(inlineLabel("广度 "))
        row2.addView(spPages)
        row2.addView(inlineLabel(" 校验 "))
        row2.addView(spLimit)
        configBox.addView(row2)
        configBox.addView(desc("广度=每引擎抓取页数（越大越全但更慢）；校验=最多候选数；建议 广度3 + 校验30。"))

        val save = {
            getPreferences(Context.MODE_PRIVATE).edit()
                .putInt("cfg_pages", spPages.selectedItem.toString().toInt())
                .putInt("cfg_limit", spLimit.selectedItem.toString().toInt())
                .apply()
        }
        val sel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { save() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spPages.onItemSelectedListener = sel
        spLimit.onItemSelectedListener = sel
    }

    private fun inlineLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(android.graphics.Color.parseColor("#333333"))
    }

    // ---------- 阅读设置 ----------
    private data class RCfg(val fontSize: Int = 17, val font: String = "system", val lineHeight: Float = 1.9f, val margin: Int = 1, val theme: String = "light", val pageTurn: String = "scroll", val autoPage: Int = 0, val keepScreen: Boolean = true)

    private fun readRCfg(): RCfg {
        val p = getPreferences(Context.MODE_PRIVATE)
        return RCfg(
            p.getInt("r_fs", 17), p.getString("r_font", "system") ?: "system",
            p.getFloat("r_lh", 1.9f), p.getInt("r_margin", 1),
            p.getString("r_theme", "light") ?: "light",
            p.getString("r_turn", "scroll") ?: "scroll",
            p.getInt("r_auto", 0), p.getBoolean("r_keep", true)
        )
    }

    private fun themeColors(t: String): Pair<String, String> = when (t) {
        "sepia" -> "#3b2f20" to "#f5ecd7"
        "green" -> "#1f3b2a" to "#e3f0e3"
        "dark" -> "#bbbbbb" to "#141414"
        else -> "#222222" to "#ffffff"
    }

    private fun fontCss(f: String): String = when (f) {
        "song" -> "'Songti SC','SimSun',serif"
        "kai" -> "'Kaiti SC','KaiTi',serif"
        "hei" -> "'PingFang SC','Microsoft YaHei',sans-serif"
        else -> "sans-serif"
    }

    private fun showReadingSettings() {
        val c = readRCfg()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad / 2, pad, pad / 2)

        fun sp(options: List<String>, sel: Int): Spinner {
            val s = Spinner(this@MainActivity)
            s.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            if (sel >= 0) s.setSelection(sel)
            return s
        }
        fun row(labelTxt: String, s: Spinner) {
            val l = LinearLayout(this@MainActivity)
            l.orientation = LinearLayout.HORIZONTAL
            l.gravity = android.view.Gravity.CENTER_VERTICAL
            l.addView(inlineLabel(labelTxt))
            l.addView(s, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(l)
        }

        val spFs = sp((12..26).map { "$it" }, c.fontSize - 12)
        val spFont = sp(listOf("系统字体", "宋体", "楷体", "黑体"), listOf("system", "song", "kai", "hei").indexOf(c.font))
        val spLh = sp(listOf("紧凑　1.6", "适中　1.9", "宽松　2.2"), listOf(1.6f, 1.9f, 2.2f).indexOfFirst { Math.abs(it - c.lineHeight) < 0.01f })
        val spMg = sp(listOf("窄", "中", "宽"), c.margin.coerceIn(0, 2))
        val spTheme = sp(listOf("白", "米黄（护眼）", "浅绿（护眼）", "夜间黑"), listOf("light", "sepia", "green", "dark").indexOf(c.theme))
        val spTurn = sp(listOf("上下滚动", "仿真翻页"), listOf("scroll", "flip").indexOf(c.pageTurn))
        val spAuto = sp(listOf("关", "8 秒", "15 秒", "30 秒"), listOf(0, 8, 15, 30).indexOf(c.autoPage))
        val ckKeep = CheckBox(this)
        ckKeep.text = "阅读时保持屏幕常亮"
        ckKeep.isChecked = c.keepScreen

        row("字号 ", spFs); row("字体 ", spFont); row("行距 ", spLh); row("边距 ", spMg)
        row("主题 ", spTheme); row("翻页 ", spTurn); row("自动翻页 ", spAuto)
        root.addView(ckKeep)

        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putInt("r_fs", spFs.selectedItem.toString().toInt())
                    .putString("r_font", listOf("system", "song", "kai", "hei")[spFont.selectedItemPosition])
                    .putFloat("r_lh", listOf(1.6f, 1.9f, 2.2f)[spLh.selectedItemPosition])
                    .putInt("r_margin", spMg.selectedItemPosition)
                    .putString("r_theme", listOf("light", "sepia", "green", "dark")[spTheme.selectedItemPosition])
                    .putString("r_turn", listOf("scroll", "flip")[spTurn.selectedItemPosition])
                    .putInt("r_auto", listOf(0, 8, 15, 30)[spAuto.selectedItemPosition])
                    .putBoolean("r_keep", ckKeep.isChecked)
                    .apply()
                toast("阅读设置已保存")
                if (currentBook != null && shelfPanel.visibility != View.VISIBLE) openChapter(currentChapterIndex)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 书签 ----------
    private fun bmSet(bookUrl: String): Set<Int> {
        val raw = getPreferences(Context.MODE_PRIVATE).getString("bm_$bookUrl", "[]") ?: "[]"
        return try { val a = JSONArray(raw); (0 until a.length()).map { a.getInt(it) }.toSet() } catch (e: Exception) { emptySet() }
    }

    private fun toggleBookmark() {
        val b = currentBook ?: return
        val s = bmSet(b.url).toMutableSet()
        if (!s.add(currentChapterIndex)) s.remove(currentChapterIndex)
        getPreferences(Context.MODE_PRIVATE).edit().putString("bm_${b.url}", JSONArray(s.toList()).toString()).apply()
        rebuildRows(); rowsAdapter.notifyDataSetChanged()
        toast(if (bmSet(b.url).contains(currentChapterIndex)) "已添加书签 ★" else "已取消书签")
    }

    private fun showSearchPanel() {
        shelfPanel.visibility = View.GONE
        sidebar.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
        applyStatusBar(0xFFFFFFFF.toInt(), true)
        searchInput.requestFocus()
        showKeyboard()
    }

    private fun submit() {
        val s = searchInput.text.toString().trim()
        if (s.isEmpty()) return
        searchInput.clearFocus(); hideKeyboard()
        if (looksLikeUrl(s)) { webView.loadUrl(if (s.startsWith("http")) s else "https://$s"); return }
        crawlerSearch(s)
    }

    private fun looksLikeUrl(s: String): Boolean {
        if (s.contains(" ")) return false
        if (s.any { it.code in 0x4E00..0x9FFF }) return false
        return s.contains(".") && Regex("""^[a-zA-Z0-9][a-zA-Z0-9.\-:/?#=&%]*$""").matches(s)
    }

    // ---------- 检索聚合 ----------
    private fun crawlerSearch(q: String) {
        // 立即显示“检索中”加载状态
        shelfPanel.visibility = View.GONE
        resultsTitle.text = "检索中…（$q）"
        sourceEmpty.visibility = View.GONE
        loadBar.visibility = View.VISIBLE
        searchPanel.visibility = View.VISIBLE
        sidebar.visibility = View.GONE
        Thread {
            val cfg = readSearchConfig()
            val all = SourceValidator.searchSources(q, cfg.pages, cfg.limit, true, true).toMutableList()
            for (u in userSources()) {
                val v = SourceValidator.validateAll(listOf(u.second to u.first)).firstOrNull() ?: continue
                if (v.usable) all.add(v)
            }
            val usable = all.filter { it.usable }.sortedWith(
                compareBy<SourceValidator.Source> { it.freeQ < 100 }.thenByDescending { it.score }
            )
            runOnUiThread {
                loadBar.visibility = View.GONE
                if (usable.isEmpty()) {
                    currentResults = emptyList()
                    resultsTitle.text = "未找到可阅读页面（$q）"
                    sourceEmpty.visibility = View.VISIBLE
                } else showSources(q, usable)
            }
        }.start()
    }

    private fun showSources(q: String, list: List<SourceValidator.Source>) {
        if (list.isEmpty()) { toast("未找到相关结果：$q"); return }
        currentResults = list
        resultsTitle.text = "检索结果（$q）· 点击阅读"
        sourceEmpty.visibility = View.GONE
        loadBar.visibility = View.GONE
        (sourceList.adapter as? SourceAdapter)?.notifyDataSetChanged()
        searchPanel.visibility = View.VISIBLE
        sidebar.visibility = View.GONE
    }

    private fun startBook(src: SourceValidator.Source) {
        currentBook = src
        currentChapterIndex = 0
        sideTitle.text = src.title.take(12)
        shelfPanel.visibility = View.GONE
        searchPanel.visibility = View.GONE
        if (src.chapters.isEmpty()) { webView.loadUrl(src.url); pendingAutoRead = true; return }
        openChapter(0)
    }

    // ---------- 流式连续阅读 ----------
    private var streamStart = 0
    private var streamEnd = 0
    private var fetchingStream = false
    private var currentProgress = 0

    // ---------- 章节打开（正文“看到哪拿哪”+ 流式连续阅读） ----------
    private fun openChapter(i: Int, initialProgress: Int = 0) {
        val book = currentBook ?: return
        if (i < 0 || i >= book.chapters.size) return
        currentChapterIndex = i
        currentProgress = initialProgress
        val c = readRCfg()
        webView.keepScreenOn = c.keepScreen
        val tb = themeBgInt(c.theme)
        applyStatusBar(tb, c.theme != "dark")
        topBar.setBackgroundColor(tb)
        val ch = book.chapters[i]
        updateSideStatus()
        rowsAdapter.notifyDataSetChanged()
        Stores.updateProgress(this, book.url, i, ch.title, initialProgress)
        Stores.touchShelf(this, book.url)

        Thread {
            val text = SourceValidator.fetchHtml(ch.url)?.let { TextCleaner.clean(TextCleaner.extractContentText(it)) }
            if (!text.isNullOrBlank() && text.length > 50) {
                runOnUiThread {
                    streamStart = i; streamEnd = i
                    internalPage = true
                    webView.loadDataWithBaseURL(null, buildStreamHtml(ch, text, i, initialProgress), "text/html", "utf-8", null)
                }
            } else {
                runOnUiThread { pendingAutoRead = true; webView.loadUrl(ch.url) }
            }
        }.start()
    }

    // 流式阅读文档：当前章正文 + 无缝追加/前插 + 自动翻页
    private fun buildStreamHtml(ch: SourceValidator.Chapter, text: String, idx: Int, progress: Int = 0): String {
        val c = readRCfg()
        val tc = themeColors(c.theme)
        val padX = c.margin * 16 + 4
        val flip = c.pageTurn == "flip"
        val fs = c.fontSize
        val lh = c.lineHeight

        val init = "var INIT={t:" + jsString(TextCleaner.cleanTitle(ch.title)) + ",x:" + jsString(text) + ",i:" + idx + ",p:" + progress + "};"
        val bodyStyle = "margin:0;padding:0 ${padX}px;font-size:${fs}px;line-height:${lh};color:${tc.first};background:${tc.second};font-family:${fontCss(c.font)};text-align:justify"
        val script = init +
                "var FLIP=" + if (flip) "true" else "false" + ";" +
                "function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}" +
                "function mk(t,x,i){var d=document.createElement('div');d.className='chsec';d.setAttribute('data-i',i);var h=document.createElement('h3');h.textContent=t;h.style.cssText='font-size:1.15em;margin:1.2em 0 .6em;color:" + tc.first + "';d.appendChild(h);" +
                "var ps=x.split('\\n');for(var k=0;k<ps.length;k++){var p=ps[k].replace(/\\s+/g,' ').trim();if(!p)continue;var el=document.createElement('p');el.textContent=p;el.style.cssText='margin:0 0 1em;line-height:" + lh + ";text-indent:2em;';d.appendChild(el);}return d;}" +
                "function stream(){return FLIP?document.getElementById('pg'):document.getElementById('stream');}" +
                "window.__appendChapter__=function(t,x,i){stream().appendChild(mk(t,x,i));window.__cur=i;};" +
                "window.__prependChapter__=function(t,x,i){if(FLIP){var pg=document.getElementById('pg');var sl=pg.scrollLeft;pg.insertBefore(mk(t,x,i),pg.firstChild);pg.scrollLeft=sl+pg.firstChild.offsetWidth;}else{var y=window.scrollY;var st=document.getElementById('stream');st.insertBefore(mk(t,x,i),st.firstChild);window.scrollTo(0,y+st.firstChild.offsetHeight);}window.__cur=i;};" +
                "window.Android&&window.Android.setCurrentChapter&&window.Android.setCurrentChapter(" + idx + ");" +
                "var pgEl=document.getElementById('pg');" +
                "if(FLIP&&pgEl){pgEl.addEventListener('scroll',function(){if(pgEl.scrollLeft+pgEl.clientWidth>=pgEl.scrollWidth-30&&!window.__nxt){window.__nxt=1;window.Android&&window.Android.nextChapter();}});var sx=0;document.addEventListener('touchstart',function(e){sx=e.touches[0].clientX},{passive:true});document.addEventListener('touchend',function(e){if(pgEl.scrollLeft<=2&&!window.__prv&&e.changedTouches[0].clientX-sx>90){window.__prv=1;window.Android&&window.Android.prevChapter();}},{passive:true});}" +
                "else{window.addEventListener('scroll',function(){if(window.innerHeight+window.scrollY>=document.body.scrollHeight-80&&!window.__nxt){window.__nxt=1;window.Android&&window.Android.nextChapter();}});var sy=0;document.addEventListener('touchstart',function(e){sy=e.touches[0].clientY},{passive:true});document.addEventListener('touchend',function(e){if(window.scrollY<=2&&!window.__prv&&e.changedTouches[0].clientY-sy>130){window.__prv=1;window.Android&&window.Android.prevChapter();}},{passive:true});}" +
                "window.__nxt=0;window.__prv=0;" +
                "(function(){var st=document.getElementById('stream')||document.getElementById('pg');st.appendChild(mk(INIT.t,INIT.x,INIT.i));})();" +
                // 进度上报 + 恢复
                "var __lastRep=0;" +
                "function report(){try{var secs=document.querySelectorAll('.chsec');if(!secs.length)return;var y=window.scrollY,sl=FLIP?(document.getElementById('pg')||{scrollLeft:0}).scrollLeft:0;var cur=null;for(var k=0;k<secs.length;k++){if(FLIP){if(secs[k].offsetLeft<=sl+40)cur=secs[k];}else{if(secs[k].offsetTop<=y+window.innerHeight*0.2)cur=secs[k];}}if(!cur)return;var pct=0;if(FLIP){pct=(pgEl&&pgEl.scrollLeft-cur.offsetLeft)/Math.max(1,cur.offsetWidth)*100;}else{pct=(y-cur.offsetTop)/Math.max(1,cur.offsetHeight-window.innerHeight)*100;}pct=Math.max(0,Math.min(100,Math.round(pct)));var now=Date.now();if(now-__lastRep>400){__lastRep=now;window.Android&&window.Android.setChapterProgress&&window.Android.setChapterProgress(parseInt(cur.getAttribute('data-i')||'0',10),pct);}}catch(e){}}" +
                "window.addEventListener('scroll',report,{passive:true});if(FLIP&&pgEl){pgEl.addEventListener('scroll',report,{passive:true});}" +
                "try{if(FLIP&&pgEl){var mx=Math.max(0,pgEl.scrollWidth-pgEl.clientWidth);pgEl.scrollLeft=INIT.p/100*mx;}else{window.scrollTo(0,INIT.p/100*Math.max(0,document.body.scrollHeight-window.innerHeight));}}catch(e){}" +
                "var auto=parseInt(" + (c.autoPage) + ",10);if(auto>0){setInterval(function(){try{if(FLIP){var w=document.getElementById('pg');w.scrollLeft+=w.clientWidth;}else window.scrollBy(0,window.innerHeight*0.9);}catch(e){}},auto*1000);}";

        val body = if (flip)
            "<div id=\"pg\" style=\"height:92vh;overflow-x:auto;overflow-y:hidden;column-width:calc(100vw - ${padX * 2}px);column-gap:0;scroll-snap-type:x mandatory;-webkit-column-fill:auto\"><div id=\"stream\"></div></div>"
        else "<div id=\"stream\"></div>"

        return "<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"></head>" +
                "<body style=\"$bodyStyle\">$body<script>$script</script></body></html>"
    }

    // ---------- 阅读模式：底层永远开启 ----------
    private fun enterReading(tryCount: Int = 0) {
        val js = "window.__RUN_READING__ ? window.__RUN_READING__() : {found:false}"
        webView.evaluateJavascript(js) { res ->
            val found = try { if (res.isNullOrBlank() || res == "null") false else JSONObject(res).optBoolean("found", false) } catch (e: Exception) { false }
            if (found) { readingMode = true }
            else if (tryCount < 3) {
                // 站点 JS 可能延迟渲染正文：稍后再试
                webView.postDelayed({ enterReading(tryCount + 1) }, 1000)
            }
        }
    }

    private fun injectScripts() {
        val c = readRCfg()
        val rcfg = JSONObject().put("fontSize", c.fontSize).put("font", c.font)
            .put("lineHeight", c.lineHeight).put("margin", c.margin)
            .put("theme", c.theme).put("pageTurn", c.pageTurn).put("autoPage", c.autoPage).toString()
        val script = "window.__RCFG__=$rcfg; $readabilityJs; $readerJs; window.__READER_READY__=true;"
        webView.evaluateJavascript(script) { }
    }

    private fun injectAdblock() {
        webView.evaluateJavascript("$adblockJs; window.__ADBLOCK__ && window.__ADBLOCK__();") { }
    }

    private fun isAdHost(host: String): Boolean {
        if (adHosts.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx >= 0) { val d = host.substring(idx + 1); if (adHosts.contains(d)) return true; idx = host.indexOf('.', idx + 1) }
        return false
    }

    // ---------- 侧边栏 ----------
    private fun toggleSidebar(show: Boolean) {
        if (show) {
            // 与多功能栏互斥
            pmMenu?.dismiss()
            applySidebarTheme()
            sidebar.translationX = 0f
            sidebar.visibility = View.VISIBLE
            rebuildRows()
            updateSideStatus()
            rowsAdapter.notifyDataSetChanged()
        } else {
            sidebar.visibility = View.GONE
        }
    }

    // 目录跟随阅读设置（主题底色/文字色/字号）
    private fun applySidebarTheme() {
        val c = readRCfg()
        val (fg, bg, sec, acc) = when (c.theme) {
            "dark" -> arrayOf("#bbbbbb", "#141414", "#8a8a8a", "#6fb3ff")
            "sepia" -> arrayOf("#3b2f20", "#f5ecd7", "#8a7a5f", "#8a5a2b")
            "green" -> arrayOf("#1f3b2a", "#e3f0e3", "#5a7a63", "#2e6b4f")
            else -> arrayOf("#333333", "#ffffff", "#68707a", "#1a73e8")
        }
        themeFg = fg; themeSec = sec; themeAcc = acc
        sidebar.setBackgroundColor(android.graphics.Color.parseColor(bg))
        sideTitle.setTextColor(android.graphics.Color.parseColor(fg))
        sideStatus.setTextColor(android.graphics.Color.parseColor(sec))
        sideSearch.setTextColor(android.graphics.Color.parseColor(fg))
        sideSearch.setHintTextColor(android.graphics.Color.parseColor(sec))
        val rb = android.graphics.drawable.GradientDrawable()
        rb.cornerRadius = 12f
        rb.setStroke(1, android.graphics.Color.parseColor(sec).let { it and 0x55FFFFFF })
        rb.setColor(0)
        sideSearch.background = rb
        val sz = (c.fontSize * 0.82f).coerceIn(13f, 19f)
        sideSearch.textSize = sz
        // 章节列表字体大小跟随
        sideList.adapter?.also { rowsAdapter.notifyDataSetChanged() }
    }

    private fun updateSideStatus() {
        val book = currentBook
        if (book == null || book.chapters.isEmpty()) { sideStatus.text = "暂无章节"; return }
        val pct = (currentChapterIndex + 1) * 100 / book.chapters.size
        val bm = bmSet(book.url).contains(currentChapterIndex)
        sideStatus.text = (if (bm) "★ " else "") + "第 ${currentChapterIndex + 1} / ${book.chapters.size} 章 · ${pct}%"
    }

    private fun rebuildRows() {
        rows = mutableListOf()
        val book = currentBook ?: return
        val n = book.chapters.size
        val q = sideSearch.text.toString().trim()
        // 书签集合
        val bm = bmSet(book.url)
        fun star(i: Int) = if (bm.contains(i)) "★ " else ""

        if (q.isNotBlank()) {
            // 搜索模式：平铺过滤（保留分组头？平铺更直接）
            for (i in 0 until n) {
                if (book.chapters[i].title.contains(q, ignoreCase = true)) {
                    rows.add(Row(false, star(i) + book.chapters[i].title, i, 0, false))
                }
            }
            return
        }

        expandedGroups.add(currentChapterIndex / GROUP)
        var g = 0
        while (g * GROUP < n) {
            val from = g * GROUP + 1
            val to = minOf((g + 1) * GROUP, n)
            val isExp = expandedGroups.contains(g)
            rows.add(Row(true, "第 $from - $to 章", -1, g, isExp))
            if (isExp) {
                for (i in g * GROUP until minOf((g + 1) * GROUP, n)) rows.add(Row(false, star(i) + book.chapters[i].title, i, g, false))
            }
            g++
        }
    }

    // ---------- 模拟书架（初始屏 + 进度续读） ----------
    private val BOOK_COLORS = listOf("#4a6fa5", "#7a5ca8", "#3f7d5a", "#b25b6b", "#c08a3e", "#5b7f9e", "#8a6d3b", "#6b4f8a")

    private fun showShelfPanel() {
        shelfPanel.visibility = View.VISIBLE
        searchPanel.visibility = View.GONE
        sidebar.visibility = View.GONE
        applyStatusBar(0xFFFFFFFF.toInt(), true)
        buildShelfBooks()
    }

    private fun buildShelfBooks() {
        shelfRows.removeAllViews()
        val items = Stores.shelves(this)
        shelfHint.text = if (items.isEmpty()) "书架空空：点右上「搜索」找书，加入书架后进度自动记录"
        else "点书继续阅读 · 长按管理 · 进度自动记录"
        for (item in items) shelfRows.addView(makeBook(item))
    }

    private fun makeBook(item: Stores.ShelfItem): View {
        val dp = resources.displayMetrics.density
        val c = BOOK_COLORS[(item.title.hashCode() and 0x7FFFFFFF) % BOOK_COLORS.size]
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = android.view.Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(-1, (96 * dp).toInt())
        lp.bottomMargin = (12 * dp).toInt()
        card.layoutParams = lp

        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = 10 * dp
        bg.setColor(android.graphics.Color.WHITE)
        bg.setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#e7e9ee"))
        card.background = bg

        // 书脊色条（左侧）
        val strip = View(this)
        val sg = android.graphics.drawable.GradientDrawable()
        sg.cornerRadius = 10 * dp
        sg.setColor(android.graphics.Color.parseColor(c))
        strip.background = sg
        card.addView(strip, LinearLayout.LayoutParams((12 * dp).toInt(), -1))

        // 信息列
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((12 * dp).toInt(), (10 * dp).toInt(), 0, (10 * dp).toInt())

        val t = TextView(this)
        t.text = item.title
        t.setTextColor(android.graphics.Color.parseColor("#1b1b1b"))
        t.textSize = 15f
        t.setSingleLine(true)
        t.maxLines = 1

        val s = TextView(this)
        s.text = "来源：${item.source}${if (item.chapterTitle.isNotBlank()) " · 上次：${item.chapterTitle}" else ""}"
        s.setTextColor(android.graphics.Color.parseColor("#68707a"))
        s.textSize = 12f
        s.setSingleLine(true)
        s.maxLines = 1

        val p = TextView(this)
        p.text = if (item.chapterIndex > 0) "第 ${item.chapterIndex + 1} 章" else "未读"
        p.setTextColor(android.graphics.Color.parseColor(if (item.chapterIndex > 0) "#1a73e8" else "#b0b6bf"))
        p.textSize = 13f

        col.addView(t)
        col.addView(s)
        col.addView(p)
        card.addView(col, LinearLayout.LayoutParams(0, -1, 1f))

        val arr = TextView(this)
        arr.text = "›"
        arr.setTextColor(android.graphics.Color.parseColor("#b8bfc8"))
        arr.textSize = 24f
        arr.gravity = android.view.Gravity.CENTER
        card.addView(arr, LinearLayout.LayoutParams((44 * dp).toInt(), -1))

        card.setOnClickListener { openShelfBook(item) }
        card.setOnLongClickListener {
            val opts = arrayOf<CharSequence>("继续阅读", "从书架移除")
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(opts) { _, which ->
                    if (which == 0) openShelfBook(item)
                    else { Stores.removeShelf(this, item.url); buildShelfBooks(); toast("已从书架移除") }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        return card
    }

    private fun openShelfBook(item: Stores.ShelfItem) {
        shelfPanel.visibility = View.GONE
        searchPanel.visibility = View.GONE
        Thread {
            val html = SourceValidator.fetchHtml(item.url)
            val chapters = if (html != null) SourceValidator.extractToc(html, item.url) else emptyList()
            runOnUiThread {
                if (chapters.isNotEmpty()) {
                    val src = SourceValidator.Source(item.url, item.title, item.source, "dir", chapters.size, chapters, 0, "目录", true, 0.0)
                    currentBook = src
                    sideTitle.text = src.title.take(12)
                    openChapter(item.chapterIndex.coerceIn(0, chapters.size - 1), item.progress)
                } else {
                    pendingAutoRead = true
                    webView.loadUrl(item.url)
                }
            }
        }.start()
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 书架 / 历史 / 导入源 ----------
    private fun showShelf() {
        val items = Stores.shelves(this)
        if (items.isEmpty()) { toast("书架是空的"); return }
        AlertDialog.Builder(this)
            .setTitle("书架")
            .setItems(items.map { "${it.title}\n${it.source}" }.toTypedArray<CharSequence>()) { _, which -> webView.loadUrl(items[which].url) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showHistory() {
        val items = Stores.history(this)
        if (items.isEmpty()) { toast("暂无历史记录"); return }
        AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(items.take(80).map { "${it.title}\n${it.url.take(40)}" }.toTypedArray<CharSequence>()) { _, which -> webView.loadUrl(items[which].url) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showImport() {
        val et = EditText(this)
        et.hint = "粘贴书源 URL / 站点地址（每行一个），或 {\"name\":..,\"url\":..}"
        et.minLines = 3
        AlertDialog.Builder(this)
            .setTitle("导入用户书源")
            .setView(et)
            .setPositiveButton("导入") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val list = userSources().toMutableList()
                for (line in text.split(Regex("[\\n\\s]+")).map { it.trim() }.filter { it.isNotBlank() }) {
                    if (line.startsWith("{")) {
                        try { val o = JSONObject(line); if (o.has("url")) list.add(o.optString("name", "导入源") to o.optString("url")) } catch (e: Exception) { }
                    } else if (line.startsWith("http") || line.startsWith("www.")) {
                        list.add("导入源" to if (line.startsWith("www.")) "https://$line" else line)
                    }
                }
                getPreferences(Context.MODE_PRIVATE).edit().putString("userSources",
                    JSONArray().apply { list.forEach { put(JSONObject().put("name", it.first).put("url", it.second)) } }.toString()).apply()
                toast("已导入 ${list.size} 条书源")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun userSources(): List<Pair<String, String>> {
        val raw = getPreferences(Context.MODE_PRIVATE).getString("userSources", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> val o = arr.optJSONObject(i); (o?.optString("name", "导入源") ?: "导入源") to o?.optString("url").orEmpty() }.filter { it.second.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    // ---------- 其他 ----------
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    // 状态栏与页面统一配色（刘海/状态栏区分更干净）
    private fun applyStatusBar(bg: Int, darkIcons: Boolean) {
        try {
            window.statusBarColor = bg
            var f = window.decorView.systemUiVisibility
            f = if (darkIcons) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            window.decorView.systemUiVisibility = f
        } catch (e: Exception) { }
    }

    private fun themeBgInt(t: String): Int = when (t) {
        "sepia" -> 0xFFF5ECD7.toInt()
        "green" -> 0xFFE3F0E3.toInt()
        "dark" -> 0xFF141414.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun readAsset(name: String): String = try { resources.assets.open(name).bufferedReader().readText() } catch (e: Exception) { "" }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val reading = currentBook != null && sidebar.visibility != View.VISIBLE &&
                searchPanel.visibility != View.VISIBLE && shelfPanel.visibility != View.VISIBLE
        if (reading && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { openChapter(currentChapterIndex + 1); return true }
        if (reading && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { openChapter(currentChapterIndex - 1); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (sidebar.visibility == View.VISIBLE) { toggleSidebar(false); return }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() { webView.removeJavascriptInterface("Android"); webView.destroy(); super.onDestroy() }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.data?.toString()
        if (!url.isNullOrBlank()) { rawViewOnce = false; webView.loadUrl(url) }
    }

    inner class JSBridge {
        @JavascriptInterface fun exitReading() = this@MainActivity.runOnUiThread { rawViewOnce = true; webView.reload() }
        @JavascriptInterface fun gotoChapter(url: String) = this@MainActivity.runOnUiThread {
            val book = currentBook ?: return@runOnUiThread
            val idx = book.chapters.indexOfFirst { it.url == url }
            if (idx >= 0) openChapter(idx)
        }
        @JavascriptInterface fun showToc() = this@MainActivity.runOnUiThread { toggleSidebar(true) }
        @JavascriptInterface fun nextChapter() = this@MainActivity.runOnUiThread {
            val book = currentBook ?: return@runOnUiThread
            if (fetchingStream) return@runOnUiThread
            val nxt = streamEnd + 1
            if (nxt >= book.chapters.size) return@runOnUiThread
            fetchingStream = true
            Thread {
                val ch = book.chapters[nxt]
                val text = SourceValidator.fetchHtml(ch.url)?.let { TextCleaner.clean(TextCleaner.extractContentText(it)) }
                runOnUiThread {
                    fetchingStream = false
                    if (!text.isNullOrBlank() && text.length > 50) {
                        streamEnd = nxt
                        webView.evaluateJavascript("window.__appendChapter__(" + jsString(TextCleaner.cleanTitle(ch.title)) + "," + jsString(text) + "," + nxt + ")") { }
                        currentChapterIndex = nxt
                        Stores.updateProgress(this@MainActivity, book.url, nxt, ch.title)
                        updateSideStatus(); rowsAdapter.notifyDataSetChanged()
                    }
                }
            }.start()
        }

        @JavascriptInterface fun prevChapter() = this@MainActivity.runOnUiThread {
            val book = currentBook ?: return@runOnUiThread
            if (fetchingStream) return@runOnUiThread
            val prv = streamStart - 1
            if (prv < 0) return@runOnUiThread
            fetchingStream = true
            Thread {
                val ch = book.chapters[prv]
                val text = SourceValidator.fetchHtml(ch.url)?.let { TextCleaner.clean(TextCleaner.extractContentText(it)) }
                runOnUiThread {
                    fetchingStream = false
                    if (!text.isNullOrBlank() && text.length > 50) {
                        streamStart = prv
                        webView.evaluateJavascript("window.__prependChapter__(" + jsString(TextCleaner.cleanTitle(ch.title)) + "," + jsString(text) + "," + prv + ")") { }
                        currentChapterIndex = prv
                        Stores.updateProgress(this@MainActivity, book.url, prv, ch.title)
                        updateSideStatus(); rowsAdapter.notifyDataSetChanged()
                    }
                }
            }.start()
        }

        @JavascriptInterface fun setCurrentChapter(i: Int) = this@MainActivity.runOnUiThread {
            val book = currentBook ?: return@runOnUiThread
            if (i in 0 until book.chapters.size && i != currentChapterIndex) {
                currentChapterIndex = i
                Stores.updateProgress(this@MainActivity, book.url, i, book.chapters[i].title, currentProgress)
                updateSideStatus()
            }
        }

        @JavascriptInterface fun setChapterProgress(i: Int, p: Int) = this@MainActivity.runOnUiThread {
            val book = currentBook ?: return@runOnUiThread
            if (i !in 0 until book.chapters.size) return@runOnUiThread
            val changed = i != currentChapterIndex
            currentChapterIndex = i
            currentProgress = p.coerceIn(0, 100)
            Stores.updateProgress(this@MainActivity, book.url, i, book.chapters[i].title, currentProgress)
            updateSideStatus()
            if (changed) rowsAdapter.notifyDataSetChanged()
        }
    }

    // 侧边栏适配器
    inner class ChapterAdapter : BaseAdapter() {
        fun groupOf(chapterIdx: Int) = if (currentBook == null) 0 else chapterIdx / GROUP
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = rows[position]
            var tv: TextView = convertView as? TextView ?: TextView(this@MainActivity)
            tv.setPadding(dp(14), dp(12), dp(14), dp(12))
            tv.textSize = if (r.isHeader) 14f else 15f
            if (r.isHeader) {
                tv.text = (if (r.expanded) "∨ " else "▶ ") + r.text
                tv.setTextColor(android.graphics.Color.parseColor(themeSec))
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                tv.text = r.text
                tv.typeface = android.graphics.Typeface.DEFAULT
                tv.setTextColor(if (r.chapterIndex == currentChapterIndex) android.graphics.Color.parseColor(themeAcc) else android.graphics.Color.parseColor(themeFg))
            }
            if (!r.isHeader) tv.textSize = ((readRCfg().fontSize) * 0.82f).coerceIn(13f, 19f)
            else tv.textSize = 13f
            return tv
        }
    }

    // 检索结果条目适配器
    inner class SourceAdapter : BaseAdapter() {
        override fun getCount() = currentResults.size
        override fun getItem(position: Int) = currentResults[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_source, parent, false)
            val s = currentResults[position]
            v.findViewById<TextView>(R.id.srcTitle).text = s.title.ifBlank { s.host }
            val meta = StringBuilder("${s.host} · ${s.chapters.size}章")
            v.findViewById<TextView>(R.id.srcMeta).text = meta.toString()
            v.findViewById<TextView>(R.id.srcMeta).setTextColor(android.graphics.Color.parseColor("#68707a"))
            return v
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
