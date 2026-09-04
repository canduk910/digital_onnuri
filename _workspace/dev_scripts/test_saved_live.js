/* 즐겨찾기·최근 본·공유 — 실동작 15경로 (2026-09-05)
   merchants-saved.js 분리를 위해 만든 것이다. 정적 계약(test_frontend_static.js)이
   "계약이 있다"를 보고, 여기서는 "실제로 저장되고 표·지도가 따라 움직이는가"를 본다.

   실행: python3 -m http.server 8655 후 node _workspace/dev_scripts/test_saved_live.js
   **포트 8655 고정** — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라 다른 포트는 401.

   ⑭ 를 **다른 가맹점**으로 재는 이유: 같은 가맹점으로 재면 ⑫ 에서 연 팝업이 그대로
   남아 onOpenSpot 배선을 끊어도 통과한다(변조 실험으로 실제로 걸렸다).
*/
const { chromium } = require('/Users/koscom/Projects/auto_stock/node_modules/playwright');
/* 기본은 로컬(포트 8655 고정 — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라
   다른 포트는 401). 배포 뒤 **배달된 것**을 재려면 기준 주소를 바꿔 준다:
     ONNURI_BASE=https://onnuri.koscomlabor.cloud node <이 파일>
   로컬 통과와 라이브 통과는 다른 질문이다 — 캐시버스트를 빠뜨리면 옛 파일이 나간다. */
const BASE = process.env.ONNURI_BASE || 'http://localhost:8655';
const U = BASE + '/merchants.html?region=%EC%84%9C%EC%9A%B8';
let fail=0;
/* 감시 시계 — 매달리면 **실패로** 끝나야 한다(변조 실험에서 한 변종이 8분 넘게 매달렸다). */
const WATCHDOG=setTimeout(()=>{console.log('\nFAIL 시간 초과(150초)');process.exit(1);},150000);
WATCHDOG.unref&&WATCHDOG.unref(); const ck=(o,t,d)=>{console.log(`  [${o?'PASS':'FAIL'}] ${t}${d?' — '+d:''}`); if(!o)fail++;};
(async()=>{
const b=await chromium.launch({channel:'chrome'});
const p=await b.newPage({viewport:{width:1440,height:900}});
const errs=[]; p.on('pageerror',e=>errs.push(String(e)));
await p.goto(U,{waitUntil:'domcontentloaded'});
await p.evaluate(()=>{localStorage.removeItem('onnuri_favs');localStorage.removeItem('onnuri_recent');
                     sessionStorage.setItem('onnuri_chat_closed','1');});
await p.reload({waitUntil:'domcontentloaded'}); await p.waitForTimeout(8000);

ck(await p.evaluate(()=>!!window.OnnuriSaved && ['attach','isFav','toggleFav','recordRecent','updateCount','openModal','closeModal'].every(k=>typeof OnnuriSaved[k]==='function')),'① 모듈 로드·계약 7종');
ck(await p.evaluate(()=>document.querySelectorAll('.fav-btn').length>0),'② 행마다 ☆ 버튼이 그려진다');
// ③ 즐겨찾기 토글
const n0=await p.evaluate(()=>document.querySelector('#svCount').textContent);
await p.click('.fav-btn'); await p.waitForTimeout(400);
const st=await p.evaluate(()=>({star:document.querySelector('.fav-btn').textContent,
  cnt:document.querySelector('#svCount').textContent,
  ls:JSON.parse(localStorage.getItem('onnuri_favs')||'[]')}));
ck(st.star==='★','③ ☆→★ 로 바뀐다',st.star);
ck(st.cnt==='1'&&n0==='0','④ 저장 개수가 0→1',n0+'→'+st.cnt);
ck(st.ls.length===1&&!!st.ls[0].name,'⑤ localStorage 에 스냅샷이 남는다',(st.ls[0]||{}).name);
ck(st.ls[0].region==='서울','⑥ 스냅샷의 region 이 현재 시도다(게터)',String(st.ls[0].region));
// ⑦ 팝업
await p.click('#svOpen'); await p.waitForTimeout(400);
const mo=await p.evaluate(()=>({open:!document.querySelector('#svModal').hidden,
  secs:[...document.querySelectorAll('.sv-sec')].map(e=>e.textContent),
  items:document.querySelectorAll('.sv-item').length}));
ck(mo.open,'⑦ 저장 팝업이 열린다');
ck(mo.items===1&&/즐겨찾기 1/.test(mo.secs.join('|')),'⑧ 즐겨찾기 1건이 보인다',mo.secs.join('|'));
// ⑨ 공유 링크
await p.click('.sv-item [data-act="share"]'); await p.waitForTimeout(300);
ck(await p.evaluate(()=>/복사됨/.test(document.querySelector('.sv-item [data-act="share"]').textContent)),'⑨ 링크 복사 표시');
// ⑩ 팝업에서 해제 → 표의 별도 함께 꺼진다(onChange)
await p.click('.sv-item [data-act="unfav"]'); await p.waitForTimeout(600);
const af=await p.evaluate(()=>({items:document.querySelectorAll('.sv-item').length,
  cnt:document.querySelector('#svCount').textContent,
  star:document.querySelector('.fav-btn').textContent}));
ck(af.items===0&&af.cnt==='0','⑩ 팝업에서 해제하면 목록·개수가 준다',af.items+'/'+af.cnt);
ck(af.star==='☆','⑪ onChange 로 표의 별도 함께 꺼진다',af.star);
// ⑫ 최근 본 — 행을 클릭하면 기록
await p.evaluate(()=>document.querySelector('#svModal .modal-x, #svModal [data-close]').click()); await p.waitForTimeout(300);
await p.click('#resultArea tbody tr td.name'); await p.waitForTimeout(2500);
const rc=await p.evaluate(()=>JSON.parse(localStorage.getItem('onnuri_recent')||'[]'));
ck(rc.length===1&&!!rc[0].name,'⑫ 가맹점을 열면 최근 본에 남는다',(rc[0]||{}).name);
// ⑬⑭ goSpot — **다른** 가맹점을 즐겨찾기해 두고 그것을 고른다.
// 같은 가맹점으로 재면 ⑫에서 연 팝업이 그대로 남아 배선을 끊어도 통과한다(실측으로 걸렸다).
const other = await p.evaluate(() => {
  var btns = document.querySelectorAll('.fav-btn');
  var i = btns.length > 5 ? 5 : btns.length - 1;
  var b = btns[i]; b.click();
  return b.closest('tr').getAttribute('data-name');
});
await p.waitForTimeout(500);
await p.click('#svOpen'); await p.waitForTimeout(400);
await p.evaluate((nm) => {
  var items = [...document.querySelectorAll('.sv-item')];
  var hit = items.find((d) => d.querySelector('.sv-name').textContent === nm);
  hit.querySelector('.sv-main').click();
}, other);
await p.waitForTimeout(2500);
const g = await p.evaluate(() => ({ closed: document.querySelector('#svModal').hidden,
  iw: [...document.querySelectorAll('.iw-name, .iw h4, .iw b, .iw strong')].map((e) => e.textContent).join('|'),
  all: document.querySelector('.iw') ? document.querySelector('.iw').textContent.slice(0, 120) : '' }));
ck(g.closed, '⑬ 항목을 고르면 팝업이 닫힌다');
ck(g.all.indexOf(other) !== -1, '⑭ 고른 가맹점의 지도 팝업이 열린다(onOpenSpot 배선)', other + ' / ' + g.all.slice(0, 40));
const real=errs.filter(e=>!/401/.test(e));
ck(real.length===0,'⑮ 스크립트 오류 없음',real.slice(0,1).join(''));
console.log(fail?`\n실패 ${fail}건`:`\n전체 통과 (15건)`); clearTimeout(WATCHDOG); await b.close(); process.exit(fail?1:0);
})().catch(e=>{console.log('FAIL',String(e).slice(0,200));process.exit(1);});
