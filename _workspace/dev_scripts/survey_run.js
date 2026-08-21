#!/usr/bin/env node
/**
 * survey_run.js — 수집 결과(JSON) 파일을 받아 브랜드·카테고리 판정을 출력한다.
 * 사용: node _workspace/dev_scripts/survey_run.js <collect 결과 파일>
 *
 * Playwright evaluate 는 결과를 {"result": "<JSON 문자열>"} 형태로 감싸 저장할 수도,
 * 문자열을 그대로 저장할 수도 있다. 양쪽 다 받아들인다.
 */
'use strict';
const fs = require('fs');
const { analyze } = require('./survey_probe.js');

const path = process.argv[2];
if (!path) { console.error('사용: node survey_run.js <collect 결과 파일>'); process.exit(2); }

let text = fs.readFileSync(path, 'utf-8').trim();
// 마크다운 코드펜스로 감싸 저장되는 경우가 있다
const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/);
if (fence) text = fence[1].trim();

let raw;
try { raw = JSON.parse(text); } catch (e) { console.error('JSON 파싱 실패: ' + e.message); process.exit(1); }
// 한 겹 더 감싼 형태 흡수
for (const key of ['result', 'value', 'data']) {
  if (raw && typeof raw[key] === 'string') { try { raw = JSON.parse(raw[key]); } catch (_) {} }
}
if (typeof raw === 'string') { try { raw = JSON.parse(raw); } catch (_) {} }

if (!raw || typeof raw.text !== 'string') {
  console.error('수집 결과에 text 필드가 없다 — COLLECT_SNIPPET 으로 수집한 파일이 맞는지 확인할 것');
  process.exit(1);
}

const r = analyze(raw);
console.log(`# ${r.title}`);
console.log(`  ${r.url}  (본문 ${r.textLen.toLocaleString()}자)`);
console.log(`\n## 확인된 브랜드 (${r.confirmed.length})`);
console.log('  ' + (r.confirmed.join(', ') || '없음'));
if (r.suspect.length) {
  console.log(`\n## 오탐 의심 (${r.suspect.length}) — 문맥 보고 사람이 판정`);
  for (const s of r.suspect) console.log(`  ${s.brand.padEnd(10)} ${s.ctx}`);
}
if (r.brandDirectory.length) {
  console.log(`\n## 브랜드 디렉터리 (${r.brandDirectory.length})`);
  console.log('  ' + r.brandDirectory.join(', '));
}
if (r.brandLinks.length) {
  console.log('\n## 브랜드 링크');
  for (const b of r.brandLinks.slice(0, 4)) console.log(`  ${b.text} → ${b.href}`);
}
console.log(`\n## 카테고리 (${r.cats.length})`);
console.log('  ' + r.cats.join(' · '));
