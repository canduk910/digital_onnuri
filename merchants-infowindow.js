/* 지도 상세 팝업(InfoWindow) — merchants.html 에서 분리 (2026-09-05)
   ────────────────────────────────────────────────────────────────────────
   개별 팝업·그룹 팝업·결제 표시·지도 링크·거리뷰 버튼 배선.
   사용자가 2026-09-05 에 라이브로 확인한 자리라 **동작을 먼저 고정하고** 옮겼다
   (_workspace/dev_scripts/test_infowindow_live.js — 16경로).

   **경계** — 허브(state·SNAP·refresh)를 전혀 건드리지 않는다. 지도는 게터로 받고,
   바깥 세계와 닿는 두 지점은 콜백이다:
     onOpen(r)                — 최근 본 기록(저장 모듈의 일)
     onPano(lat, lng, name)   — 거리뷰 열기(거리뷰 모듈의 일)
   이 둘을 안으로 들이면 팝업이 저장과 거리뷰를 알게 되어, 셋 중 하나를 고칠 때마다
   나머지를 봐야 한다.

   **openedAt() 을 노출하는 이유** — 바깥의 clearMarkers 가 "방금 연 팝업은 닫지 않는다"를
   판단하는 데 이 시각이 필요하다. 모바일에서 팝업이 스스로 민 지도 때문에 그 팝업이
   닫히던 2026-08-24 결함의 방어선이라, 값을 복제하지 말고 여기 하나만 두고 읽어 가게 한다.

   계약:
     OnnuriInfoWindow.attach({ esc, catLabel, ssmBrand, getMap, getInfoWin, onOpen, onPano })
     OnnuriInfoWindow.openInfo(anchor, r, backGroup) / .openGroup(anchor, group)
     OnnuriInfoWindow.payTags(r, cls) / .openedAt() */
(function () {
  "use strict";
  var esc = null, catLabel = null, SSM_BRAND = null;
  var getMap = null, getInfoWin = null, onOpen = null, onPano = null;

  var IWG = { anchor: null, group: null };   // 그룹 팝업 되돌아가기 맥락
  var popupOpenedAt = 0;                     // 마지막으로 팝업을 연 시각

  // 네이버 지도 링크 — 장소 보기(이름+주소 검색)·길찾기(목적지 좌표+이름). 좌표·주소는 데이터에 이미 있다.
  // 앱 설치 시 앱으로, 아니면 웹으로 열린다(라벨엔 '앱' 단정 금지 — guide-content-style).
  function naverPlaceUrl(r) {
    var q = ((r.name || "") + " " + (r.addr || "")).trim();
    return "https://map.naver.com/p/search/" + encodeURIComponent(q);
  }
  function naverDirUrl(r) {
    if (!r.lat || !r.lng) return null;
    return "https://map.naver.com/p/directions/-/" + r.lng + "," + r.lat + ","
      + encodeURIComponent(r.name || "") + "/-/transit";
  }
  // InfoWindow는 내부 클릭 전파를 막아 document 위임이 닿지 않는다 — 팝업을 연 직후 직접 바인딩.
  function wirePanoBtns() {
    Array.prototype.forEach.call(document.querySelectorAll(".iw-act-pano"), function (b) {
      if (b.__wired) return; b.__wired = true;
      b.addEventListener("click", function () {
        if (onPano) onPano(parseFloat(b.getAttribute("data-lat")), parseFloat(b.getAttribute("data-lng")), b.getAttribute("data-name"));
      });
    });
  }

  function mapActions(r) {
    var html = '<div class="iw-actions">'
      + '<a class="iw-act" href="' + naverPlaceUrl(r) + '" target="_blank" rel="noopener">네이버 지도</a>';
    if (r.lat && r.lng) {
      html += '<button type="button" class="iw-act iw-act-pano" data-lat="' + r.lat + '" data-lng="' + r.lng
        + '" data-name="' + esc(r.name || "") + '">거리뷰</button>';
    }
    var dir = naverDirUrl(r);
    if (dir) html += '<a class="iw-act iw-act-dir" href="' + dir + '" target="_blank" rel="noopener">길찾기</a>';
    return html + '</div>';
  }

  /**
   * 결제 수단 태그. **창구를 하나로 둔다** — 표·개별 팝업·그룹 팝업이 각각 조건을 쓰다가
   * 팝업 두 곳만 "둘 다 N 이면 결제 줄을 통째로 생략"하고 있었다(2026-09-03 적발).
   * 결제가 안 되는 곳에서 결제에 관해 **아무 말도 안 하는 것**이 가장 나쁜 침묵이다.
   * card·qr 은 공식 API 가 늘 Y/N 을 주므로 "모름" 상태는 존재하지 않는다.
   */
  function payTags(r, cls) {
    var p = cls || "";
    var t = "";
    if (r.card === "Y") t += '<span class="' + p + 'card">카드</span>';
    if (r.qr === "Y") t += '<span class="' + p + 'qr">QR</span>';
    if (t) return t;
    // 2026-09-04: "둘 다 N = 디지털 불가" 는 **공식 API 가 준 행에서만** 참이다.
    // 그런데 이 파일 안에서 openInfo 에 card·qr 이 아예 없는 객체를 넘기는 경로가 있었고
    // (리스트 행 클릭이 만들던 합성 객체, 그리고 그때 저장된 옛 '최근 본' 스냅샷),
    // 그 경우 값이 undefined 라 여기 폴백에 걸려 **결제되는 가맹점을 지류 전용이라 단정**했다.
    // 호출부는 고쳤지만(아래 wireRowMap), 이미 저장된 스냅샷은 되돌릴 수 없다.
    // 모르는 것과 안 되는 것을 가른다 — 모르면서 안 된다고 말하는 쪽이 더 나쁘다.
    if (r.card !== "N" && r.qr !== "N") {
      return '<span class="' + p + 'none" title="저장된 기록에 결제 수단이 없어 확인하지 못했습니다 — 목록에서 이 가맹점을 다시 찾아보세요">결제 수단 미확인</span>';
    }
    return '<span class="' + p + 'none" title="공식 목록 기준 카드형·모바일형(QR) 결제가 되지 않는 곳입니다 — 지류(종이) 상품권만 받습니다">디지털 불가 · 지류만</span>';
  }

  /* 팝업 컨텍스트. 데이터 속성에는 객체를 실을 수 없으므로(인덱스만 실린다) 지금 열려 있는
     그룹을 코드가 들고 있는다. 그룹 팝업↔개별 팝업을 오가는 두 방향이 같은 배열을 본다. */
  var IWG = { anchor: null, group: null };

  /**
   * 개별 팝업. `backGroup` 이 있으면 그룹 목록으로 돌아가는 줄이 맨 위에 붙는다.
   *
   * **최근 본 기록의 유일한 창구가 이 함수다**(onOpen 호출부는 여기 한 곳뿐이다 —
   * 기록 자체는 저장 모듈이 하고 우리는 "열렸다"만 알린다).
   * 그래서 그룹 항목 클릭을 이 함수로 보내면 기록 규칙을 새로 쓸 필요가 없고,
   * 그룹 팝업 자체는 계속 아무것도 기록하지 않는다 — 사용자 결정(2026-09-04)이
   * "그룹 팝업에서는 개별 가맹점을 조회할 때만 등록" 이었다.
   */
  function openInfo(anchor, r, backGroup) {
    if (onOpen) onOpen(r);   // 최근 본 기록 — 저장 모듈의 일이라 바깥에 맡긴다
    IWG = { anchor: anchor, group: backGroup || null };
    var pay = payTags(r);
    var back = backGroup
      ? '<button type="button" class="iw-back">‹ 이 위치 목록 ' + backGroup.length + '곳</button>'
      : "";
    var html = '<div class="iw">' + back + '<div class="iw-name">' + esc(r.name) + '</div>'
      + '<div class="iw-cat">' + esc(catLabel(r.cat)) + (r.brand === SSM_BRAND ? " · SSM" : "") + '</div>'
      + '<div class="iw-addr">' + esc(r.addr || "") + (r.market ? " · " + esc(r.market) : "") + '</div>'
      + '<div class="iw-pay">' + pay + '</div>'
      + mapActions(r) + '</div>';
    openInfoWindow(html, (anchor instanceof naver.maps.Marker) ? anchor : new naver.maps.LatLng(r.lat, r.lng));
  }

  // 동일좌표(같은 건물) 여러 가맹점 — 그 위치의 전체 목록 팝업(온누리 '선택 위치 가맹점'과 동일 UX).
  function openGroupInfo(anchor, group) {
    IWG = { anchor: anchor, group: group };
    var items = group.map(function (r, i) {
      var pay = payTags(r);
      // data-i 는 group 배열의 자리뿐이다. 이름·좌표를 마크업에 싣지 않는다 —
      // DOM 텍스트에서 되읽으면 2026-08-24 의 '☆·태그가 이름에 섞인' 결함이 되살아난다.
      return '<div class="iwg-item tap" role="button" tabindex="0" data-i="' + i + '">'
        + '<div class="iwg-l"><div class="iwg-name">' + esc(r.name) + '</div>'
        + '<div class="iwg-cat">' + esc(catLabel(r.cat)) + (r.brand === SSM_BRAND ? " · SSM" : "") + '</div></div>'
        + '<div class="iw-pay">' + pay + '</div><div class="iwg-go" aria-hidden="true">›</div></div>';
    }).join("");
    // 그룹은 좌표·주소가 동일하므로 지도/길찾기 링크는 상단에 한 세트만(개별 항목엔 중복 안 함).
    // 곳 수는 **현재 필터를 통과한 수**다(drawPins 가 SNAP.mapPins 를 그룹핑한다) — 그 주소의
    // 전부가 아니다. 부분만 보여줄 땐 그렇다고 말한다는 원칙에 따라 제목에 밝힌다.
    var html = '<div class="iw iwg"><div class="iwg-head">이 위치 가맹점 <b>' + group.length + '곳</b>'
      + '<span class="iwg-scope" title="지금 켜 둔 업종·브랜드·검색 조건을 통과한 곳만 셉니다">현재 조건 기준</span></div>'
      + '<div class="iwg-addr">' + esc(group[0].addr || "") + '</div>'
      + mapActions(group[0])
      + '<div class="iwg-list">' + items + '</div></div>';
    // 되돌아가는 시점에는 마커가 이미 지워졌을 수 있다 — openInfo 와 같게 정규화한다.
    openInfoWindow(html, (anchor instanceof naver.maps.Marker) ? anchor
      : new naver.maps.LatLng(group[0].lat, group[0].lng));
  }

  /* 그룹 항목·되돌아가기 배선. wirePanoBtns 와 같은 패턴 —
     InfoWindow 는 내부 클릭 전파를 막아 document 위임이 닿지 않으므로 직접 바인딩하고,
     __wired 가드로 같은 노드에 두 번 걸리지 않게 한다. */
  function wireIwgItems() {
    Array.prototype.forEach.call(document.querySelectorAll(".iwg-item[data-i]"), function (it) {
      if (it.__wired) return; it.__wired = true;
      function go() {
        var g = IWG.group; if (!g) return;
        var r = g[parseInt(it.getAttribute("data-i"), 10)];
        if (r) openInfo(IWG.anchor, r, g);   // ← 여기서만 최근 본에 남는다
      }
      it.addEventListener("click", go);
      it.addEventListener("keydown", function (e) {
        if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); }
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll(".iw-back"), function (b) {
      if (b.__wired) return; b.__wired = true;
      b.addEventListener("click", function () {
        if (IWG.group) openGroupInfo(IWG.anchor, IWG.group);
      });
    });
  }

  /* 팝업 열기 창구 — 여는 경로(개별·그룹)가 모두 같은 보호를 받게 한다.
     모바일은 지도(≈388px)에 견줘 팝업이 커서(그룹 12곳이면 347px) SDK 가 지도를 자동으로
     민다. 그 pan 이 idle → 400ms 뒤 viewportRender → clearMarkers 로 이어져 방금 연 팝업을
     닫아버렸다(PC 는 지도가 780px 라 pan 이 없어 멀쩡했다 — 대조 실측).

     idle 자체를 건너뛰는 방식은 쓰지 않는다. 팝업 뒤에 오는 첫 idle 이 팝업 pan 이 아닐 수
     있어서다 — 딥링크 착지는 morph 로 지도를 옮긴 뒤 팝업을 여는데, 그 morph 의 idle 을
     먹어버리면 마커가 아예 그려지지 않는다(실제로 그렇게 깨졌다).
     그래서 재렌더는 그대로 두고, **방금 연 팝업만** 닫지 않는다. */
  function openInfoWindow(html, anchor) {
    popupOpenedAt = Date.now();
    var win = getInfoWin();
    win.setContent(html);
    win.open(getMap(), anchor);
    setTimeout(function () { wirePanoBtns(); wireIwgItems(); }, 0);
  }

  window.OnnuriInfoWindow = {
    attach: function (d) {
      esc = d.esc; catLabel = d.catLabel; SSM_BRAND = d.ssmBrand;
      getMap = d.getMap; getInfoWin = d.getInfoWin; onOpen = d.onOpen; onPano = d.onPano;
    },
    openInfo: function (anchor, r, backGroup) { return openInfo(anchor, r, backGroup); },
    openGroup: function (anchor, group) { return openGroupInfo(anchor, group); },
    payTags: function (r, cls) { return payTags(r, cls); },
    openedAt: function () { return popupOpenedAt; },
  };
})();
