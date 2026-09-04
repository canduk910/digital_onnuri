/* 브랜드 검색 팝업 — merchants.html 에서 분리 (2026-09-05)
   ────────────────────────────────────────────────────────────────────────
   2026-08-09 에 콤보에서 팝업으로 바꾼 것이다(브랜드가 178종이라 콤보로는 못 찾는다).
   부분검색 + 초성/알파벳 색인 + 다중 토글.

   **경계** — 이 118줄이 허브(state·SNAP·refresh)를 쓰는 자리는 `pickBrand` 하나뿐이라
   그것을 통째로 `onPick(key, modalCat)` 콜백으로 넘겼다. 나머지는 읽기뿐이라 게터로
   받는다. 브랜드 목록 조회도 바깥 몫이다 — API 모드냐 JSON 폴백이냐는 이 팝업의
   관심사가 아니고, 두 경로가 갈라지면 안 되는 자리이기도 하다.

   계약:
     OnnuriBrandModal.attach({ el, esc, fmt, catOrder, fetchBrands,
                               getCat, getBrand, getScopeLabel, onPick })
     OnnuriBrandModal.wire()            // 닫기·배경·업종 콤보·검색 입력 배선
     OnnuriBrandModal.open() / .close() / .isOpen()
   getCat·getBrand·getScopeLabel 은 **게터**다 — 필터가 바뀌면 값이 달라진다.
   onPick(key, modalCat): modalCat 은 팝업 업종 콤보가 활성일 때만 값, 아니면 null
   (바깥이 "업종은 건드리지 말라"를 구분할 수 있게). */
(function () {
  "use strict";
  var el = null, esc = null, fmt = null, catOrder = null, fetchBrands = null;
  var getCat = null, getBrand = null, getScopeLabel = null, onPick = null;

  var CHO = ["ㄱ","ㄴ","ㄷ","ㄹ","ㅁ","ㅂ","ㅅ","ㅇ","ㅈ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"];
  var CHO_ALL = ["ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ","ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"];
  var CHO_BASE = { "ㄲ":"ㄱ","ㄸ":"ㄷ","ㅃ":"ㅂ","ㅆ":"ㅅ","ㅉ":"ㅈ" };
  var INDEX_KEYS = CHO.concat("ABCDEFGHIJKLMNOPQRSTUVWXYZ".split("")).concat(["#"]);
  function initialOf(name) {
    var ch = (name || "").charAt(0), code = ch.charCodeAt(0);
    if (code >= 0xAC00 && code <= 0xD7A3) { var c = CHO_ALL[Math.floor((code - 0xAC00) / 588)]; return CHO_BASE[c] || c; }
    if (/[a-zA-Z]/.test(ch)) return ch.toUpperCase();
    return "#";
  }
  // 팝업용 브랜드 목록 — **전 지역(서울·인천·경기·부산) 기준**. 지역·지도범위·디지털 필터와 무관하게
  // 전체 리스트에서 집계한다. 선택한 브랜드가 현재 지역에 없으면 결과가 0곳으로 표시되는 것이 정상 동작.
  /* 브랜드 목록은 **바깥에서** 받아 온다. API 모드냐 JSON 폴백이냐는 이 팝업의
     관심사가 아니고, 두 경로의 규칙이 갈라지면 안 되는 자리이기도 하다. */

  var bmState = { cat: "전체", list: [], q: "" };
  function openBrandModal() {
    bmState.cat = getCat();
    bmState.q = "";
    var qEl = el("bmQ"); if (qEl) qEl.value = "";
    // 업종 콤보: 현재 업종 반영. 이미 업종이 선택돼 있으면 비활성(변경 제한).
    var sel = el("bmCat");
    var oh = '<option value="전체">업종 전체</option>';
    catOrder().forEach(function (pair) { oh += '<option value="' + esc(pair[0]) + '"' + (getCat() === pair[0] ? " selected" : "") + ">" + esc(pair[1]) + "</option>"; });
    sel.innerHTML = oh;
    sel.value = getCat();
    sel.disabled = (getCat() !== "전체");
    // 안내 문구에 **지금 좁혀질 범위**를 그대로 적는다(2026-09-03 사용자 지적). 종전 문구는
    // "현재 선택한 지역·지도 범위"를 언급해 팝업 숫자에 그 범위가 반영되는 것처럼 읽혔다 —
    // 숫자는 전 지역 합산이고 범위는 고른 뒤 목록에만 걸린다. 둘의 관계를 문장이 말해야 한다.
    var hint = el("bmScopeHint");
    if (hint) {
      var scope = getScopeLabel();
      hint.textContent = "숫자는 서울·인천·경기·부산 전체 매장 수입니다. 브랜드를 고르면 " + scope
        + "의 목록으로 좁혀지고, 그 범위에 없는 브랜드는 0곳이 됩니다.";
    }
    el("brandModal").hidden = false;
    document.body.style.overflow = "hidden";
    loadBrandModalList();
    if (qEl) qEl.focus();
  }
  function closeBrandModal() { el("brandModal").hidden = true; document.body.style.overflow = ""; }
  function loadBrandModalList() {
    el("bmList").innerHTML = '<div class="bm-empty">불러오는 중…</div>';
    fetchBrands(bmState.cat).then(function (list) {
      bmState.list = list.slice().sort(function (a, b) { return a.key.localeCompare(b.key, "ko"); });
      renderBrandModal();
    }).catch(function () { el("bmList").innerHTML = '<div class="bm-empty">브랜드 목록을 불러오지 못했습니다.</div>'; });
  }
  function renderBrandModal() {
    var q = bmState.q.trim().toLowerCase();
    var rows = q ? bmState.list.filter(function (b) { return b.key.toLowerCase().indexOf(q) !== -1; }) : bmState.list;
    // 색인: 결과에 존재하는 초성만 활성
    var present = {}; rows.forEach(function (b) { present[initialOf(b.key)] = true; });
    var idx = el("bmIndex"); idx.innerHTML = "";
    INDEX_KEYS.forEach(function (k) {
      var li = document.createElement("li");
      li.textContent = k;
      if (present[k]) { li.onclick = function () { jumpToInitial(k); }; }
      else li.className = "disabled";
      idx.appendChild(li);
    });
    // 리스트
    var listEl = el("bmList");
    if (!rows.length) { listEl.innerHTML = '<div class="bm-empty">' + (q ? '"' + esc(bmState.q) + '"에 해당하는 브랜드가 없습니다.' : "브랜드가 없습니다.") + "</div>"; return; }
    listEl.innerHTML = "";
    // 전체(해제)
    var all = document.createElement("button");
    all.className = "bm-item" + (getBrand() === "전체" ? " active" : "");
    all.innerHTML = '<span>브랜드 전체(해제)</span>';
    all.onclick = function () { pickBrand("전체"); };
    listEl.appendChild(all);
    var lastInit = null;
    rows.forEach(function (b) {
      var ini = initialOf(b.key);
      if (ini !== lastInit) { var hd = document.createElement("div"); hd.className = "bm-head"; hd.textContent = ini; hd.setAttribute("data-init", ini); listEl.appendChild(hd); lastInit = ini; }
      var it = document.createElement("button");
      it.className = "bm-item" + (getBrand() === b.key ? " active" : "");
      it.innerHTML = '<span>' + esc(b.key) + '</span><span class="cnt">' + fmt(b.count) + "</span>";
      it.onclick = function () { pickBrand(b.key); };
      listEl.appendChild(it);
    });
  }
  function jumpToInitial(k) {
    var list = el("bmList");
    var hd = list.querySelector('.bm-head[data-init="' + k + '"]');
    if (!hd) return;
    // 함정: .bm-head는 sticky(top:0)라 offsetTop·getBoundingClientRect 모두 '고정된 렌더 위치'를
    // 반환해(스크롤에 따라 값이 변함) 위로 올라가는 방향에서 어긋난다. 헤더 바로 다음 아이템은
    // non-sticky라 offsetTop이 정확하므로, 그 값에서 헤더 높이를 빼 헤더 상단으로 이동한다.
    var item = hd.nextElementSibling;
    var top = item ? Math.max(0, item.offsetTop - hd.offsetHeight) : hd.offsetTop;
    list.scrollTo({ top: top, behavior: "smooth" });
  }
  /* 고르기 — **허브를 쓰는 유일한 자리라 통째로 바깥에 넘겼다.**
     브랜드 토글·업종 동반 적용·페이지 되돌림·재조회는 전부 필터 상태의 일이다.
     팝업에서 업종을 (활성 상태로) 골랐다면 그 값을 함께 넘긴다 — 콤보가 비활성이면
     null 을 줘서 바깥이 "업종은 건드리지 말라"를 알 수 있게. */
  function pickBrand(key) {
    var modalCat = el("bmCat").disabled ? null : bmState.cat;
    closeBrandModal();
    if (onPick) onPick(key, modalCat);
  }

  window.OnnuriBrandModal = {
    attach: function (d) {
      el = d.el; esc = d.esc; fmt = d.fmt; catOrder = d.catOrder; fetchBrands = d.fetchBrands;
      getCat = d.getCat; getBrand = d.getBrand; getScopeLabel = d.getScopeLabel; onPick = d.onPick;
    },
    /* 배선은 여기서 한다 — 종전에는 bindControls 가 bmState 를 직접 만졌다
       (`bmState.cat = e.target.value`). 내부 상태를 바깥이 쓰면 경계가 이름뿐이 된다. */
    wire: function () {
      el("bmClose").addEventListener("click", closeBrandModal);
      el("brandModal").addEventListener("click", function (e) {
        if (e.target.getAttribute("data-close")) closeBrandModal();
      });
      el("bmCat").addEventListener("change", function (e) {
        bmState.cat = e.target.value; loadBrandModalList();
      });
      var t = null;
      el("bmQ").addEventListener("input", function (e) {
        bmState.q = e.target.value; clearTimeout(t);
        t = setTimeout(renderBrandModal, 120);
      });
    },
    open: function () { return openBrandModal(); },
    close: function () { return closeBrandModal(); },
    isOpen: function () { return !el("brandModal").hidden; },
  };
})();
