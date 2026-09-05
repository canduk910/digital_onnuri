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
const { analyze, computeDelta, todaysSlice, localDate, localStamp, mapCats, COLLECT_SNIPPET } = probe;

// ── 인자 ──
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const valOf = (f) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : null; };
const OUT_DIR = valOf('--out');
const ONLY = (valOf('--ids') || '').split(',').map((s) => s.trim()).filter(Boolean);
const ALL = has('--all');
const NAV_TIMEOUT = Number(valOf('--timeout') || 45000);

function log(msg) {
  // 배치(nightly_update.py)와 같은 로컬 시각 표기 — 한 로그 파일에 섞이므로 기준이 같아야 한다
  console.log(`[${localStamp()}] ${msg}`);
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
/* 2026-09-06: 주소 모양으로 **추측하던 것을 걷어냈다.** 경로 깊이만 보는 휴리스틱이
 * `tpirates(/store/onnuri)` 와 `onnuri-paldo-sijang(/Extmall/Onnuri.aspx)` 을 남의 기획전으로
 * 잘못 잡았고, 그 두 몰은 **틀린 사유로** 갱신에서 빠져 15일 넘게 옛 채록에 묶여 있었다.
 * 채록 주소는 원래 사람이 골라 넣은 값이므로, 그 주소가 몰 전체인지 남의 몰 안의 한 구획인지도
 * 고른 사람이 카탈로그에 적는다(`survey_scope`: "mall" | "section").
 *
 * 값이 없으면 추측하지 않고 **보류하되 그 사실을 사유로 남긴다.** 조용히 몰로 취급하면
 * 호스트 메뉴가 섞인 채 반영되고, 조용히 구획으로 취급하면 새 몰이 영영 갱신되지 않는다. */
function scopeOf(item) {
  const v = item.survey_scope;
  if (v === 'mall' || v === 'section') return v;
  return 'unknown';
}

function weeklyDigest(outDir) {
  let files;
  try {
    files = fs.readdirSync(outDir).filter((f) => /^survey-delta-\d{4}-\d{2}-\d{2}\.json$/.test(f)).sort();
  } catch (e) { return null; }
  if (files.length < 7) return null;

  // 마지막 다이제스트 이후 회차만 센다. 없으면 최근 7회차.
  let lastDigest = '';
  try {
    const ds = fs.readdirSync(outDir).filter((f) => /^survey-digest-/.test(f)).sort();
    if (ds.length) lastDigest = (ds[ds.length - 1].match(/(\d{4}-\d{2}-\d{2})/) || [])[1] || '';
  } catch (e) { /* 없으면 첫 다이제스트 */ }
  const fresh = files.filter((f) => f.slice('survey-delta-'.length, -5) > lastDigest);
  if (fresh.length < 7) return null;

  const byMall = new Map();
  let thinSkipped = 0;
  for (const f of fresh) {
    let doc;
    try { doc = JSON.parse(fs.readFileSync(path.join(outDir, f), 'utf-8')); } catch (e) { continue; }
    for (const r of (doc.report || [])) {
      /* 실패·얇음 회차는 아예 세지 않는다. 다이제스트는 사람이 보고 그대로 반영 도구에
         먹이는 물건이라(2026-09-06), 여기서 걸러 두지 않으면 그 회차의 관측이 보호 없이
         통과한다 — 반영 도구는 다이제스트에서 ok·thin 을 다시 볼 수 없다. */
      if (!r.ok || r.thin) { thinSkipped += (r.thin ? 1 : 0); continue; }
      const cur = byMall.get(r.label) || { id: r.id, brands: new Set(), cats: new Set(), chrome: new Set(), deepLink: false, dates: [] };
      (r.newBrands || []).forEach((x) => cur.brands.add(x));
      (r.newCats || []).forEach((x) => cur.cats.add(x));
      (r.newBrandsChrome || []).forEach((x) => cur.chrome.add(x));
      if (r.deepLink) cur.deepLink = true;
      cur.dates.push(doc.date);
      byMall.set(r.label, cur);
    }
  }
  const rows = [...byMall.entries()]
    .map(([label, v]) => ({ label, id: v.id, deepLink: v.deepLink,
      brands: [...v.brands], cats: [...v.cats], chrome: [...v.chrome], seenOn: v.dates }))
    .filter((r) => r.brands.length || r.cats.length)
    .sort((a, b) => (b.brands.length + b.cats.length) - (a.brands.length + a.cats.length));
  return { since: fresh[0].slice('survey-delta-'.length, -5), until: fresh[fresh.length - 1].slice('survey-delta-'.length, -5),
           rounds: fresh.length, malls: byMall.size, thinSkipped, rows };
}

async function main() {
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
      const scope = scopeOf(it);
      // deepLink 는 옛 리포트 파일과의 호환을 위해 계속 싣는다(반영 도구가 지난 회차를 읽는다).
      report.push({ id, label, ok: true, ...d, url: it.survey_url, scope, deepLink: scope !== 'mall' });

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
    console.log(`  [변화] ${r.label}${r.scope === 'section' ? '  ※ 남의 몰 안의 구획' : r.scope === 'unknown' ? '  ※ survey_scope 미기재' : ''}`);
    if (r.newBrands.length) console.log(`     새 브랜드: ${r.newBrands.join(', ')}`);
    // 화면 부속(내비·배너 텍스트)은 갈라서 뒤에 적는다 — 섞으면 사람이 목록을 통째로 무시한다.
    if (r.newBrandsChrome && r.newBrandsChrome.length) {
      console.log(`     (화면 부속으로 보임 — 브랜드 아닐 가능성: ${r.newBrandsChrome.join(', ')})`);
    }
    if (r.newCats.length) console.log(`     새 카테고리: ${r.newCats.join(', ')}`);
    if (r.scope !== 'mall' && r.newCats.length) {
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

  /* ── 주간 다이제스트 (2026-09-05, C4 사용자 결정 "채록 델타 리듬") ──────────
     회차 리포트는 하루 3~4곳만 담아서, 22곳을 한 바퀴 도는 일주일을 통째로 봐야
     "무엇을 반영할지"가 보인다. 그런데 리포트가 날짜별 JSON 으로만 쌓여 아무도 열지
     않았다 — 2026-08-23~09-05 14회차에 **새 브랜드 209 · 카테고리 123** 이 반영 없이
     서버에만 있었다(2026-09-05 집계).

     그래서 **일주일에 한 번, 지난 7회차를 합쳐 한 화면에 적는다.** 요일은 고정하지
     않는다 — 배치가 하루 걸러도 리듬이 밀리지 않게 "지난 다이제스트 이후 7회차가
     쌓였으면" 을 기준으로 한다. 여전히 **자동 반영은 하지 않는다**(ADR-16). */
  if (OUT_DIR) {
    try {
      fs.mkdirSync(OUT_DIR, { recursive: true });
      const stamp = localDate();   // KST 00:30 실행이 전날 파일명으로 찍히지 않도록
      const file = path.join(OUT_DIR, `survey-delta-${stamp}.json`);
      fs.writeFileSync(file, JSON.stringify({ date: stamp, report }, null, 1), 'utf-8');
      log(`리포트 저장: ${file}`);

      const dg = weeklyDigest(OUT_DIR);
      if (dg) {
        const dfile = path.join(OUT_DIR, `survey-digest-${stamp}.json`);
        fs.writeFileSync(dfile, JSON.stringify(dg, null, 1), 'utf-8');
        console.log('');
        log(`━━ 주간 다이제스트 ${dg.since} ~ ${dg.until} (${dg.rounds}회차 · ${dg.malls}곳) ━━`);
        if (!dg.rows.length) {
          log('  반영 대기 중인 변화 없음');
        } else {
          for (const r of dg.rows) {
            console.log(`  ${r.label}${r.deepLink ? '  ※ 몰 전체가 아님 — 반영 보류' : ''}`);
            if (r.brands.length) console.log(`     새 브랜드 ${r.brands.length}: ${r.brands.join(', ')}`);
            if (r.cats.length) console.log(`     새 카테고리 ${r.cats.length}: ${r.cats.join(', ')}`);
            if (r.chrome.length) console.log(`     (화면 부속 ${r.chrome.length}건은 제외하고 셌다)`);
          }
          console.log('');
          log('  ↑ 이번 주 반영 여부를 사람이 정한다. 반영하면 data/online_catalog.json 과');
          log('    _workspace/15_online_catalog_report.md 를 함께 고치고 config.js dataVersion 을 올릴 것.');
        }
        log(`다이제스트 저장: ${dfile}`);
      }
    } catch (e) {
      log(`리포트 저장 실패(무시): ${e.message}`);
    }
  }
  process.exit(0);
}

/* 이 파일은 배치가 스크립트로 부른다. 테스트가 weeklyDigest 를 require 해서 직접 시험할 수
   있도록 실행은 require.main 일 때만 한다 — 안 그러면 require 하는 순간 배치가 돈다. */
if (require.main === module) {
  main().catch((e) => {
    console.error('[survey] 예기치 못한 오류: ' + (e && e.stack || e));
    process.exit(0);   // fail-open — 배치 전체를 실패로 만들지 않는다
  });
} else {
  module.exports = { weeklyDigest, scopeOf };
}
