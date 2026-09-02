#!/usr/bin/env node
/**
 * CAT_RULES(채록 판정 규칙)를 화면이 읽을 수 있는 데이터로 내보낸다.
 *
 * 왜 필요한가: 화면 검색은 지금까지 몰 이름·요약·**카테고리 라벨**·브랜드만 훑었다.
 * 그래서 "로봇청소기"로 검색하면 0곳이었다 — taxonomy 라벨이 '생활·주방가전'까지만
 * 내려가고, 몰 메뉴 원문에도 그 문구가 없다(2026-09-02 6곳 재채록으로 확인).
 *
 * 그런데 채록에 쓰는 CAT_RULES 의 appliance-home 규칙은 이미 `청소기` 를 잡는다.
 * **같은 규칙을 화면 검색에도 쓰면** 새 동의어 사전을 만들지 않고 해결된다.
 * 규칙의 출처는 여전히 코드 한 곳이고, 이 파일은 그 사본이다(테스트가 일치를 지킨다).
 *
 *   node _workspace/dev_scripts/dump_cat_rules.js
 */
const fs = require('fs');
const path = require('path');
const { CAT_RULES } = require('./survey_probe.js');

const out = {
  meta: {
    source: '_workspace/dev_scripts/survey_probe.js CAT_RULES',
    note: '채록 판정 규칙의 사본. 손으로 고치지 말고 이 스크립트를 다시 돌린다. '
        + '코드와 어긋나면 test_survey_probe.js 가 실패한다.',
    generated_from_rules: CAT_RULES.length,
  },
  rules: CAT_RULES.map(([cat, re]) => ({ cat, re: re.source, flags: re.flags })),
};
const dest = path.join(__dirname, '..', '..', 'data', 'cat_rules.json');
fs.writeFileSync(dest, JSON.stringify(out, null, 1) + '\n', 'utf8');
console.log(`${dest}: 규칙 ${out.rules.length}건`);
