/**
 * test_index_nightly.js — 야간 상품명 색인 크롤러 QA (2026-09-02, ADR-18 단계 F)
 *
 * 케이스는 전부 2026-09-02 실측에서 **실제로 본 문자열**이다. 지어낸 예가 아니다.
 * 실행: node _workspace/dev_scripts/test_index_nightly.js
 *
 * 여기서 보는 것은 크롤러의 **순수 로직**뿐이다 — 브라우저를 띄우는 부분은 테스트하지
 * 않는다(사이트가 바뀌면 실패해야 할 것은 테스트가 아니라 야간 리포트다).
 */
'use strict';
const fs = require('fs');
const path = require('path');

const mod = require(path.join(__dirname, '..', '..', 'backend', 'tools', 'index_nightly.js'));
const { cleanName, normalizeUrl, fragmentUrl, isOnnuriStore, dedupeItems, harvestGuard, isNoiseUrl, RECIPES, URL_MAX } = mod;

let pass = 0, fail = 0;
function check(cond, label, detail) {
  if (cond) { pass++; console.log('  [PASS] ' + label); }
  else { fail++; console.log('  [FAIL] ' + label + (detail ? '\n         → ' + detail : '')); }
}

console.log('(a) 이름 정리 — 마크업에서 걷은 문자열은 공백이 지저분하다');
{
  // 놀장 실측: 카드 마크업에서 걷은 문자열에 탭·개행이 그대로 들어 있다.
  check(cleanName('\n\t\t\t[한우마을] 골드 2호/등심500gx2팩(1등급)\t\t\n')
        === '[한우마을] 골드 2호/등심500gx2팩(1등급)', '탭·개행을 걷어낸다');
  check(cleanName('[코렐]  라벤더리스   4인  18P') === '[코렐] 라벤더리스 4인 18P',
        '연속 공백은 한 칸으로');
  // 놀장 실측: 상품명에 줄바꿈이 섞여 오는 카드가 있다.
  check(cleanName('건채담 얼갈이열무물김치\n1kg~10kg 택1') === '건채담 얼갈이열무물김치 1kg~10kg 택1',
        '줄바꿈도 한 칸으로');
  check(cleanName('​해왕상회​') === '해왕상회', '제로폭 문자 제거');
  check(cleanName(null) === '' && cleanName(undefined) === '' && cleanName(123) === '',
        '문자열이 아니면 빈 문자열');
}
{
  // DB 컬럼이 VARCHAR(300) 이지만 상품명이 그보다 길 이유가 없다. 200자에서 자른다 —
  // 자르지 않으면 상세설명이 통째로 들어온 회차에 색인이 쓰레기로 채워진다.
  const long = '가'.repeat(260);
  check(cleanName(long).length === 200, '200자에서 자른다', String(cleanName(long).length));
  check(cleanName('가'.repeat(200)).length === 200, '200자는 그대로');
}

console.log('(b) URL 정규화 — 놀장 sitemap 은 경로에 // 가 들어 있다');
{
  // 실측: <loc>https://mall.noljang.co.kr//market/36</loc>
  check(normalizeUrl('https://mall.noljang.co.kr//market/36') === 'https://mall.noljang.co.kr/market/36',
        'sitemap 의 // 를 / 로', normalizeUrl('https://mall.noljang.co.kr//market/36'));
  check(normalizeUrl('https://mall.noljang.co.kr//sitemap.xml') === 'https://mall.noljang.co.kr/sitemap.xml',
        '스킴 뒤의 // 는 건드리지 않는다');
  // 인어교주 실측: 화면의 메뉴 링크가 상대경로다(`/menu/{permalink}/{상품id}`).
  const abs = normalizeUrl('/menu/%EA%B0%80%EB%9D%BD%EC%8B%9C%EC%9E%A5%EC%B2%AD%ED%95%B4%EC%88%98%EC%82%B0/6066?tab=menu',
                           'https://tpirates.com/store/0000000194');
  check(abs === 'https://tpirates.com/menu/%EA%B0%80%EB%9D%BD%EC%8B%9C%EC%9E%A5%EC%B2%AD%ED%95%B4%EC%88%98%EC%82%B0/6066?tab=menu',
        '상대경로를 절대주소로(질의는 보존)', abs);
  // 놀장은 상품마다 주소가 없다. 시장 페이지 + 이름 조각으로 식별한다(중복키 방지).
  const frag = normalizeUrl('https://mall.noljang.co.kr/market/01#%EA%B9%80%EC%B9%98');
  check(frag === 'https://mall.noljang.co.kr/market/01#%EA%B9%80%EC%B9%98',
        '조각(#)은 살린다 — 놀장의 식별자다', frag);
  check(normalizeUrl('') === null && normalizeUrl(null) === null, '빈 값은 null');
  check(normalizeUrl('javascript:void(0)') === null, 'http(s) 가 아니면 null');
  check(normalizeUrl('#') === null, '앵커만 있는 href 는 null');
}

console.log('(b2) 조각 주소 — 놀장은 상품마다 주소가 없다');
{
  const u = fragmentUrl('https://mall.noljang.co.kr/market/01', '건채담 얼갈이열무물김치 1kg~10kg 택1');
  check(u.startsWith('https://mall.noljang.co.kr/market/01#'), '시장 페이지 + 조각', u);
  check(decodeURIComponent(u.split('#')[1]) === '건채담 얼갈이열무물김치 1kg~10kg 택1',
        '조각을 되돌리면 상품명', decodeURIComponent(u.split('#')[1]));
  // 한글 한 글자가 퍼센트 인코딩에서 9바이트가 된다. 200자 이름이면 조각만 1,800자라
  // VARCHAR(700) 을 넘겨 적재가 죽는다 — 들어갈 만큼만 담아야 한다.
  const longName = '김'.repeat(200);
  const cut = fragmentUrl('https://mall.noljang.co.kr/market/01', longName);
  check(cut !== null && cut.length <= URL_MAX, `긴 이름이어도 ${URL_MAX}자 이내`,
        cut === null ? 'null' : String(cut.length));
  check(fragmentUrl('https://mall.noljang.co.kr/market/01', '   ') === null, '이름이 비면 null');
  check(fragmentUrl('', '김치') === null, '주소가 비면 null');
}

console.log('(c) 온누리 매장 필터 — 인어교주는 온누리 아닌 매장이 대부분이다');
{
  // 실측: /store/onnuri 의 목록 응답이 매장마다 tags 를 준다.
  const ddaeng = { id: '0000001278', label: '땡글이수산', uri: '/강서농수산물시장땡글이수산',
                   tags: ['quick-delivery', 'today-price', 'package', 'app-order', 'parcel-delivery', 'onnuri'] };
  const other = { id: '0000000194', label: '청해수산', uri: '/가락시장청해수산',
                  tags: ['sale-coupon', 'quick-delivery', 'package', 'app-order'] };
  check(isOnnuriStore(ddaeng) === true, "tags 에 'onnuri' 가 있으면 대상");
  check(isOnnuriStore(other) === false, "tags 에 없으면 대상 아님 — 전체 sitemap 4,461 메뉴를 그냥 넣으면 범위 오염");
  check(isOnnuriStore({ id: 'x', tags: [] }) === false, 'tags 가 비면 대상 아님');
  check(isOnnuriStore({ id: 'x' }) === false, 'tags 가 없으면 대상 아님(모르면 넣지 않는다)');
  check(isOnnuriStore(null) === false, 'null 은 대상 아님');
  check(isOnnuriStore({ id: 'x', tags: ['ONNURI'] }) === true, '대소문자는 무시');
}

console.log('(d) 중복 제거 — 같은 주소가 두 번 걷히는 일이 흔하다');
{
  // 인어교주 실측: 한 매장의 인기메뉴와 전체메뉴에 같은 상품이 함께 실려 두 번 걷힌다.
  const items = dedupeItems([
    { name: ' A급 러시아 대게(마리) ', url: 'https://tpirates.com/menu/x/117' },
    { name: 'A급 러시아 대게(마리)', url: 'https://tpirates.com/menu/x/117' },
    { name: '활 꽃게', url: 'https://tpirates.com/menu/x/118' },
  ]);
  check(items.length === 2, '같은 URL 은 한 번만', JSON.stringify(items));
  check(items[0].name === 'A급 러시아 대게(마리)', '남는 이름은 정리된 형태');
  const dropped = dedupeItems([
    { name: '', url: 'https://a/1' },
    { name: '이름만 있고 주소 없음', url: '' },
    { name: '   ', url: 'https://a/2' },
    { name: '정상', url: 'https://a/3' },
  ]);
  check(dropped.length === 1 && dropped[0].name === '정상',
        '이름이나 주소가 비면 버린다', JSON.stringify(dropped));
  check(dedupeItems(null).length === 0, 'null 이면 빈 목록');
  // 컬럼 폭을 넘는 주소는 적재 단계에서 터진다. 여기서 버리는 편이 낫다.
  const tooLong = dedupeItems([{ name: '정상', url: 'https://a/' + 'x'.repeat(URL_MAX) }]);
  check(tooLong.length === 0, `주소가 ${URL_MAX}자를 넘으면 버린다`, JSON.stringify(tooLong));
}

console.log('(e) 수집 가드 — 절반도 못 걷은 회차를 성공이라 부르지 않는다');
{
  check(harvestGuard(3156).ok === true, '걷은 게 있고 경고가 없으면 통과');
  check(harvestGuard(0).ok === false, '0건은 실패 — 색인할 게 없다');
  check(harvestGuard(0).reason === '수집 0건', '0건 사유를 남긴다', harvestGuard(0).reason);
  // 두 몰 다 총 상품 수를 말해 주지 않는다. 대신 레시피가 커버리지(열려던 곳 대비 읽은 곳)를
  // 세어 반토막이면 warn 을 올린다 — 건수만 봐서는 반쪽 회차를 구분할 수 없다.
  const w = harvestGuard(1200, '온누리 매장 158곳 중 40곳만 메뉴를 읽음 — 절반 미만');
  check(w.ok === false, '커버리지 경고가 있으면 건수와 무관하게 실패', JSON.stringify(w));
  check(w.reason.includes('158'), '경고 문구를 그대로 사유로 남긴다', w.reason);
  check(harvestGuard(1200, null).ok === true, '경고가 없으면 통과');
}

console.log('(e2) 분석·광고 차단 — 몰 자신의 스크립트를 막으면 화면이 안 그려진다');
{
  check(isNoiseUrl('https://www.google-analytics.com/g/collect?v=2') === true, '분석 도메인은 차단');
  check(isNoiseUrl('https://www.googletagmanager.com/gtm.js?id=GTM-X') === true, '태그매니저는 차단');
  check(isNoiseUrl('https://www.clarity.ms/tag/oxp1dqfhru') === true, '클래리티는 차단');
  // 인어교주 실측: 화면이 이 주소들로 상품 목록을 받아 온다. 막으면 아무것도 못 걷는다.
  check(isNoiseUrl('https://pub-api.tpirates.com/v3/www/stores/0000001278/products') === false,
        '몰의 API 는 통과');
  check(isNoiseUrl('https://tpirates.com/assets/index-BUXpfoNe.js') === false, '몰의 번들은 통과');
  // 문자열 포함으로 판정하면 'uploads.'·'downloads.' 안의 'ads.' 에 걸린다.
  check(isNoiseUrl('https://mall.noljang.co.kr/_next/static/chunks/uploads.js') === false,
        "'uploads.js' 를 광고로 오인하지 않는다");
  check(isNoiseUrl('not a url') === false, '주소가 아니면 차단하지 않는다');
}

console.log('(f) 레시피 표 — 대상은 코드가 아니라 데이터와 맞아야 한다');
{
  const ids = RECIPES.map((r) => r.id);
  check(ids.length === 2, '레시피 2개 — 놀장·인어교주', ids.join(', '));
  check(new Set(ids).size === ids.length, 'id 중복 없음');
  check(RECIPES.every((r) => typeof r.run === 'function'), '레시피마다 실행 함수가 있다');
  check(RECIPES.every((r) => r.host && typeof r.pageLimit === 'number' && r.pageLimit > 0),
        '레시피마다 호스트와 페이지 상한이 있다');

  // 여기 적힌 id 가 data/online_platforms.json 에 없으면 단계 F 가 존재하지 않는 몰을
  // 적재하게 된다(2026-08-31 robots 감시가 엉뚱한 도메인을 보던 것과 같은 유형).
  const src = path.join(__dirname, '..', '..', 'data', 'online_platforms.json');
  const known = new Set(JSON.parse(fs.readFileSync(src, 'utf8')).items.map((i) => i.id));
  const missing = ids.filter((id) => !known.has(id));
  check(missing.length === 0, '모든 레시피 id 가 data/online_platforms.json 에 있다',
        missing.join(', '));
  // platform_id 컬럼은 VARCHAR(60)(V8 이 online_platform.id 에 맞춘 폭)이다.
  const tooLong = ids.filter((id) => id.length > 60);
  check(tooLong.length === 0, 'id 가 60자를 넘지 않는다(VARCHAR(60))', tooLong.join(', '));
  // 실시간 조회 대상은 색인 층에서 앱이 걸러 내므로 여기 있으면 안 된다(ADR-18).
  check(!ids.includes('genius-mall'),
        '실시간 조회 대상(지니어스몰)은 색인 레시피에 없다', ids.join(', '));

  // 레시피의 호스트는 그 몰의 주소와 같아야 한다 — 다른 사이트를 긁고 있으면 안 된다.
  const urlOf = {};
  for (const i of JSON.parse(fs.readFileSync(src, 'utf8')).items) urlOf[i.id] = i.url || '';
  const wrongHost = RECIPES.filter((r) => {
    try { return new URL(urlOf[r.id]).host !== r.host; } catch (e) { return true; }
  }).map((r) => `${r.id}(${r.host} vs ${urlOf[r.id]})`);
  check(wrongHost.length === 0, '레시피 호스트가 데이터의 주소와 일치', wrongHost.join(', '));
}

if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
console.log(`전체 통과 (${pass}건)`);
