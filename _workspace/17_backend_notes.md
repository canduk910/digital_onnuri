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
