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
      // textContent 를 쓴다(innerText 아님). 메가메뉴 하위 카테고리는 display:none 이라
      // innerText 가 빈 문자열이 되어 최상위 메뉴만 걷혔다 — 2026-08-27 온누리핫딜 실측에서
      // 43개 → 132개. 소분류 채록이 그동안 불가능했던 원인이다.
      const t = (a.textContent || '').trim().replace(/\\s+/g, ' ');
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

/**
 * 브랜드 표기 통일 사전 — 같은 브랜드가 몰마다 다르게 적히는 것을 하나로 모은다.
 *
 * 넣는 기준은 좁다. **같은 대상임이 분명한 표기 차이만** 넣는다:
 *   ① 영문/한글 음차 (ECOVACS ↔ 에코백스)
 *   ② 약칭/정식명이 온누리 맥락에서 명백히 같은 것 (삼성 → 삼성전자 감사 페스티벌)
 *
 * 넣지 않는 것 — 이름이 겹쳐도 **다른 법인이거나 다른 제품군**인 경우:
 *   종근당 / 종근당건강, 대상 / 대상웰라이프, 롯데 / 롯데칠성,
 *   삼양 / 삼양식품, 아디다스 / 아디다스골프
 * 이런 걸 묶으면 "종근당 골라서 봤더니 건강기능식품만 나오는" 식으로 정보가 뭉개진다.
 * 통합은 되돌리기 어려운 방향이라, 확실하지 않으면 두 표기를 그대로 둔다.
 *
 * 표준 표기는 "이용자가 검색창에 칠 법한 쪽"으로 골랐다.
 */
const BRAND_ALIASES = {
  // 영문 → 한글 음차
  ECOVACS: '에코백스',
  KODAK: '코닥',
  NEXTU: '넥스트유',
  Freewell: '프리웰',
  // 한글 음차 → 널리 쓰이는 표기
  씨제이제일제당: 'CJ제일제당',
  // 약칭 → 정식명(온누리 실측 문맥에서 대상이 하나로 확정되는 것만)
  삼성: '삼성전자',
  LG: 'LG전자',
  매일: '매일유업',
};

/** 브랜드 표기를 표준형으로 바꾼다(사전에 없으면 그대로). */
function normalizeBrand(name) {
  return BRAND_ALIASES[name] || name;
}

/** 브랜드 배열을 표준화하고 중복을 없앤다(입력 순서 유지). */
function normalizeBrands(list) {
  return [...new Set((list || []).map(normalizeBrand))];
}

/**
 * 사이트 카테고리 문구 → taxonomy id 매핑.
 *
 * 몰마다 카테고리 이름이 다르다("정육"·"축산"·"육류"·"소고기"). 같은 축으로 묶어야
 * 델타가 "이 몰이 새 품목을 취급하기 시작했다"를 뜻하게 된다.
 * 주의: 이 매핑은 **후보를 만드는 도구**다. 기획전 딥링크 몰은 몰 전체 GNB 가 섞여
 * 들어오므로(2026-08-21 롯데ON), 최종 반영 전에 사람이 범위를 확인해야 한다.
 */
const CAT_RULES = [
  // 2026-08-27 세분화: 대분류 11 + 소분류 35. 각 패턴은 15_online_catalog_report.md 의
  // 실측 GNB 문구에서 뽑았다. 소분류가 잡히면 부모 단독 id 는 mapCats 가 걷어낸다.
  ['agri-rice', /쌀|잡곡|곡류|곡물|현미|찹쌀|흑미|보리|귀리|백미/],
  ['agri-nut', /견과|아몬드|호두|땅콩|캐슈|피스타치오|잣\b/],
  ['agri-veg', /채소|고구마|감자|옥수수|버섯|나물|배추|무\b|양파|당근|쌈|단호박/],
  ['agri-fruit', /과일|사과|배\b|수박|메론|멜론|참외|귤|만감|자두|복숭아|토마토|곶감|망고|키위|체리|석류|포도|딸기|블루베리|바나나|샤인머스켓/],
  ['agri', /농산|청과/],
  ['fish-fresh', /생선|해산물|어패|활어|회\b|오징어|낙지|문어|전복|굴\b|조개|새우|게\b|장어|고등어|갈치|광어|참돔|멍게|물회|초밥/],
  ['fish-seaweed', /해조|김\/|김·|\/김|미역|다시마|파래|곱창김|조미김/],
  ['fish-dried', /건어물|멸치|황태|굴비|건해산|건조 ?생선|먹태|쥐포/],
  ['fish', /수산/],
  ['meat-beef', /소고기|쇠고기|한우|육우|우육/],
  ['meat-pork', /돼지|돈육|한돈|삼겹|목살|족발/],
  ['meat-poultry', /닭|오리(?!온)|계란|알류|양고기|양\/|훈제/],
  ['meat', /축산|정육|육류/],
  ['food-kimchi', /김치/],
  ['food-banchan', /반찬|젓갈|장아찌|밑반찬/],
  ['food-mealkit', /밀키트|간편식|즉석|델리|냉동식품|만두|국\/탕|탕\/찌개/],
  ['food-sauce', /양념|장류|조미(?!김)|참기름|들기름|고춧가루|소스|식용유|올리브유|간장|된장|고추장/],
  ['food-dairy', /유제품|우유|두유|치즈|요거트|요구르트|두부/],
  ['food-drink', /음료|커피|생수|주류|전통주|막걸리|티백|녹차|홍차|차류|茶/],
  ['food-noodle', /면\/통|면류|라면|국수|파스타|통조림|즉석밥\/|캔\b/],
  ['food-snack', /(?<!강아지 ?)(?<!고양이 ?)(?<!반려 ?)(?<!애완 ?)(?:과자|간식|스낵|초콜릿|사탕)|(?<!로얄)젤리/],
  ['food-bakery', /베이커리|빵\b|떡|한과|약과|케이크|파이\b|누룽지|선식/],
  ['food', /가공식품|전통식품|축산가공|농산가공|수산가공|분식|식품관|먹거리|꿀|조청/],
  ['health-ginseng', /홍삼|인삼|수삼|녹용/],
  ['health-supplement', /비타민|(?<!강아지 ?)(?<!고양이 ?)(?<!반려 ?)(?<!화분)(?<!네일)(?<!조류 ?)영양제|유산균|프로폴리스|건강기능|영양보충|헬스보충|루테인|오메가/],
  ['health', /건강식품|건강즙|건강액|다이어트|이너뷰티|약초|건강\/|건강·/],
  ['living-kitchen', /주방용품|주방잡화|그릇|냄비|프라이팬|조리도구|텀블러|밀폐|도시락|수저|주방\/|주방·|보온|보냉|주전자/],
  ['living-clean', /세제|세탁|청소|욕실|화장지|물티슈|생리대|위생|생활용품|생활필수품|일회용품|생활\//],
  ['living-interior', /침구|커튼|가구|인테리어|조명|홈패브릭|카펫|러그|리빙|수납|정리|홈데코/],
  ['living-goods', /생활잡화|가정생활잡화|기타생활잡화|만물|(?<!패션)(?<!의류\/)(?<!가방\/)(?<!모자\/)(?<!스포츠 )(?<!유아동)잡화가게/],
  ['living', /공구|전기자재|안마|찜질|의료용품|생활\/건강/],
  ['appliance-home', /생활가전|주방가전|생활\/주방|미용가전|이미용가전|건강가전|청소기|밥솥|정수기/],
  ['appliance-season', /계절가전|계절\/|제습|선풍기|에어컨|난방|히터|온풍기/],
  ['appliance-digital', /컴퓨터|노트북|데스크톱|모니터|프린터|태블릿|휴대폰|모바일|스마트기기|주변기기|저장장치|게임기|콘솔/],
  ['appliance-camera', /카메라|드론|캠코더|짐벌|액션캠/],
  ['appliance', /(?<!특)가전|디지털(?! ?온누리| ?전통시장|온누리)|TV\b|영상기기|음향기기|세탁기|건조기|냉장고/],
  ['fashion-sports', /스포츠 ?의류|스포츠 ?웨어|스포츠 ?신발|골프 ?의류|골프 ?웨어|등산복|운동화|트레이닝복/],
  ['fashion-casual', /의류|옷가게|캐주얼|언더웨어|잠옷|신발|가방|캐리어|지갑|벨트|모자|머플러|장갑|양말|쥬얼리|주얼리|시계|선글라스|패션잡화|패션\/|속옷|데일리룩/],
  ['fashion', /패션|의복/],
  ['beauty', /뷰티|화장품|스킨케어|메이크업|선케어|클렌징|필링|향수|헤어케어|바디케어|네일|이미용|마스크팩|시트마스크|세안|남성화장품|뷰티소품/],
  ['hobby-sports', /캠핑|낚시|자전거|헬스\b|요가|필라테스|수영|스키|보드\b|구기|라켓|레저|등산(?!복)|아웃도어|골프(?! ?의류| ?웨어)|스포츠(?! ?의류| ?웨어| ?신발)/],
  ['hobby-book', /도서|문구|사무용품|필기구|서적|만화/],
  ['hobby-toy', /완구|장난감|피규어|프라모델|보드게임|취미|악기|화방|수집|파티용품/],
  ['hobby-pet', /반려|애완|강아지|고양이|(?<!카)펫|관상어|수족관|소동물|반려조|앵무|햄스터/],
  ['hobby', /꽃|원예|가드닝|화분|종교용품/],
  ['baby', /출산|유아|육아|기저귀|분유|수유|유모차|카시트|아기띠|힙시트|신생아|유아동|아동\/주니어|어린이/],
];

/** 사이트 카테고리 문구 배열 → taxonomy id 배열 */
function mapCats(cats) {
  const hit = new Set();
  for (const c of cats || []) {
    for (const [id, re] of CAT_RULES) if (re.test(c)) hit.add(id);
  }
  // 소분류가 잡혔으면 그 부모 단독 항목은 뺀다(기존 데이터 관례).
  // 2026-08-27: meat 하드코딩이던 것을 모든 대분류로 일반화 — 소분류가 35개로 늘어
  // 하드코딩으로는 감당이 안 되고, 빠뜨린 부모가 남으면 필터 카운트가 부풀려진다.
  const parentsWithSub = new Set();
  for (const x of hit) if (x.includes('-')) parentsWithSub.add(x.split('-')[0]);
  const out = [...hit].filter((x) => !(!x.includes('-') && parentsWithSub.has(x)));
  return out.sort();
}

/**
 * 오늘 돌아볼 몰을 고른다. 22곳을 매일 전부 훑으면 상대 사이트에 부담이고 대부분의
 * 회차가 "변화 없음"이 된다. 하루 3~4곳씩 돌려 일주일에 한 바퀴 돈다.
 *
 * 나누는 기준은 목록 순서(고정)와 날짜다 — 무작위가 아니라 결정적이어야 어제 뭘 봤는지
 * 재현할 수 있고, 특정 몰이 영영 선택되지 않는 일도 없다.
 *
 * @param {string[]} ids   전체 대상 id (정렬된 고정 순서)
 * @param {Date} today
 * @param {number} [cycle=7] 며칠에 한 바퀴
 */
function todaysSlice(ids, today, cycle = 7) {
  // 로컬 자정 기준 일수를 쓴다. getTime()/86400000 은 UTC 기준이라, 배치가 도는
  // KST 00:30 은 UTC 로 전날 15:30 이 되어 하루 전 묶음이 뽑힌다. 순환 자체는
  // 어느 쪽이든 7일에 한 바퀴지만, 로그·리포트 날짜(로컬)와 기준이 어긋나면
  // "오늘 어디를 봤나"를 대조할 수 없다.
  const epochDay = Math.floor((today.getTime() - today.getTimezoneOffset() * 60000) / 86400000);
  const bucket = ((epochDay % cycle) + cycle) % cycle;
  return ids.filter((_, i) => i % cycle === bucket);
}

/** 로컬 기준 YYYY-MM-DD (toISOString 은 UTC 라 KST 새벽에 전날로 찍힌다) */
function localDate(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 로컬 기준 YYYY-MM-DD HH:MM:SS — 배치 로그(파이썬)와 같은 표기 */
function localStamp(d = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${localDate(d)} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 현재 카탈로그 항목과 이번 채록 결과를 비교한다.
 *
 * 방향을 하나로 고정한다: **추가만 보고한다.** 이번에 안 보였다고 사라진 것이 아니다 —
 * 기획전은 회전하고, 지연 로드로 절반만 걷힌 회차도 있다(2026-08-21 공영쇼핑 실측).
 * "사라짐"을 델타로 올리면 그걸 본 사람이 데이터를 지우게 되고, 다음 달에 되돌아온다.
 *
 * @param {{cats?:string[], brands?:string[]}} current  online_catalog.json 의 항목
 * @param {{confirmed?:string[], brandDirectory?:string[], cats?:string[]}} analyzed
 * @param {(cats:string[])=>string[]} mapCats  사이트 카테고리 → taxonomy id 매핑
 */
function computeDelta(current, analyzed, mapCats) {
  // 양쪽 다 표준 표기로 맞춘 뒤 비교한다. 안 그러면 'ECOVACS' 가 '에코백스' 와
  // 다른 값으로 잡혀 매 회차 "새 브랜드"로 올라온다(2026-08-23 실제로 그랬다).
  const curB = new Set(normalizeBrands(current.brands));
  const curC = new Set(current.cats || []);
  const seenB = normalizeBrands([...(analyzed.confirmed || []), ...(analyzed.brandDirectory || [])]);
  const seenC = mapCats ? mapCats(analyzed.cats || []) : [];
  return {
    newBrands: seenB.filter((b) => !curB.has(b)),
    newCats: seenC.filter((c) => !curC.has(c)),
    seenBrandCount: seenB.length,
    seenCatCount: seenC.length,
    // 수집이 반쯤 실패한 회차를 "변화 없음"으로 읽으면 안 된다.
    thin: (analyzed.textLen || 0) < 1500,
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
  module.exports = { matchBrands, extractListSegment, analyze, computeDelta, todaysSlice, localDate, localStamp, mapCats, normalizeBrand, normalizeBrands, BRAND_ALIASES, CAT_RULES, COLLECT_SNIPPET, BRAND_DICT, WORDISH };
}
