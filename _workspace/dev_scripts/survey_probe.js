/**
 * survey_probe.js — 온라인 사용처 취급품목·브랜드 채록 프로브 (2026-08-19 신설, 2026-08-21 실전 반영)
 *
 * 용도: online_catalog.json 재실측 시 각 몰에서 동일한 방식으로 카테고리·브랜드를 채록한다.
 *
 * 사용(몰 1곳):
 *   1) Playwright(MCP) browser_navigate 로 몰 접속
 *   2) browser_evaluate 에 COLLECT_SNIPPET 을 넣고 filename 으로 결과 저장
 *      (원문이 수 KB~수십 KB 라 화면으로 받으면 낭비다)
 *   3) node _workspace/dev_scripts/survey_run.js <저장된파일> 로 판정 결과 확인
 *
 * QA: node _workspace/dev_scripts/test_survey_probe.js
 *
 * 설계 원칙 — 이 파일이 존재하는 이유:
 *   1) 22개 몰을 "같은 잣대"로 봐야 델타가 의미를 가진다. 몰마다 즉흥 스크립트를 쓰면
 *      이번에 잡힌 변화가 진짜 변화인지 관찰 방법이 달라진 탓인지 구분할 수 없다.
 *   2) 브랜드 매칭은 단순 문자열 포함으로 하면 반드시 오탐한다. 2026-08-21 실측에서
 *      '파인애플'→애플, '시장대상가'→대상, 'ZH-LG901G'→LG 가 실제로 걸렸다.
 *   3) 목록 추출을 끝내는 조건에 `원$` 같은 패턴을 쓰면 '뉴트리원'에서 목록이 잘린다.
 *      실제로 온누리찬스 브랜드관이 9개에서 끊겼다(정답 133개).
 */

'use strict';

/** 한글·영문·숫자 = 단어를 이루는 문자. 브랜드명 경계 판정에 쓴다. */
const WORDISH = /[0-9A-Za-z가-힣]/;

/**
 * 텍스트에서 브랜드 사전을 매칭하되, 더 긴 단어의 일부인 경우를 걸러낸다.
 *
 * 브랜드명 앞뒤에 한글/영문/숫자가 붙어 있으면 다른 단어의 조각일 가능성이 높다:
 *   '파인애플'의 애플, '시장대상가'의 대상, 'ZH-LG901G'의 LG.
 * 이런 건 제외하지 않고 suspect 로 표시해 사람이 판정하게 한다 —
 * 자동으로 버리면 'LG전자'처럼 정당한 접미 결합까지 사라진다.
 *
 * @param {string} text  페이지 전체 텍스트
 * @param {string[]} dict 브랜드 사전
 * @param {number} pad   문맥으로 함께 반환할 앞뒤 글자 수
 * @returns {{brand:string, ctx:string, suspect:boolean, count:number}[]}
 */
function matchBrands(text, dict, pad = 20) {
  const out = [];
  for (const brand of dict) {
    if (!brand) continue;
    const hits = [];
    let from = 0;
    for (;;) {
      const i = text.indexOf(brand, from);
      if (i < 0) break;
      hits.push(i);
      from = i + brand.length;
      if (hits.length > 200) break; // 폭주 방지
    }
    if (!hits.length) continue;

    const ctxOf = (i) => text
      .slice(Math.max(0, i - pad), i + brand.length + pad)
      .replace(/\s+/g, ' ')
      .trim();

    // 경계가 깨끗한 출현이 하나라도 있으면 그것을 대표 문맥으로 쓴다.
    let chosen = -1;
    let clean = false;
    for (const i of hits) {
      const before = i > 0 ? text[i - 1] : '';
      const after = text[i + brand.length] || '';
      const ok = !(WORDISH.test(before) || WORDISH.test(after));
      if (ok) { chosen = i; clean = true; break; }
      if (chosen < 0) chosen = i;
    }

    // 경계 규칙으로 걸러지지 않는 오탐이 있다 — '애플 사이다 비니거'의 애플은
    // 앞뒤가 공백이라 규칙상 깨끗하다(2026-08-21 꾹AI 실측). 그래서 대표 문맥 하나만
    // 보여주면 사람이 오판한다. 출현 문맥을 여러 개 함께 넘겨 판정할 수 있게 한다.
    out.push({
      brand,
      ctx: ctxOf(chosen),
      ctxs: hits.slice(0, 3).map(ctxOf),
      suspect: !clean,
      count: hits.length,
    });
  }
  return out;
}

/**
 * '브랜드관' 같은 표지 다음에 이어지는 짧은 항목 나열을 뽑는다.
 *
 * 끝나는 지점은 "상품 영역이 시작됐다"는 신호로 판정한다. 가격·배송·별점처럼
 * 상품 카드에만 나오는 패턴을 쓰고, 브랜드명 자체에 나타날 수 있는 글자
 * ('원'으로 끝나는 뉴트리원 등)는 절대 종료 조건에 넣지 않는다.
 *
 * @param {string} text        페이지 전체 텍스트
 * @param {string} marker      나열이 시작되는 표지 (예: '브랜드관')
 * @param {object} [opt]
 * @param {number} [opt.maxLen=16]   항목으로 인정할 최대 길이
 * @param {number} [opt.tolerance=2] 연속 비항목 몇 줄까지 참고 넘어갈지
 * @returns {string[]}
 */
function extractListSegment(text, marker, opt = {}) {
  const maxLen = opt.maxLen ?? 16;
  const tolerance = opt.tolerance ?? 2;
  const at = text.indexOf(marker);
  if (at < 0) return [];

  // 가격·배송·별점·수량 = 상품 카드 신호. 브랜드명에는 나타나지 않는 것만 골랐다.
  // `원$` 을 넣으면 안 된다 — '뉴트리원'에서 목록이 끊긴다(2026-08-21 실제 사고).
  // 가격은 숫자(\d{2,})로 이미 걸리므로 '원'을 따로 볼 이유가 없다.
  const PRODUCT_SIGNAL = /\d{2,}|[₩$]|무료배송|배송비|★|리뷰|후기|할인|더보기|장바구니|로그인|검색|%/;

  const lines = text.slice(at).split('\n').map((s) => s.trim()).filter(Boolean).slice(1);
  const out = [];
  let miss = 0;
  for (const line of lines) {
    const isItem = line.length <= maxLen && !PRODUCT_SIGNAL.test(line);
    if (isItem) { out.push(line); miss = 0; continue; }
    if (++miss >= tolerance) break;
  }
  return out;
}

/**
 * 브라우저에서 실행하는 **수집** 단계. Playwright browser_evaluate 에 넣는다.
 *
 * 판정 로직은 여기 두지 않는다. 브라우저로 보내는 코드는 이 파일과 별개의 사본이 되어
 * 테스트가 검증한 것과 실제로 돈 것이 달라질 수 있기 때문이다. 그래서 이 단계는
 * 원문(innerText)과 링크 텍스트만 걷어오고, 매칭·목록 추출은 Node 에서 analyze() 가 한다.
 *
 * 이 함수는 문자열로 만들어 evaluate 에 넘긴다: COLLECT_SNIPPET 참조.
 */
const COLLECT_SNIPPET = `() => {
  const text = document.body ? (document.body.innerText || '') : '';
  const NAV = ['nav a','header a','li a','[class*="gnb"] a','[class*="category"] a',
               '[class*="cate"] a','[class*="menu"] a','[class*="tab"] a',
               '[id*="gnb"] a','[id*="category"] a'];
  const cats = new Set();
  for (const sel of NAV) {
    for (const a of document.querySelectorAll(sel)) {
      const t = (a.innerText || '').trim().replace(/\\s+/g, ' ');
      if (t && t.length <= 18 && !/^https?:/.test(t)) cats.add(t);
    }
  }
  const brandLinks = [...document.querySelectorAll('a')]
    .filter(a => /브랜드/.test(a.innerText || '') || /brand/i.test(a.getAttribute('href') || ''))
    .map(a => ({ text: (a.innerText || '').trim().slice(0, 24), href: a.href }))
    .slice(0, 8);
  return JSON.stringify({
    title: document.title, url: location.href,
    text, cats: [...cats].slice(0, 140), brandLinks
  });
}`;

/**
 * Node 에서 도는 **분석** 단계. 수집 결과(raw)를 받아 브랜드·목록을 뽑는다.
 * 테스트가 검증하는 것과 실제 실측에 쓰이는 것이 같은 코드가 되도록 여기로 모았다.
 *
 * @param {{title?:string,url?:string,text:string,cats?:string[],brandLinks?:object[]}} raw
 * @param {string[]} [dict]
 */
function analyze(raw, dict = BRAND_DICT) {
  const text = (raw && raw.text) || '';
  const brands = matchBrands(text, dict);
  return {
    title: raw.title || '',
    url: raw.url || '',
    textLen: text.length,
    cats: raw.cats || [],
    brandLinks: raw.brandLinks || [],
    brandDirectory: extractListSegment(text, '브랜드관'),
    confirmed: brands.filter((b) => !b.suspect).map((b) => b.brand),
    suspect: brands.filter((b) => b.suspect).map((b) => ({ brand: b.brand, ctx: b.ctx })),
    contexts: brands.map((b) => ({ brand: b.brand, count: b.count, ctxs: b.ctxs })),
  };
}

/** 22개 몰 공통 브랜드 사전. 실측에서 한 번이라도 확인된 것 + 흔한 유통 브랜드. */
const BRAND_DICT = [
  '삼성전자', '삼성', 'LG전자', 'LG', '애플', '닌텐도', '플레이스테이션', 'DJI', '코닥', 'KODAK',
  '다이슨', '로보락', '에코백스', 'ECOVACS', '샤크닌자', '블랙앤데커', '브라운', '필립스', '테팔',
  '쿠쿠', '쿠첸', '코렐', '콕스타', '네오플램', '해피콜', '락앤락', '한경희', '위니아', '신일', '파세코',
  '리큅', '보만', '보랄', '루메나', '브리츠', '아이나비', '아이리버', '로지텍', '샤오미', '고프로',
  '가민', '샥즈', '마샬', '스탠리', '프리웰', '아이젠', 'NEXTU', '넥스트유', '알텐바흐',
  '네스프레소', '돌체구스토', '스타벅스', '맥심', '카누', '칸타타',
  '정관장', '일양약품', '종근당', '코오롱제약', '셀트리온', '뉴트리원', '고려은단', '광동제약',
  '농심', '오뚜기', 'CJ제일제당', '씨제이제일제당', '동원', '대상', '청정원', '종가집', '풀무원',
  '비비고', '하림', '해태', '오리온', '크라운제과', '삼양', '롯데', '빙그레', '매일유업', '매일',
  '도미솔', '창억', '피코크', '빕스',
  '깨끗한나라', '유한킴벌리', '삼정펄프', '피죤',
  '나이키', '아디다스', '푸마', '휠라', '뉴발란스', '크록스', '디스커버리', '내셔널지오그래픽',
  '스투시', '휴고보스', '디올', '엘르', '기라로쉬', '쌤소나이트', '탠디', '커터앤벅',
  '설화수', '에스티로더', '오휘', 'AHC', '이니스프리', '코리아나', '조말론', 'vt코스메틱',
  '리파', '가히', '피지오겔', '아토팜', '리얼베리어', '트라이앵글', '오아',
  '한샘', '듀오백', '알레르망', '아망떼', '코스타노바', '제니퍼룸', '헤이홈', '에브리봇',
  '카카오프렌즈', '레고', '삼천리 자전거', '아이키움북',
];

// 브라우저(Playwright evaluate)와 Node(test) 양쪽에서 쓸 수 있게 내보낸다.
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { matchBrands, extractListSegment, analyze, COLLECT_SNIPPET, BRAND_DICT, WORDISH };
}
