/* 가맹점 거리뷰 — 네이버 파노라마 (ADR-9 계열 · 2026-09-04 외부화)
 *
 * merchants.html 의 거리뷰 구획 328줄을 **한 줄도 고치지 않고** 옮긴 것이다.
 * 옮기면서 눈에 띈 것을 함께 고치고 싶어지지만(예: #mapNote 공유), 그건 동작 변경이라
 * 회귀가 났을 때 이동 탓인지 수정 탓인지 못 가린다 — 별도 커밋으로 미룬다.
 *
 * ── 계약 ────────────────────────────────────────────────────────────────
 * 바깥에서 필요한 것 5종을 attach 로 주입받는다. `mapObj`·`mapReady` 는 initMap 이
 * **나중에** 채우는 값이라 값이 아니라 **게터**로 받는다 — 로드 시점에 붙잡으면 영영 null 이다.
 *
 *   OnnuriPano.attach({ el, esc, getMap, isMapReady, ensureMap })
 *   OnnuriPano.openPano(lat, lng, name)   ← 팝업의 '거리뷰' 버튼
 *   OnnuriPano.closePano()                ← 패널 닫기 버튼
 *   OnnuriPano.toggleStreetMode()         ← 지도 구석 토글
 *   OnnuriPano.initPanoFloat()            ← 패널 이동·크기조절 초기화
 *
 * ── 남아 있는 결합 (옮긴다고 사라지지 않는다) ──────────────────────────────
 * ①`#mapNote` 를 거리뷰가 진입 시 저장했다가 나갈 때 되돌린다(panoNoteSaved).
 *   그 사이 지도가 재렌더하면 저장본이 낡는다 — 파일을 나눠 **보이게** 됐을 뿐이다.
 * ②SDK URL 의 `&submodules=panorama` 가 merchants.html 에 남는다. 그것이 빠지면
 *   이 파일은 로드되지만 파노라마가 열리지 않는다.
 * ③`#panoClose`·`#streetBtn` 은 merchants.html 의 bindControls 가 배선한다.
 */
(function () {
  "use strict";

  // 주입 대상. attach 전에는 아무 함수도 부르면 안 된다(boot 가 가장 먼저 부른다).
  var el = null, esc = null, getMap = null, isMapReady = null, ensureMap = null;

  /* ── 거리뷰 (2026-08-13) — 네이버 공식 panorama 서브모듈, 플로팅 패널(이동·크기조절).
     열려 있는 동안 메인 지도가 '거리뷰 모드': StreetLayer(파란 길) 표시 + 지도 클릭 = 그 지점 거리뷰 이동,
     현재 보는 위치는 지도 위 주황 원(street-spot)으로 동기화. ── */
  var panoObj = null, panoTimer = null;
  var streetLayer = null, spotMarker = null, streetClickL = null, panoNoteSaved = null;
  function showNoPano(view) {
    view.innerHTML = '<div class="pano-msg">이 위치 주변에는 거리뷰가 제공되지 않습니다.<br>메인 지도의 파란 길을 눌러 근처 촬영 지점을 선택해 보세요.</div>';
    panoObj = null;
  }
  function setSpot(pos) {   // 현재 보는 위치+방향 마커(주황 원 + 시야 콘)
    if (!pos) return;
    if (!spotMarker) {
      spotMarker = new naver.maps.Marker({ map: getMap(), position: pos, zIndex: 300,
        icon: { content: '<div class="street-spot-wrap"><div class="street-cone"></div><div class="street-spot"></div></div>',
                anchor: new naver.maps.Point(44, 44) } });
    } else { spotMarker.setMap(getMap()); spotMarker.setPosition(pos); }
    setTimeout(updateSpotDir, 0);
  }
  // 촬영일자 — 파노라마 메타(location.photodate)가 있을 때만 표시(없으면 비움 — 사실만)
  function updatePanoDate() {
    var out = el("panoDate");
    if (!out) return;
    var txt = "";
    try {
      var loc = panoObj && panoObj.getLocation && panoObj.getLocation();
      var pd = loc && (loc.photodate || loc.photoDate);
      if (pd) {
        var digits = String(pd).replace(/\D/g, "");
        if (digits.length >= 6) txt = "촬영 " + digits.slice(0, 4) + "." + digits.slice(4, 6);
        else txt = "촬영 " + pd;
      }
    } catch (e) {}
    out.textContent = txt;
  }
  // 파노라마 시선 방향(pov.pan, 정북 0°·시계방향)을 마커 콘 회전으로 동기화
  function updateSpotDir() {
    if (!spotMarker || !panoObj) return;
    try {
      var pov = panoObj.getPov();
      var root = spotMarker.getElement && spotMarker.getElement();
      var w = root && root.querySelector(".street-spot-wrap");
      if (w && pov) w.style.transform = "rotate(" + (pov.pan || 0) + "deg)";
    } catch (e) {}
  }
  /* ── 파노라마 안 '지정 위치' 표시 (2026-08-24) ────────────────────────────────
     거리뷰를 열어도 어느 건물이 그 가맹점인지 알 수 없다는 제보에서 출발했다.
     SDK 의 Marker·InfoWindow 는 map 으로 Panorama 를 받아주긴 하지만 **투영되지 않는다**
     (DOM 이 -9999px 에 방치된다 — 실측). 그래서 projection.fromCoordToOffset 으로
     화면 중심 기준 오프셋을 직접 구해 오버레이를 얹는다.
     대상이 시야 밖이면 SDK 가 (-9999,-9999) 센티넬을 주므로 그것으로 숨김을 판정한다. */
  var panoTarget = null;      // { pos, name } — 가맹점 경유로 열었을 때만 채운다
  var panoTargetRaf = 0;
  var OFFSCREEN = -9000;      // 센티넬 판정 임계(정상 오프셋이 이만큼 음수일 수 없다)

  function ensurePanoOverlay() {
    var view = el("panoView");
    if (!view) return null;
    var box = view.querySelector(".pano-target");
    if (!box) {
      box = document.createElement("div");
      box.className = "pano-target"; box.hidden = true;
      box.innerHTML = '<div class="pt-label"></div><div class="pt-stem"></div><div class="pt-pin"></div>';
      view.appendChild(box);
      ["left", "right"].forEach(function (side) {
        var e = document.createElement("div");
        e.className = "pano-edge " + side; e.hidden = true;
        e.innerHTML = side === "left"
          ? '<span aria-hidden="true">◀</span><span class="pe-name"></span>'
          : '<span class="pe-name"></span><span aria-hidden="true">▶</span>';
        view.appendChild(e);
      });
    }
    return box;
  }

  function updatePanoTarget() {   // pov_changed 는 드래그 중 연달아 온다 — 프레임당 한 번만 그린다
    if (panoTargetRaf) return;
    panoTargetRaf = requestAnimationFrame(function () { panoTargetRaf = 0; drawPanoTarget(); });
  }

  function drawPanoTarget() {
    var view = el("panoView");
    var box = view && view.querySelector(".pano-target");
    if (!box) return;
    var L = view.querySelector(".pano-edge.left"), R = view.querySelector(".pano-edge.right");
    function hideAll() { box.hidden = true; if (L) L.hidden = true; if (R) R.hidden = true; }
    if (!panoTarget || !panoObj) return hideAll();

    var proj, off;
    try { proj = panoObj.getProjection(); off = proj.fromCoordToOffset(panoTarget.pos); }
    catch (e) { return hideAll(); }

    if (off && off.x > OFFSCREEN && off.y > OFFSCREEN) {
      // 시야 안 — 중심에서 오프셋만큼 떨어진 지점에 얹는다
      if (L) L.hidden = true; if (R) R.hidden = true;
      box.style.left = (view.clientWidth / 2 + off.x) + "px";
      box.style.top = (view.clientHeight / 2 + off.y) + "px";
      box.hidden = false;
      return;
    }
    // 시야 밖 — 어느 쪽으로 돌리면 되는지만 알려준다
    box.hidden = true;
    var right = true;
    try {
      var tp = proj.fromCoordToPov(panoTarget.pos).pan, cp = (panoObj.getPov() || {}).pan || 0;
      right = ((((tp - cp) % 360) + 540) % 360 - 180) > 0;   // -180~180 으로 정규화
    } catch (e) {}
    if (L) L.hidden = right;
    if (R) R.hidden = !right;
  }

  /* 두 좌표 사이의 방위각(정북 0°·시계방향) — 파노라마 pov.pan 과 같은 기준 */
  function bearingDeg(from, to) {
    var f1 = from.lat() * Math.PI / 180, f2 = to.lat() * Math.PI / 180;
    var dl = (to.lng() - from.lng()) * Math.PI / 180;
    var y = Math.sin(dl) * Math.cos(f2);
    var x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }

  /* 지면 클릭 이동 — 네이버 로드뷰처럼 "가려는 쪽 바닥"을 누르면 그리로 걸어간다.
     SDK 의 flightSpot 화살표는 정확히 눌러야 하고, 패널이 낮으면 화면 밖으로 밀린다(실측).
     projection.fromOffsetToCoord 는 지면 교점이 아니라 수백 m 짜리 구면 투영을 주므로 쓸 수 없다
     (하늘을 눌러도 413m 가 나온다). 그래서 클릭 지점의 **방위만** 직접 계산하고,
     그 방향 앞 좌표로 setPosition 한다 — 가장 가까운 촬영점 스냅은 SDK 가 해준다. */
  var PANO_STEP_M = 12;        // 한 번에 걸어갈 거리(가까운 촬영점으로 스냅되므로 대략치면 된다)
  function initPanoGroundClick(view) {
    if (!view || view.__groundClick) return;
    view.__groundClick = true;
    var downX = 0, downY = 0, downT = 0;
    view.addEventListener("mousedown", function (e) { downX = e.clientX; downY = e.clientY; downT = Date.now(); });
    view.addEventListener("click", function (e) {
      if (!panoObj) return;
      // 둘러보려고 드래그한 것을 클릭으로 오인하면 안 된다
      if (Math.abs(e.clientX - downX) > 6 || Math.abs(e.clientY - downY) > 6) return;
      if (Date.now() - downT > 700) return;
      // 화살표는 SDK 가 직접 처리한다 — 우리가 또 옮기면 두 칸 간다
      if (e.target && /arrow/i.test((e.target.className || "").toString())) return;
      var r = view.getBoundingClientRect();
      var ox = e.clientX - r.left - r.width / 2;
      var oy = e.clientY - r.top - r.height / 2;
      if (oy <= 0) return;                       // 지평선 위(하늘·건물 상단)는 이동 대상이 아니다
      var pov = panoObj.getPov() || { pan: 0, fov: 100 };
      var half = (pov.fov || 100) / 2 * Math.PI / 180;
      // 화면 x 오프셋 → 시선 기준 각도(원근 보정: 선형이 아니라 tan 비례)
      var dPan = Math.atan((ox / (r.width / 2)) * Math.tan(half)) * 180 / Math.PI;
      var bearing = ((pov.pan || 0) + dPan) * Math.PI / 180;
      var here = panoObj.getPosition();
      var dLat = (PANO_STEP_M * Math.cos(bearing)) / 111000;
      var dLng = (PANO_STEP_M * Math.sin(bearing)) / (111000 * Math.cos(here.lat() * Math.PI / 180));
      try { panoObj.setPosition(new naver.maps.LatLng(here.lat() + dLat, here.lng() + dLng)); } catch (err) {}
    });
  }

  function setPanoTarget(lat, lng, name) {
    // 지도 구석 토글로 연 거리뷰에는 '지정 위치'가 없다(그 지점이 곧 현재 위치다)
    panoTarget = (name && isFinite(lat) && isFinite(lng))
      ? { pos: new naver.maps.LatLng(lat, lng), name: name } : null;
    var box = ensurePanoOverlay();
    if (!box) return;
    var lab = box.querySelector(".pt-label");
    // 대표좌표는 건물 단위라 점포 입구가 아니다 — 라벨에서 그 사실을 숨기지 않는다
    if (lab) lab.innerHTML = panoTarget ? esc(name) + '<i>대략 위치</i>' : "";
    var view = el("panoView");
    ["left", "right"].forEach(function (side) {
      var e = view && view.querySelector(".pano-edge." + side);
      var n = e && e.querySelector(".pe-name");
      if (n) n.textContent = panoTarget ? name : "";
    });
    drawPanoTarget();
  }

  function updateStreetBtn(on) {
    var b = el("streetBtn");
    if (b) { b.classList.toggle("on", !!on); b.setAttribute("aria-pressed", on ? "true" : "false"); }
  }
  function enterStreetMode(pos) {   // pos 없이도 진입 가능(지도 구석 토글) — 클릭한 지점에서 거리뷰가 열린다
    if (!isMapReady() && !ensureMap()) return;
    if (!streetLayer) streetLayer = new naver.maps.StreetLayer();
    streetLayer.setMap(getMap());
    setSpot(pos);
    if (!streetClickL) {
      streetClickL = naver.maps.Event.addListener(getMap(), "click", function (e) {
        if (panoObj) panoObj.setPosition(e.coord);   // 파노라마가 가장 가까운 촬영점을 찾는다
        else {
          var lat = e.coord.lat ? e.coord.lat() : e.coord.y, lng = e.coord.lng ? e.coord.lng() : e.coord.x;
          openPano(lat, lng, null);
        }
      });
    }
    updateStreetBtn(true);
    var note = el("mapNote");
    if (note) {
      if (panoNoteSaved == null) panoNoteSaved = note.innerHTML;
      note.innerHTML = "거리뷰 모드 — 지도의 <b>파란 길</b>을 누르면 그 지점 거리뷰가 열리고, 다시 누르면 이동합니다.";
    }
  }
  function exitStreetMode() {
    if (streetLayer) streetLayer.setMap(null);
    if (spotMarker) spotMarker.setMap(null);
    if (streetClickL) { naver.maps.Event.removeListener(streetClickL); streetClickL = null; }
    updateStreetBtn(false);
    var note = el("mapNote");
    if (note && panoNoteSaved != null) { note.innerHTML = panoNoteSaved; panoNoteSaved = null; }
  }
  function toggleStreetMode() {
    if (streetClickL) closePano();   // 모드 중이면 패널까지 닫고 완전 종료(closePano→exitStreetMode)
    else enterStreetMode(null);
  }
  function openPano(lat, lng, name) {
    var m = el("panoModal"), view = el("panoView");
    el("panoTitle").textContent = name ? "거리뷰 — " + name : "거리뷰";
    m.hidden = false;
    view.innerHTML = "";
    clearTimeout(panoTimer);
    if (!(window.naver && naver.maps && naver.maps.Panorama)) {
      view.innerHTML = '<div class="pano-msg">거리뷰 모듈을 불러오지 못했습니다 — 새로고침 후 다시 시도해 주세요.</div>';
      return;
    }
    var pos = new naver.maps.LatLng(lat, lng);
    var inited = false;
    // 열자마자 정북을 보면 가맹점이 등 뒤일 수 있다(가장자리 화살표만 뜬다).
    // 처음부터 그쪽을 바라보게 하고, 지면이 화면에 들어오도록 살짝 내려다본다
    // — tilt 0 이면 낮은 패널에서 이동 화살표가 화면 밖으로 밀린다(실측).
    var initPov = { pan: 0, tilt: -12, fov: 100 };
    try {
      panoObj = new naver.maps.Panorama(view, {
        position: pos,
        pov: initPov,
        flightSpot: true,        // 지면 이동 화살표 — 누르면 그 방향 촬영점으로 이동(pano_changed 로 지도 마커 동기화)
        zoomControl: true
      });
      naver.maps.Event.addListener(panoObj, "init", function () {
        inited = true; clearTimeout(panoTimer);
        // 파노라마는 요청 좌표에서 가장 가까운 촬영점으로 스냅된다 — 그 실제 위치에서
        // 가맹점 쪽 방위를 다시 계산해야 정확히 바라본다.
        if (panoTarget && panoObj) {
          try {
            var here = panoObj.getPosition();
            panoObj.setPov({ pan: bearingDeg(here, panoTarget.pos), tilt: -12, fov: 100 });
          } catch (e) {}
        }
        initPanoGroundClick(el("panoView"));
      });
      naver.maps.Event.addListener(panoObj, "pano_status", function (st) {
        if (st && st !== "OK" && !inited) showNoPano(view);
      });
      // 파노라마에서 이동(화살표·지도 클릭)하면 메인 지도의 현재 위치 마커를 따라 옮긴다
      naver.maps.Event.addListener(panoObj, "pano_changed", function () {
        if (!panoObj) return;
        setSpot(panoObj.getPosition());   // 마커가 없으면(구석 토글 진입) 여기서 생성
        updatePanoDate();
        updatePanoTarget();               // 이동하면 지정 위치가 보이는 방향·거리가 달라진다
      });
      // 시선을 돌리면(드래그) 지도 마커의 시야 콘도 따라 회전
      naver.maps.Event.addListener(panoObj, "pov_changed", function () {
        updateSpotDir(); updatePanoTarget();
      });
      naver.maps.Event.addListener(panoObj, "init", function () {
        updatePanoDate(); updatePanoTarget();
      });
    } catch (e) { showNoPano(view); }
    setPanoTarget(lat, lng, name);   // 파노라마 생성 뒤에 얹는다(view.innerHTML="" 로 지워지므로)
    enterStreetMode(pos);
    // 주변에 파노라마가 없으면 init이 오지 않는다 — 타임아웃 폴백으로 안내
    panoTimer = setTimeout(function () {
      if (!inited && !view.querySelector("canvas, img")) showNoPano(view);
    }, 3500);
  }
  function closePano() {
    clearTimeout(panoTimer);
    if (panoTargetRaf) { cancelAnimationFrame(panoTargetRaf); panoTargetRaf = 0; }
    panoTarget = null;
    el("panoModal").hidden = true;
    el("panoView").innerHTML = "";     // 오버레이도 함께 사라진다(다음 열 때 재생성)
    var pd = el("panoDate"); if (pd) pd.textContent = "";
    panoObj = null;
    exitStreetMode();
  }
  // 패널 이동(헤더 드래그) + 크기조절(우하단 그립). 크기는 localStorage로 기억.
  function initPanoFloat() {
    var panel = el("panoModal");
    if (!panel || panel.__float) return;
    panel.__float = true;
    var SZ_KEY = "onnuri_pano_box";
    try {
      var sz = JSON.parse(localStorage.getItem(SZ_KEY));
      if (sz && sz.w) { panel.style.width = Math.min(sz.w, window.innerWidth - 28) + "px"; }
      if (sz && sz.h) { el("panoView").style.height = Math.min(sz.h, window.innerHeight - 160) + "px"; }
    } catch (e) {}
    var head = panel.querySelector(".modal-head");
    head.addEventListener("pointerdown", function (ev) {
      if (ev.target.closest(".modal-close")) return;
      ev.preventDefault();
      var rect = panel.getBoundingClientRect(), sx = ev.clientX, sy = ev.clientY;
      panel.style.left = rect.left + "px"; panel.style.top = rect.top + "px";
      panel.style.right = "auto"; panel.style.bottom = "auto";
      function mv(e2) {
        var L = Math.min(Math.max(rect.left + e2.clientX - sx, 8), window.innerWidth - 80);
        var T = Math.min(Math.max(rect.top + e2.clientY - sy, 8), window.innerHeight - 60);
        panel.style.left = L + "px"; panel.style.top = T + "px";
      }
      function up() { document.removeEventListener("pointermove", mv); document.removeEventListener("pointerup", up); }
      document.addEventListener("pointermove", mv); document.addEventListener("pointerup", up);
    });
    var grip = el("panoResize");
    var rzTimer = null;
    grip.addEventListener("pointerdown", function (ev) {
      ev.preventDefault();
      var rect = panel.getBoundingClientRect(), vh0 = el("panoView").getBoundingClientRect().height;
      var sx = ev.clientX, sy = ev.clientY;
      function mv(e2) {
        var w = Math.min(Math.max(rect.width + e2.clientX - sx, 320), window.innerWidth - 28);
        var h = Math.min(Math.max(vh0 + e2.clientY - sy, 220), window.innerHeight - 180);
        panel.style.width = w + "px";
        // 파노라마 SDK가 #panoView에 width/height를 인라인 px로 고정한다 — CSS 변수가 아니라
        // 인라인을 직접 갱신해야 적용된다(width는 비워 100%로 패널을 따르게).
        var v = el("panoView");
        v.style.height = h + "px"; v.style.width = "";
        clearTimeout(rzTimer);
        rzTimer = setTimeout(function () {   // 파노라마 캔버스 재배치(디바운스)
          try { window.dispatchEvent(new Event("resize")); localStorage.setItem("onnuri_pano_box", JSON.stringify({ w: w, h: h })); } catch (e) {}
        }, 150);
      }
      function up() { document.removeEventListener("pointermove", mv); document.removeEventListener("pointerup", up); }
      document.addEventListener("pointermove", mv); document.addEventListener("pointerup", up);
    });
  }

  window.OnnuriPano = {
    attach: function (d) {
      el = d.el; esc = d.esc; getMap = d.getMap; isMapReady = d.isMapReady; ensureMap = d.ensureMap;
    },
    openPano: function (lat, lng, name) { return openPano(lat, lng, name); },
    closePano: function () { return closePano(); },
    toggleStreetMode: function () { return toggleStreetMode(); },
    initPanoFloat: function () { return initPanoFloat(); },
  };
})();
