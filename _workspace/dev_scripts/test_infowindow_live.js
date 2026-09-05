/* 지도 상세 팝업(InfoWindow) — 실동작 (2026-09-05)
   merchants-infowindow.js 분리에 앞서 **현재 동작을 고정하려고** 먼저 만든 것이다.
   구조를 만지기 전에 스냅샷을 남긴다(app-architecture: 스냅샷 없이 구조를 만지지 않는다).

   실행: python3 -m http.server 8655 후 node _workspace/dev_scripts/test_infowindow_live.js
   **포트 8655 고정** — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라 다른 포트는 401. */
/* playwright 는 **경로를 박지 않는다.** 2026-09-05 에 이 네 스크립트가 개발자 기계의
   절대경로(`/Users/.../auto_stock/node_modules/playwright`)를 require 하고 있어, CI 에
   넣자마자 `MODULE_NOT_FOUND` 로 죽었다 — 내 기계에서만 도는 테스트였던 것이다.
   test_frontend_render.js 와 같은 규약을 쓴다: 보통 방식으로 찾고, 없으면 종료 코드 2. */
let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) {
  console.log('playwright 가 없어 건너뜁니다.');
  console.log('  NODE_PATH=<playwright 설치 경로> 를 주거나 `npm i --no-save playwright` 하세요.');
  process.exit(2);
}
/* 기본은 로컬(포트 8655 고정 — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라
   다른 포트는 401). 배포 뒤 **배달된 것**을 재려면 기준 주소를 바꿔 준다:
     ONNURI_BASE=https://onnuri.koscomlabor.cloud node <이 파일>
   로컬 통과와 라이브 통과는 다른 질문이다 — 캐시버스트를 빠뜨리면 옛 파일이 나간다. */
const BASE = process.env.ONNURI_BASE || 'http://localhost:8655';
const U = BASE + '/merchants.html?region=%EC%84%9C%EC%9A%B8';
let fail = 0;
const WATCHDOG = setTimeout(() => { console.log('\nFAIL 시간 초과(180초)'); process.exit(1); }, 180000);
WATCHDOG.unref && WATCHDOG.unref();
const ck = (o, t, d) => { console.log(`  [${o ? 'PASS' : 'FAIL'}] ${t}${d ? ' — ' + d : ''}`); if (!o) fail++; };
(async () => {
  const b = await chromium.launch({ channel: 'chrome' });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
  await p.goto(U, { waitUntil: 'domcontentloaded' });
  await p.evaluate(() => { localStorage.clear(); sessionStorage.setItem('onnuri_chat_closed', '1'); });
  await p.reload({ waitUntil: 'domcontentloaded' }); await p.waitForTimeout(9000);

  // ① 행을 클릭하면 개별 팝업 — 이름·업종·주소·결제 태그·액션 3종
  await p.click('#resultArea tbody tr td.name'); await p.waitForTimeout(2500);
  const iw = await p.evaluate(() => {
    const e = document.querySelector('.iw'); if (!e) return null;
    return { name: (e.querySelector('.iw-name') || {}).textContent || '',
      cat: (e.querySelector('.iw-cat') || {}).textContent || '',
      addr: (e.querySelector('.iw-addr') || {}).textContent || '',
      pay: (e.querySelector('.iw-pay') || {}).textContent || '',
      acts: [...e.querySelectorAll('.iw-actions a, .iw-actions button')].map((a) => a.textContent.trim()) };
  });
  ck(!!iw, '① 행을 클릭하면 상세 팝업이 열린다');
  ck(iw && iw.name.length > 0 && iw.name.indexOf('☆') === -1 && iw.name.indexOf('★') === -1,
    '② 제목이 순수 상호다(즐겨찾기 기호가 섞이지 않는다)', iw && iw.name);
  ck(iw && iw.addr.length > 5, '③ 주소가 실린다', iw && iw.addr.slice(0, 30));
  ck(iw && /카드|QR|지류/.test(iw.pay), '④ 결제 표시가 실린다', iw && iw.pay);
  // 순서는 고정 계약이 아니다(실측 순서는 지도·거리뷰·길찾기) — **세 가지가 다 있는가**를 본다.
  ck(iw && iw.acts.length === 3 && ['네이버 지도', '길찾기', '거리뷰'].every((t) => iw.acts.some((a) => a.indexOf(t) !== -1)),
    '⑤ 액션 3종(장소·길찾기·거리뷰)', iw && iw.acts.join('/'));
  // ⑥ 링크가 실제 좌표·이름을 담는다
  const href = await p.evaluate(() => {
    const a = document.querySelectorAll('.iw-actions a');
    return { place: a[0] ? a[0].href : '', dir: a[1] ? a[1].href : '' };
  });
  ck(/map\.naver\.com\/p\/search\//.test(href.place), '⑥ 장소보기 링크 형식', href.place.slice(0, 52));
  ck(/map\.naver\.com\/p\/directions\/.*\/transit/.test(href.dir), '⑦ 길찾기 링크 형식', href.dir.slice(0, 56));
  // ⑧ 최근 본에 남는다(팝업이 유일한 창구)
  ck(await p.evaluate(() => (JSON.parse(localStorage.getItem('onnuri_recent') || '[]')).length === 1),
    '⑧ 팝업을 열면 최근 본에 1건 남는다');

  // ⑨~⑬ 그룹 팝업 — 같은 좌표에 여러 곳
  /* 그룹 마커(`.pin-multi`)를 화면에 띄우는 조건은 **둘 다** 필요하다.
     ① 필터 결과가 3,000곳 이하 — 넘으면 서버 클러스터(`.cmark`) 모드다.
     ② 지도 줌 > 15 — MarkerClustering 의 `maxZoom: 15` 를 넘어야 개별 마커가 나온다.
     그래서 구를 고르고(①) 휠로 확대한다(②). 클러스터를 코드로 `.click()` 하면
     SDK 가 실제 이벤트를 기대해 `reading 'x'` 로 죽는다(실측) — 휠을 쓴다. */
  // 겹친 좌표(강남구, 9곳)로 먼저 착지시킨다 — `?spot=` 이 zoom 17 로 morph 해
  // 이후 휠 한 번이면 maxZoom 15 를 넘긴다. 이 착지 없이 구만 고르면 지도가 구 전체를
  // 담는 줌으로 맞춰져 아무리 휠을 굴려도 그 자리로 가지 못한다(실측).
  await p.goto(U + '&spot=37.488658,127.067757,x', { waitUntil: 'domcontentloaded' });
  await p.waitForTimeout(9000);
  await p.selectOption('#selGu', '강남구'); await p.waitForTimeout(4000);
  /* 지도를 **화면 안으로** 올린다. 기본 스크롤 위치에서는 지도가 y≈1100 (뷰포트 900)
     아래에 있어 `elementFromPoint` 가 전부 null 이었다 — 마커를 못 찾은 진짜 이유다. */
  await p.evaluate(() => document.querySelector('#map').scrollIntoView({ block: 'center' }));
  await p.waitForTimeout(600);
  const mbox = await (await p.$('#map')).boundingBox();
  let grp = 'none';
  for (let i = 0; i < 5; i++) {
    await p.mouse.move(mbox.x + mbox.width / 2, mbox.y + mbox.height / 2);
    await p.mouse.wheel(0, -400); await p.waitForTimeout(2000);
    /* **화면 안에 있는** 그룹 마커를 고른다. 첫 번째를 그냥 잡으면 지도 밖에 있을 수
       있어 `elementFromPoint` 가 null 이고 클릭이 아무 데도 닿지 않는다(실측).
       그리고 **실제 마우스로** 누른다 — 마커 청취자는 SDK 가 DOM 이벤트로 붙인 것이라
       `element.click()` 으로는 열리지 않는다(실측). */
    const spot = await p.evaluate((mb) => {
      const inside = [...document.querySelectorAll('.pin-multi')].map((e) => {
        const r = e.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
      }).filter((c) => c.x > mb.x + 20 && c.x < mb.x + mb.width - 20
                    && c.y > mb.y + 20 && c.y < mb.y + mb.height - 20)
        .filter((c) => { const e = document.elementFromPoint(c.x, c.y);
                         return e && e.classList.contains('pin-multi'); });
      return inside[0] || null;
    }, mbox);
    if (spot) {
      await p.mouse.click(spot.x, spot.y);
      await p.waitForTimeout(1800); grp = 'clicked'; break;
    }
  }
  if (grp === 'clicked') {
    await p.waitForTimeout(2000);
    const gi = await p.evaluate(() => {
      const e = document.querySelector('.iwg'); if (!e) return null;
      return { head: (e.querySelector('.iwg-head') || {}).textContent || '',
        scope: !!e.querySelector('.iwg-scope'),
        addr: (e.querySelector('.iwg-addr') || {}).textContent || '',
        items: e.querySelectorAll('.iwg-item[data-i]').length,
        acts: e.querySelectorAll('.iw-actions a, .iw-actions button').length };
    });
    ck(!!gi, '⑨ 그룹 마커를 누르면 위치 목록 팝업');
    ck(gi && gi.items > 1, '⑩ 그 위치의 가맹점이 여러 건 나온다', gi && gi.items + '건');
    ck(gi && gi.scope && /현재 조건 기준/.test(gi.head), '⑪ 곳 수가 현재 조건 기준임을 밝힌다', gi && gi.head.slice(0, 30));
    ck(gi && gi.acts === 3, '⑫ 지도 링크는 상단 한 세트뿐(항목마다 중복 안 함)', gi && String(gi.acts));
    // ⑬ 항목 → 개별, 되돌아가기 → 그룹
    const it1 = await p.$('.iwg-item[data-i="1"]');
    if (it1) { const ib = await it1.boundingBox(); await p.mouse.click(ib.x + ib.width / 2, ib.y + ib.height / 2); }
    await p.waitForTimeout(1500);
    const one = await p.evaluate(() => ({ iw: !!document.querySelector('.iw:not(.iwg)'),
      back: (document.querySelector('.iw-back') || {}).textContent || '',
      recent: (JSON.parse(localStorage.getItem('onnuri_recent') || '[]')).length }));
    ck(one.iw && /이 위치 목록/.test(one.back), '⑬ 항목을 고르면 개별 팝업 + 되돌아가기', one.back);
    ck(one.recent >= 2, '⑭ 그룹에서 개별을 열 때 최근 본에 쌓인다', String(one.recent));
    const bk = await p.$('.iw-back');
    if (bk) { const bb = await bk.boundingBox(); await p.mouse.click(bb.x + bb.width / 2, bb.y + bb.height / 2); }
    await p.waitForTimeout(1500);
    ck(await p.evaluate(() => !!document.querySelector('.iwg')), '⑮ 되돌아가면 그룹 팝업으로');
  } else {
    ck(false, '⑨ 그룹 마커를 찾지 못했다(테스트 전제 실패)');
  }
  const real = errs.filter((e) => !/401/.test(e));
  ck(real.length === 0, '⑯ 스크립트 오류 없음', real.slice(0, 1).join(''));
  console.log(fail ? `\n실패 ${fail}건` : '\n전체 통과');
  clearTimeout(WATCHDOG); await b.close(); process.exit(fail ? 1 : 0);
})().catch((e) => { console.log('FAIL', String(e).slice(0, 200)); process.exit(1); });
