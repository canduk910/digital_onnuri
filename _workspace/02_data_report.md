# 02. 사용처 데이터 수집 리포트

- 작성: onnuri-data-curator
- 작성일: 2026-08-06
- 산출물: `data/online_platforms.json`, `data/offline_categories.json`

## 1. 수집 경위

기존 `index.html`(번들 페이지, 388행의 `__bundler/template` JSON 문자열)에 하드코딩돼 있던 Vue/React 데이터 배열(`ONLINE` 30건, `OFFLINE` 12건)을 추출해 SSOT로 이관했다. base64 블롭은 건드리지 않고 python으로 템플릿 문자열만 디코드해 파싱했다.

기존 페이지 자체 표기: "온누리 공식 홈페이지 '온라인 전통시장관'(1~3페이지)에 안내된 가맹 플랫폼 전체 (2026-08-06 수집)".

## 2. 공식 출처 재검증 — **실패**

| 시도 | 결과 |
|------|------|
| `https://www.onnuri.gift/` WebFetch | SPA 셸("onnuri-gift")만 수신 — 콘텐츠 없음 |
| `https://www.onnuri.gift/store/online`, `/mall` WebFetch | 동일 (SPA 셸) |
| `https://r.jina.ai/...` (JS 렌더 프록시) | HTTP 422 |
| `onnurigift.or.kr` | DNS 해석 실패 |

스킬 규칙에 따라 **기존 항목 전량 유지**, `collected_on`은 기존 페이지 수집일 `2026-08-06` 그대로 사용(올리지 않음), `meta.note`에 재검증 실패를 명시했다.

### 2-1. 후속 — verifier 브라우저 실측으로 재검증 완료 (2026-08-06)

B1 검증에서 verifier가 `https://www.onnuri.gift/visit/market` **전 3페이지를 브라우저로 실측**해 30곳의 이름·카운트·kind가 본 데이터와 **완전 일치**함을 확인했다(현행 목록 = 이관본). 이에 따라:

- `meta.source_url`·전 항목 `source_url`을 `https://www.onnuri.gift/visit/market`으로 갱신, `meta.pages_checked: "1-3"`은 실측 확인값으로 승격.
- 기획전 딥링크 확보 3건(D1): **cyso**(경북 사이소)·**hyundai-home-shopping**·**gongyoung-shopping** — 몰 루트 URL을 온누리 기획전 직링크로 교체, "온누리 홈페이지 카드에서 이동" 비고 해소.
- 파라미터 정밀화 2건(D2): **epost-mall**(`#origin_market_tab` 앵커)·**lotte-on-sangsaeng-store**(`ch_dtl_no` 파라미터). tpirates는 utm 추적 파라미터뿐이라 기존 클린 URL 유지.
- `collected_on`은 전 항목 2026-08-06으로 실측일과 동일 — 상향 불필요.
- **수정 내역**: 05_verification_report.md B1 결함 **D1(사이소·현대홈쇼핑·공영쇼핑 딥링크 교체)·D2(우체국쇼핑 앵커·롯데ON 파라미터 추가, tpirates는 utm 추적 파라미터라 클린 URL 유지)** 반영 완료 — 이름·summary·kind는 검증 통과분이므로 미변경.

## 3. 델타

verifier 실측 대조 결과 **추가 0 / 삭제 0 / 변경 0** — 기존 30곳이 현행 공식 목록과 완전 일치.

이관 과정의 데이터 변형(원본 index.html 대비):

- `summary`(한 줄 소개)·`note`(비고)는 원문 그대로 이관. 단 먹깨비 note에 "지역별 가용성 상이" 부연 추가.
- `region_limited` 필드 신설: 원본 각주("지역 기반 배달앱 — 대구로, 배달특급, 배달의 명수, 전주맛배달 등")에 명시된 4곳 + 먹깨비(note에 "여러 지자체 참여" 명시)를 true로 표기.
- 오프라인 `conditional` 5건에 `check_point` 보강: 원본 비고에 확인 방법이 없던 항목(음식점·카페, 마트·슈퍼 등)에 가맹점 지도(onnuri.gift/place) 점포 단위 검색·입구 스티커·카드형 앱 차감 알림 확인법을 추가했다. 스킬 규칙("확인 방법 없는 조건부는 결함") 준수 목적.

## 4. basis ID — 01_policy_analysis.md 매핑 완료

작업 도중 `_workspace/01_policy_analysis.md`가 산출되어, 처음 임시로 배정한 R1~R4가 해당 문서 "주장 1 — 오프라인 사용 요건 4가지"의 ①~④와 정확히 일치함을 확인하고 JSON `meta.basis_definitions`에 확정 반영했다:

| ID | 매핑 (01_policy_analysis.md 주장 1) |
|----|-------------------------------------|
| R1 | ① 지정 구역(전통시장·상점가·골목형상점가) 안 점포 |
| R2 | ② 온누리 가맹점 등록(스티커/앱 지도 확인) |
| R3 | ③ 연매출 30억 원 이하 — 기존 가맹점은 최초 갱신 시점부터 적용(경과조치) |
| R4 | ④ 제외(제한) 업종 — 2026.6.17 보건업 등 추가, 약국 예외 |

배정 근거가 상대적으로 약한 항목(재검토 대상):
- **편의점 R3**: "직영점 배제"를 R3(연매출 30억 — 직영은 본사 법인 매출 기준 초과)로 해석. 직영 배제의 정확한 규정 위치는 policy 문서에도 미확보.
- **생활서비스 R4**: 약국 조제분 결제 제한을 R4(제한업종·약국 예외)에 배정. policy 문서는 "약국 가맹 유지, 단 30억 기준 동일 적용"이라 하나 조제분 제한 자체는 언급 없음 — 원본 페이지 서술 승계.
- **writer 전달**: policy DELTA에 따라 "약국 가맹 유지" 서술에는 "30억 기준은 약국에도 적용" 병기 필요.
- **수정 내역 (2026-08-06)**: 생활서비스 `check_point`를 writer 확정 문구(03_content_spec.md S6)로 교체 — 기존 "조제분 결제 제한 가능"은 1차 근거 미확인(low)이라 단정 삭제, "처방약 결제 가능 범위는 결제 전 약국·앱에서 확인" 확인지시 어조로 전환 + "연매출 30억 기준 동일 적용" 병기.

## 5. 미확인 항목

- **배달앱 지역 커버리지**: 휘파람(동네 기반)·머먹지(소상공인 배달앱)·땡겨요는 서비스 지역 미확인으로 `region_limited: false` 처리, 먹깨비는 true이나 `regions: []`(참여 지자체 목록 미확인). 공식 사이트에 지역 메타가 없어 실측으로도 미해소 — 각 앱 자체 공지로 확인 필요.
- ~~온라인 30곳 현행 여부~~ → 해소: verifier 실측으로 현행 목록과 완전 일치 확인 (§2-1).
- ~~기획전형 플랫폼 진입 URL~~ → 해소: cyso·현대홈쇼핑·공영쇼핑 딥링크 확보 (§2-1).

## 6. 파생 값 (렌더 시 계산 — 저장 금지)

- 온라인 총 30곳 = `items.filter(status === 'active').length` — 쇼핑 22 · 배달 8은 `kind` 집계
- 오프라인 12유형 — 가능 4 · 조건부 5 · 불가 3은 `verdict` 집계
- 페이지 기준일 스탬프 = `min(items[].collected_on)` = 2026-08-06

페이지 헤더의 "공식 안내 30곳 — 쇼핑 22 · 배달 8" 문구는 반드시 위 계산으로 대체할 것 (현재 index.html에는 하드코딩돼 있음 — dev 이관 시 제거 대상).
