/* 브랜드 검색 팝업 — 실동작 14경로 (2026-09-05)
   merchants-brandmodal.js 분리를 위해 만든 것이다. 정적 계약이 "계약이 있다"를 보고,
   여기서는 "실제로 열리고 찾아지고 골라져 목록이 좁혀지는가"를 본다.

   실행: python3 -m http.server 8655 후 node _workspace/dev_scripts/test_brandmodal_live.js
   **포트 8655 고정** — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라 다른 포트는 401. */
const { chromium } = require('/Users/koscom/Projects/auto_stock/node_modules/playwright');
/* 기본은 로컬(포트 8655 고정 — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라
   다른 포트는 401). 배포 뒤 **배달된 것**을 재려면 기준 주소를 바꿔 준다:
     ONNURI_BASE=https://onnuri.koscomlabor.cloud node <이 파일>
   로컬 통과와 라이브 통과는 다른 질문이다 — 캐시버스트를 빠뜨리면 옛 파일이 나간다. */
const BASE = process.env.ONNURI_BASE || 'http://localhost:8655';
const U = BASE + '/merchants.html?region=%EC%84%9C%EC%9A%B8';
let fail = 0;
/* 감시 시계 — 매달리면 **실패로** 끝나야 한다. 변조 실험을 돌리다 한 변종이 8분 넘게
   매달려 배치 전체가 죽었다(그때는 '못 잡았다'와 '멈췄다'가 구분되지 않았다). */
const WATCHDOG = setTimeout(() => { console.log('\nFAIL 시간 초과(150초)'); process.exit(1); }, 150000);
WATCHDOG.unref && WATCHDOG.unref();
const ck = (o, t, d) => { console.log(`  [${o ? 'PASS' : 'FAIL'}] ${t}${d ? ' — ' + d : ''}`); if (!o) fail++; };
(async () => {
  const b = await chromium.launch({ channel: 'chrome' });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
  await p.goto(U, { waitUntil: 'domcontentloaded' });
  await p.evaluate(() => { localStorage.clear(); sessionStorage.setItem('onnuri_chat_closed', '1'); });
  await p.reload({ waitUntil: 'domcontentloaded' }); await p.waitForTimeout(8000);

  ck(await p.evaluate(() => !!window.OnnuriBrandModal
    && ['attach', 'wire', 'open', 'close', 'isOpen'].every((k) => typeof OnnuriBrandModal[k] === 'function')),
    '① 모듈 로드·계약 5종');
  const total0 = await p.evaluate(() => (document.querySelector('#countLine') || document.querySelector('.count') || document.body).textContent.slice(0, 60));
  await p.click('.brand-find'); await p.waitForTimeout(2500);
  const o = await p.evaluate(() => ({
    open: !document.querySelector('#brandModal').hidden,
    items: document.querySelectorAll('.bm-item').length,
    heads: document.querySelectorAll('.bm-head').length,
    idx: document.querySelectorAll('#bmIndex li').length,
    live: document.querySelectorAll('#bmIndex li:not(.disabled)').length,
    hint: (document.querySelector('#bmScopeHint') || {}).textContent || '',
    lock: document.querySelector('#bmCat').disabled,
  }));
  ck(o.open, '② 팝업이 열린다');
  ck(o.items > 100, '③ 브랜드 목록이 채워진다', o.items + '개');
  ck(o.heads > 5 && o.idx === 41, '④ 초성/알파벳 색인이 그려진다', '헤더' + o.heads + '/색인' + o.idx);
  ck(o.live > 5 && o.live < o.idx, '⑤ 결과에 있는 초성만 활성', o.live + '/' + o.idx);
  // 고정 접두에 '서울·인천·경기·부산'이 들어 있어 `/서울/` 로 재면 게터를 비워도 통과한다
  // (변조 실험에서 실제로 걸렸다). **좁혀질 범위 자리**를 집어서 본다.
  ck(/브랜드를 고르면 서울의 목록으로 좁혀지고/.test(o.hint),
    '⑥ 안내 문구가 좁혀질 범위를 말한다(게터)', o.hint.slice(0, 52));
  ck(o.lock === false, '⑦ 업종 전체일 때 업종 콤보가 열려 있다');
  // ⑧ 부분검색
  // 질의어는 **이 데이터에 실제로 있는 것**으로. 처음에 '삼성'으로 썼다가 0건이 나왔는데
  // 결함이 아니라 서울 가맹점 브랜드에 삼성이 없었다(삼익가구·삼천리자전거는 있다).
  await p.fill('#bmQ', '삼'); await p.waitForTimeout(500);
  const q = await p.evaluate(() => [...document.querySelectorAll('.bm-item span:first-child')].map((e) => e.textContent));
  const hits = q.filter((t) => t !== '브랜드 전체(해제)');
  ck(hits.length >= 2 && hits.every((t) => t.indexOf('삼') !== -1), '⑧ 부분검색이 걸린다', hits.slice(0, 4).join(','));
  // ⑨ 색인 점프 — 검색어를 비우고 위로 올라가는 방향으로
  await p.fill('#bmQ', ''); await p.waitForTimeout(500);
    // **맨 아래**에서 시작해야 '위로 올라가는 방향'을 재는 것이 된다. 4000 으로 고정했더니
  // 고른 중간 초성이 그보다 아래라 내려가는 방향을 재고 있었다.
  await p.evaluate(() => { const l = document.querySelector('#bmList'); l.scrollTop = l.scrollHeight; });
  await p.waitForTimeout(200);
  const before = await p.evaluate(() => document.querySelector('#bmList').scrollTop);
  // **중간 초성**을 고른다. 첫 초성(ㄱ)으로 재면 목표가 목록 맨 위라, sticky 보정을
  // 되돌린 변종의 결과(목록 자체의 화면 좌표 ≈166px)와 정답이 우연히 겹쳐 통과한다
  // — 변조 실험에서 실제로 걸렸다.
  const key = await p.evaluate(() => {
    const live = [...document.querySelectorAll('#bmIndex li:not(.disabled)')];
    const li = live[Math.floor(live.length / 2)];
    li.click(); return li.textContent;
  });
  await p.waitForTimeout(900);
  // '줄었다'만 보면 안 된다 — 보정을 되돌리면 음수가 나오고 scrollTo 가 0 으로 깎아
  // 여전히 줄어든다(변조 실험에서 걸렸다). **고른 초성 머리가 목록 맨 위에 왔는가**를 본다.
  const after = await p.evaluate((k) => {
    window.__jumpKey = k;
    const list = document.querySelector('#bmList');
    const hd = list.querySelector('.bm-head[data-init="' + window.__jumpKey + '"]');
    return { top: list.scrollTop, k: window.__jumpKey,
      gap: Math.round(hd.getBoundingClientRect().top - list.getBoundingClientRect().top) };
  }, key);
  ck(before > after.top && Math.abs(after.gap) <= 10,   // 목록 안쪽 여백 6px 실측
    '⑨ 색인이 고른 초성 머리를 목록 맨 위에 붙인다(맨 아래에서 위로)',
    after.k + ' gap=' + after.gap + ' (' + before + '→' + after.top + ')');
  // ⑩ 고르기 → 필터 적용·팝업 닫힘
  const pick = await p.evaluate(() => {
    const it = [...document.querySelectorAll('.bm-item')].find((e) => /GS25/.test(e.textContent));
    if (it) { it.click(); return 'GS25'; }
    const any = document.querySelectorAll('.bm-item')[1]; any.click();
    return any.querySelector('span').textContent;
  });
  await p.waitForTimeout(3000);
  const af = await p.evaluate(() => ({
    closed: document.querySelector('#brandModal').hidden,
    chips: [...document.querySelectorAll('#brandChips .chip.active')].map((e) => e.textContent.trim()),
    rows: document.querySelectorAll('#resultArea tbody tr').length,
  }));
  ck(af.closed, '⑩ 고르면 팝업이 닫힌다');
  ck(af.chips.some((t) => t.indexOf(pick) !== -1), '⑪ 고른 브랜드가 칩에 활성으로 뜬다(onPick 배선)', pick + ' / ' + af.chips.join(','));
  ck(af.rows > 0 && af.rows <= 50, '⑫ 목록이 그 브랜드로 좁혀진다', af.rows + '행');
  // ⑬ Escape 로 닫힌다
  await p.click('.brand-find'); await p.waitForTimeout(2000);
  await p.keyboard.press('Escape'); await p.waitForTimeout(400);
  ck(await p.evaluate(() => document.querySelector('#brandModal').hidden), '⑬ Escape 로 닫힌다');
  const real = errs.filter((e) => !/401/.test(e));
  ck(real.length === 0, '⑭ 스크립트 오류 없음', real.slice(0, 1).join(''));
  console.log(fail ? `\n실패 ${fail}건` : '\n전체 통과 (14건)');
  clearTimeout(WATCHDOG); await b.close(); process.exit(fail ? 1 : 0);
})().catch((e) => { console.log('FAIL', String(e).slice(0, 200)); process.exit(1); });
