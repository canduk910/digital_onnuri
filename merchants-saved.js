/* 즐겨찾기·최근 본·위치 공유 — merchants.html 에서 분리 (2026-09-05)
   ────────────────────────────────────────────────────────────────────────
   2026-08-13 에 들어온 기능이다. 이 브라우저에만 저장한다(localStorage — 서버로
   보내지 않고 개인정보를 수집하지 않는다). 항목은 스냅샷
   ({id,name,cat,addr,market,lat,lng,card,qr,region})이라 필터·페이지가 바뀌어도
   그대로 열람된다.

   **왜 떼어낼 수 있었나** — 이 76줄이 바깥에서 쓰는 것은 다섯 가지뿐이고
   (el·esc·현재 시도·지도 이동·표 다시 그리기), 그중 지도 관련 둘은 콜백으로
   밀어냈다. 허브(state·SNAP·refresh)를 직접 읽지도 쓰지도 않는다.

   계약:
     OnnuriSaved.attach({ el, esc, getRegion, onOpenSpot, onChange })
     OnnuriSaved.isFav(r) / .toggleFav(r) / .recordRecent(r)
     OnnuriSaved.updateCount() / .openModal() / .closeModal()
   getRegion 은 **게터**다 — 시도를 바꾸면 값이 달라지므로 붙잡아 두면 안 된다.
   onOpenSpot(s) 는 지도를 그 좌표로 옮기고 상세 팝업을 여는 일을 맡는다. */
(function () {
  "use strict";
  var el = null, esc = null, getRegion = null, onOpenSpot = null, onChange = null;
  var FAV_KEY = "onnuri_favs", RECENT_KEY = "onnuri_recent";

  function loadList(key) { try { var v = JSON.parse(localStorage.getItem(key)); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
  function saveList(key, list) { try { localStorage.setItem(key, JSON.stringify(list)); } catch (e) {} }
  function snapOf(r) {
    return { id: r.id || null, name: r.name || "", cat: r.cat || "", addr: r.addr || "", market: r.market || "",
             lat: r.lat || null, lng: r.lng || null, card: r.card || "", qr: r.qr || "", region: getRegion() };
  }
  function snapKey(s) { return s.id || (s.name + "@" + s.lat + "," + s.lng); }
  function isFav(r) {
    var k = snapKey(snapOf(r));
    return loadList(FAV_KEY).some(function (s) { return snapKey(s) === k; });
  }
  function toggleFav(r) {
    var favs = loadList(FAV_KEY), k = snapKey(snapOf(r));
    var i = favs.findIndex(function (s) { return snapKey(s) === k; });
    if (i === -1) favs.unshift(snapOf(r)); else favs.splice(i, 1);
    saveList(FAV_KEY, favs.slice(0, 100));
    updateSvCount();
    return i === -1;
  }
  function recordRecent(r) {
    if (!r || !r.name) return;
    var list = loadList(RECENT_KEY), s = snapOf(r), k = snapKey(s);
    list = list.filter(function (x) { return snapKey(x) !== k; });
    list.unshift(s);
    saveList(RECENT_KEY, list.slice(0, 10));
  }
  function updateSvCount() { var b = el("svCount"); if (b) b.textContent = loadList(FAV_KEY).length; }
  function spotShareUrl(s) {
    return location.origin + location.pathname + "?region=" + encodeURIComponent(s.region || getRegion())
      + "&spot=" + s.lat + "," + s.lng + "," + encodeURIComponent(s.name);
  }
  function copyShare(s, btn) {
    var url = spotShareUrl(s);
    var done = function () { if (btn) { var t = btn.textContent; btn.textContent = "복사됨"; setTimeout(function () { btn.textContent = t; }, 1200); } };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done, function () { prompt("링크를 복사하세요", url); });
    else prompt("링크를 복사하세요", url);
  }
  function goSpot(s) {
    // 지도 이동·팝업은 **바깥에 남겼다.** 원래 여기서 팝업 보호 플래그를 세우고
    // 상세 팝업을 열었는데 둘 다 지도 쪽 상태다. 모듈이 지도를 알기 시작하면 다음
    // 사람이 "여기서 마커도 그리자"가 되어 경계가 무너진다.
    closeSvModal();
    if (onOpenSpot) onOpenSpot(s);
  }
  function renderSvModal() {
    var favs = loadList(FAV_KEY), recent = loadList(RECENT_KEY);
    var listEl = el("svList"); listEl.innerHTML = "";
    function section(title, items, fav) {
      if (!items.length) return;
      var hd = document.createElement("div"); hd.className = "sv-sec"; hd.textContent = title; listEl.appendChild(hd);
      items.forEach(function (s) {
        var d = document.createElement("div"); d.className = "sv-item";
        d.innerHTML = '<div class="sv-main"><div class="sv-name">' + esc(s.name) + '</div>'
          + '<div class="sv-addr">' + esc(s.addr || "") + (s.region ? " · " + esc(s.region) : "") + '</div></div>'
          + '<div class="sv-act">'
          + (fav ? '<button type="button" data-act="unfav" title="즐겨찾기 해제">★</button>'
                 : '<button type="button" data-act="fav" title="즐겨찾기 추가">☆</button>')
          + '<button type="button" data-act="share" title="위치 공유 링크 복사">링크</button></div>';
        d.querySelector(".sv-main").addEventListener("click", function () { goSpot(s); });
        d.querySelector('[data-act="share"]').addEventListener("click", function (e) { copyShare(s, e.target); });
        var fb = d.querySelector('[data-act="unfav"], [data-act="fav"]');
        fb.addEventListener("click", function () { toggleFav(s); renderSvModal(); if (onChange) onChange(); });
        listEl.appendChild(d);
      });
    }
    section("즐겨찾기 " + favs.length, favs, true);
    section("최근 본 " + recent.length, recent, false);
    if (!favs.length && !recent.length)
      listEl.innerHTML = '<div class="sv-empty">저장된 가맹점이 없습니다 — 목록에서 ☆를 눌러 즐겨찾기하거나, 가맹점을 클릭하면 최근 본 목록에 남습니다.</div>';
  }
  function openSvModal() { renderSvModal(); el("svModal").hidden = false; }
  function closeSvModal() { el("svModal").hidden = true; }

  window.OnnuriSaved = {
    attach: function (d) {
      el = d.el; esc = d.esc; getRegion = d.getRegion;
      onOpenSpot = d.onOpenSpot; onChange = d.onChange;
    },
    isFav: function (r) { return isFav(r); },
    toggleFav: function (r) { return toggleFav(r); },
    recordRecent: function (r) { return recordRecent(r); },
    updateCount: function () { return updateSvCount(); },
    openModal: function () { return openSvModal(); },
    closeModal: function () { return closeSvModal(); },
  };
})();
