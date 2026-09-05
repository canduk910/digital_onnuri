/**
 * test_survey_probe.js — 채록 프로브 QA (2026-08-21)
 *
 * 케이스는 전부 2026-08-21 실측에서 **실제로 겪은** 오탐·절단이다. 지어낸 예가 아니다.
 * 실행: node _workspace/dev_scripts/test_survey_probe.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { matchBrands, extractListSegment, analyze, computeDelta, looksLikeChrome, todaysSlice, localDate, localStamp, mapCats, normalizeBrand, normalizeBrands, BRAND_ALIASES, CAT_RULES, COLLECT_SNIPPET, BRAND_DICT } = require('./survey_probe.js');

let pass = 0, fail = 0;
function check(cond, label, detail) {
  if (cond) { pass++; console.log('  [PASS] ' + label); }
  else { fail++; console.log('  [FAIL] ' + label + (detail ? '\n         → ' + detail : '')); }
}
function find(res, brand) { return res.find((r) => r.brand === brand); }

console.log('(a) 오탐 — 브랜드명이 더 긴 단어의 조각인 경우 suspect 로 표시');
{
  // 11번가 실측: 인기검색어 "파인애플"이 '애플'로 잡혔다.
  const r = matchBrands('8 위 파인애플 검색어 정보 달콤한 여름 한입', ['애플']);
  check(find(r, '애플') && find(r, '애플').suspect === true, "'파인애플' 안의 애플 → suspect");
}
{
  // 꾹AI 실측: 시장 이름 "가음시장대상가"가 '대상'으로 잡혔다.
  const r = matchBrands('시장찾기 (서울)포방터시장 가음시장대상가 강남시장', ['대상']);
  check(find(r, '대상') && find(r, '대상').suspect === true, "'시장대상가' 안의 대상 → suspect");
}
{
  // 온누리5일장 실측: 가습기 모델명 "ZH-LG901G"가 'LG'로 잡혔다.
  const r = matchBrands('[파세코] 포그니 가열식 가습기 ZH-LG901G 299,000원', ['LG']);
  check(find(r, 'LG') && find(r, 'LG').suspect === true, "'ZH-LG901G' 안의 LG → suspect");
}

console.log('(b) 정탐 — 경계가 깨끗하면 suspect 가 아니어야 한다');
{
  // 현대이지웰 실측: 실제 대상 브랜드 상품.
  const r = matchBrands('무료배송 (서울)중랑교종합상가 [대상 뉴케어] 300(tf)', ['대상']);
  check(find(r, '대상') && find(r, '대상').suspect === false, "'[대상 뉴케어]' → 정탐");
}
{
  // 지니어스몰 실측: 'LG전자'는 사전에 있으므로 그 자체로 깨끗하게 잡혀야 한다.
  const r = matchBrands('[한정특가] LG전자 울트라기어 게이밍모니터 32G600A', ['LG전자', 'LG']);
  check(find(r, 'LG전자') && find(r, 'LG전자').suspect === false, "'LG전자' → 정탐");
  check(find(r, 'LG') && find(r, 'LG').suspect === true, "같은 문장의 'LG'는 접미 결합이라 suspect");
}
{
  const r = matchBrands('[본사직영] ECOVACS T50 PRO OMNI 에코백스 T50 프로 옴니', ['ECOVACS', '에코백스']);
  check(find(r, 'ECOVACS') && !find(r, 'ECOVACS').suspect, "'ECOVACS' → 정탐");
  check(find(r, '에코백스') && !find(r, '에코백스').suspect, "'에코백스' → 정탐");
}

console.log('(c) 혼합 — 오탐과 정탐이 같이 있으면 정탐을 대표 문맥으로 고른다');
{
  // 온누리핫딜: 본문에 '애플사이다비니거'와 실제 '[애플] 에어팟'이 함께 나온다.
  const text = '발효 사과식초 애플 사이다 비니거 ... 신상품 [애플] 에어팟 프로 3 359,000원';
  const r = matchBrands(text, ['애플']);
  const hit = find(r, '애플');
  check(hit && hit.suspect === false, '정탐 출현이 하나라도 있으면 suspect=false');
  check(hit && hit.count === 2, '출현 횟수는 전부 센다 (count=2)', hit && String(hit.count));
  // '애플 사이다 비니거'는 앞뒤가 공백이라 경계 규칙으로는 깨끗하다 — 규칙만으로 못 거른다.
  // 그래서 문맥을 여러 개 넘겨 사람이 판정하게 한다. 이것이 이 함수의 계약이다.
  check(hit && Array.isArray(hit.ctxs) && hit.ctxs.length === 2, '출현 문맥을 여러 개 넘긴다');
  check(hit && hit.ctxs.some((c) => c.includes('사이다')) && hit.ctxs.some((c) => c.includes('에어팟')),
        '오탐 문맥과 정탐 문맥이 모두 보여야 판정할 수 있다', hit && JSON.stringify(hit.ctxs));
}

console.log('(d) 목록 추출 — 브랜드명에 있는 글자를 종료 조건으로 쓰지 않는다');
{
  // 온누리찬스 실측 버그: 종료 조건 /원$/ 가 '뉴트리원'에 걸려 9개에서 잘렸다(정답 133).
  const text = [
    '브랜드관', '간식대장', '개성시대', '기라로쉬', '깨끗한나라', '나이키',
    '내셔널지오그래픽', '네네치킨', '네오플램', '농심', '뉴트리원', '뇌물김',
    '닌텐도', '다이슨', '테팔', '휠라',
    '[테팔] 비스트브라운 웍(궁중팬) 28cm', '45,000원', '무료배송',
  ].join('\n');
  const list = extractListSegment(text, '브랜드관');
  check(list.includes('뉴트리원'), "'뉴트리원'이 잘리지 않는다 (실제 버그)", JSON.stringify(list));
  check(list.length === 15, '브랜드 15개 전부 수집', `실제 ${list.length}: ${JSON.stringify(list)}`);
  check(!list.some((s) => s.includes('원') && /\d/.test(s)), '가격 줄은 포함되지 않는다');
  check(!list.some((s) => s.includes('비스트브라운')), '상품명 줄에서 멈춘다');
}
{
  const list = extractListSegment('아무 텍스트', '브랜드관');
  check(Array.isArray(list) && list.length === 0, '표지가 없으면 빈 배열');
}
{
  // 짧은 상품 카드가 한 줄 끼어도 tolerance 안에서는 계속 읽는다.
  const text = ['브랜드관', '나이키', '★★★★★', '아디다스', '휠라', '19,900원', '무료배송'].join('\n');
  const list = extractListSegment(text, '브랜드관');
  check(list.includes('아디다스') && list.includes('휠라'), '비항목 1줄은 건너뛴다', JSON.stringify(list));
}

console.log('(e) 사전 무결성');
{
  check(BRAND_DICT.length === new Set(BRAND_DICT).size, '브랜드 사전에 중복 없음');
  check(BRAND_DICT.every((b) => typeof b === 'string' && b.length >= 2),
        '모든 항목이 2글자 이상 문자열 (1글자는 오탐 폭탄)');
  const longestFirst = [...BRAND_DICT].every((b, i, arr) => {
    const container = arr.find((o, j) => j !== i && o.includes(b) && o.length > b.length);
    return !container || arr.indexOf(container) < i;
  });
  check(longestFirst, "포함 관계가 있으면 긴 쪽이 먼저 온다 ('LG전자'가 'LG'보다 앞)");
}

console.log('(f) analyze — 수집 결과를 판정으로 바꾼다 (실측에 실제로 쓰이는 경로)');
{
  const raw = {
    title: '온누리찬스', url: 'https://onnurichance.com/',
    cats: ['브랜드관', '식품관', '가전디지털'],
    brandLinks: [{ text: '브랜드관', href: 'https://onnurichance.com/brand' }],
    text: [
      '브랜드관', '나이키', '뉴트리원', '닌텐도', '다이슨',
      '[파세코] 가열식 가습기 ZH-LG901G', '299,000원',
      '[대상 뉴케어] 300', '8 위 파인애플 검색어',
    ].join('\n'),
  };
  const r = analyze(raw);
  check(r.confirmed.includes('대상'), 'confirmed 에 정탐이 들어간다');
  check(r.suspect.some((s) => s.brand === 'LG'), 'suspect 에 오탐이 분리된다');
  check(!r.confirmed.includes('LG'), '오탐은 confirmed 에 섞이지 않는다', JSON.stringify(r.confirmed));
  check(r.brandDirectory.includes('뉴트리원'), 'analyze 경로에서도 목록이 안 잘린다');
  check(r.cats.length === 3 && r.brandLinks.length === 1, '수집 필드는 그대로 통과시킨다');
}

console.log('(g) 수집 스니펫 — 브라우저로 보내는 문자열의 형태');
{
  check(typeof COLLECT_SNIPPET === 'string' && COLLECT_SNIPPET.startsWith('() =>'),
        'evaluate 에 넣을 수 있는 화살표 함수 문자열');
  check(!/matchBrands|extractListSegment/.test(COLLECT_SNIPPET),
        '판정 로직이 스니펫에 복제되지 않았다 (테스트한 코드와 실행된 코드가 같아야 한다)');
  check(/innerText/.test(COLLECT_SNIPPET) && /brandLinks/.test(COLLECT_SNIPPET),
        '원문과 링크를 수집한다');
  check((() => { try { new Function('return ' + COLLECT_SNIPPET); return true; } catch (e) { return false; } })(),
        '스니펫이 문법적으로 유효하다');
}

console.log('(h) todaysSlice — 22곳을 일주일에 한 바퀴, 결정적으로 나눈다');
{
  const ids = Array.from({ length: 22 }, (_, i) => 'm' + i);
  const days = Array.from({ length: 7 }, (_, k) => new Date(2026, 7, 24 + k)); // 월~일
  const picks = days.map((d) => todaysSlice(ids, d));
  const sizes = picks.map((p) => p.length);
  check(sizes.every((n) => n >= 3 && n <= 4), '하루 3~4곳', JSON.stringify(sizes));
  const union = new Set(picks.flat());
  check(union.size === 22, '일주일이면 22곳 전부 한 번씩', `실제 ${union.size}`);
  const total = sizes.reduce((a, b) => a + b, 0);
  check(total === 22, '중복 없이 정확히 한 번씩', String(total));
  const again = todaysSlice(ids, new Date(2026, 7, 24));
  check(JSON.stringify(again) === JSON.stringify(picks[0]), '같은 날짜면 같은 결과(결정적)');
  const nextWeek = todaysSlice(ids, new Date(2026, 7, 31));
  check(JSON.stringify(nextWeek) === JSON.stringify(picks[0]), '7일 뒤 같은 묶음으로 돌아온다');

  // 배치는 KST 00:30 에 돈다. UTC 기준으로 계산하면 그 시각은 전날 15:30 이라
  // 하루 전 묶음이 뽑히고, 로컬 날짜로 찍히는 로그와 어긋난다(2026-08-23 실제 결함).
  const midnightish = new Date(2026, 7, 23, 0, 30);
  const noonSameDay = new Date(2026, 7, 23, 12, 0);
  check(JSON.stringify(todaysSlice(ids, midnightish)) === JSON.stringify(todaysSlice(ids, noonSameDay)),
        '같은 로컬 날짜면 00:30 과 정오가 같은 묶음');
  check(JSON.stringify(todaysSlice(ids, midnightish)) !== JSON.stringify(todaysSlice(ids, new Date(2026, 7, 22, 12, 0))),
        '전날과는 다른 묶음(하루 밀리지 않는다)');
}

console.log('(h-2) 로컬 날짜 표기 — UTC 로 찍어 전날이 되지 않는가');
{
  const d = new Date(2026, 7, 23, 0, 30, 5);
  check(localDate(d) === '2026-08-23', 'localDate 가 로컬 날짜', localDate(d));
  check(localStamp(d) === '2026-08-23 00:30:05', 'localStamp 가 배치 로그와 같은 표기', localStamp(d));
  check(new Date(2026, 7, 23, 0, 30).toISOString().slice(0, 10) !== localDate(d) ||
        new Date().getTimezoneOffset() === 0,
        'UTC 표기와 다르다(양수 오프셋 지역에서) — 이 차이가 결함의 원인이었다');
}

console.log('(i-2) 화면 부속은 빼지 않고 갈라 놓는다 (2026-09-05)');
{
  /* 2026-09-05 우체국쇼핑 델타 실측 — "새 브랜드" 13개가 전부 화면 부속이었다.
     이런 것이 섞이면 사람이 리포트를 통째로 무시하게 되고, 그러면 감시가 이름만 남는다.
     **자동 제외가 아니라 표시**다(2026-08-21 규칙 — 자동 제외했더니 LG전자까지 사라졌다). */
  const NOISE = ['TOP', 'Previous', '↓', '축소/확대 버튼', '최근 본 상품',
    '쿠폰등록 법인다량상담 기부하기', '전통시장 MD 추천', '팔도명물만 찾았다!',
    '인기 상품', '전체 상품', 'MD 추천', '온누리상품권 소개'];
  NOISE.forEach((n) => check(looksLikeChrome(n), `화면 부속으로 본다: ${n}`));

  // 반대편이 더 중요하다 — 실제 브랜드를 하나라도 부속으로 보면 그 몰의 델타가 조용히 준다.
  const REAL = ['삼성전자', 'LG전자', 'CJ제일제당', '아디다스골프', '매일유업', 'DJI',
    'ECOVACS', '삼천리 자전거', '내셔널지오그래픽', '로보락', '뉴트리원', '청정원'];
  REAL.forEach((b) => check(!looksLikeChrome(b), `브랜드를 부속으로 보지 않는다: ${b}`));

  const d = computeDelta(
    { brands: [], cats: [] },
    { confirmed: [], brandDirectory: ['다이슨', 'TOP', '최근 본 상품'], cats: [], textLen: 9000 },
    () => []);
  check(d.newBrands.join(',') === '다이슨', '깨끗한 것만 newBrands 에 남는다', d.newBrands.join(','));
  check(d.newBrandsChrome.length === 2, '부속은 버리지 않고 newBrandsChrome 에 담는다',
    d.newBrandsChrome.join(','));
}

console.log('(i) computeDelta — 추가만 보고한다');
{
  const current = { brands: ['애플', '정관장'], cats: ['agri-rice', 'food'] };
  const analyzed = { confirmed: ['애플', '다이슨'], brandDirectory: ['테팔'], cats: ['가전', '과일'], textLen: 9000 };
  const mapCats = (cs) => cs.map((c) => (/가전/.test(c) ? 'appliance' : /과일/.test(c) ? 'agri-fruit' : null)).filter(Boolean);
  const d = computeDelta(current, analyzed, mapCats);
  check(d.newBrands.includes('다이슨') && d.newBrands.includes('테팔'), '새 브랜드를 잡는다');
  check(!d.newBrands.includes('애플'), '이미 있는 브랜드는 델타가 아니다');
  check(d.newCats.includes('appliance') && d.newCats.includes('agri-fruit'), '새 카테고리를 잡는다');
  check(!d.newCats.includes('food'), '이미 있는 카테고리는 델타가 아니다');
  check(d.thin === false, '본문이 충분하면 thin=false');
}
{
  // 기획전 회전으로 이번에 안 보인 브랜드가 "사라짐"으로 보고되면 안 된다.
  const current = { brands: ['닌텐도', 'KODAK'], cats: ['food'] };
  const d = computeDelta(current, { confirmed: [], brandDirectory: [], cats: [], textLen: 9000 }, () => []);
  check(d.newBrands.length === 0 && d.newCats.length === 0, '미관찰은 델타가 아니다(삭제 방향 보고 없음)');
  check(!('removedBrands' in d), '삭제 필드 자체가 없다 — 지우라는 신호를 만들지 않는다');
}
{
  // 수집이 반쯤 실패한 회차를 '변화 없음'으로 읽으면 안 된다(2026-08-21 공영쇼핑).
  const d = computeDelta({ brands: [], cats: [] }, { confirmed: [], cats: [], textLen: 354 }, () => []);
  check(d.thin === true, '본문이 얇으면 thin=true 로 표시');
}

console.log('(j) 배치 러너가 스니펫을 실행 가능한 형태로 쓰는가');
{
  // Playwright 의 evaluate 는 문자열을 **표현식**으로 평가한다. "() => {...}" 를 그대로
  // 넘기면 함수 객체가 나올 뿐 실행되지 않고, 직렬화도 안 돼 undefined 가 온다.
  // 2026-08-22 실제로 이 버그로 전 몰 수집이 실패했다 — 즉시 호출로 감싸야 한다.
  const runner = path.join(__dirname, '..', '..', 'backend', 'tools', 'survey_nightly.js');
  if (!fs.existsSync(runner)) {
    check(false, '배치 러너 파일 존재', runner);
  } else {
    const src = fs.readFileSync(runner, 'utf-8');
    check(/evaluate\(`\(\$\{COLLECT_SNIPPET\}\)\(\)`\)/.test(src),
          'evaluate 에 즉시 호출 형태 `(${COLLECT_SNIPPET})()` 로 넘긴다');
    check(!/evaluate\(COLLECT_SNIPPET\)/.test(src),
          '스니펫을 맨 문자열로 넘기지 않는다(실행되지 않는 형태)');
    check(/computeDelta\(/.test(src) && /mapCats/.test(src),
          '델타 계산에 프로브의 순수 함수를 쓴다(로직 복제 아님)');
    check(!/writeFileSync\([^)]*online_catalog/.test(src) && !/git\s+(commit|push)/.test(src),
          '카탈로그를 고치거나 git 을 건드리지 않는다(탐지 전용)');
  }
}

console.log('(k) 브랜드 표기 통일 — 같은 대상만 묶고, 다른 법인은 묶지 않는다');
{
  check(normalizeBrand('ECOVACS') === '에코백스', '영문 → 한글 음차');
  check(normalizeBrand('KODAK') === '코닥', 'KODAK → 코닥');
  check(normalizeBrand('씨제이제일제당') === 'CJ제일제당', '음차 → 통용 표기');
  check(normalizeBrand('삼성') === '삼성전자', '약칭 → 정식명');
  check(normalizeBrand('쿠쿠') === '쿠쿠', '사전에 없으면 그대로');
}
{
  // 이름이 겹쳐도 다른 법인·제품군이면 묶지 않는다. 묶으면 정보가 뭉개진다.
  for (const [a, b] of [['종근당', '종근당건강'], ['대상', '대상웰라이프'],
                        ['롯데', '롯데칠성'], ['삼양', '삼양식품'], ['아디다스', '아디다스골프']]) {
    check(normalizeBrand(a) !== normalizeBrand(b), `'${a}' 와 '${b}' 는 별개로 둔다`);
  }
}
{
  const out = normalizeBrands(['ECOVACS', '에코백스', '삼성', '삼성전자', '쿠쿠']);
  check(out.length === 3, '표준화 후 중복 제거', JSON.stringify(out));
  check(out.filter((x) => x === '에코백스').length === 1, '에코백스가 하나로');
  check(!out.includes('ECOVACS') && !out.includes('삼성'), '별칭 표기는 남지 않는다');
}
{
  // 이 동의어 처리가 없어서 매 회차 "새 브랜드 ECOVACS" 가 올라왔다(2026-08-23).
  const current = { brands: ['에코백스'], cats: [] };
  const d = computeDelta(current, { confirmed: ['ECOVACS', '쿠쿠'], cats: [], textLen: 9000 }, () => []);
  check(!d.newBrands.includes('ECOVACS'), '표기만 다른 브랜드는 델타가 아니다');
  check(d.newBrands.includes('쿠쿠'), '진짜 새 브랜드는 여전히 잡힌다', JSON.stringify(d.newBrands));
}
{
  // 별칭의 표준형이 사전 자체에 다시 별칭으로 등록돼 있으면 순환·미수렴이 생긴다.
  const bad = Object.values(BRAND_ALIASES).filter((v) => v in BRAND_ALIASES);
  check(bad.length === 0, '표준형이 다시 별칭으로 등록되지 않았다', JSON.stringify(bad));
}

console.log('(l) 카탈로그 데이터 계약 — 렌더가 모르는 값이 들어가 있지 않은가');
{
  const root = path.join(__dirname, '..', '..');
  const cat = JSON.parse(fs.readFileSync(path.join(root, 'data', 'online_catalog.json'), 'utf-8'));
  const plat = JSON.parse(fs.readFileSync(path.join(root, 'data', 'online_platforms.json'), 'utf-8'));

  // taxonomy 에 정의된 id 전부(대분류 + 소분류). 소분류까지 봐야 한다 —
  // 2026-08-23 에 'meat-chicken'(실제 id 는 meat-poultry)이 6개 몰에 들어가 있었고,
  // 대분류만 검사하던 탓에 'meat' 로 시작한다는 이유로 통과하고 있었다.
  const defined = new Set();
  for (const t of cat.taxonomy) {
    defined.add(t.id);
    for (const s of t.subs || []) defined.add(s.id);
  }
  const unknown = [];
  for (const it of cat.items) for (const c of it.cats) if (!defined.has(c)) unknown.push(`${it.id}:${c}`);
  check(unknown.length === 0, 'taxonomy 에 없는 cats 가 없다', JSON.stringify(unknown.slice(0, 8)));

  // 소분류 id 는 부모 대분류를 접두로 갖는다(렌더가 이 규칙에 기대지는 않지만, 어긋나면 오타 신호)
  const misprefixed = [];
  for (const t of cat.taxonomy) for (const s of t.subs || []) {
    if (!s.id.startsWith(t.id + '-')) misprefixed.push(`${t.id}/${s.id}`);
  }
  check(misprefixed.length === 0, '소분류 id 가 부모 접두를 따른다', JSON.stringify(misprefixed));

  // 카탈로그 항목은 플랫폼 목록에 실재해야 한다(online.html 이 '고아 id' 로 경고하는 조건)
  const platIds = new Set(plat.items.map((p) => p.id));
  const orphan = cat.items.map((i) => i.id).filter((id) => !platIds.has(id));
  check(orphan.length === 0, '카탈로그 id 가 모두 플랫폼 목록에 있다', JSON.stringify(orphan));

  // 브랜드 표기가 표준형인가 — 별칭이 데이터에 남아 있으면 필터가 갈라진다
  const stray = [];
  for (const it of cat.items) for (const b of it.brands) if (normalizeBrand(b) !== b) stray.push(`${it.id}:${b}`);
  check(stray.length === 0, '데이터에 별칭 표기가 남아 있지 않다', JSON.stringify(stray.slice(0, 8)));

  // 확인 못 한 항목의 날짜를 올리지 않았는가 — 화면 스탬프가 이 값으로 계산된다
  const okDates = cat.items.filter((i) => i.survey_status !== 'partial').map((i) => i.surveyed_on);
  const latest = okDates.reduce((m, d) => (d > m ? d : m), okDates[0]);
  const liars = cat.items.filter((i) => i.survey_status === 'partial' && i.surveyed_on === latest);
  check(liars.length === 0, 'partial 항목이 최신 확인일을 달고 있지 않다', JSON.stringify(liars.map((i) => i.id)));
}

console.log('(m) 소분류 세분화 — 2026-08-27 신설. 실측 로그(15_online_catalog_report.md)의 GNB 문구를 그대로 쓴다.');
{
  const cases = [
    ['김치/반찬', 'food-kimchi'],
    ['젓갈', 'food-banchan'],
    ['밀키트', 'food-mealkit'],
    ['양념', 'food-sauce'],
    ['유제품', 'food-dairy'],
    ['음료/커피', 'food-drink'],
    ['면/통조림', 'food-noodle'],
    ['과자', 'food-snack'],
    ['베이커리', 'food-bakery'],
    ['홍삼/인삼', 'health-ginseng'],
    ['비타민', 'health-supplement'],
    ['주방용품', 'living-kitchen'],
    ['생활용품', 'living-clean'],
    ['홈인테리어(가구·조명·침구)', 'living-interior'],
    ['잡화/만물', 'living-goods'],
    ['생활/주방/계절/미용가전', 'appliance-home'],
    ['계절가전', 'appliance-season'],
    ['컴퓨터·모바일', 'appliance-digital'],
    ['드론', 'appliance-camera'],
    ['스포츠·레저(캠핑/낚시)', 'hobby-sports'],
    ['도서/문구', 'hobby-book'],
    ['완구·취미', 'hobby-toy'],
    ['반려동물', 'hobby-pet'],
    ['견과(아몬드·호두·땅콩 등)', 'agri-nut'],
    ['김/해조류', 'fish-seaweed'],
  ];
  for (const [text, want] of cases) {
    const got = mapCats([text]);
    check(got.includes(want), `"${text}" → ${want}`, '실제: ' + JSON.stringify(got));
  }
}

console.log('(n) 소분류가 잡히면 부모 단독 id 는 빠진다 — meat 뿐 아니라 모든 대분류에');
{
  check(!mapCats(['김치']).includes('food'), 'food-kimchi 가 잡히면 food 단독 제외');
  check(!mapCats(['주방용품']).includes('living'), 'living-kitchen 이 잡히면 living 단독 제외');
  check(!mapCats(['드론']).includes('appliance'), 'appliance-camera 가 잡히면 appliance 단독 제외');
  check(mapCats(['가공식품']).includes('food'), '소분류가 안 잡히면 부모 food 유지(정보 손실 방지)');
}

console.log('(o) 스포츠 축 분리 — 입는 것은 패션, 용품·활동은 취미');
{
  check(mapCats(['스포츠의류']).includes('fashion-sports'), '스포츠의류 → fashion-sports');
  check(mapCats(['캠핑/낚시']).includes('hobby-sports'), '캠핑/낚시 → hobby-sports');
  check(!mapCats(['캠핑/낚시']).includes('fashion-sports'), '캠핑은 패션으로 잡히지 않는다');
}

console.log('(q) 데이터 계약 — 채록 대상은 survey_scope 를 반드시 적는다');
{
  /* 2026-09-06: 종전에는 주소 모양으로 "남의 기획전인가"를 추측했고, 그 휴리스틱이
     인어교주해적단·온누리팔도시장을 잘못 잡아 두 몰이 틀린 사유로 15일 넘게 갱신에서
     빠져 있었다. 이제 카탈로그가 적는다. 값이 없으면 반영 도구가 보류하므로,
     새 몰을 넣고 이 칸을 비우면 그 몰은 조용히 영영 갱신되지 않는다 —
     **그래서 여기서 먼저 깨뜨린다.** */
  const root = path.join(__dirname, '..', '..');
  const cat = JSON.parse(fs.readFileSync(path.join(root, 'data', 'online_catalog.json'), 'utf-8'));
  const targets = cat.items.filter((i) => i.survey_url);
  const missing = targets.filter((i) => !i.survey_scope).map((i) => i.id);
  check(missing.length === 0, '채록 대상 전부가 survey_scope 를 갖는다', JSON.stringify(missing));

  const bad = targets.filter((i) => !['mall', 'section'].includes(i.survey_scope))
    .map((i) => i.id + '=' + i.survey_scope);
  check(bad.length === 0, 'survey_scope 는 mall 또는 section 뿐이다', JSON.stringify(bad));

  // 두 값이 다 쓰이는지 본다 — 한쪽이 0이면 구분이 이름만 남고 실제로는 안 쓰이는 것이다.
  const mall = targets.filter((i) => i.survey_scope === 'mall').length;
  const sect = targets.filter((i) => i.survey_scope === 'section').length;
  check(mall > 0 && sect > 0, '두 값이 실제로 쓰인다 (mall ' + mall + ' · section ' + sect + ')');

  // 오분류로 15일 묶여 있던 두 곳을 이름으로 고정한다. 다시 section 이 되면 여기서 깨진다.
  for (const id of ['tpirates', 'onnuri-paldo-sijang']) {
    const it = targets.find((i) => i.id === id);
    check(it && it.survey_scope === 'mall',
      id + ' 는 그 몰 자신의 온누리 화면이다(mall)', it && it.survey_scope);
  }
}

console.log('(p) 데이터 계약 — CAT_RULES 의 소분류 id 는 부모 접두를 지킨다');
{
  const ids = CAT_RULES.map(([id]) => id);
  const tops = ids.filter((i) => !i.includes('-'));
  const bad = ids.filter((i) => i.includes('-') && !tops.includes(i.split('-')[0]));
  check(bad.length === 0, '모든 소분류 id 가 정의된 대분류 접두를 쓴다', '위반: ' + bad.join(', '));
}

console.log('(q) 수집 스니펫은 textContent 로 걷는다 — 숨은 메가메뉴 하위가 소분류 근거다');
{
  check(/a\.textContent/.test(COLLECT_SNIPPET),
        '카테고리 수집이 textContent 를 쓴다',
        'innerText 로 되돌아가면 display:none 인 하위 메뉴가 통째로 빠진다(2026-08-27 실측 43→132개)');
  check(!/const t = \(a\.innerText/.test(COLLECT_SNIPPET),
        '카테고리 수집에 innerText 를 쓰지 않는다');
  check(/document\.body\.innerText/.test(COLLECT_SNIPPET),
        '본문 text 는 innerText 유지 — 브랜드 매칭은 보이는 텍스트 기준이어야 오탐이 적다');
}

console.log('(r) 소분류 오탐 — 2026-08-27 전수 채록에서 실제로 걸린 것들. 지어낸 예 아님.');
{
  const notHave = (text, id) => {
    const got = mapCats([text]);
    check(!got.includes(id), `"${text}" 는 ${id} 가 아니다`, '실제: ' + JSON.stringify(got));
  };
  // '가전' 이 '특가전' 에 걸려 반찬·쿠폰 카테고리가 가전으로 잡혔다(가장 광범위한 오탐).
  notHave('할인쿠폰/특가전', 'appliance');
  notHave('반찬 특가전', 'appliance');
  notHave('디지털온누리상품권 사용처', 'appliance');
  notHave('2026 디지털전통시장', 'appliance');
  // '조류' 가 '해조류' 에, '펫' 이 '카펫' 에 걸렸다.
  notHave('건어물/해조류', 'hobby-pet');
  notHave('김/미역/해조류', 'hobby-pet');
  notHave('카펫/러그', 'hobby-pet');
  // 반려동물 먹이·영양제가 사람 간식·건강식품으로 잡혔다.
  notHave('강아지 간식', 'food-snack');
  notHave('고양이 간식', 'food-snack');
  notHave('강아지 영양제', 'health-supplement');
  notHave('고양이 영양제', 'health-supplement');
  notHave('화분영양제/비료', 'health-supplement');
  notHave('네일영양제', 'health-supplement');
  // 로얄젤리는 간식이 아니라 건강식품이다.
  notHave('프로폴리스/로얄젤리', 'food-snack');
  // '조미' 가 '무조미김/조미김'(= 김) 에 걸렸다.
  notHave('무조미김', 'food-sauce');
  notHave('조미김', 'food-sauce');
  // '면' 계열이 면도용품에 걸렸다.
  notHave('드라이기/면도기/이미용가전', 'food-noodle');
  notHave('구강/세안/면도', 'food-noodle');
  // 브랜드명 '오리온' 의 '오리'.
  notHave('오리온', 'meat-poultry');
  // 위생 마스크는 뷰티가 아니다.
  notHave('여성용품/마스크/의약외품', 'beauty');
  // 패션잡화는 생활잡화가 아니다.
  notHave('패션잡화', 'living-goods');
  notHave('의류/잡화', 'living-goods');
  notHave('가방/모자/잡화', 'living-goods');
}

console.log('(s) 오탐을 고쳐도 정탐은 살아 있어야 한다');
{
  const have = (text, id) => {
    const got = mapCats([text]);
    check(got.includes(id), `"${text}" → ${id} 유지`, '실제: ' + JSON.stringify(got));
  };
  have('가전·디지털', 'appliance');
  have('생활가전', 'appliance-home');
  have('반려동물', 'hobby-pet');
  have('강아지 사료', 'hobby-pet');
  have('관상어/수족관', 'hobby-pet');
  have('과자/간식', 'food-snack');
  have('비타민/영양제', 'health-supplement');
  have('양념/오일', 'food-sauce');
  have('면/통조림', 'food-noodle');
  have('닭/오리', 'meat-poultry');
  have('스킨케어', 'beauty');
  have('생활잡화', 'living-goods');
  have('건어물/해조류', 'fish-seaweed');
  have('조미김', 'fish-seaweed');
}

console.log('(m) CAT_RULES 사본 — 화면 검색이 같은 규칙을 쓴다');
{
  // 화면 검색은 지금까지 카테고리 **라벨**만 훑어 "로봇청소기"가 0곳이었다.
  // taxonomy 라벨은 '생활·주방가전'까지만 내려가고 몰 메뉴 원문에도 그 문구가 없다
  // (2026-09-02 6곳 재채록으로 확인). 채록 규칙을 화면에도 쓰면 새 사전이 필요 없다.
  const dumpPath = path.join(__dirname, '..', '..', 'data', 'cat_rules.json');
  const exists = fs.existsSync(dumpPath);
  check(exists, 'data/cat_rules.json 존재', '없으면 node _workspace/dev_scripts/dump_cat_rules.js');
  if (exists) {
    const dumped = JSON.parse(fs.readFileSync(dumpPath, 'utf8')).rules;
    check(dumped.length === CAT_RULES.length, '사본과 코드의 규칙 수가 같다',
      `사본 ${dumped.length} vs 코드 ${CAT_RULES.length}`);
    const mismatch = CAT_RULES.map(([cat, re], i) =>
      (dumped[i] && dumped[i].cat === cat && dumped[i].re === re.source) ? null : cat).filter(Boolean);
    check(mismatch.length === 0, '사본의 정규식이 코드와 일치',
      mismatch.length ? `어긋난 규칙: ${mismatch.join(', ')} — 사본을 다시 내보내라` : '');
  }
  const hit = (q) => CAT_RULES.filter(([, re]) => re.test(q)).map(([c]) => c);
  check(hit('로봇청소기').includes('appliance-home'), "'로봇청소기' → 생활·주방가전",
    `실제: ${hit('로봇청소기').join(', ') || '없음'}`);
  check(hit('노트북').includes('appliance-digital'), "'노트북' → 컴퓨터·모바일");
  check(hit('선풍기').includes('appliance-season'), "'선풍기' → 계절가전");
  check(hit('기저귀').includes('baby'), "'기저귀' → 출산·유아");
  // 아무 말에나 걸리면 검색이 넓어져 쓸모가 없어진다.
  check(hit('zzqqxyw12345').length === 0, '없는 말에는 카테고리가 걸리지 않는다',
    hit('zzqqxyw12345').join(', '));
}

// ── 브랜드 별칭 사본 일치 (2026-09-03 신설) ───────────────────────────────
// 화면(online.html)이 data/brand_aliases.json 을 읽어 `brand=삼성` 같은 착지 값을
// `삼성전자` 로 맞춘다. 코드만 고치고 사본을 안 내보내면 "채록은 삼성을 삼성전자로 보는데
// 화면은 못 찾는" 상태가 조용히 생긴다 — cat_rules.json 과 같은 이유로 일치를 고정한다.
{
  const aliasPath = path.join(__dirname, '..', '..', 'data', 'brand_aliases.json');
  const exists = fs.existsSync(aliasPath);
  check(exists, 'data/brand_aliases.json 존재', '없으면 node _workspace/dev_scripts/dump_brand_aliases.js');
  if (exists) {
    const dumped = JSON.parse(fs.readFileSync(aliasPath, 'utf8'));
    const a = dumped.aliases || {};
    const codeKeys = Object.keys(BRAND_ALIASES).sort();
    const fileKeys = Object.keys(a).sort();
    check(JSON.stringify(codeKeys) === JSON.stringify(fileKeys),
      '별칭 키 집합 일치', `코드 ${codeKeys.length} vs 사본 ${fileKeys.length}`);
    check(codeKeys.every((k) => a[k] === BRAND_ALIASES[k]),
      '별칭 값 일치', '값이 다른 키가 있다');
    check(a['삼성'] === '삼성전자' && a['LG'] === 'LG전자',
      '대표 별칭이 사본에 있다(삼성→삼성전자·LG→LG전자)', JSON.stringify({s: a['삼성'], l: a['LG']}));
  }
}

// ── 최종 판정 ─────────────────────────────────────────────────────────────
// 2026-09-04: 이 두 줄이 **파일 중간(429행)에 있었다.** 그 뒤로 (m) CAT_RULES 사본 8건과
// 브랜드 별칭 사본 4건, 합계 12건이 게이트 밖에서 돌아 **실패해도 종료 코드가 0** 이었고,
// 화면에는 [FAIL] 이 떠 있는데 마지막 줄은 문자 그대로 `전체 통과` 라고 찍혔다.
// 재현: data/cat_rules.json 규칙 하나를 지우면 FAIL 2건인데 EXIT=0.
// 하필 그 12건이 **코드와 데이터 사본의 일치**를 지키는 검사다 — 사본을 안 내보내면
// "채록은 로봇청소기를 가전으로 보는데 화면은 못 찾는" 상태가 조용히 생기는 바로 그 자리.
// 새 블록은 반드시 이 아래가 아니라 **이 줄 위**에 넣어라.
console.log();
if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
console.log(`전체 통과 (${pass}건)`);
