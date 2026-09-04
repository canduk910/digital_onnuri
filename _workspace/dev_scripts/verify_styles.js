#!/usr/bin/env node
/**
 * 렌더된 스타일·기하 전수 대조 (2026-09-04 신설)
 *
 * **무엇을 위한 도구인가.** CSS 를 옮기거나 규칙 순서를 건드리는 변경은 캐스케이드가
 * 통째로 걸린 변경이라 기능 테스트로는 부족하다. 곳 수도 맞고 클릭도 되는데 어떤 요소의
 * 여백 하나가 달라져 있을 수 있고, 그것은 **에러를 내지 않는다.**
 * 그래서 화면의 모든 요소를 찍어 변경 전후를 대조한다.
 *
 * PC 1440 · 모바일 390 두 뷰포트에서, 렌더되는 모든 요소의
 * `boundingRect` + 계산된 속성 43종(색·여백·폰트·flex·overflow·z-index·transform…).
 *
 * ── 쓰는 법 ────────────────────────────────────────────────────────────────
 *   node _workspace/dev_scripts/verify_styles.js guard HEAD
 *       ← **이것을 쓰면 된다.** 지정한 커밋을 임시 워크트리로 꺼내 찍고, 지금 작업본을
 *         찍어 대조한다. 두 스냅샷을 반드시 **같은 도구**로 찍는다.
 *
 *   node _workspace/dev_scripts/verify_styles.js noise
 *       ← 도구 자체의 잡음 측정(같은 코드를 두 번 찍어 비교). 큰 작업 전에 한 번.
 *
 *   node _workspace/dev_scripts/verify_styles.js snap out.json      # 낱개 사용
 *   node _workspace/dev_scripts/verify_styles.js diff a.json b.json
 *
 *   공통 옵션: --page=merchants.html  --query="?region=서울&gu=강남구&dong=개포동"
 *
 * 종료 코드: 0 차이 없음 · 1 차이 있음(또는 실패) · 2 playwright 없어 건너뜀.
 *
 * ── 이 도구를 만들며 걸린 함정 두 가지 (지우지 말 것) ──────────────────────
 * ①**비렌더 요소가 형제 인덱스를 민다.** 요소를 DOM 경로로 식별하는데, `<script>` 한 줄을
 *   더하는 것만으로 뒤 형제의 인덱스가 전부 밀려 **차이 51건**이 떴다. 전부 거짓 경보였다.
 *   → script·link·style·meta·title 을 아예 빼고, **인덱스도 그것들을 뺀 뒤** 센다.
 * ②**기준선은 같은 도구로 찍어야 한다.** 도구를 고치면 옛 스냅샷과는 비교할 수 없다.
 *   → `guard` 가 매번 워크트리에서 기준선을 새로 찍는다. 옛 JSON 을 재활용하지 않는다.
 *
 * 포트 8655 는 고정이다 — 네이버 지도 허용 도메인이라 다른 포트에서는 지도가 401 이고,
 * 지도가 죽으면 안내 문구·레이아웃이 달라져 대조 자체가 무의미해진다.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const PORT = 8655;

let chromium;
try { ({ chromium } = require('playwright')); }
catch (e) {
  console.log('playwright 가 없어 건너뜁니다.');
  console.log('  NODE_PATH=<playwright 설치 경로> 를 주거나 `npm i --no-save playwright` 하세요.');
  process.exit(2);
}

const ARGS = process.argv.slice(2);
const CMD = ARGS[0] || 'guard';
const opt = (k, d) => { const a = ARGS.find(x => x.startsWith('--' + k + '=')); return a ? a.slice(k.length + 3) : d; };
const PAGE = opt('page', 'merchants.html');
// 기본 질의는 **동일좌표 그룹이 실제로 있는 지역**이다(개포동 144곳) — 지도·팝업까지 그려진다.
const QUERY = opt('query', '?region=' + encodeURIComponent('서울')
  + '&gu=' + encodeURIComponent('강남구') + '&dong=' + encodeURIComponent('개포동'));

const PROPS = ['display','position','width','height','margin','padding','border','borderRadius',
  'color','backgroundColor','fontSize','fontWeight','fontFamily','lineHeight','letterSpacing',
  'textAlign','flexDirection','justifyContent','alignItems','gap','gridTemplateColumns',
  'overflow','overflowX','overflowY','zIndex','opacity','visibility','boxShadow','minHeight',
  'minWidth','maxHeight','maxWidth','whiteSpace','textOverflow','cursor','transform',
  'top','left','right','bottom'];

/* 알려진 잡음. `.content-inner` 의 좌우 여백·너비는 폭 슬라이더가 `--page-w` 를 계산하는
   시점에 따라 회차마다 갈린다 — 같은 코드로 두 번 찍어도 다르다(실측). 코드 변경과 무관.
   여기 무언가를 더할 때는 **먼저 `noise` 로 재현**하고, 왜 잡음인지 한 줄 적어라. */
function isNoise(cls, prop) {
  return cls.indexOf('content-inner') >= 0 && (prop === 'margin' || prop === 'width');
}

function serve(dir) {
  const p = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'],
    { cwd: dir, stdio: 'ignore' });
  const kill = () => { try { p.kill(); } catch (e) {} };
  process.on('exit', kill);
  process.on('SIGINT', () => { kill(); process.exit(130); });
  return { proc: p, stop: () => new Promise((r) => { kill(); setTimeout(r, 700); }) };
}

async function snapshot(dir, label) {
  const srv = serve(dir);
  await new Promise((r) => setTimeout(r, 1000));
  const b = await chromium.launch(
    process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {});
  const out = {};
  try {
    for (const [name, vp] of [['pc', { width: 1440, height: 900 }], ['mo', { width: 390, height: 844 }]]) {
      const p = await b.newPage({ viewport: vp });
      const errs = [];
      p.on('pageerror', (e) => errs.push(String(e).slice(0, 130)));
      await p.goto(`http://localhost:${PORT}/${PAGE}${QUERY}`, { waitUntil: 'domcontentloaded' });
      // 목록이 그려질 때까지 — 데이터가 오기 전에 찍으면 두 스냅샷이 다른 상태를 본다.
      await p.waitForFunction(
        () => document.querySelectorAll('tr.row-link, .pf-card, .card').length > 0,
        { timeout: 25000 }).catch(() => {});
      await p.waitForTimeout(6000);
      out[name] = await p.evaluate((props) => {
        const SKIP = /^(SCRIPT|LINK|STYLE|META|TITLE|HEAD)$/;
        const pathOf = (el) => {
          const parts = [];
          for (let n = el; n && n.nodeType === 1 && parts.length < 6; n = n.parentElement) {
            const par = n.parentElement;
            let i = 0;
            if (par) for (const c of par.children) { if (c === n) break; if (!SKIP.test(c.tagName)) i++; }
            // **id 가 있으면 인덱스를 붙이지 않는다.** 인덱스는 형제 위치라, 요소를 하나
            // 끼워 넣기만 해도 뒤 형제의 키가 전부 바뀌어 '새 요소' 로 쏟아진다
            // (2026-09-04 두 번째로 겪었다 — 처음은 <script>, 이번은 숨은 안내줄).
            // id 는 그 자체가 정체성이므로 위치와 무관하게 같은 것으로 봐야 한다.
            parts.unshift(n.id ? n.tagName.toLowerCase() + '#' + n.id
                               : n.tagName.toLowerCase() + ':' + i);
          }
          return parts.join('>');
        };
        const rows = [];
        document.querySelectorAll('*').forEach((el) => {
          if (SKIP.test(el.tagName)) return;
          // 지도 내부는 SDK 가 매 프레임 바꾸고, 챗 패널은 열림 상태가 세션마다 갈린다.
          if (el.closest('#map') || el.closest('.cw-panel')) return;
          const cs = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          const s = {};
          props.forEach((k) => { s[k] = cs[k]; });
          rows.push({
            k: pathOf(el),
            cls: el.className && el.className.toString ? String(el.className) : '',
            box: [Math.round(r.x * 10) / 10, Math.round(r.y * 10) / 10,
                  Math.round(r.width * 10) / 10, Math.round(r.height * 10) / 10],
            s,
          });
        });
        return {
          count: rows.length, rows,
          docW: document.documentElement.scrollWidth, docH: document.documentElement.scrollHeight,
          listRows: document.querySelectorAll('tr.row-link, .pf-card, .card').length,
          countText: (document.getElementById('countText') || {}).textContent || '',
        };
      }, PROPS);
      out[name].errors = errs.filter((e) => !/401/.test(e));
      await p.close();
    }
  } finally {
    await b.close();
    await srv.stop();
  }
  console.log(`  ${label}: pc ${out.pc.count} · mo ${out.mo.count} 요소 · 항목 ${out.pc.listRows}`
    + ` · ${out.pc.countText.trim().slice(0, 24)} · 오류 ${out.pc.errors.length + out.mo.errors.length}`);
  return out;
}

function compare(A, B, aName, bName) {
  let diffs = 0, checked = 0, noise = 0;
  for (const vp of ['pc', 'mo']) {
    const a = A[vp], b = B[vp];
    if (a.count !== b.count) { console.log(`  [${vp}] 요소 수 ${a.count} → ${b.count}`); }
    if (a.docW !== b.docW || a.docH !== b.docH) {
      console.log(`  [${vp}] 문서 크기 ${a.docW}x${a.docH} → ${b.docW}x${b.docH}`); diffs++;
    }
    if (a.listRows !== b.listRows) { console.log(`  [${vp}] 항목 수 ${a.listRows} → ${b.listRows}`); diffs++; }
    const map = new Map(a.rows.map((r) => [r.k, r]));
    for (const rb of b.rows) {
      const ra = map.get(rb.k);
      if (!ra) { console.log(`  [${vp}] 새 요소 ${rb.k} ${rb.cls.slice(0, 30)}`); diffs++; continue; }
      checked++;
      for (let i = 0; i < 4; i++) {
        if (Math.abs(ra.box[i] - rb.box[i]) > 0.6) {
          console.log(`  [${vp}] 기하 ${rb.k} ${rb.cls.slice(0, 28)} : [${ra.box}] → [${rb.box}]`);
          diffs++; break;
        }
      }
      for (const k of Object.keys(rb.s)) {
        if (ra.s[k] === rb.s[k]) continue;
        if (isNoise(rb.cls, k)) { noise++; continue; }
        console.log(`  [${vp}] 스타일 ${rb.k} ${rb.cls.slice(0, 26)} .${k}: ${ra.s[k]} → ${rb.s[k]}`);
        diffs++;
      }
    }
  }
  console.log();
  if (diffs === 0) console.log(`동일 — ${checked}개 요소 대조, 차이 0 (알려진 잡음 ${noise}건 제외)`);
  else console.log(`차이 ${diffs}건 (${aName} → ${bName}, 알려진 잡음 ${noise}건 제외)`);
  return diffs;
}

(async () => {
  if (CMD === 'snap') {
    const out = ARGS[1];
    if (!out) { console.log('사용: verify_styles.js snap <out.json>'); process.exit(1); }
    console.log(`스냅샷 — ${PAGE}`);
    fs.writeFileSync(out, JSON.stringify(await snapshot(ROOT, '작업본')));
    console.log('저장', out);
    return;
  }

  if (CMD === 'diff') {
    const [, a, b] = ARGS;
    if (!a || !b) { console.log('사용: verify_styles.js diff <a.json> <b.json>'); process.exit(1); }
    const n = compare(JSON.parse(fs.readFileSync(a, 'utf8')), JSON.parse(fs.readFileSync(b, 'utf8')), a, b);
    process.exit(n === 0 ? 0 : 1);
  }

  if (CMD === 'noise') {
    // 도구 자체의 잡음을 잰다. **큰 작업 전에 한 번 돌려라** — 잡음을 모르면 차이가
    // 나왔을 때 내 변경 탓인지 도구 탓인지 가릴 수 없다.
    console.log(`잡음 측정 — 같은 코드를 두 번 찍어 비교 (${PAGE})`);
    const a = await snapshot(ROOT, '1회차');
    const b = await snapshot(ROOT, '2회차');
    const n = compare(a, b, '1회차', '2회차');
    if (n > 0) console.log('\n※ 잡음이 있다. 이 항목들은 코드 변경과 무관하게 회차마다 갈린다 —\n'
      + '   isNoise() 에 등록하거나, 대조 결과를 읽을 때 이만큼을 감안하라.');
    process.exit(n === 0 ? 0 : 1);
  }

  if (CMD === 'guard') {
    const ref = ARGS[1] && !ARGS[1].startsWith('--') ? ARGS[1] : 'HEAD';
    const git = (...a) => execFileSync('git', a, { cwd: ROOT, encoding: 'utf8' }).trim();
    let sha;
    try { sha = git('rev-parse', '--short', ref); }
    catch (e) { console.log(`'${ref}' 를 찾을 수 없습니다.`); process.exit(1); }

    const wt = fs.mkdtempSync(path.join(os.tmpdir(), 'onnuri-styles-'));
    console.log(`기준선 ${ref} (${sha}) 과 작업본을 대조합니다 — ${PAGE}`);
    console.log('  ※ 두 스냅샷을 같은 도구로 찍습니다. 옛 JSON 을 재활용하지 않는 것이 이 명령의 요점입니다.\n');
    let base, now, code = 1;
    try {
      execFileSync('git', ['worktree', 'add', '--detach', '-q', wt, sha], { cwd: ROOT });
      base = await snapshot(wt, `기준선 ${sha}`);
      now = await snapshot(ROOT, '작업본  ');
      console.log();
      code = compare(base, now, sha, '작업본') === 0 ? 0 : 1;
    } finally {
      try { execFileSync('git', ['worktree', 'remove', '--force', wt], { cwd: ROOT, stdio: 'ignore' }); }
      catch (e) { try { fs.rmSync(wt, { recursive: true, force: true }); } catch (e2) {} }
      try { execFileSync('git', ['worktree', 'prune'], { cwd: ROOT, stdio: 'ignore' }); } catch (e) {}
    }
    process.exit(code);
  }

  console.log('명령: guard [ref] | noise | snap <out.json> | diff <a.json> <b.json>');
  console.log('옵션: --page=merchants.html --query="?region=..."');
  process.exit(1);
})().catch((e) => { console.log('실패 —', String(e).slice(0, 300)); process.exit(1); });
