/* 채록 반영 도구와 주간 다이제스트 테스트 (2026-09-06 신설)
 *
 * 왜 필요한가 — 두 조각 다 **경계에서만 드러나는** 코드다. 반영 도구는 데이터 파일을 직접
 * 덮어쓰는데 판단이 틀려도 JSON 은 멀쩡히 파싱되고 화면도 정상으로 그려진다. 다이제스트는
 * 발화 조건이 틀리면 매일 뜨거나 영영 안 뜨는데, 어느 쪽이든 **일주일이 지나야 알 수 있고
 * 에러는 안 난다.** 2026-09-05 훑기에서 두 파일의 결함 다섯 건이 한꺼번에 나온 자리다.
 *
 *   node _workspace/dev_scripts/test_apply_delta.js
 */
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..', '..');
const TOOL = path.join(__dirname, 'apply_survey_delta.js');
const { weeklyDigest } = require(path.join(ROOT, 'backend', 'tools', 'survey_nightly.js'));

let pass = 0, fail = 0;
const check = (cond, label, extra) => {
  if (cond) { pass++; console.log('  [ok] ' + label); }
  else { fail++; console.log('  [FAIL] ' + label + (extra !== undefined ? ' — ' + extra : '')); }
};

function tmpdir(name) {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'onnuri-' + name + '-'));
}

/* 최소 카탈로그. 실제 파일을 쓰지 않는 이유는 두 가지다 — 실제 데이터를 건드리지 않기 위해서,
   그리고 케이스가 실제 데이터의 우연에 기대지 않게 하기 위해서다. */
function baseCatalog() {
  return {
    meta: { collected_on: '2026-01-01' },
    taxonomy: [
      { id: 'appliance', subs: [{ id: 'appliance-home' }, { id: 'appliance-season' }] },
      { id: 'food', subs: [{ id: 'food-kimchi' }] },
    ],
    items: [
      { id: 'mall-a', cats: ['appliance'], brands: [], survey_url: 'https://a.example/', survey_scope: 'mall', surveyed_on: '2026-08-01' },
      { id: 'sect-b', cats: [], brands: [], survey_url: 'https://b.example/plan/1', survey_scope: 'section', surveyed_on: '2026-08-01' },
      { id: 'noscope-c', cats: [], brands: [], survey_url: 'https://c.example/', surveyed_on: '2026-08-01' },
      { id: 'partial-d', cats: [], brands: [], survey_url: 'https://d.example/', survey_scope: 'mall', survey_status: 'partial', surveyed_on: '2026-07-01' },
    ],
  };
}

function run(catalog, doc, args) {
  const dir = tmpdir('apply');
  const catPath = path.join(dir, 'online_catalog.json');
  const cfgPath = path.join(dir, 'config.js');
  const docPath = path.join(dir, 'input.json');
  fs.writeFileSync(catPath, JSON.stringify(catalog, null, 1) + '\n');
  fs.writeFileSync(cfgPath, 'window.ONNURI_CONFIG = {\n  dataVersion: "2026-01-01",\n};\n');
  fs.writeFileSync(docPath, JSON.stringify(doc));
  let out = '', code = 0;
  try {
    out = execFileSync('node', [TOOL, docPath].concat(args || []), {
      encoding: 'utf-8',
      env: Object.assign({}, process.env, { ONNURI_CATALOG: catPath, ONNURI_CONFIG: cfgPath }),
    });
  } catch (e) { out = (e.stdout || '') + (e.stderr || ''); code = e.status; }
  return {
    out, code,
    cat: JSON.parse(fs.readFileSync(catPath, 'utf-8')),
    cfg: fs.readFileSync(cfgPath, 'utf-8'),
    item: (id) => JSON.parse(fs.readFileSync(catPath, 'utf-8')).items.find((i) => i.id === id),
  };
}

const delta = (rows, date) => ({ date: date || '2026-09-03', report: rows });
const digest = (rows) => ({ since: '2026-08-28', until: '2026-09-03', rounds: 7, malls: rows.length, rows });

console.log('(a) 다이제스트를 그대로 먹여도 죽지 않는다 — 사람이 보는 것이 그것이다');
{
  const r = run(baseCatalog(), digest([
    { id: 'mall-a', label: 'mall-a', brands: [], cats: ['appliance-home'], seenOn: ['2026-08-30', '2026-09-02'] },
  ]));
  check(r.code === 0, '종료 코드 0', r.code);
  check(/다이제스트\(7회차\)/.test(r.out), '다이제스트로 인식한다');
  check(r.item('mall-a').cats.includes('appliance-home'), '관측이 반영된다');
}

console.log('(b) 회차 파일도 그대로 동작한다(회귀)');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(r.code === 0 && r.item('mall-a').cats.includes('food-kimchi'), '회차 파일 반영');
  check(/회차/.test(r.out), '회차로 인식한다');
}

console.log('(c) 보류 판단은 리포트가 아니라 카탈로그가 정한다');
{
  // 리포트가 "이건 몰 전체다"라고 우겨도 카탈로그가 section 이면 보류한다.
  const r = run(baseCatalog(), delta([
    { id: 'sect-b', label: 'sect-b', ok: true, scope: 'mall', deepLink: false, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(/남의 몰 안의 구획/.test(r.out), 'section 은 보류한다');
  check(!r.item('sect-b').cats.includes('food-kimchi'), '보류한 몰은 값이 안 들어간다');
  check(r.item('sect-b').surveyed_on === '2026-08-01', '보류한 몰은 날짜도 안 올라간다');

  // 반대로 옛 리포트가 deepLink:true 라 해도 카탈로그가 mall 이면 반영한다 —
  // 오분류로 16일 묶여 있던 두 몰이 풀리는 경로가 이것이다.
  const r2 = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, deepLink: true, newBrands: [], newCats: ['appliance-home'] },
  ]));
  check(r2.item('mall-a').cats.includes('appliance-home'), '옛 리포트의 deepLink 추측은 무시한다');
}

console.log('(d) survey_scope 가 없으면 추측하지 않고 보류하되 사유를 남긴다');
{
  const r = run(baseCatalog(), delta([
    { id: 'noscope-c', label: 'noscope-c', ok: true, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(/survey_scope 미기재/.test(r.out), '사유가 구획 보류와 구분된다');
  check(!r.item('noscope-c').cats.length, '값이 안 들어간다');
}

console.log('(e) survey_status=partial 은 보류한다 — thin 이 아니어도');
{
  const r = run(baseCatalog(), delta([
    { id: 'partial-d', label: 'partial-d', ok: true, thin: false, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(/partial/.test(r.out), 'partial 사유로 보류한다');
  check(r.item('partial-d').surveyed_on === '2026-07-01',
    '확인 못 한 몰의 날짜가 오르지 않는다 — 화면의 "N곳은 …확인분"이 그 값에 기댄다');
}

console.log('(f) 소분류가 붙으면 뜻이 없어진 부모 단독 id 를 걷어낸다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ]));
  const cats = r.item('mall-a').cats;
  check(cats.includes('appliance-home'), '소분류가 들어간다', JSON.stringify(cats));
  check(!cats.includes('appliance'), '부모 단독 id 가 빠진다', JSON.stringify(cats));
  check(/부모 -1\(appliance\)/.test(r.out), '무엇을 걷어냈는지 로그에 남는다');
}

console.log('(g) 부모만 있고 소분류가 없으면 부모를 그대로 둔다 — 정리와 파괴를 가른다');
{
  const c = baseCatalog();
  const r = run(c, delta([{ id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['food-kimchi'] }]));
  check(r.item('mall-a').cats.includes('appliance'),
    '소분류가 생기지 않은 대분류는 유지된다', JSON.stringify(r.item('mall-a').cats));
}

console.log('(h) 브랜드는 사전에 있는 것만 — 카테고리 메뉴를 물어 온 것은 버린다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: ['삼성전자', '전체보기'], newCats: [] },
  ]));
  const br = r.item('mall-a').brands;
  check(br.includes('삼성전자'), '사전에 있는 브랜드는 들어간다', JSON.stringify(br));
  check(!br.includes('전체보기'), '사전에 없는 것은 제외된다', JSON.stringify(br));
  check(/제외 1/.test(r.out), '제외 건수를 밝힌다');

  /* 로그의 숫자는 **실제로 늘어난 수**여야 한다. 후보 수를 적으면 이미 갖고 있던 것까지
     세어 "+20"이라 말하고 실제로는 7개만 늘어난다 — 그 숫자가 회차 리포트로 흘러간다. */
  const c2 = baseCatalog();
  c2.items.find((i) => i.id === 'mall-a').brands = ['삼성전자'];
  const r2 = run(c2, delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: ['삼성전자', 'LG전자'], newCats: [] },
  ]));
  check(/브랜드 \+1\(후보 2/.test(r2.out),
    '이미 갖고 있던 브랜드는 늘어난 수에 안 센다', r2.out.split('\n').find((l) => /mall-a/.test(l)));
}

console.log('(i) 날짜는 실행 당일이 아니라 실제 관측일로 찍는다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ], '2026-08-25'));
  check(r.item('mall-a').surveyed_on === '2026-08-25',
    '회차 파일의 date 를 쓴다', r.item('mall-a').surveyed_on);

  const r2 = run(baseCatalog(), digest([
    { id: 'mall-a', label: 'mall-a', brands: [], cats: ['appliance-home'], seenOn: ['2026-08-29', '2026-09-01', '2026-08-30'] },
  ]));
  check(r2.item('mall-a').surveyed_on === '2026-09-01',
    '다이제스트는 마지막 관측일을 쓴다', r2.item('mall-a').surveyed_on);
}

console.log('(i-2) 날짜는 앞으로만 간다 — 옛 회차가 더 최근의 확인을 취소하지 못한다');
{
  // 2026-09-06 첫 실전 실행에서 15회차를 모아 반영하려다 10곳의 날짜가 뒤로 가는 것을 봤다.
  const c = baseCatalog();
  c.items.find((i) => i.id === 'mall-a').surveyed_on = '2026-09-05';
  const r = run(c, delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ], undefined));   // 회차 date = 2026-09-03 (기본값), 카탈로그는 09-05
  check(r.item('mall-a').surveyed_on === '2026-09-05',
    '더 오래된 관측일은 날짜를 낮추지 않는다', r.item('mall-a').surveyed_on);
  check(r.item('mall-a').cats.includes('appliance-home'),
    '날짜는 그대로여도 관측한 값은 반영된다', JSON.stringify(r.item('mall-a').cats));

  /* 사람이 **명령줄 인자**로 날짜를 명시하면 그 판단을 따른다 — 되돌리는 경로를 막지 않는다.
     리포트 안의 date 와 다른 자리다(그쪽은 관측일이라 앞으로만 간다). */
  const r3 = run(c, delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-season'] },
  ]), ['2026-08-01']);
  check(r3.item('mall-a').surveyed_on === '2026-08-01',
    '인자로 준 날짜는 낮추는 방향이어도 따른다', r3.item('mall-a').surveyed_on);
}

console.log('(j) meta.collected_on 은 가장 오래된 확인일이다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ], '2026-09-03'));
  check(r.cat.meta.collected_on === '2026-07-01',
    '보류한 몰(2026-07-01)이 전체 스탬프를 잡는다', r.cat.meta.collected_on);
}

console.log('(k) 바뀐 것이 있으면 dataVersion 을 올린다 — 파일명이 고정이라 이것만이 캐시를 깬다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ], '2026-09-03'));
  check(/dataVersion:\s*"2026-09-03"/.test(r.cfg), 'dataVersion 이 올라간다', r.cfg.replace(/\s+/g, ' ').slice(0, 80));
}

console.log('(k-2) 같은 날 두 번째면 dataVersion 접미를 올린다 — 내리지 않는다');
{
  /* 2026-09-06 첫 실전 실행에서 `2026-09-06.2` 가 `2026-09-06` 으로 내려갔다.
     값이 달라져 캐시는 깨지지만 버전이 뒤로 가는 것은 다음 사람에게 사고로 보인다. */
  const dir = tmpdir('ver');
  const catPath = path.join(dir, 'c.json'), cfgPath = path.join(dir, 'g.js'), docPath = path.join(dir, 'd.json');
  const bump = (start) => {
    fs.writeFileSync(catPath, JSON.stringify(baseCatalog(), null, 1) + '\n');
    fs.writeFileSync(cfgPath, `window.ONNURI_CONFIG = {\n  dataVersion: "${start}",\n};\n`);
    fs.writeFileSync(docPath, JSON.stringify(delta([
      { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
    ], '2026-09-06')));
    try {
      execFileSync('node', [TOOL, docPath], { encoding: 'utf-8',
        env: Object.assign({}, process.env, { ONNURI_CATALOG: catPath, ONNURI_CONFIG: cfgPath }) });
    } catch (e) { /* 값만 본다 */ }
    return (fs.readFileSync(cfgPath, 'utf-8').match(/dataVersion:\s*"([^"]+)"/) || [])[1];
  };
  check(bump('2026-09-01') === '2026-09-06', '다른 날짜면 그 날짜로', bump('2026-09-01'));
  check(bump('2026-09-06') === '2026-09-06.2', '같은 날이면 .2 로', bump('2026-09-06'));
  check(bump('2026-09-06.2') === '2026-09-06.3', '.2 다음은 .3', bump('2026-09-06.2'));
}

console.log('(l) 바뀐 것이 없으면 파일을 쓰지 않는다');
{
  const c = baseCatalog();
  const r = run(c, delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: [] },
  ], '2026-08-01'));
  check(/바뀐 것이 없습니다/.test(r.out), '쓰지 않았다고 밝힌다');
  check(/dataVersion:\s*"2026-01-01"/.test(r.cfg), 'dataVersion 도 그대로다');
}

console.log('(m) --dry 는 아무것도 쓰지 않는다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, newBrands: [], newCats: ['appliance-home'] },
  ]), ['--dry']);
  check(/--dry/.test(r.out), '드라이런임을 밝힌다');
  check(!r.item('mall-a').cats.includes('appliance-home'), '카탈로그가 그대로다');
  check(/dataVersion:\s*"2026-01-01"/.test(r.cfg), 'config 도 그대로다');
}

console.log('(n) 카탈로그에 없는 id 는 조용히 버리지 않고 사유를 남긴다');
{
  const r = run(baseCatalog(), delta([
    { id: 'gone-x', label: 'gone-x', ok: true, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(/카탈로그에 없는 id/.test(r.out), '무엇이 버려졌는지 보인다');
}

console.log('(o) 알 수 없는 형식이면 종료 코드 3으로 죽는다 — 조용히 0건 반영하지 않는다');
{
  const r = run(baseCatalog(), { hello: 'world' });
  check(r.code === 3, '종료 코드 3', r.code);
}

console.log('(p) 수집 실패·본문 얇음 회차는 건드리지 않는다');
{
  const r = run(baseCatalog(), delta([
    { id: 'mall-a', label: 'mall-a', ok: true, thin: true, newBrands: [], newCats: ['appliance-home'] },
    { id: 'mall-a', label: 'mall-a', ok: false, newBrands: [], newCats: ['food-kimchi'] },
  ]));
  check(!r.item('mall-a').cats.includes('appliance-home'), 'thin 회차는 반영 안 함');
  check(!r.item('mall-a').cats.includes('food-kimchi'), '실패 회차는 반영 안 함');
}

console.log('(q) 주간 다이제스트 — 발화 조건과 thin 제외');
{
  const dir = tmpdir('digest');
  const mk = (d, rows) => fs.writeFileSync(path.join(dir, `survey-delta-${d}.json`), JSON.stringify({ date: d, report: rows }));
  const days = ['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04', '2026-09-05', '2026-09-06', '2026-09-07'];

  days.slice(0, 6).forEach((d) => mk(d, [{ id: 'm', label: 'm', ok: true, newBrands: ['x'], newCats: [] }]));
  check(weeklyDigest(dir) === null, '6회차에서는 뜨지 않는다(발화 기준 7)');

  mk(days[6], [{ id: 'm', label: 'm', ok: true, newBrands: ['y'], newCats: [] }]);
  const dg = weeklyDigest(dir);
  check(dg && dg.rounds === 7, '7회차가 쌓이면 뜬다', dg && dg.rounds);
  check(dg && dg.rows[0].brands.sort().join(',') === 'x,y', '회차를 가로질러 합친다', dg && JSON.stringify(dg.rows[0].brands));
  check(dg && dg.rows[0].id === 'm', 'id 를 싣는다 — 반영 도구가 그것으로 카탈로그를 찾는다');

  // thin 회차의 관측은 다이제스트에 들어가면 안 된다. 반영 도구는 다이제스트에서
  // ok·thin 을 다시 볼 수 없으므로, 여기서 거르지 않으면 보호 없이 통과한다.
  const dir2 = tmpdir('digest2');
  days.forEach((d, i) => fs.writeFileSync(path.join(dir2, `survey-delta-${d}.json`),
    JSON.stringify({ date: d, report: [{ id: 'm', label: 'm', ok: true, thin: i === 3, newBrands: ['b' + i], newCats: [] }] })));
  const dg2 = weeklyDigest(dir2);
  check(dg2 && !dg2.rows[0].brands.includes('b3'), 'thin 회차의 관측은 빠진다', dg2 && JSON.stringify(dg2.rows[0].brands));
  check(dg2 && dg2.thinSkipped === 1, '몇 건을 걸렀는지 밝힌다', dg2 && dg2.thinSkipped);
}

console.log('');
if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
console.log(`전체 통과 (${pass}건)`);
