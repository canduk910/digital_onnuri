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

---

## 2026-08-18 — 관리자 비밀번호 로그인 (POST /api/admin/login + admin-report.html) 검증

**검증 대상**: 백엔드 `AdminController`(신규)·`ChatConfig.adminLoginRateLimiter`·`application.yml app.admin.password`, 프론트 `admin-report.html` 로그인 폼.
**환경**: main 작업트리(미커밋 상태), JDK21, 로컬 `onnuri-db`(도커, merchant 78,734건), 백엔드 `bootRun --server.port=8080`(임시 `APP_ADMIN_PASSWORD`·`APP_ADMIN_KEY` 주입 — 값은 검증 후 폐기, 본 문서 미기록), 정적서버 `python3 -m http.server 8655`, Playwright.
**핵심 사유**: 프론트 인계 노트의 200/403/429 검증이 전부 `fetch` 모킹이었다 — 실서버 경계면은 이 검증이 처음이다.

### 항목별 판정

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 1 | 백엔드 테스트 전체 | **통과** | `cleanTest test` 강제 실행 — 13클래스 tests=44 failures=0 errors=0 skipped=0. `AdminLoginContractTest` 7건 포함 |
| 2 | 경계면 실연동(브라우저↔실서버) | **통과** | 5개 시나리오 전부 실서버 응답으로 확인(아래 2절) — 모킹 아님 |
| 3 | 회귀 무영향 | **통과** | 검색 API 기준값 12개 전부 일치, 검색 계약 파일 무변경. RateLimiter 빈 3개 분리 실측(제보 2·관리자 5·챗 10) |
| 4 | 보안 점검 | **조건부** | 비번 로그 미출력·프론트 미저장·응답 최소화는 통과. 단 **rate limit이 X-Forwarded-For 스푸핑으로 완전 우회됨(F-6)** |

### 1. 백엔드 테스트
최초 `./gradlew test`는 `UP-TO-DATE`(backend 실행분 캐시)라 판정 근거가 되지 않아 `cleanTest test`로 강제 재실행했다. XML 리포트 집계 결과 tests=44 / failures=0 / errors=0 / skipped=0, 13개 클래스. 신규 `AdminLoginContractTest` 7건(정상 200+key / 오답 403·키 미노출 / 비번 미설정 403 / 키 미설정 403 / 분당 한도 429·타 IP 무영향 / 본문 없음·빈 body 403 / authenticate 경계값) 전부 녹색.

### 2. 경계면 실연동 (핵심 — 프론트 모킹 검증의 대체)
`admin-report.html`의 로컬 API_BASE는 `http://localhost:8080/api`(config.js `apiBase` 주석 처리 상태라 hostname 분기 적용) — 기동 포트와 일치 확인 후 진행.

| # | 시나리오 | 판정 | 실측 |
|---|----------|------|------|
| ① | 오답 입력 → 403 문구 | 통과 | 네트워크 `POST /api/admin/login => 403`, 화면 "비밀번호가 올바르지 않거나 로그인 기능이 비활성 상태입니다.", 폼·입력값 유지, 버튼 재활성화, `sessionStorage=null` |
| ② | 정답 입력 → 키 저장 | 통과 | `=> 200`, `#keyState.className="key-state ok"`("관리자 키 입력됨"), `sessionStorage.onnuri_admin_key` 48자 = **서버 `APP_ADMIN_KEY`와 동일**, 로그인 폼 소멸 |
| ③ | 그 키로 상태 변경 실동작 | 통과 | 테스트 제보 1건 생성 → 브라우저에서 "반영" 클릭 → `POST /api/reports/1/status => 200`, 배지 `rep-badge done`, **DB 실조회 `1\|반영`**. "접수" 원복도 200 → DB `1\|접수` |
| ④ | CORS preflight | 통과 | curl 실측 `OPTIONS /api/admin/login` (Origin: http://localhost:8655) → **200**, `Allow-Origin: http://localhost:8655`, `Allow-Methods: GET,POST`, `Allow-Headers: content-type`. 브라우저 콘솔에 CORS·JS 예외 **0건** |
| ⑤ | 연속 오답 → 429 문구 | 통과 | UI 연속 제출 7회에서 5번째부터 "시도 횟수를 초과했습니다 — 잠시 후 다시 시도해 주세요."(직전 성공 로그인이 60초 슬라이딩 창에 남아 있어 4회+1). curl 단독 버킷(오답 7연타)에서는 **1~5=403, 6~7=429**로 계약(분 5회)과 정확히 일치 |

추가 확인(계약 밖이지만 운영상 중요):
- **`APP_ADMIN_PASSWORD` 미설정 서버**(env 제거 후 재기동): 정답이었던 비번·빈 문자열 모두 **403**(로그인 비활성), 그 상태에서도 `X-Admin-Key` 직접 상태변경은 **200**(기존 경로 무회귀).
- 기존 `?key=` 진입 경로: URL에서 `key` 제거됨(`location.search`에 `key` 없음), 키 48자 저장, 폼 미표시 — 회귀 없음.
- 콘솔 "error" 8건은 전부 브라우저가 403/429 HTTP 상태를 자동 기록한 `Failed to load resource` — JS 예외·CORS 차단 아님(코드로 억제 불가, 결함 아님).

**검증 함정 기록**: `.env` 파일을 `source`한 셸에서 `bootRun`을 띄우면 `APP_ADMIN_KEY`만 명시해도 **`APP_ADMIN_PASSWORD`가 셸 환경으로 상속**되어 "미설정" 시나리오가 조용히 무력화된다(정답 비번이 계속 200). `env -u APP_ADMIN_PASSWORD`로 명시 제거해야 한다. Gradle 데몬 재사용을 의심해 `--no-daemon`까지 시도했으나 원인은 셸 환경 상속이었다.

### 3. 회귀 무영향
변경 파일 목록에 검색 계약(`SearchQuery`·`MerchantSpecs`·`MerchantController`·`ClusterRepository`) **없음**. 이번 기능의 변경 범위는 `admin/`(신규 2) + `chat/ChatConfig`(빈 1개 추가) + `application.yml`(app.admin.* 3줄) + `admin-report.html` + 문서(DEPLOY.md·17·04)에 한정.

로컬 API 기준값 대조 — **12/12 일치**:

| 질의 | 기준값 | 실측 | 질의 | 기준값 | 실측 |
|---|---|---|---|---|---|
| 서울 | 29,462 | 29,462 | 개포동 | 145 | 145 |
| 인천 | 7,236 | 7,236 | 수원 팔달구 | 1,241 | 1,241 |
| 경기 | 29,522 | 29,522 | 부산 해운대구 | 681 | 681 |
| 부산 | 12,514 | 12,514 | 경기 GS더프레시 | 8 | 8 |
| 서울 강남구 | 761 | 761 | bounds(37.49~37.51,127.02~127.07) | 571 | 571 |
| 전국 GS25 | 335 | 335 | facets 키 | cat/brand/mtype | 일치 |

**주의**: 작업트리의 `chat-widget.js/css`(v10)·6개 HTML 캐시버스트·`build_index.py` 수정은 **이번 로그인 기능과 무관한 별건**(2026-08-15 챗 기본 열림)이다. 본 검증 범위 밖이며 손대지 않았다.

### 3-b. RateLimiter 빈 3개 회귀 (backend 중점 요청 #3)
`ChatConfig`에 `adminLoginRateLimiter`가 추가되어 동일 타입 빈이 3개가 됐다. 주입은 파라미터명 매칭에 의존하므로(`reportRateLimiter`/`chatRateLimiter`/`adminLoginRateLimiter` — 정의명과 주입 파라미터명 1:1 확인) **한도가 서로 뒤바뀌어도 예외 없이 조용히 틀린다.** 기동 성공만으로는 근거가 부족해 세 경로의 한도 경계를 각각 별도 IP 버킷으로 실측했다.

| 경로 | 계약 | 실측 | 판정 |
|---|---|---|---|
| `POST /api/reports` | 분 2회 | 1·2회 **200**, 3회 **429** | 일치 |
| `POST /api/admin/login` | 분 5회 | 1~5회 403, 6회 **429** | 일치 |
| `POST /api/chat` | 분 10회 | 1~10회 통과(키 미설정 안내 이벤트), 11회 **"요청이 많아 잠시 제한되었습니다"** | 일치 |

챗은 `ChatController`가 한도 검사를 `ai.isConfigured()`보다 먼저 수행하므로 OpenAI 키 없이도 경계 측정이 가능했다. 세 한도가 각자 값을 유지하고 서로 오염되지 않음 — **교차 배선 없음**.

### 4. 보안 점검

| 점검 | 결과 |
|---|---|
| 서버 로그 비밀번호 평문 | **없음** — 부팅~시나리오 전체 로그 44줄에서 비번·키·시도값("brute-force")·"password" 문자열 grep **0건**, ERROR/WARN 0건 |
| 프론트 비밀번호 저장 | **없음** — 성공 후 `document.body.innerHTML`·`sessionStorage`·`localStorage`에 비번 문자열 부재. 코드상 `pw.value`는 요청 본문에만 사용, 성공 시 폼 자체가 DOM에서 소멸. URL에도 미노출(form submit `preventDefault`) |
| 응답 민감정보 | **최소** — 200 body 키는 `['key']` 하나뿐, 403/429는 `message`만. 실패 응답에 키 미포함(계약 테스트로 고정) |
| 상수시간 비교 | `MessageDigest.isEqual` 사용 — 접두 일치 조기 반환 없음 |
| 무차별 대입 방지 | **우회 가능(F-6)** — 아래 |

### 미해결 지적

| ID | 등급 | 내용 | 담당 |
|----|------|------|------|
| F-6 | **주의(보안)** | **`X-Forwarded-For` 스푸핑으로 로그인 rate limit이 완전 무력화된다.** `AdminController.clientIp()`(AdminController.java:63-66)가 클라이언트가 보낸 XFF의 **첫 값**을 그대로 IP로 쓴다. 재현: 오답 20회를 매번 다른 `X-Forwarded-For: 203.0.113.N`으로 전송 → **403 20회, 429 0회**(동일 헤더 고정 시엔 6회째 429). 프로덕션 `deploy/Caddyfile:5`는 `reverse_proxy app:8080`만 있고 `trusted_proxies` 설정이 없어 Caddy가 클라이언트 XFF 뒤에 실제 IP를 **덧붙이므로**, 첫 값은 여전히 공격자 제어값이다. 결과적으로 "IP당 분 5·일 30"은 정직한 클라이언트에게만 적용되고, 기억 가능한(=저엔트로피) 비밀번호에 대한 무제한 대입으로 48자 관리자 키를 얻을 수 있다. 피해 범위는 제보 상태 배지 변조(접수↔반영)로 한정되나, 리더가 계약에 명시한 보안 통제가 실제로는 성립하지 않는다. 수정 방향: XFF의 **마지막** 값 채택(Caddy 1홉 구성 전제) 또는 Caddy `trusted_proxies` 설정 + Spring `ForwardedHeaderFilter`, 보조로 로그인 실패에 대한 **전역(IP 무관) 카운터** 병행. 동일 패턴이 `ReportController.java:104-107`에도 있으나 그쪽은 스팸 억제라 위험도가 낮다 | **backend** |
| F-7 | 참고 | **로그인 실패·429가 서버 로그에 전혀 남지 않는다** — 무차별 대입 시도의 흔적이 0. 전체 로그 44줄 중 로그인 관련 0줄. 운영자가 공격을 인지할 수단이 없다. 실패·429에 한해 WARN 1줄(시각·IP만, **비밀번호는 절대 금지**) 권장 | backend(선택) |
| F-8 | 참고 | 일 한도(30회)는 **라이브 미검증** — 30회 요청 대신 `RateLimiterTest.일당_한도를_넘으면_다음_날까지_거부한다`(동일 `RateLimiter` 클래스)로 커버됨. 분 한도만 실서버로 확인했다 | — |

### 판정 요약
**조건부 통과.** 항목 1~3(테스트 44/44·경계면 실연동 5/5·회귀 12/12·RateLimiter 3빈 분리) **통과**, 항목 4(보안)는 **F-6으로 조건부**. backend 중점 요청 4건(①응답 키 `key` 소비 ②403/429 구분 안내 ③RateLimiter 빈 3개 회귀 ④`?key=` 경로 회귀)은 전부 실측 통과. 기능 자체는 계약대로 정확히 동작하며 배포를 막을 기능 결함은 없다. 다만 계약에 명시된 무차별 대입 방지(429)가 XFF 스푸핑으로 실효를 잃으므로, **F-6은 배포 전 또는 직후 후속 수정 대상**으로 남긴다(관할 backend).

검증 후 정리: 로컬 백엔드 종료(8080 down), 정적서버 종료(8655 down), 테스트 제보 행 삭제(`report` 0건), 임시 자격증명 파일 폐기, Gradle 데몬 종료. `onnuri-db` 컨테이너는 기존대로 유지. 작업트리 변경 없음(git status 검증 전후 동일).

---

## 2026-08-18 (2차) — F-6/F-7 수정 재검증 (ClientIp 도입)

**검증 대상**: `gift/onnuri/web/ClientIp.java`(신규 — XFF **마지막** 값 채택)로 `AdminController`·`ReportController`·`ChatController` 3개 호출부 통일, F-7 WARN 로그 추가.
**환경**: 1차와 동일(로컬 8080 + docker `onnuri-db` + 정적서버 8655 + Playwright, 임시 자격증명 신규 발급 후 폐기).

### 항목별 판정

| # | 항목 | 판정 | 근거 |
|---|------|------|------|
| 1 | 테스트 52개 녹색 | **통과** | `cleanTest test` 강제 실행 — 14클래스 tests=52 failures=0 errors=0 skipped=0. `ClientIpTest` 6 신규, `AdminLoginContractTest` 7→9 |
| 2 | F-6 공격 차단 | **통과(해소)** | 프로덕션 토폴로지 재현에서 6회째부터 429. 정상 경로·타 클라이언트 무회귀 |
| 3 | F-7 로그 | **통과(해소)** | 실패 21줄·한도 초과 9줄 모두 시각+`ip=`만. 비번·키·시도값 **0건** |
| 4 | 최소 회귀 | **통과** | 제보 분2·챗 분10 유지, 정상 로그인 200, 브라우저 경계면 정상 |

### 1. F-6 공격 재현 (핵심)
**1차 검증과 같은 공격이되, 프로덕션 토폴로지를 정확히 흉내 냈다.** Caddy는 클라이언트가 보낸 XFF **뒤에** 자기가 본 IP를 덧붙이므로, 실제 공격 요청이 앱에 도달하는 형태는 `X-Forwarded-For: <위조값>, <실제 IP>`다. 1차처럼 단일 값만 보내면 프록시가 없는 로컬에서는 마지막 값까지 공격자 값이 되어 현실과 다른 결론이 나온다.

| # | 공격/경로 | XFF 형태 | 실측 | 판정 |
|---|---|---|---|---|
| A | 위조 앞값 회전(오답 10회) | `203.0.113.$i, 198.51.100.7` | 1~5 **403**, 6~10 **429** | **차단됨**(1차: 20회 전부 403·429 0회) |
| B | 후행 콤마·공백으로 마지막 값 비우기 | `203.0.113.$i, 198.51.100.8, , ` | 1~5 403, 6~7 **429** | 빈 요소 건너뛰기 정상 — 우회 불가 |
| C | 스푸핑 없는 정상 경로 | `198.51.100.20`(단일) | 1~5 403, 6 **429** | 분 5회 계약 유지(과잉 차단 없음) |
| D | 다른 실제 클라이언트 | `198.51.100.21` | 오답 403, **정답 200** | 버킷 분리 정상·**로그인 기능 무회귀** |
| E | XFF 없는 요청 | (헤더 없음) | 1~5 403, 6 **429** | `remoteAddr` 폴백 정상 |

### 2. F-7 로그
로그 74줄 중 `관리자 로그인 실패` 21줄·`관리자 로그인 한도 초과` 9줄. 각 줄은 타임스탬프 + `ip=<값>`만 담고, **비밀번호·관리자 키·시도값("guess") 문자열은 전체 로그에서 0건**. 기록된 `ip=` 고유값은 `198.51.100.7/8/20/21`·`0:0:0:0:0:0:0:1`로 **전부 실제(마지막 홉) IP** — 공격자가 위조한 `203.0.113.*`는 한 건도 남지 않았다. 즉 로그가 위조값으로 오염되지 않아 추적 자료로 쓸 수 있다.

### 3. 최소 회귀
- 제보 `POST /api/reports`: 위조 회전 공격에도 200·200·**429**(분 2회 유지).
- 챗 `POST /api/chat`: 위조 회전에도 1~10 통과·11 **한도 초과**(분 10회 유지). ChatController는 backend가 추가로 찾아낸 동일 결함 지점 — 수정 후 한도가 실제로 작동함을 확인했다.
- 브라우저 경계면: 정상 비번 로그인 → "관리자 키 입력됨", `sessionStorage` 키가 서버 `APP_ADMIN_KEY`와 **일치**, 폼 소멸, 비번 DOM 미잔존. 계약(응답 키 `key`) 무변경 확인.

### 재검증 지적

| ID | 등급 | 내용 | 담당 |
|----|------|------|------|
| F-6 | **해소** | 프로덕션 토폴로지에서 위조 회전 공격이 6회째 429로 차단됨(위 A). 1차 지적 종료 | — |
| F-7 | **해소** | 실패·429 WARN 1줄, 비밀번호·키 미노출(위 2절). 1차 지적 종료 | — |
| F-9 | 참고 | **수정의 안전성이 배포 토폴로지에 양방향으로 결합된다.** DEPLOY.md는 "앞단에 CDN·LB **추가**"(과잉 차단) 방향만 경고한다. 반대 방향 — 앱을 프록시 없이 **직접 노출**(디버깅용 `ports: 8080:8080` 추가 등) — 은 마지막 값까지 공격자 제어가 되어 **F-6이 조용히 재발**한다. 실측: 프록시 없는 로컬에 단일값 XFF를 회전시키면 12회 전부 403·429 0회. 현 `docker-compose.prod.yml`은 `expose`만 두어 안전하고 `ClientIp` javadoc도 전제를 적시하므로 **지금은 결함 아님**. DEPLOY.md의 해당 경고에 "app 서비스에 `ports:`를 추가하지 말 것(직접 노출 시 한도 우회 재발)" 한 줄 추가 권장 | backend(문서, 선택) |
| F-10 | 참고 | **로그인 성공은 로그에 남지 않는다**(성공 로그 0줄). 실패·429만 기록되어 "공격이 결국 성공했는지"를 로그만으로 판별할 수 없다. 관리자 로그인 성공 1줄(시각·IP만) 추가 권장 | backend(선택) |
| F-8 | 유지 | 일 한도(30회)는 여전히 라이브 미검증 — `RateLimiterTest`의 일당 한도 단위 테스트로 커버 | — |

### 2차 판정 요약
**통과.** 1차의 조건부 사유였던 F-6이 해소됐고 F-7도 반영됐다. 테스트 52/52, 공격 차단 실측, 정상 경로·타 한도·브라우저 경계면 모두 무회귀. 남은 F-9·F-10은 문서·관측 관련 **참고**이며 배포를 막지 않는다.

정리: 로컬 백엔드·정적서버 종료, QA 테스트 제보 행 삭제(`report` 0건), 임시 자격증명 폐기, Gradle 데몬 종료, `onnuri-db` 유지. 커밋 없음.

---

## 2026-09-02 — 전일 색인 층(ADR-18) + 지니어스몰 실시간 11번째 승격 검증

검증 스냅샷: 미커밋 작업트리(`git status` 기준). 검증 중 `CLAUDE.md`·`_workspace/19_online_probe.md` 가 다른 에이전트에 의해 추가 수정됨 — 아래 문서 지적은 그 시점 내용 기준이다.

**판정: 조건부 통과.** 코드·계약·테스트·렌더는 결함 0. 문서·주석의 곳 수 정합 4건(D1~D4)을 고친 뒤 커밋한다.

### 1. 테스트

| 항목 | 결과 | 기대 |
|---|---|---|
| `./gradlew cleanTest test` | **144건** · 실패 0 · 오류 0 · 스킵 1 | 144 ✓ |
| 스킵 1건 | `SelfTestLiveTest`(라이브 카나리아, `PROBE_LIVE=1` 없이는 미실행) | 설계대로 |
| `test_survey_probe.js` | **154건** 전부 통과 | 154 ✓ |
| `test_index_nightly.js` | **51건** 전부 통과 | 51 ✓ |

신규 테스트 클래스 실행 확인: `IndexJudgeTest`(14 케이스) · `OnlineProductIndexRepositoryTest`(3) · `OnlineSearchContractTest`(필드 순서·`index` 존재 고정) · `ProbeTargetsTest`(대상 11곳·사유 8/2/1 고정).

### 2. 경계면 교차 비교

**(a) 응답 계약 ↔ 프론트 소비 — 일치.**
`OnlineSearchResult` 는 기존 12필드 뒤에 `index` 를 **맨 뒤로** 붙였고 `OnlineSearchContractTest` 가 순서를 고정한다. 프론트가 읽는 키는 `data.index` / `idx.notice` · `platformCount` · `asOf` · `items` / `h.matchCount` · `name` · `collectedOn` · `sampleTitles` · `samplePartial` · `searchUrl` — 전부 record 컴포넌트명과 1:1. `IndexLayer.foundCount` 만 프론트가 읽지 않는데, 곳 수를 프론트가 다시 세지 않는다는 원칙에 따라 `notice` 가 그 값을 담고 나가므로 **설계대로**(죽은 필드 아님).
`IndexLayer` 는 계약상 null 이 아니다(빈 층 = `platformCount 0`·`notice null`). 프론트 가드 `!idx || !idx.notice || !(idx.platformCount > 0)` 와 서버 `notice(platformCount==0) → null` 이 같은 지점에서 맞물린다.

**(b) 제외 사유 집합 — 정확히 3종 일치.**

| 사유 | 서버 `EXCLUSION` | 프론트 `REASON_LONG` |
|---|---|---|
| `robots-blocked` | 8곳 | 있음 |
| `scope-first` | 2곳 | 있음 |
| `no-static-search` | 1곳 | 있음 |

죽은 키 0 · 누락 키 0. 폐기된 `no-search-feature` 는 양쪽 모두에서 제거됐고, 렌더 실측에서 영문 원시 키가 화면 텍스트에 노출되지 않음을 확인(`rawKeyLeak: false`).

**(c) 조회 URL ↔ 데이터 `search_url_template` — 손 대조 완료.**
11곳 중 9곳 동일. 다른 2곳은 **의도된 차이**다 — `hyundai-ezwel-onnuri`·`onnuri-5iljang` 은 몰의 내부 검색 API(JSON)를 조회하므로 데이터에는 사람이 볼 화면 주소가 들어 있고 `searchUrlFor` 가 데이터를 우선한다(2026-09-02 설계). 신규 `genius-mall` 은 화면 렌더 몰이라 조회 URL = 링크 URL 로 동일.
분할 검증: 쇼핑 22곳 = 조회 대상 11 + 제외 명시 11. 양방향 고아 0(대상인데 데이터에 없는 id 0, 제외 사전에 있는데 쇼핑 목록에 없는 id 0).

**(d) 서버 `notice` 4분기 ↔ 명세.**
`IndexJudge.notice` 는 4분기(색인 없음 → null / 전체 낱말 매치 / 일부 낱말만 / 무매치). `_workspace/17_backend_notes.md` 는 4분기를 전부 적고 있으나 `03_content_spec` S19 표는 **무매치 문구 하나만** 축자로 적는다 → D5(경미).

**(e) 색인 리포트 스키마 ↔ 단계 F ↔ V8 컬럼 — 일치.**
`index_nightly.js` 산출 `{date, platforms:[{id, ok, count, pages, seconds, items:[{name,url}], error?}]}` ↔ `stage_f_index` 가 읽는 키 동일. 길이: `NAME_MAX 200` ≤ `VARCHAR(300)`, `URL_MAX 700` = `VARCHAR(700)`(초과분은 크롤러가 버린다), `platform_id` 60자 상한은 노드 테스트가 고정. JS 는 UTF-16 코드유닛으로 세므로 Postgres 문자 수보다 보수적 — 안전 방향.

**(f) 색인 대상 ∩ 실시간 대상 = ∅.**
`RECIPES` = `onnuri-noljang`·`tpirates`, 둘 다 `ProbeTargets.ids()` 에 없다. 런타임에서도 `IndexJudge` 가 `ProbeTargets.ids()` 를 다시 빼므로 이중 방어. 노드 테스트가 이 조건을 고정한다.

### 3. 렌더 실측 (Playwright · `localhost:8655` · `fetch` 스텁 = 서버 record 형태)

| 시나리오 | 결과 |
|---|---|
| ① 색인 있음(found 1·partial 1) | 블록 생성 · eyebrow `전일 색인` · 행 2 · `상품명 2건 발견` / `검색어 전체와 맞는 이름은 없었습니다` · 샘플 문구 2종 정확 · 링크 2 |
| 뒤처진 몰 스탬프 | `collectedOn 2026-08-30` ≠ `asOf 2026-09-01` 인 행에만 `2026-08-30 수집분` 병기 (1건) |
| ② 색인 빈 층(platformCount 2·items 0) | 블록·헤드라인 생성, `<ul>` **미생성**(빈 목록 경계선 없음) |
| ③ 실시간 전부 unknown(timeout) + 색인 | 색인 블록 정상 렌더 · 실패 사유 배지 `응답 지연` |
| ④ 색인 없음(platformCount 0·notice null) | 블록 미생성 · 각주가 단층 문구로 되돌아감 |
| 블록 배치 | `pb-head → pb-list → pb-index → pb-more → pb-also → pb-foot` — 실시간 아래·비대상 접힘 위(명세대로) |
| 사유 3종 whyBlock | 8곳 / 2곳 / 1곳 = 11곳, 접힘 요약 `확인하지 않은 나머지 11곳` |
| 모바일 390px | `scrollWidth 390 == clientWidth` — 가로 스크롤 없음 |
| pageerror | 전 시나리오 **0** |

### 4. 회귀 기준값 (온라인)

| 항목 | 값 | 판정 |
|---|---|---|
| 온라인몰 전체(active) | 30 (쇼핑 22 + 배달 8) | 유지 |
| 카탈로그 플랫폼 / taxonomy | 22 / 11 대분류 | 유지 |
| 검색 링크 보유 | 15 → **16** | 지니어스몰 추가 — 의도된 변경 |
| `index.html` | 무변경 | ✓ |
| `verify_build.py`(D-F1) | 전체 통과(참고 실행) | ✓ |

### 5. 비밀값 스캔

변경 파일의 추가된 줄에서 서버 IP·API 키·개인키·하드코딩 비밀번호 **0건**. 기존 검출분(`CLAUDE.md` 2026-08-10 이력의 NCP IP, `DEPLOY.md` 의 `APP_ADMIN_*` 환경변수 이름)은 이번 변경과 무관한 기존 줄이다.

### 6. 지적 목록

| # | 심각도 | 파일:줄 | 기대 vs 실제 | 담당 추정 |
|---|---|---|---|---|
| D1 | 보통(문서) | `backend/DEPLOY.md:430` | 카나리아 요청량 — 기대 `11곳 = 22건`, 실제 `10곳 = 하루 20건(2026-09-02 기준)`. `19_online_probe.md:461` 은 이미 22건으로 적혀 있어 두 문서가 서로 어긋난다 | backend |
| D2 | 보통(문서) | `backend/DEPLOY.md:479` | `실제 6곳을 두드려 확인한다` — 실제 11곳. 2026-09-02 문서 정합 원칙상 **곳 수를 적지 않는 것**이 맞다(`SelfTestLiveTest` 가 `ALL.size()*2` 로 이미 하드코딩을 뺐다) | backend |
| D3 | 낮음(문서) | `_workspace/19_online_probe.md:241` | `20요청/일(2026-09-02 조회 대상 10곳)` — 실제 22/11. 6-3절은 카나리아 **계약 설명**이라 실측 기록 예외에 해당하지 않는다 | backend |
| D4 | 낮음(주석) | `backend/.../ProbeTargets.java:11,13,16` | 클래스 javadoc 이 `실시간 조회 대상 6곳` · `22곳 중 6곳인 이유` · `나머지 14곳` — 실제 11/11. 이 파일이 곳 수의 원천인데 그 위 주석이 거짓이다 | backend |
| D5 | 낮음(문서) | `_workspace/03_content_spec.md:337~345` | 색인 층 문구 표에 `index.notice` 4분기 중 무매치 1개만 축자로 있고, 전체 매치·부분 매치 헤드라인 문구가 없다 | frontend/backend |
| D6 | 정보(주석) | `OnlineSearchService.java:48`, `online-probe.js:19`, `online.html:808` | 주석의 `6곳`·`6건` 이 낡음. 동작 무영향 | backend/frontend |
| D7 | 정보(문서) | `backend/DEPLOY.md:438` | run.sh 설명 주석의 `하루 1회 12요청` 낡음 | backend |

### 7. 미검증(통과로 적지 않음)

- **V8 마이그레이션 실제 적용·SQL 실행**: 로컬 Postgres 미기동. `OnlineProductIndexRepositoryTest` 는 빈 인자 가드와 `escapeLike` 만 검증하고 `summarize`/`findMatching` 의 SQL 은 **한 번도 DB 에 나가지 않았다.** 컬럼명·`to_char` 별칭·`IN` 자리표시자 개수는 코드 리뷰로만 확인. 배포 후 `V8` 적용과 실 질의 1회를 반드시 확인할 것.
- **단계 F 실행**: `index_nightly.js` 를 Playwright 로 실제 구동하지 않았다(레시피 2곳의 크롤 동작·건수 가드는 단위 테스트 51건 범위).
- **라이브 카나리아 22건**: `SelfTestLiveTest` 는 스킵됨. 지니어스몰 승격의 실측 기대치(present `로봇청소기`)는 배포 후 확인 대상.

---

## 2026-09-02 (2차) — 온라인 사용처 2탭 분리(ADR-20) + 챗 navigate `tab` 착지 검증

검증 대상: 미커밋 작업트리 9파일(`CLAUDE.md`·`03_content_spec`·`16_arch_decisions`·`17_chatbot_design`·`ChatService`·`OpenAiClient`·`ChatContractTest`·`online-probe.js`·`online.html`).

**판정: 조건부 통과.** 게이트 항목 전부 기대치 일치. 다만 착지 창구가 `전국 이용 가능만` 필터를 되돌리지 않아 **챗봇이 말한 곳 수와 착지 화면의 곳 수가 갈리는 경로**를 실측으로 재현했다(F-1). 1차 게이트에서 지적한 문서 정합 D1~D4 는 커밋본에서 전부 반영된 것을 확인했다.

### 1. 테스트

| 항목 | 결과 | 기대 |
|---|---|---|
| `./gradlew cleanTest test` | **150건** · 실패 0 · 오류 0 · 스킵 1 | 150 ✓ |
| 스킵 1건 | `SelfTestLiveTest`(라이브 카나리아) | 설계대로 |
| `ChatContractTest` | 6 → **12건** | +6 ✓ |
| `test_survey_probe.js` | **154건** | 154 ✓ |
| `test_index_nightly.js` | **51건** | 51 ✓ |

신규 6건은 실질 단언이다 — 도구 스키마 enum(`["live","browse"]` 순서까지)·description 키워드, `params` 실림, 비-online page 의 tab 무시(+오류 아님), enum 밖 값 폐기, `params` 안 tab 도 같은 창구, 프롬프트의 탭 라우팅 지시.

### 2. 경계면 교차 비교

**(a) `tab` 값 집합 — 일치.**
서버가 내보내는 값 = `{live, browse, 없음}`(그 밖은 `ChatService.navigate` 가 조용히 제거). 프론트 `resolveTab` 은 `live|browse` 만 그대로 쓰고 나머지는 규칙 폴백. 폴백 규칙도 명세와 일치 — `q` 만 → live / `kind|cat|brand` 중 하나라도 → browse / 둘 다 없음 → browse. **URL 로 들어오는 미검증 값도 프론트가 막는다**: `?tab=zzz&q=로봇청소기` → live 착지·주소 `tab=live` 로 정정·조회 요청 0. 파라미터가 0개인 평범한 방문은 `applyUrlParams` 가 조기 반환해 기본 탭(live)을 지킨다(폴백 규칙에 태우면 browse 로 뒤집히는 함정을 방어).

**(b) `chat-widget.js` 무수정 — 확인.**
`handleAction`(:309)은 `a.params || {}` 를 그대로 `onnuriApplyChatFilter` 에, `actionUrl`(:350)은 그대로 `sessionStorage.onnuri_nav_filter` 에 넣는다. 키 필터링이 없어 `tab` 이 손실 없이 통과. `git status` 에도 없다.

**(c) "live 착지는 자동 조회 안 함" ↔ 실제 요청 — 일치.**
착지 6경로(챗 훅 4 + URL 2) 전부 `/online/search` 요청 **0건**이면서 조회 버튼 배너는 떠 있다. 탭 2 → 탭 1 통로(`#toLive`)와 빈 결과 통로도 요청 0.

**(d) `renderResult` 시그니처 — 일치.**
`(mount, data, bridge)` 3인자. 호출자는 `online.html:1020` 하나뿐(저장소 전수 확인, `merchants.html` 의 `renderResultError` 는 동명이인). 캐시버스트 `online-probe.js?v=7` 도 online.html:416 에서 올라갔다.

**(e) 사유 3종·색인 층 계약 — 회귀 없음.**
`whyBlock` 8곳/2곳/1곳 = 11곳, 접힘 요약 `확인하지 않은 나머지 11곳` 유지. 색인 층은 있으면 그리고 없으면 안 그린다.

### 3. 렌더 실측 (Playwright · 독립 작성 스크립트 · `localhost:8655` · fetch 스텁)

| 시나리오 | 실측 | 기대 |
|---|---|---|
| 기본 진입 | live 선택·`aria-selected true/false`·tabIndex 0/-1·browse 패널 hidden·보이는 칩 0·요청 0 | ✓ |
| 탭 2 카드 | 30 (count `30곳 중 30곳 표시`) | 30 ✓ |
| 탭 2 `김치` | 10 (`30곳 중 10곳 표시`) | 10 ✓ |
| 탭 2 `로봇청소기` | 13 (`… 13곳 표시 · 품목으로 찾은 13곳 포함`) | 13 ✓ |
| 탭 2 Enter | 요청 **0건** | 0 ✓ |
| 탭 1 `로봇청소기` Enter | 요청 1건 · 결과 · 색인 블록 · 다리 `이 품목을 다루는 몰 13곳 둘러보기 →` | ✓ |
| 블록 순서 | `pb-head → pb-list → pb-index → pb-bridge → pb-more → pb-foot` | 명세대로 ✓ |
| 다리 클릭 | browse 전환 · 카드 **13** · `30곳 중 13곳 표시 · 세부 미확인 4곳 제외` · 칩 `생활·청소용품` + 소분류 활성 | **버튼 곳 수 = 착지 곳 수** ✓ |
| 무카테고리(`zzqqxyw12345`) | 다리가 `몰 둘러보기에서 품목·브랜드로 찾기 →` 로 물러섬, 클릭 시 필터 없이 30곳 | ✓ |
| 챗 훅 `{q}` | live · `#pq` 채움 · 요청 0 | ✓ |
| 챗 훅 `{kind,cat,brand}` | browse · 3곳 · 칩 `가전·디지털` + 브랜드 `로보락` 활성 | 3 ✓(2026-08-27 기준값) |
| 챗 훅 `{tab:live,q}` | live · `#pq`=다이슨 · 요청 0 | ✓ |
| 챗 훅 `{tab:browse,cat:식품/김치}` | browse · 10곳 · 칩 `반찬·가공식품`+`김치` 활성 | 10 ✓ |
| 같은 페이지 훅 | live · 요청 0 | ✓ |
| URL `?tab=live&q=` / `?kind&cat=식품/김치` | live 요청 0 / browse 10곳 | ✓ |
| 실시간 회귀 — 색인 없음 | `pb-index` 미생성, 다리는 유지 | ✓ |
| 실시간 회귀 — 전부 unknown+timeout | 색인·다리 정상 | ✓ |
| 키보드 ←/→ | live→browse→live, `aria-selected` 반전, 로빙 tabIndex, 포커스 이동, 주소 `tab` 동기 | ✓ |
| IME 방어 | 1자 Enter 0건 · `isComposing` Enter 0건 · `keyCode 229` Enter 0건 · 정상 Enter 1건 | ✓ |
| 탭 2 → 탭 1(`#toLive`) | live 전환 · 태그 검색어 `다이슨` 이월 · 포커스 `#pq` · 신규 요청 0 | ✓ |
| 탭 2 빈 결과 통로 | `[상품명으로 실시간 조회하기 →]` 존재 · 클릭 시 live + 검색어 이월 + 요청 0 | ✓ |
| 모바일 390px | 3상태 전부 `scrollWidth 390 == clientWidth`, 탭 줄 41px 1줄 | ✓ |
| pageerror | 전 시나리오 **0** | ✓ |
| JS 문법 | online.html 인라인·online-probe.js `new Function` 통과 | ✓ |

### 4. 회귀 기준값·D-F1

| 항목 | 값 | 판정 |
|---|---|---|
| 온라인몰 active | 30 (쇼핑 22 + 배달 8) | 유지 |
| 검색 링크 보유 | 16 | 유지 |
| 카탈로그 items / taxonomy | 22 / 11 | 유지 |
| `index.html`·`config.js`·`data/`·`build_index.py` | 무변경 | ✓ |
| `verify_build.py`(D-F1) | 전체 통과(참고 실행) | ✓ |

### 5. 문서 정합

- `03_content_spec` S19 는 **곳 수를 적지 않는다**는 원칙을 본문에 명시하고 조회 대상 수를 쓰지 않는다. 등장하는 숫자(13·10·30)는 카탈로그 데이터에서 나오는 값이라 원칙 위반이 아니다. 몰 이름은 우체국쇼핑 1건뿐이고 `mallWide` 배지 설명이라 필요한 언급이다.
- `17_chatbot_design` navigate 계약표에 `tab` 이 선택 필드임·enum·이중 검문·"live 는 자동 조회 안 함"이 모두 적혀 있다.
- 사이드바 앵커 `#pageTabs` 는 **online.html 자기 자신만** 참조한다(저장소 전수 grep). 다른 페이지에서 `online.html#kindTabs` 로 들어오는 링크 0건 — 죽은 앵커 없음.
- **1차 게이트 지적 반영 확인**: D1·D2·D3 는 "조회 대상 수(`ProbeTargets.ALL` 이 정한다)"로 숫자를 빼는 방향으로, D4 는 클래스 javadoc 재작성으로 전부 해소됐다.

### 6. 비밀값 스캔

이번 변경의 추가된 줄에서 IP·API 키·개인키·하드코딩 비밀번호 **0건**.

### 7. 지적 목록

| # | 심각도 | 파일:줄 | 기대 vs 실제 | 담당 |
|---|---|---|---|---|
| **F-1** | **보통(코드)** | `online.html:1076~1096` `applyLanding` | 착지 창구가 `state.nationwideOnly`·체크박스를 되돌리지 않는다. **재현**: 탭 2에서 `전국 이용 가능만` 체크 → 챗 자동모드로 `{tab:"browse", kind:"delivery"}` 착지. 기대 **8곳**(깨끗한 세션), 실제 **3곳**. 챗봇은 자기 도구 결과로 "배달앱 8곳"이라 말한 뒤 3곳 화면에 착지시킨다. 같은 파일의 `goBrowseFrom`(:687)과 `resetAll`(:1079)은 이 축을 되돌리는데 `applyLanding` 만 빠져 있다. 결함 자체는 이번 변경 이전부터 있었으나(옛 `onnuriApplyChatFilter` 도 동일), 착지 창구를 새로 만들면서 **바로 옆 함수와 규칙이 갈린** 상태다 — 이 영역이 열려 있는 지금 한 줄로 맞추는 것이 맞다. URL 착지는 새 문서 로드라 영향 없다 | frontend |
| O-1 | 낮음(경화) | `online-probe.js:181` | `bridge.lead` 를 `innerHTML` 에 **이스케이프 없이** 삽입한다(`label` 은 `esc` 를 거친다). 현재는 `bridgeCopy` 가 고정 문자열 2종만 돌려주므로 안전하고, JSDoc 이 "검색어를 담지 말 것"을 경고한다. 다만 방어가 주석뿐이라 뒷사람이 곳 수 대신 검색어를 넣는 순간 XSS 가 된다. `<b>` 만 허용하는 최소 정제나 개발 모드 단언 권고 | frontend |
| O-2 | 정보 | — | `bridgeTarget` 은 여러 대분류에 걸친 품목에서 한 대분류만 넘긴다(ADR-20 이 알려진 한계로 기록). 현 46규칙에서 손실 사례는 확인되지 않았다(로봇청소기 생활·주방 13 ⊇ 가전 10) | — |

### 8. 미검증(통과로 적지 않음)

- **실서버 챗 경로 e2e**: 로컬 백엔드·`OPENAI_API_KEY` 가 없어 실제 모델이 `tab` 을 실어 보내는지는 확인하지 못했다. 서버 측은 계약 테스트로, 프론트 측은 `sessionStorage`/`onnuriApplyChatFilter` 직접 주입으로 검증했다. 배포 후 "로봇청소기 어디서 사?" 1회 라이브 확인 필요.
- **실제 실시간 조회 응답**: `/online/search` 를 스텁했다. 서버 판정 자체의 회귀는 1차 게이트와 `gradlew` 범위.

---

## 2026-09-03 (3차) — ADR-19 진행: 조회 15곳 · 사유 4종 7곳 · 색인 5곳 검증

검증 대상: 미커밋 작업트리 18파일 + 픽스처 8개.

**판정: 통과.** 게이트 7항목 전부 기대치 일치. 기능 결함 0 — 아래 지적은 전부 참고·정보 수준이며 배포를 막지 않는다.

### 1. 테스트

| 항목 | 결과 | 기대 |
|---|---|---|
| `./gradlew cleanTest test` | **158건** · 실패 0 · 오류 0 · 스킵 1 | 158 ✓ |
| 스킵 1건 | `SelfTestLiveTest`(라이브 카나리아) | 설계대로 |
| `ProbeTargetsTest` / `ProbeJudgeTest` / `OnlineSearchServiceTest` | 11 / 25 / 18 | — |
| `test_survey_probe.js` | **154건** | 154 ✓ |
| `test_index_nightly.js` | **69건** | 69 ✓ |

`ProbeTargetsTest` 가 대상 15곳·사유 4종·곳 수 1/2/3/1·합계 7 을 전부 고정한다. `isApi()` 근거 검사에 `jsonApi` 가 더해져, 선언을 지우면 테스트가 먼저 막는다.

### 2. 경계면 교차 비교

**(a) 사유 문자열 — 정확히 일치, 죽은 키 0.**

| 사유 | 서버 `EXCLUSION` | 프론트 `REASON` | `REASON_LONG` |
|---|---|---|---|
| `bot-blocked` | 1곳(사이소) | 있음 | 있음 |
| `scope-first` | 2곳 | 있음 | 있음 |
| `scope-mixed` | 3곳 | 있음 | 있음 |
| `no-static-search` | 1곳 | 있음 | 있음 |

`REASON_LONG` 키 집합 == 서버 사유 집합(4/4). 폐기된 `robots-blocked`·`not-a-probe-target` 은 **양쪽 모두에서 사라졌고**, 백엔드 전수 grep 으로 `not-a-probe-target` 을 대입하는 지점이 0곳임을 확인했다.

**(b) 조회 15곳 링크 — 화면형 12곳 코드=데이터 일치, API형 3곳 데이터 링크 비어 있지 않음.**
API 형 `hyundai-ezwel-onnuri`(`{qq}`) · `onnuri-5iljang`(formBody) · `hyundai-home-shopping`(jsonApi) 셋 다 데이터에 사람이 볼 주소가 있다. **현대홈쇼핑 링크를 실제로 열어 확인했다** — `sectId=3132118` 로 `200 · text/html · 422,478B`, 본문에 온누리 기획전 상품명이 들어 있고 `3132118` 이 92회 등장한다. **JSON 이 아니라 전용관 화면이 맞다**(조회에 쓰는 `/md/api/cache?...` 와 다른 주소).

**(c) 템플릿이 빈 몰 → 홈(기획전) 링크.**
템플릿이 빈 쇼핑몰 7곳 = 비대상 7곳과 정확히 같은 집합이다. `searchUrlFor(t=null, p, q)` 가 `p.url()` 로 떨어지고, 7곳 모두 `url` 이 채워져 있다(사이소·11번가·롯데ON·공영쇼핑은 기획전 상세, 인어교주는 `/store/onnuri`).

**(d) 색인 5곳 ∩ 실시간 15곳 = ∅.**
레시피 `onnuri-noljang`·`tpirates`·`11st-onnuri-market`·`lotte-on-sangsaeng-store`·`gongyoung-shopping` — 교집합 0, 5개 id 전부 데이터에 존재. 런타임에서도 `IndexJudge` 가 `ProbeTargets.ids()` 를 다시 뺀다(이중 방어).

**(e) 쇼핑 22 = 15 + 7, 양방향 고아 0.**
대상인데 데이터에 없는 id 0 · 쇼핑인데 어느 쪽에도 없는 id 0 · 제외 사전에 있는데 쇼핑이 아닌 id 0.

**(f) 색인 층 계약 — 회귀 없음.** `platformCount 5` 층이 정상 렌더되고, 빈 층이면 블록이 사라진다.

**계약 완화의 안전성**: `searchUrlFor` 가 `{q}` 없는 템플릿도 쓰게 됐지만, `ProbeTargetsTest` 가 API 몰에 대해 **비어 있지 않음**을 여전히 요구한다. 2026-09-02 사고(조회용 JSON 이 이용자 링크로 나감)를 막던 방어는 그대로다.

### 3. 렌더 실측 (Playwright · 독립 작성 · fetch 스텁 = 서버 record 형태)

| 시나리오 | 실측 | 기대 |
|---|---|---|
| 조회 목록 | 15행 | 15 ✓ |
| 비대상 접힘 | `확인하지 않은 나머지 7곳` · 행 7 · 배지 7 | 7 ✓ |
| 사유 블록 순서·곳 수 | `1곳 bot-blocked` → `2곳 scope-first` → `3곳 scope-mixed` → `1곳 no-static-search` | **1/2/3/1 성격순** ✓ |
| 4종 문구 | 4줄 전부 `REASON_LONG` 축자 일치 | ✓ |
| 배지 4종 | `몰이 자동 접근을 막아 둠` / `시장·주소를 먼저 고르는 구조` / `온누리 범위로 좁힐 수 없음` / `자동 조회가 안 되는 구조` | ✓ |
| 원시 키 노출 | 화면 텍스트에 영문 사유 키 **0** | ✓ |
| **미지 사유 2종 병합** | 사전에 없는 `future-a`·`future-b` 를 보냈을 때 → 폴백 문장 **한 줄 `2곳`**, 배지는 생략 | 병합 ✓ |
| 색인 층 | 있으면 `pb-index` 생성, 없으면 미생성 | ✓ |
| 블록 순서 | `pb-head → pb-list → pb-index → pb-bridge → pb-more → pb-foot` | 명세대로 ✓ |
| 탭 회귀 | 기본 live · browse 패널 hidden · 카드 30 · 탭 2 로봇청소기 13곳(`품목으로 찾은 13곳 포함`) · 탭 2 요청 0 | ✓ |
| 다리 회귀 | 버튼 `13곳` → 클릭 후 카드 **13** · 칩 `생활·주방` 활성 · `세부 미확인 4곳 제외` | 곳 수 일치 ✓ |
| 챗 착지 회귀 | `{tab:live,q}` → live·검색어 채움·요청 **0** / `{tab:browse,cat:식품/김치}` → browse·10곳 | ✓ |
| 모바일 390px | `scrollWidth 390 == clientWidth`, 사유 4줄 정상 | ✓ |
| pageerror | 전 시나리오 **0** | ✓ |
| JS 문법 | `online-probe.js`·online.html 인라인 `new Function` 통과 | ✓ |

캐시버스트 `online-probe.js?v=11` · `dataVersion 2026-09-03` 모두 올라갔다(데이터가 바뀌었으므로 필요한 동반 상향).

### 4. 크롤러 (단계 F)

- **robots 금지 경로 미사용 — 코드로 확인.** 공영쇼핑 레시피는 `/exhibition/getEbtDetail.do`·`/goods/getGoodsUnitInfo.do` 만 부른다. robots 가 금지한 `/search/`·`/api/` 는 코드에 없다. 11번가는 `plan.11st.co.kr/plan/front/...`(그 호스트 robots 의 `Allow: /plan/front/` 안). 롯데ON 은 요청이 `pbf.lotteon.com`(robots 404) **1건**뿐이고 `www.lotteon.com` 주소는 Referer 헤더와 저장할 상품 주소로만 쓰이며 **가져오지 않는다**.
- **호스트당 간격**: `await pace(hostname, recipe.intervalMs)` — 롯데ON 3000ms, 나머지 1000ms. 각 몰의 `Crawl-delay` 와 맞다.
- **playwright 부재 동작 — 실행으로 확인.**

| 실행 | 결과 |
|---|---|
| `--ids onnuri-noljang`(브라우저 레시피만) | 종료코드 **2** · `대상이 전부 브라우저 레시피라 할 일이 없습니다` · 네트워크 0 |
| `--ids gongyoung-shopping --limit 2`(정적) | 종료코드 **0** · 실제로 상품명 50건 수집 |

정적 경로는 playwright 없이 돈다. 두 번째 실행이 `ok=false` 로 끝난 것은 내가 준 `--limit 2` 때문에 450종 중 50종만 받아 **50% 가드가 걸린 것**이며 설계대로다(운영 기본값은 `pageLimit 30`).

### 5. 회귀 기준값·D-F1

| 항목 | 값 | 판정 |
|---|---|---|
| 온라인몰 active | 30 (쇼핑 22 + 배달 8) | 유지 |
| 검색 링크 보유 | 16 → **15** | 의도된 변경(현대홈쇼핑 +1 · 공영쇼핑·사이소 −2) |
| 카탈로그 items / taxonomy | 22 / 11 | 유지 |
| `index.html`·`build_index.py`·`chat-widget.js` | 무변경 | ✓ |
| `verify_build.py`(D-F1) | 전체 통과(참고 실행) | ✓ |

### 6. 문서 정합

- **ADR-19 상태 문구 ↔ 코드 일치.** "진행 · 1차 3곳 → 14곳 · 2차 현대홈쇼핑 편입 15곳 · `scope-mixed` 신설 · 색인 2→5곳 · 색인 크롤은 robots 준수"가 전부 코드와 맞는다.
- 카나리아 요청량은 `19_online_probe:241`·`DEPLOY.md:478` 모두 **숫자를 적지 않고** `조회 대상 수 × 2` 로 쓴다(1차 게이트 지적 반영분 유지). `17_backend_notes` 의 `28 → 30건/일` 은 15×2 로 정확하다.
- `17_backend_notes` 의 `EXCLUSION 재편 — 8곳`(:294)은 같은 날 **1차(14곳) 기록** 안에 있고, 뒤에 `2026-09-03 (2) — 현대홈쇼핑 편입(15곳)`·`사유 재편 — 비대상 7곳` 절이 따로 있다. 날짜순 append 로그라 정합 문제 아님.
- `03_content_spec` S19 는 곳 수를 적지 않는 원칙을 유지한다.

### 7. 비밀값 스캔

변경의 추가된 줄에서 IP·API 키·개인키·하드코딩 비밀번호 **0건**.

### 8. 지적 목록 (전부 참고 수준 — 기능 결함 0)

| # | 심각도 | 파일:줄 | 내용 |
|---|---|---|---|
| I-1 | 낮음(문서) | `backend/DEPLOY.md:252` | 단계 F 설명이 `**5곳**` 을 본문에 박았다. 바로 아래 표가 5곳을 열거하므로 혼자 조용히 늙지는 않지만, 이 저장소가 방금 세운 "곳 수는 코드가 정한다" 원칙과는 어긋난다. 레시피가 늘 때 표만 고치고 이 줄을 놓치면 거짓이 된다 |
| I-2 | 정보(주석) | `online-probe.js:53` | 주석의 날짜가 `2026-09-02(ADR-19)` 인데 이 변경은 **2026-09-03** 이다(ADR-19 진행일). 동작 무영향 |
| I-3 | 정보(데이터) | `data/online_platforms.json` `hyundai-home-shopping` | 템플릿이 값 없는 `&dispOrd` 로 끝난다. 실제로 열어 보니 200·정상 렌더라 무해하지만 잘린 URL 처럼 보인다 |
| I-4 | 정보(설계) | `OnlineSearchService.searchUrlFor` | 완화 후 **비어 있지 않은 템플릿은 무엇이든 그대로** 이용자 링크가 된다. API 몰은 계약 테스트가 비어 있지 않음을 지키지만, 비-API 몰의 데이터에 엉뚱한 값이 들어가면 막을 장치가 없다. 현재 데이터에는 해당 사례 0 — 데이터 큐레이션에 의존한다는 점만 기록 |

### 9. 미검증(통과로 적지 않음)

- **라이브 카나리아 30건**: `SelfTestLiveTest` 는 스킵됐다. 신규 4곳(굿데이·인더마켓·팔도·현대홈쇼핑)의 실측 기대치는 **배포 후 확인 대상**이다. 특히 현대홈쇼핑 present 질의 `세트`(20건)와 인더마켓의 "없음 응답이 더 큼" 특성은 라이브에서 한 번 봐야 한다.
- **단계 F 전체 실행**: 브라우저 레시피 2곳은 playwright 미설치로 돌리지 못했다. 정적 3곳 중 공영쇼핑 1곳만 축소 실행했다.
- **V8 색인 테이블 실 질의**: 로컬 Postgres 미기동(1차 게이트와 동일).

---

## 2026-09-03 (4차) — ADR-19 3차 정정: 조회 17곳 · 비대상 5곳 · 색인 3곳 검증

검증 대상: 미커밋 작업트리 14파일 + 픽스처 4개. **프론트는 무변경**(`online.html`·`online-probe.js`·`chat-widget.js` 모두 `git status` 에 없다) — 곳 수를 서버 값으로 받는 설계가 실제로 프론트 수정 없이 15→17 을 흡수했다.

**판정: 통과.** 게이트 7항목 전부 기대치 일치, 기능 결함 0. 지적 1건(F-1)은 운영 관측 갭이며 배포를 막지 않는다.

### 1. 테스트

| 항목 | 결과 | 기대 |
|---|---|---|
| `./gradlew cleanTest test` | **162건** · 실패 0 · 오류 0 · 스킵 1 | 162 ✓ |
| 스킵 1건 | `SelfTestLiveTest`(라이브 카나리아) | 설계대로 |
| `ProbeJudgeTest` | 25 → **29** | +4 |
| `test_survey_probe.js` | **154건** | 154 ✓ |
| `test_index_nightly.js` | **68건** | 68 ✓ |

### 2. 경계면 교차 비교

**(a) 사유 4종 — 정확히 일치, 죽은 키 0.**

| 사유 | 서버 `EXCLUSION` | 프론트 `REASON_LONG` |
|---|---|---|
| `bot-blocked` | 1곳(사이소) | 있음 |
| `scope-first` | 2곳 | 있음 |
| `scope-mixed` | **1곳(롯데ON)** | 있음 |
| `no-static-search` | 1곳(인어교주) | 있음 |

`scope-mixed` 가 3곳 → 1곳으로 줄었지만 **롯데ON 이 남아 사유 자체는 살아 있다** — 프론트 4종 유지가 맞다. 키 집합 == 서버 사유 집합, 죽은 키 0 · 누락 0.

**(b) 링크 — 17곳 전수 대조 + API형 5곳 실제 확인.**
화면형 12곳은 코드 URL = 데이터 템플릿. API형 5곳은 조회 URL 과 이용자 링크가 의도적으로 다르다.

| API 몰 | 이용자 링크 | 확인 |
|---|---|---|
| 현대이지웰 | `…/onnuri/main/searchPage?…` | 화면 |
| 온누리5일장 | `www.onnuri5.com/shop/search_result_onnuri?…` | 화면 |
| 현대홈쇼핑 | `…/md/dpa/searchSpexSectItem?sectId=3132118…` | 3차에서 실측(200·text/html·422KB) |
| **11번가** | `search.11st.co.kr/pc/total-search?kwd={q}&tabId=TOTAL_SEARCH&filters=ONNURI` | **브라우저로 열어 확인** |
| **공영쇼핑** | 템플릿 비움 → `p.url()` = 기획전 상세 | **열어 확인**(200·text/html·303,354B·본문 `온누리샵`) |

**11번가 링크를 실제로 렌더해 봤다** — `김치` 질의로 `200`, 페이지에 `선택된 필터 / 온누리상품권 / 삭제 / 초기화` 칩이 실제로 걸려 있고 본문에 `온누리` 124회. JSON 이 아니라 필터가 적용된 검색 화면이 맞다.

**(c) 원문 대조 경로 — 화면 HTML 몰에 적용되지 않는다.**
`ProbeJudge` 의 새 절은 `full.contains(plain) || (t.isApi() && html.contains(plain))` 로 **`isApi()` 게이트가 걸려 있고**, 전용 회귀 테스트 `화면_HTML_몰은_원문_매칭을_쓰지_않는다` 가 이를 고정한다.
**기존 API 몰 판정 불변을 픽스처로 직접 검증했다** — 이 절이 만들 수 있는 유일한 위험은 '있음' 응답을 `none` 으로 뒤집는 것인데, `*-hit.html` 픽스처 12개 전수에서 **각 몰의 없음-문구가 원문에 하나도 없다.** 즉 어떤 몰에서도 판정이 바뀔 수 없다.

**(d) 색인 3곳 ∩ 실시간 17곳 = ∅.** 레시피 `onnuri-noljang`·`tpirates`·`lotte-on-sangsaeng-store`, 교집합 0.

**(e) 쇼핑 22 = 17 + 5, 양방향 고아 0.**

**(f) 색인 층 계약 회귀 없음.** `platformCount 3` 층 정상 렌더.

**계약 완화(“template 또는 url 중 하나”)도 안전하다** — `ProbeTargetsTest` 가 API 몰에 대해 둘 중 하나가 비어 있지 않음을 여전히 요구한다. 공영쇼핑처럼 템플릿을 일부러 비운 몰도 `url` 이 채워져 있어 조회용 JSON 이 링크로 나갈 길이 없다.

### 3. 렌더 실측 (Playwright · 프론트 무변경 회귀)

| 항목 | 실측 | 기대 |
|---|---|---|
| 조회 목록 | 17행 | 17 ✓ |
| 비대상 접힘 | `확인하지 않은 나머지 5곳` · 행 5 · 배지 5 | 5 ✓ |
| 사유 곳 수·순서 | `1곳 bot-blocked` → `2곳 scope-first` → `1곳 scope-mixed` → `1곳 no-static-search` | **1/2/1/1 성격순** ✓ |
| 4종 문구 | `REASON_LONG` 축자 일치 | ✓ |
| 원시 키 노출 | 0 | ✓ |
| 색인 층·블록 순서 | `pb-head → pb-list → pb-index → pb-bridge → pb-more → pb-foot` | ✓ |
| 탭·다리 회귀 | 기본 live · 다리 `13곳` → 클릭 후 카드 **13**·`세부 미확인 4곳 제외` | ✓ |
| 모바일 390px | `scrollWidth 390 == clientWidth` | ✓ |
| pageerror | **0** | ✓ |

### 4. 크롤러 (단계 F)

- **삭제된 두 레시피 잔존 0.** `crawl11st`·`crawlGongyoung`·`plan.11st.co.kr`·`gongyoungshop` 문자열이 코드에서 전부 사라졌다(남은 한 줄은 "여기 없다"고 설명하는 주석).
- **playwright 부재 동작 — 실행으로 확인.**

| 실행 | 결과 |
|---|---|
| `--ids onnuri-noljang,tpirates`(브라우저만) | 종료코드 **2** · 네트워크 0 |
| `--ids lotte-on-sangsaeng-store`(정적 1곳) | 종료코드 **0** · 실제 상품명 수집 |

- `nightly_update.py` 의 단계 F 설명·docstring 이 3곳으로 함께 갱신됐고, `DEPLOY.md` 는 곳 수를 **`RECIPES 가 정한다`** 로 바꿔 3차 지적 I-1 을 해소했다.

### 5. 회귀 기준값·D-F1

| 항목 | 값 | 판정 |
|---|---|---|
| 온라인몰 active | 30 (쇼핑 22 + 배달 8) | 유지 |
| 검색 링크 보유 | 15 → **16** | 의도된 변경(11번가 +1) |
| 카탈로그 / taxonomy | 22 / 11 | 유지 |
| `index.html`·`build_index.py`·`online.html`·`online-probe.js`·`chat-widget.js` | 무변경 | ✓ |
| `verify_build.py`(D-F1) | 전체 통과(참고 실행) | ✓ |
| `dataVersion` | `2026-09-03.2` | 같은 날 두 번째 데이터 변경 — 동반 상향 정확 ✓ |

### 6. 문서 정합

ADR-19 3차 정정(`16:128`)이 코드와 정확히 맞는다 — 17곳 · 비대상 5곳(1/2/1/1) · 색인 3곳 · 롯데ON 보류 사유 2가지. `19_online_probe` 6-10-1 절과 대조표도 일치. `DEPLOY.md` 의 단계 F 곳 수는 코드가 정한다는 표현으로 바뀌었다.

### 7. 비밀값 스캔

추가된 줄에서 IP·API 키·개인키·하드코딩 비밀번호 **0건**.

### 8. 지적 목록

| # | 심각도 | 위치 | 기대 vs 실제 |
|---|---|---|---|
| **F-1** | **낮음(운영)** | `backend/tools/nightly_update.py:69~72` `ROBOTS_WATCH_IDS` | 단계 E robots 감시가 **조회 대상 17곳 중 9곳만** 덮는다. 빠진 8곳: 11번가·공영쇼핑·현대홈쇼핑·현대이지웰·온누리5일장·온누리쇼핑·꾹AI·팔도시장. `DEPLOY.md:525` 는 `차단으로 바뀌었으면 즉시 대상에서 뺀다` 를 운영 규칙으로 적어 두었는데 **그 방아쇠가 이 8곳에는 없다.** ADR-19 가 robots 무시의 균형점으로 "정책이 더 강해지거나 항의가 오면 즉시 끈다"를 들었고 그 관측 수단이 바로 이 목록이다. 3차 게이트 시점 6곳에서 이번에 **8곳으로 벌어졌다**(11번가·공영쇼핑 편입분). 기능 결함은 아니고 관측 공백이다 |
| I-1 | 정보(주석) | `backend/tools/nightly_update.py:63~68` | 주석이 아직 `단계 F(전일 색인)가 여는 2곳` 이라고 말한다. 단계 F 는 이제 3곳을 열고, 롯데ON 은 스캐너 한계 때문에 **의도적으로** 감시에서 뺀 것이다(`DEPLOY.md:333` 에 사유가 있다). 주석에 그 사실이 없어 목록을 고칠 사람이 실수로 넣기 쉽다 |
| I-2 | 정보(데이터) | `data/online_platforms.json` `hyundai-home-shopping` | 3차 게이트 I-3 이월 — 템플릿이 값 없는 `&dispOrd` 로 끝난다. 열어 보면 정상이라 무해 |

3차 게이트 지적 중 I-1(DEPLOY 곳 수)과 I-2(`online-probe.js` 주석 날짜)는 **해소를 확인했다.**

### 9. 미검증(통과로 적지 않음)

- **라이브 카나리아 34건**(17곳 × 2): `SelfTestLiveTest` 는 스킵됐다. 신규 2곳(11번가 `"groupName":"noSearchData"` · 공영쇼핑 `<rsltYn>N</rsltYn>` 원문 대조)의 실측 기대치는 **배포 후 확인 대상**이다. 특히 공영쇼핑은 이번에 새로 만든 원문 대조 경로에 의존하므로 라이브에서 한 번 봐야 한다.
- **단계 F 전체 실행**: playwright 미설치로 브라우저 레시피 2곳은 못 돌렸다. 정적 1곳(롯데ON)만 실행했다.
- **V8 색인 테이블 실 질의**: 로컬 Postgres 미기동(1차 게이트와 동일).
