/* 결과 표 컬럼 폭 리사이저 — 2026-09-05 외부화
 *
 * merchants.html 1050~1104행. 3단계 두 번째 걸음이다.
 * 실측 결합: 바깥에서 쓰는 것 **2종**(el 1 · render 1), 바깥이 쓰는 것은
 * `COL_W` **읽기 5회**(render 의 colgroup 계산)와 `wireColResize` 1회.
 * `state`·`SNAP`·`refresh` 를 건드리지 않는다.
 *
 * ── 계약 ────────────────────────────────────────────────────────────────
 *   OnnuriColResize.attach({ el, onReset })
 *   OnnuriColResize.wire()      ← render 가 표를 다시 그린 뒤 부른다
 *   OnnuriColResize.widths()    ← render 가 colgroup 을 만들 때 읽는다(null 이면 auto)
 *
 * **`COL_W` 를 게터로 내보내는 이유.** 이 값은 드래그 중에 바뀌고 render 는 매번 최신을
 * 읽어야 한다. 값으로 넘기면 render 가 옛 배열을 붙잡는다 — pano·split 에서 지도 핸들을
 * 게터로 받은 것과 같은 이유다.
 *
 * **`onReset` 이 필요한 이유.** 더블클릭 초기화는 폭을 지운 뒤 표를 **다시 그려야** 한다.
 * 그 일을 하는 것은 merchants.html 의 `render` 이므로 콜백으로 받는다 —
 * 이 파일이 render 를 알면 결합이 되살아난다.
 */
(function () {
  "use strict";

  var el = null, onReset = null;

  // 렌더마다 테이블이 재생성되므로 폭 상태는 전역 COL_W(px 배열)+localStorage에 두고 매 렌더 재적용.
  var COL_W = (function () {
    try { var v = JSON.parse(localStorage.getItem("onnuri_col_w")); return Array.isArray(v) && v.length === 5 ? v : null; }
    catch (e) { return null; }
  })();
  var COL_MIN = 64;
  function wireColResize() {
    var table = el("resultArea").querySelector("table"); if (!table) return;
    Array.prototype.forEach.call(table.querySelectorAll(".col-grip"), function (grip) {
      grip.addEventListener("pointerdown", function (e) {
        e.preventDefault(); e.stopPropagation();
        // 첫 드래그 시 현재 렌더 폭을 스냅샷해 fixed로 전환
        if (!COL_W) COL_W = Array.prototype.map.call(table.querySelectorAll("thead th"), function (th) { return Math.round(th.getBoundingClientRect().width); });
        var idx = parseInt(grip.getAttribute("data-col"), 10);
        var startX = e.clientX, startW = COL_W[idx];
        try { grip.setPointerCapture(e.pointerId); } catch (err) {} // 합성/특수 포인터에서 캡처 불가여도 계속
        grip.classList.add("dragging"); document.body.classList.add("split-dragging");
        var cols = null;
        function apply() {
          if (!cols) { // colgroup이 없으면(첫 전환) 만들어 붙인다
            if (!table.querySelector("colgroup")) {
              var cg = document.createElement("colgroup");
              COL_W.forEach(function (w, i2) {
                var c = document.createElement("col");
                if (i2 < COL_W.length - 1) c.style.width = w + "px";   // 마지막 열은 잔여 흡수
                cg.appendChild(c);
              });
              table.insertBefore(cg, table.firstChild);
              table.style.tableLayout = "fixed";
            }
            cols = table.querySelectorAll("colgroup col");
          }
          if (idx < COL_W.length - 1) cols[idx].style.width = COL_W[idx] + "px";
          // 표 폭은 카드에 맞춰 100% 유지 — 열 합계가 넘치면 min-width로 가로 스크롤
          table.style.width = "100%";
          table.style.minWidth = Math.max(880, COL_W.reduce(function (a, b) { return a + b; }, 0)) + "px";
        }
        function onMove(ev) { COL_W[idx] = Math.max(COL_MIN, startW + Math.round(ev.clientX - startX)); apply(); }
        function onUp(ev) {
          try { grip.releasePointerCapture(ev.pointerId); } catch (err) {}
          grip.classList.remove("dragging"); document.body.classList.remove("split-dragging");
          document.removeEventListener("pointermove", onMove);
          document.removeEventListener("pointerup", onUp);
          localStorage.setItem("onnuri_col_w", JSON.stringify(COL_W));
        }
        // document에 바인딩 — 캡처 실패 환경에서도 그립 밖 이동을 추적
        document.addEventListener("pointermove", onMove);
        document.addEventListener("pointerup", onUp);
      });
      grip.addEventListener("dblclick", function () { // 더블클릭: 열 폭 초기화
        COL_W = null; localStorage.removeItem("onnuri_col_w"); if (onReset) onReset();
      });
      grip.addEventListener("click", function (e) { e.stopPropagation(); }); // 정렬 등 헤더 클릭과 분리
    });
  }

  window.OnnuriColResize = {
    attach: function (d) { el = d.el; onReset = d.onReset; },
    wire: function () { return wireColResize(); },
    widths: function () { return COL_W; },
  };
})();
