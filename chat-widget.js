/* ============================================================
   chat-widget.js — 온누리 가이드 챗봇 위젯 (ADR-12)
   3페이지 공통. 서버 POST /api/chat (SSE)와 통신한다.
   - 렌더: marked(마크다운) + DOMPurify(XSS 방어 — LLM 출력은 신뢰 불가 입력) + mermaid
     세 라이브러리는 첫 오픈 시 CDN에서 지연 로드(페이지 초기 로드 무영향).
   - 이벤트 계약: token/action/done/error — backend ChatContractTest와 한 변경 단위.
   - 이력: sessionStorage(페이지 이동에도 대화 유지, 탭 닫으면 소멸). 서버 저장 없음.
   ============================================================ */
(function () {
  "use strict";
  var CFG = window.ONNURI_CONFIG || {};
  if (CFG.chatEnabled === false) return;

  var API_BASE = (function () {
    if (CFG.apiBase) return CFG.apiBase.replace(/\/$/, "");
    var h = location.hostname;
    if (h === "localhost" || h === "127.0.0.1") return "http://localhost:8080/api";
    return "https://api.koscomlabor.cloud/api";
  })();

  var HIST_KEY = "onnuri_chat_hist";
  var MAX_TURNS = 10;
  var CDN = {
    marked: "https://cdn.jsdelivr.net/npm/marked@12/marked.min.js",
    purify: "https://cdn.jsdelivr.net/npm/dompurify@3/dist/purify.min.js",
    mermaid: "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"
  };
  var HINTS = [
    "잔액 환불은 어떻게 하나요?",
    "노량진동에 GS25 가맹점 있나요?",
    "카드형 결제 흐름을 그려서 설명해줘",
    "온라인에서 애플 제품 살 수 있나요?"
  ];

  var history = [];       // {role, content}
  var busy = false;
  var libsReady = null;   // Promise
  var mermaidSeq = 0;
  var AUTOMAP_KEY = "onnuri_chat_automap";   // 지도 바로 이동 토글 (기본 ON)
  var pendingNav = null;                      // 자동 이동 예약(답변 완료 후 실행)
  function autoMapOn() { return localStorage.getItem(AUTOMAP_KEY) !== "0"; }

  // ---- 지연 로드 ----
  function loadScript(src) {
    return new Promise(function (res, rej) {
      var s = document.createElement("script");
      s.src = src; s.onload = res; s.onerror = function () { rej(new Error(src)); };
      document.head.appendChild(s);
    });
  }
  function ensureLibs() {
    if (libsReady) return libsReady;
    libsReady = Promise.all([loadScript(CDN.marked), loadScript(CDN.purify), loadScript(CDN.mermaid)])
      .then(function () {
        window.mermaid.initialize({
          startOnLoad: false, securityLevel: "strict", theme: "base",
          // 파싱 실패 시 body에 에러 SVG를 삽입하는 기본 동작 차단(스트리밍 중 미완성
          // 코드 렌더 시 페이지가 "Syntax error" 블록으로 도배되던 결함 — 2026-08-11)
          suppressErrorRendering: true,
          // htmlLabels:false — 라벨을 SVG <text>로 렌더. HTML(foreignObject) 라벨은
          // DOMPurify SVG 새니타이즈에서 제거되어 노드가 빈 상자로 보인다.
          flowchart: { htmlLabels: false },
          themeVariables: {
            primaryColor: "#FEF3EC", primaryBorderColor: "#F26B1D", primaryTextColor: "#0B0C0E",
            lineColor: "#585D64", secondaryColor: "#F6F6F7", tertiaryColor: "#EFF0F2",
            fontFamily: "Pretendard, sans-serif", fontSize: "13px"
          }
        });
      })
      .catch(function () { libsReady = null; }); // 실패 시 다음 오픈에서 재시도(폴백: 플레인 텍스트)
    return libsReady;
  }

  // ---- 마크다운 렌더 ----
  function renderMd(el, text, skipMermaid) {
    if (window.marked && window.DOMPurify) {
      var html = window.marked.parse(text, { gfm: true, breaks: true });
      el.innerHTML = window.DOMPurify.sanitize(html);
      el.classList.add("cw-md");
      if (!skipMermaid) renderMermaid(el);   // 스트리밍 중엔 생략 — 완성본에서만 렌더
    } else {
      el.textContent = text;
    }
  }
  function renderMermaid(el) {
    if (!window.mermaid) return;
    Array.prototype.forEach.call(el.querySelectorAll("pre > code.language-mermaid"), function (code) {
      var pre = code.parentElement;
      var src = code.textContent;
      var box = document.createElement("div");
      box.className = "cw-mermaid";
      pre.replaceWith(box);
      var mid = "cwm" + (++mermaidSeq);
      window.mermaid.render(mid, src).then(function (r) {
        box.innerHTML = window.DOMPurify ? window.DOMPurify.sanitize(r.svg, { USE_PROFILES: { svg: true, svgFilters: true } }) : r.svg;
      }).catch(function () {
        var p = document.createElement("pre"); p.textContent = src; box.replaceWith(p); // 파싱 실패 시 원문 유지
        ["#" + mid, "#d" + mid].forEach(function (sel) {   // mermaid가 body에 남긴 측정·에러 노드 제거
          var n = document.querySelector(sel); if (n && !el.contains(n)) n.remove();
        });
      });
    });
  }

  // ---- DOM 구성 ----
  var fab, panel, body, input, sendBtn;
  function build() {
    fab = document.createElement("button");
    fab.className = "cw-fab"; fab.type = "button";
    fab.setAttribute("aria-label", "온누리 가이드 챗봇 열기");
    fab.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg><span class="cw-fab-x" aria-hidden="true">×</span>';
    panel = document.createElement("div");
    panel.className = "cw-panel";
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", "온누리 가이드 챗봇");
    panel.innerHTML =
      '<div class="cw-head"><span class="cw-head-dot"></span>' +
      '<span class="cw-head-tit">온누리 가이드 챗</span>' +
      '<span class="cw-head-sub">공식 출처 기반 안내<br>AI 답변 — 결제 전 확인 권장</span>' +
      '<button class="cw-close" type="button" aria-label="닫기">×</button></div>' +
      '<div class="cw-body"></div>' +
      '<div class="cw-opts"><label class="cw-switch"><input type="checkbox" id="cwAutoMap"><span></span>지도 바로 이동</label>' +
      '<span class="cw-opts-hint">위치 문의 시 확인 없이 지도·목록을 이동합니다</span></div>' +
      '<div class="cw-input"><textarea rows="1" placeholder="예: 환불 어떻게 하나요?" aria-label="질문 입력"></textarea>' +
      '<button class="cw-send" type="button" aria-label="보내기"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg></button></div>' +
      '<div class="cw-disclaim">AI가 생성한 답변으로 오류가 있을 수 있습니다 · 개인정보를 입력하지 마세요</div>';
    document.body.appendChild(fab);
    document.body.appendChild(panel);
    body = panel.querySelector(".cw-body");
    input = panel.querySelector("textarea");
    sendBtn = panel.querySelector(".cw-send");

    fab.addEventListener("click", toggle);
    panel.querySelector(".cw-close").addEventListener("click", toggle);
    var am = panel.querySelector("#cwAutoMap");
    am.checked = autoMapOn();
    am.addEventListener("change", function () {
      localStorage.setItem(AUTOMAP_KEY, am.checked ? "1" : "0");
    });
    sendBtn.addEventListener("click", submit);
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); }
    });
    input.addEventListener("input", function () {
      input.style.height = "auto";
      input.style.height = Math.min(input.scrollHeight, 96) + "px";
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && panel.classList.contains("open")) toggle();
    });
    restore();
  }

  function toggle() {
    var open = panel.classList.toggle("open");
    fab.classList.toggle("open", open);
    fab.setAttribute("aria-label", open ? "챗봇 닫기" : "온누리 가이드 챗봇 열기");
    if (open) { ensureLibs().then(rerenderAll); input.focus(); scrollEnd(); }
  }

  // ---- 이력 ----
  function restore() {
    try { history = JSON.parse(sessionStorage.getItem(HIST_KEY) || "[]"); } catch (e) { history = []; }
    if (history.length) {
      history.forEach(function (m) {
        var d = appendMsg(m.role === "user" ? "user" : "bot", m.content);
        if (m.role !== "user") d.classList.add("cw-final");   // 복원된 AI 답변에도 면책 표기
      });
    } else {
      greet();
    }
  }
  function saveHist() {
    try { sessionStorage.setItem(HIST_KEY, JSON.stringify(history.slice(-MAX_TURNS))); } catch (e) {}
  }
  function greet() {
    var el = appendMsg("bot", "안녕하세요! **디지털온누리상품권** 사용처·결제 방법·제도를 안내하는 챗봇입니다. 무엇이 궁금하세요?");
    var hints = document.createElement("div");
    hints.className = "cw-hints";
    HINTS.forEach(function (h) {
      var b = document.createElement("button");
      b.type = "button"; b.textContent = h;
      b.addEventListener("click", function () { input.value = h; submit(); });
      hints.appendChild(b);
    });
    body.appendChild(hints);
  }

  // ---- 메시지 DOM ----
  function appendMsg(kind, text) {
    var d = document.createElement("div");
    d.className = "cw-msg " + kind;
    if (kind === "bot") { d.dataset.md = text; renderMd(d, text); }
    else d.textContent = text;
    body.appendChild(d);
    scrollEnd();
    return d;
  }
  function rerenderAll() {
    Array.prototype.forEach.call(body.querySelectorAll(".cw-msg.bot[data-md]"), function (d) {
      if (!d.classList.contains("cw-md")) renderMd(d, d.dataset.md);
    });
  }
  function appendNote(text, isErr) {
    var d = document.createElement("div");
    d.className = "cw-note" + (isErr ? " err" : "");
    d.textContent = text;
    body.appendChild(d);
    scrollEnd();
  }
  // 액션 분기(2026-08-11 지도 바로 이동): 토글 ON이고 검색 페이지 액션이면 —
  // 같은 페이지: 확인 없이 즉시 필터·지도 이동 / 다른 페이지: 답변 완료 후 자동 이동.
  // 토글 OFF 또는 가이드 액션: 기존 확인 카드.
  function handleAction(a) {
    var isSearch = a.page === "merchants" || a.page === "online";
    if (!isSearch || !autoMapOn()) { appendAction(a); return; }
    var target = a.page === "online" ? "online.html" : "merchants.html";
    var onTarget = location.pathname.indexOf(target) !== -1;
    if (onTarget && typeof window.onnuriApplyChatFilter === "function") {
      window.onnuriApplyChatFilter(a.params || {});
      appendNote("지도·목록을 이동했습니다 — " + (a.label || ""));
      return;
    }
    pendingNav = a;   // 스트리밍 중 이탈하면 답이 끊기므로 done에서 이동
    appendNote("답변 완료 후 " + (a.page === "online" ? "온라인 사용처" : "가맹점 찾기") + " 화면으로 이동합니다…");
  }

  function appendAction(a) {
    var card = document.createElement("div");
    card.className = "cw-action";
    var label = document.createElement("div");
    label.className = "cw-action-label";
    label.textContent = a.label || "페이지로 이동";
    var btn = document.createElement("button");
    btn.type = "button"; btn.textContent = "이동 →";
    btn.addEventListener("click", function () { location.href = actionUrl(a); });
    card.appendChild(label); card.appendChild(btn);
    body.appendChild(card);
    scrollEnd();
  }
  function actionUrl(a) {
    var params = a.params || {};
    if (a.page === "guide") {
      return "index.html" + (params.hash ? "#" + params.hash : "");
    }
    // 검색 조건을 주소창에 노출하지 않는다(2026-08-11) — sessionStorage 핸드오프.
    // 착지 페이지가 onnuri_nav_filter를 읽고 즉시 삭제한다(같은 탭 1회성).
    var page = a.page === "online" ? "online.html" : "merchants.html";
    try { sessionStorage.setItem("onnuri_nav_filter", JSON.stringify({ page: a.page, params: params })); } catch (e) {}
    return page;
  }
  function scrollEnd() { body.scrollTop = body.scrollHeight; }

  // ---- 전송 · SSE 소비 ----
  function submit() {
    var q = (input.value || "").trim();
    if (!q || busy) return;
    input.value = ""; input.style.height = "auto";
    busy = true; sendBtn.disabled = true;
    var hint = body.querySelector(".cw-hints"); if (hint) hint.remove();
    appendMsg("user", q);
    history.push({ role: "user", content: q });
    saveHist();

    var botEl = appendMsg("bot", "");
    botEl.classList.add("cw-typing");
    var acc = "";

    ensureLibs().then(function () {
      return fetch(API_BASE + "/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: history.slice(-MAX_TURNS) })
      });
    }).then(function (res) {
      if (!res.ok || !res.body) throw new Error("HTTP " + res.status);
      var reader = res.body.getReader();
      var dec = new TextDecoder();
      var buf = "";
      function pump() {
        return reader.read().then(function (r) {
          if (r.done) return finish(null);
          buf += dec.decode(r.value, { stream: true });
          var events = buf.split("\n\n");
          buf = events.pop();
          events.forEach(handleEvent);
          return pump();
        });
      }
      function handleEvent(block) {
        var ev = "message", data = "";
        block.split("\n").forEach(function (ln) {
          if (ln.indexOf("event:") === 0) ev = ln.slice(6).trim();
          else if (ln.indexOf("data:") === 0) data += ln.slice(5).trim();
        });
        if (!data) return;
        var d; try { d = JSON.parse(data); } catch (e) { return; }
        if (ev === "token") {
          acc += d.text || "";
          botEl.dataset.md = acc;
          renderMd(botEl, acc, true);   // 미완성 mermaid 시도 금지
          scrollEnd();
        } else if (ev === "action") {
          handleAction(d);
        } else if (ev === "error") {
          finish(d.message || "오류가 발생했습니다.");
        } else if (ev === "done") {
          finish(null);
        }
      }
      function finish(errMsg) {
        try { reader.cancel(); } catch (e) {}
        done(errMsg);
      }
      return pump();
    }).catch(function () {
      done("서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    });

    function done(errMsg) {
      if (!busy) return;
      busy = false; sendBtn.disabled = false;
      botEl.classList.remove("cw-typing");
      if (acc) botEl.classList.add("cw-final");   // AI 답변 면책 표기(CSS ::after)
      if (errMsg) {
        if (!acc) botEl.remove();
        appendNote(errMsg, true);
      }
      if (acc) {
        renderMd(botEl, acc);   // 최종 마크다운 재파싱(mermaid 포함)
        history.push({ role: "assistant", content: acc });
        saveHist();
      }
      scrollEnd();
      if (pendingNav && !errMsg) {
        var nav = pendingNav; pendingNav = null;
        setTimeout(function () { location.href = actionUrl(nav); }, 600);   // 이력은 저장됨 — 새 페이지에서 대화 복원
      } else {
        pendingNav = null;
      }
    }
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", build);
  else build();
})();
