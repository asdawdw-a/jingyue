// 来源校验：从当前页提取候选结果链接 + 注入“已校验可用源”置顶面板
(function () {
  "use strict";

  // 提取当前搜索结果页里的候选外部链接（跳过搜索引擎自身与导航）
  window.__EXTRACT_RESULTS__ = function () {
    var out = [], seen = {};
    var as = document.querySelectorAll("a[href]");
    var self = location.hostname;
    for (var i = 0; i < as.length; i++) {
      var a = as[i], h = a.href || "", t = (a.textContent || "").replace(/\s+/g, "").trim();
      if (!/^https?:/i.test(h)) continue;
      try {
        var host = new URL(h).hostname;
        if (host === self) continue;
        if (/(^|\.)(bing|baidu|sogou|so\.com|google|sm|360\.cn|quark)\.[a-z]+$/i.test(host)) continue;
        if (/(^|\.)(bing|baidu|sogou|so\.com|sm|quark)\.[a-z]+$/i.test(host)) continue;
      } catch (e) { continue; }
      if (seen[h]) continue;
      seen[h] = 1;
      out.push({ url: h, title: t });
      if (out.length >= 60) break;
    }
    return out;
  };

  // 在页面顶部注入“已校验的可用书源”面板
  window.__INJECT_SOURCES__ = function (html) {
    try {
      var old = document.getElementById("__validatePanel__");
      if (old && old.parentNode) old.parentNode.removeChild(old);
      var panel = document.createElement("div");
      panel.id = "__validatePanel__";
      panel.style.cssText = "position:relative;z-index:9999;background:#fff;border:1px solid #eee;border-radius:10px;margin:8px;padding:8px;max-height:260px;overflow:auto;font-size:14px;box-shadow:0 1px 4px rgba(0,0,0,.06)";
      panel.innerHTML = '<div style="font-weight:700;margin-bottom:6px;color:#1a73e8">✓ 已校验的可读书源（点开即读）</div>' + (html || '<div style="color:#888;padding:4px 0">未发现可用来源</div>');
      document.body.insertBefore(panel, document.body.firstChild);
      return true;
    } catch (e) { return false; }
  };
})();
