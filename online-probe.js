/**
 * online-probe.js — 온라인 사용처 실시간 조회 UI (ADR-17)
 *
 * online.html 이 이미 770줄 단일 IIFE 라 외부 호출·상태·에러 렌더를 여기로 격리한다
 * (online-source.js 와 같은 방식). online.html 은 훅 3곳만 부른다.
 *
 * 설계에서 지키는 것:
 *   - 자동 조회하지 않는다. 타이핑마다 조회 대상 전부를 두드리면 상대 사이트 부담이 통제 불능이 된다.
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

  // 다리 리드 문구는 <b> 강조만 허용한다 — 전부 이스케이프한 뒤 <b></b> 만 되살린다.
  // lead 에 검색어 같은 외부 값이 들어가는 순간 innerHTML 이 XSS 가 되므로(2026-09-02 dev-qa O-1)
  // 호출자가 조심하는 대신 여기서 막는다.
  function escLead(s) { return esc(s).replace(/&lt;(\/?)b&gt;/g, "<$1b>"); }

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
    "parse-changed": "페이지 구조가 바뀐 듯",
    // 조회하지 않은 이유(ProbeTargets.exclusionReason). 사유를 대야 링크를 눌러 볼 근거가 된다.
    // 3종 전수(ADR-18) — 예전에는 2종만 적고 나머지를 no-static-search 로 흘려 화면이
    // "화면에서만 만들어져 읽을 수 없음 10곳"이라고 잘못 말하고 있었다.
    // no-search-feature 는 붙는 몰이 사라져(지니어스몰 조회 대상 승격) 함께 지웠다 —
    // 해당하는 몰이 없는 사유 라벨은 두지 않는다(2026-09-01 rules-unverified 와 같은 원칙).
    "robots-blocked": "몰이 자동 조회를 막아 둠",
    "scope-first": "시장·주소를 먼저 고르는 구조",
    "no-static-search": "자동 조회가 안 되는 구조",
    "not-a-probe-target": "자동 확인 대상 아님"
  };
  /** 접힌 섹션 안에서 한 번 더 풀어 쓴다 — 배지만으로는 뜻이 안 통한다. */
  var REASON_LONG = {
    "robots-blocked": "이 몰은 robots.txt로 자동 조회를 막아 뒀습니다. 링크로 들어가 직접 검색하는 것은 정상 이용입니다.",
    "scope-first": "시장이나 배달 주소를 먼저 골라야 상품이 나오는 몰이라 한 번에 조회할 수 없습니다.",
    "no-static-search": "검색 결과가 화면에서만 만들어지고 검색 API 는 인증이 필요해 자동으로 읽을 수 없습니다."
  };
  /**
   * 모르는 사유가 오면 **원시 키를 그대로 찍지 않는다.** 서버가 사유를 더하거나 빼는 동안
   * (배포 시차) `no-search-feature` 같은 영문 키가 이용자 화면에 보이면 안 된다.
   *
   * 배지는 아예 생략한다 — 이 자리는 조회 실패 사유(timeout·http-error)도 함께 쓰므로
   * 아무 문장이나 채우면 그 몰에 대해 거짓이 될 수 있다. 상태 라벨이 이미 뜻을 담고 있다.
   */
  function reasonBadge(k) { return REASON[k] || null; }
  /** 접힌 섹션은 비대상 몰만 모아 두므로, 모르는 사유는 어느 몰에도 참인 문장으로 물러선다. */
  function reasonText(k) { return REASON_LONG[k] || REASON[k] || REASON["not-a-probe-target"]; }

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
          : '<span class="pb-why">\'' + esc(q) + '\' 상품이 실제로 있는지 여러 몰을 동시에 조회해 확인할 수 있습니다.</span>')
      + '<button type="button" class="pb-run" id="probeRun">실시간 병렬조회 실행</button>'
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

  /**
   * 옆 탭('몰 둘러보기')으로 건너가는 다리 — "이걸로 끝이 아니다"를 말한다.
   *
   * 실시간 조회는 몇 곳만 보고 그 순간의 검색 결과만 읽는다. 반면 태그 목록은 각 몰의
   * 카테고리·브랜드를 사람이 훑어 정리한 것이라 **무엇을 파는 몰인지**를 넓게 보여준다.
   * 둘은 답하는 질문이 다르고, 한쪽만 보면 이용자가 "여기엔 없다"로 잘못 접는다.
   *
   * 2026-09-02 탭 분리 전에는 "아래 N곳 목록에는…"이라는 안내였다. 두 축이 탭으로 갈리면서
   * 그 목록이 같은 화면에 없어졌으므로 **문장이 아니라 버튼**으로 바꿨다 — '아래'가 없는데
   * 아래라고 말하면 이용자는 스크롤만 하다 만다.
   *
   * 문구·곳 수는 호출자(online.html)가 만들어 넘긴다. 여기서 세면 적용될 필터와 곳 수가
   * 갈려 "13곳 둘러보기"를 눌렀는데 10곳이 나오는 일이 생긴다.
   */
  function bridgeBlock(bridge) {
    if (!bridge || !bridge.label) return "";
    return '<div class="pb-bridge">'
      + (bridge.lead ? '<p>' + escLead(bridge.lead) + '</p>' : "")
      + '<button type="button" id="probeBridge">' + esc(bridge.label) + '</button></div>';
  }

  /**
   * 전일 색인 층(ADR-18) — 야간 배치가 모아 둔 상품명에서 찾은 결과.
   *
   * 실시간 층과 **섞지 않는다**. "어제 이 이름의 상품이 올라와 있었다"와 "지금 검색된다"는
   * 다른 주장이고, 한 목록에 넣으면 문구가 거짓이 된다. 그래서 별도 블록·별도 헤드라인·
   * 한 단계 약한 시각 위계로 두고, 상단에 `전일 색인` eyebrow 를 박는다.
   *
   * 헤드라인은 서버 notice 를 그대로 쓴다 — items 로 곳 수를 다시 세면 계약이 바뀔 때
   * 조용히 어긋난다(2026-08-27 normKind 유형).
   * 그릴 것이 없으면(platformCount 0 · notice 없음) 블록 자체를 만들지 않는다.
   */
  function indexBlock(idx) {
    if (!idx || !idx.notice || !(idx.platformCount > 0)) return "";
    var items = idx.items || [];
    var rows = items.map(function (h) {
      // matchCount 는 '검색어 전체를 담은 이름'의 건수다. 0인데 샘플이 있으면 부분 일치라
      // 그 사실을 상태로 먼저 말해야 샘플이 '찾았다'로 읽히지 않는다.
      var hit = h.matchCount > 0;
      var status = hit
        ? '<span class="pb-status pb-idx-hit">상품명 ' + esc(h.matchCount) + '건 발견</span>'
        : '<span class="pb-status pb-idx-miss">검색어 전체와 맞는 이름은 없었습니다</span>';
      // 스탬프 규칙 — 확인하지 않은 날짜를 올리지 않는다. 이 몰만 뒤처졌으면 그 날짜를 적는다.
      // **지우지 말 것**: 색인 몰이 같은 밤에 함께 걷히면 날짜가 전부 같다. 갈리는 경우는
      // 한 몰의 수집이 실패해 이전 회차 색인이 유지될 때뿐이다(배치 가드). 즉 이 병기는
      // 부분 실패를 이용자에게 알리는 유일한 통로다 — 화면을 깔끔하게 하려고 접으면
      // 2026-09-01 가맹점 수집 중단이 나흘간 안 알려졌던 것과 같은 침묵이 된다.
      var when = (h.collectedOn && idx.asOf && h.collectedOn !== idx.asOf)
        ? ' <span class="pb-idx-when">' + esc(h.collectedOn) + ' 수집분</span>' : "";
      var samples = (h.sampleTitles || []).length
        ? '<div class="pb-samples">' + h.sampleTitles.slice(0, 3).map(function (t) {
            return '<span>' + esc(t) + '</span>'; }).join("")
          + '<em>' + (h.samplePartial
              ? '검색어의 일부 낱말만 맞는 결과입니다 — 찾는 상품이 아닐 수 있습니다.'
              : '이름이 비슷한 다른 상품일 수 있습니다 — 몰에서 확인하세요.') + '</em></div>'
        : "";
      return '<li class="pb-row"><span class="pb-name">' + esc(h.name) + when + '</span>'
        + status
        + (h.searchUrl
            ? '<a class="pb-link" href="' + esc(h.searchUrl) + '" target="_blank" rel="noopener">몰에서 보기 ↗</a>'
            : "")
        + samples + '</li>';
    }).join("");
    // 블록을 그릴지는 platformCount·notice 로만 판단한다(위 가드) — items 로 판단하지 않는다.
    // **색인한 몰은 있는데 이 검색어를 담은 이름이 하나도 없는 경우가 정상**이고, 그때
    // notice 가 "색인에 없다는 뜻이지, 그 몰에 없다는 확정은 아닙니다"를 말한다.
    // 다만 목록 자체는 행이 있을 때만 만든다 — 빈 <ul> 은 경계선만 남긴다.
    return '<div class="pb-index">'
      + '<div class="pb-idx-eyebrow">전일 색인</div>'
      + '<div class="pb-idx-head">' + esc(idx.notice) + '</div>'
      + (rows ? '<ul class="pb-list">' + rows + '</ul>' : "") + '</div>';
  }

  /**
   * @param bridge {lead, label, onGo} — lead 는 강조 태그를 담을 수 있는 **고정 문구**라
   *   그대로 삽입한다. 검색어 같은 외부 값은 절대 lead 에 담지 않는다(label 은 esc 를 거친다).
   */
  function renderResult(mount, data, bridge) {
    var probed = data.items.filter(function (h) { return h.status !== "not-probed"; });
    var skipped = data.items.filter(function (h) { return h.status === "not-probed"; });

    function row(h) {
      var L = LABEL[h.status] || LABEL.unknown;
      var badge = h.reason ? reasonBadge(h.reason) : null;
      var why = badge ? ' <span class="pb-reason">(' + esc(badge) + ')</span>' : "";
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

    var idxHtml = indexBlock(data.index);

    mount.innerHTML =
      '<div class="probe-result">'
      + '<div class="pb-head">' + esc(data.notice) + '</div>'
      + '<ul class="pb-list">' + probed.map(row).join("") + '</ul>'
      // 실시간 목록 **아래**, 확인하지 않은 곳 **위**. 색인은 실시간의 보완이지 대체가 아니다.
      + idxHtml
      // 다리는 두 층 바로 뒤 — 결과를 다 읽은 자리에서 다음 행선지를 준다.
      // 접힌 섹션(확인하지 않은 곳)은 부차적 공개라 그 뒤로 민다.
      + bridgeBlock(bridge)
      + (skipped.length
          // 곳 수는 서버가 센 값을 쓴다. 여기서 다시 세면 계약이 바뀔 때 헤드라인과 조용히
          // 어긋난다(2026-08-27 normKind 와 같은 유형).
          ? '<details class="pb-more"><summary>확인하지 않은 나머지 '
            + (data.notProbedCount != null ? data.notProbedCount : skipped.length)
            + '곳 — 이유와 검색 링크</summary>'
            + whyBlock(skipped)
            + '<ul class="pb-list">' + skipped.map(row).join("") + '</ul></details>'
          : "")
      // 각주는 맨 아래에 한 번만 둔다 — 색인 층까지 덮어야 하므로, 색인이 있으면 두 층을
      // 각각 무엇으로 읽어야 하는지 밝힌다. 결제 가능 여부 단서는 어느 층에도 똑같이 걸린다.
      + '<p class="pb-foot">확인 시각 ' + esc(data.checkedAt) + ' · '
      + (idxHtml
          // "찾은 것입니다"라고 쓰면 아무것도 못 찾은 회차에서 각주가 거짓이 된다.
          ? '위 목록은 각 몰의 검색 결과를 그 자리에서 읽은 것이고, 전일 색인은 미리 모아 둔 상품명에서 찾아본 결과입니다. '
          : '각 몰의 검색 결과를 그 자리에서 읽은 것입니다. ')
      + '<b>검색된다고 해서 온누리상품권으로 결제된다는 뜻은 아닙니다</b> — 상품 상세와 결제 수단은 몰에서 확인하세요.</p>'
      + '</div>';
    var bb = mount.querySelector("#probeBridge");
    if (bb && bridge && bridge.onGo) bb.onclick = function () { bridge.onGo(); };
  }

  /** 확인하지 않은 곳들을 사유별로 묶어 설명한다. 곳 수는 넘겨받은 목록에서 센다. */
  function whyBlock(skipped) {
    var groups = {};
    skipped.forEach(function (h) {
      var r = h.reason || "not-a-probe-target";
      (groups[r] = groups[r] || []).push(h.name);
    });
    // 곳 수가 많은 사유부터가 아니라 **성격이 다른 순서**로 — 허락(robots) → 구조(범위 선행)
    // → 기술(정적 조회 불가). 셋은 우리가 열 수 없는 이유가 서로 다르다.
    var order = ["robots-blocked", "scope-first", "no-static-search"];
    var keys = order.filter(function (k) { return groups[k]; })
      .concat(Object.keys(groups).filter(function (k) { return order.indexOf(k) === -1; }));
    if (!keys.length) return "";
    return '<div class="pb-why-list">' + keys.map(function (k) {
      return '<p><b>' + groups[k].length + '곳</b> · ' + esc(reasonText(k)) + '</p>';
    }).join("") + '</div>';
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
