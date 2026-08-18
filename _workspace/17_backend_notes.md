# 17. 백엔드 노트 — API 계약·마이그레이션·검증 기록

backend-dev 산출물 로그. 계약 변경(필드·의미)·스키마 마이그레이션·로컬 검증 결과를 시간순으로 남긴다.

## 2026-08-12 — 온라인 플랫폼 API 신설 + 야간 배치 (ADR-14)

### 마이그레이션
- **V5__online_platform.sql** — `online_platform` 테이블 신설. `data/online_platforms.json`의 items와 의미상 1:1.
  - 컬럼: id(PK 슬러그), post_no(공식 postNo), ord(공식 ordNo), kind(shopping/delivery), name, summary, note, url, region_limited, source_url, collected_on, status(active/removed).
  - 인덱스: `idx_online_platform_ord`, `idx_online_platform_post_no`.
  - JPA 엔티티 없음(JdbcTemplate 직조회, report 패턴) → ddl-auto=validate 영향 없음.

### API 계약 (프론트 전달용 — online.html·terms.html JSON 폴백과 매핑)
- `GET /api/online/platforms`, `POST /api/online/platforms` — 동일 응답(필터 없음, POST는 본문 컨벤션 통일).
- 응답 shape:
  ```
  { "meta": { "collected_on": "YYYY-MM-DD"|null, "source": "…", "sourceUrl": "…" },
    "items": [ { "id","no","kind","name","summary","note","url",
                 "regionLimited","sourceUrl","collectedOn","status" } ] }
  ```
- **직렬화 키 최종본(프론트가 매핑할 정확한 이름)**:
  - meta: `collected_on`(snake — JSON 파일 meta와 동일), `source`, `sourceUrl`.
  - item: `id`, `no`(=post_no), `kind`, `name`, `summary`, `note`, `url`, `regionLimited`, `sourceUrl`, `collectedOn`, `status`. (item은 전부 camelCase.)
  - ⚠ meta만 `collected_on`(snake)이고 item은 `collectedOn`(camel) — 프론트 JSON 폴백(data/online_platforms.json)의 기존 키와 정확히 일치시킨 결과다. 바꾸지 말 것.
- 정렬: `ord ASC NULLS LAST, name`. **removed 포함 전체 반환** — 프론트가 `status`로 필터.
- `meta.collected_on` = active 항목의 `min(collected_on)`(확인 안 한 항목 날짜를 스탬프에 올리지 않는다). active 없으면 null.
- 계약 고정: `OnlineContractTest`(record 컴포넌트명 + 직렬화 키). 이름 변경 시 프론트와 한 변경 단위로.

### 적재기·배치
- **tools/load_online_platforms.py** — JSON → online_platform 초기 upsert(멱등, id 기준). ord=배열 순서, post_no=null(배치 첫 실행이 채움), 큐레이션 필드 포함 전체 적재.
- **tools/nightly_update.py** — 서버 cron(00:30) 배치. 단계 A(가맹점 stage-swap)·B(온라인 upsert)·C(RAG). 스킵 플래그 `--skip-merchants/--skip-online/--skip-rag`, 로컬 스왑 검증용 `--no-collect`(재수집 생략). 상세 설계는 ADR-14.

### 로컬 검증 결과 (2026-08-12, docker onnuri-db)
- V5 Flyway 적용 성공(앱 부팅 UP, JPA validate 통과).
- API GET/POST: 30건, meta.collected_on 정확, camelCase 키 계약 일치, ord 정렬 보존.
- 온라인 배치(실측 공식 API): 30건 수집→30 갱신(이름 매칭), note·region_limited·source_url·id 보존, post_no 채움(11번가 31·cyso 30 등), collected_on 갱신.
- 가맹점 stage-swap: `--no-collect` 연속 2회 멱등(인덱스명 `merchant_stage_*`→`merchant_*` 정규화 후 재실행 충돌 없음, 잔여 stage 테이블 없음). 회귀 유지: 서울 29,462/인천 7,236/경기 29,522/부산 12,514, 강남구 761, 개포동 145, 수원 팔달구 1,241.
- 가드 발동 실증: 부산 12,514→100(축소본) 주입 시 "가드 위반 +99.2%"로 스왑 중단, 기존 DB 무결.
- `./gradlew test` 전체 녹색.

## 2026-08-18 — 관리자 로그인 API 신설 (POST /api/admin/login)

### 배경
admin-report.html 진입에 48자리 `APP_ADMIN_KEY`를 직접 입력해야 했다. 기억 가능한 비밀번호로
로그인해 키를 받아오는 경로를 추가한다. 키 자체의 쓰임(`X-Admin-Key` 헤더 → `POST /api/reports/{id}/status`)은 불변.

### API 계약 (프론트 admin-report.html과 합의된 고정값)
- `POST /api/admin/login`, 요청 body: `{"password":"…"}` (Content-Type: application/json)
- **200**: `{"key":"<APP_ADMIN_KEY 값>"}` — 프론트가 읽는 키 이름은 `key` 하나뿐.
- **403**: 비번 불일치 / `app.admin.password` 미설정 / `app.admin.key` 미설정. body `{"message":"비밀번호가 올바르지 않습니다."}`
  — **실패 이유를 구분하지 않는다**(설정 상태가 응답으로 새지 않게).
- **429**: rate limit 초과. body `{"message":"시도가 너무 잦습니다…"}`
- 실패 응답에는 키를 절대 담지 않는다(계약 테스트로 고정).

### 구현
- `gift/onnuri/admin/AdminController.java` (신규) — 패키지-당-기능 관례(report·visit·meta·news) 유지.
  스키마 변경 없음(마이그레이션 없음), 엔티티 없음.
- rate limit: `ChatConfig`에 `adminLoginRateLimiter` 빈 추가 — **IP당 분 5회·일 30회**
  (`app.admin.rate-per-minute:5` / `rate-per-day:30`). RateLimiter 빈이 3개가 되었고
  주입은 기존과 동일하게 **파라미터명**으로 선택된다(reportRateLimiter 선례).
  한도 검사가 비번 검증보다 **먼저** — 비번 미설정 상태에서도 대입 시도가 카운트된다.
- 비번 대조 `AdminController.authenticate()`: `MessageDigest.isEqual`(UTF-8 바이트) — 길이가 달라도
  조기 반환하지 않는 상수시간 비교. 설정값이 null/blank면 무조건 거절(= 기능 비활성).
- 비밀번호는 로그에 남기지 않는다(로그 grep 0건 실증).
- `application.yml`: `app.admin.password: ${APP_ADMIN_PASSWORD:}` 추가(키와 동일 패턴 — 서버 .env에만 실제 값).
- CORS 무변경 — 기존 `CorsConfig`가 `/api/**` + `allowedHeaders("*")`로 이미 덮는다(프리플라이트 실측 확인).

### 테스트 (`src/test/java/gift/onnuri/admin/AdminLoginContractTest.java`, 7개)
스프링 컨텍스트 없이 컨트롤러를 직접 조립한다 — 이 저장소 테스트는 DB 없이 도는 것이 규약.
정상 비번 200+key / 오답 403(키 미노출) / 비번 미설정 403 / 키 미설정 403 /
분당 한도 초과 429(다른 IP는 영향 없음) / 본문 없음·빈 body 403(500 아님) / authenticate 경계값.

### 검증 결과 (2026-08-18)
- `./gradlew test` **44개 전부 녹색**(기존 37 + 신규 7).
- 로컬 부팅 실측(포트 8099, docker onnuri-db): 정답 200 `{"key":…}` / 오답 403 / 빈 본문 403 /
  6번째 요청 429(정답 비번이어도) / `APP_ADMIN_PASSWORD` 미설정 재기동 시 정답 비번도 403.
- CORS 프리플라이트: `Origin: https://onnuri.koscomlabor.cloud` → 200,
  `Allow-Methods: GET,POST`, `Allow-Headers: content-type`.
- 로그에 비밀번호·키 문자열 노출 0건.

## 2026-08-18 — F-6/F-7 수정: XFF 스푸핑에 의한 rate limit 우회 차단

### 결함 (dev-qa 18_devqa_report.md F-6)
`clientIp()`가 `X-Forwarded-For`의 **첫 값**을 IP로 썼다. 첫 값은 클라이언트가 그대로 실어 보낼 수
있어, 매 요청 다른 값을 넣으면 rate limit 버킷이 매번 갈려 한도가 통째로 무력화된다.
dev-qa 재현: 오답 20회를 매번 다른 XFF로 → **403 20회, 429 0회**.

### 수정
- **`gift/onnuri/web/ClientIp.java` 신규** — XFF의 **마지막 값**(공백·빈 요소는 건너뜀), XFF 없거나
  전부 공백이면 `remoteAddr`. 프록시는 받은 XFF *뒤에* 자기가 본 TCP 상대 주소를 덧붙이므로
  마지막 값만이 위조 불가다.
- **전제 확인(코드 주석에도 기록)**: 프로덕션은 Caddy 1홉 — `deploy/Caddyfile`이
  `reverse_proxy app:8080`뿐이라 XFF 재작성이 없고, `docker-compose.prod.yml`의 app은
  `expose`만 있어(`ports` 없음) Caddy 우회 직접 접근 경로가 없다.
  ⚠ **앞단에 CDN·LB를 추가하면 마지막 값이 그 중계자 IP가 되어 전 이용자가 한 버킷을 공유한다**
  (정상 이용자가 서로의 한도에 걸린다). 홉이 늘면 ClientIp와 Caddy trusted_proxies를 함께 재설계할 것.
- **호출부 3곳을 공용 헬퍼로 통일**: `AdminController`, `ReportController`,
  그리고 **`ChatController`** — 세 번째 지점은 리더 지시·F-6 지적 어디에도 없었으나 동일 결함이었고,
  이 한도는 "공개 사이트에 노출된 유일한 LLM 비용 통제 장치"(RateLimiterTest 주석)라 위험도가 가장 높았다.
  이제 `X-Forwarded-For` 문자열은 `ClientIp` 한 곳에만 존재한다(grep 확인).
- **F-7 로그**: 로그인 403·429에 한해 WARN 1줄(`관리자 로그인 실패/한도 초과 — ip=…`).
  시각은 로그 포맷이 붙인다. 비밀번호·시도값은 남기지 않는다.
- 부수: `ChatController`의 낡은 주석 "빈 2개(chat/report)" → "빈 3개(chat/report/adminLogin)".

### 테스트 (52개, +8)
- **`web/ClientIpTest`(신규 6)**: XFF 부재→remoteAddr / 단일 값 / 다중은 마지막 / 위조 앞값이 달라도
  같은 키 / 공백·빈 요소 건너뜀 / XFF 공백뿐이면 remoteAddr.
- **`AdminLoginContractTest`(+2, 총 9)**: XFF 첫 값을 매번 바꿔도 6회째 429(F-6 회귀) /
  프록시 뒤에서도 실제 클라이언트가 다르면 버킷 분리.

### 검증 결과 (2026-08-18, 로컬 8099 + docker onnuri-db)
- `./gradlew cleanTest test` **52개 녹색**(기존 44 + 신규 8), 실패·에러 0.
- **F-6 공격 재현이 차단됨**: XFF 첫 값을 1~8로 바꿔가며 오답 → **1~5회 403, 6~8회 429**
  (수정 전에는 429가 영원히 나지 않았다). 실제 IP가 다른 클라이언트(198.51.100.8)는 정상 200.
- 다른 두 한도도 동일 공격에 견딤: 제보 1·2회 200 → 3·4회 **429**(분 2회), 챗 10회 통과 → 11회 **제한**(분 10회).
- F-7 로그 실측: 실패 5줄·한도 초과 3줄, 각 줄에 시각과 ip만. 로그 전체에서
  비밀번호·시도값("brute")·"password"·관리자 키 문자열 **0건**.
- 검증 후 정리: 임시 제보 2행 삭제(report 0건), 앱 종료.

### 2026-08-18 마무리 (dev-qa 2차 F-9·F-10)
- **F-9**: DEPLOY.md 1홉 경고에 한 줄 추가 — `app` 서비스에 `ports:`를 붙여 직접 노출하면
  Caddy를 거치지 않은 요청이 XFF를 통째로 지어낼 수 있어 F-6이 재발한다. `expose: 8080` 유지.
- **F-10**: 로그인 **성공**에도 로그 1줄(INFO, `관리자 로그인 성공 — ip=…`). 실패만 남기면
  대입 시도가 끝내 뚫렸는지를 로그로 알 수 없다. 키·비밀번호는 남기지 않는다.
- 검증: `./gradlew cleanTest test` **52개 녹색**. 실측 로그 — 실패 WARN·성공 INFO 각 1줄에 시각과 ip만,
  비밀번호·관리자 키·"password" 문자열 0건. 앱 종료 완료.
