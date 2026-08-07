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
