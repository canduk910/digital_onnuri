---
name: onnuri-guide-orchestrator
description: "디지털온누리 가이드의 2팀(개발팀·업무팀) 오케스트레이터. 업무팀(정책분석·데이터큐레이션·문구·콘텐츠검증)은 가이드 콘텐츠 제작·갱신을, 개발팀(프론트엔드·백엔드·개발QA·아키텍트)은 기능 개발·API·리팩토링을 담당하며, 이 스킬이 요청을 팀으로 라우팅한다. 가이드 신규 제작·전체 갱신·정책 반영 일괄 작업, 기능 개발(프론트+백엔드 걸친 변경)·API 확장·구조 리팩토링·TDD 사이클, 두 팀에 걸친 작업(데이터 스키마 변경→적재→렌더) 등 두 단계 이상이 얽힌 요청에 반드시 이 스킬을 사용할 것. 후속 작업(다시 실행, 재실행, 부분만 다시, 이전 결과 보완·개선)도 이 스킬이 받는다. 단일 단계 요청은 팀을 기동하지 않고 개별 스킬 직행 — 플랫폼 한두 개 추가는 onnuri-usage-data, 문장 다듬기는 guide-content-style, 페이지 소수정은 guide-page-build, 백엔드 단건 수정은 backend-server-dev, 테스트만은 dev-testing, 구조 판단만은 app-architecture, 콘텐츠 검수만은 guide-verification. 커밋·푸시는 doc-commit. 개별 점포 단건 질문은 스킬 없이 답한다."
---

# 온누리 가이드 오케스트레이터 — 2팀 체제

디지털온누리 가이드를 만드는 두 팀을 조율한다. **업무팀**은 "무엇을 말할 것인가"(콘텐츠·데이터의 정확성), **개발팀**은 "어떻게 동작할 것인가"(기능·API·구조)를 소유한다.

## 실행 모드: 에이전트 팀

이 세션에는 `TeamCreate`가 없다. 팀은 다음 조합으로 구성한다:

- `Agent(name: "...", subagent_type: "...", model: "opus")` — 팀원 스폰. `name`으로 `SendMessage` 주소 지정
- `SendMessage({to: name})` — 팀원 간·리더와의 직접 통신
- `TaskCreate` / `TaskUpdate` / `TaskGet` — 공유 작업 목록

**시작 전에 도구를 먼저 로드한다** (미로드 호출은 InputValidationError — 첫 번째 실패 지점):

```
ToolSearch("select:SendMessage,TaskCreate,TaskUpdate,TaskGet,TaskList")
```

## 팀 구성

### 개발팀 (dev-team) — 기능·API·구조

| 팀원 (name) | subagent_type | 역할 | 스킬 | 산출물 |
|---|---|---|---|---|
| `architect` | app-architect | 구조 설계·리팩토링·구현 위치 판단 | app-architecture | `_workspace/16_arch_decisions.md` |
| `backend` | backend-dev | Spring 검색 API·스키마·NCP 운영 | backend-server-dev, dev-testing | `backend/**`, `_workspace/17_backend_notes.md` |
| `frontend` | guide-frontend-dev | 3페이지·config·이중소스 프론트 | guide-page-build, dev-testing | `*.html`, `config.js`, `_workspace/04_build_notes.md` |
| `dev-qa` | dev-qa (**general-purpose로 스폰**) | 테스트 실행·경계면 검증·TDD 게이트 | dev-testing | `_workspace/18_devqa_report.md` |

### 업무팀 (biz-team) — 콘텐츠·데이터 정확성

| 팀원 (name) | subagent_type | 역할 | 스킬 | 산출물 |
|---|---|---|---|---|
| `policy` | onnuri-policy-analyst | 제도·요건·시행일 | onnuri-policy-research | `_workspace/01_policy_analysis.md` |
| `data` | onnuri-data-curator | 사용처 목록 SSOT | onnuri-usage-data | `data/*.json`, `_workspace/02_data_report.md` |
| `writer` | guide-content-writer | 문구·정보구조 | guide-content-style | `_workspace/03_content_spec.md` |
| `verifier` | guide-verifier | 콘텐츠 교차 검증 | guide-verification | `_workspace/05_verification_report.md` |

모든 Agent 호출에 `model: "opus"`를 명시한다. dev-qa는 검증 스크립트 실행이 필요하므로 subagent_type을 general-purpose로 스폰하고 에이전트 정의(`.claude/agents/dev-qa.md`)를 프롬프트에서 참조시킨다.

## Phase 0-A: 라우팅 — 어느 팀을, 몇 명이나 기동할지

**팀 기동은 비싸다.** 요청을 먼저 분류한다:

| 요청 유형 | 라우팅 |
|---|---|
| 콘텐츠·데이터·문구·정책 (가이드 갱신, 목록 변경, 문구 개편) | **업무팀** — 워크플로 A |
| 기능 개발·API·버그·리팩토링 (필터 추가, API 확장, 구조 변경) | **개발팀** — 워크플로 B |
| 두 팀에 걸침 (데이터 스키마 변경→적재→렌더, 정책 변경이 기능을 요구) | **혼성** — 워크플로 C |
| 단일 단계로 끝남 | **팀을 띄우지 않는다** — description의 개별 스킬 직행 표 |
| 판단이 애매함 | 손대야 하는 산출물 수를 센다. 2개 이상이면 팀 |

**개발팀도 전원 기동이 기본이 아니다.** 프론트만 걸치면 frontend+dev-qa 2인, 계약 변경이면 backend+frontend+dev-qa 3인, 구조 쟁점이 있을 때만 architect를 추가한다.

## Phase 0-B: 컨텍스트 확인

`_workspace/`·`data/`·산출물 존재로 실행 모드를 정한다:

| 상태 | 모드 | 행동 |
|---|---|---|
| `_workspace/` 없음 | 초기 실행 | 해당 워크플로 처음부터 |
| 있음 + 부분 수정 요청 | 부분 재실행 | 해당 팀원만 스폰, 이전 산출물 경로를 프롬프트에 주입 |
| 있음 + 정기 갱신 | 갱신 실행 | 상류(policy·data)부터, 델타 없으면 하류 생략 |
| 있음 + 완전히 새 입력 | 새 실행 | 기존 `_workspace/`를 `_workspace_{YYYYMMDD}/`로 이동 |

**갱신 실행에서 델타가 없으면 멈춘다.** 변경 없는데 재생성하면 스탬프만 올라가 검증되지 않은 정보에 신뢰를 부여한다.

리더는 오늘 날짜를 확정해 모든 팀원 프롬프트에 명시한다(에이전트는 날짜를 스스로 모른다).

## 워크플로 A: 업무팀 (콘텐츠 파이프라인)

1. **조사 팬아웃** — `policy` ∥ `data` 동시 스폰(한 메시지, 두 Agent 호출). policy의 요건 변경은 즉시 data에 통지(판정 재계산), data의 설명 안 되는 항목은 policy에 역질의 — 이 왕복이 두 산출물을 상호 검증하게 만든다.
2. **점진 검증** — data 산출 즉시 `verifier`가 B1(출처↔데이터)만 먼저 검증. 전체 완성을 기다리지 않는다 — 잘못된 데이터 위에 쓴 문구는 문구까지 다시 써야 한다.
3. **문구** — `writer` 스폰(입력: 01·02·data/*.json) → verifier B3(정책↔문구). confidence:low 항목의 단정형 서술이 치명 등급.
4. **구현** — 페이지 반영이 필요하면 **개발팀 `frontend`를 차출**한다. 리더는 `04_build_notes.md`의 동적 문구 매핑이 콘텐츠 명세의 동적 문구 표를 빠짐없이 덮는지 확인 — 덮이지 않은 자리가 곧 하드코딩 숫자다.
5. **최종 검증** — verifier가 B2·B4와 렌더 동작(브라우저 실측). 판정: 통과 / 조건부(담당 배정 수정→재검증 최대 2회) / 불합격(2회 후 사용자 판단). 경계면 결함은 **양쪽 담당자 모두**에게 — 한쪽만 고치면 반대로 다시 어긋난다.

## 워크플로 B: 개발팀 (TDD 사이클)

1. **설계 게이트(조건부)** — 구현 위치·구조 쟁점이 있으면 `architect` 먼저. ADR과 위임 결정문(계약·테스트 요구·롤백 포함)을 받는다. 쟁점 없으면 생략.
2. **Red** — 담당(backend/frontend)이 실패하는 테스트·검증 시나리오를 먼저 작성. dev-qa가 "테스트가 기대 동작을 표현하는가" 확인 — TDD 게이트 1.
3. **Green** — 최소 구현. 계약 변경(필드·센티넬·필터 규칙)은 서버와 프론트 JSON 폴백을 **하나의 변경 단위**로 묶는다 — 한쪽만 바꾸면 에러 없이 다른 숫자가 나온다(이 시스템 최악의 결함 유형).
4. **Refactor** — 테스트 녹색을 유지하며 정리. 구조 정리는 architect 주도 + dev-qa 사전/사후 스냅샷.
5. **검증 게이트** — dev-qa가 `./gradlew test`·Playwright·경계면 교차 비교·회귀 기준값 대조(dev-testing 스킬) — TDD 게이트 2. "미검증"을 "통과"로 쓰지 않는다.
6. **반영** — doc-commit 절차(문서→비밀 스캔→커밋·푸시→main↔feat 동기화). 프로덕션 반영 시 backend가 DEPLOY.md 절차 수행 + dev-qa가 프로덕션 기준값 대조.

## 워크플로 C: 혼성 (두 팀에 걸친 변경)

데이터 스키마 변경처럼 SSOT→적재→API→렌더가 연쇄되는 요청:

1. `architect`가 변경 경로 설계(어느 층이 먼저, 계약 영향) → 결정문
2. 업무팀 `data`가 SSOT 변경 → 개발팀 `backend`가 엔티티·마이그레이션·적재기 추종 → `frontend`가 소비 갱신. 각 단계 사이 dev-qa 점진 검증.
3. 최종: **dev-qa(코드·경계면) ∥ verifier(콘텐츠·스탬프) 병렬** — 관할 상호 불가침(코드=dev-qa, 콘텐츠=verifier). 발견이 상대 관할이면 넘긴다.

## 데이터 흐름

```
                 [리더: 라우팅]
        ┌──────────┴──────────┐
   업무팀(A)               개발팀(B)
policy ∥ data          architect(조건부) → 결정문(16)
   ↓ 01·02·json            ↓
verifier B1        backend ∥ frontend   ← Red→Green→Refactor
   ↓                       ↓ 17·04
writer → 03         dev-qa: 테스트·경계면·기준값 → 18
   ↓                       ↓
frontend(차출) → 04     doc-commit 반영
   ↓
verifier B2·B4·렌더 → 05
        └── 혼성(C): data → backend → frontend, 최종 dev-qa ∥ verifier ──┘
```

## 에러 핸들링

| 상황 | 전략 |
|---|---|
| 공식 사이트 접근 실패 | 미검증 기록 후 진행. 이전 값 유지, **날짜는 올리지 않는다** |
| data 항목 수 20% 이상 급감 | 수집 실패 의심 — 자동 반영 금지, 사용자 보고 |
| 테스트 인프라 다운(gradlew·서버·DB) | dev-qa가 "검증 불가" 명시, 인프라 복구를 선행 작업으로 등록 — 통과로 눙치지 않는다 |
| 회귀 기준값 불일치 | 즉시 버그 판정 금지 — 로컬 JSON 대조로 데이터 변동/버그를 분리한 뒤 처리 |
| 계약 분쟁(서버·프론트가 서로 자기 규칙 주장) | architect가 결정하고 ADR로 기록 |
| 팀원 무응답 | SendMessage 상태 확인 → 1회 재스폰 → 재실패 시 해당 산출물 없이 진행, 누락 명시 |
| 정책↔데이터 상충 | 삭제하지 않고 출처 병기, 상위 출처 채택·이유 기록 |
| 검증 2회 재시도 후 불합격 | 사용자 판단 요청. 임의 통과 처리 금지 |

## 테스트 시나리오

### 정상 — 개발팀 TDD (워크플로 B)
1. "가맹점 검색에 영업시간 필터 추가해줘"
2. 라우팅: 기능·계약 변경 → backend+frontend+dev-qa 3인(구조 쟁점 없음 → architect 생략)
3. Red: backend가 SearchQuery·Specs 테스트 먼저(실패 확인), frontend가 통과 시나리오(기준 카운트) 정의
4. Green: 서버 필터 + 프론트 JSON 폴백을 한 변경 단위로 구현 → Refactor
5. dev-qa: gradlew 녹색 · 경계면 표 대조 · API vs JSON 폴백 동일 카운트 → 18 리포트
6. doc-commit 반영(main+feat 동기화)

### 정상 — 혼성 (워크플로 C)
1. "가맹점 데이터에 전화번호 필드 추가하고 카드에 표시하자"
2. architect: 수집기→SSOT→마이그레이션→API→렌더 경로 결정문
3. data(수집기·JSON) → backend(V2 마이그레이션·적재기·MerchantView) → frontend(렌더) — 단계마다 dev-qa
4. 최종 dev-qa(계약·기준값) ∥ verifier(스탬프·문구) → 통과 → doc-commit

### 에러 — 기준값 불일치
1. dev-qa가 프로덕션 대조에서 GS더프레시 7≠8 발견
2. 즉시 버그 판정하지 않고 로컬 data/merchants/gyeonggi.json 카운트 → 8 확인
3. 판정: 데이터 변동(재수집분) — dev-testing 기준표를 8로 갱신, 근거를 18 리포트에 기록
