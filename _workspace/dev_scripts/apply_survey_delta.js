/**
 * apply_survey_delta.js — 채록 델타를 카탈로그에 반영한다 (2026-09-05 신설)
 *
 * **자동으로 도는 것이 아니다.** 사람이 주간 다이제스트를 보고 "반영하자"고 정했을 때
 * 부르는 도구다(ADR-16 — 배치는 탐지만 한다). 규칙을 코드로 적어 두는 이유는,
 * 다음에 반영할 때 같은 판단을 다시 처음부터 하지 않기 위해서다.
 *
 * 사용: node _workspace/dev_scripts/apply_survey_delta.js <survey-delta-YYYY-MM-DD.json>
 *
 * 규칙(2026-09-05 확정 — 근거는 _workspace/15_online_catalog_report.md):
 *   ① 기획전 딥링크 몰은 **판단 보류** — 호스트 몰 전체 GNB 가 섞인다.
 *   ② 브랜드는 BRAND_DICT 에 있는 것만 — brandDirectory 스크랩이 카테고리 메뉴를 물어 온다
 *      (찬스 118건 중 진짜는 4건이었다).
 *   ③ 카테고리는 소분류만. 소분류가 이미 있으면 부모 단독 id 는 넣지 않는다.
 *   ④ thin 인 몰은 건드리지 않는다.
 *   ⑤ 반영·확인한 몰만 surveyed_on 을 올린다 — 보류한 몰의 날짜를 올리면 화면이 거짓말한다.
 */
const fs=require('fs');
const path=require('path');
const {BRAND_DICT,normalizeBrands}=require(path.join(__dirname,'survey_probe.js'));
const CAT=path.join(__dirname,'..','..','data','online_catalog.json');
const src=process.argv[2];
if(!src){ console.error('사용: node apply_survey_delta.js <survey-delta-YYYY-MM-DD.json>'); process.exit(2); }
const rep=JSON.parse(fs.readFileSync(src,'utf-8')).report;
const cat=JSON.parse(fs.readFileSync(CAT,'utf-8'));
const DICT=new Set(normalizeBrands(BRAND_DICT));
const TODAY=(process.argv[3])||new Date().toISOString().slice(0,10);   // 확인일. 인자로 덮어쓸 수 있다

// taxonomy: 대분류 id 와 소분류 id
const parents=new Set(), subs=new Set();
for(const t of cat.taxonomy){ parents.add(t.id); for(const c of (t.subs||[])) subs.add(c.id); }

const by={}; for(const p of cat.items) by[p.id]=p;
const log=[];
for(const r of rep){
  const p=by[r.id]; if(!p) continue;
  if(!r.ok || r.thin){ log.push([r.label,'건드리지 않음(수집 실패·본문 얇음)']); continue; }
  if(r.deepLink){ log.push([r.label,'판단 보류(기획전 딥링크 — 호스트 GNB 섞임)']); continue; }

  // ① 브랜드: 사전에 있는 것만. brandDirectory 스크랩이 카테고리 메뉴를 물어 오는 몰이 있다.
  const nb=(r.newBrands||[]).filter(b=>DICT.has(b));
  const dropped=(r.newBrands||[]).filter(b=>!DICT.has(b));
  // ② 카테고리: 소분류만. 이미 그 대분류의 소분류를 갖고 있으면 부모 단독 id 는 넣지 않는다.
  const has=new Set(p.cats||[]);
  const nc=(r.newCats||[]).filter(c=>{
    if(has.has(c)) return false;
    if(subs.has(c)) return true;
    if(parents.has(c)) return ![...has].some(x=>x.startsWith(c+'-'));  // 소분류가 이미 있으면 부모는 안 넣는다
    return false;
  });
  if(nb.length){ p.brands=[...new Set([...(p.brands||[]),...nb])].sort((a,b)=>a.localeCompare(b,'ko')); }
  if(nc.length){ p.cats=[...new Set([...(p.cats||[]),...nc])].sort(); }
  p.surveyed_on=TODAY;   // 확인했고 반영했다(변화가 없어도 확인일은 올린다)
  log.push([r.label,`브랜드 +${nb.length}(제외 ${dropped.length}) 카테고리 +${nc.length}`]);
}
cat.meta=cat.meta||{}; cat.meta.collected_on=TODAY;
fs.writeFileSync(CAT, JSON.stringify(cat,null,1)+'\n','utf-8');
for(const [a,b] of log) console.log(`  ${a.padEnd(24)} ${b}`);
