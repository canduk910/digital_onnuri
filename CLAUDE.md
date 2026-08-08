# digital_onnuri

디지털온누리상품권 사용처 가이드 페이지(`index.html`) 리포지토리.

## 하네스: 온누리 가이드 제작·갱신

**목표:** 날짜에 종속된 상품권 정책·사용처 정보를 정확하게 유지하며 가이드 페이지를 갱신한다. 이용자가 계산대에서 결제 실패를 겪지 않는 것이 품질 기준이다.

**트리거:** 가이드 제작·갱신·최신화, 사용처 목록 변경, 정책 반영, 문구·페이지 수정, 배포 전 검수 요청 시 `onnuri-guide-orchestrator` 스킬을 사용하라. 개별 사용처 단건 질문("우리 동네 CU 되나")은 스킬 없이 직접 답해도 된다.

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
