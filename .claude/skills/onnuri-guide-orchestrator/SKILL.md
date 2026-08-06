---
name: onnuri-guide-orchestrator
description: "디지털온누리상품권 가이드 페이지(index.html)를 제작·갱신하는 5인 에이전트 팀 오케스트레이터. 정책분석·데이터큐레이션·문구작성·페이지구현·교차검증을 조율한다. 조사·데이터·문구·구현·검증 중 두 단계 이상이 함께 필요한 요청 — 가이드 신규 제작, 전체 갱신·최신화, 정책 변경을 데이터와 문구까지 일괄 반영, 검증 결과 전면 반영, 직전 가이드 산출물 대비 이번 갱신에서 무엇이 바뀌었는지 정리 — 에 반드시 이 스킬을 사용할 것. 후속 작업(다시 실행, 재실행, 부분만 다시, 이전 결과 보완·개선)도 이 스킬이 받는다. 단일 단계로 끝나는 요청은 팀을 기동하지 말고 개별 스킬을 직접 쓴다 — 플랫폼 한두 개 추가·삭제는 onnuri-usage-data, 문장 다듬기는 guide-content-style, 스타일·필터 수정은 guide-page-build, 검수만 요청하면 guide-verification. 개별 점포 단건 질문('우리 동네 CU 되나')은 스킬 없이 답한다."
---

# 온누리 가이드 오케스트레이터

디지털온누리상품권 사용처 가이드 페이지를 만들고 갱신하는 팀을 조율한다.

## 실행 모드: 에이전트 팀

이 세션에는 `TeamCreate`가 없다. 팀은 다음 조합으로 구성한다:

- `Agent(name: "...", subagent_type: "...", model: "opus")` — 팀원 스폰. `name`을 주면 `SendMessage`로 주소 지정이 가능해진다
- `SendMessage({to: name})` — 팀원 간·리더와의 직접 통신
- `TaskCreate` / `TaskUpdate` / `TaskGet` — 공유 작업 목록

**시작 전에 도구를 먼저 로드한다.** `SendMessage`, `TaskCreate`, `TaskUpdate`, `TaskGet`은 deferred 도구다. 한 번의 호출로 함께 로드한다:

```
ToolSearch("select:SendMessage,TaskCreate,TaskUpdate,TaskGet,TaskList")
```

로드하지 않고 호출하면 InputValidationError가 난다. 이것이 이 하네스의 첫 번째 실패 지점이다.

## 팀 구성

| 팀원 (name) | subagent_type | 역할 | 스킬 | 산출물 |
|---|---|---|---|---|
| `policy` | onnuri-policy-analyst | 제도·요건·시행일 | onnuri-policy-research | `_workspace/01_policy_analysis.md` |
| `data` | onnuri-data-curator | 사용처 목록 SSOT | onnuri-usage-data | `data/*.json`, `_workspace/02_data_report.md` |
| `writer` | guide-content-writer | 문구·정보구조 | guide-content-style | `_workspace/03_content_spec.md` |
| `dev` | guide-frontend-dev | 페이지 구현 | guide-page-build | `index.html`, `_workspace/04_build_notes.md` |
| `verifier` | guide-verifier | 교차 검증 | guide-verification | `_workspace/05_verification_report.md` |

모든 Agent 호출에 `model: "opus"`를 명시한다.

## 워크플로우

### Phase 0-A: 팀을 기동할지 판단

**팀 기동은 비싸다.** 5명을 띄우는 비용이 정당한지 먼저 따진다.

| 요청 | 처리 |
|---|---|
| 두 단계 이상이 얽힘 (정책 변경 → 데이터 → 문구 → 페이지) | 팀 기동. Phase 0-B로 |
| 단일 단계로 끝남 (플랫폼 1곳 삭제, 오타 수정, 색상 변경, 검수만) | **팀을 띄우지 않는다.** 해당 스킬을 직접 사용 |
| 판단이 애매함 | 실제로 손대야 하는 산출물 수를 센다. 2개 이상이면 팀 |

"플랫폼 하나 빼줘"는 `data/online_platforms.json` 한 곳만 바뀌고 페이지는 계산식으로 자동 반영된다 — 이런 요청에 5인 팀을 띄우는 것은 낭비다. 단, 그 변경이 문구까지 바꿔야 한다면(예: 배달 카테고리가 통째로 사라짐) 두 단계이므로 팀을 기동한다.

### Phase 0-B: 컨텍스트 확인

`_workspace/`와 `data/`, `index.html`의 존재를 확인해 실행 모드를 정한다.

| 상태 | 모드 | 행동 |
|---|---|---|
| `_workspace/` 없음 | **초기 실행** | Phase 1부터 전체 |
| 있음 + 부분 수정 요청 | **부분 재실행** | 해당 팀원만 스폰. 이전 산출물 경로를 프롬프트에 넣어 읽고 고치게 한다 |
| 있음 + 정기 갱신 요청 | **갱신 실행** | `policy`·`data`부터. 변경이 없으면 하류 단계를 건너뛴다 |
| 있음 + 완전히 새 입력 | **새 실행** | 기존 `_workspace/`를 `_workspace_{YYYYMMDD}/`로 옮기고 Phase 1 |

**갱신 실행에서 델타가 없으면 멈춘다.** 정책·데이터 모두 "변경 없음"이면 문구와 페이지를 다시 만들 이유가 없다. 이때 해야 할 유일한 일은 검증관에게 현재 페이지의 스탬프 정합성만 확인시키는 것이다. 변경 없는데 페이지를 재생성하면 스탬프만 올라가 **검증되지 않은 정보에 신뢰를 부여**하게 된다.

### Phase 1: 준비

1. 요청 범위 파악 — 전체 갱신인지, 특정 섹션인지, 특정 데이터인지
2. `_workspace/` 생성 (초기 실행 시)
3. 오늘 날짜를 확정해 팀원 프롬프트에 명시한다. 에이전트는 날짜를 스스로 알 수 없으므로 리더가 주입해야 `verified_on`·`collected_on`이 정확해진다

### Phase 2: 조사 (팬아웃)

**`policy`와 `data`를 동시에 스폰한다.** 한 메시지에서 두 Agent 호출.

```
Agent(name: "policy", subagent_type: "onnuri-policy-analyst", model: "opus",
      prompt: "오늘은 {YYYY-MM-DD}. onnuri-policy-research 스킬을 따라 ... 
               완료 후 SendMessage로 data와 verifier에게 알린다.")
Agent(name: "data", subagent_type: "onnuri-data-curator", model: "opus",
      prompt: "오늘은 {YYYY-MM-DD}. onnuri-usage-data 스킬을 따라 ...")
```

작업 등록:

```
TaskCreate([
  { title: "가맹 요건·시행일 확정", assignee: "policy" },
  { title: "제외 업종 확인", assignee: "policy" },
  { title: "온라인 플랫폼 전 페이지 수집", assignee: "data" },
  { title: "업종 판정표 갱신", assignee: "data", depends_on: ["가맹 요건·시행일 확정"] },
  { title: "B1 출처↔데이터 검증", assignee: "verifier", depends_on: ["온라인 플랫폼 전 페이지 수집"] }
])
```

**통신 규칙:** `policy`가 요건 변경을 확정하면 즉시 `data`에게 알린다 — 판정 근거가 바뀌면 업종 판정을 다시 계산해야 하기 때문이다. `data`가 요건상 설명되지 않는 항목을 발견하면 `policy`에게 확인을 요청한다. 이 왕복이 두 산출물을 서로 검증하게 만든다.

### Phase 3: 점진 검증 시작

`data`의 산출물이 나오는 즉시 `verifier`를 스폰해 **B1(출처↔데이터)만** 검증시킨다. 전체 완성을 기다리지 않는다 — 잘못된 데이터 위에 쓴 문구는 문구까지 다시 써야 한다.

### Phase 4: 문구 (파이프라인)

`writer`를 스폰한다. 입력은 `01`, `02`, `data/*.json`.

완료되면 `verifier`에게 **B3(정책↔문구)** 검증을 요청한다. 여기서 잡아야 하는 것은 `confidence: low` 항목이 단정형으로 쓰인 경우다 — 치명 등급이며, 페이지가 완성된 뒤에는 발견해도 연쇄 수정이 커진다.

### Phase 5: 구현

`dev`를 스폰한다. 입력은 `03_content_spec.md`와 `data/*.json`.

리더가 확인할 것: `04_build_notes.md`의 **동적 문구 매핑**이 콘텐츠 명세의 동적 문구 표를 빠짐없이 덮는가. 덮이지 않은 자리가 곧 하드코딩된 숫자다.

### Phase 6: 최종 검증

`verifier`가 B2·B4와 렌더 동작을 검증한다. 브라우저로 `index.html`을 실제로 열게 한다 — 콘솔 에러와 하드코딩된 숫자는 코드 리뷰로 잡히지 않는다.

판정에 따라:

| 판정 | 행동 |
|---|---|
| 통과 | Phase 7 |
| 조건부 통과 | 결함을 담당자에게 배정해 수정 → 재검증. **최대 2회** |
| 불합격 (치명 결함) | 수정 필수. 2회 재시도 후에도 불합격이면 사용자에게 보고하고 판단을 받는다 |

경계면 결함은 **양쪽 담당자 모두**에게 보낸다. 한쪽만 고치면 반대 방향으로 다시 어긋난다.

### Phase 7: 정리

1. 팀원에게 종료 요청
2. `_workspace/` **보존** — 다음 갱신의 델타 기준이 된다. 삭제하면 "무엇이 바뀌었나"를 영영 알 수 없다
3. 사용자 보고: 무엇이 바뀌었는지(델타), 검증 판정, **미검증 항목**
4. 피드백 요청 — 결과나 팀 구성에 고칠 점이 있는지 묻는다

## 데이터 흐름

```
[리더] ──┬─→ policy ──┐ (요건 변경 알림)
         └─→ data   ←─┘
              │  └──→ 01, 02, data/*.json
              ↓
           verifier (B1 즉시)
              ↓
           writer ──→ 03  →  verifier (B3)
              ↓
            dev ──→ index.html, 04
              ↓
           verifier (B2·B4·렌더) ──→ 05
```

## 에러 핸들링

| 상황 | 전략 |
|---|---|
| 공식 사이트 접근 실패 | 해당 항목을 미검증으로 기록하고 진행. 이전 값 유지하되 **날짜는 올리지 않는다** |
| `data`의 항목 수 20% 이상 급감 | 수집 실패 의심. 자동 반영 금지, 사용자에게 보고 |
| 팀원 무응답 | `SendMessage`로 상태 확인 → 1회 재스폰 → 재실패 시 해당 산출물 없이 진행하고 보고서에 누락 명시 |
| 정책↔데이터 상충 | 삭제하지 않고 출처와 함께 병기. 상위 출처를 채택하고 이유를 기록 |
| 검증 2회 재시도 후 불합격 | 사용자 판단 요청. 임의로 통과 처리하지 않는다 |
| 팀원이 다른 팀원 산출물을 못 읽음 | 파일 경로를 리더가 직접 확인해 `SendMessage`로 전달 |

## 테스트 시나리오

### 정상 흐름 — 정기 갱신
1. 사용자: "온누리 가이드 최신 정보로 갱신해줘"
2. Phase 0: `_workspace/` 존재 → 갱신 실행
3. Phase 2: `policy`·`data` 병렬. `policy`가 "변경 없음", `data`가 "플랫폼 2곳 추가·1곳 종료" 보고
4. Phase 3: `verifier` B1 검증 → 통과
5. Phase 4~5: 데이터 델타가 있으므로 `writer`(동적 문구만 확인)·`dev` 진행
6. Phase 6: B2에서 "표 31행 vs 헤더 30곳" 발견 → `dev`가 계산식으로 교체 → 재검증 통과
7. 결과: `index.html` 갱신, 스탬프가 새 `collected_on`으로 이동

### 에러 흐름 — 부분 수집
1. `data`가 온라인 전통시장관 3페이지 중 2페이지만 접근 성공
2. 확인된 항목만 갱신, 미확인 항목의 `collected_on` 유지
3. `verifier`가 B4에서 "모든 항목 날짜가 동일한가" 확인 → 정상 (미확인 항목 날짜가 옛날 그대로)
4. B1은 **미검증**으로 기록 (통과 아님)
5. 판정: 조건부 통과
6. 사용자 보고에 "3페이지 미확인, 다음 실행에서 재시도 필요" 명시
