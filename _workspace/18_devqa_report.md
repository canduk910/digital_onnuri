# 18. dev-qa 검증 리포트

검증 원칙: "동작한다"가 아니라 "기준값과 일치한다"로 판정. "검증불가"를 "통과"로 쓰지 않는다.

---

## 2026-08-12 — 온라인 플랫폼 DB 이관(V5 + /api/online/platforms) + 야간 배치 검증

**검증 대상**: 온라인 플랫폼 목록의 백엔드 DB 이관(V5 `online_platform`, GET/POST `/api/online/platforms`), 야간 배치(`nightly_update.py`), 프론트 `online-source.js` 어댑터(API 우선 + JSON 폴백).
**환경**: feat backend worktree(`digital_onnuri_feat`), main 프론트, 로컬 `onnuri-db`(도커, merchant 78,734건·online_platform 30건 적재됨), JDK21.

### 항목별 판정

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 1 | 백엔드 테스트 `./gradlew test` | **통과** | `--rerun-tasks` 강제 실행 tests=29 failures=0 errors=0. OnlineContractTest 3건 포함 전부 녹색 |
| 2 | 경계면 삼자 대조(API↔JSON↔어댑터) | **통과(주의 1건)** | 실제 curl 응답 필드 = 계약 = 어댑터 정규화 일치. snake/camel 혼재 정상 흡수. "기준" 스탬프 소스별 상이(아래 F-1) |
| 3 | 프론트 실경로(Playwright) | **통과** | API 모드·JSON 폴백 모두 30카드·스탬프 소스 추종. terms.html 지역한정 5곳 정상 |
| 4 | 회귀 기준값 | **통과** | 로컬 API: 서울 29,462·개포동 145·GS25 335 정확. online 30(쇼핑22·배달8)·지역한정 5 |
| 5 | 배치 안전성 리뷰 | **통과(주의 1·참고 2)** | 가드·스왑·인덱스 리네임·fail-open·큐레이션 보존 구현 확인. 아래 F-2~F-4 |
| 6 | D-F1(리터럴 `</` 0) | **통과** | 빌더 assert 통과·이스케이프 215. 작업트리 index.html 직접 스캔 리터럴 `</`=0 (참고 F-5) |

### 1. 백엔드 테스트
`./gradlew test --rerun-tasks` 강제 실행 — 10개 테스트 클래스, 합계 tests=29 / failures=0 / errors=0. `OnlineContractTest`(record 컴포넌트명·직렬화 키·meta.collected_on snake 키 고정) 3건 통과.

### 2. 경계면 삼자 대조 (핵심)
로컬 백엔드 기동 후 `curl localhost:8080/api/online/platforms` 실제 응답 vs `data/online_platforms.json` vs `online-source.js` 어댑터 규칙 필드 단위 대조:

| 필드 | API 응답(실측) | JSON 폴백 | 어댑터 정규화 | 판정 |
|------|---------------|-----------|--------------|------|
| meta.collected_on | `collected_on`(snake) = **2026-08-12** | `collected_on` = **2026-08-06** | `pick(collected_on, collectedOn)` | 키 동일(snake)·**값 소스별 상이**(F-1) |
| meta.source | `source` | `source` | `meta.source` | OK |
| meta.sourceUrl | `sourceUrl`(camel) | `source_url`(snake) | `pick(source_url, sourceUrl)` | 키 상이·어댑터 흡수 OK |
| item.regionLimited | `regionLimited`(camel) | `region_limited`(snake) | `pick → region_limited` | OK |
| item.sourceUrl | `sourceUrl`(camel) | `source_url`(snake) | `pick → source_url` | OK |
| item.collectedOn | `collectedOn`(camel) | `collected_on`(snake) | `pick → collected_on` | OK |
| item.no | postNo(예: 대구로=1) | `null`(전항목) | `raw.no` | 값 상이·**미표시로 무해** |
| item.regions | **필드 없음** | `regions[]`(배달 지역한정) | `raw.regions \|\| []` | API=[]·**미표시로 무해**(F-4) |
| status/id/kind/name/summary/note/url | 동일 | 동일 | 그대로 | OK |

**meta는 `collected_on`(snake), items는 `collectedOn`(camel) 혼재**를 실측 확인 — 어댑터 `normPayload`/`normItem`이 각각 pick으로 정규화해 내부형(snake)으로 통일. 정상.

### 3. 프론트 실경로 (Playwright, 로컬 8655 + dataMode auto)
- **API 모드**(백엔드 up): online.html — API 요청 1·JSON 0, `.card-grid` 30카드, metaLine "공식 목록 **2026-08-12** 수집", 콘솔 에러 0·카탈로그 고아 id 0.
- **JSON 폴백**(백엔드 down): API 시도 실패→JSON 폴백, 30카드 유지, metaLine "공식 목록 **2026-08-06** 수집". 카운트 동일, 스탬프만 소스별 전환.
- terms.html 폴백: `regionApps` = "전주맛배달, 배달특급, 먹깨비, 배달의 명수, 대구로"(지역한정 5곳) — 어댑터 `region_limited` 정규화 정상.

### 4. 회귀 기준값 (로컬 API, 2026-08-10 수집분 기준표)
서울 29,462 · 개포동 145 · GS25(전국) 335 — 모두 정확 일치. DB 지역 카운트 서울29,462·인천7,236·경기29,522·부산12,514 일치. online 30(쇼핑22·배달8)·지역한정 5.

### 5. 배치 안전성(`nightly_update.py`) 코드 리뷰
- 단계 A(가맹점): 재수집 exit≠0→False(배치 실패), stage `LIKE INCLUDING ALL` 적재→**지역별 ±20%·총계 하한 50,000 가드**→위반 시 stage drop·기존 유지, **단일 트랜잭션 RENAME 스왑**(무중단), old drop·인덱스명 정규화(`merchant_stage_*→merchant_*`, 멱등)·ANALYZE. 구현 보고대로 확인. **통과.**
- 단계 B(온라인): 수집 fail-open((None,사유) 반환·totalCnt<10 가드), post_no→이름 매칭 upsert, **UPDATE가 note·region_limited·id·source_url 미변경(큐레이션 보존)**, 미매칭 기존 active→removed(삭제 금지). 구현 확인. **통과.**
- 단계 C(RAG): OPENAI_API_KEY 없으면 스킵. 확인.

### 발견 결함/유의점

| ID | 등급 | 내용 | 담당 |
|----|------|------|------|
| F-1 | **주의** | "기준" 스탬프가 소스별로 다름: API=2026-08-12(DB, 배치 갱신본)·JSON 폴백=2026-08-06(정적). **카운트는 양 경로 동일**하나 표시 날짜만 다름. ADR-14(DB=SSOT·JSON=낡아도 되는 폴백)상 **의도된 동작**이나, dev-qa 원칙("두 폴백은 같은 답")의 예외를 명시적으로 문서화 권장. 실사용상 백엔드 장애 시 사용자가 보는 "수집일"이 과거로 바뀜 | backend(설계 의도 확인)·frontend(문구 톤 검토) |
| F-2 | 주의 | 단계 B는 `conn.autocommit=True`라 `conn.commit()`이 no-op — **B의 upsert 루프가 비트랜잭션**. 수집은 완료 후 일괄 기록이라 실패지점이 좁고 멱등(익일 재동기)이라 자가치유되나, DB-write 중 예외는 fail-open 대상이 아님(수집 실패만 fail-open) → 부분갱신 후 배치 crash 가능. 단계 A(스왑)는 `conn.transaction()`으로 원자성 확보됨 | backend |
| F-3 | 참고 | `_canonicalize_index_names`가 스왑 트랜잭션 밖(autocommit)에서 실행 — 중간 실패 시 인덱스명이 `merchant_stage_*`로 잔존해 익일 stage 생성 이름 충돌 가능. 코드 주석이 위험을 인지하고 있고 멱등이라 저위험 | backend |
| F-4 | 참고 | 어댑터 `regions` 필드는 API 계약·스키마에 없어 API 모드에서 항상 `[]`. online.html·terms.html 모두 `regions` 배열 미표시(`region_limited`만 사용)라 **기능 영향 없음**. 어댑터의 죽은 필드 — 정리해도 무방 | frontend(선택) |
| F-5 | 참고 | **작업트리 index.html이 빌더 최신 산출과 일치**(재빌드 시 무변경·D-F1 통과)하나, **`git add` 스테이징 스냅샷이 1빌드 뒤처짐**(staged엔 "이 안내 페이지의 목록은 갱신…" span 누락). 커밋 시 index.html 재-스테이징 필요 — 안 하면 낡은 산출물이 커밋됨 | frontend/commit |

### 판정 요약
6개 항목 전부 **통과**. 치명 결함 없음. 계약·경계면·회귀·D-F1 모두 기준 일치. 커밋 전 조치 권장: **F-5(index.html 재-스테이징)**, F-1(스탬프 소스별 상이 문서화), F-2(단계 B 트랜잭션/에러 fail-open 범위 검토).

검증 후 로컬 백엔드 앱 종료(8080 down), 정적서버 종료, `onnuri-db` 컨테이너 유지.
