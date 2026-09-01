---
name: dev-testing
description: "개발팀 TDD 워크플로와 검증 절차 — 백엔드 JUnit 테스트 작성·실행(./gradlew test), 프론트 Playwright 검증 시나리오, API↔프론트 경계면 교차 비교, 회귀 기준값 카운트표 대조. 백엔드·프론트 코드를 변경하거나, 테스트를 작성·실행하거나, 배포 전 개발 검증을 하거나, API와 프론트 결과가 다르다는 의심이 들 때 반드시 이 스킬을 사용할 것. 재검증·회귀 확인·기준값 갱신도 이 스킬. (콘텐츠·문구·출처 검증은 guide-verification — 그쪽은 이 스킬이 아니다)"
---

# 개발 테스트 — TDD와 경계면 검증

이 시스템의 결함은 500 에러가 아니라 **조용히 틀린 숫자**로 나타난다. 그래서 "돌아간다"가 아니라 "기준값과 일치한다"가 통과 조건이다.

## TDD 사이클 (기능 변경의 기본 순서)

1. **Red** — 기대 동작을 실패하는 테스트로 먼저 적는다.
   - 백엔드: `backend/src/test/java/...` JUnit. 순수 로직(SearchQuery·정규화)은 단위로, 필터·집계는 통합으로.
   - 프론트: 통과 조건을 Playwright로 검증 가능한 형태(기준 카운트·DOM 상태)로 정의한다.
2. **Green** — 테스트를 통과시키는 최소 구현.
3. **Refactor** — 테스트 녹색을 유지하며 정리. 구조 변경(아키텍트 리팩토링 포함)은 이 단계에서만.

테스트 없는 기능 변경은 dev-qa가 되돌려보낸다. 이유: 이중 소스(API/JSON) 구조에서 테스트 없는 변경은 폴백 순간의 불일치를 잠복시킨다.

## 백엔드 테스트 실행

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home) ./gradlew test
```

- 단위 테스트(스프링 컨텍스트 불필요)를 우선한다 — 빠르고 DB 없이 돈다.
- 통합 테스트는 로컬 Docker Postgres(backend/docker-compose.yml) 기동을 전제. DB가 없으면 `@SpringBootTest` 테스트는 건너뛰게 태그하고, 리포트에 "통합 미실행"을 명시한다.
- 완료 조건: `./gradlew test` 녹색. 실패 상태로 커밋·인계 금지.

## 프론트 검증 (Playwright)

로컬 정적 서버(`python3 -m http.server 8655`) + 필요 시 로컬 백엔드(bootRun) 기동 후:

- 캐시 무력화 쿼리(`?v=...`)로 로드하고, 충분한 로드 대기 후 DOM 값을 읽어 기준과 대조한다.
- JS 문법 사전 검사: `node -e "new Function(<마지막 script 추출>)"` — 수정 직후 즉시.
- index.html을 만졌다면 build_index.py 재실행 로그의 **"리터럴 </ = 0"**(D-F1)이 선행 조건.

## 경계면 교차 비교 (이 프로젝트 고유의 핵심 검증)

API 응답과 프론트 소비 코드를 **동시에 열고** 의미를 비교한다. 체크리스트:

| 경계면 | 서버 측 | 프론트 측 | 어긋나면 |
|---|---|---|---|
| 필드명 | MerchantView.marketType | normItem(): marketType→market_type | 시장유형 태그 사라짐(에러 없음) |
| 동 미상 센티넬 | SearchQuery.UNKNOWN_DONG "동 미상" | UNKNOWN_DONG "동 미상" | 동 필터 무결과 |
| bounds 의미 | hasBounds→지역필터 대체·region 무시 | MAP_BOUNDS→regionParams region 생략 | 영역 검색 카운트 상이 |
| 필터 규칙 | MerchantSpecs(digital=card∨qr, q like 3필드) | jBase/jFull 동일 규칙 | 폴백 순간 숫자 널뜀 |
| 상한 | app.map.max-markers 3000 | MAP_MAX 3000 | truncated 판정 불일치 |

**양쪽 폴백 대조 실행**: 같은 질의를 API(`curl -G $D/api/merchants ...`)와 로컬 JSON 계산(node/python으로 data/*.json 필터)으로 각각 돌려 total이 일치해야 통과.

## 회귀 기준값 카운트표

데이터 수집일 기준의 정답표. **기준값 변경은 버그가 아니라 데이터 변동일 수 있다** — 로컬 `data/merchants/*.json`을 세어 서버와 같으면 데이터 변동(표를 갱신), 다르면 버그.

| 질의 | 기준값 (2026-09-01 수집분) |
|---|---|
| 서울 전체 | 29,728 |
| 인천 / 경기 / 부산 전체 | 7,331 / 30,021 / 12,720 |
| 서울 강남구 | 760 |
| 서울 강남구 개포동 | 144 |
| 경기 수원시 팔달구 | 1,260 |
| 부산 해운대구 | 686 |
| 경기 brand=GS더프레시 | 8 |
| bounds(37.49~37.51, 127.02~127.07) | 571 |
| 전국(region 생략) brand GS25 | 365 |
| facets 키 | cat, brand, mtype |
| clusters 정합성 | sum(cluster.count) == /merchants total (서울·부산 편의점·GS25 전국 3조합) |

위 값은 **2026-09-01 수집분** 기준이다(v3 격자 순회로 수집 방식이 바뀐 첫 회차 — 이전 기준은 2026-08-28 v2 addrCd 순회분). 데이터가 갱신되면 이 표도 함께 올린다.

로컬 개발 DB는 `data/merchants/*.json` 재수집 후 **반드시 재적재**(load_merchants.py 멱등) — 로컬 DB가 낡으면 auto 모드 검증에서 프로덕션과 다른 수치가 나와 가짜 버그를 쫓게 된다.

프로덕션 대조: `D=https://api.koscomlabor.cloud`로 위 질의 실행(한글 파라미터는 `--data-urlencode` 필수).

## 검증 리포트 (`_workspace/18_devqa_report.md`)

실행한 테스트 목록·결과, 경계면 비교 결과, 기준값 대조표, 미해결 지적(파일:라인+재현+기대vs실제)을 남긴다. "미검증"과 "통과"를 구분해 적는다 — 검증하지 못한 것을 통과로 쓰는 순간 이 표는 신뢰를 잃는다.

## 흔한 함정

- CSS 후손 셀렉터가 범용 클래스명(.card 등)을 과잉 매칭 — 레이아웃 속성 추가 시 매칭되는 전 요소를 확인하고, 렌더 계측(행 높이·요소 크기)으로 검증한다(guide-page-build 셀렉터 규칙 참조).
- 서버 코드만 바꾸고 옛 프로세스로 검증 — 8080 재기동 후 테스트할 것.
- curl에 한글 파라미터 직접 삽입 → 빈 응답. `-G --data-urlencode` 사용.
- Playwright 로드 대기 부족 → 이전 상태 캡처. 데이터 로드(수 초)를 명시적으로 기다린다.
- raw CDN 캐시로 옛 파일 검증 — 커밋 SHA 고정 URL 또는 `?v=` 사용.
- Postgres 이미지 계열 교체(알파인 musl↔데비안 glibc) 후 REINDEX 누락 — 콜레이션 버전이 달라져 한글 인덱스가 조용히 손상된다(데이터는 무결·순차 스캔 정상이라 일부 쿼리만 어긋남). 교체 직후 `REINDEX DATABASE` + 동 단위 기준값 검증 필수(2026-08-11 실사고, DEPLOY.md 운영 메모 참조).
- 프론트 정적 파일 수정 후 브라우저 캐시로 옛 버전 검증 — `?v=` 쿼리를 올리고 확인한다(위젯 htmlLabels 수정이 캐시에 가려 재현된 사례).
