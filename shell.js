/* ============================================================
   shell.js — 3페이지 공통 셸 동작 (ADR-9)
   ① 모바일 드로어 내비  ② 화면 폭 토글(.sb-width)
   이벤트는 document 위임으로 바인딩한다 — index.html은 DC가 문서를
   마운트/재렌더하므로 요소 직접 바인딩은 리스너가 소실된다(위임은 생존).
   폭 변경 시 window 'pagewidthchange' 이벤트 발행(지도 resize 등이 청취).
   ============================================================ */
(function () {
  "use strict";
  var mq = function () { return window.matchMedia("(max-width:959px)").matches; };
  var $ = function (id) { return document.getElementById(id); };

  /* ── 드로어 내비 (위임) ── */
  function openNav() {
    var s = $("sidebar"), o = $("navOverlay"), t = $("navToggle");
    if (s) s.classList.add("open"); if (o) o.hidden = false; if (t) t.setAttribute("aria-expanded", "true");
  }
  function closeNav() {
    var s = $("sidebar"), o = $("navOverlay"), t = $("navToggle");
    if (s) s.classList.remove("open"); if (o) o.hidden = true; if (t) t.setAttribute("aria-expanded", "false");
  }
  document.addEventListener("click", function (e) {
    var t = e.target; if (!t || !t.closest) return;
    if (t.closest("#navToggle")) { e.preventDefault(); ($("sidebar") && $("sidebar").classList.contains("open")) ? closeNav() : openNav(); return; }
    if (t.closest("#navOverlay")) { closeNav(); return; }
    if (t.closest(".sidebar a") && mq()) closeNav();
  });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeNav(); });
  window.addEventListener("resize", function () { if (!mq()) closeNav(); });

  /* ── 화면 폭 토글 (위임) ── */
  var PW_KEY = "onnuri_pw";
  function applyPw(v) {
    if (v) document.documentElement.setAttribute("data-pw", v);
    else document.documentElement.removeAttribute("data-pw");
    Array.prototype.forEach.call(document.querySelectorAll(".sb-width button"), function (b) {
      b.classList.toggle("active", (b.getAttribute("data-pw") || "") === (v || ""));
    });
    try { window.dispatchEvent(new Event("pagewidthchange")); } catch (e) {}
  }
  document.addEventListener("click", function (e) {
    var b = e.target && e.target.closest ? e.target.closest(".sb-width button") : null;
    if (!b) return;
    var v = b.getAttribute("data-pw") || "";
    if (v) localStorage.setItem(PW_KEY, v); else localStorage.removeItem(PW_KEY);
    applyPw(v);
  });
  // 초기 적용: data-pw는 즉시(루트 속성이라 마운트 무관), 버튼 active는 로드/마운트 후 재시도
  applyPw(localStorage.getItem(PW_KEY) || "");
  var tries = 10;
  var t = setInterval(function () {
    if (document.querySelector(".sb-width button") || --tries <= 0) {
      applyPw(localStorage.getItem(PW_KEY) || "");
      clearInterval(t);
    }
  }, 300);
})();

/* ── 모바일 PC 최적화 안내 (2026-08-11) ──
   지도·표·스플리터 등은 넓은 화면 기준 설계라, 모바일 첫 방문 시 한 번만 알린다.
   닫으면 localStorage(onnuri_pc_notice)로 전 페이지에서 재노출하지 않는다. */
(function () {
  "use strict";
  var KEY = "onnuri_pc_notice";
  function boot() {
    try {
      if (localStorage.getItem(KEY)) return;
      if (!window.matchMedia("(max-width:959px)").matches) return;
      var d = document.createElement("div");
      d.className = "pc-notice show";
      d.setAttribute("role", "status");
      d.innerHTML = '이 가이드는 <b>PC 화면에 최적화</b>되어 있습니다 — 모바일에서도 이용은 가능하지만, 지도·표 검색은 PC에서 더 편합니다.'
        + '<button type="button" class="pcn-close" aria-label="안내 닫기">×</button>';
      d.querySelector(".pcn-close").addEventListener("click", function () {
        d.remove();
        try { localStorage.setItem(KEY, "1"); } catch (e) {}
      });
      document.body.appendChild(d);
    } catch (e) {}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
