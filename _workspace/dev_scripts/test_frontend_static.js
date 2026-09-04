#!/usr/bin/env node
/**
 * 프론트엔드 정적 계약 테스트 (2026-09-04 신설)
 *
 * **브라우저를 띄우지 않는다.** HTML·JSON·JS 를 문자열/JSON 으로 읽어 검사한다.
 * 그래서 준비물이 없고 CI 에서 그대로 돈다 — 이것이 이 층의 존재 이유다.
 *
 * 케이스는 **지어낸 것이 아니다.** 전부 CLAUDE.md 변경 이력에 실제로 적힌 결함에서 나왔고,
 * 각 섹션 머리말에 어느 날 무슨 일이 있었는지 적어 두었다. 공통점은 하나다 —
 * **전부 조용했다.** 에러를 내지 않았고, 화면은 멀쩡해 보였고, 며칠 뒤 사람이 우연히 찾았다.
 *
 * 실행: node _workspace/dev_scripts/test_frontend_static.js
 *
 * 브라우저가 필요한 검사(곳 수·착지·렌더)는 test_frontend_render.js 가 맡는다.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const rd = (p) => fs.readFileSync(path.join(ROOT, p), 'utf-8');
const rj = (p) => JSON.parse(rd(p));

let pass = 0, fail = 0;
function check(cond, label, detail) {
  if (cond) { pass++; console.log(`  [PASS] ${label}`); }
  else { fail++; console.log(`  [FAIL] ${label}${detail !== undefined ? ' — ' + detail : ''}`); }
}

// 저장소의 모든 페이지. **여덟 개다** — admin-report.html 을 빼고 세다가 그 페이지의
// 캐시버스트 드리프트가 감시 밖에 남는 일을 막는다(사이드바가 없다고 페이지가 아닌 것은 아니다).
const PAGES = fs.readdirSync(ROOT).filter((f) => f.endsWith('.html')).sort();
const HTML = {};
PAGES.forEach((p) => { HTML[p] = rd(p); });

console.log(`프론트엔드 정적 계약 — 페이지 ${PAGES.length}개\n`);

// ─────────────────────────────────────────────────────────────────────────────
console.log('(a) 캐시버스트 — 한 자산은 모든 페이지에서 같은 버전이어야 한다');
// 2026-08-25 'payment.html 만 chat-widget.js?v=9 로 뒤처져 있던 것'
// 2026-08-26 'payment.html 만 chat-widget.css?v=9 로 뒤처져 있던 것'
// 2026-08-31 'online-source.js 가 4단계에서 바뀌었는데 캐시버스트가 v=1 로 남아 있던 것'
// 세 번 같은 방식으로 났다. 한 페이지만 옛 버전을 물면 그 페이지 방문자는 옛 코드를 받는데
// **화면은 멀쩡하다** — 새 기능이 조용히 없을 뿐이다.
{
  const ASSETS = ['shell.css', 'shell.js', 'chat-widget.css', 'chat-widget.js',
                  'config.js', 'online-source.js', 'online-probe.js',
                  'merchants.css', 'merchants-pano.js', 'merchants-split.js', 'merchants-saved.js', 'merchants-brandmodal.js', 'merchants-infowindow.js',
                  'merchants-colresize.js', 'favicon.svg'];
  ASSETS.forEach((a) => {
    const seen = {};   // 버전 → 그 버전을 쓰는 페이지들
    PAGES.forEach((p) => {
      // **실제 참조만 본다.** 주석 안의 파일명 언급("공통 셸은 shell.css — ADR-9")까지 세면
      // 없는 드리프트를 만들어 낸다. 그리고 index.html 은 4MB 번들이라 속성이 이스케이프된
      // 형태(href=\\"shell.css?v=8\\")로 들어 있다 — 뒤따르는 문자에 역슬래시를 허용한다.
      const re = new RegExp('(?:href|src)=\\\\?["\']' + a.replace('.', '\\.') + '(\\?v=([0-9.]+))?\\\\?["\']');
      const m = HTML[p].match(re);
      if (!m) return;
      const v = m[2] || '(버전 없음)';
      (seen[v] = seen[v] || []).push(p);
    });
    const versions = Object.keys(seen);
    if (versions.length === 0) return;   // 아무 페이지도 안 쓰는 자산
    check(versions.length === 1, `${a} 버전이 갈리지 않는다`,
      versions.map((v) => `${v}: ${seen[v].join(',')}`).join(' | '));
  });
}

console.log();
console.log('(b) dataVersion — 데이터가 바뀌면 함께 올라가야 한다');
// 2026-08-21 '데이터만 고치고 dataVersion 을 빼먹어 기존 방문자가 ?v= 로 캐시된 옛
// 카탈로그를 계속 받는 상태였다'(수습에 별도 커밋 64261b8 이 필요했다).
// 파일명이 그대로라 쿼리만이 유일한 무력화 수단이다.
{
  const cfg = rd('config.js');
  const m = cfg.match(/dataVersion:\s*"([0-9.\-]+)"/);
  check(!!m, 'config.js 에 dataVersion 이 있다');
  if (m) {
    const dv = m[1].slice(0, 10);   // "2026-09-04.2" 같은 접미를 잘라 날짜만 본다
    const stamps = [];
    fs.readdirSync(path.join(ROOT, 'data')).filter((f) => f.endsWith('.json')).forEach((f) => {
      const d = rj(path.join('data', f));
      const meta = (d && d.meta) || {};
      const s = meta.collected_on || meta.surveyed_on;
      if (s) stamps.push({ f, s });
      // 항목별 수집일도 본다 — 목록에 뒤늦게 붙은 항목이 meta 보다 새로울 수 있다
      // (2026-09-03 온누리 권율로가 정확히 그랬다).
      (d && d.items || []).forEach((it) => {
        const t = it.collected_on || it.surveyed_on;
        if (t) stamps.push({ f: f + '(항목)', s: t });
      });
    });
    const newest = stamps.reduce((a, b) => (a && a.s >= b.s ? a : b), null);
    check(newest && dv >= newest.s, 'dataVersion 이 데이터 최신 수집일보다 뒤처지지 않는다',
      newest ? `dataVersion=${dv} · 최신=${newest.s}(${newest.f})` : '수집일 없음');
  }
}

console.log();
console.log('(c) 페이지 간 링크 — 가리키는 곳이 실제로 있어야 한다');
// 2026-09-03 '사이드바 자기 앵커 #kindTabs→#pageTabs(탭 2 안으로 들어가 죽은 링크)'.
// 자기 앵커는 저장소 전체에 두 개뿐이라 그것만 봐서는 사실상 아무것도 검사하지 못한다.
// 실제 위험은 **페이지 간 해시 링크**다(index.html#online 등 16건) — 대상 페이지가 그
// id 를 갖거나, 해시 라우터가 그 값을 처리해야 한다.
{
  const anchorRe = /href="([a-z-]*\.html)?#([A-Za-z][\w-]*)"/g;
  PAGES.forEach((p) => {
    let m, bad = [];
    while ((m = anchorRe.exec(HTML[p])) !== null) {
      const target = m[1] || p, id = m[2];
      if (!HTML[target]) { bad.push(`${m[0]} → 페이지 없음`); continue; }
      const hasId = new RegExp('id=\\\\?["\']' + id + '\\\\?["\']').test(HTML[target])
                 || new RegExp('id="' + id + '"').test(HTML[target]);
      // index.html 은 4MB 번들이라 id 가 이스케이프된 형태로만 있고, 해시는 탭 라우터가
      // 처리한다(applyHash). 라우터가 그 값을 명시적으로 다루면 살아 있는 링크다.
      const routed = new RegExp('h\\s*===?\\s*\\\\?["\']' + id + '\\\\?["\']').test(HTML[target]);
      if (!hasId && !routed) bad.push(m[0]);
    }
    check(bad.length === 0, `${p} 의 해시 링크가 전부 살아 있다`, bad.join(', '));
  });
}

console.log();
console.log('(d) 모바일 브레이크포인트 — 한 값으로 통일');
// 2026-08-13 '브레이크포인트 900↔959 불일치(셸=모바일/지도=데스크톱 사각지대) 해소'.
// 두 값이 섞이면 그 사이 폭에서 셸은 모바일인데 지도는 데스크톱인 상태가 생긴다.
{
  const files = PAGES.concat(['shell.css', 'chat-widget.css']);
  const odd = [];
  files.forEach((f) => {
    const src = HTML[f] || rd(f);
    const ms = src.match(/(max|min)-width:\s*(9[0-9][0-9])px/g) || [];
    ms.forEach((x) => {
      const n = +x.match(/(9[0-9][0-9])/)[1];
      if (n !== 959 && n !== 960) odd.push(`${f}: ${x}`);
    });
  });
  check(odd.length === 0, '900번대 브레이크포인트는 959/960 뿐이다', odd.join(', '));
}

console.log();
console.log('(e) 문서 기본 — 탭 제목과 파비콘');
// 2026-08-18 'index 파비콘·탭 제목 소실 — 번들 로더의 replaceWith 가 외곽 head 를 통째로
// 날려, 로드 완료 후 title="" · link[rel=icon] 0개였다'.
// index 는 브랜드명만 쓰고 하위 페이지는 "페이지명 — 브랜드" 다(2026-08-11 의도된 설계).
{
  const BRAND = '코스콤 디지털온누리 가이드';
  PAGES.forEach((p) => {
    const t = (HTML[p].match(/<title>([^<]*)<\/title>/) || [])[1] || '';
    const okTitle = p === 'index.html' ? t === BRAND : t.endsWith(' — ' + BRAND) && t.length > BRAND.length + 3;
    check(okTitle, `${p} 탭 제목`, JSON.stringify(t));
    check(/rel="icon"/.test(HTML[p]), `${p} 파비콘 링크`);
  });
}

console.log();
console.log('(f) online-probe — 링크가 검색을 실행하는지 라벨이 구분한다');
// 2026-09-04 '라벨이 몰에서 보기 하나뿐이라, 전용관 주소가 고정인 3곳(현대홈쇼핑·공영쇼핑·
// 롯데ON)에서 누르면 검색어가 사라지는데 같은 말을 했다'.
// 이 파일은 window 셤만 있으면 Node 에서 그대로 로드된다 — 순수 함수는 여기서 본다.
{
  global.window = {};
  global.document = { addEventListener() {}, readyState: 'complete', getElementById: () => null };
  global.location = { hostname: 'localhost' };
  require(path.join(ROOT, 'online-probe.js'));
  const P = global.window.OnnuriOnlineProbe;
  check(!!P, 'online-probe.js 가 Node 에서 로드된다');

  const src = rd('online-probe.js');
  check(/function carriesQuery\(u, q\)/.test(src) && /function linkTag\(u, q\)/.test(src),
    '판단 헬퍼가 모듈 스코프에 있다(네 경로가 같은 규칙을 쓴다)');
  // 라벨이 하나로 되돌아가면(창구가 무너지면) 여기서 깨진다.
  check(/검색 결과 보기/.test(src) && /몰 화면 열기/.test(src) && !/몰에서 보기 ↗/.test(src),
    '링크 라벨이 두 갈래로 갈려 있다');
  // 상대 표현은 매일 틀린다 — 배치는 당일 00:30 에 돈다(2026-09-04).
  // 주석의 역사적 언급('그때 이름은 "전일 색인"이었다')까지 막으면 기록을 못 남긴다.
  // 화면에 나가는 문자열만 본다 — eyebrow 와 각주.
  check(!/>전일 색인</.test(src) && !/어제 올라와/.test(src) && !/전일 색인은 /.test(src),
    "색인 층의 화면 문구에 '전일·어제' 상대 표기가 없다");

  // 미지 사유 키를 원시 문자열로 노출하지 않는다(2026-09-02).
  // renderResult 는 querySelector 가 null 을 줘도 정상 동작한다 — 가짜 mount 로 돌린다.
  if (P && P.renderResult) {
    const mount = { innerHTML: '', querySelector: () => null };
    P.renderResult(mount, {
      query: '김치', checkedAt: '2026-09-04 09:00', notice: '테스트', notProbedCount: 1,
      items: [{ platformId: 'x', name: '어떤몰', status: 'not-probed', reason: 'zzq-unknown-reason',
                searchUrl: 'https://example.com/', sampleTitles: [] }],
      index: null,
    }, null);
    check(mount.innerHTML.indexOf('zzq-unknown-reason') === -1,
      '모르는 제외 사유 키가 화면에 원시 문자열로 나가지 않는다');
    check(mount.innerHTML.indexOf('몰 화면 열기') >= 0,
      '검색어를 담지 못하는 링크는 그렇게 라벨된다');
  }
}

console.log();
console.log('(g) merchants — 외부화한 자산이 실제로 연결돼 있다');
// 2026-09-04: <style> 412줄을 merchants.css 로 외부화했다. 링크가 빠지면 페이지가
// 스타일 없이 뜨는데 **JS 는 멀쩡히 돌아** 테스트 대부분이 통과한다 — 조용한 실패다.
{
  const m = HTML['merchants.html'];
  check(/<link rel="stylesheet" href="merchants\.css\?v=\d+">/.test(m), 'merchants.css 를 연결한다');
  check(!/<style>/.test(m), '인라인 <style> 이 남아 있지 않다(사본 방지)');
  // CSS 는 규칙 순서가 곧 우선순위다. shell.css 뒤에 와야 셸 토큰을 덮을 수 있다.
  const iShell = m.indexOf('shell.css'), iOwn = m.indexOf('merchants.css');
  check(iShell >= 0 && iOwn > iShell, 'merchants.css 가 shell.css 뒤에 온다(캐스케이드 순서)');
  let css = '';
  try { css = rd('merchants.css'); } catch (e) {}
  check(css.length > 5000, 'merchants.css 에 규칙이 들어 있다', `${css.length}자`);
  // 거리뷰 시트가 SDK 인라인 스타일을 이기는 규칙 — 순서·존재가 깨지면 패널이 무너진다.
  check(/!important/.test(css), 'SDK 인라인 스타일을 이기는 !important 규칙이 살아 있다');

  // 2026-09-04: 거리뷰 328줄을 merchants-pano.js 로 외부화했다.
  check(/<script src="merchants-pano\.js\?v=\d+"><\/script>/.test(m), 'merchants-pano.js 를 연결한다');
  // 인라인 스크립트가 window.OnnuriPano 를 읽으므로 그보다 **먼저** 로드돼야 한다.
  const iPano = m.indexOf('merchants-pano.js'), iInline = m.lastIndexOf('<script>');
  check(iPano >= 0 && iPano < iInline, 'merchants-pano.js 가 인라인 스크립트보다 먼저 온다');
  check(!/function openPano\(/.test(m), '거리뷰 함수가 merchants.html 에 남아 있지 않다(사본 방지)');
  let pano = '';
  try { pano = rd('merchants-pano.js'); } catch (e) {}
  check(/window\.OnnuriPano = \{/.test(pano), 'OnnuriPano 계약을 노출한다');
  // 지도는 **게터**로 받아야 한다 — initMap 이 나중에 채우므로 값으로 붙잡으면 영영 null 이고
  // 에러 없이 아무 일도 안 일어난다(가장 조용한 실패 모드).
  // 주석에는 설명을 위해 `mapObj` 라는 낱말이 나온다 — **코드에서만** 찾는다.
  const panoCode = pano.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  check(/getMap\(\)/.test(panoCode) && !/\bmapObj\b/.test(panoCode),
    '지도를 값이 아니라 게터로 받는다(코드 기준)');
  check(/PANO\.attach\(/.test(m), 'merchants.html 이 attach 로 주입한다');
  // 2026-09-04: 거리뷰가 #mapNote 를 공유하며 저장·복원하던 것을 전용 줄로 끊었다.
  // 코드에 mapNote 가 다시 나타나면 그 결합이 되살아난 것이다(주석의 설명 문구는 제외).
  check(!/\bmapNote\b/.test(panoCode), '거리뷰가 지도 안내줄을 알지 못한다(코드 기준)');
  check(!/panoNoteSaved/.test(panoCode), '저장·복원 변수가 없다');
  check(/setStreetNote/.test(pano) && /setStreetNote: setStreetNote/.test(m),
    '모드 표시를 setStreetNote 로 주입받는다');
  check(/id="streetNote"/.test(m) && /function setStreetNote\(on\)/.test(m),
    '전용 줄과 그 창구가 merchants.html 에 있다(문장·자리를 페이지가 소유)');
  // 숨은 기본값 — 평소 화면이 1px 도 움직이지 않는 근거다.
  check(/id="streetNote" hidden/.test(m), '전용 줄의 기본값은 hidden 이다');
  check(/\.street-note\{/.test(css) && /\.street-note b\{/.test(css),
    'street-note 와 그 <b> 강조 규칙이 있다');
  // 드래그 핸들은 옆 열(지도 + 안내줄)과 키가 맞아야 한다. height 를 다시 박으면
  // 안내줄만큼 짧아져 아래쪽에서 안 잡힌다(2026-09-04 실측 45~95px).
  const sh = (css.match(/\.split-handle\{[^}]*\}/) || [''])[0];
  check(/align-self:\s*stretch/.test(sh), '핸들이 align-self:stretch 로 열에 맞춘다');
  check(!/height:\s*var\(--panel-h/.test(sh), '핸들 높이를 지도 높이로 못 박지 않는다');
  /* #mapNote 의 writer 는 **지도 자신에 관한 셋**만 남아야 한다 —
     renderMap · viewportRender · navermap_authFailure.
     거리뷰(전용 줄로 분리)와 위치 권한 오류(locNote 로 이관)가 여기 다시 나타나면
     '누가 이 줄의 주인인가'가 도로 흐려진다. 코드에서만 세고 주석은 뺀다. */
  const mCode = m.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')
                 .replace(/^\s*\/\/.*$/gm, '');
  // 마크업 정의(`id="mapNote"`) 한 줄은 빼고 **참조**만 센다.
  const mapNoteRefs = (mCode.match(/\bmapNote\b/g) || []).length
                    - (mCode.match(/id="mapNote"/g) || []).length;
  check(mapNoteRefs === 3, 'mapNote 를 참조하는 곳이 셋뿐이다(renderMap·viewportRender·인증실패)',
    `${mapNoteRefs}곳`);
  check(!/note\.textContent = "위치 정보를 가져오지 못해/.test(mCode),
    '위치 권한 오류는 mapNote 가 아니라 locNote 로 간다');
  check(/setLocNote\("위치 정보를 가져오지 못해/.test(mCode),
    '정렬 실패 안내가 locNote 창구를 쓴다');
  // SDK URL 의 submodules=panorama 가 빠지면 이 파일은 로드되나 파노라마가 안 열린다.
  check(/submodules=panorama/.test(m), 'SDK URL 에 panorama 서브모듈이 있다');

  // 2026-09-05: 스플리터 99줄을 merchants-split.js 로 외부화했다(3단계 첫 걸음).
  check(/<script src="merchants-split\.js\?v=\d+"><\/script>/.test(m), 'merchants-split.js 를 연결한다');
  const iSplit = m.indexOf('merchants-split.js');
  check(iSplit >= 0 && iSplit < iInline, 'merchants-split.js 가 인라인 스크립트보다 먼저 온다');
  check(!/function initSplit\(/.test(m) && !/function notifyMapResize\(/.test(m),
    '스플리터 함수가 merchants.html 에 남아 있지 않다(사본 방지)');
  let split = '';
  try { split = rd('merchants-split.js'); } catch (e) {}
  check(/window\.OnnuriSplit = \{/.test(split), 'OnnuriSplit 계약을 노출한다');
  const splitCode = split.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  // 지도는 게터로 — initMap 이 나중에 채우므로 값으로 붙잡으면 영영 null 이다(pano 와 같은 함정).
  check(/getMap\(\)/.test(splitCode) && !/\bmapObj\b/.test(splitCode),
    '스플리터가 지도를 게터로 받는다(코드 기준)');
  // 허브를 건드리지 않는 것이 이 조각을 첫 걸음으로 고른 이유다. 들어오면 전제가 무너진다.
  check(!/\b(state|SNAP|refresh)\b/.test(splitCode),
    '스플리터가 state·SNAP·refresh 허브를 건드리지 않는다');
  // pagewidthchange 청취자는 **init 안**에서 등록해야 한다 — 모듈 평가 시점에 걸면
  // attach 보다 먼저 발화해 isMapReady 가 null 인 채 불린다.
  const initBody = (splitCode.match(/init: function \(\) \{[\s\S]*?\n    \}/) || [''])[0];
  check(/addEventListener\("pagewidthchange"/.test(initBody),
    'pagewidthchange 청취자를 init 안에서 등록한다(attach 이후 보장)');

  // 2026-09-05: 컬럼 폭 리사이저 55줄 외부화(3단계 두 번째 걸음).
  check(/<script src="merchants-colresize\.js\?v=\d+"><\/script>/.test(m), 'merchants-colresize.js 를 연결한다');
  check(!/function wireColResize\(/.test(m) && !/var COL_MIN/.test(m),
    '리사이저 함수·상수가 merchants.html 에 남아 있지 않다(사본 방지)');
  let colr = '';
  try { colr = rd('merchants-colresize.js'); } catch (e) {}
  check(/window\.OnnuriColResize = \{/.test(colr), 'OnnuriColResize 계약을 노출한다');
  const colrCode = colr.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  check(!/\b(state|SNAP|refresh)\b/.test(colrCode) && !/\brender\(/.test(colrCode),
    '리사이저가 허브와 render 를 직접 부르지 않는다(onReset 콜백을 쓴다)');
  // 폭은 드래그 중에 바뀐다. render 가 값으로 붙잡으면 옛 배열을 그린다 —
  // **에러 없이 폭이 되돌아간 것처럼 보이는** 조용한 실패 모드다.
  // **코드에서** 게터를 읽어야 한다. 주석에도 `COLR.widths()` 가 나오므로 원문 그대로
  // 찾으면 render 가 값을 붙잡도록 바뀌어도 통과한다(실측으로 걸렸다).
  check(/var COL_W = COLR \? COLR\.widths\(\) : null;/.test(mCode),
    'render 가 폭을 렌더 시점에 게터로 읽는다(코드 기준)');
  check(/onReset/.test(m) && /onReset/.test(colrCode), '초기화가 콜백으로 표를 다시 그린다');

  // 즐겨찾기·최근 본 (2026-09-05 분리)
  const saved = rd('merchants-saved.js');
  const savedCode = saved.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  check(/window\.OnnuriSaved = \{/.test(saved), 'merchants-saved.js 가 OnnuriSaved 를 노출한다');
  ['attach', 'isFav', 'toggleFav', 'recordRecent', 'updateCount', 'openModal', 'closeModal']
    .forEach((k) => check(new RegExp('\\b' + k + ':').test(saved), `OnnuriSaved 가 ${k} 를 노출한다`));
  // **지도를 몰라야 한다.** 팝업 보호 플래그·상세 팝업·지도 객체는 바깥 몫이다 —
  // 모듈이 그것을 알기 시작하면 다음 사람이 마커까지 여기로 옮긴다.
  check(!/\b(mapObj|KEEP_INFO_ONCE|openInfo|initMap|naver\.maps)\b/.test(savedCode),
    '저장 모듈이 지도를 모른다(onOpenSpot 콜백만 쓴다)');
  check(!/\b(state|SNAP|refresh)\b/.test(savedCode), '저장 모듈이 허브를 건드리지 않는다');
  // 시도는 **게터**여야 한다. 값으로 붙잡으면 시도를 바꿔도 옛 지역이 스냅샷에 박힌다.
  check(/getRegion\(\)/.test(savedCode) && !/state\.sido/.test(savedCode),
    '저장 모듈이 시도를 게터로 받는다');
  check(/onOpenSpot: goSpot/.test(mCode), 'merchants 가 지도 이동을 onOpenSpot 으로 넘긴다');
  check(/getRegion: function \(\) \{ return state\.sido; \}/.test(mCode),
    'merchants 가 시도를 게터로 주입한다');

  // 브랜드 검색 팝업 (2026-09-05 분리)
  const bm = rd('merchants-brandmodal.js');
  const bmCode = bm.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  check(/window\.OnnuriBrandModal = \{/.test(bm), 'merchants-brandmodal.js 가 OnnuriBrandModal 을 노출한다');
  ['attach', 'wire', 'open', 'close', 'isOpen']
    .forEach((k) => check(new RegExp('\\b' + k + ':').test(bm), `OnnuriBrandModal 이 ${k} 를 노출한다`));
  check(!/\b(state|SNAP|refresh|MODE|apiGet|jsonAllItems|LIST_BY_MAP|multiToggle)\b/.test(bmCode),
    '브랜드 팝업이 허브·데이터소스를 모른다(게터와 콜백만 쓴다)');
  // 배선을 모듈이 한다. 종전에는 bindControls 가 `bmState.cat = ...` 로 내부 상태를
  // 직접 만졌다 — 바깥이 내부를 쓰면 경계가 이름뿐이 된다.
  check(/if \(BM\) BM\.wire\(\);/.test(mCode) && !/bmState/.test(mCode),
    'merchants 가 배선을 모듈에 맡기고 내부 상태를 직접 만지지 않는다');
  check(/onPick: pickBrand/.test(mCode), 'merchants 가 고르기(허브 쓰기)를 onPick 으로 받는다');
  // 브랜드 목록 조회는 바깥에 남아야 한다 — API 모드와 JSON 폴백이 **같은 답**을
  // 내야 하는 자리라 팝업이 갖고 있으면 규칙이 갈라진다.
  check(/function brandsForCat\(cat\) \{/.test(mCode) && /fetchBrands: brandsForCat/.test(mCode),
    '브랜드 목록 조회는 merchants 에 남아 두 경로가 갈라지지 않는다');

  // 지도 상세 팝업 (2026-09-05 분리)
  const iw = rd('merchants-infowindow.js');
  const iwCode = iw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  check(/window\.OnnuriInfoWindow = \{/.test(iw), 'merchants-infowindow.js 가 OnnuriInfoWindow 를 노출한다');
  ['attach', 'openInfo', 'openGroup', 'payTags', 'openedAt']
    .forEach((k) => check(new RegExp('\\b' + k + ':').test(iw), `OnnuriInfoWindow 가 ${k} 를 노출한다`));
  check(!/\b(state|SNAP|refresh|PANO|recordRecent|mapObj)\b/.test(iwCode),
    '팝업이 허브·저장·거리뷰를 모른다(게터와 콜백만 쓴다)');
  // 팝업을 연 시각은 **한 곳**에만 둔다. 복제하면 clearMarkers 의 유예 판단과 갈라져
  // 모바일에서 방금 연 팝업이 닫히던 2026-08-24 결함이 되살아난다.
  check(!/popupOpenedAt/.test(mCode) && /IW\.openedAt\(\)/.test(mCode),
    '팝업을 연 시각이 모듈 한 곳에만 있고 바깥은 게터로 읽는다');
  check(/onOpen: recordRecent/.test(mCode) && /onPano: function/.test(mCode),
    'merchants 가 최근 본·거리뷰를 콜백으로 잇는다');
  // 결제 표시는 표·팝업이 **같은 창구**를 써야 한다(2026-09-04 사본 적발).
  check(/payTags\(r, "pay "\)/.test(mCode) && /payTags: function/.test(iw),
    '결제 표시 창구가 하나다(표가 모듈의 payTags 를 쓴다)');
  // 손잡이는 열 **안쪽**에 있어야 한다. `right:-5px` 로 경계에 걸치면 다음 열 th 가
  // (각 th 가 sticky 라 형제 스택 컨텍스트다) 오른쪽 절반을 덮어 **잡히지 않는다** —
  // 2026-09-05 실측: elementFromPoint 로 재면 왼쪽 4px 만 `.col-grip`, 5px 부터는 `TH`.
  check(/\.col-grip \{[^}]*right:0;/.test(rd('merchants.css')),
    '컬럼 손잡이가 열 안쪽에 있다(right:0 — 경계에 걸치지 않는다)');
}

console.log();
console.log('(h) merchants — 결제 표시와 최근 본 기록의 창구가 하나여야 한다');
// 2026-09-03 '표·개별 팝업·그룹 팝업이 각각 조건을 쓰다가 팝업 두 곳만 결제 줄을 통째로
// 생략했다' → payTags 창구 신설. 2026-09-04 '그런데 표는 여전히 자기 사본을 갖고 있었고,
// 리스트 행 클릭은 card·qr 없는 합성 객체를 넘겨 결제되는 곳을 지류 전용이라 단정했다'.
/* 2026-09-05: 팝업 층이 merchants-infowindow.js 로 옮겨 갔다. 검사를 **지우지 않고
   겨냥만 옮긴다** — 지우면 '이관'과 '소실'이 구분되지 않는다(verify_build.py 의 (i)
   이관 무결성과 같은 원칙). 표 쪽 계약(m)과 팝업 쪽 계약(iwm)을 나눠 본다. */
{
  const m = HTML['merchants.html'];
  const iwm = rd('merchants-infowindow.js');
  const both = m + '\n' + iwm;
  const tip = (both.match(/공식 목록 기준 카드형/g) || []).length;
  check(tip === 1, '결제 불가 툴팁 문구가 저장소에 한 번만 있다(사본 없음)', `${tip}회`);
  check(/function payTags\(r, cls\)/.test(iwm), 'payTags 가 클래스 접두를 받는다(표도 같은 창구)');
  check(/결제 수단 미확인/.test(iwm), "payTags 가 '모름'과 '안 됨'을 가른다");

  // C-4 계약(2026-09-04 사용자 결정): 그룹 팝업은 스스로 기록하지 않는다.
  // 최근 본 기록의 창구는 openInfo 하나여야 한다 — 여기가 늘면 그룹 통째 기록이 되살아난다.
  // 분리 후 그 창구는 팝업 모듈의 `onOpen(r)` 한 줄이다.
  const calls = (iwm.match(/^\s*if \(onOpen\) onOpen\(r\);/gm) || []).length;
  check(calls === 1, '최근 본 기록 창구가 한 곳뿐이다(openInfo 안)', `${calls}곳`);
  check(!/recordRecent/.test(iwm), '팝업 모듈이 저장 모듈을 직접 부르지 않는다(콜백을 쓴다)');
  check(/function openInfo\(anchor, r, backGroup\)/.test(iwm), 'openInfo 가 되돌아갈 그룹을 받는다');
  check(/openInfo\(IWG\.anchor, r, g\)/.test(iwm), '그룹 항목 클릭이 openInfo 로 들어간다');
  check(/data-ri="' \+ i \+ '" tabindex/.test(m), '결과 표 행이 원본 행 인덱스를 갖는다');
  check(/SNAP\.list && SNAP\.list\[ri\]/.test(m), '행 클릭이 합성 객체가 아니라 원본 행을 넘긴다');
  // InfoWindow 는 내부 클릭 전파를 막아 document 위임이 닿지 않는다(2026-08-13 실측).
  check(/setTimeout\(function \(\) \{ wirePanoBtns\(\); wireIwgItems\(\); \}, 0\)/.test(iwm),
    '팝업 배선이 openInfoWindow 한 곳에서 함께 돈다');
}

console.log();
console.log('(i) online — 외부에서 들어오는 값은 창구를 거친다');
// 2026-08-27 normKind(챗봇이 kind 를 보내는 모든 온라인 질문이 빈 화면으로 끝났다),
// 2026-09-02 applyCat(대분류/소분류 슬래시 형식이 통째로 무시돼 10곳이 22곳이 됐다),
// 2026-09-03 applyBrand(brand=삼성 → 0곳). 같은 유형이 세 번 났다.
// 창구가 있어도 **입력 지점이 그것을 안 쓰면** 같은 일이 또 난다.
{
  const o = HTML['online.html'];
  // 실제 구조는 한 겹 더 낫다 — 네 창구를 `applyLanding` 하나가 모아 쓰고,
  // 챗 훅과 URL 파라미터 **두 입력 지점이 그 applyLanding 을 쓴다**.
  // 그래서 각 창구의 호출부는 하나여도 되고, 지켜야 할 계약은 두 가지다:
  //   ⓐ 창구가 존재하고 applyLanding 안에서 불린다
  //   ⓑ applyLanding 을 두 입력 지점이 함께 쓴다 (여기가 갈리면 한쪽만 정규화된다)
  const landing = (o.match(/function applyLanding\(o\)\s*\{[\s\S]*?\n  \}/) || [])[0] || '';
  check(landing.length > 0, 'applyLanding 착지 창구가 있다');
  ['normKind', 'applyCat', 'applyBrand', 'resolveTab'].forEach((fn) => {
    check(new RegExp('function ' + fn + '\\(').test(o), `${fn} 창구가 있다`);
    check(new RegExp('\\b' + fn + '\\(').test(landing), `applyLanding 이 ${fn} 을 거친다`);
  });
  const entries = (o.match(/^\s*applyLanding\(/gm) || []).length;
  check(entries >= 2, 'applyLanding 을 두 입력 지점(챗 훅·URL)이 함께 쓴다', `${entries}곳`);
  // 2026-09-04 '전국 이용 가능만' 은 확인하지 않은 사실을 단정했다.
  check(!/전국 이용 가능만/.test(o) && /지역 한정 제외/.test(o),
    '지역 필터 라벨이 하는 일 그대로다');
  // 2026-09-04 폴백이 조용했다 — 저장소 사본을 그리면서 '자동 갱신'이라 말했다.
  check(/META\.source/.test(o) && /서버 연결 실패/.test(o),
    '어느 소스를 그렸는지 화면이 말한다');
}

console.log();
console.log('(j) 데이터 사본 — 화면이 읽는 파일이 실제로 있다');
// 2026-09-02 '파일이 없으면 이 경로만 조용히 꺼지고 직접 일치 검색은 그대로 동작한다'.
// 즉 없어도 에러가 안 나고 검색 품질만 조용히 떨어진다.
// (코드↔사본의 **내용** 일치는 test_survey_probe.js 가 본다. 여기서는 존재만.)
{
  ['data/cat_rules.json', 'data/brand_aliases.json'].forEach((f) => {
    let ok = false;
    try { ok = !!rj(f); } catch (e) { ok = false; }
    check(ok, `${f} 가 있고 파싱된다`);
  });
  const o = HTML['online.html'];
  check(/cat_rules\.json/.test(o), 'online.html 이 채록 규칙 사본을 읽는다');
  check(/brand_aliases\.json/.test(o), 'online.html 이 브랜드 별칭 사본을 읽는다');
}

// ── 최종 판정 ─────────────────────────────────────────────────────────────
// 새 블록은 반드시 **이 줄 위**에 넣어라. 아래에 넣으면 실패해도 종료 코드가 0 이 된다
// (test_survey_probe.js 가 실제로 그 상태로 며칠 있었다 — 2026-09-04 발견).
console.log();
if (fail) { console.log(`실패 ${fail}건 / 전체 ${pass + fail}건`); process.exit(1); }
console.log(`전체 통과 (${pass}건)`);
