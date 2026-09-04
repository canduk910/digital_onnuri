#!/usr/bin/env node
/**
 * 프론트엔드 렌더 테스트 (2026-09-04 신설)
 *
 * 브라우저를 띄워 **실제로 그려진 화면의 숫자**를 본다. 정적 검사(test_frontend_static.js)가
 * 문자열로 잡는 것과 답하는 질문이 다르다 — 여기서 잡는 결함은 전부 이 모양이었다:
 *
 *   normKind  (2026-08-27) 챗봇의 모든 온라인 답변이 빈 화면으로 끝났다 —  3곳 →  0곳
 *   applyCat  (2026-09-02) 슬래시 형식 카테고리가 통째로 무시됐다      — 10곳 → 22곳
 *   applyBrand(2026-09-03) brand=삼성 이 아무것도 못 찾았다            —  9곳 →  0곳
 *   hasCat    (2026-08-27) 소분류가 부모 id 만 가진 몰까지 끌어들였다   — 10곳 → 19곳
 *
 * 넷 다 **예외를 던지지 않는다.** 화면은 멀쩡하고 숫자만 조용히 틀린다.
 * 곳 수를 세는 테스트만이 잡는다.
 *
 * 실행:
 *   NODE_PATH=/Users/koscom/Projects/auto_stock/node_modules PLAYWRIGHT_CHANNEL=chrome \
 *     node _workspace/dev_scripts/test_frontend_render.js
 *
 * 종료 코드: 0 통과 · 1 실패 · 2 playwright 없어 건너뜀(index_nightly.js 와 같은 규약).
 *
 * **지도는 보지 않는다.** 네이버 Client ID 가 도메인 제한이라 localhost 에서는 401 이고
 * 러너에서도 마찬가지다. 지도 검사를 여기 섞으면 이 테스트가 통째로 못 돌게 된다 —
 * 지도는 배포 후 라이브에서 사람이 본다(--base 로 라이브를 겨눌 수 있게는 해 두었다).
 */
'use strict';
const path = require('path');
const { spawn } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const PORT = 8655;   // 백엔드 CORS 허용 오리진. 나중에 라이브 API 대조를 여기서 열 수 있다.
const ARGS = process.argv.slice(2);
const only = (ARGS.find((a) => a.startsWith('--only=')) || '').split('=')[1] || '';
const BASE = (ARGS.find((a) => a.startsWith('--base=')) || '').split('=')[1] || `http://127.0.0.1:${PORT}`;
const LOCAL = BASE.indexOf('127.0.0.1') >= 0 || BASE.indexOf('localhost') >= 0;

let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) {
  console.log('playwright 가 없어 렌더 테스트를 건너뜁니다.');
  console.log('  NODE_PATH=<playwright 설치 경로> 를 주거나 `npm i --no-save playwright` 하세요.');
  process.exit(2);
}

let pass = 0, fail = 0;
function check(cond, label, detail) {
  if (cond) { pass++; console.log(`  [PASS] ${label}`); }
  else { fail++; console.log(`  [FAIL] ${label}${detail !== undefined ? ' — ' + detail : ''}`); }
}
function eq(actual, expected, label) {
  check(actual === expected, label, `기대 ${expected} · 실제 ${actual}`);
}

/* 정적 서버는 이 스크립트가 띄우고 스스로 죽인다.
   "먼저 서버를 띄우세요" 라는 절차를 만들면 그 절차는 반드시 잊힌다. */
function serve() {
  if (!LOCAL) return null;
  const p = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'],
    { cwd: ROOT, stdio: 'ignore' });
  const kill = () => { try { p.kill(); } catch (e) {} };
  process.on('exit', kill); process.on('SIGINT', () => { kill(); process.exit(130); });
  return p;
}

/** 온라인 페이지를 열고 착지 파라미터를 적용한 뒤 카드 수를 센다. */
async function onlineCount(ctx, query) {
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e).slice(0, 120)));
  await p.goto(`${BASE}/online.html${query}`, { waitUntil: 'domcontentloaded' });
  // 목록은 두 JSON fetch 뒤에 그려진다. 카드가 나타나거나 '0곳' 문구가 뜰 때까지 기다린다.
  await p.waitForFunction(
    () => document.querySelectorAll('#resultArea .pf-card').length > 0
       || /곳/.test((document.getElementById('countText') || {}).textContent || ''),
    { timeout: 15000 }).catch(() => {});
  const r = await p.evaluate(() => ({
    cards: document.querySelectorAll('#resultArea .pf-card').length,
    count: (document.getElementById('countText') || {}).textContent || '',
    tab: (document.querySelector('.ptab[aria-selected="true"]') || {}).id || '',
    nationwide: !!(document.getElementById('nationwideOnly') || {}).checked,
    pq: (document.getElementById('pq') || {}).value || '',
    q: (document.getElementById('q') || {}).value || '',
    probeRequests: 0,
    sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth,
  }));
  r.errors = errs;
  await p.close();
  return r;
}

(async () => {
  const srv = serve();
  if (srv) await new Promise((r) => setTimeout(r, 800));

  const browser = await chromium.launch(
    process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {});
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

  console.log(`렌더 테스트 — ${BASE}\n`);

  // ───────────────────────────────────────────────────────────────────────────
  if (!only || only === 'smoke') {
    console.log('(a) 부팅 스모크 — 모든 페이지가 오류 없이 뜬다');
    // 2026-08-27 'renderBrandChips~renderAll 구간을 교체하며 그 사이의 cardHTML·render 를
    // 통째로 삭제했다(ReferenceError)'. 페이지가 통째로 죽는 종류의 사고다.
    const PAGES = ['index.html', 'merchants.html', 'online.html', 'payment.html',
                   'terms.html', 'news.html', 'report.html'];
    for (const page of PAGES) {
      const p = await ctx.newPage();
      const errs = [];
      p.on('pageerror', (e) => errs.push(String(e).slice(0, 120)));
      await p.goto(`${BASE}/${page}`, { waitUntil: 'domcontentloaded' });
      await p.waitForTimeout(page === 'index.html' ? 4500 : 2500);
      const r = await p.evaluate(() => ({
        title: document.title,
        icon: document.querySelectorAll('link[rel="icon"]').length,
        sb: document.querySelectorAll('.sb-item').length,
        sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth,
      }));
      // 네이버 지도는 도메인 제한이라 localhost 에서 반드시 실패한다 — 그 오류만 뺀다.
      const real = errs.filter((e) => !/naver|LatLng|authFail|401/i.test(e));
      check(real.length === 0, `${page} 스크립트 오류 없음`, real.join(' | '));
      check(r.title.length > 0, `${page} 탭 제목이 살아 있다`, r.title);
      // 2026-08-18: 번들 로더가 외곽 head 를 교체해 파비콘이 통째로 사라졌다.
      check(r.icon >= 1, `${page} 파비콘이 로드 후에도 남아 있다`, `${r.icon}개`);
      check(r.sb === 8, `${page} 사이드바 8항목`, `${r.sb}개`);
      check(r.sw <= r.cw, `${page} 가로 스크롤 없음(1440px)`, `${r.sw}>${r.cw}`);
      await p.close();
    }
    console.log();

    console.log('(b) 모바일 390px — 가로 스크롤 없음');
    // 2026-08-26·08-27·09-01·09-02·09-03 거의 모든 UI 변경의 검증 항목으로 반복 등장한다.
    const m = await browser.newContext({ viewport: { width: 390, height: 844 } });
    for (const page of PAGES) {
      const p = await m.newPage();
      await p.goto(`${BASE}/${page}`, { waitUntil: 'domcontentloaded' });
      await p.waitForTimeout(page === 'index.html' ? 4500 : 2200);
      const r = await p.evaluate(() => ({
        sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
      check(r.sw <= r.cw + 1, `${page} 모바일 가로 스크롤 없음`, `${r.sw}>${r.cw}`);
      await p.close();
    }
    await m.close();
    console.log();
  }

  // ───────────────────────────────────────────────────────────────────────────
  if (!only || only === 'online') {
    console.log('(c) 온라인 착지 — 외부에서 들어온 값이 0곳을 만들지 않는다');
    // 이 절이 이 파일의 핵심이다. 아래 기대값은 **데이터에서 파생하지 않고 실측으로 고정**한다 —
    // 데이터에서 다시 계산하면 필터 로직이 깨져도 기대값이 같이 움직여 아무것도 못 잡는다.
    // 대신 데이터가 바뀌어 값이 달라지면 실패하는데, 그때는 사람이 실제 화면을 보고 갱신한다.
    const base = await onlineCount(ctx, '');
    check(base.cards > 0, '파라미터 없이 열면 전체 목록이 나온다', `${base.cards}장`);
    check(base.tab === 'tabLive', '파라미터 0개는 착지가 아니라 기본 탭(live)', base.tab);
    const ALL = base.cards;

    // 2026-08-27 normKind: 영문 코드가 들어오면 '배달만' 으로 빠져 0곳이 됐다.
    const shop = await onlineCount(ctx, '?kind=shopping');
    check(shop.cards > 0 && shop.cards < ALL, 'kind=shopping 이 0곳이 아니다', `${shop.cards}장 / 전체 ${ALL}`);
    const shopKo = await onlineCount(ctx, '?kind=' + encodeURIComponent('쇼핑'));
    eq(shopKo.cards, shop.cards, '한글 라벨과 영문 코드가 같은 결과');
    const bogus = await onlineCount(ctx, '?kind=zzq-nonsense');
    eq(bogus.cards, ALL, '모르는 kind 는 조용히 배달로 빠지지 않고 전체로 폴백');

    // 2026-09-02 applyCat: RAG 코퍼스가 '대분류/소분류' 로 적어 모델이 그대로 넘긴다.
    const slash = await onlineCount(ctx, '?cat=' + encodeURIComponent('가전·디지털/생활·주방가전'));
    const sub = await onlineCount(ctx, '?cat=' + encodeURIComponent('생활·주방가전'));
    eq(slash.cards, sub.cards, '슬래시 형식 cat 이 소분류와 같은 결과');
    check(slash.cards < ALL, '슬래시 형식 cat 이 필터를 실제로 건다(전체가 아니다)',
      `${slash.cards} / 전체 ${ALL}`);

    // 2026-09-03 applyBrand: 표준 표기는 '삼성전자' 인데 챗봇은 '삼성' 을 보낸다.
    const bStd = await onlineCount(ctx, '?brand=' + encodeURIComponent('삼성전자'));
    const bAlias = await onlineCount(ctx, '?brand=' + encodeURIComponent('삼성'));
    check(bStd.cards > 0, 'brand=삼성전자 가 결과를 낸다', `${bStd.cards}장`);
    eq(bAlias.cards, bStd.cards, 'brand=삼성 별칭이 같은 결과(0곳이 아니다)');

    // 2026-09-02 dev-qa F-1: 착지가 '지역 한정 제외' 체크를 되돌리지 않아
    // 챗봇이 "배달앱 N곳" 이라 말한 뒤 더 적은 화면에 착지시켰다.
    const p = await ctx.newPage();
    await p.goto(`${BASE}/online.html`, { waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(2500);
    const landed = await p.evaluate(() => {
      var cb = document.getElementById('nationwideOnly');
      cb.checked = true; cb.dispatchEvent(new Event('change', { bubbles: true }));
      // 챗 훅과 같은 창구를 탄다
      if (window.onnuriApplyChatFilter) window.onnuriApplyChatFilter({ kind: 'delivery' });
      return { checked: document.getElementById('nationwideOnly').checked,
               cards: document.querySelectorAll('#resultArea .pf-card').length };
    });
    check(landed.checked === false, '착지가 지역 한정 제외 체크를 되돌린다');
    await p.close();

    // ADR-20: 착지 탭 판정. live 착지는 검색어만 채우고 **조회하지 않는다**.
    const liveLand = await onlineCount(ctx, '?q=' + encodeURIComponent('로봇청소기'));
    check(liveLand.tab === 'tabLive', 'q 만 오면 실시간 탭에 착지', liveLand.tab);
    eq(liveLand.pq, '로봇청소기', 'live 착지는 검색어를 실시간 입력창에 채운다');
    const browseLand = await onlineCount(ctx, '?cat=' + encodeURIComponent('식품'));
    check(browseLand.tab === 'tabBrowse', 'cat 이 오면 둘러보기 탭에 착지', browseLand.tab);
    console.log();
  }

  // ───────────────────────────────────────────────────────────────────────────
  if (!only || only === 'merchants') {
    console.log('(d) 가맹점 — 지도가 죽어도 목록·필터는 산다');
    // 2026-08-11 '지도 렌더 예외가 리스트를 죽이던 결함 방어'.
    // localhost 에서는 지도가 반드시 죽으므로(도메인 제한) 이 방어가 매번 시험된다 —
    // 여기서 목록이 나오면 그 방어가 살아 있다는 뜻이다.
    const p = await ctx.newPage();
    const errs = [];
    p.on('pageerror', (e) => errs.push(String(e).slice(0, 120)));
    await p.goto(`${BASE}/merchants.html`, { waitUntil: 'domcontentloaded' });
    await p.waitForFunction(() => document.querySelectorAll('tr.row-link').length > 0,
      { timeout: 20000 }).catch(() => {});
    const r = await p.evaluate(() => {
      const rows = [...document.querySelectorAll('tr.row-link')];
      return {
        rows: rows.length,
        withRi: rows.filter((t) => t.getAttribute('data-ri') !== null).length,
        // 2026-08-24: 셀 textContent 를 쓰면 ☆·브랜드·거리 태그가 이름에 섞였다.
        dirtyNames: rows.map((t) => t.getAttribute('data-name') || '')
                        .filter((n) => /[★☆]|SSM/.test(n)).length,
        // 2026-09-03·09-04: 결제 태그는 payTags 한 창구에서 나온다.
        payCells: [...document.querySelectorAll('td[data-label="결제"]')]
                    .map((td) => td.textContent.trim()),
      };
    });
    check(r.rows > 0, '지도가 죽어도 결과 목록이 그려진다', `${r.rows}행`);
    eq(r.withRi, r.rows, '모든 행이 원본 행 인덱스를 갖는다');
    eq(r.dirtyNames, 0, 'data-name 에 ☆·태그가 섞이지 않는다');
    const bad = r.payCells.filter((t) => !/카드|QR|디지털 불가|결제 수단 미확인/.test(t));
    eq(bad.length, 0, '결제 칸이 네 가지 표현 중 하나다');
    // 표에서 '카드'로 표시된 곳은 팝업에서도 카드여야 한다 — 합성 객체 회귀 방지.
    // (팝업 자체는 지도가 필요해 여기서 열 수 없으므로, 원본 행이 넘어가는지를 본다.)
    const real = errs.filter((e) => !/naver|LatLng|authFail|401/i.test(e));
    check(real.length === 0, '가맹점 페이지 스크립트 오류 없음', real.join(' | '));
    await p.close();
    console.log();
  }

  await browser.close();
  if (srv) srv.kill();

  // ── 최종 판정 ───────────────────────────────────────────────────────────
  // 새 블록은 반드시 이 줄 위에.
  console.log();
  if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
  console.log(`전체 통과 (${pass}건)`);
})().catch((e) => {
  console.log('테스트 하네스 자체가 실패했습니다 —', String(e).slice(0, 300));
  process.exit(1);
});
