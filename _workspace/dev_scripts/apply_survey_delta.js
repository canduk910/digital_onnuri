/**
 * apply_survey_delta.js — 채록 델타를 카탈로그에 반영한다 (2026-09-05 신설 · 2026-09-06 개정)
 *
 * **자동으로 도는 것이 아니다.** 사람이 주간 다이제스트를 보고 "반영하자"고 정했을 때
 * 부르는 도구다(ADR-16 — 배치는 탐지만 한다). 규칙을 코드로 적어 두는 이유는,
 * 다음에 반영할 때 같은 판단을 다시 처음부터 하지 않기 위해서다.
 *
 *   node apply_survey_delta.js <survey-delta-YYYY-MM-DD.json | survey-digest-YYYY-MM-DD.json>
 *   --dry  를 주면 파일을 쓰지 않고 무엇을 할지만 보여 준다
 *
 * 2026-09-06 개정에서 바꾼 것 — **보류 판단의 원천을 리포트에서 카탈로그로 옮겼다.**
 * 종전에는 리포트가 실어 보낸 `deepLink`(주소 모양 추측)를 믿었는데 그것이 두 몰을
 * 잘못 잡았고, 그 두 몰은 틀린 사유로 16일 넘게 갱신에서 빠져 있었다. 이제 몰의 성격
 * (`survey_scope`)과 확인 상태(`survey_status`)는 카탈로그가 정본이고, 리포트는 **관측치만**
 * 싣는다(무엇이 새로 보였나). 부수 효과로 옛 회차 리포트도 자동으로 옳게 처리된다 —
 * 리포트에 그 필드가 없어도 카탈로그를 보면 되기 때문이다.
 *
 * 규칙:
 *   ① 몰 전체가 아닌 곳은 **보류** — `survey_scope: "section"`. 호스트 몰의 GNB 가 섞인다.
 *      값이 없으면 추측하지 않고 보류하되 사유를 남긴다.
 *   ② 아직 제대로 확인하지 못한 몰은 **보류** — `survey_status: "partial"`.
 *      화면이 "N곳은 YYYY-MM-DD 확인분"이라 밝히는 근거가 그 값이다.
 *   ③ 브랜드는 BRAND_DICT 에 있는 것만 — brandDirectory 스크랩이 카테고리 메뉴를 물어 온다
 *      (찬스 118건 중 진짜는 4건이었다).
 *   ④ 카테고리는 소분류만. 소분류가 붙으면 **뜻이 없어진 부모 단독 id 는 걷어낸다**(양방향).
 *   ⑤ 그 회차에 수집이 실패했거나 본문이 얇으면 건드리지 않는다.
 *   ⑥ 날짜는 **실제 관측일**로 찍는다. 반영·확인한 몰만 올린다 —
 *      보류한 몰의 날짜를 올리면 화면이 거짓말한다.
 *   ⑦ 무엇이든 바뀌었으면 `config.js` 의 `dataVersion` 을 올린다 — 파일명이 고정이라
 *      `?v=` 만이 캐시를 깨는 유일한 수단이다(2026-08-21 에 빼먹어 겪은 사고).
 */
const fs = require('fs');
const path = require('path');
const { BRAND_DICT, normalizeBrands } = require(path.join(__dirname, 'survey_probe.js'));

const ROOT = path.join(__dirname, '..', '..');
/* 시험 가능하게 이음매를 냈다 — 테스트가 임시 사본을 가리켜 실제 데이터를 건드리지 않고
   쓰기 경로(부모 정리·날짜·dataVersion)까지 잰다. 평소에는 저장소 파일이 기본값이다. */
const CAT = process.env.ONNURI_CATALOG || path.join(ROOT, 'data', 'online_catalog.json');
const CFG = process.env.ONNURI_CONFIG || path.join(ROOT, 'config.js');

const args = process.argv.slice(2);
const DRY = args.includes('--dry');
const rest = args.filter((a) => a !== '--dry');
const src = rest[0];
if (!src) {
  console.error('사용: node apply_survey_delta.js <survey-delta-*.json | survey-digest-*.json> [확인일] [--dry]');
  process.exit(2);
}
const dateArg = rest[1];

/* 델타(회차 하나)와 다이제스트(여러 회차 합본)를 **둘 다** 받는다.
   사람이 보는 것은 다이제스트인데 종전에는 그것을 먹이면 TypeError 로 죽었다 —
   리듬을 만들어 두고 그 리듬을 따라가면 막히는 상태였다. */
function readObservations(file) {
  const doc = JSON.parse(fs.readFileSync(file, 'utf-8'));
  if (Array.isArray(doc.report)) {
    return {
      kind: '회차',
      span: doc.date || '',
      rows: doc.report.map((r) => ({
        id: r.id, label: r.label || r.id, ok: r.ok !== false, thin: !!r.thin,
        brands: r.newBrands || [], cats: r.newCats || [], seenOn: doc.date || '',
      })),
    };
  }
  if (Array.isArray(doc.rows)) {
    return {
      kind: `다이제스트(${doc.rounds || '?'}회차)`,
      span: [doc.since, doc.until].filter(Boolean).join(' ~ '),
      rows: doc.rows.map((r) => ({
        // 다이제스트는 이미 실패·얇음 회차를 걸러 모은 것이다(survey_nightly.js weeklyDigest).
        id: r.id, label: r.label || r.id, ok: true, thin: false,
        brands: r.brands || [], cats: r.cats || [],
        seenOn: (r.seenOn || []).filter(Boolean).sort().slice(-1)[0] || '',
      })),
    };
  }
  console.error('알 수 없는 형식입니다 — report(회차) 도 rows(다이제스트) 도 없습니다:', file);
  process.exit(3);
}

const obs = readObservations(src);
const cat = JSON.parse(fs.readFileSync(CAT, 'utf-8'));
const DICT = new Set(normalizeBrands(BRAND_DICT));

const parents = new Set(), subs = new Set();
for (const t of cat.taxonomy) { parents.add(t.id); for (const c of (t.subs || [])) subs.add(c.id); }

const by = {};
for (const p of cat.items) by[p.id] = p;

console.log(`입력: ${obs.kind}${obs.span ? ' · ' + obs.span : ''} · ${obs.rows.length}행`);

const log = [];
let changed = 0;
for (const r of obs.rows) {
  const p = by[r.id];
  if (!p) { log.push([r.label, '카탈로그에 없는 id — 건너뜀(몰이 빠졌거나 id 가 바뀌었다)']); continue; }
  if (!r.ok || r.thin) { log.push([r.label, '건드리지 않음(수집 실패·본문 얇음)']); continue; }

  // ① 몰의 성격 — 카탈로그가 정본이다.
  const scope = p.survey_scope;
  if (scope === 'section') { log.push([r.label, '판단 보류(남의 몰 안의 구획 — 호스트 메뉴 섞임)']); continue; }
  if (scope !== 'mall') { log.push([r.label, '판단 보류(survey_scope 미기재 — 카탈로그에 적어야 반영된다)']); continue; }

  // ② 아직 제대로 확인하지 못한 몰. 종전에는 이 검사가 없었고 thin(본문 1500자)이 **우연히**
  //    막고 있었다 — 상대 사이트가 배너만 늘려도 조건이 뒤집혀 "확인 못 했다"는 유일한 고지가
  //    화면에서 사라진다. 대리 지표가 아니라 카탈로그가 스스로 적은 값을 본다.
  if (p.survey_status === 'partial') {
    log.push([r.label, '판단 보류(survey_status=partial — 아직 제대로 확인하지 못한 몰)']); continue;
  }

  // ③ 브랜드
  const nb = r.brands.filter((b) => DICT.has(b));
  const dropped = r.brands.filter((b) => !DICT.has(b));

  // ④ 카테고리 — 소분류만 넣고, 소분류가 생긴 대분류의 부모 단독 id 는 걷어낸다.
  const has = new Set(p.cats || []);
  const nc = r.cats.filter((c) => {
    if (has.has(c)) return false;
    if (subs.has(c)) return true;
    if (parents.has(c)) return ![...has].some((x) => x.startsWith(c + '-'));
    return false;
  });

  const next = new Set([...has, ...nc]);
  /* 종전에는 "부모를 넣지 않는다"만 있고 **이미 들어 있던 부모를 빼지 않았다.** 그래서
     소분류가 새로 붙을 때마다 `가전·디지털` 과 `가전·디지털(생활·주방가전)` 이 나란히 남았다.
     카드는 태그를 8개까지만 펴므로 정보량 0 인 칩이 그 자리를 먹는다. 2026-08-23 에 사람이
     손으로 치웠던 상태로 되돌아오던 자리다. */
  const removed = [];
  for (const c of [...next]) {
    if (parents.has(c) && [...next].some((x) => x !== c && x.startsWith(c + '-'))) {
      next.delete(c); removed.push(c);
    }
  }

  const brandsBefore = (p.brands || []).length, catsBefore = has.size;
  if (nb.length) p.brands = [...new Set([...(p.brands || []), ...nb])].sort((a, b) => a.localeCompare(b, 'ko'));
  if (nc.length || removed.length) p.cats = [...next].sort();

  // ⑥ 실제 관측일. 실행 당일이 아니다 — 지난 회차를 나중에 반영하는 것이 이 도구의 용도다.
  const on = dateArg || r.seenOn || p.surveyed_on;
  const dateMoved = on && on !== p.surveyed_on;
  if (on) p.surveyed_on = on;

  const touched = (p.brands || []).length !== brandsBefore || (p.cats || []).length !== catsBefore || removed.length || dateMoved;
  if (touched) changed++;
  log.push([r.label,
    `브랜드 +${nb.length}(제외 ${dropped.length}) 카테고리 +${nc.length}` +
    (removed.length ? ` 부모 -${removed.length}(${removed.join(',')})` : '') +
    ` · 확인일 ${p.surveyed_on}`]);
}

/* meta.collected_on 은 "이 카탈로그 전체가 최소 이 날짜만큼은 확인됐다" 는 뜻이다 —
   그래서 **가장 오래된 항목의 확인일**로 둔다. 종전에는 조건 없이 오늘로 올렸는데,
   보류한 몰들이 그대로인 채 파일 전체가 오늘로 보이게 된다. 이 값은 챗봇 코퍼스의
   스탬프로 흘러가 없는 정확도를 말하게 한다. */
cat.meta = cat.meta || {};
const oldest = cat.items.map((p) => p.surveyed_on).filter(Boolean).sort()[0];
if (oldest) cat.meta.collected_on = oldest;

for (const [a, b] of log) console.log(`  ${a.padEnd(24)} ${b}`);

if (!changed) {
  console.log('\n바뀐 것이 없습니다 — 파일을 쓰지 않습니다.');
  process.exit(0);
}

if (DRY) {
  console.log(`\n--dry 라 쓰지 않았습니다. 반영 대상 ${changed}곳 · meta.collected_on = ${cat.meta.collected_on}`);
  process.exit(0);
}

fs.writeFileSync(CAT, JSON.stringify(cat, null, 1) + '\n', 'utf-8');
console.log(`\n반영 ${changed}곳 · meta.collected_on = ${cat.meta.collected_on}`);

/* ⑦ 캐시 태그. 파일명이 고정이라 이것만이 브라우저가 옛 카탈로그를 계속 받는 것을 막는다.
   손으로 하던 두 단계 중 하나만 코드로 옮기면, 도구를 쓸수록 남은 한 단계를 잊게 된다. */
const stamp = dateArg || cat.items.map((p) => p.surveyed_on).filter(Boolean).sort().slice(-1)[0];
if (stamp) {
  const cfg = fs.readFileSync(CFG, 'utf-8');
  const m = cfg.match(/dataVersion:\s*"([^"]+)"/);
  if (!m) {
    console.log('⚠ config.js 에서 dataVersion 을 찾지 못했습니다 — 손으로 올리세요.');
  } else if (m[1] === stamp) {
    console.log(`dataVersion 이미 ${stamp}`);
  } else {
    fs.writeFileSync(CFG, cfg.replace(/dataVersion:\s*"[^"]+"/, `dataVersion: "${stamp}"`), 'utf-8');
    console.log(`dataVersion ${m[1]} → ${stamp}`);
  }
}
