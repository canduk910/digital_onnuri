/* 지도 범위 모드 실동작 검사 (2026-09-06 신설, F1)
 *
 * 왜 별도 스크립트인가 — 이 기능은 **서버 연결 모드에서만 존재한다.** updateBoundsBtn 이
 * `b.hidden = (MODE !== "api")` 로 버튼을 감추므로(merchants.html), 백엔드가 없으면
 * '이 지도 범위로 목록 보기' 버튼 자체가 없어 이 경로에 닿을 수 없다. 그런데 로컬 config.js 는
 * apiBase 를 비워 두어 localhost:8080 을 찌르고 실패한다 — 그래서 **로컬에서는 config.js 응답을
 * 가로채** 라이브 API 를 가리키게 한 뒤 잰다. test_frontend_render.js 에 넣지 않은 이유가 이것이다:
 * 그쪽은 외부 의존 없이 도는 것이 계약이고, 배포 게이트(pages.yml)도 그 전제로 돌린다.
 *
 * 무엇을 지키는가 — 2026-09-06 이전에는 지도 범위 모드에서도 "N곳 중 M곳 표시"를 썼는데,
 * 그 N(regionTotal)은 토글한 순간의 값으로 얼어붙고 M 만 지도를 따라 갱신됐다. 좁혀 본 뒤
 * 축소하면 M 이 N 을 넘어 `79곳 중 59,497곳 표시` 같은 자기모순 문장이 이용자에게 나갔다
 * (실측 재현값). 이용자는 그것을 필터 고장으로 읽는다.
 *
 *   NODE_PATH=<playwright> PLAYWRIGHT_CHANNEL=chrome node test_boundsmode_live.js
 *   ONNURI_BASE=https://onnuri.koscomlabor.cloud 를 주면 배달된 것을 잰다
 *     (그때는 config.js 를 가로채지 않는다 — 배포 도메인은 스스로 배포 API 를 고른다)
 *
 * 종료 코드: 0 통과 · 1 실패 · 2 실행되지 않음(playwright 없음)
 */
'use strict';

let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) {
  console.log('playwright 가 없어 지도 범위 모드 검사를 건너뜁니다.');
  console.log('  NODE_PATH=<playwright 설치 경로> 를 주거나 `npm i --no-save playwright` 하세요.');
  process.exit(2);
}
const { spawn } = require('child_process');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const LIVE_API = 'https://api.koscomlabor.cloud/api';
const PORT = 8655;                       // 고정 — 네이버 지도 Client ID 가 도메인+포트 허용 목록이다
const REMOTE = process.env.ONNURI_BASE || '';
const BASE = REMOTE || ('http://localhost:' + PORT);
const WATCHDOG_MS = 180000;

let fails = 0;
const ok = (cond, msg) => { console.log((cond ? '  PASS ' : '  FAIL ') + msg); if (!cond) fails++; };
const num = s => { const m = String(s).match(/([\d,]+)곳/); return m ? parseInt(m[1].replace(/,/g, ''), 10) : NaN; };

(async () => {
  const guard = setTimeout(() => { console.error('시간 초과 — 멈춘 것과 못 잡은 것을 구분하려고 둔 감시 시계다.'); process.exit(1); }, WATCHDOG_MS);

  let srv = null;
  if (!REMOTE) {
    srv = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: ROOT, stdio: 'ignore' });
    await new Promise(r => setTimeout(r, 900));
  }
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || undefined });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));

  if (!REMOTE) {
    // 로컬만 — 배포 도메인은 apiBase 미지정으로도 배포 API 를 고른다(config.js 주석 참조).
    await page.route('**/config.js*', async route => {
      const res = await route.fetch();
      const body = (await res.text())
        .replace('// apiBase: "https://api.koscomlabor.cloud/api",', 'apiBase: "' + LIVE_API + '",');
      await route.fulfill({ response: res, body, headers: Object.assign({}, res.headers(), { 'content-type': 'application/javascript' }) });
    });
  }

  await page.goto(BASE + '/merchants.html', { waitUntil: 'networkidle' });
  await page.waitForFunction(() => { const b = document.getElementById('boundsBtn'); return b && !b.hidden; }, { timeout: 20000 })
    .catch(() => {});

  const apiMode = await page.evaluate(() => { const b = document.getElementById('boundsBtn'); return !!b && !b.hidden; });
  ok(apiMode, 'API 모드로 붙었다(boundsBtn 노출) — 이것이 없으면 이 검사는 아무것도 보지 않는다');
  if (!apiMode) { await browser.close(); if (srv) srv.kill(); clearTimeout(guard); process.exit(1); }

  const read = () => page.evaluate(() => (document.getElementById('countText') || {}).textContent || '');

  const before = await read();
  ok(/^[\d,]+곳 중 [\d,]+곳 표시$/.test(before), '지역 모드에서는 "N곳 중 M곳 표시" 그대로: ' + before);

  // 지도를 화면 안으로 올린다 — 뷰포트 밖 좌표를 누르면 클릭이 허공에 간다(elementFromPoint 가 null).
  const map = await page.$('#map');
  await map.scrollIntoViewIfNeeded();
  await page.waitForTimeout(600);
  const box = await map.boundingBox();
  const cx = Math.round(box.x + box.width / 2);
  const cy = Math.round(Math.min(box.y + box.height / 2, page.viewportSize().height - 60));
  const onMap = await page.evaluate(([x, y]) => { const e = document.elementFromPoint(x, y); return !!(e && e.closest('#map')); }, [cx, cy]);
  ok(onMap, '지도 위 좌표를 잡았다(' + cx + ',' + cy + ')');

  for (let i = 0; i < 4; i++) { await page.mouse.dblclick(cx, cy); await page.waitForTimeout(1400); }
  await page.waitForTimeout(1500);

  await page.click('#boundsBtn');
  await page.waitForTimeout(2500);
  const narrow = await read();
  ok(/^지도 범위에서 [\d,]+곳$/.test(narrow), '지도 범위 모드 문구가 "지도 범위에서 N곳": ' + narrow);

  await page.mouse.move(cx, cy);
  for (let i = 0; i < 6; i++) { await page.mouse.wheel(0, 300); await page.waitForTimeout(1100); }
  await page.waitForTimeout(3000);
  const wide = await read();
  ok(!/중/.test(wide), '축소한 뒤에도 "중"이 없다 — 뒤집힐 문장 자체가 없다: ' + wide);
  ok(num(wide) > num(narrow),
    '축소로 실제로 수가 늘었다(' + num(narrow) + ' → ' + num(wide) + ') — 늘지 않으면 이 검사는 결함을 지나친다');

  await page.click('#boundsBtn');
  await page.waitForTimeout(2500);
  ok(/^[\d,]+곳 중 [\d,]+곳 표시$/.test(await read()), '해제하면 "N곳 중 M곳 표시"로 복귀한다');

  ok(errs.length === 0, 'pageerror 0건' + (errs.length ? ' — ' + errs.join(' / ') : ''));

  await browser.close();
  if (srv) srv.kill();
  clearTimeout(guard);
  console.log(fails ? '\n실패 ' + fails + '건' : '\n전체 통과 (' + BASE + ')');
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error('오류:', e.message); process.exit(1); });
