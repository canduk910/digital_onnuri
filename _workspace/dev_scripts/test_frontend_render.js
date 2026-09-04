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
 * **지도도 본다 — 단, 포트 8655 에서만.**
 * 이 저장소는 오랫동안 "네이버 Client ID 가 도메인 제한이라 로컬에서는 지도가 안 뜬다"고
 * 알고 있었는데 **틀렸다**(2026-09-04 실측). 8655 에서는 인증이 통과하고 타일이 뜨며
 * 클러스터 → 개별 마커 → 인포윈도우 → 파노라마까지 전부 동작한다. 다른 포트는 401 이다 —
 * 허용 도메인이 포트까지 포함해 등록돼 있는 것으로 보인다. 그래서 PORT 를 바꾸지 마라.
 *
 * 앞서 "마커가 안 보인다"고 본 것은 `.cluster` 를 세지 않은 탓이었다. drawPins 는 마커를
 * 지도에 직접 붙이지 않고 MarkerClustering 에 넘기므로, 초기 fit 줌에서는 `.cluster` 만
 * 있고 `.pin` 은 0 이다. **클러스터를 한 번 클릭해야 개별 마커가 나온다.**
 */
'use strict';
const path = require('path');
const { spawn } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
// 8655 는 두 가지 이유로 고정이다 — 백엔드 CORS 허용 오리진이자, **네이버 지도 허용 도메인**.
// 다른 포트로 바꾸면 지도 절이 통째로 401 이 된다(2026-09-04 실측).
const PORT = 8655;
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

  // ───────────────────────────────────────────────────────────────────────────
  if (!only || only === 'map') {
    console.log('(e) 지도 — 클러스터에서 마커, 마커에서 팝업까지');
    /* **로컬 전용이다.** 원격 도메인을 겨누면 마커 클릭이 네이버 SDK 의 오버레이 레이어에
       막힌다(elementFromPoint 가 마커가 아니라 무클래스 DIV 를 준다 — 라이브 실측).
       뷰포트 확대·스크롤·force 클릭·좌표 클릭·합성 이벤트를 전부 시도했으나 닿지 못했다.
       제품이 깨졌다는 증거는 아니고 하네스가 라이브 레이아웃에서 마커에 닿지 못하는 것인데,
       **그 구분이 안 되는 검사를 통과로 적으면 거짓 신호**가 되므로 아예 건너뛰고 밝힌다.
       라이브 지도 동작은 사람이 브라우저에서 확인한다. */
    if (!LOCAL) {
      console.log('  [SKIP] 원격 base 에서는 마커 클릭이 SDK 오버레이에 막혀 수행하지 않습니다.');
      console.log('         지도 검사는 로컬(포트 8655)에서 돌리고, 라이브는 사람이 눈으로 확인하세요.');
      console.log();
    } else {
    // 이 절이 C-4(2026-09-04 사용자 결정)의 계약을 지킨다:
    //   "그룹 팝업에서는 개별 가맹점을 조회할 때만 최근 본에 등록."
    // 그룹 팝업은 아무것도 기록하지 않고, 항목을 눌렀을 때만 그 한 곳이 남아야 한다.
    const p = await ctx.newPage();
    const errs = [];
    p.on('pageerror', (e) => errs.push(String(e).slice(0, 120)));
    // 개포동 — 동일좌표 그룹이 실제로 있는 지역(실측으로 고른 값이다).
    const q = '?region=' + encodeURIComponent('서울')
            + '&gu=' + encodeURIComponent('강남구') + '&dong=' + encodeURIComponent('개포동');
    await p.goto(`${BASE}/merchants.html${q}`, { waitUntil: 'domcontentloaded' });
    await p.waitForTimeout(9000);
    await p.evaluate(() => localStorage.removeItem('onnuri_recent'));

    // 지도를 화면 한가운데로. 라이브는 레이아웃이 달라 마커가 y=947(뷰포트 900)에 있었고
    // 좌표 클릭이 허공을 때렸다(elementFromPoint 가 null). 스크롤은 idle → 재렌더를
    // 부르므로 **여기서 한 번만** 하고 충분히 기다린 뒤 마커를 찾는다.
    await p.evaluate(() => {
      const m = document.getElementById('map');
      if (m) m.scrollIntoView({ block: 'center', behavior: 'instant' });
    });
    await p.waitForTimeout(2500);

    const auth = await p.evaluate(() => !!window.__naverAuthFail);
    check(auth === false, '네이버 지도 인증 통과(포트 8655)');
    const c0 = await p.evaluate(() => ({
      cluster: document.querySelectorAll('.cluster').length,
      pin: document.querySelectorAll('.pin').length,
      multi: document.querySelectorAll('.pin-multi').length,
      tiles: document.querySelectorAll('#map img').length }));
    // **초기 상태는 소스에 따라 다르다.** 로컬(JSON 폴백)은 클라이언트 격자라 클러스터가
    // 뜨고, 라이브(API)는 서버 집계라 이 줌에서 개별 마커가 바로 나온다. 둘 다 정상이므로
    // "클러스터가 있다"를 단언하면 라이브에서 거짓 실패가 난다 — 지도가 무언가를 그렸는지만 본다.
    check(c0.tiles > 0 && (c0.cluster + c0.pin + c0.multi) > 0,
      '초기 화면에 타일과 마커(또는 클러스터)가 있다', JSON.stringify(c0));

    // 2026-08-12: 클러스터 → 개별 마커 전환이 비동기 응답 경합으로 깨진 적이 있다.
    const cl = await p.$('.cluster');
    if (cl) { await cl.click(); await p.waitForTimeout(4000); }
    const c1 = await p.evaluate(() => ({
      pin: document.querySelectorAll('.pin').length,
      multi: document.querySelectorAll('.pin-multi').length,
      cluster: document.querySelectorAll('.cluster').length }));
    check(c1.pin + c1.multi > 0, '개별 마커가 그려진다', JSON.stringify(c1));
    // 2026-08-12: 그룹 배지가 '건물 수'가 아니라 '가맹점 수'여야 한다.
    check(c1.multi > 0, '동일좌표 그룹 마커가 존재한다(그룹 팝업 검사의 전제)', `${c1.multi}개`);

    /* 마커·팝업 클릭은 **실제 포인터**로 보낸다.
       ①`page.click()` 은 안정성 대기가 헛돌아 라이브에서 30초 타임아웃이 났다(SDK 가
         마커를 절대 배치하며 계속 미세 조정한다).
       ②합성 `MouseEvent` 는 네이버 SDK 가 좌표를 읽다 죽는다
         (`Cannot read properties of undefined (reading 'x')` — 실측).
       그래서 요소의 박스 중심 좌표로 마우스를 직접 클릭한다. */
    const clickIn = async (sel) => {
      // 스크롤은 여기서 하지 않는다 — 매 클릭마다 스크롤하면 팝업을 연 직후에도 화면이
      // 움직여 그 팝업이 닫힌다(2026-08-24 회귀와 같은 경로). 지도는 절 시작에서 한 번만
      // 화면 안으로 넣고, 그 뒤로는 좌표만 다시 잰다.
      const h = await p.$(sel); if (!h) return false;
      // `force: true` 로 안정성 대기를 건너뛴다 — SDK 가 마커를 절대 배치하며 계속 미세
      // 조정해서 기본 대기가 30초를 채우고 실패한다(라이브 실측). 좌표 클릭(mouse.click)은
      // 마커의 클릭 핸들러를 타지 못했다 — SDK 가 자기 요소의 리스너로 받기 때문이다.
      try { await h.click({ force: true, timeout: 10000 }); return true; }
      catch (e) { return false; }
    };

    if (c1.multi > 0) {
      const opened = await clickIn('.pin-multi'); await p.waitForTimeout(1500);
      check(opened, '그룹 마커를 실제로 클릭할 수 있었다(화면 안에 있다)');
      const g = await p.evaluate(() => ({
        items: document.querySelectorAll('.iwg-item').length,
        tap: document.querySelectorAll('.iwg-item.tap').length,
        scope: !!document.querySelector('.iwg-scope'),
        recent: JSON.parse(localStorage.getItem('onnuri_recent') || '[]').length }));
      check(g.items > 0, '그룹 마커가 위치 목록 팝업을 연다', `${g.items}곳`);
      eq(g.tap, g.items, '모든 항목이 누를 수 있다');
      check(g.scope, "곳 수가 현재 필터 기준임을 밝힌다('현재 조건 기준')");
      // ── C-4 의 핵심 계약 ──
      eq(g.recent, 0, '그룹 팝업 자체는 최근 본에 아무것도 기록하지 않는다');

      await clickIn('.iwg-item.tap'); await p.waitForTimeout(1300);
      const one = await p.evaluate(() => {
        const rec = JSON.parse(localStorage.getItem('onnuri_recent') || '[]');
        return { name: (document.querySelector('.iw-name') || {}).textContent || '',
                 back: (document.querySelector('.iw-back') || {}).textContent || '',
                 pay: (document.querySelector('.iw-pay') || {}).textContent || '',
                 recent: rec.length, r0: rec[0] || null };
      });
      check(one.name.length > 0, '항목을 누르면 그 한 곳의 개별 팝업이 열린다', one.name);
      eq(one.recent, 1, '그때 비로소 최근 본에 한 곳이 남는다');
      // 2026-09-04: 합성 객체를 넘기면 id·card·qr 이 비어 결제를 거짓으로 그린다.
      check(!!(one.r0 && one.r0.id), '기록에 id 가 있다(원본 행이 넘어갔다)',
        one.r0 ? JSON.stringify(one.r0.id) : 'null');
      check(!!(one.r0 && (one.r0.card === 'Y' || one.r0.card === 'N')),
        '기록에 결제 수단이 있다(합성 객체가 아니다)', one.r0 ? one.r0.card : 'null');
      check(/카드|QR|디지털 불가/.test(one.pay), '개별 팝업이 결제 수단을 말한다', one.pay);
      check(/이 위치 목록/.test(one.back), '되돌아가는 줄이 있다', one.back);

      await clickIn('.iw-back'); await p.waitForTimeout(1300);
      const backAgain = await p.evaluate(() => ({
        items: document.querySelectorAll('.iwg-item').length,
        recent: JSON.parse(localStorage.getItem('onnuri_recent') || '[]').length }));
      eq(backAgain.items, g.items, '되돌아가면 목록이 그대로 복귀한다');
      eq(backAgain.recent, 1, '되돌아가기는 기록을 만들지 않는다');

      // 2026-08-24: 모바일에서 팝업이 지도를 밀면 재렌더가 팝업을 닫아버렸다.
      // 그룹↔개별 전환은 팝업 크기가 바뀌므로 여기가 그 회귀의 시험대다.
      await clickIn('.iwg-item.tap'); await p.waitForTimeout(2500);
      const alive = await p.evaluate(() => document.querySelectorAll('.iw').length);
      check(alive > 0, '팝업 전환 2.5초 뒤에도 팝업이 살아 있다', `${alive}개`);
    }
    /* ── 거리뷰 (2026-09-04 merchants-pano.js 외부화) ──────────────────────
       순수 이동이 조용히 깨지는 자리를 본다. 이 구획은 외부 심볼 5종을 주입받는데,
       `mapObj`·`mapReady` 는 initMap 이 **나중에** 채우는 값이라 게터로 넘긴다 —
       값으로 붙잡으면 영영 null 이고 **에러 없이 아무 일도 안 일어난다**. */
    check(await p.evaluate(() => !!window.OnnuriPano), 'OnnuriPano 가 로드된다');
    check(await p.evaluate(() => {
      const P = window.OnnuriPano || {};
      return ['attach', 'openPano', 'closePano', 'toggleStreetMode', 'initPanoFloat']
        .every((k) => typeof P[k] === 'function');
    }), '거리뷰 계약 5종이 노출된다');

    /* 지도 구석 토글 — StreetLayer(파란 길) 진입·이탈.
       2026-09-04: 이 안내는 **전용 줄 `#streetNote`** 로 옮겼다. 종전에는 `#mapNote` 를
       공유하며 진입 시 저장 → 이탈 시 복원했는데 두 가지가 깨졌다(둘 다 재현했다) —
       ①모드 중에 지도를 확대하면 idle → viewportRender 가 그 자리를 덮어 안내가 사라진다
       ②이탈 시 되돌리는 저장본이 그 사이 낡아, 개별 마커 화면에 옛 클러스터 문구를 복원한다.
       아래 두 검사가 정확히 그 둘을 고정한다. */
    const noteState = () => p.evaluate(() => ({
      street: (() => { const n = document.getElementById('streetNote');
                       return n && !n.hidden ? n.textContent.trim() : null; })(),
      map: ((document.getElementById('mapNote') || {}).textContent || '').trim(),
      on: document.getElementById('streetBtn').classList.contains('on'),
    }));
    const before = await noteState();
    check(before.street === null, '평소에는 거리뷰 줄이 숨어 있다');

    await p.click('#streetBtn', { force: true }); await p.waitForTimeout(2500);
    const st = await noteState();
    check(st.on && /파란 길/.test(st.street || ''), '진입하면 전용 줄에 안내가 뜬다',
      (st.street || '(없음)').slice(0, 30));
    check(st.map === before.map, '거리뷰가 지도 안내줄을 건드리지 않는다', st.map.slice(0, 34));

    /* ① 모드 중 지도 재렌더 — 종전에는 여기서 안내가 사라졌다.
       확대(.cmark 클릭)는 화면에 따라 안내 문장이 안 바뀔 수 있어(개포동 144곳처럼 이미
       개별 마커인 경우) **재렌더가 일어났는지**를 확증하지 못한다. 업종 칩을 눌러
       refresh("filter") → renderMap 을 확실히 태우고, 지도 안내가 바뀐 것으로 그것을 증명한다. */
    const chip = await p.$('#catChips .chip:not(.chip-label):nth-of-type(2)')
              || await p.$('#catChips .chip');
    if (chip) { await chip.click({ force: true }); await p.waitForTimeout(3500); }
    const zoomed = await noteState();
    check(zoomed.map !== before.map, '재렌더가 실제로 일어났다(지도 안내가 바뀜)',
      before.map.slice(0, 26) + ' → ' + zoomed.map.slice(0, 26));
    check(/파란 길/.test(zoomed.street || ''), '재렌더에도 거리뷰 안내가 살아 있다',
      (zoomed.street || '(사라짐)').slice(0, 30));

    // ② 이탈 — 낡은 저장본이 되살아나면 안 된다.
    await p.click('#streetBtn', { force: true }); await p.waitForTimeout(2000);
    const off = await noteState();
    check(off.street === null && !off.on, '이탈하면 전용 줄이 숨고 버튼도 꺼진다');
    check(off.map === zoomed.map, '이탈 시 낡은 지도 안내가 복원되지 않는다', off.map.slice(0, 34));

    // 팝업의 거리뷰 버튼 → 파노라마 패널
    const pin = await p.$('.pin, .pin-multi');
    if (pin) { await pin.click({ force: true }); await p.waitForTimeout(1500); }
    const pbtn = await p.$('.iw-act-pano');
    check(!!pbtn, '팝업에 거리뷰 버튼이 있다');
    if (pbtn) {
      await pbtn.click({ force: true }); await p.waitForTimeout(6000);
      const pv = await p.evaluate(() => {
        const m = document.getElementById('panoModal');
        return { open: !!(m && !m.hidden),
                 view: ((document.getElementById('panoView') || {}).innerHTML || '').length,
                 title: (document.getElementById('panoTitle') || {}).textContent || '' };
      });
      check(pv.open, '파노라마 패널이 열린다');
      // 패널이 열리기만 하고 비어 있으면 주입이 끊긴 것이다 — 에러가 안 나는 실패 모드.
      check(pv.view > 3000, '파노라마가 실제로 렌더된다', pv.view + '자');
      check(pv.title.length > 0, '패널 제목에 상호명이 있다', pv.title.slice(0, 24));
      await p.click('#panoClose', { force: true }); await p.waitForTimeout(1200);
      check(await p.evaluate(() => document.getElementById('panoModal').hidden), '패널이 닫힌다');
    }

    /* ── 리스트↔지도 드래그 핸들 (2026-09-04) ────────────────────────────
       높이를 `--panel-h`(지도 높이)로 못 박고 있었는데, 이 핸들이 옆에 선 열은 지도
       **아래 안내줄만큼 더 길다** — 평소 45.5px, 거리뷰를 켜면 72.3px 짧았고 그만큼
       아래쪽에서 드래그가 잡히지 않았다. `align-self:stretch` 가 살아나도록 height 를
       뺐다. 안내줄이 늘고 주는 화면이므로 **키가 맞는지**를 계속 본다. */
    await p.evaluate(() => document.getElementById('map').scrollIntoView({ block: 'start' }));
    await p.waitForTimeout(1000);
    const hb = () => p.evaluate(() => {
      const h = document.querySelector('.split-handle').getBoundingClientRect();
      const m = document.querySelector('.result-map').getBoundingClientRect();
      return { hTop: h.top, hBot: h.bottom, hMid: h.left + h.width / 2,
               hH: Math.round(h.height * 10) / 10, mH: Math.round(m.height * 10) / 10,
               mapW: Math.round(m.width) };
    });
    const h0 = await hb();
    check(Math.abs(h0.hH - h0.mH) < 1, '드래그 핸들 높이가 지도 열과 같다', `${h0.hH} vs ${h0.mH}`);
    // 화면에 보이는 부분의 아래쪽 — 수정 전에는 여기가 핸들 밖이었다.
    const gy = Math.round(h0.hTop + (Math.min(h0.hBot, 896) - h0.hTop) * 0.9);
    const onHandle = await p.evaluate(([x, y]) => {
      const e = document.elementFromPoint(x, y);
      return !!(e && e.closest('.split-handle'));
    }, [h0.hMid, gy]);
    check(onHandle, '핸들 아래쪽에서도 실제로 잡힌다');
    if (onHandle) {
      await p.mouse.move(h0.hMid, gy); await p.mouse.down();
      await p.mouse.move(h0.hMid - 120, gy, { steps: 10 }); await p.mouse.up();
      await p.waitForTimeout(800);
      const h1 = await hb();
      check(h1.mapW < h0.mapW - 60, '아래쪽에서 끌어 지도 폭이 줄었다', `${h0.mapW} → ${h1.mapW}`);
      check(Math.abs(h1.hH - h1.mH) < 1, '드래그 후에도 키가 맞는다', `${h1.hH} vs ${h1.mH}`);
      await p.dblclick('.split-handle', { force: true }); await p.waitForTimeout(600);
      check((await hb()).mapW > h1.mapW, '더블클릭으로 초기화된다');
    }

    const real = errs.filter((e) => !/401/.test(e));
    check(real.length === 0, '지도·거리뷰 경로 스크립트 오류 없음', real.join(' | '));
    await p.close();
    console.log();
    }
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
