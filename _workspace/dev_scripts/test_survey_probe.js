/**
 * test_survey_probe.js — 채록 프로브 QA (2026-08-21)
 *
 * 케이스는 전부 2026-08-21 실측에서 **실제로 겪은** 오탐·절단이다. 지어낸 예가 아니다.
 * 실행: node _workspace/dev_scripts/test_survey_probe.js
 */
'use strict';
const { matchBrands, extractListSegment, analyze, COLLECT_SNIPPET, BRAND_DICT } = require('./survey_probe.js');

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

console.log();
if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
console.log(`전체 통과 (${pass}건)`);
