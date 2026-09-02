#!/usr/bin/env node
/**
 * index_nightly.js — 온라인 상품명 **전일 색인** 수집(단계 F, 2026-09-02, ADR-18)
 *
 * 하는 일: 실시간 조회가 닿지 않는 몰 3곳을 야간에 한 번 열어 **상품명과 주소만** 걷는다.
 * 하지 않는 일: 가격·재고·리뷰를 담지 않고, 상품 상세 페이지를 열지 않으며, 데이터를 고치지 않는다.
 *   적재는 nightly_update.py 단계 F 가 한다(이 스크립트는 JSON 파일만 남긴다).
 *
 * 색인이 말하는 것과 말하지 않는 것:
 *   "어제 이 몰이 이 이름의 상품을 올려 두고 있었다" — 여기까지다.
 *   "지금 검색된다"가 아니다. 그래서 실시간 조회의 상태 목록(none/likely/…)에 섞지 않는다(ADR-18).
 *
 * 사용:
 *   node backend/tools/index_nightly.js                     # 3곳 전부, 요약만 출력
 *   node backend/tools/index_nightly.js --out DIR           # DIR/product-index-YYYY-MM-DD.json 저장
 *   node backend/tools/index_nightly.js --ids tpirates      # 지정한 몰만
 *   node backend/tools/index_nightly.js --limit 10          # 몰당 페이지 상한을 낮춰 시험
 *   node backend/tools/index_nightly.js --channel chrome    # 번들 대신 설치된 Chrome
 *
 * 종료 코드: 0 = 정상(수집 성공 여부 무관) · 2 = playwright 없음 · 3 = 입력 문제
 *   몰 하나가 실패해도 나머지는 계속하고, 전 몰이 실패해도 0 으로 끝낸다 — 단계 F 는 fail-open 이다.
 *
 * 예의(상대 사이트 부담):
 *   · 호스트당 요청 간격 ≥ 1초  · 몰당 페이지 상한(레시피별 선언)
 *   · 이미지·폰트·미디어·분석 스크립트는 차단해 바이트를 줄인다
 *   · robots.txt 를 지킨다 — 지니어스몰의 /ko_mall/ 은 열지 않는다(2026-09-02 실측)
 *   · 검색 API 를 직접 부르거나 번들의 토큰을 재사용하지 않는다(ADR-18 기각 대안).
 *     인어교주해적단의 상품 목록은 **화면을 열면 브라우저가 스스로 보내는 요청**의 응답을 읽는다.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
         + '(KHTML, like Gecko) Chrome/125.0 Safari/537.36';

const NAME_MAX = 200;           // DB 는 VARCHAR(300) 이지만 상품명이 그보다 길 이유가 없다
const URL_MAX = 700;            // online_product_index.url 의 컬럼 폭. 넘으면 적재가 죽는다
const HOST_INTERVAL_MS = 1000;  // 호스트당 최소 요청 간격
const NAV_TIMEOUT = 45000;

// 분석·광고 도메인. 이 몰들의 화면 동작에 필요 없고, 우리가 남의 집계에 잡힐 이유도 없다.
// **주소 문자열이 아니라 호스트명으로만 판정한다** — `url.includes('ads.')` 로 하면
// `/assets/uploads.js` 같은 몰 자신의 스크립트까지 막아 화면이 안 그려진다.
const BLOCK_HOSTS = [
  'google-analytics.com', 'googletagmanager.com', 'doubleclick.net', 'clarity.ms',
  'facebook.net', 'facebook.com', 'criteo.com', 'wcs.naver.net', 'google.com',
];
const BLOCK_TYPES = new Set(['image', 'font', 'media']);

/** 이 요청이 분석·광고인가 — 호스트명이 차단 도메인이거나 그 하위 도메인일 때만 참. */
function isNoiseUrl(url) {
  let host;
  try { host = new URL(url).hostname.toLowerCase(); } catch (e) { return false; }
  return BLOCK_HOSTS.some((d) => host === d || host.endsWith('.' + d));
}

// ────────────────────────────────────────────────────────────── 순수 로직 (테스트 대상)

/** 마크업에서 걷은 문자열을 한 줄로 정리하고 길이를 제한한다. */
function cleanName(s) {
  if (typeof s !== 'string') return '';
  return s
    .replace(/[​-‍﻿]/g, '')   // 제로폭 문자
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, NAME_MAX);
}

/**
 * 링크를 절대 주소로 만들고 경로의 중복 슬래시를 없앤다.
 *
 * 놀장 sitemap 이 `https://mall.noljang.co.kr//market/36` 처럼 // 를 넣어 준다 —
 * 그대로 두면 같은 페이지가 두 주소로 남아 기본키가 갈린다.
 * 조각(#)은 지우지 않는다 — 놀장은 상품마다 주소가 없어 시장 페이지 + 이름 조각으로 식별한다.
 */
function normalizeUrl(href, base) {
  if (typeof href !== 'string' || !href.trim()) return null;
  if (href.trim().startsWith('#')) return null;   // 같은 페이지 앵커 — 갈 곳이 없다
  let u;
  try { u = base ? new URL(href, base) : new URL(href); } catch (e) { return null; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  u.pathname = u.pathname.replace(/\/{2,}/g, '/');
  return u.toString();
}

/**
 * 인어교주해적단의 매장이 온누리 매장인가.
 *
 * 이 사이트에는 온누리 아닌 매장이 훨씬 많다(전체 sitemap 은 메뉴 4,461·매장 339).
 * 그걸 그대로 색인하면 온누리상품권으로 못 사는 상품이 결과에 섞인다 —
 * 2026-08-21 롯데ON 딥링크 오염과 같은 유형이다. 목록 응답의 tags 가 판단 근거다.
 */
function isOnnuriStore(store) {
  if (!store || !Array.isArray(store.tags)) return false;
  return store.tags.some((t) => String(t).toLowerCase() === 'onnuri');
}

/**
 * 놀장처럼 상품 주소가 없는 몰에서 쓸 식별자 — 시장 페이지 주소 + 이름 조각.
 *
 * 한글은 퍼센트 인코딩에서 한 글자가 9바이트가 된다. 이름 200자면 조각만 1,800자라
 * VARCHAR(700) 을 넘긴다(오늘 실측 최댓값은 487자지만 긴 이름 하나면 적재가 죽는다).
 * 들어갈 만큼만 담고, 그래도 안 되면 null 을 돌려 그 항목을 버린다.
 */
function fragmentUrl(baseUrl, name) {
  const base = normalizeUrl(baseUrl);
  const clean = cleanName(name);
  if (!base || !clean) return null;
  for (let n = clean.length; n > 0; n = Math.floor(n * 0.7)) {
    const url = `${base}#${encodeURIComponent(clean.slice(0, n))}`;
    if (url.length <= URL_MAX) return url;
  }
  return null;
}

/** 이름·주소가 온전한 항목만, 주소 기준으로 한 번씩만 남긴다. */
function dedupeItems(items) {
  const seen = new Set();
  const out = [];
  for (const it of (Array.isArray(items) ? items : [])) {
    if (!it) continue;
    const name = cleanName(it.name);
    const url = normalizeUrl(it.url);
    if (!name || !url || url.length > URL_MAX || seen.has(url)) continue;
    seen.add(url);
    out.push({ name, url });
  }
  return out;
}

/**
 * 이번 회차가 믿을 만한가.
 *
 * 적재 여부를 정하는 가드(DB 기존 건수 대비 50%)는 단계 F(파이썬)에 있다. 이건 그 앞 단계다 —
 * 크롤러가 스스로 "이 회차는 못 믿는다"고 말해 두지 않으면, 파이썬 가드는 반쯤 걷힌 수집분을
 * 정상 수집으로 받아 보게 된다.
 *
 * 두 몰 다 "상품이 총 몇 개"인지는 말해 주지 않는다. 대신 레시피는 **몇 곳을 열려 했고
 * 몇 곳을 읽었나**를 안다(놀장=sitemap 의 시장 수, 인어교주=온누리 매장 수). 그 커버리지가
 * 반토막이면 건수만 봐서는 알 수 없는 반쪽 회차이므로 레시피가 warn 을 올린다.
 */
function harvestGuard(collected, warn = null) {
  if (!collected) return { ok: false, reason: '수집 0건' };
  if (warn) return { ok: false, reason: warn };
  return { ok: true, reason: null };
}

// ────────────────────────────────────────────────────────────── 예의: 호스트당 간격

/** 이미지·폰트·미디어·분석 스크립트를 막는다 — 상대 서버의 바이트를 아끼고 우리도 빨라진다. */
async function blockNoise(page) {
  await page.route('**/*', (route) => {
    const req = route.request();
    if (BLOCK_TYPES.has(req.resourceType()) || isNoiseUrl(req.url())) {
      return route.abort();
    }
    return route.continue();
  });
}

const lastHit = new Map();
async function pace(host) {
  const prev = lastHit.get(host) || 0;
  const wait = HOST_INTERVAL_MS - (Date.now() - prev);
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastHit.set(host, Date.now());
}

async function open(ctx, url, host, waitMs = 2500) {
  await pace(host);
  const page = await ctx.newPage();
  await blockNoise(page);
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
  await page.waitForTimeout(waitMs);
  return page;
}

// ────────────────────────────────────────────────────────────── 레시피 1: 온누리 놀장

/**
 * 온누리 놀장 — sitemap 의 시장(관) 페이지를 열어 화면에 그려진 상품명을 걷는다.
 *
 * robots: `Allow: /`. sitemap 은 `https://mall.noljang.co.kr//market/{코드}` 형태로
 * 경로에 `//` 가 들어 있다(정규화 필요).
 *
 * 상품은 Next.js 서버 렌더(RSC)로 내려오고 **상품 목록 JSON API 는 호출되지 않는다**
 * (2026-09-02 실측 — 호출되는 것은 카테고리·주소 목록뿐이다). 그래서 DOM 에서 걷는다.
 *
 * 상품마다 고유 주소가 없다 — 카드는 `<li class="… cursor-pointer">` 이고 href 가 없으며,
 * 클릭해도 라우팅되지 않는다(주소 선택이 먼저인 구조). 그래서 **시장 페이지 주소 + 이름 조각**을
 * 식별자로 쓴다. 이용자가 그 주소를 열면 실제로 그 상품이 있는 화면에 도착한다.
 */
async function crawlNoljang(ctx, recipe, limit, log) {
  const origin = 'https://mall.noljang.co.kr';
  let marketUrls = [];
  try {
    await pace(recipe.host);
    const res = await fetch(`${origin}/sitemap.xml`, { headers: { 'User-Agent': UA } });
    const xml = await res.text();
    marketUrls = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)]
      .map((m) => normalizeUrl(m[1]))
      .filter((u) => u && /\/market\/\w+$/.test(u));
  } catch (e) {
    log(`    sitemap 읽기 실패: ${String(e.message || e).slice(0, 100)}`);
  }
  if (!marketUrls.length) throw new Error('sitemap 에서 시장 페이지를 찾지 못했습니다');

  const items = [];
  let pages = 0;
  for (const url of marketUrls.slice(0, limit)) {
    const page = await open(ctx, url, recipe.host, 4000);
    pages++;
    try {
      // 가로 캐러셀이 여러 단이라 아래로 훑어 지연 렌더를 끌어낸다.
      for (let i = 0; i < 3; i++) {
        await page.evaluate(() => window.scrollBy(0, 2000)).catch(() => {});
        await page.waitForTimeout(900);
      }
      const names = await page.evaluate(() => {
        const out = [];
        for (const li of document.querySelectorAll('li')) {
          if (!/cursor-pointer/.test(li.className || '')) continue;
          // 헤더 메뉴('카테고리' 등)도 cursor-pointer 인 li 다. 상품 카드는 썸네일과
          // 가격을 함께 가진다 — 둘을 요구해야 메뉴 문구가 상품명으로 섞이지 않는다.
          if (!li.querySelector('img')) continue;
          if (!/[\d,]+원/.test(li.textContent || '')) continue;
          const spans = [...li.querySelectorAll('span')];
          // 상품명 칸은 line-clamp 로 두 줄까지만 보여 준다. 클래스가 바뀔 때를 대비해
          // 가격(숫자+원)·판매자 줄(가운뎃점)·배지 문구를 걸러낸 뒤 가장 긴 것을 고르는 폴백을 둔다.
          let el = spans.find((s) => /line-clamp/.test(s.className || ''));
          if (!el) {
            const cands = spans.filter((s) => {
              const t = (s.textContent || '').trim();
              return t.length > 3 && !/^[\d,]+원$/.test(t) && !/^\d+%$/.test(t)
                     && !t.includes('ㆍ') && !s.querySelector('b');
            });
            cands.sort((a, b) => (b.textContent || '').length - (a.textContent || '').length);
            el = cands[0];
          }
          if (el) out.push(el.textContent);
        }
        return out;
      });
      for (const n of names) {
        const name = cleanName(n);
        const link = fragmentUrl(url, name);
        if (name && link) items.push({ name, url: link });
      }
    } finally {
      await page.close().catch(() => {});
    }
  }
  log(`    시장 ${pages}곳 / sitemap ${marketUrls.length}곳`);
  const warn = pages < marketUrls.length * 0.5
    ? `sitemap 의 시장 ${marketUrls.length}곳 중 ${pages}곳만 열림 — 절반 미만` : null;
  return { items: dedupeItems(items), pages, warn };
}

// ────────────────────────────────────────────────────────────── 레시피 2: 인어교주해적단

/**
 * 인어교주해적단 — 온누리 매장 목록을 얻고, 매장 화면을 열어 메뉴(상품)명을 걷는다.
 *
 * robots.txt 가 없다(요청이 SPA 화면으로 넘어간다 — 2026-09-02 실측).
 *
 * **직접 API 를 부르지 않는다.** `/store/onnuri` 화면을 열면 브라우저가 스스로
 * 온누리 매장 목록을 받아 오고, 매장 화면을 열면 그 매장의 메뉴 목록을 받아 온다.
 * 우리는 그 응답을 읽을 뿐이다 — 번들에서 토큰을 뽑아 API 를 부르는 안은 ADR-18 이 기각했다.
 *
 * 온누리 매장만 넣는다 — 목록 응답의 tags 에 'onnuri' 가 있는 매장뿐이다(isOnnuriStore).
 * 메뉴 주소는 `/menu/{permalink}/{상품id}` 형식이고 permalink 는 매장 목록의 uri 다.
 */
async function crawlTpirates(ctx, recipe, limit, log) {
  const origin = 'https://tpirates.com';

  // 1) 온누리 매장 목록 — 화면이 스스로 부르는 응답을 읽는다.
  let stores = null;
  const listPage = await ctx.newPage();
  await blockNoise(listPage);
  listPage.on('response', async (r) => {
    if (!/market\/filter\/list/.test(r.url())) return;
    try {
      const j = JSON.parse(await r.text());
      if (Array.isArray(j.content)) stores = j.content;
    } catch (e) { /* 응답이 JSON 이 아니면 무시 */ }
  });
  try {
    await pace(recipe.host);
    await listPage.goto(`${origin}/store/onnuri`, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
    for (let i = 0; i < 12 && !stores; i++) await listPage.waitForTimeout(1000);
  } finally {
    await listPage.close().catch(() => {});
  }
  if (!stores) throw new Error('온누리 매장 목록 응답을 받지 못했습니다');

  const onnuri = stores.filter(isOnnuriStore);
  log(`    온누리 매장 ${onnuri.length}곳 / 목록 ${stores.length}곳`);
  if (onnuri.length < stores.length) {
    log(`    · tags 에 onnuri 가 없는 ${stores.length - onnuri.length}곳은 제외(범위 밖)`);
  }

  // 2) 매장별 메뉴 — 매장 화면을 열고 그 화면이 받는 상품 목록 응답을 읽는다.
  const items = [];
  let pages = 1;                                   // 목록 화면 1건 포함
  let storesOk = 0;
  for (const s of onnuri) {
    if (pages >= limit) break;
    const permalink = String(s.uri || '').replace(/^\//, '');
    if (!s.id || !permalink) continue;
    let payload = null;
    await pace(recipe.host);
    const page = await ctx.newPage();
    await blockNoise(page);
    page.on('response', async (r) => {
      // 필터가 걸린 목록(?filterType=…)은 인기·할인만 담긴 부분집합이라 쓰지 않는다.
      if (!new RegExp(`/stores/${s.id}/products$`).test(r.url().split('?')[0])) return;
      if (r.url().includes('filterType=')) return;
      try { payload = JSON.parse(await r.text()); } catch (e) { /* 무시 */ }
    });
    pages++;
    try {
      await page.goto(`${origin}/store/${s.id}`, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
      for (let i = 0; i < 10 && !payload; i++) await page.waitForTimeout(800);
      if (!payload) continue;
      storesOk++;
      for (const k of (payload.keywords || [])) {
        for (const p of (k.products || [])) {
          if (!p || !p.id) continue;
          items.push({ name: p.name, url: `${origin}/menu/${encodeURIComponent(permalink)}/${p.id}` });
        }
      }
    } catch (e) {
      // 매장 하나가 안 열려도 나머지는 계속한다.
    } finally {
      await page.close().catch(() => {});
    }
  }
  log(`    메뉴를 읽은 매장 ${storesOk}곳 / 온누리 매장 ${onnuri.length}곳`);
  const warn = storesOk < onnuri.length * 0.5
    ? `온누리 매장 ${onnuri.length}곳 중 ${storesOk}곳만 메뉴를 읽음 — 절반 미만` : null;
  return { items: dedupeItems(items), pages, warn };
}

// ────────────────────────────────────────────────────────────── 레시피 표

/**
 * 대상 몰과 그 레시피. id 는 data/online_platforms.json 의 id 와 같아야 한다
 * (테스트가 대조한다 — 어긋나면 존재하지 않는 몰을 적재하게 된다).
 *
 * pageLimit 는 몰마다 다르다: 놀장은 sitemap 의 시장 수만큼, 인어교주는 매장 수 + 목록 1건이다.
 * `--limit` 를 주면 전부 그 값으로 낮춘다(시험용).
 *
 * **지니어스몰은 여기 없다.** 2026-09-02 에 `?search={q}` 정적 검색이 확인되어 실시간 조회
 * 대상이 됐고, 앱은 색인 층에서 실시간 대상을 걸러 낸다(ADR-18 — 한 몰이 두 층에서 다른 말을
 * 하지 않게). 색인으로 걷어 봐야 화면에 닿지 않으므로 레시피를 두지 않는다.
 */
const RECIPES = [
  { id: 'onnuri-noljang', host: 'mall.noljang.co.kr',  pageLimit: 40,  run: crawlNoljang },
  { id: 'tpirates',       host: 'tpirates.com',        pageLimit: 200, run: crawlTpirates },
];

// ────────────────────────────────────────────────────────────── 시각 표기

/** 로컬 기준 YYYY-MM-DD — toISOString 은 UTC 라 KST 새벽 배치가 전날로 찍힌다. */
function localDate(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
/** 로컬 기준 YYYY-MM-DD HH:MM:SS — 배치 로그(파이썬)와 같은 표기. */
function localStamp(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${localDate(d)} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

// ────────────────────────────────────────────────────────────── 실행

async function main() {
  const argv = process.argv.slice(2);
  const valOf = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
  const OUT_DIR = valOf('--out');
  const ONLY = (valOf('--ids') || '').split(',').map((s) => s.trim()).filter(Boolean);
  const LIMIT = valOf('--limit') ? Number(valOf('--limit')) : null;
  const channel = valOf('--channel') || process.env.PLAYWRIGHT_CHANNEL || null;

  const log = (m) => console.log(`[${localStamp()}] ${m}`);

  const targets = ONLY.length ? RECIPES.filter((r) => ONLY.includes(r.id)) : RECIPES;
  if (!targets.length) {
    console.error(`[index] --ids 에 해당하는 레시피가 없습니다: ${ONLY.join(', ')}`);
    console.error(`        가능한 값: ${RECIPES.map((r) => r.id).join(', ')}`);
    process.exit(3);
  }
  if (LIMIT !== null && (!Number.isFinite(LIMIT) || LIMIT < 1)) {
    console.error('[index] --limit 은 1 이상의 수여야 합니다.');
    process.exit(3);
  }

  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch (e) {
    console.error('[index] playwright 가 없습니다 — 단계 F 를 건너뜁니다.');
    console.error('        설치: npm i playwright && npx playwright install --with-deps chromium');
    process.exit(2);
  }

  const launchOpts = { args: ['--no-sandbox'] };
  if (channel) launchOpts.channel = channel;
  const browser = await chromium.launch(launchOpts);
  const ctx = await browser.newContext({ userAgent: UA, viewport: { width: 1400, height: 1000 } });

  log(`대상 ${targets.length}곳 — ${targets.map((r) => r.id).join(', ')}`);
  const platforms = [];
  for (const recipe of targets) {
    const t0 = Date.now();
    const limit = LIMIT !== null ? LIMIT : recipe.pageLimit;
    log(`  ${recipe.id} 시작 (페이지 상한 ${limit})`);
    try {
      const { items, pages, warn } = await recipe.run(ctx, recipe, limit, log);
      const guard = harvestGuard(items.length, warn || null);
      const seconds = Number(((Date.now() - t0) / 1000).toFixed(1));
      platforms.push({
        id: recipe.id, ok: guard.ok, count: items.length, pages, seconds, items,
        ...(guard.ok ? {} : { error: guard.reason }),
      });
      log(`  ${recipe.id} — ${guard.ok ? '수집' : '실패'} ${items.length}건 · ${pages}페이지 · ${seconds}s`
          + (guard.ok ? '' : ` — ${guard.reason}`));
    } catch (e) {
      const seconds = Number(((Date.now() - t0) / 1000).toFixed(1));
      platforms.push({
        id: recipe.id, ok: false, count: 0, pages: 0, seconds, items: [],
        error: String(e.message || e).slice(0, 200),
      });
      log(`  ${recipe.id} — 수집 실패: ${String(e.message || e).slice(0, 140)}`);
    }
  }
  await browser.close().catch(() => {});

  const date = localDate();
  const report = { date, platforms };

  console.log('');
  log(`요약 — 성공 ${platforms.filter((p) => p.ok).length}곳 / ${platforms.length}곳 · `
      + `총 ${platforms.reduce((n, p) => n + p.count, 0)}건`);
  for (const p of platforms) {
    console.log(`  ${p.ok ? '·' : '✗'} ${p.id}: ${p.count}건 · ${p.pages}페이지 · ${p.seconds}s`
                + (p.error ? ` — ${p.error}` : ''));
    for (const it of p.items.slice(0, 5)) console.log(`      ${it.name}`);
  }

  if (OUT_DIR) {
    try {
      fs.mkdirSync(OUT_DIR, { recursive: true });
      const file = path.join(OUT_DIR, `product-index-${date}.json`);
      fs.writeFileSync(file, JSON.stringify(report, null, 1), 'utf-8');
      log(`색인 저장: ${file}`);
    } catch (e) {
      log(`색인 저장 실패(무시): ${e.message}`);
    }
  } else {
    log('저장하지 않았습니다(--out 미지정) — 요약만 출력했습니다.');
  }
  process.exit(0);
}

module.exports = {
  cleanName, normalizeUrl, fragmentUrl, isOnnuriStore, dedupeItems, harvestGuard, isNoiseUrl,
  localDate, localStamp, RECIPES, NAME_MAX, URL_MAX,
};

if (require.main === module) {
  main().catch((e) => {
    console.error('[index] 예기치 못한 오류: ' + (e && e.stack || e));
    process.exit(0);   // fail-open — 배치 전체를 실패로 만들지 않는다
  });
}
