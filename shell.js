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

  /* ── 화면 폭 슬라이더 (2026-08-12) ──
     3단 토글(좁게/표준/넓게) → 연속 슬라이더. 범위 760px ~ 사이드바 제외 가용 폭(최대=100%).
     저장: onnuri_pw_px(px). 구 키 onnuri_pw(narrow/wide)는 최초 1회 픽셀로 이전.
     UI는 여기서 .sb-width 안에 주입 — 6개 페이지 마크업 공통(index DC 마운트 대비 재시도). */
  var PW_PX_KEY = "onnuri_pw_px", PW_MIN = 760, PW_DEFAULT = 1080;
  function pwAvail() {
    // 사이드바 최소화(data-sb-min) 상태면 그 폭도 가용 폭에 포함(2026-08-13)
    var sb = window.matchMedia("(min-width:960px)").matches
      && !document.documentElement.hasAttribute("data-sb-min") ? 248 : 0;
    return Math.max(PW_MIN + 40, window.innerWidth - sb - 64);
  }
  function applyPw(px) {
    var avail = pwAvail();
    var v = Math.min(Math.max(px, PW_MIN), avail);
    // 최대치면 100%로 — 창을 더 키워도 항상 가득 차게
    document.documentElement.style.setProperty("--page-w", v >= avail - 10 ? "100%" : v + "px");
    var out = document.querySelector(".sb-width-val");
    if (out) out.textContent = v >= avail - 10 ? "최대" : v + "px";
    var r = document.querySelector("#pwRange");
    if (r && Math.abs(parseInt(r.value, 10) - v) > 4) r.value = v;
    try { window.dispatchEvent(new Event("pagewidthchange")); } catch (e) {}
  }
  function savedPw() {
    var px = parseInt(localStorage.getItem(PW_PX_KEY), 10);
    if (px) return px;
    var legacy = localStorage.getItem("onnuri_pw");   // 구 3단 값 이전
    if (legacy === "narrow") return 880;
    if (legacy === "wide") return 1640;
    return PW_DEFAULT;
  }
  function mountSlider() {
    var box = document.querySelector(".sidebar .sb-width");
    if (!box || box.querySelector("#pwRange")) return !!box;
    var avail = pwAvail(), cur = Math.min(savedPw(), avail);
    box.innerHTML = '<span class="sb-width-label">화면 폭</span>'
      + '<input type="range" id="pwRange" min="' + PW_MIN + '" max="' + Math.round(avail) + '" step="20" value="' + Math.round(cur) + '"'
      + ' aria-label="본문 폭 조절" title="드래그로 본문 폭 조절 · 더블클릭 초기화">'
      + '<span class="sb-width-val"></span>';
    var r = box.querySelector("#pwRange");
    r.addEventListener("input", function () {
      var v = parseInt(r.value, 10);
      localStorage.setItem(PW_PX_KEY, String(v));
      applyPw(v);
    });
    r.addEventListener("dblclick", function () {   // 초기화 = 표준 1080
      localStorage.removeItem(PW_PX_KEY); localStorage.removeItem("onnuri_pw");
      r.value = PW_DEFAULT; applyPw(PW_DEFAULT);
    });
    applyPw(cur);
    return true;
  }
  window.addEventListener("resize", function () {   // 창 크기 변화 → 가용 폭 갱신
    var r = document.querySelector("#pwRange");
    if (r) { r.max = Math.round(pwAvail()); applyPw(parseInt(r.value, 10)); }
  });
  applyPw(savedPw());          // 슬라이더 마운트 전에도 폭은 즉시 적용(FOUC 방지)
  var pwTries = 12;
  var pwTimer = setInterval(function () {
    if (mountSlider() || --pwTries <= 0) clearInterval(pwTimer);
  }, 300);
})();

/* ── PC 사이드바 최소화 (2026-08-13) ──
   접기(사이드바 상단 ◀)·펼침(좌측 고정 오렌지 탭 ❯) 버튼을 주입하고 html[data-sb-min]을 토글한다.
   상태는 localStorage(onnuri_sb_min). 토글 시 화면 폭 슬라이더 가용치 재계산 + pagewidthchange 발행
   (지도 resize 등이 청취). 클릭은 document 위임 — index DC 재렌더에도 생존. */
(function () {
  "use strict";
  var KEY = "onnuri_sb_min";
  function setMin(min) {
    if (min) document.documentElement.setAttribute("data-sb-min", "1");
    else document.documentElement.removeAttribute("data-sb-min");
    try { min ? localStorage.setItem(KEY, "1") : localStorage.removeItem(KEY); } catch (e) {}
    // 화면 폭 슬라이더 가용치 재계산 — resize 핸들러(위 IIFE)가 pwAvail·applyPw를 다시 돌게 한다
    try { window.dispatchEvent(new Event("resize")); } catch (e) {}
    try { window.dispatchEvent(new Event("pagewidthchange")); } catch (e) {}
  }
  try { if (localStorage.getItem(KEY)) document.documentElement.setAttribute("data-sb-min", "1"); } catch (e) {}
  function mount() {
    var sb = document.querySelector(".sidebar");
    if (sb && !sb.querySelector(".sb-collapse")) {
      var c = document.createElement("button");
      c.type = "button"; c.className = "sb-collapse"; c.title = "사이드바 접기";
      c.setAttribute("aria-label", "사이드바 접기"); c.textContent = "◀";
      sb.appendChild(c);
    }
    if (!document.querySelector(".sb-expand")) {
      var x = document.createElement("button");
      x.type = "button"; x.className = "sb-expand"; x.title = "사이드바 펼치기";
      x.setAttribute("aria-label", "사이드바 펼치기"); x.textContent = "❯";
      document.body.appendChild(x);
    }
    return !!sb;
  }
  document.addEventListener("click", function (e) {
    var t = e.target; if (!t || !t.closest) return;
    if (t.closest(".sb-collapse")) { setMin(true); return; }
    if (t.closest(".sb-expand")) { setMin(false); return; }
  });
  var tries = 12;
  var timer = setInterval(function () { if (mount() || --tries <= 0) clearInterval(timer); }, 300);
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount);
  else mount();
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

/* ── 방문자 카운트 (2026-08-11) ──
   브라우저 세션당 1회 POST /api/visit(개인정보 미저장 — 일자 카운트만), 이후엔 GET.
   API 미응답 시 표기 자체를 생략(가이드 기능에 무영향). 계약: VisitContractTest(today/total). */
(function () {
  "use strict";
  var API = (function () {
    var c = window.ONNURI_CONFIG || {};
    if (c.apiBase) return c.apiBase.replace(/\/$/, "");
    var h = location.hostname;
    if (h === "localhost" || h === "127.0.0.1") return "http://localhost:8080/api";
    return "https://api.koscomlabor.cloud/api";
  })();
  function boot() {
    var first = !sessionStorage.getItem("onnuri_visited");
    fetch(API + "/visit", first ? { method: "POST" } : undefined)
      .then(function (r) { if (!r.ok) throw 0; return r.json(); })
      .then(function (d) {
        try { sessionStorage.setItem("onnuri_visited", "1"); } catch (e) {}
        var sb = document.querySelector(".sidebar .sb-width");
        if (!sb) return;
        var el = document.createElement("div");
        el.className = "sb-visits";
        var nf = function (n) { return Number(n).toLocaleString("ko-KR"); };
        el.innerHTML = "방문 오늘 <b>" + nf(d.today) + "</b> · 누적 <b>" + nf(d.total) + "</b>";
        sb.parentNode.insertBefore(el, sb);
      })
      .catch(function () {});
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else setTimeout(boot, 0);
})();
