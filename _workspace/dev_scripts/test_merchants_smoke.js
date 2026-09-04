/* merchants 스모크 — 모듈이 다 실려 돌아가는가 (2026-09-05)
   ────────────────────────────────────────────────────────────────────────
   2026-09-04·05 에 merchants.html 을 여섯 조각(CSS 포함)으로 나눴다. 조각이 늘수록
   **하나가 404 여도 나머지가 멀쩡히 돌아** 조용히 반쪽이 되는 위험이 커진다
   (캐시버스트를 빠뜨리거나 배포에서 파일이 빠지는 경우). 이 스크립트는 그것만 본다.

   기본은 로컬, 배포 뒤에는 기준 주소를 바꿔 **배달된 것**을 잰다:
     ONNURI_BASE=https://onnuri.koscomlabor.cloud node _workspace/dev_scripts/test_merchants_smoke.js
   로컬은 포트 8655 고정 — 네이버 지도 Client ID 가 도메인+포트 허용 목록이라 다른 포트는 401. */
const { chromium } = require('/Users/koscom/Projects/auto_stock/node_modules/playwright');
const BASE = process.env.ONNURI_BASE || 'http://localhost:8655';
const U = BASE + '/merchants.html?region=%EC%84%9C%EC%9A%B8';
let fail=0; const ck=(o,t,d)=>{console.log(`  [${o?'PASS':'FAIL'}] ${t}${d?' — '+d:''}`);if(!o)fail++;};
const WD=setTimeout(()=>{console.log('\nFAIL 시간 초과');process.exit(1);},170000); WD.unref&&WD.unref();
(async()=>{const b=await chromium.launch({channel:'chrome'});const p=await b.newPage({viewport:{width:1440,height:900}});
const errs=[];p.on('pageerror',e=>errs.push(String(e).slice(0,110)));
await p.goto(U,{waitUntil:'domcontentloaded'});
await p.evaluate(()=>{localStorage.clear();sessionStorage.setItem('onnuri_chat_closed','1');});
await p.reload({waitUntil:'domcontentloaded'});await p.waitForTimeout(9000);
const mods=await p.evaluate(()=>['OnnuriPano','OnnuriSplit','OnnuriColResize','OnnuriSaved','OnnuriBrandModal','OnnuriInfoWindow'].filter(k=>!window[k]));
ck(mods.length===0,'① 모듈 6종이 모두 로드된다',mods.join(',')||'전부 로드');
ck(await p.evaluate(()=>document.querySelectorAll('#resultArea tbody tr').length>0),'② 결과 표가 그려진다');
ck(await p.evaluate(()=>document.querySelectorAll('.col-grip').length>0),'③ 컬럼 손잡이가 배선된다');
// 손잡이 전 폭
const g=await p.$('.col-grip'); const gb=await g.boundingBox();
const hits=await p.evaluate(b=>{const o=[];for(let dx=1;dx<b.w;dx+=2){const e=document.elementFromPoint(b.x+dx,b.y+b.h/2);o.push(!!(e&&e.classList.contains('col-grip')));}return o;},{x:gb.x,y:gb.y,w:gb.width,h:gb.height});
ck(hits.every(Boolean)&&hits.length>=4,'④ 손잡이 전 폭이 잡힌다(2026-09-05 수정)',hits.filter(Boolean).length+'/'+hits.length);
// 스플리터
/* 스플리터·세로 핸들은 실제로 있는 선택자로 잰다. 종전 초안은 `||true` 라
   무엇을 넣어도 통과하는 죽은 검사였다 — 통과하는 검사를 세어 봐야 소용없다. */
const sp=await p.evaluate(()=>({h:!!document.querySelector('.split-handle'),
                                v:!!document.querySelector('.vsplit-handle')}));
ck(sp.h&&sp.v,'⑤ 가로·세로 드래그 핸들이 둘 다 있다',JSON.stringify(sp));
// 즐겨찾기 개수 위젯
ck(await p.evaluate(()=>!!document.querySelector('#svCount')),'⑥ 저장 개수 위젯');
ck(await p.evaluate(()=>!!document.querySelector('.brand-find')),'⑦ 브랜드 검색 버튼');
// 지도
ck(await p.evaluate(()=>document.querySelectorAll('.cluster, .pin, .pin-multi, .cmark').length>0),'⑧ 지도에 마커가 그려진다');
const real=errs.filter(e=>!/401/.test(e));
ck(real.length===0,'⑨ 스크립트 오류 없음',real.slice(0,1).join(''));
console.log(fail?`\n실패 ${fail}건`:'\n전체 통과');
clearTimeout(WD);await b.close();process.exit(fail?1:0);})().catch(e=>{console.log('FAIL',String(e).slice(0,160));process.exit(1);});
