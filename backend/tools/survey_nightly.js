#!/usr/bin/env node
/**
 * survey_nightly.js — 온라인 사용처 취급품목·브랜드 변화 **탐지**(단계 D, 2026-08-22)
 *
 * 하는 일: 오늘 몫의 몰 3~4곳을 열어 카테고리·브랜드를 채록하고, 현재
 * data/online_catalog.json 과 비교해 **새로 생긴 것만** 리포트로 남긴다.
 *
 * 하지 않는 일: 데이터를 고치지 않는다. 커밋도 푸시도 하지 않는다.
 *   이유 — 단계 B(온라인 플랫폼)는 공식 API 라 계약이 안정적이지만, 채록은 HTML
 *   스크래핑이다. 사이트가 개편되거나 지연 로드로 절반만 걷힌 회차를 자동 반영하면
 *   데이터가 조용히 나빠진다(2026-08-21 실측에서 공영쇼핑·시장을 방으로가 실제로 그랬다).
 *   그래서 사람이 리포트를 보고 반영을 결정한다.
 *
 * 왜 매일 전부가 아니라 3~4곳인가: 취급품목·브랜드는 가맹점 목록만큼 자주 바뀌지
 * 않는다. 22곳을 매일 훑으면 상대 사이트에 부담이고 대부분의 회차가 "변화 없음"이 된다.
 * 일주일에 한 바퀴 돌면 어떤 몰이든 최근 7일 내 확인분이 유지된다.
 *
 * 사용:
 *   node backend/tools/survey_nightly.js                 # 오늘 몫
 *   node backend/tools/survey_nightly.js --all           # 22곳 전부(수동 재실측용)
 *   node backend/tools/survey_nightly.js --ids a,b       # 지정한 id 만
 *   node backend/tools/survey_nightly.js --out DIR       # 리포트 저장 위치(기본: 출력만)
 *   node backend/tools/survey_nightly.js --channel chrome # 번들 대신 설치된 Chrome 사용
 *
 * 종료 코드: 0 = 정상(변화 유무 무관) · 2 = playwright 없음 · 3 = 입력 파일 문제
 *   전 몰 수집 실패라도 0 으로 끝낸다 — 이 단계는 fail-open 이고, 실패 사실은 리포트에 남는다.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const PROBE = path.join(ROOT, '_workspace', 'dev_scripts', 'survey_probe.js');
const CATALOG = path.join(ROOT, 'data', 'online_catalog.json');
const PLATFORMS = path.join(ROOT, 'data', 'online_platforms.json');

let probe;
try {
  probe = require(PROBE);
} catch (e) {
  console.error('[survey] 프로브를 찾지 못했습니다: ' + PROBE + ' — ' + e.message);
  process.exit(3);
}
const { analyze, computeDelta, todaysSlice, mapCats, COLLECT_SNIPPET } = probe;

// ── 인자 ──
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const valOf = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
const OUT_DIR = valOf('--out');
const ONLY = (valOf('--ids') || '').split(',').map((s) => s.trim()).filter(Boolean);
const ALL = has('--all');
const NAV_TIMEOUT = Number(valOf('--timeout') || 45000);

function log(msg) {
  const t = new Date().toISOString().replace('T', ' ').slice(0, 19);
  console.log(`[${t}] ${msg}`);
}

// ── 대상 결정 ──
let catalog, platforms;
try {
  catalog = JSON.parse(fs.readFileSync(CATALOG, 'utf-8'));
  platforms = JSON.parse(fs.readFileSync(PLATFORMS, 'utf-8'));
} catch (e) {
  console.error('[survey] 데이터 파일을 읽지 못했습니다: ' + e.message);
  process.exit(3);
}
const nameOf = {};
for (const p of platforms.items || []) nameOf[p.id] = p.name;

const items = (catalog.items || []).filter((it) => it.survey_url);
const allIds = items.map((it) => it.id).sort();          // 고정 순서 — 순환이 결정적이어야 한다
let targets;
if (ONLY.length) targets = allIds.filter((id) => ONLY.includes(id));
else if (ALL) targets = allIds;
else targets = todaysSlice(allIds, new Date());

const byId = {};
for (const it of items) byId[it.id] = it;

/**
 * 기획전 딥링크인가 — 몰 루트가 아니라 특정 기획전/전용관 페이지를 가리키는가.
 * 이런 몰은 채록할 때 호스트 몰 전체 GNB 가 함께 걷힌다. 그 카테고리는 온누리 결제
 * 범위 밖일 수 있으므로 리포트에서 따로 표시해 사람이 범위를 확인하게 한다.
 * (2026-08-21 실측: 롯데ON 이 그랬고, 재검증에서 사이소도 같은 양상이었다.)
 */
function isDeepLink(url) {
  try {
    const u = new URL(url);
    const depth = u.pathname.split('/').filter(Boolean).length;
    return depth >= 2 || !!u.search;
  } catch (e) {
    return false;
  }
}

(async () => {
  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch (e) {
    console.error('[survey] playwright 가 없습니다 — 단계 D 를 건너뜁니다.');
    console.error('         설치: npm i playwright && npx playwright install --with-deps chromium');
    process.exit(2);
  }

  log(`대상 ${targets.length}곳 / 전체 ${allIds.length}곳 — ${targets.join(', ')}`);
  // 브라우저 지정: 서버는 `npx playwright install chromium` 으로 받은 기본 번들을 쓰지만,
  // 이미 Chrome 이 깔린 환경이나 playwright 버전과 캐시가 어긋난 환경에서는
  // --channel(또는 PLAYWRIGHT_CHANNEL) 로 설치된 브라우저를 그대로 쓸 수 있게 한다.
  const channel = valOf('--channel') || process.env.PLAYWRIGHT_CHANNEL || null;
  const launchOpts = { args: ['--no-sandbox'] };
  if (channel) launchOpts.channel = channel;
  const browser = await chromium.launch(launchOpts);
  const ctx = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36',
    viewport: { width: 1400, height: 1000 },
  });

  const report = [];
  for (const id of targets) {
    const it = byId[id];
    const label = nameOf[id] || id;
    const page = await ctx.newPage();
    try {
      await page.goto(it.survey_url, { waitUntil: 'domcontentloaded', timeout: NAV_TIMEOUT });
      // 지연 로드 몰이 있다 — 본문이 붙을 시간을 주고 한 번 스크롤한다.
      await page.waitForTimeout(2500);
      await page.evaluate(() => window.scrollBy(0, 1600)).catch(() => {});
      await page.waitForTimeout(1200);

      // COLLECT_SNIPPET 은 "() => {...}" 문자열이다. Playwright 의 evaluate 는 문자열을
      // **표현식**으로 평가하므로 그대로 넘기면 함수 객체가 나올 뿐 실행되지 않는다
      // (직렬화도 안 돼 undefined 가 온다). 즉시 호출 형태로 감싸야 한다.
      const rawJson = await page.evaluate(`(${COLLECT_SNIPPET})()`);
      const raw = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson;
      const a = analyze(raw);
      const d = computeDelta(it, a, mapCats);
      report.push({ id, label, ok: true, ...d, url: it.survey_url, deepLink: isDeepLink(it.survey_url) });

      const flags = [];
      if (d.thin) flags.push('본문 얇음(수집 실패 의심)');
      if (d.newBrands.length) flags.push(`새 브랜드 ${d.newBrands.length}`);
      if (d.newCats.length) flags.push(`새 카테고리 ${d.newCats.length}`);
      log(`  ${label} — ${flags.length ? flags.join(' · ') : '변화 없음'} (본문 ${a.textLen.toLocaleString()}자)`);
    } catch (e) {
      report.push({ id, label, ok: false, error: String(e.message || e).slice(0, 160), url: it.survey_url });
      log(`  ${label} — 수집 실패: ${String(e.message || e).slice(0, 120)}`);
    } finally {
      await page.close().catch(() => {});
    }
  }
  await browser.close().catch(() => {});

  // ── 리포트 ──
  const changed = report.filter((r) => r.ok && (r.newBrands.length || r.newCats.length));
  const failed = report.filter((r) => !r.ok);
  const thin = report.filter((r) => r.ok && r.thin);

  console.log('');
  log(`요약 — 확인 ${report.length}곳 · 변화 ${changed.length}곳 · 수집실패 ${failed.length}곳 · 본문얇음 ${thin.length}곳`);
  for (const r of changed) {
    console.log(`  [변화] ${r.label}${r.deepLink ? '  ※ 기획전 딥링크' : ''}`);
    if (r.newBrands.length) console.log(`     새 브랜드: ${r.newBrands.join(', ')}`);
    if (r.newCats.length) console.log(`     새 카테고리: ${r.newCats.join(', ')}`);
    if (r.deepLink && r.newCats.length) {
      console.log('     └ 이 몰은 기획전/전용관 링크다. 위 카테고리에 호스트 몰 전체 GNB 가');
      console.log('       섞였을 수 있으니 온누리 결제 범위인지 확인하고 반영할 것.');
    }
  }
  for (const r of failed) console.log(`  [실패] ${r.label} — ${r.error}`);
  for (const r of thin) console.log(`  [의심] ${r.label} — 본문이 얇다. 사이트 개편이나 로그인 요구일 수 있다`);

  if (changed.length || failed.length) {
    console.log('');
    console.log('  ↑ 데이터는 자동으로 고치지 않는다. 반영하려면 사람이 확인한 뒤');
    console.log('    data/online_catalog.json 을 고치고 _workspace/15_online_catalog_report.md 에 근거를 남길 것.');
  }

  if (OUT_DIR) {
    try {
      fs.mkdirSync(OUT_DIR, { recursive: true });
      const stamp = new Date().toISOString().slice(0, 10);
      const file = path.join(OUT_DIR, `survey-delta-${stamp}.json`);
      fs.writeFileSync(file, JSON.stringify({ date: stamp, report }, null, 1), 'utf-8');
      log(`리포트 저장: ${file}`);
    } catch (e) {
      log(`리포트 저장 실패(무시): ${e.message}`);
    }
  }
  process.exit(0);
})().catch((e) => {
  console.error('[survey] 예기치 못한 오류: ' + (e && e.stack || e));
  process.exit(0);   // fail-open — 배치 전체를 실패로 만들지 않는다
});
