# 24. merchants 허브 실측 (2026-09-05, C1)

**사용자 요청**: "C1 : 별도 실측 보고서 만들어줘" — 허브를 해체할지 정하기 전에
**지금 무엇이 어떻게 얽혀 있는지**부터 재라는 뜻이다.

읽기 쉬운 보고서는 별도 페이지로 냈고, 이 파일은 **원자료**다. 네 갈래를 각각 재고
그 결과를 다시 코드로 검증했으며(검증 단계가 수치 20여 건을 정정), 아래는 그 검증본이다.

핵심 수치(내가 직접 재확인) — merchants.html **1,576줄 · 함수 95개**,
`state` 참조 173(읽기 107 · 쓰기 66) · 필드 11, `SNAP` 참조 35(읽기 21 · 쓰기 14) · 필드 13,
`refresh(` 호출 18. 주석·문자열을 지운 사본에서 **출현 단위**로 셌다.

**실측 중 확증된 결함 2건**은 별도로 다룬다 — 결함 ①(`regionTotal` 수명 불일치)은
화면에서 재현했다: 지도 범위 모드에서 축소하면 `95곳 중 2,163곳 표시`.

---

### merchants.html `state` 객체 실측 — 11개 필드 · 읽기 106 · 쓰기 66 (270행 선언, 총 1,575행)
 - `state` 는 11개 필드의 평평한 객체 하나다. 중첩 없음, 클래스 없음, 270~274행 5줄에 전부 들어간다.
    수치: 필드 11개. 선언 실물: `sido:"서울", si:"전체", gu:"전체", dong:"전체", cat:"전체", brand:"전체", mtype:"전체", digitalOnly:false, q:"", sort:"default", page:1`. 의미 — sido=시도 탭(서울/인천/경기/부산) · si=경기 전용 시(市) · gu=구 · dong=동(
    의미: 떼어낼 단위가 11개뿐이고 전부 스칼라(문자열·불리언·정수)라, 모듈에 넘길 때 게터 11개면 전부 덮인다. 참조 공유 위험(객체·배열)이 없어 값 복제 사고가 원리적으로 안 난다 — merchants-*.js 분리에서 `mapObj` 를 게터로 넘겨야 했던 것과 성격이 다르다.
 - 읽기 106 · 쓰기 66. 필드별 편차가 크고, 읽기/쓰기 비율이 뒤집힌 필드가 하나(page) 있다.
    수치: Node 스크립트로 주석(줄·블록·HTML)을 공백으로 치환한 뒤 `/state\.(\w+)/g` 를 **출현 단위**로 세고, 뒤에 `=`(단, `==`/`===`/`=>` 제외)가 오면 쓰기로 분류. 결과 — sido 18R/2W · gu 17R/6W · brand 14R/6W · si 13R/5W · cat 10R/6W · dong 9R/7W · mt
    의미: sido 는 18번 읽히는데 2번만 쓰인다 — 읽기 전용에 가까워 게터 하나로 안전하게 내보낼 수 있다(실제로 1527행 SAVED.attach 가 이미 `getRegion: () => state.sido` 로 그렇게 한다). 반대로 page 는 21번 쓰이고 5번만 읽혀, 어떤 모듈로도 뗄 수 없는 순수 허브 값이다.
 - 쓰기 주체는 사용자 조작이 압도적(42/66)이고, 외부 입력 15, 내부 로직 1, 겸용 8이다. 내부 로직 단독 쓰기는 **딱 한 곳**뿐이다.
    수치: 쓰기 66건을 감싸는 함수/핸들러로 귀속시켜 분류. **사용자 조작 42** — 지역 셀렉트 757/758/759(9건), 업종 칩 775(2), 브랜드 칩 809(2), 브랜드 팝업 pickBrand 864·866·867(3), 지도범위 토글 964(1), 페이저 1322-1325(4), 검색창 debounce 1452(2), 정렬 셀렉트 1472·147
    의미: 쓰기 창구가 사실상 전부 DOM 이벤트 핸들러이고, 그 핸들러들은 이미 `bindControls`(1450~1495)·`renderRegionSelects`(757-759)·`renderChips`(775·809)에 모여 있다. 즉 **state 를 쓰는 코드와 화면을 그리는 코드가 이미 갈라져 있다** — 분리할 때 `state` 를 통째로 올릴 필요 없
 - 지역 3계층은 하향 캐스케이드로 함께 초기화된다. 그런데 **시도만은 업종·브랜드·시장유형까지 함께 지운다** — 지역 계층 밖으로 번지는 유일한 리셋이다.
    수치: ①`selSi.onchange`(757) → si=값, **gu="전체", dong="전체"**, page=1 ②`selGu.onchange`(758) → gu=값, **dong="전체"**, page=1 ③`selDong.onchange`(759) → dong=값, page=1 (하위 없음) ④`selectSido`(1386-1387) → sido=값 +
    의미: '시도를 바꾸면 필터가 다 풀린다'가 코드에 있는 계약인데 화면 어디에도 안 적혀 있다. 지역 로직을 모듈로 떼면 이 함수가 cat·brand·mtype 까지 만져야 해서 **지역 모듈이 필터 모듈을 알아야 하는 결합**이 생긴다 — 떼려면 `selectSido` 를 '지역 설정'과 '필터 초기화' 두 콜백으로 갈라야 한다.
 - `page=1` 은 사실상 모든 필터 쓰기에 자동 동반된다. page 쓰기 21건 중 17건이 그 리셋이고, 진짜 페이지 이동은 4건뿐이다.
    수치: page 쓰기 21건 = 리셋(`=1`) 17건 + 페이저 실이동 4건(1322 first / 1323 prev `state.page-1` / 1324 next `state.page+1` / 1325 last). 리셋 17건이 붙는 자리: 757,758,759,775,809,867,964,1009,1387,1427,1452,1472,1476,1478,147
    의미: page 는 나머지 10필드의 종속 변수다. 다른 필드를 만지는 창구를 하나로 모으면 page=1 을 그 창구 한 곳에서 처리해 17곳이 1곳이 된다 — 지금은 새 필터를 추가할 때마다 page=1 을 손으로 붙여야 하고, 빠뜨리면 '2페이지에서 필터를 바꿨더니 빈 화면'이 조용히 난다.
 - `q`·`digitalOnly`·`sort` 는 state 와 DOM 값이 **양방향으로 동기화**돼야 하는 필드다. q 는 쓰기 5건 중 4건이 `el("q").value` 를 함께 만진다.
    수치: q 쓰기 5건의 DOM 동반 — 1426 `state.q=p.q; qi.value=state.q`(state→DOM) · 1441 다음 줄 1442 `qi.value=""` · 1452 input 이벤트(DOM→state, 200ms debounce) · 1496 resetSearch `el("q").value=""` · 1500 resetAll 다음 줄 
    의미: 칩·셀렉트 계열은 state 가 단일 진실이라 그냥 뗄 수 있지만, 이 3필드는 **DOM 이 두 번째 사본**이라 모듈로 옮기면 동기화 지점이 모듈 경계를 넘는다. resetAll 을 모듈로 뺄 때 `el("q").value=""` 를 빠뜨리면 '검색어는 지워졌는데 입력창에 글자가 남는' 상태가 되고 에러는 안 난다.
 - `resetAll` 은 10필드를 지우지만 `sido` 는 건드리지 않는다. 반대로 `selectSido` 는 sido 를 포함해 8필드를 지운다 — 두 리셋의 범위가 어긋난다.
    수치: resetAll(1497-1502) 이 쓰는 필드 9개 = si,gu,dong,cat,brand,mtype,digitalOnly,q,page. **sido·sort 제외.** selectSido(1384-1387) 가 쓰는 필드 8개 = sido,si,gu,dong,cat,brand,mtype,page. **digitalOnly·q·sort 제외.** 두 
    의미: '검색 초기화'가 시도는 유지한다는 판단은 의도적으로 보이지만(빈 결과에서 지역까지 날리면 맥락 상실), 두 리셋이 서로 다른 필드 집합을 갖는다는 사실이 코드 어디에도 안 적혀 있다. 필드를 새로 추가하면 **두 곳 다 고쳐야 하는데 한 곳만 고치기 쉽다** — 실제로 resetAll 은 2026-09-04 에 LIST_BY_MAP 을 빠뜨렸던 전력이 이
 - state 를 읽는 코드는 이미 4개 소비처로 갈라져 있다 — API 파라미터 조립 · JSON 폴백 필터 · 컨트롤 렌더 · 모듈 게터. 앞의 둘은 같은 필터 규칙을 **두 벌로 구현**한다.
    수치: 읽기 106건의 소재 — ①`regionParams`+`fullParams`(353-380) 22건: state → API 요청 파라미터 ②JSON 폴백 필터(471-495, 513-536, 617-619) 34건: state → 로컬 배열 filter ③컨트롤 렌더(721-821, 860-861, 878, 919, 926) 27건: state → 칩·셀렉
    의미: 필터 의미론이 두 벌이라 한쪽만 고치면 API 모드와 JSON 폴백이 다른 답을 낸다 — 이 저장소가 온라인몰 쪽에서 이미 겪은 유형('브랜드 목록 조회를 팝업이 아니라 merchants 에 남겼다'는 2026-09-05 판단과 같은 논리). state 를 떼기 전에 **이 이중 구현부터 하나로 모으는 것**이 선행 과제다. 반대로 ⑤ 모듈 게터 3건은 이
  주의: 세는 방법에서 틀리기 쉬운 것 다섯 가지. ①**`grep -c` 로 세면 안 된다** — 줄 수를 세므로 한 줄에 같은 필드가 두 번 나오는 관용구(`if (state.brand !== \"전체\") p.brand = state.brand;`)에서 절반을 잃는다. 실측 격차: gu 줄수 17 vs 출현수 23(−6), brand 13 vs 20(−7), si 15 vs 19, cat 11 

### merchants.html SNAP 실측 — 필드 13개(리터럴 12+런타임 1) · 쓰기 함수 2개/14회 · 읽기 함수 7개/21회
 - SNAP 의 필드는 13종이다 — 리터럴 선언 12개 + 런타임에만 생기는 1개(mapClusterRows).
    수치: 리터럴 12: list, total, pages / facetCat, catTotal, facetBrand, facetMtype / mapPins, mapTotal, mapTruncated / regionTotal, sidoTotal. 런타임 1: mapClusterRows(669행에서만 생성). 세는 법 — `sed -n '277,282p' merchan
    의미: 리터럴만 보고 필드 목록을 만들면 mapClusterRows 를 빠뜨린다. 그 필드는 초기값 계약이 혼자 다르다 — 첫 지도 회차 전에는 `undefined`, API 모드에서는 매 지도 회차마다 `null` 로 덮인다. 읽는 쪽(1070·1201)이 `|| []`, `||` 로 방어하고 있는 것이 그 증거다. 떼어낼 때 이 필드만 초기값을 명시하지 않으면
 - 쓰기는 함수 딱 2개에서만 일어난다 — refresh 13회, selectSido 1회.
    수치: 대입 총 14회. `grep -o "SNAP\.[A-Za-z]* *=[^=]" merchants.html | wc -l` = 14, 줄번호를 함수 정의 위치와 대조. refresh 13회: 655(list) 656(total) 657(pages) 660(facetCat) 661(catTotal) 662(facetBrand) 663(facetMtype) 66
    의미: 쓰기 창구가 사실상 하나라 SNAP 을 모듈로 떼기가 예상보다 쉽다 — `applySnapshot(R)` setter 하나면 13개를 덮는다. 남는 예외는 selectSido 의 sidoTotal 단 1회이고, 그 1회가 곧 '쓰기 함수가 2개'인 유일한 이유다(F7 참조 — 그 필드를 SNAP 밖으로 빼면 쓰기 함수가 1개가 된다).
 - 읽는 함수는 7개 21회이고, 표 쪽 12회와 지도 쪽 6회가 서로의 필드를 한 번도 넘보지 않는다.
    수치: 읽기 21회 = 등장 36회 − 쓰기 14회 − 주석 1회(1293). 함수별: render 8회(877 total, 878 sidoTotal, 879 regionTotal+total, 882 total, 884 pages, 885 list, 926 total) / renderMap 3회(1110 mapTruncated, 1111 mapTotal, 1117
    의미: 필드 경계와 독자 경계가 정확히 겹친다. 지도 3함수(6회)는 map* 4필드만 읽고, 표·집계 4함수(12회)는 map* 를 한 번도 안 읽는다. 즉 `SNAP.map*` 4개를 별 객체로 떼면 표 쪽 코드는 한 줄도 안 바뀐다 — 이 허브에서 가장 깨끗한 절단면이다.
 - '서버 응답 스냅샷'이라는 주석은 절반만 맞다 — 13필드 중 서버 값 그대로는 6개뿐이다.
    수치: 서버 응답을 그대로 옮기는 것 6: list←list.items(571·655), total←list.total(571), facetBrand←fac.brand(580), mapTotal←mp.total(582), mapTruncated←mp.truncated(582), regionTotal←rc.total(583). 클라이언트가 모양을 바꿔 넣는 것 5:
    의미: 캐시 무효화를 '서버 응답이 왔는가'로 설계하면 sidoTotal 과 mapClusterRows 두 필드가 그 규칙 밖으로 샌다. 변환이 apiQuery(578·579·580)와 refresh(657·663·667) 두 층에 흩어져 있는 것도 함께 봐야 한다 — 한 층만 옮기면 모양이 반쯤 바뀐 값이 SNAP 에 들어간다.
 - scope 4종의 갱신 범위는 refresh 가 아니라 apiQuery/jsonQuery 가 정하고, refresh 는 'R 에 그 필드가 있는가'로만 판단한다.
    수치: 매트릭스(필드그룹 × scope) — all: list·total·pages ✓ / facet4 ✓ / map4 ✓ / regionTotal ✓ (API 4콜). filter: list·total·pages ✓ / facet4 ✗ / map4 ✓ / regionTotal ✗ (2콜). facet: list·total·pages ✓ / facet4 ✓ / m
    의미: scope→요청→필드가 세 파일 위치로 흩어져 있다(apiQuery·jsonQuery·refresh). 셋 중 하나만 옮기면 '요청은 나가는데 SNAP 에 안 담기는' 또는 그 반대의 조용한 결함이 생긴다. 특히 `undefined` 판정이라 서버가 나중에 빈 객체 `{}` 를 주기 시작하면 게이트가 반대로 열린다.
 - regionTotal 은 scope="all" 에서만 갱신되는데, 지도범위 모드의 지도 이동은 "facet" 이라 total 과 짝이 어긋난다.
    수치: regionTotal 갱신 경로는 18개 호출 중 all 8곳뿐. 지도범위 모드(LIST_BY_MAP)에서 지도를 움직이면 1009행이 450ms 뒤 refresh("facet") → total 은 새 bounds 기준, regionTotal 은 직전 "all" 회차의 bounds 기준으로 남는다. 879행이 그 둘을 한 문장에 쓴다: `nf(SNAP.re
    의미: 축소하면 새 bounds 의 total 이 옛 bounds 의 regionTotal 을 넘어 'N곳 중 M곳' 에서 M>N 이 나올 수 있다. 분해 설계에서 regionTotal 을 total 과 같은 슬롯에 두면 이 수명 차이가 그대로 옮겨 간다 — 두 값의 갱신 주기가 다르다는 것을 타입/구조로 드러내야 한다.
 - sidoTotal 은 SNAP 이 소유한 값이 아니라 SIDO_TOTAL 캐시의 사본이고, SNAP 쓰기 함수가 2개인 유일한 원인이다.
    수치: 4회 등장 = 쓰기 2(672·1391) + 읽기 2(672 우변 자기폴백·878). R 경유 0회 — 값의 출처는 100% `SIDO_TOTAL[state.sido]`(285행 선언, 588-594 loadSidoTotal 이 시도당 1회 조회 후 캐시). 실제로 화면에 나가는 곳은 878행 하나이고 그마저 `LIST_BY_MAP` 이 꺼졌을 때만(876
    의미: 같은 수를 캐시 두 개가 들고 있다. 이 필드를 SNAP 에서 빼고 render 가 `SIDO_TOTAL[state.sido]` 를 직접 읽게 하면 ①필드 13→12 ②쓰기 함수 2→1(refresh 단독) ③672행의 자기폴백 삼항이 사라진다. 분해 시 가장 값싼 정리 대상.
 - pages 와 catTotal 은 상태가 아니라 계산식이다 — 저장하지 않아도 되는 파생값 2개.
    수치: pages: 쓰기 1(657 `Math.max(1, Math.ceil(R.total / PAGE_SIZE))`) + 읽기 1(884) = 2회. total 만 있으면 언제든 재계산된다. catTotal: 쓰기 1(661) + 읽기 1(681 '전체' 칩) = 2회. 값의 정체는 API 모드 579행 `(fac.cat||[]).reduce(합)`, JSON 
    의미: 13필드 중 2개를 게터로 바꾸면 필드가 11개로 줄고, total↔pages / facetCat↔catTotal 이 어긋날 여지가 원천 제거된다. 둘 다 읽는 곳이 1곳뿐이라 치환 비용도 최소다.
 - JSON 모드에서 list·mapPins·mapClusterRows 는 같은 행 객체를 공유한다 — SNAP 은 불변 스냅샷이 아니다.
    수치: jsonQuery 가 `full` 하나를 셋에 나눠 준다: R.list = full.slice(start, start+50)(새 배열·같은 원소), R.mapPins = 상한 초과면 [] 아니면 full, R.mapClusterRows = full(556행 주석이 '참조만 — 복사 없음'이라 명시). refresh 의 `.map(normItem)`(655·
    의미: 배열 교체와 원소 수정의 파급이 비대칭이다 — 배열을 갈아 끼우면 셋이 독립이지만, 행 하나를 고치면 표·지도·클러스터가 동시에 바뀐다. 떼어낼 때 이 비대칭을 계약으로 적지 않으면 다음 사람이 '어차피 복사본'으로 읽고 행을 직접 손댄다. mapTruncated 일 때 mapPins 는 비고 mapClusterRows 만 남는 것도 함께 봐야 한다 — 그
 - SNAP 은 IIFE 밖으로 한 번도 나가지 않지만, refresh 밖에서도 읽힌다 — 서버 호출 없는 재렌더 경로가 3개다.
    수치: 외부 노출 0: 외부 모듈 6개(merchants-split/colresize/saved/brandmodal/infowindow/pano.js)에서 `SNAP` 문자열 6회가 잡히지만 **전부 주석**(코드 0회), `window.SNAP` 0회, attach 주입 계약 6곳(1512-1545)에 SNAP 없음. 반대로 fetch 없이 SNAP 을 다시 읽
    의미: 캡슐화는 이미 지켜져 있어 모듈 경계는 걱정할 게 없다. 대신 '서버 응답이 올 때만 읽힌다'는 전제가 틀렸다 — 즐겨찾기 토글·컬럼 폭 리셋·지도 idle 이 SNAP 을 렌더의 단독 원천으로 쓴다. SNAP 을 떼면 그 세 경로가 즉시 소비자가 되므로, 응답 버퍼가 아니라 '렌더 데이터 원천'으로 설계해야 한다.
 - 캐시인가 파생인가의 답은 필드가 아니라 MODE 로 갈린다 — JSON 모드에선 13필드 전부 파생, API 모드에선 6필드가 서버만 아는 값.
    수치: MODE="json": jsonQuery(537-559)가 서버에 아무것도 묻지 않고 jsonLoadSido 로 이미 받아 둔 배열에서 13필드를 전부 계산한다 → 100% 재계산 가능. MODE="api": 클라이언트에 전체 행(약 79,800건)이 없으므로 list·total·facetBrand·mapTotal·mapTruncated·regionTota
    의미: 이것이 이 허브를 떼기 어려운 진짜 이유다. 순수 파생 스토어(selector)로 리팩터링하면 API 모드가 깨지고, 순수 응답 캐시로 다루면 JSON 모드에 불필요한 사본이 남는다. 경계는 필드가 아니라 모드에 그어져 있으므로, 분해 설계는 '두 모드가 같은 SNAP 모양을 채운다'는 현재의 정규화 계약(apiQuery 주석 561행 '동일 정규화 형태'
  주의: 이 갈래에서 세다가 틀리기 쉬운 것 6가지. ①**리터럴만 세면 12로 틀린다** — mapClusterRows 는 669행 대입으로만 생긴다(정답 13). ②**한 줄에 SNAP 참조가 둘인 줄이 4개**(668 mapTotal+mapTruncated, 879 regionTotal+total, 1201 mapClusterRows+mapPins, 1295 list×2, 681 catTotal

### merchants.html refresh(scope) 실측 — 갱신 계획·경합·렌더 팬아웃이 한 함수에 겹쳐 있다
 - scope 는 4종이 아니라 **불리언 3개**로 갈린다 — `wantFacets`·`wantMap`·`wantRegionTotal`. 문자열은 그 셋을 고르는 이름표일 뿐이고, **모르는 값은 조용히 "filter" 와 똑같이 동작**한다(enum 검증 없음).
    수치: scope 값 4종(all/filter/facet/page) + 인자 없음(→ `scope || "all"`, 650행). API 모드 요청 수: all=4콜(list+facets+map+regionTotal) · filter=2콜(list+map) · facet=2콜(list+facets) · page=1콜(list). 판정식은 561–564행 딱 3줄 
    의미: 상태 저장소로 옮길 때 `scope` 문자열을 그대로 옮기면 안 된다 — 실제 계약은 **"어느 파생값을 무효화할 것인가" 3비트**다. 저장소에서는 이 3비트를 호출자가 선언하는 대신 **바뀐 축(region/digital → facets+regionTotal, cat/brand/mtype/q/sort → list+map, page → list)에서 유도
 - 호출자는 **18곳**이고 그중 **5곳이 인자 없이** 부른다 — `grep 'refresh("'` 로 세면 13곳만 잡혀 기본값 "all" 경로를 통째로 놓친다.
    수치: scope별 호출자 18곳: **암묵 all 5** — 763 afterRegionChange(시/구 변경·옵션 재로드), 764 afterRegionChange(동 변경), 1380 renderResultError 재시도 버튼, 1393 selectSido(시도 탭), 1506 resetAll(빈 결과 '검색 초기화'). **명시 all 3** — 965
    의미: 저장소 액션을 설계할 때 호출자 목록이 곧 액션 목록이다. **인자 없는 5곳이 전부 '지역 축이 바뀌었다'(afterRegionChange·selectSido·resetAll) 아니면 '재시도'**라 의미가 서로 다른데 지금은 같은 이름으로 뭉쳐 있다 — 재시도(1380)는 아무것도 안 바뀌었는데 4콜을 다 쏘는 유일한 경로다.
 - 경쟁 조건은 `refreshSeq` **하나로만** 막고, 늦게 온 응답은 **SNAP 을 쓰기 전 한 줄에서 통째로 버린다**. 다만 요청 자체는 취소하지 않는다(AbortController 없음).
    수치: 카운터 3개가 **서로 독립**으로 돈다 — `refreshSeq`(648행, refresh 전용) · `viewportSeq`(877행 근처 선언, viewportRender 전용) · `clusterSeq`(1015행 근처, 클러스터 전용). 버리는 자리는 2곳뿐: 654행 `if (seq !== refreshSeq) return;`(성공 경로, SNA
    의미: 버려진 응답도 **네트워크는 이미 다 썼다** — 검색어를 빠르게 치면 200ms 디바운스를 통과한 회차마다 2콜이 나가고 마지막 것만 반영된다. 그리고 늦게 온 refresh 는 버려지지만 **그 refresh 가 부를 예정이던 renderMap 도 함께 사라져** 지도만 옛 필터로 남는 창이 생긴다(지도는 자기 idle 로만 회복). 저장소로 옮기면 이
 - 부수 효과는 **SNAP 부분 갱신 → 칩 재렌더 → 목록 재렌더 → (조건부) 지도 재렌더** 넷이고, scope 와 무관하게 **칩과 목록은 항상 통째로 다시 그린다**. 스크롤은 refresh 가 만지지 않는다(호출자 몫).
    수치: SNAP 쓰기 8종(655–672행): list·total·pages 는 **항상**, facetCat·catTotal·facetBrand·facetMtype 는 `R.facetCat!==undefined` 일 때만, mapPins·mapTotal·mapTruncated·mapClusterRows 는 `R.mapPins!==undefined` 일 때만, r
    의미: "무엇이 바뀌었나"와 "무엇을 다시 그리나"의 경계가 **673행 한 줄**에 뭉개져 있다. 저장소로 가르면 655–672(커밋)만 저장소가 갖고 673–675(팬아웃)는 구독자가 되어야 하며, 그 순간 **scope 별로 무엇을 다시 그릴지 고를 수 있게 된다** — 지금은 page 넘김에도 칩 24개를 새로 만든다. 숨은 부수 효과 2개도 함께 옮겨야 
 - API/JSON 갈림은 **652행 한 줄**뿐 — 그 아래는 완전히 공통이다. 두 경로가 같은 정규화 형태를 내도록 되어 있고 scope 판정도 같은 식을 쓴다. 대신 **비용 구조가 정반대**다.
    수치: 652행 `var query = (MODE === "json") ? jsonQuery(scope) : apiQuery(scope);`. API: scope 가 곧 HTTP 콜 수(1~4). JSON: scope 와 무관하게 **매번 `jBase(items)` + `jFull(base)` 전수 순회** — 시도 파일 행 수 서울 29,728 · 경기 30,0
    의미: 저장소로 옮길 때 **JSON 폴백은 '서버 왕복'이 아니라 '순수 함수 재계산'**이라는 점이 드러나야 한다 — 이쪽은 메모이제이션할 자리(`base` 는 지역·digital 이 안 바뀌면 불변)가 명확한데 지금은 scope 가 그걸 표현하지 못한다. 또 하나: **LIST_BY_MAP 은 API 모드 전용**이라(336–352행 `LIST_BY_MAP 
 - **결함 후보 ①** — 지도 범위 모드에서 지도를 옮기면 `countText` 의 앞 숫자(regionTotal)가 **옛 뷰포트 값으로 남는다**. "N곳 중 M곳 표시"에서 M > N 인 문장이 나올 수 있다.
    수치: 경로: 965행 toggleListByMap → refresh("all") → regionTotal = 그 시점 bounds 건수. 이후 지도 이동 → 1009행 refresh("facet") → `wantRegionTotal=(scope==="all")` 이라 **false** → 671행 조건 미충족 → SNAP.regionTotal 불변. 그런데 SN
    의미: scope 표를 손으로 관리해서 생긴 **표 드리프트의 실물**이다. 저장소로 옮기며 무효화를 '바뀐 축'에서 유도하면 이 부류가 구조적으로 사라진다 — bounds 가 baseParams 에 들어가는 순간 regionTotal 도 자동으로 무효가 되어야 한다. 코드 경로로 확인한 것이고 브라우저 실행으로 재현하지는 않았다.
 - **결함 후보 ②** — `wantMap` 이 받아 온 `/merchants/map` 페이로드가 **API 모드에서 거의 항상 버려진다**. renderMap 이 `MAP_FIT_NEXT` 가 서 있을 때만 SNAP.mapPins 를 읽고, 아니면 곧바로 viewportRender 로 넘어가 **같은 엔드포인트를 뷰포트 조건으로 다시 부른다**.
    수치: 1109행 `if (!consumeMapFit()) { viewportRender(); return; }` — SNAP.mapPins·mapTotal·mapTruncated 를 읽는 자리(1111·1117행)는 전부 그 아래, 즉 fit 경로 안. `MAP_FIT_NEXT=true` 를 세우는 곳은 **딱 2곳**(1385 selectSido, 1419 a
    의미: "무엇을 다시 받나"와 "무엇을 다시 그리나"가 이미 어긋나 있다는 증거다. scope 표는 2026-08-12 뷰포트 자동 지도가 들어오기 전 계약을 그대로 들고 있다. 저장소 설계에서 지도 데이터는 **refresh 의 산출물이 아니라 지도 자신의 질의**로 떼어 내는 것이 코드가 이미 하고 있는 일에 맞다 — 그러면 wantMap 불리언 자체가 사라진
 - **이것이 해체의 진짜 경계다** — 목록·칩은 커밋된 스냅샷(SNAP)을 읽지만 **지도는 API 모드에서 SNAP 을 전혀 읽지 않고 `state` 를 실시간으로 다시 읽는다**. 같은 필터에 대해 저장소가 둘이다.
    수치: SNAP 독자 실측: render/renderChips 가 11곳(782·824·877·878·879·882·884·885·926), 행 클릭이 1곳(1295). 지도 쪽 SNAP 독자는 fit 경로 2곳(1111·1117)과 JSON 전용 2곳(1070·1201)뿐. 반면 viewportRender 는 1213·1216행에서 **`fullParams()`
    의미: 상태 저장소 리팩토링의 성패가 여기서 갈린다. **"무엇이 바뀌었나" = state(필터 축 9개: sido·si·gu·dong·cat·brand·mtype·digitalOnly·q + page·sort) + LIST_BY_MAP + 지도 bounds. "무엇을 다시 그리나" = SNAP(list·total·pages / facet 4종 / map 4종 /
 - 실패 경로가 **부분 상태를 남긴다** — 목록 자리만 오류로 갈아치우고 칩·지도·카운트는 이전 값을 그대로 들고 있다. 게다가 **렌더 예외도 같은 catch 로 떨어져** 이용자에게는 네트워크 실패로 보인다.
    수치: 676–679행 catch 하나가 ①질의 실패 ②`renderChips()` 예외 ③`render()` 예외를 전부 받는다(renderMap 만 675행에서 try/catch 로 분리). 결과는 renderResultError(1377–1381행) — `#resultArea` innerHTML 교체 + `#countText`='로드 실패' **2곳만** 건
    의미: 저장소로 가르면 '질의 실패'와 '렌더 실패'가 서로 다른 층의 사건이 되어 자연히 분리된다. 지금은 렌더 버그 하나가 **몰 장애처럼 보이는 문구**를 띄우고 칩은 옛 숫자를 단 채 남아, 이용자는 필터가 살아 있다고 믿는다.
 - `refresh` 가 돌려주는 promise 에는 **계약이 없다** — 성공·경합으로 버림·오류 셋 다 `undefined` 로 resolve 되고 절대 reject 하지 않는다. 체이닝하는 호출자가 셋을 구분할 수 없다.
    수치: 654행(경합 버림)·678행(오류 처리 후 catch 종료) 둘 다 값 없이 끝나고, 653행 `return query.then(...)` 뒤에 `.catch` 가 붙어 있어 rejection 이 밖으로 안 나간다. 반환값을 실제로 체이닝하는 호출자 3곳 — 1393행 `return refresh()`(selectSido), 1427행 `return re
    의미: 착지 시나리오(챗 이동 카드·`?spot=` 딥링크)가 **실패한 refresh 위에서도 성공한 것처럼 이어진다**. 지금은 applyNavFilter 가 어차피 자기 refresh 를 한 번 더 태워 스스로 낫지만, 저장소로 옮기며 액션을 async 로 만들 때 이 계약 공백을 그대로 복사하면 '조용히 아무 일도 안 일어남'이 남는다.
 - scope 표가 **정확히 맞는 자리도 확인했다** — 집계(facets)를 filter/page 에서 건너뛰는 판단은 옳다. 칩 카운트가 `baseParams`(지역+디지털)만으로 계산되고 cat·brand·mtype·q 가 거기 안 들어가기 때문이다.
    수치: baseParams(367–372행) = regionParams + digital. fullParams(373–386행) = baseParams + cat + brand + mtype + q + sort. 즉 "filter" 가 건드리는 5개 축(cat·brand·mtype·q + page)은 전부 base 밖 → 집계 불변이 **성립**한다. 반대로 di
    의미: 해체할 때 **버릴 것과 지킬 것을 갈라야 한다.** 이 base/full 경계는 서버 facet 컨텍스트와 1:1로 맞물린 진짜 도메인 규칙이라 저장소에서도 그대로 보존해야 한다 — 파생값을 `baseKey`(지역·digital·bounds)와 `fullKey`(baseKey + cat·brand·mtype·q·sort)로 두 겹 키를 만들면 scope 
  주의: 이 갈래에서 세다가 틀리기 쉬운 것 다섯. ①**호출자 수** — `grep 'refresh(\"'` 는 13곳만 잡는다. 인자 없는 5곳(763·764·1380·1393·1506)이 전부 기본값 \"all\" 이라, 이걸 빼면 가장 비싼 4콜 경로를 통째로 놓친다. 반드시 `grep 'refresh('` 로 세고 정의부(649)와 주석 속 언급(1004)을 빼라 → 18곳. ②**scope

### merchants.html `state` 재검증 — 11필드·읽기 106·쓰기 66 확인, 읽기 소비처 분류 8건 중 6개 수치 정정
 - [재확인함] `state` 는 11개 필드의 평평한 객체 하나다. 중첩 없음, 클래스 없음, 270~274행 5줄에 전부 들어간다.
    수치: 재확인함. 270행 `var state = {`, 271행 `sido/si/gu/dong`, 272행 `cat/brand/mtype`, 273행 `digitalOnly/q/sort/page`, 274행 `};` — 5줄·11필드 실물 대조 일치. 전부 스칼라(문자열 8·불리언 1·정수 1 + sort 문자열)이고 객체·배열 필드 0개도 확인. `multiH
    의미: 떼어낼 단위가 11개뿐이고 전부 스칼라라 게터 11개면 전부 덮인다. 참조 공유 위험이 원리적으로 없어, merchants-*.js 분리에서 `mapObj` 를 게터로 넘겨야 했던 것과 성격이 다르다.
 - [재확인함] 읽기 106 · 쓰기 66. 필드별 편차가 크고, 읽기/쓰기 비율이 뒤집힌 필드는 page 하나뿐이다.
    수치: 재확인함 — 11필드 전부 한 건도 안 틀렸다. 주석(줄·블록·HTML)을 공백 치환한 사본에 `/state\.(\w+)/g` 전역 매칭, 뒤에 `=`(단 `==`/`=>` 제외)면 쓰기. 실측: sido 18R/2W · gu 17R/6W · brand 14R/6W · si 13R/5W · cat 10R/6W · dong 9R/7W · mtype 6R/4W
    의미: sido 는 18번 읽고 2번만 쓴다 — 1527행 `SAVED.attach({ getRegion: function () { return state.sido; } })` 가 이미 그 형태로 내보내고 있음을 실물 확인했다. 반대로 page 는 21W/5R 로 어떤 모듈로도 뗄 수 없는 순수 허브 값이다.
 - [재확인함] 쓰기 주체는 사용자 조작 42 · 외부 입력 15 · 내부 로직 1 · 겸용 8. 내부 로직 단독 쓰기는 딱 한 곳뿐이다.
    수치: 재확인함 — 66건 전부 줄 단위로 귀속 대조했고 분류·건수가 정확하다. **사용자 42**: 757(si,gu,dong,page=4)·758(gu,dong,page=3)·759(dong,page=2)=9 / 775(cat,page)=2 / 809(brand,page)=2 / pickBrand 864·866·867=3 / toggleListByMap 964
    의미: 쓰기 창구가 사실상 전부 DOM 이벤트 핸들러이고 `bindControls`(1448~1495)·`renderRegionSelects`(757-759)·`renderChips`(775·809)에 이미 모여 있다 — state 를 통째로 올리지 않고 쓰기 창구에 setter 콜백을 꽂는 방향이 열려 있다. 외부 입력 2경로(applyNavFilter·onnu
 - [재확인함] 지역 3계층은 하향 캐스케이드로 초기화되고, 시도만은 업종·브랜드·시장유형까지 함께 지운다 — 지역 계층 밖으로 번지는 유일한 리셋이다.
    수치: 재확인함. 실물 대조 — 757 `state.si = e.target.value; state.gu = "전체"; state.dong = "전체"; state.page = 1` (설정 1 + 리셋 3) · 758 `state.gu = …; state.dong = "전체"; state.page = 1` (리셋 2) · 759 `state.dong = …; st
    의미: '시도를 바꾸면 필터가 다 풀린다'가 코드에 있는 계약인데 화면 어디에도 안 적혀 있다. 지역 로직을 모듈로 떼면 이 함수가 cat·brand·mtype 까지 만져야 해 지역 모듈이 필터 모듈을 알아야 하는 결합이 생긴다 — `selectSido` 를 '지역 설정'과 '필터 초기화' 두 콜백으로 갈라야 한다.
 - [정정] page 쓰기 21건 중 17건이 리셋, 4건이 실이동인 것은 맞다. 그러나 '필드를 쓰면서 같은 함수에서 page=1 을 안 하는 곳이 하나'는 **틀렸다 — 둘이다**(1440-1441 챗 훅 + 1564 boot).
    수치: **리셋 17 / 실이동 4 = 21 은 재확인함.** 리셋 17행 실물 확인: 757,758,759,775,809,867,964,1009,1387,1427,1452,1472,1476,1478,1479,1496,1500. 실이동 4건은 wirePager 1322(first)·1323(prev)·1324(next)·1325(last). **정정**: 원 보고
    의미: page 는 나머지 10필드의 종속 변수다. 창구를 하나로 모으면 17곳이 1곳이 된다. 정정이 중요한 이유 — 예외를 '하나'로 적으면 다음 사람이 boot 경로를 계약 밖으로 보고, 착지(NAV_FILTER.region)에서 page 를 손대는 변경을 넣을 때 selectSido 위임을 끊어도 안전하다고 오판한다.
 - [재확인함] `q`·`digitalOnly`·`sort` 는 state 와 DOM 이 양방향 동기화돼야 하는 필드다. q 는 쓰기 5건 중 4건이 입력창 value 를 함께 만진다.
    수치: 재확인함. q 쓰기 5건 전수 — 1426 `state.q = p.q; … qi.value = state.q`(state→DOM, 같은 줄에 1W+1R) · 1441 `state.q = ""` + 1442 `qi.value = ""` · 1452 input 200ms debounce(DOM→state, **유일하게 DOM 을 안 만지는 1건**) · 149
    의미: 칩·셀렉트 계열은 state 가 단일 진실이라 그냥 뗄 수 있지만, 이 3필드는 DOM 이 두 번째 사본이라 모듈로 옮기면 동기화 지점이 경계를 넘는다. resetAll 을 뗄 때 1501행을 빠뜨리면 '검색어는 지워졌는데 입력창에 글자가 남는' 상태가 되고 에러는 안 난다.
 - [부분 정정] `resetAll`(9필드)과 `selectSido`(8필드)의 리셋 범위가 어긋나는 것은 맞다. 다만 LIST_BY_MAP 누락 전력의 출처가 틀렸다 — CLAUDE.md 이력이 아니라 **코드 주석**에만 있다.
    수치: **필드 집합은 재확인함.** resetAll(1497-1502) 쓰기 9 = si,gu,dong(1498) + cat,brand,mtype(1499) + digitalOnly,q,page(1500) — **sido·sort 제외**. selectSido(1384-1387) 쓰기 8 = sido,si,gu,dong(1386) + cat,brand,mtype
    의미: 두 리셋이 서로 다른 필드 집합을 갖는다는 사실이 코드 어디에도 안 적혀 있어 필드 추가 시 한 곳만 고치기 쉽다. 출처 정정이 중요한 이유 — 이 함정의 유일한 기록이 **그 함수 안 주석**이라, resetAll 을 모듈로 떼면서 주석을 안 가져가면 경고가 통째로 사라진다(CLAUDE.md 를 봐도 안 나온다).
 - [정정] 읽기가 4개 소비처로 갈려 있고 API 조립과 JSON 폴백이 같은 필터를 두 벌로 구현한다는 **결론은 맞다**. 그러나 소비처별 건수가 6개 중 5개 틀렸고, 617-619행을 JSON 폴백으로 분류한 것이 오분류다.
    수치: 실측 재집계(합계는 106 으로 동일). **①API 파라미터 조립 24**(원보고 22) = regionParams·baseParams·fullParams 353-380 **18** + loadRegionOptions **API 분기** 617(1)·618(2)·619(2)=**5** + apiQuery 페이징 570(1). **②JSON 폴백 26**(
    의미: 필터 의미론이 두 벌이라 한쪽만 고치면 API 모드와 JSON 폴백이 다른 답을 낸다. 정정으로 드러난 것 — 이중 구현 지점이 원 보고가 본 **필터 한 쌍이 아니라 지역 옵션 조회까지 두 종류**다. state 를 떼기 전에 이 이중 구현부터 모으는 것이 선행 과제이고, 그 범위가 원 추정보다 넓다. 반대로 ⑤ 모듈 게터 3건은 이미 올바른 형태라 그대
  주의: 세는 방법에서 틀리기 쉬운 것 — 원 보고의 5가지는 **전부 실측으로 옳음이 확인됐고**, 이번 재검증에서 4가지가 추가로 드러났다.

【확인된 5가지】①`grep -c` 로 세면 안 된다 — 줄 수를 세므로 `if (state.brand !== "전체") p.brand = state.brand;` 같은 관용구에서 절반을 잃는다. **실측 재확인**: gu 줄17/출현23(−6) · bra

### merchants.html SNAP 재검증 — 필드 13개(리터럴 12+런타임 1) · 쓰기 함수 2개/14회 · 읽기 함수 8개/21회 [원본의 "7개/12회"를 정정]
 - SNAP 의 필드는 13종이다 — 리터럴 선언 12개 + 런타임에만 생기는 1개(mapClusterRows). **재확인함.**
    수치: 재확인함. `awk 'NR>=277&&NR<=282'` 로 리터럴 키를 세니 12: list,total,pages / facetCat,catTotal,facetBrand,facetMtype / mapPins,mapTotal,mapTruncated / regionTotal,sidoTotal. `grep -o "SNAP\.[A-Za-z]*" merchants.
    의미: 리터럴만 보고 필드 목록을 만들면 mapClusterRows 를 빠뜨린다. 그 필드는 초기값 계약이 혼자 다르다 — 첫 지도 회차 전에는 `undefined`, API 모드에서는 매 지도 회차마다 `null` 로 덮인다(669행 `R.mapClusterRows || null`, apiQuery 는 이 필드를 만들지 않는다). 읽는 쪽(1070 `|| []`
 - 쓰기는 함수 딱 2개에서만 일어난다 — refresh 13회, selectSido 1회. **재확인함**(단 selectSido 의 끝 행이 1394가 아니라 1395).
    수치: 재확인함. `grep -no "SNAP\.[A-Za-z]* *=[^=]"` = **14**, 줄번호 전부 일치: refresh 13회(655 list, 656 total, 657 pages, 660 facetCat, 661 catTotal, 662 facetBrand, 663 facetMtype, 667 mapPins, 668 mapTotal·mapTrun
    의미: 쓰기 창구가 사실상 하나라 SNAP 을 모듈로 떼기가 예상보다 쉽다 — `applySnapshot(R)` setter 하나면 13개를 덮는다. 남는 예외는 selectSido 의 sidoTotal 단 1회이고, 그 1회가 곧 '쓰기 함수가 2개'인 유일한 이유다(F7 참조 — 그 필드를 SNAP 밖으로 빼면 쓰기 함수가 1개가 된다).
 - **정정 — 읽는 함수는 7개가 아니라 8개**이고 21회다. 표·집계 쪽은 12회가 아니라 **14회**, 지도 쪽 6회다. 서로의 필드를 한 번도 넘보지 않는다는 성질 자체는 실측으로 확인됐다.
    수치: 읽기 21회 = 등장 36 − 쓰기 14 − 주석 1(1293) ✓. 함수별 횟수는 원본이 전부 맞다 — render 8(877 total, 878 sidoTotal, 879 regionTotal+total, 882 total, 884 pages, 885 list, 926 total) / renderMap 3(1110 mapTruncated, 1111 ma
    의미: 틀린 것은 개수뿐이고 **절단면 판단은 그대로 선다** — 지도 3함수(renderMap·renderClusters·viewportRender, 6회)는 map* 4필드만 읽고, 표·집계 4함수(render·catCount·renderChips·wireRowMap, 14회)는 map* 를 한 번도 안 읽는다(실측 전수 확인). `SNAP.map*` 4개를 
 - '서버 응답 스냅샷'이라는 주석은 절반만 맞다 — 13필드 중 서버 값 그대로는 6개뿐이다. **분류는 맞고, apiQuery 쪽 행 번호가 전부 어긋나 있었다.**
    수치: 분류 재확인함(6/5/1/1). **정정 — apiQuery 행 번호가 원본에서 1~4행씩 밀려 있었다.** 실제: list←list.items **575**(원본 571), total←list.total **575**(571), facetCat 배열→객체 **577**(578), catTotal 합 **578**(579), facetBrand←fac.br
    의미: 캐시 무효화를 '서버 응답이 왔는가'로 설계하면 sidoTotal 과 mapClusterRows 두 필드가 그 규칙 밖으로 샌다. 변환이 apiQuery(577·578·579)와 refresh(657·663·667) 두 층에 흩어져 있는 것도 함께 봐야 한다 — 한 층만 옮기면 모양이 반쯤 바뀐 값이 SNAP 에 들어간다.
 - scope 4종의 갱신 범위는 refresh 가 아니라 apiQuery/jsonQuery 가 정하고, refresh 는 'R 에 그 필드가 있는가'로만 판단한다. **매트릭스·호출부 18곳 전부 재확인함**(jsonQuery 게이트 행 번호만 정정).
    수치: 매트릭스 재확인함 — all: list·total·pages ✓ / facet4 ✓ / map4 ✓ / regionTotal ✓ (API 4콜). filter: 리스트3 ✓ / facet4 ✗ / map4 ✓ / regionTotal ✗ (2콜). facet: 리스트3 ✓ / facet4 ✓ / map4 ✗ / regionTotal ✗ (2콜). page:
    의미: scope→요청→필드가 세 곳으로 흩어져 있다(apiQuery·jsonQuery·refresh). 셋 중 하나만 옮기면 '요청은 나가는데 SNAP 에 안 담기는' 또는 그 반대의 조용한 결함이 생긴다. 특히 `undefined` 판정이라 서버가 나중에 빈 객체 `{}` 를 주기 시작하면 게이트가 반대로 열린다.
 - regionTotal 은 scope="all" 에서만 갱신되는데, 지도범위 모드의 지도 이동은 "facet" 이라 total 과 짝이 어긋난다. **재확인함**(regionParams 행 범위만 정정).
    수치: 재확인함. regionTotal 갱신 경로는 18개 호출 중 all 8곳뿐이고, LIST_BY_MAP 진입 자체는 965행 `refresh("all")` 이라 그때 한 번 맞춰진다. 이후 지도 이동은 **1009**행 450ms 디바운스 `refresh("facet")` → apiQuery 567 `wantRegionTotal=(scope==="all")`
    의미: 축소하면 새 bounds 의 total 이 옛 bounds 의 regionTotal 을 넘어 'N곳 중 M곳' 에서 M>N 이 나올 수 있다(LIST_BY_MAP 은 API 모드 전용이고 bounds 가 지역을 **대체**하므로 regionTotal 도 bounds 기준값이다 — 그래서 둘이 같은 축인 척 보인다). 분해 설계에서 regionTotal 을 
 - sidoTotal 은 SNAP 이 소유한 값이 아니라 SIDO_TOTAL 캐시의 사본이고, SNAP 쓰기 함수가 2개인 유일한 원인이다. **재확인함**(SIDO_TOTAL 선언 행만 정정).
    수치: 재확인함. `grep -no "SNAP\.sidoTotal"` = **4회** = 672(좌변 쓰기)·672(우변 읽기)·878(읽기)·1391(쓰기). R 경유 0회 — 값의 출처는 100% `SIDO_TOTAL[state.sido]`. **정정 — 선언 행**: SIDO_TOTAL 은 **284**행이다(원본 285는 그 다음 줄 `var BRAND_R
    의미: 같은 수를 캐시 두 개가 들고 있다. 이 필드를 SNAP 에서 빼고 render 가 `SIDO_TOTAL[state.sido]` 를 직접 읽게 하면 ①필드 13→12 ②쓰기 함수 2→1(refresh 단독) ③672행의 자기폴백 삼항이 사라진다(그 삼항이 곧 '읽기 21회'의 21번째다 — 없어지면 읽기도 20회로 정리된다). 분해 시 가장 값싼 정리 대상
 - pages 와 catTotal 은 상태가 아니라 계산식이다 — 저장하지 않아도 되는 파생값 2개. **재확인함**(catTotal 의 JSON 모드 출처 행만 정정).
    수치: 재확인함. `grep -no "SNAP\.pages"` = 2회(쓰기 657 `Math.max(1, Math.ceil(R.total / PAGE_SIZE))` · 읽기 884) — total 과 PAGE_SIZE(262행, 50)만 있으면 언제든 재계산. `grep -no "SNAP\.catTotal"` = 2회(쓰기 661 · 읽기 681 '전체' 칩).
    의미: 13필드 중 2개를 게터로 바꾸면 필드가 11개로 줄고, total↔pages / facetCat↔catTotal 이 어긋날 여지가 원천 제거된다. 둘 다 읽는 곳이 1곳뿐이라 치환 비용도 최소다.
 - JSON 모드에서 list·mapPins·mapClusterRows 는 같은 행 객체를 공유한다 — SNAP 은 불변 스냅샷이 아니다. **재확인함.**
    수치: 재확인함. jsonQuery 가 `full` 하나를 셋에 나눠 준다: **541** `R.list = full.slice(start, start+50)`(새 배열·같은 원소), **555** `R.mapPins = R.mapTruncated ? [] : full`(비절단 시 **full 그 자체**), **556** `R.mapClusterRows = fu
    의미: 배열 교체와 원소 수정의 파급이 비대칭이다 — 배열을 갈아 끼우면 셋이 독립이지만, 행 하나를 고치면 표·지도·클러스터가 동시에 바뀐다. 떼어낼 때 이 비대칭을 계약으로 적지 않으면 다음 사람이 '어차피 복사본'으로 읽고 행을 직접 손댄다. mapTruncated 일 때 mapPins 는 비고 mapClusterRows 만 남는 것도 함께 봐야 한다 — 그
 - SNAP 은 IIFE 밖으로 한 번도 나가지 않지만, refresh 밖에서도 읽힌다 — 서버 호출 없는 재렌더 경로가 3개다. **재확인함.**
    수치: 재확인함. 외부 노출 0: 외부 모듈에서 `SNAP` 문자열 **6회**가 잡히지만 **전부 주석**이다(merchants-split.js:5 / merchants-colresize.js:6 / merchants-saved.js:10 / merchants-brandmodal.js:6 / merchants-infowindow.js:7·128 / merchan
    의미: 캡슐화는 이미 지켜져 있어 모듈 경계는 걱정할 게 없다. 대신 '서버 응답이 올 때만 읽힌다'는 전제가 틀렸다 — 즐겨찾기 토글·컬럼 폭 리셋·지도 idle 이 SNAP 을 렌더의 단독 원천으로 쓴다. SNAP 을 떼면 그 세 경로가 즉시 소비자가 되므로, 응답 버퍼가 아니라 '렌더 데이터 원천'으로 설계해야 한다.
 - 캐시인가 파생인가의 답은 필드가 아니라 MODE 로 갈린다 — JSON 모드에선 13필드 전부 파생, API 모드에선 6필드가 서버만 아는 값. **재확인함**(resolveMode 행 범위만 정정).
    수치: 재확인함. MODE="json": jsonQuery(537-560)가 서버에 아무것도 묻지 않고 jsonLoadSido 로 받아 둔 배열에서 13필드를 전부 계산한다(sidoTotal 도 590행 `jsonLoadSido(sido).length` 로 파생) → 100% 재계산 가능. MODE="api": 클라이언트에 전체 행이 없으므로 list·total·
    의미: 이것이 이 허브를 떼기 어려운 진짜 이유다. 순수 파생 스토어(selector)로 리팩터링하면 API 모드가 깨지고, 순수 응답 캐시로 다루면 JSON 모드에 불필요한 사본이 남는다. 경계는 필드가 아니라 모드에 그어져 있으므로, 분해 설계는 '두 모드가 같은 SNAP 모양을 채운다'는 현재의 정규화 계약을 명시해 지켜야 한다 — 그 계약이 깨지면 폴백 대
  주의: 이 갈래에서 세다가 틀리기 쉬운 것 8가지(원본 6가지 중 ②는 수가 틀려 정정, ⑦⑧ 신설). ①**리터럴만 세면 12로 틀린다** — mapClusterRows 는 669행 대입으로만 생긴다(정답 13). ②**정정: 한 줄에 `SNAP.필드` 가 둘인 줄은 4개가 아니라 6개다** — 668(mapTotal+mapTruncated) · **672(sidoTotal×2 — 원본이 이 줄을

### merchants.html refresh(scope) 재검증 — 계약·경합·팬아웃 판단은 유효, 행 번호·개수 12건 정정
 - scope 는 4종이 아니라 **불리언 3개**로 갈린다 — `wantFacets`·`wantMap`·`wantRegionTotal`. 문자열은 그 셋을 고르는 이름표일 뿐이고, **모르는 값은 조용히 "filter" 와 똑같이 동작**한다(enum 검증 없음).
    수치: **재확인함(판정 내용) / 행 번호 정정.** scope 값 4종 + 인자 없음(`scope = scope || "all"`, **650행** — 보고서 649행은 함수 선언부다). API 요청 수 all=4·filter=2·facet=2·page=1 재확인(570~573행의 4개 `apiGet` 슬롯을 직접 셈). **판정 3줄은 561–564 가 아니
    의미: 상태 저장소로 옮길 때 `scope` 문자열을 그대로 옮기면 안 된다 — 실제 계약은 **"어느 파생값을 무효화할 것인가" 3비트**다. 저장소에서는 이 3비트를 호출자가 선언하는 대신 **바뀐 축(region/digital → facets+regionTotal, cat/brand/mtype/q → list+map, page/sort → list)에서 유도
 - 호출자는 **18곳**이고 그중 **5곳이 인자 없이** 부른다. 다만 `grep 'refresh("'` 가 잡는 것은 13곳이 아니라 **14줄**이다 — 그중 하나(1004행)가 주석이다.
    수치: **개수·귀속 전부 재확인함 / 그레프 수치 1건 정정.** 원본에서 `grep -c 'refresh('` = **20줄**(18 호출자 + 정의 649 + 주석 1004). 주석 제거 사본에서는 **19줄**(18 + 정의). `grep 'refresh("'` 은 원본에서 **14줄**(보고서가 말한 13이 아니다 — 1004행 주석 `종전에는 refre
    의미: 저장소 액션 목록이 곧 이 호출자 목록이다. **인자 없는 5곳 중 4곳이 '지역 축이 바뀌었다'**(763·764·1393·1506)이고 **1380만 '아무것도 안 바뀌었는데 전량 재조회'**다 — 의미가 다른 둘이 같은 이름으로 뭉쳐 있다. 재시도가 가장 비싼 경로인 것은 뒤집혀 있다.
 - 경쟁 조건은 `refreshSeq` **하나로만** 막고, 늦게 온 응답은 **SNAP 을 쓰기 전 한 줄에서 통째로 버린다**. 요청 자체는 취소하지 않는다(AbortController 없음).
    수치: **재확인함 / viewportSeq·clusterSeq 선언·사용 행 정정.** 버리는 자리 2곳 재확인 — 654행 `if (seq !== refreshSeq) return;`(SNAP 첫 쓰기 655행보다 앞) · 678행 `if (seq === refreshSeq) renderResultError();`. 654 이후 675까지 전부 동기 재확인. 
    의미: 버려진 응답도 **네트워크는 이미 다 썼다** — 검색어를 빠르게 치면 200ms 디바운스를 통과한 회차마다 2콜이 나가고 마지막 것만 반영된다. 늦게 온 refresh 가 버려지면 **그 회차가 부를 예정이던 renderMap 도 함께 사라져** 지도만 옛 필터로 남는 창이 생긴다(지도는 자기 idle 로만 회복). 저장소로 옮기면 세 카운터를 하나의 '
 - 부수 효과는 **SNAP 부분 갱신 → 칩 재렌더 → 목록 재렌더 → (조건부) 지도 재렌더** 넷이고, scope 와 무관하게 **칩과 목록은 항상 통째로 다시 그린다**. 스크롤은 refresh 가 만지지 않는다(호출자 몫).
    수치: **구조는 재확인함 / 필드 수·칩 수·행 번호 정정.** SNAP 쓰기는 **8종이 아니라 13필드**(12줄 — 668행이 mapTotal·mapTruncated 2개): 655 list·656 total·657 pages(**항상**) / 660 facetCat·661 catTotal·662 facetBrand·663 facetMtype(`R.face
    의미: "무엇이 바뀌었나"와 "무엇을 다시 그리나"의 경계가 **673행 한 줄**에 뭉개져 있다. 저장소로 가르면 655–672(커밋)만 저장소가 갖고 673–675(팬아웃)는 구독자가 되어야 하며, 그 순간 **scope 별로 무엇을 다시 그릴지 고를 수 있게 된다** — 지금은 page 넘김에도 칩 23개와 `#selMtype` option 을 새 노드로 만
 - API/JSON 갈림은 **652행 한 줄**뿐 — 그 아래는 완전히 공통이다. 대신 **비용 구조가 정반대**다.
    수치: **재확인함 / 함수 경계 행 정정.** 652행 `var query = (MODE === "json") ? jsonQuery(scope) : apiQuery(scope);` ✓, 653행 이후 공통 ✓. 시도 파일 행 수를 실제로 세어 **전부 일치**: 서울 **29,728** · 경기 **30,021** · 부산 **12,720** · 인천 **7,3
    의미: 저장소로 옮길 때 **JSON 폴백은 '서버 왕복'이 아니라 '순수 함수 재계산'**이라는 점이 드러나야 한다 — 이쪽은 메모이제이션할 자리(`base` 는 지역·digital 이 안 바뀌면 불변)가 명확한데 지금은 scope 가 그걸 표현하지 못한다.
 - **결함 후보 ① (코드 경로로 확증)** — 지도 범위 모드에서 지도를 옮기면 `countText` 의 앞 숫자(regionTotal)가 **옛 뷰포트 값으로 남는다**. "N곳 중 M곳"에서 M > N 인 문장이 나올 수 있다.
    수치: **재확인함 — 근거가 보고서보다 강하다.** 경로: 965 toggleListByMap→refresh("all")→573행이 `assign(regionParams(), {size:1})` 로 조회하는데 **regionParams(336–358)가 349행에서 `LIST_BY_MAP && MODE==="api"` 면 지역 4축을 버리고 bounds 만 반환
    의미: scope 표를 손으로 관리해서 생긴 **표 드리프트의 실물**이다. 저장소로 옮기며 무효화를 '바뀐 축'에서 유도하면 이 부류가 구조적으로 사라진다 — bounds 가 baseParams(→regionParams)에 들어가는 순간 regionTotal 도 자동으로 무효가 되어야 한다.
 - **결함 후보 ② (확증)** — `wantMap` 이 받아 온 `/merchants/map` 페이로드가 **API 모드에서 14곳 중 12곳에서 버려진다**. 다만 그 콜은 순수 낭비가 아니라 **renderMap 을 켜는 트리거**이기도 하다(보고서가 빠뜨린 대가).
    수치: **재확인함 / 행 번호 4건 정정 + 단서 1건 추가.** 게이트는 **1108**행 `if (!consumeMapFit()) { viewportRender(); return; }`(보고서 1109). SNAP.map* 을 읽는 자리는 전부 그 아래 — **1110**(mapTruncated)·**1111**(mapTotal)·**1117**(mapPin
    의미: "무엇을 다시 받나"와 "무엇을 다시 그리나"가 이미 어긋나 있다는 증거다. scope 표는 2026-08-12 뷰포트 자동 지도가 들어오기 전 계약을 그대로 들고 있다. 저장소 설계에서 지도 데이터는 **refresh 의 산출물이 아니라 지도 자신의 질의**로 떼어 내되, 675행이 겸하던 트리거는 명시적 이벤트로 남겨야 한다.
 - **이것이 해체의 진짜 경계다** — 목록·칩은 커밋된 스냅샷(SNAP)을 읽지만 **지도는 API 모드에서 SNAP 을 전혀 읽지 않고 `state` 를 실시간으로 다시 읽는다**. 같은 필터에 대해 저장소가 둘이다.
    수치: **재확인함 / 독자 수 정정.** 주석 제거 사본에서 SNAP 참조를 전수로 뽑았다(29줄). 커밋(655–672) 밖 독자는 **17줄**: catCount 681(facetCat·catTotal — renderChips 가 774행에서 부른다) / renderChips 782·824 / render 877·878·879(x2)·882·884·885·9
    의미: 상태 저장소 리팩토링의 성패가 여기서 갈린다. **"무엇이 바뀌었나" = state 11필드 + LIST_BY_MAP + 지도 bounds. "무엇을 다시 그리나" = SNAP 13필드.** 지금 SNAP 은 목록·칩의 저장소일 뿐이고 지도는 저장소 밖에 있다. 지도를 구독자로 끌어들일지(그러면 bounds 도 상태가 된다), '라이브 질의 표면'으로 명시
 - 실패 경로가 **부분 상태를 남긴다** — 목록 자리만 오류로 갈아치우고 칩·지도·카운트 컨텍스트는 이전 값을 그대로 들고 있다. 게다가 **렌더 예외도 같은 catch 로 떨어져** 이용자에게는 네트워크 실패로 보인다.
    수치: **재확인함 / DOM 접점 1건 보정.** 676–679행 catch 하나가 ①질의 실패 ②`renderChips()` 예외 ③`render()` 예외를 받는다 — renderMap 만 675행에서 자체 try/catch(`console.warn("[map] 렌더 스킵:")`)로 분리 ✓. renderResultError(**1377–1381**)가 건드
    의미: 저장소로 가르면 '질의 실패'와 '렌더 실패'가 서로 다른 층의 사건이 되어 자연히 분리된다. 지금은 렌더 버그 하나가 **몰 장애처럼 보이는 문구**를 띄우고 칩은 옛 숫자를 단 채 남아, 이용자는 필터가 살아 있다고 믿는다.
 - `refresh` 가 돌려주는 promise 에는 **계약이 없다** — 성공·경합으로 버림·오류 셋 다 `undefined` 로 resolve 되고 절대 reject 하지 않는다. 체이닝하는 호출자가 셋을 구분할 수 없다.
    수치: **재확인함 + 파급 1건 추가.** 653행 `return query.then(...)` 뒤 676행 `.catch(...)` 가 붙어 rejection 이 밖으로 안 나간다 ✓. 세 종착점 모두 값 없음 — 654(경합 `return;`)·675(성공 마지막 문장이 `if (hasMap){...}`)·678(오류 처리 후 종료) ✓. 반환값 체이닝 호출
    의미: 착지 시나리오(챗 이동 카드·`?spot=` 딥링크)가 **실패한 refresh 위에서도 성공한 것처럼 이어진다**. 저장소로 옮기며 액션을 async 로 만들 때 이 계약 공백을 그대로 복사하면 '조용히 아무 일도 안 일어남'이 남는다.
 - scope 표가 **정확히 맞는 자리도 확인했다** — 집계(facets)를 filter/page 에서 건너뛰는 판단은 옳다. 칩 카운트가 `baseParams`(지역+디지털)만으로 계산되고 cat·brand·mtype·q 가 거기 안 들어가기 때문이다.
    수치: **재확인함 / 행 범위만 정정.** baseParams(**367–371**) = regionParams + `if (state.digitalOnly) p.digital = true`. fullParams(**373–382**) = baseParams + cat + brand + mtype + q + sort(+dist 시 uLat·uLng). 즉 "fi
    의미: 해체할 때 **버릴 것과 지킬 것을 갈라야 한다.** 이 base/full 경계는 서버 facet 컨텍스트와 1:1로 맞물린 진짜 도메인 규칙이라 저장소에서도 그대로 보존해야 한다 — 파생값을 `baseKey`(지역·digital·bounds)와 `fullKey`(baseKey + cat·brand·mtype·q·sort)로 두 겹 키를 만들면 scope 
  주의: 이 갈래에서 세다가 틀리기 쉬운 것 일곱. ①**호출자 수** — `grep 'refresh('` 는 원본에서 20줄이고 여기서 정의부(649)와 주석(1004)을 빼야 18곳이다. `grep 'refresh(\"'` 는 원본 **14줄**(주석 1004 포함, 코드 13)이라 **양방향으로 틀린다** — 암묵 all 5곳(763·764·1380·1393·1506)을 놓치면서 주석 1줄을 더

### merchants.html 렌더 함수 8종 — 허브(state·SNAP) 결합 실측과 과잉 재렌더 5건
 - 렌더 함수는 렌더 도중 state 를 바꾸지 않는다 — 딱 한 곳, renderMap 만 예외다
    수치: 파일 전체 state 참조 173곳 / SNAP 참조 36곳. 렌더 함수 8종의 렌더 시점 state 쓰기 = 0곳. 유일한 예외 renderMap L1108 `consumeMapFit()` → MAP_FIT_NEXT=false (한 번 쓰는 플래그를 렌더가 소비), 같은 함수 L1113 `CLUSTER_MODE=false; clusterSeq++`. 세는
    의미: 'state 를 바꾸는 렌더 함수'는 구독으로 못 바꾼다 — 자기가 반응할 값을 자기가 고치면 루프가 된다. 실측 결과 그런 함수는 renderMap 하나뿐이고, 그것도 state 가 아니라 모듈 플래그(MAP_FIT_NEXT·CLUSTER_MODE·clusterSeq)다. 즉 나머지 7종은 순수 읽기라 구독 전환의 걸림돌이 아니다.
 - 렌더 함수의 state 쓰기는 전부 '렌더가 설치한 핸들러' 안에 있다 — 얽힘의 실체는 쓰기가 아니라 매 렌더마다 핸들러를 새로 만드는 것이다
    수치: renderChips: 렌더 시점 읽기 state.cat×2·brand×3·mtype×1·sido×2 / 쓰기는 L775(onclick: cat,page)·L809(onclick: brand,page) 뿐. renderRegionSelects: 읽기 si×2·gu×2·dong×1 / 쓰기는 L757-759 onchange 3개(si,gu,dong,page)
    의미: 핸들러와 마크업이 한 함수 안에 붙어 있어 '내용만 갱신'이 불가능하다. 지금 구조에서 active 클래스 하나를 바꾸려면 버튼을 부수고 다시 만들 수밖에 없고, 그래서 아래 (과잉 재렌더) 문제가 구조적으로 따라온다.
 - 함수별 읽는 필드 표 — SNAP 은 세 덩어리(list계·facet계·map계)로 이미 갈라져 있고, 각 렌더 함수는 그중 하나만 읽는다
    수치: renderMeta: state 0·SNAP 0 (META 5회만). renderSidoTabs: state.sido, SIDO_TOTAL, loadingSido. renderRegionSelects: state.{sido(isGG),si,gu,dong} + REGIONS.{si,gu,dong}. renderChips: SNAP.{facetCat,catTo
    의미: 덩어리가 겹치지 않는다 — renderChips 는 SNAP.list 를 한 번도 안 읽고 render 는 facet 을 안 읽는다. 이 분리는 이미 데이터에 있는데 호출 쪽에서만 뭉쳐져 있다. 즉 구독 전환의 비용은 SNAP 재설계가 아니라 refresh 의 호출부 3줄이다.
 - drawPins·renderMeta 는 이미 순수하다 — 떼어낼 때 주입이 거의 필요 없다
    수치: drawPins(L1123-1161): state 0·SNAP 0, 바깥 참조는 mapObj×2·mapMarkers×2·clusterObj×1·SKIP_IDLE_ONCE×1 뿐(전부 지도 계층). renderMeta(L699-715): META 5회, state·SNAP 0회.
    의미: 해체를 어디서 시작할지의 답이다 — 이 둘은 지금 당장 인자·주입만으로 떨어지고, 반대로 render(SNAP 5필드+state 3필드+모듈 4종)와 viewportRender(fullParams 경유 state 8필드)는 허브를 통째로 들고 있다.
 - 호출 관계 — refresh 가 모든 것의 목이다. renderChips·render 는 scope 와 무관하게 무조건 함께 불린다
    수치: refresh 호출부 19곳(L763,764,775,809,868,965,1004,1009,1326,1380,1393,1427,1452,1472,1476,1478,1479,1496,1506). refresh 안에서 L673 `renderChips(); render();` 는 조건 없음, L675 renderMap 만 `hasMap` 조건. render 의 
    의미: scope 는 **네트워크 호출만** 갈라 놓고(apiQuery L562-586 의 wantFacets/wantMap/wantRegionTotal) 렌더는 안 가른다. 이득의 출처가 여기다 — 갈래는 이미 계산돼 있는데 렌더가 그 갈래를 안 쓴다.
 - 【과잉 ①】 페이지 넘김·정렬 변경(scope="page")에서 칩 3종이 바이트 단위로 동일한데 전량 재생성된다
    수치: 실측(Chrome·API 모드·서울): 페이지 넘김 1회 → catChips 552B·brandChips 1,032B·selMtype 288B **전부 바이트 동일**, 그런데 DOM 교체 26회(catChips add×12·brandChips add×13·selMtype add×1), createElement button×23. 정렬 변경도 같은 수치. 
    의미: 구조적으로 변할 수 없는 것을 매번 다시 만든다. 구독으로 바꾸면 `SNAP.facet*` 이 안 바뀐 회차엔 renderChips 가 아예 안 돌아 버튼 23개 생성이 0이 된다 — 페이지를 넘길 때마다 확실히 사라지는 비용이고, 추정이 아니라 실측한 23개다.
 - 【과잉 ②】 업종 칩 클릭(scope="filter")에서 실제로 바뀌는 것은 클래스 두 곳인데 칩 23개를 통째로 다시 만든다
    수치: 실측: 업종 칩 1개 클릭 → catChips 552B→552B 로 길이 같고 **다른 문자 51자**(= `chip` ↔ `chip active` 가 한 칩에서 다른 칩으로 이동), brandChips 1,032B·selMtype 288B 는 **바이트 동일**. 그런데 button×23 재생성, DOM 교체 25회. 브랜드 칩이 안 바뀌는 이유는 집계 
    의미: 필요한 일은 `classList.toggle('active')` 2회다. 지금은 그 2회를 위해 브랜드 칩 12개와 selMtype 8옵션까지 재생성한다 — 이 자리는 구독이 아니라 '카운트 렌더'와 '선택표시 렌더'를 가르기만 해도 해결된다. renderChips 가 읽는 필드가 SNAP.facet*(카운트)와 state.cat/brand/mtype(선택
 - 【과잉 ③】 저장 모달에서 즐겨찾기 하나를 해제하면 표 50행 전체가 다시 그려지고 리스너 165개가 재부착된다
    수치: 실측: 저장 모달 ★ 해제 1회 → resultArea add×1(표 통째 교체), addEventListener 165회(TR.row-link click 50 + keydown 50 + BUTTON.fav-btn click 50 + col-grip 12 + 기타 3). 실제 화면 변화는 그 행의 ★→☆ 한 글자. 반대로 **표에서 직접 ☆를 누르면** 재
    의미: 같은 상태 변화에 대해 한 경로는 국소 갱신, 다른 경로는 전면 재렌더다. 구독으로 바꿀 때 이 자리가 첫 수혜자다 — '즐겨찾기 집합'은 SNAP 도 state 도 아닌 제3의 상태이고, 표는 그 집합의 변화에만 반응하면 되므로 행 하나의 버튼만 갱신하면 된다.
 - 【과잉 ④】 부팅 때 4버튼짜리 시도 탭을 7번 통째로 다시 만든다
    수치: 실측(API 모드): 부팅 중 sidoTabs add×28 / del×6 → 렌더 7회(4버튼×7=28). 관측된 **내용 단계는 5개**이고 단계 사이에 바뀌는 것은 배지 텍스트 하나씩 — []→[서울 불러오는중…]→[서울 29,803·인천 7,356]→[+부산 12,759]→[+경기 30,193]. 호출부 7곳 = boot L1545(1) + 시도별 프
    의미: 배지 하나가 채워질 때마다 버튼 4개를 부수고 다시 만든다. 구독이면 `SIDO_TOTAL[key]` 변화가 그 탭의 `.tab-sub` 텍스트 하나만 건드린다. 28개 생성 → 4개 + 텍스트 갱신 7회.
 - 【과잉 ⑤】 시도 탭 클릭과 구 셀렉트 변경에서 내용이 안 바뀌는 블록이 함께 재생성된다
    수치: 시도 탭 클릭(서울→부산) 실측: renderMeta 재실행되나 noticeMain 291B·footLimit 301B **내용 동일**(META 는 부팅 후 불변인데 selectSido L1392 가 매번 부른다). 구 셀렉트 변경 실측: renderRegionSelects 가 regionSelects 를 통째로 교체(864B→952B) — selDong
    의미: renderMeta 는 state·SNAP 을 하나도 안 읽는(META 전용) 함수라 구독 전환이 가장 쉽고, 전환하면 시도 클릭당 592B 재생성이 0이 된다. renderRegionSelects 는 셀렉트 3개가 각자 다른 필드를 읽으므로(si←REGIONS.si, gu←REGIONS.gu, dong←REGIONS.dong+dongEnabled) 셋으로
 - 구독으로 바꿀 때 각 함수가 반응해야 할 필드 — 여덟 중 다섯은 단일 출처에만 매인다
    수치: renderMeta ← META.{collected,staleSince,staleReason} (state·SNAP 0). renderSidoTabs ← SIDO_TOTAL, state.sido, loadingSido (3). renderRegionSelects ← REGIONS.{si,gu,dong} + state.{si,gu,dong,sido} (7, 
    의미: 질문의 'renderChips 는 SNAP.facetCat 만 보면 되는가'에 대한 답은 **아니오, 두 축이다** — 카운트(SNAP.facet*)와 선택표시(state.cat/brand/mtype)가 서로 다른 주기로 바뀐다. 이 둘을 한 구독으로 묶으면 과잉 ①(page)은 사라지지만 과잉 ②(filter)는 그대로 남는다.
 - 매 렌더가 붙이는 리스너 총량이 크고, 그 대부분이 표 rewire 다
    수치: refresh 1회당 addEventListener 실측 162~225회. 고정 성분은 항상 150회 = 행 click 50 + 행 keydown 50 + 별 click 50(PAGE_SIZE=50, 실측 rows 50·favBtns 50). 여기에 col-grip 12(grips 4×3종: pointerdown·dblclick·click)와 지도 마커 리
    의미: 표는 SNAP.list 가 바뀔 때만 다시 그리면 되는데, 지금은 SAVED.onChange·COLR.onReset·모든 refresh 가 같은 문을 통과한다. 150개 리스너 재부착은 '표를 다시 그렸다'의 정확한 가격표이고, 과잉 ③(즐겨찾기 해제)에서는 그 150개가 전부 헛일이다.
  주의: 이 갈래에서 세다가 틀리기 쉬운 것 다섯. ①**모드를 확인하지 않으면 부팅 렌더를 절반으로 센다** — localhost 에서 그냥 열면 API_BASE 가 localhost:8080 이라 JSON 폴백으로 돌고(확인법: `#boundsBtn`.hidden 이 true), 그러면 시도 배지 프리페치(L1562, API 모드 한정)가 없어 renderSidoTabs 가 7회가 아니라 3회로 

