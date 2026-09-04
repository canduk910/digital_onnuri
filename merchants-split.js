/* 리스트↔지도 드래그 스플리터 (PC 전용) — 2026-09-05 외부화
 *
 * merchants.html 223~321행의 스플리터 구획을 옮긴 것이다. 옮긴 이유는 크기가 아니라
 * **결합이 가장 얕아서**다 — 실측: 바깥에서 쓰는 것 3종 4회(el 2 · mapObj 1 · mapReady 1),
 * 바깥이 쓰는 것 2곳(initSplit · initVSplit). `state`·`SNAP`·`refresh` 허브를
 * **한 번도 건드리지 않는다.** 허브가 얽힌 구획(브랜드 팝업 state 16회, 데이터 소스 26회)을
 * 손대기 전에, pano 로 한 번 통한 주입 계약이 두 번째로도 통하는지 state 위험 없이 확인한다.
 *
 * ── 계약 ────────────────────────────────────────────────────────────────
 *   OnnuriSplit.attach({ el, getMap, isMapReady })
 *   OnnuriSplit.init()   ← boot 이 `initSplit(); initVSplit();` 대신 부른다
 *
 * `mapObj`·`mapReady` 는 initMap 이 **나중에** 채우는 값이라 값이 아니라 게터로 받는다 —
 * 로드 시점에 붙잡으면 영영 null 이고 **에러 없이 아무 일도 안 일어난다**(pano 와 같은 이유).
 *
 * ── 순수 이동이 아니다 ────────────────────────────────────────────────────
 * `pagewidthchange` 청취자를 **모듈 최상위에서 init() 안으로 옮겼다.** 원래는 스크립트가
 * 평가되는 순간 등록됐는데, 외부 파일이 되면 그 시점이 attach 보다 앞선다 — 그때 폭 토글이
 * 발화하면 `isMapReady` 가 아직 null 이라 TypeError 가 난다. init() 안에서 등록하면
 * attach 이후가 보장된다. 그 대신 **init 을 부르기 전에는 폭 토글에 반응하지 않는다**
 * (boot 이 곧바로 부르므로 실사용 차이는 없다).
 */
(function () {
  "use strict";

  // 주입 대상. attach 전에는 아무 함수도 부르면 안 된다.
  var el = null, getMap = null, isMapReady = null;

  // ---- 리스트↔지도 드래그 스플리터 (PC 전용) ----
  // --map-w(px)를 갱신해 지도 폭 조절. localStorage 저장, 더블클릭 초기화, ←/→ 키 지원.
  var SPLIT_KEY = "onnuri_map_w", SPLIT_MIN = 300;
  function applyMapW(px) { document.documentElement.style.setProperty("--map-w", px + "px"); }
  function clearMapW() { document.documentElement.style.removeProperty("--map-w"); }
  function notifyMapResize() { // 컨테이너 크기 변경을 네이버 지도에 반영
    if (isMapReady() && window.naver && naver.maps) naver.maps.Event.trigger(getMap(), "resize");
  }
  function splitMaxW(split) { return Math.max(SPLIT_MIN, Math.round(split.getBoundingClientRect().width * 0.65)); }

  // 수직 높이 조절(2026-08-12): --panel-h 갱신 — 지도·리스트·좌우핸들이 함께 줄어
  // 필터+지도+리스트가 한 화면에 들어오게 한다. 저장·더블클릭 초기화·↑/↓ 키.
  var VSPLIT_KEY = "onnuri_panel_h", VSPLIT_MIN = 260;
  function applyPanelH(px) { document.documentElement.style.setProperty("--panel-h", px + "px"); }
  function clearPanelH() { document.documentElement.style.removeProperty("--panel-h"); }
  function vsplitMax() { return Math.max(VSPLIT_MIN, window.innerHeight - 160); }
  function initVSplit() {
    var handle = el("vsplitHandle"); if (!handle) return;
    var saved = parseInt(localStorage.getItem(VSPLIT_KEY), 10);
    if (saved && saved >= VSPLIT_MIN) applyPanelH(Math.min(saved, vsplitMax()));
    var raf = null, curH = null;
    function rowTop() {
      var row = document.querySelector(".result-split");
      return row ? row.getBoundingClientRect().top : 0;
    }
    function setH(px) {
      curH = Math.max(VSPLIT_MIN, Math.min(px, vsplitMax()));
      if (raf) return;
      raf = requestAnimationFrame(function () { raf = null; applyPanelH(curH); notifyMapResize(); });
    }
    handle.addEventListener("pointerdown", function (e) {
      e.preventDefault();
      try { handle.setPointerCapture(e.pointerId); } catch (err) {}
      handle.classList.add("dragging"); document.body.classList.add("split-dragging");
      function onMove(ev) { setH(ev.clientY - rowTop() - 10); }   // 10 = 핸들 여백 보정
      function onUp(ev) {
        try { handle.releasePointerCapture(ev.pointerId); } catch (err) {}
        handle.classList.remove("dragging"); document.body.classList.remove("split-dragging");
        document.removeEventListener("pointermove", onMove);
        document.removeEventListener("pointerup", onUp);
        if (curH) localStorage.setItem(VSPLIT_KEY, String(Math.round(curH)));
        notifyMapResize();
      }
      document.addEventListener("pointermove", onMove);
      document.addEventListener("pointerup", onUp);
    });
    handle.addEventListener("dblclick", function () {
      clearPanelH(); localStorage.removeItem(VSPLIT_KEY); curH = null; notifyMapResize();
    });
    handle.addEventListener("keydown", function (e) {   // 접근성: ↑/↓로 20px 조절
      if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
      e.preventDefault();
      var row = document.querySelector(".result-split");
      var cur = row ? row.getBoundingClientRect().height : 600;
      setH(cur + (e.key === "ArrowDown" ? 20 : -20));
      if (curH) localStorage.setItem(VSPLIT_KEY, String(Math.round(curH)));
    });
  }

  function initSplit() {
    var handle = el("splitHandle"); if (!handle) return;
    var split = handle.parentElement;
    var saved = parseInt(localStorage.getItem(SPLIT_KEY), 10);
    if (saved && saved >= SPLIT_MIN) applyMapW(Math.min(saved, splitMaxW(split)));
    var raf = null, curW = null;
    function setW(px) {
      curW = Math.max(SPLIT_MIN, Math.min(px, splitMaxW(split)));
      if (raf) return;
      raf = requestAnimationFrame(function () { raf = null; applyMapW(curW); notifyMapResize(); });
    }
    handle.addEventListener("pointerdown", function (e) {
      e.preventDefault();
      try { handle.setPointerCapture(e.pointerId); } catch (err) {}   // 합성 포인터에서도 계속
      handle.classList.add("dragging"); document.body.classList.add("split-dragging");
      function onMove(ev) { setW(ev.clientX - split.getBoundingClientRect().left - 8); } // 지도가 좌측 — 좌변 기준(8=핸들 절반+갭)
      function onUp(ev) {
        try { handle.releasePointerCapture(ev.pointerId); } catch (err) {}
        handle.classList.remove("dragging"); document.body.classList.remove("split-dragging");
        document.removeEventListener("pointermove", onMove);
        document.removeEventListener("pointerup", onUp);
        if (curW) localStorage.setItem(SPLIT_KEY, String(curW));
        notifyMapResize();
      }
      document.addEventListener("pointermove", onMove);   // 캡처 실패 환경에서도 추적
      document.addEventListener("pointerup", onUp);
    });
    handle.addEventListener("dblclick", function () { // 기본 폭으로 초기화
      clearMapW(); localStorage.removeItem(SPLIT_KEY); curW = null; notifyMapResize();
    });
    handle.addEventListener("keydown", function (e) { // 접근성: 화살표로 20px 조절
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      e.preventDefault();
      var w = curW || parseInt(getComputedStyle(document.querySelector(".result-map")).width, 10) || 420;
      setW(w + (e.key === "ArrowRight" ? 20 : -20)); // →: 지도 넓게, ←: 리스트 넓게 (지도가 좌측)
      localStorage.setItem(SPLIT_KEY, String(curW));
    });
  }

  window.OnnuriSplit = {
    attach: function (d) { el = d.el; getMap = d.getMap; isMapReady = d.isMapReady; },
    init: function () {
      initSplit();
      initVSplit();
      // 청취자는 여기서 등록한다 — 모듈 평가 시점에 걸면 attach 전에 발화할 수 있다.
      window.addEventListener("pagewidthchange", function () { setTimeout(notifyMapResize, 60); });
    },
  };
})();
