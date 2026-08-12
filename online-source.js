// 온라인 플랫폼 목록 데이터 소스 (online.html·terms.html 공용).
// 목록이 백엔드 DB로 이관되어 매일 배치로 갱신된다. 프론트는 가맹점(merchants.html)과 동일한
// "API 우선 + JSON 폴백" 이중소스로 읽는다. 백엔드 미기동·장애 시 data/online_platforms.json으로 폴백.
//
// 경계면 원칙: API 응답(키가 camelCase일 수 있음)과 JSON 폴백(snake_case)을 "하나의 내부 형태"
// (snake_case — 기존 소비 코드가 쓰던 형태)로 정규화하는 어댑터를 여기 한곳에 둔다. 소비 코드는
// 소스를 구분하지 않는다. 정규화가 두 페이지에 흩어지면 한쪽만 바뀌어도 조용히 다른 결과가 난다.
(function () {
  "use strict";
  var CFG = window.ONNURI_CONFIG || {};
  // API_BASE 규칙은 merchants.html과 동일 — 로컬은 8080, 그 외는 배포 도메인.
  var API_BASE = CFG.apiBase || (function () {
    var h = location.hostname;
    if (h === "localhost" || h === "127.0.0.1" || h === "") return "http://localhost:8080/api";
    return "https://api.koscomlabor.cloud/api";
  })();

  // camelCase(API) 또는 snake_case(JSON) 중 있는 값을 집는다. snake를 우선(폴백 원형 보존).
  function pick(o, snake, camel) {
    if (o[snake] !== undefined && o[snake] !== null) return o[snake];
    if (o[camel] !== undefined && o[camel] !== null) return o[camel];
    return undefined;
  }
  // 한 항목을 내부 형태(snake_case)로 정규화. API·JSON 어느 쪽이 와도 같은 형태를 낸다.
  function normItem(raw) {
    return {
      id: raw.id,
      no: raw.no != null ? raw.no : null,
      kind: raw.kind,
      name: raw.name,
      summary: raw.summary || "",
      note: raw.note || "",
      url: raw.url,
      region_limited: !!pick(raw, "region_limited", "regionLimited"),
      regions: raw.regions || [],
      source_url: pick(raw, "source_url", "sourceUrl") || "",
      collected_on: pick(raw, "collected_on", "collectedOn") || "",
      status: raw.status || "active"
    };
  }
  function normPayload(d) {
    d = d || {};
    var meta = d.meta || {};
    return {
      meta: {
        collected_on: pick(meta, "collected_on", "collectedOn") || "",
        source: meta.source || "",
        source_url: pick(meta, "source_url", "sourceUrl") || ""
      },
      items: (d.items || []).map(normItem)
    };
  }

  function bust() { return CFG.dataVersion ? "?v=" + encodeURIComponent(CFG.dataVersion) : ""; }

  function fetchJson() {
    return fetch("data/online_platforms.json" + bust()).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(normPayload);
  }
  function fetchApi() {
    return fetch(API_BASE + "/online/platforms").then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(normPayload);
  }

  // dataMode(auto/api/json)에 따라 소스 결정 후, 정규화된 {meta, items}를 resolve.
  //  - json : 항상 JSON (프로브 지연 없음)
  //  - api  : 항상 API (실패 시 reject — 소비 페이지가 오류 표시)
  //  - auto : API를 짧게 시도, probeTimeoutMs 내 성공하면 API·아니면 JSON 폴백
  function load() {
    var want = CFG.dataMode || "auto";
    if (want === "json") return fetchJson();
    if (want === "api") return fetchApi();
    return new Promise(function (resolve, reject) {
      var settled = false, to = CFG.probeTimeoutMs || 2500;
      function fallback() {
        if (settled) return; settled = true;
        fetchJson().then(resolve, reject);
      }
      var timer = setTimeout(fallback, to); // API가 느리면(초과) JSON으로
      fetchApi().then(function (norm) {
        if (settled) return; settled = true; clearTimeout(timer);
        resolve(norm);
      }, function () { clearTimeout(timer); fallback(); }); // API 실패 → JSON
    });
  }

  window.OnnuriOnlineSource = { load: load, API_BASE: API_BASE };
})();
