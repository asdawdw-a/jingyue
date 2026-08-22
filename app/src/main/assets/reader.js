// 最简阅读模式 v3：配置驱动（字号/字体/行距/边距/主题/翻页/自动翻页）
(function () {
  "use strict";

  var THEMES = {
    light: { fg: "#222222", bg: "#ffffff" },
    sepia: { fg: "#3b2f20", bg: "#f5ecd7" },
    green: { fg: "#1f3b2a", bg: "#e3f0e3" },
    dark:  { fg: "#bbbbbb", bg: "#141414" }
  };
  var FONTS = {
    system: "sans-serif",
    song: "'Songti SC','SimSun',serif",
    kai: "'Kaiti SC','KaiTi',serif",
    hei: "'PingFang SC','Microsoft YaHei',sans-serif"
  };

  window.__RUN_READING__ = function () {
    var res = { found: false, title: "", site: "", len: 0 };

    try {
      res.site = location.hostname || "";

      // 1) 取正文
      var article = null, contentText = "";
      try {
        article = new Readability(document).parse();
        if (article) contentText = article.textContent || "";
      } catch (e) { article = null; }

      if (contentText.replace(/\s+/g, "").length < 50) {
        var best = null, bestLen = 0;
        var els = document.querySelectorAll("div,article,section,td,dd,p");
        for (var i = 0; i < els.length; i++) {
          var t = (els[i].textContent || "");
          if (t.length > bestLen) { bestLen = t.length; best = els[i]; }
        }
        if (best) contentText = best.textContent || "";
      }
      contentText = contentText.replace(/\s+/g, " ").trim();
      if (contentText.length < 20) return res;
      contentText = cleanText(contentText);
      if (contentText.length < 20) return res;

      res.title = (article && article.title) || document.title || "";
      res.title = cleanTitle(res.title);
      res.len = contentText.length;

      // 2) 段落化：优先 Readability 内容结构（保留段）；纯文本兜底按 3 句成段
      var paras = [];
      if (article && article.content) paras = parasFromHtml(article.content);
      if (!paras.length) {
        var sentences = contentText.match(/[^。！？]+[。！？]?/g) || [contentText];
        for (var s = 0; s < sentences.length; s++) {
          if (s % 3 === 0) paras.push(sentences[s]); else paras[paras.length - 1] += sentences[s];
        }
      }
      paras = paras.map(cleanPar).filter(function (p) { return p && p.length >= 2; });

      // 3) 配置
      var cfg = window.__RCFG__ || {};
      var theme = THEMES[cfg.theme] || THEMES.light;
      var fs = parseInt(cfg.fontSize, 10) || 17;
      var lh = parseFloat(cfg.lineHeight) || 1.9;
      var fam = FONTS[cfg.font] || FONTS.system;
      var padX = (parseInt(cfg.margin, 10) || 1) * 16 + 4;   // 边距 px
      var flip = cfg.pageTurn === "flip";

      // 4) 组装
      var content = "<h1 style=\"font-size:1.35em;margin:0 0 1em;line-height:1.4\">" + esc(res.title) + "</h1>";
      for (var j = 0; j < paras.length; j++) {
        content += "<p style=\"margin:0 0 1em;line-height:" + lh + ";text-indent:2em;\">" + esc(paras[j]) + "</p>";
      }

      var prev = findLink(/上一[章页节]|上一章|上一页|上章/i);
      var next = findLink(/下一[章页节]|下一章|下一页|下章/i);
      var nav = "";
      if (prev || next) {
        nav = "<div style=\"display:flex;gap:12px;margin:1.4em 0;justify-content:space-between\">" +
          (prev ? "<button onclick=\"window.Android&&window.Android.gotoChapter('" + js(prev) + "')\" style=\"padding:10px 18px;font-size:1em\">上一章</button>" : "<span></span>") +
          "<button onclick=\"window.Android&&window.Android.showToc()\" style=\"padding:10px 18px;font-size:1em\">目录</button>" +
          (next ? "<button onclick=\"window.Android&&window.Android.gotoChapter('" + js(next) + "')\" style=\"padding:10px 18px;font-size:1em\">下一章</button>" : "<span></span>") +
          "</div>";
      }

      var style = "body{font-family:" + fam + ";color:" + theme.fg + ";background:" + theme.bg +
        ";margin:0;padding:0 " + padX + "px;font-size:" + fs + "px;line-height:" + lh +
        ";-webkit-text-size-adjust:100%;text-align:justify}button{font-size:1em;padding:10px 16px;margin:0 4px}" +
        "#pg{height:92vh;overflow-x:auto;overflow-y:hidden;column-width:calc(100vw - " + (padX * 2) + "px);column-gap:0;" +
        "scroll-snap-type:x mandatory;-webkit-column-fill:auto;-webkit-text-size-adjust:100%}" +
        "#pg p{margin:0 0 1.1em;line-height:" + lh + "}";

      var bodyHtml = flip
        ? "<div id=\"pg\">" + content + nav + "</div>"
        : content + nav;

      var doc = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>" + style + "</style></head><body>" + bodyHtml + "</body></html>";

      document.open();
      document.write(doc);
      document.close();

      // 5) 自动翻页（画布滚动/分页滑动）
      var auto = parseInt(cfg.autoPage, 10) || 0;
      if (auto > 0) {
        window.__AutoTimer = setInterval(function () {
          try {
            if (flip) { var w = document.getElementById("pg"); if (w) w.scrollLeft += w.clientWidth; }
            else window.scrollBy(0, window.innerHeight * 0.9);
          } catch (e) {}
        }, auto * 1000);
      }

      // 6) 无缝翻章：滚到底→下一章；顶部继续上滑→上一章
      try {
        var flipped2 = cfg.pageTurn === "flip";
        if (flipped2) {
          var pgEl = document.getElementById("pg");
          if (pgEl) {
            pgEl.addEventListener("scroll", function () {
              if (pgEl.scrollLeft + pgEl.clientWidth >= pgEl.scrollWidth - 30 && !window.__nxt) {
                window.__nxt = 1;
                window.Android && window.Android.nextChapter();
              }
            });
            var sx = 0;
            document.addEventListener("touchstart", function (e) { sx = e.touches[0].clientX; }, { passive: true });
            document.addEventListener("touchend", function (e) {
              if (pgEl.scrollLeft <= 2 && !window.__prv && e.changedTouches[0].clientX - sx > 90) {
                window.__prv = 1;
                window.Android && window.Android.prevChapter();
              }
            }, { passive: true });
          }
        } else {
          window.addEventListener("scroll", function () {
            if (window.innerHeight + window.scrollY >= document.body.scrollHeight - 60 && !window.__nxt) {
              window.__nxt = 1;
              window.Android && window.Android.nextChapter();
            }
          });
          var sy = 0;
          document.addEventListener("touchstart", function (e) { sy = e.touches[0].clientY; }, { passive: true });
          document.addEventListener("touchend", function (e) {
            if (window.scrollY <= 2 && !window.__prv && e.changedTouches[0].clientY - sy > 130) {
              window.__prv = 1;
              window.Android && window.Android.prevChapter();
            }
          }, { passive: true });
        }
      } catch (e) {}

      res.found = true;
      return res;
    } catch (e) {
      return res;
    }
  };

  // 章节标题清洗
  function cleanTitle(t) {
    t = String(t || "").split("_")[0].trim();
    if (t.length < 2 || t.length > 30) t = String(t || "").trim().slice(0, 24);
    return t;
  }

  // 格式适配 + 无用文本过滤（与 App 端 TextCleaner 一致）
  function cleanText(t) {
    t = t.replace(/&(?:nbsp|amp|lt|gt|quot|#\d+|#x[0-9a-fA-F]+);/g, function (m) {
      var e = m.slice(1, -1).toLowerCase();
      if (e === "nbsp") return " ";
      if (e === "amp") return "&";
      if (e === "lt") return "<";
      if (e === "gt") return ">";
      if (e === "quot") return '"';
      if (e.charAt(0) === "#") {
        try {
          var n = e.slice(1).toLowerCase().indexOf("x") === 0 ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
          return String.fromCharCode(n);
        } catch (x) { return ""; }
      }
      return m;
    });
    t = t.replace(/[\u200b\u200c\u200d\ufeff\u3000]/g, "");
    t = t.replace(/(?<=[\u4e00-\u9fff])\/(?=[\u4e00-\u9fff])/g, "");
    t = t.replace(/\s+/g, " ");
    return t.trim();
  }

  // 从 Readability 内容 HTML 还原段落（保留块结构）
  function parasFromHtml(html) {
    var tmp = document.createElement("div"); tmp.innerHTML = html || "";
    var paras = [], cur = "";
    function flush() { var t = cur.replace(/\s+/g, " ").trim(); if (t) paras.push(t); cur = ""; }
    function walk(n) {
      var cs = n.childNodes;
      for (var i = 0; i < cs.length; i++) {
        var c = cs[i];
        if (c.nodeType === 3) { cur += c.nodeValue; }
        else if (c.nodeType === 1) {
          var tg = c.tagName;
          if (tg === "BR") { cur += "\n"; }
          else if (/^(P|DIV|LI|TD|DD|SECTION|ARTICLE|H1|H2|H3|H4|H5|H6|BLOCKQUOTE|TR)$/.test(tg)) { walk(c); flush(); }
          else { walk(c); }
        }
      }
    }
    walk(tmp); flush();
    return paras;
  }

  function cleanPar(p) { return cleanText(p); }

  function findLink(re) {
    var as = document.querySelectorAll("a");
    for (var i = 0; i < as.length; i++) {
      var t = (as[i].textContent || "").replace(/\s+/g, "");
      if (re.test(t) && /^https?:/i.test(as[i].href || "")) return as[i].href;
    }
    return "";
  }

  function esc(s) { return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
  function js(s) { return String(s).replace(/\\/g, "\\\\").replace(/'/g, "\\'"); }
})();
