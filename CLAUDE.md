# digital_onnuri

디지털온누리상품권 사용처 가이드 페이지(`index.html`) 리포지토리.

## 하네스: 온누리 가이드 제작·갱신

**목표:** 날짜에 종속된 상품권 정책·사용처 정보를 정확하게 유지하며 가이드 페이지를 갱신한다. 이용자가 계산대에서 결제 실패를 겪지 않는 것이 품질 기준이다.

**트리거:** 가이드 제작·갱신·최신화, 사용처 목록 변경, 정책 반영, 문구·페이지 수정, 배포 전 검수 요청 시 `onnuri-guide-orchestrator` 스킬을 사용하라. 개별 사용처 단건 질문("우리 동네 CU 되나")은 스킬 없이 직접 답해도 된다. 커밋·푸시 요청("커밋푸시하자" 등)은 `doc-commit` 스킬로 — 문서(변경이력·명세) 갱신 후 안전 점검을 거쳐 커밋한다.

**핵심 제약:**
- 목록의 단일 진실 공급원은 `data/*.json`이다. 페이지에 숫자를 손으로 쓰지 않는다.
- 페이지의 "○○ 기준" 스탬프는 `min(collected_on)`에서 계산한다. 확인하지 않은 항목의 날짜를 올리지 않는다.
- `_workspace/`는 삭제하지 않는다. 다음 갱신의 델타 기준이다.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-06 | 초기 구성 (에이전트 5 + 스킬 6) | 전체 | - |
| 2026-08-06 | 브라우저 도구 deferred 로드 지시 추가 | guide-verifier, guide-verification | 드라이런에서 ToolSearch 없이 호출 시 실패 확인 |
| 2026-08-06 | 6개 스킬 description 경계 정리 | skills 전체 | 트리거 검증: 오케스트레이터가 개별 스킬 트리거를 흡수, 범위 밖 가드 부재 |
| 2026-08-06 | 팀 기동 여부 판단 단계 추가 | onnuri-guide-orchestrator | 단일 단계 작업에 5인 팀이 기동되는 비용 문제 |
| 2026-08-06 | 2차 트리거 수정 5건 (API 연동·배포 가드, 단발 질의 포착, 어휘 축 분리) | skills 전체 | 재검증: 오분류 8/11·오발동 6/7 해소 후 잔존분 |
| 2026-08-06 | 결제 구조 조사 절 추가 (카드형·모바일형 분리) | onnuri-policy-research | description이 본문보다 넓은 범위를 약속하던 불일치 해소 |
| 2026-08-06 | writer·data 모델 opus → fable | guide-content-writer, onnuri-data-curator | 사용자 지시 |
| 2026-08-06 | 수도권 가맹점 검색 기능 추가 (merchants.html + data/merchants/) | 전체 팀 | 사용자 요청. 출처: 공공데이터포털 전국 가맹점 현황(2025-07-31, 차기 2026-08-12). onnuri.gift/place는 인천·경기 미서비스·카테고리 부재로 기각. 갱신: build_merchants.py 재실행 |
| 2026-08-06 | SSM 단정 정정 (직영/가맹 축 분리) | 03 spec, offline JSON, index.html | 사용자 결함 제보: GS더프레시 가맹점 실존. 직영=불가 유지, 지정구역 내 개인 가맹점=가능 |
| 2026-08-06 | 배포: GitHub Pages Actions workflow 전환 | .github/workflows/pages.yml | legacy 빌더 즉사(duration 0), stuck deployment 취소 필요했음 |
| 2026-08-07 | 브랜드 매장 뷰 추가 (편의점/마트·슈퍼/SSM/다이소, 구·시별) | merchants.html, data/merchants/brand_stores.json | 사용자 요청. 출처: 온누리 가맹점찾기 비공식 API(2026-08-07 수집, 1,515건) — 공공데이터에 없는 도로명 주소·구/시 분류 확보. 갱신: build_brand_stores.py 재실행 |
| 2026-08-07 | 지도 링크 문구 목적지 일치 + 앱스토어 보조링크 | merchants.html, index.html | 조사 결과 앱 딥링크 부재 확정 → 웹 지도 링크 유지, 라벨에서 "앱" 제거해 목적지와 일치. 디지털온누리 앱스토어 설치 링크(검증됨) 보조 추가. 공식 지도 지역 제한 오해 서술 금지 |
| 2026-08-07 | 오프라인 탭 최상단에 검색 서브탭 2개 신설 | index.html | 진입 링크가 요건 박스에 묻혀 발견 어려움 → '가맹점 찾기'(merchants.html 내부·수도권)·'공식 지도 검색'(onnuri.gift/place 외부·전국) 서브탭으로 승격. 성격 배지·aria-label |
| 2026-08-08 | 시장·브랜드 뷰 통합 + 구/동 계층 필터 | merchants.html, data/merchants/*.json | 사용자 요청. 공공데이터(주소 없음)를 온누리 API 전수 수집본(66,211건, 도로명 주소·구/동)으로 교체. 2뷰→1뷰 통합, 지역 계층(서울·인천 구→동/경기 시→구→동), 업종·brand·시장유형 3축 필터. brand_stores.json 흡수. 갱신: build_region_full.py 재실행 |
| 2026-08-08 | 빈도+지점명 브랜드 자동탐지 + 브랜드 필터 | data/merchants/*.json, merchants.html | 사용자 요청. 상호 7회+ 중복 AND 지점명비율 40%+ → 브랜드(169종), 표기변형 병합. 충남상회류 오탐 제외. brand enum 4종→개별명. 상위칩+롱테일 검색. 후보 CSV(13_brand_candidates.csv) |
| 2026-08-08 | UX 대개편: 좌측 사이드바 + 화이트 모노톤 + 오렌지 | index.html, merchants.html | 사용자 요청. 크림/따뜻한 톤 제거→중립 모노톤, 오렌지 포인트 절제. 계층 사이드바(PC 상시/모바일 드로어), 모던 버튼(pill→6px). 디자인 시스템: 14_design_system.md. index는 build_index.py 재실행 |
| 2026-08-08 | 네이버 지도 통합 (가맹점 찾기, 지도+리스트 병행) | merchants.html, lib/MarkerClustering.js | 사용자 요청. 네이버 Maps JS SDK(Client ID는 도메인 제한 보호, Secret 미사용·코드에 없음). 마커 클러스터, 필터(지역계층·업종·브랜드) 연동, 성능 상한 3,000곳. 리스트↔지도 연동. 좌표는 데이터 lat/lng |
| 2026-08-09 | 백엔드 분리(Spring+Postgres) + 프론트 API 전환 + 브랜드 검색 팝업 + 현재위치 | backend/, merchants.html, config.js | 사용자 요청. Spring 검색 API(feat/backend-scaffold). config.js dataMode(auto/api/json)로 이중소스 — 백엔드 미기동 시 JSON 폴백. 라이브(main)는 dataMode=json. 브랜드 콤보→검색 팝업(부분검색+초성/알파벳 색인+업종 콤보 잠금). Geolocation 현재위치 마커. 배포자산: backend/DEPLOY.md·deploy/(Docker Compose+Caddy)·terraform |
| 2026-08-10 | index '가맹점 찾기' 카드 출처 문구 정정 | build_index.py, 03_content_spec.md, index.html | 사용자 결함 제보. 2026-08-08 데이터 교체(공공데이터→온누리 가맹점찾기 수집본) 후 카드 문구만 옛 출처로 남음 → "온누리 가맹점찾기 수집본 기준"으로 통일. build_index.py 재빌드(D-F1 통과) |
| 2026-08-10 | 브랜드 칩 전역 빈도 tie-break + 부분노출 안내 문구 | merchants.html, guide-content-style | 사용자 결함 제보(소규모 지역서 GS25 등 대표 브랜드가 칩에서 누락). count 동점 시 시도 전역 빈도순위로 정렬(이름순이면 ko 정렬상 라틴이 후순위). 칩에 "대표 브랜드만 · 전체는 검색" 한 줄 안내. guide-content-style에 "부분만 보여줄 땐 그렇다고 말한다" 원칙 추가 |
| 2026-08-10 | 서비스 지역에 부산 추가 (코스콤·한국거래소 소재지) | data/merchants/busan.json, merchants.html, index.html, build_region_full.py, load_merchants.py | 사용자 요청(코스콤 직원용 내부 가이드 — 거래소는 형제회사). 부산(시도코드 26000, 16 구/군, 12,514건) 온누리 API 수집. 전체 재수집(수집일 2026-08-10). 범위 문구 '수도권'→'코스콤·한국거래소 소재지(서울·인천·경기·부산)'. config.js dataVersion으로 JSON 캐시 무력화. 부산은 구→동 계층(서울·인천과 동일). 갱신: build_region_full.py 재실행 |
| 2026-08-10 | UI/UX 대비 강화: 극단 타이포 + 초저투명 패턴 | merchants.html, index.html, build_index.py | 사용자 요청. 타이포 스케일 극단화(h1 clamp 34→54px w900 ↔ 라벨 10px uppercase, 시도 탭은 숫자가 주인공). 잉크 블랙(#0B0C0E)·그레이 재조정, 오렌지는 포인트만. 패턴: body 도트+오렌지 글로우, 사이드바 핀스트라이프, 필터 해칭, 헤더 '온' 워터마크(4.5%). index는 build_index.py 24b.h1-display 스텝 추가 |
| 2026-08-10 | 코스콤 CI 적용 + 지도범위 검색 | merchants.html, index.html, build_index.py | 사용자 요청(CI 가이드 이미지 참조). '온' 마크→❯koscom CI(오렌지 셰브론 SVG+워드마크, 자산파일 없이 인라인), 브랜드명 '코스콤 디지털온누리 가이드'. 지도 상단 '현 지도에서 가맹점 검색' — bounds 필터(MAP_BOUNDS), 진입 시 구/동 리셋·지역 조작 시 자동 해제·뷰 유지. API 모드용 백엔드 bounds 파라미터는 feat의 MerchantSpecs에 추가 |
| 2026-08-10 | doc-commit 스킬 신설 (표준 커밋·푸시 절차) | .claude/skills/doc-commit | 사용자 요청. 커밋 전 문서 갱신(CLAUDE.md 이력·명세·DEPLOY.md) → 비밀값·D-F1·브랜치 규칙 점검 → 커밋·푸시 → main↔feat 공용 파일 동기화까지 절차화. 트리거: "커밋푸시하자" 등 |
| 2026-08-10 | 지도범위 검색을 전 지역(4개 시도) 대상으로 확장 | merchants.html | 사용자 요청(시도 탭과 지도 위치가 어긋나면 0곳 나오는 혼동 해소). bounds 모드 시 시도 무관 서울·인천·경기·부산 합본에서 검색(JSON은 4파일 합본·캐시, API는 region 파라미터 생략). mScope에 '전 지역 대상' 표시, 첫 실행 로딩 안내. 검증: 동일 뷰에서 27,331(시도 한정)→32,937(전 지역) |
| 2026-08-10 | 지도범위 검색: 지도 따라 자동 재검색 + 상한 오버레이 | merchants.html | 사용자 결함 제보("클릭 후 줌 해야 보임"). 원인: 넓은 뷰는 3,000 마커 상한 초과라 마커 미표시인데 안내가 약했고 재검색이 수동. 수정: bounds 모드 중 지도 idle 시 400ms 디바운스 자동 재검색(해제·재적용 불필요), 상한 초과 시 지도 중앙 다크 오버레이("이 영역 N곳 — 확대하면 자동 재검색"), 버튼 라벨 '지도 따라 검색 중'. 검증: 32,937(마커0·오버레이)→행클릭 줌인→자동 236곳 마커 표시 |
| 2026-08-10 | 사이드바 '지역별 찾기'·'업종·브랜드별' → '가맹점 찾기' 통합 | merchants.html, index.html, build_index.py | 사용자 질문으로 드러난 혼동: 두 메뉴가 별개 기능이 아니라 같은 검색 페이지의 앵커(#sidoTabs/#catChips) 스크롤 차이뿐. 단일 '가맹점 찾기'로 통합(메뉴명이 기능을 약속하지 않게) |
| 2026-08-10 | 코스콤 CI를 공식 로고 이미지로 교체 | assets/koscom_ci.png, merchants.html, index.html, build_index.py, .gitignore | 사용자 피드백(SVG+텍스트 재현이 단순 텍스트로 보임). 공식 CI PNG(152×31, 투명배경)를 assets/에 두고 img 기반으로 교체. .gitignore *.png에 assets/*.png 예외 추가(누락 시 라이브 404) |
| 2026-08-10 | CI 이미지 고해상 교체 (152×31 → 470×96) | assets/koscom_ci.png | 사용자 제공 CI 가이드 시트(8000px)에서 기본형 로고 크롭·점선 제거·흰배경 투명화·LANCZOS 리사이즈. 표시 19px의 5배 밀도(레티나 선명). 경로 동일 — HTML 무변경 |
| 2026-08-10 | 디스플레이 서체 Gmarket Sans 도입 (제목·브랜드 전용) | merchants.html, index.html, build_index.py | 사용자 요청(코스콤 폰트 유사체). 코스콤 전용체는 비공개 사내 자산 → CI 워드마크(원형 기하 라틴)와 가장 유사한 무료 웹폰트 Gmarket Sans 선정(시편 비교: Paperlogy·SUIT 대비). Bold 1웨이트만 CDN 로드(font-display:swap, 미로드 시 Pretendard 폴백). 적용: h1·시도탭 숫자·사이드바/상단바 브랜드명·모달 제목. 본문·표는 Pretendard 유지(가독성) |
| 2026-08-10 | 온라인 사용처 2축 검색 신설 (물품종류×브랜드, online.html) | online.html, data/online_catalog.json, merchants.html, index.html, build_index.py(S16), 15_online_catalog_report.md | 사용자 요청. 쇼핑 22곳 Playwright 실측(카테고리 GNB·브랜드관 채록, 기획전 딥링크는 기획전 범위만) → 플랫폼 단위 태깅(taxonomy 10대분류+소분류, 브랜드 42종 — 삼성·애플·LG·DJI·로보락 실측 확인). 배달 8곳은 태깅 제외(음식 주문). 카드 그리드+구분탭+계층 칩+브랜드 칩/드롭다운. 사이드바 3곳+index 온라인탭 진입 카드. 갱신: 재실측 후 online_catalog.json·15_report 수기 갱신 |
| 2026-08-10 | 백엔드 NCP 배포 완료 + 라이브 API 모드 전환 | config.js (서버: NCP 101.79.31.30) | NCP Server(Ubuntu 22.04)+Docker Compose(Postgres·Spring·Caddy) 배포, api.koscomlabor.cloud TLS(Let's Encrypt) 자동 발급, 78,734건 적재. 외부 검증 전 통과(개포동 145·팔달 1,241·부산 12,514·해운대 681·bounds·facets·CORS). 라이브 dataMode json→auto(API 우선, 장애 시 JSON 폴백). 서버 갱신 절차: DEPLOY.md. 이슈 해결: NCP 미러에 docker-compose-plugin 없음→get.docker.com, private repo raw 404→public 전환 |
| 2026-08-10 | 리스트↔지도 드래그 스플리터 + '결제 방법' 토글 자동 펼침 | merchants.html, index.html, build_index.py | 사용자 요청 2건. ①PC에서 리스트·지도 사이 핸들 드래그로 지도 폭 조절(--map-w, 300px~65%, localStorage 유지, 더블클릭 초기화, ←/→ 키, 드래그 시 네이버 지도 resize 트리거, 모바일 숨김). ②사이드바 '결제 방법' 진입 시 접힌 결제 흐름 아코디언(▶) 자동 펼침(openPaymentToggles, DC 마운트 재시도) + 펼침 후 스크롤 보정(450ms) |
| 2026-08-10 | FOUC 방어 + 리스트 내부 스크롤 + 화면 폭 토글 | build_index.py, merchants.html, online.html, config.js | 사용자 제보 3건. ①index 마운트 순간 CI 2개 세로 겹침 — 4MB 번들 FOUC(이미지 크기 무관) → SHELL 크리티컬 인라인 스타일(topbar none·sidebar fixed·img width/height), 모바일 media는 !important로 복원. ②PC에서 가맹점 리스트를 지도와 같은 높이의 자체 스크롤로 분리(thead sticky, 페이저는 내부 스크롤 리셋). ③화면 폭 토글 좁게 880/표준 1080/넓게 1640 — 사이드바 위젯 3페이지 공통(html[data-pw]+--page-w), localStorage 공유, 로직은 config.js(index는 셸 내장), 변경 시 지도 resize |
