// 全局广告拦截：在“正常浏览”模式下也去掉广告/弹窗/遮罩/悬浮/注入块，保留正常网页布局。
// MainActivity 在每个页面加载完成后注入并常驻定时清扫。
(function () {
  "use strict";

  // 广告 class/id 关键词（正常浏览用适中力度，避免误删正文）
  var AD_RE = /(banner|advert|_ad\b|ad_|ad-|adbox|ggad|pc_gg|\bgg\b|appdown|app-download|tuiguang|tuijian|announce|notice|\btip\b|tips|recommend|popup|\bpop\b|float|fixed|download|qrcode|\bqr\b|scan|share|reward|coin|redpacket|mask|modal|dialog|alertbox|adtop|adbot|\byml\b|daoyin|tu\b)/i;

  // 明显是广告/弹窗的常见容器
  var EXTRA_SEL = [
    "div[id*=ad]", "div[class*=ad]", "div[id*=banner]", "div[class*=banner]",
    "span[id*=ad]", "span[class*=ad]", "p[id*=ad]", "p[class*=ad]",
    "iframe[src*=ad]", "iframe[src*=gg]", "a[id*=ad]", "a[class*=ad]",
    "img[src*=ad]", "ul[class*=ad]", "section[class*=ad]"
  ];

  function removeByClass(root) {
    if (!root) return;
    var all = root.querySelectorAll("*");
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      var cls = ((el.className || "") + " " + (el.id || "")).toLowerCase();
      if (AD_RE.test(cls)) { if (el.parentNode) el.parentNode.removeChild(el); }
    }
  }

  function removeFloating() {
    var all = document.querySelectorAll("*");
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      try {
        var st = el.style;
        if (st && (st.position === "fixed" || st.position === "absolute")) {
          var h = el.offsetHeight || 0, w = el.offsetWidth || 0;
          var zz = parseInt(st.zIndex || "0", 10);
          var overlay = h > 200 && zz > 10;               // 整屏遮罩/弹窗
          var smallFloat = h < 300 && w < 420;            // 小块悬浮广告
          if (smallFloat || overlay) { if (el.parentNode) el.parentNode.removeChild(el); }
        }
      } catch (e) {}
    }
  }

  function removeExtra() {
    EXTRA_SEL.forEach(function (s) {
      try {
        var ns = document.querySelectorAll(s);
        for (var i = ns.length - 1; i >= 0; i--) if (ns[i].parentNode) ns[i].parentNode.removeChild(ns[i]);
      } catch (e) {}
    });
  }

  function run() {
    try { removeFloating(); removeByClass(document.body); removeExtra(); } catch (e) {}
  }

  window.__ADBLOCK__ = function () {
    run();
    if (window.__abTimer) clearInterval(window.__abTimer);
    window.__abTimer = setInterval(run, 1500);
    return { ok: true };
  };
})();
