#!/usr/bin/env node
/**
 * BRAND_ALIASES(브랜드 표기 표준화 사전)를 화면이 읽을 수 있는 데이터로 내보낸다.
 *
 * 왜 필요한가: 챗봇이나 URL 이 `brand=삼성` 으로 착지하면 화면이 **0곳**을 보여 준다.
 * 카탈로그의 표준 표기는 `삼성전자` 이고 화면 필터는 정확 일치로만 비교하기 때문이다
 * (2026-09-03 감사 적발 — 라이브 실측: brand=삼성전자 → 9곳 / brand=삼성 → 0곳).
 *
 * 채록은 이미 이 사전으로 표기를 통일하고 있다(survey_probe.js normalizeBrand).
 * **같은 사전을 화면에도 쓰면** 새 사전을 만들지 않고 해결된다 — 2026-09-02 에
 * CAT_RULES 를 data/cat_rules.json 으로 내보낸 것과 같은 방식이고, 같은 이유로
 * 이 파일은 사본이며 코드와 어긋나면 test_survey_probe.js 가 실패한다.
 *
 *   node _workspace/dev_scripts/dump_brand_aliases.js
 */
const fs = require('fs');
const path = require('path');
const { BRAND_ALIASES } = require('./survey_probe.js');

const out = {
  meta: {
    source: '_workspace/dev_scripts/survey_probe.js BRAND_ALIASES',
    note: '브랜드 표기 표준화 사전의 사본. 손으로 고치지 말고 이 스크립트를 다시 돌린다. '
        + '코드와 어긋나면 test_survey_probe.js 가 실패한다.',
    generated_from_aliases: Object.keys(BRAND_ALIASES).length,
  },
  aliases: BRAND_ALIASES,
};
const dest = path.join(__dirname, '..', '..', 'data', 'brand_aliases.json');
fs.writeFileSync(dest, JSON.stringify(out, null, 1) + '\n', 'utf8');
console.log(`${dest}: 별칭 ${out.meta.generated_from_aliases}건`);
