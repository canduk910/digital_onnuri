/**
 * online-probe.js — 온라인 사용처 실시간 조회 UI (ADR-17)
 *
 * online.html 이 이미 770줄 단일 IIFE 라 외부 호출·상태·에러 렌더를 여기로 격리한다
 * (online-source.js 와 같은 방식). online.html 은 훅 3곳만 부른다.
 *
 * 설계에서 지키는 것:
 *   - 자동 조회하지 않는다. 타이핑마다 6곳을 두드리면 상대 사이트 부담이 통제 불능이 된다.
 *   - 헤드라인 문구·카운트는 서버가 준 것을 그대로 쓴다. 여기서 다시 세면 계약이
 *     바뀔 때 조용히 틀린 숫자가 나온다.
 *   - 어떤 경로로 끝나든(성공·실패·기능 꺼짐) 이용자는 22곳 링크에 닿을 수 있어야 한다.
 */
(function () {
  "use strict";

  // 로드 순서에 기대지 않는다 — 호출 시점에 해석한다.
  function apiBase() {
    var S = window.OnnuriOnlineSource;
    if (S && S.API_BASE) return S.API_BASE;
    var h = location.hostname;
    if (h === "localhost" || h === "127.0.0.1" || h === "") return "http://localhost:8080/api";
    return "https://api.koscomlabor.cloud/api";
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  /** 상태별 표시 문구. "있음"이라고 말하지 않는 것이 핵심이다. */
  var LABEL = {
    likely: { cls: "pb-likely", text: "관련 상품이 검색됨" },
    none: { cls: "pb-none", text: "검색 결과가 없습니다" },
    unclear: { cls: "pb-unclear", text: "판정하지 못했습니다" },
    unknown: { cls: "pb-unknown", text: "확인하지 못했습니다" },
    "not-probed": { cls: "pb-skip", text: "확인하지 않았습니다" }
  };
  var REASON = {
    timeout: "응답 지연", "http-error": "응답 오류", busy: "잠시 후 다시",
    "rate-limited": "요청 한도", disabled: "기능 꺼짐",
    "parse-changed": "페이지 구조가 바뀐 듯", "not-a-probe-target": "자동 확인 대상 아님"
  };

  var current = null;   // 진행 중 요청(AbortController)

  function search(q) {
    if (current) current.abort();
    current = new AbortController();
    return fetch(apiBase() + "/online/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ q: q }),
      signal: current.signal
    }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  /** 검색 전 안내 줄. 로컬 결과가 0곳이면 왜 0곳인지 먼저 말한다. */
  function renderBanner(mount, q, empty, onRun) {
    if (!mount) return;
    mount.innerHTML =
      '<div class="probe-banner">'
      + (empty
          ? '<span class="pb-why">지금 목록은 <b>어느 몰이 무엇을 파는지</b>까지만 알고 있어서 '
            + "'" + esc(q) + "' 같은 상품명은 걸리지 않습니다.</span>"
          : '<span class="pb-why">\'' + esc(q) + '\' 상품이 실제로 있는지 몰에서 직접 확인해 볼 수 있습니다.</span>')
      + '<button type="button" class="pb-run" id="probeRun">쇼핑몰에서 직접 찾아보기</button>'
      + '</div>';
    var b = mount.querySelector("#probeRun");
    if (b) b.onclick = function () { onRun(); };
  }

  /** 조회 중 — 몰 이름과 링크를 **먼저** 보여준다. 결과가 어떻든 이용자는 갈 곳이 있다. */
  function renderRunning(mount, q, platforms) {
    var rows = platforms.map(function (p) {
      return '<li class="pb-row"><span class="pb-name">' + esc(p.name) + '</span>'
        + '<span class="pb-status pb-wait">확인 중…</span>'
        + '<a class="pb-link" href="' + esc(linkFor(p, q)) + '" target="_blank" rel="noopener">몰에서 보기 ↗</a></li>';
    }).join("");
    mount.innerHTML = '<div class="probe-result"><div class="pb-head">\'' + esc(q) + '\' 확인 중…</div>'
      + '<ul class="pb-list">' + rows + '</ul></div>';
  }

  function linkFor(p, q) {
    var tpl = p.search_url_template || "";
    if (tpl && tpl.indexOf("{q}") !== -1) return tpl.replace("{q}", encodeURIComponent(q));
    return p.url || "";
  }

  function renderResult(mount, data) {
    var probed = data.items.filter(function (h) { return h.status !== "not-probed"; });
    var skipped = data.items.filter(function (h) { return h.status === "not-probed"; });

    function row(h) {
      var L = LABEL[h.status] || LABEL.unknown;
      var why = h.reason ? ' <span class="pb-reason">(' + esc(REASON[h.reason] || h.reason) + ')</span>' : "";
      // 부분 일치는 반드시 말한다. "다이슨 청소기"를 찾았는데 '청소기'만 맞는 이름이
      // 근거로 나오면 이용자는 "그 몰에 다이슨이 있다"로 읽는다(2026-08-31 실측).
      var samples = (h.sampleTitles || []).length
        ? '<div class="pb-samples">' + h.sampleTitles.map(function (t) {
            return '<span>' + esc(t) + '</span>'; }).join("")
          + '<em>' + (h.samplePartial
              ? '검색어의 일부 낱말만 맞는 결과입니다 — 찾는 상품이 아닐 수 있습니다.'
              : '이름이 비슷한 다른 상품일 수 있습니다 — 몰에서 확인하세요.') + '</em></div>'
        : "";
      var wide = h.mallWide
        ? ' <span class="pb-wide" title="기획전 딥링크라 온누리 전용관 밖 상품이 섞입니다">온누리 범위 밖 포함</span>'
        : "";
      return '<li class="pb-row"><span class="pb-name">' + esc(h.name) + wide + '</span>'
        + '<span class="pb-status ' + L.cls + '">' + esc(L.text) + why + '</span>'
        + '<a class="pb-link" href="' + esc(h.searchUrl) + '" target="_blank" rel="noopener">몰에서 보기 ↗</a>'
        + samples + '</li>';
    }

    mount.innerHTML =
      '<div class="probe-result">'
      + '<div class="pb-head">' + esc(data.notice) + '</div>'
      + '<ul class="pb-list">' + probed.map(row).join("") + '</ul>'
      + (skipped.length
          // 곳 수는 서버가 센 값을 쓴다. 여기서 다시 세면 계약이 바뀔 때 헤드라인과 조용히
          // 어긋난다(2026-08-27 normKind 와 같은 유형).
          ? '<details class="pb-more"><summary>직접 확인할 수 있는 나머지 '
            + (data.notProbedCount != null ? data.notProbedCount : skipped.length) + '곳</summary>'
            + '<ul class="pb-list">' + skipped.map(row).join("") + '</ul></details>'
          : "")
      + '<p class="pb-foot">확인 시각 ' + esc(data.checkedAt)
      + ' · 각 몰의 검색 결과를 그 자리에서 읽은 것입니다. '
      + '<b>검색된다고 해서 온누리상품권으로 결제된다는 뜻은 아닙니다</b> — 상품 상세와 결제 수단은 몰에서 확인하세요.</p>'
      + '</div>';
  }

  /** 실패해도 쇼핑 22곳 링크는 준다 — 배너가 조용히 사라지는 경로를 만들지 않는다.
      배달 8곳은 제외한다(음식 주문이라 상품명 검색이 성립하지 않는다). */
  function renderFailure(mount, q, platforms) {
    var rows = platforms.filter(function (p) { return p.kind !== "delivery"; }).map(function (p) {
      return '<li class="pb-row"><span class="pb-name">' + esc(p.name) + '</span>'
        + '<a class="pb-link" href="' + esc(linkFor(p, q)) + '" target="_blank" rel="noopener">몰에서 보기 ↗</a></li>';
    }).join("");
    mount.innerHTML = '<div class="probe-result">'
      + '<div class="pb-head">실시간 확인에 실패했습니다 — 아래에서 직접 검색해 보세요.</div>'
      + '<ul class="pb-list">' + rows + '</ul></div>';
  }

  function abort() { if (current) { current.abort(); current = null; } }

  window.OnnuriOnlineProbe = {
    search: search,
    renderBanner: renderBanner,
    renderRunning: renderRunning,
    renderResult: renderResult,
    renderFailure: renderFailure,
    linkFor: linkFor,
    abort: abort
  };
})();
