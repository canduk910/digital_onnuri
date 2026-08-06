# 사용처 데이터 스키마

`data/*.json`의 필드 정의. 렌더 코드가 이 필드명에 의존하므로, 필드명을 바꾸면 `guide-frontend-dev`에게 반드시 알린다.

## 목차

1. [공통 규칙](#공통-규칙)
2. [online_platforms.json](#online_platformsjson)
3. [offline_categories.json](#offline_categoriesjson)
4. [파생 값 — 계산해서 쓰는 것](#파생-값--계산해서-쓰는-것)

---

## 공통 규칙

- 최상위는 객체다. 배열을 최상위로 두지 않는다 — 메타데이터를 붙일 자리가 없어진다.
- 날짜는 `YYYY-MM-DD` 문자열.
- `id`는 안정적이어야 한다. 순서가 바뀌어도 유지되며, 연번(`no`)과 별개다. 연번은 표시용이므로 렌더 시 계산한다.

```json
{
  "meta": {
    "source": "온누리상품권 공식 홈페이지 〉 온라인 전통시장관",
    "source_url": "https://...",
    "pages_checked": "1-3",
    "collected_on": "2026-08-06",
    "note": "부분 수집인 경우 범위와 사유"
  },
  "items": [ ... ]
}
```

`meta.collected_on`은 **items 중 가장 오래된 `collected_on`** 이하여야 한다. 페이지의 기준일 스탬프는 이 값을 따른다.

---

## online_platforms.json

```json
{
  "id": "nolzang",
  "no": null,
  "kind": "shopping",
  "name": "온누리 놀장",
  "summary": "전통시장 장보기 배송 서비스",
  "note": "일부 지역 한정",
  "url": "https://...",
  "region_limited": true,
  "regions": ["대구"],
  "source_url": "https://...",
  "collected_on": "2026-08-06",
  "status": "active"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `id` | ✓ | 안정적 식별자. 영문 소문자·하이픈 |
| `no` | | 표시용 연번. **null로 두고 렌더 시 계산한다** |
| `kind` | ✓ | `shopping` \| `delivery` |
| `name` | ✓ | 플랫폼명 |
| `summary` | ✓ | 한 줄 소개. **출처 사이트 문구를 그대로 옮긴다** — 각색하면 사실이 미묘하게 바뀐다 |
| `note` | | 비고 (지역 제약, 전용관 한정 등) |
| `url` | | 방문 링크 |
| `region_limited` | ✓ | 지역 한정 여부. 지역 배달앱은 해당 지역에서만 주문 가능 |
| `regions` | | `region_limited`가 true일 때 지역 목록 |
| `source_url` | ✓ | 이 항목을 확인한 페이지 |
| `collected_on` | ✓ | 확인 날짜 |
| `status` | ✓ | `active` \| `ended` — 제휴 종료 시 `ended`로 두고 다음 갱신에서 제거 |

`region_limited`를 별도 필드로 두는 이유: 비고 문자열에 묻어두면 필터·경고 표시를 만들 수 없다. 대구 사람에게만 유효한 플랫폼이 전국 목록에 섞여 있으면 헛걸음이 발생한다.

---

## offline_categories.json

```json
{
  "id": "convenience-store",
  "type": "편의점",
  "examples": "CU, GS25, 세븐일레븐 등",
  "verdict": "conditional",
  "verdict_label": "가맹 시 가능",
  "basis": ["R1", "R2"],
  "check_point": "앱 '가맹점 찾기'에서 점포 단위 확인 + 입구 스티커",
  "collected_on": "2026-08-06"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `id` | ✓ | 안정적 식별자 |
| `type` | ✓ | 업종명 |
| `examples` | ✓ | 대상·예시 |
| `verdict` | ✓ | `allowed` \| `conditional` \| `denied` |
| `verdict_label` | ✓ | 화면 표시 문구 ("가능", "가맹 시 가능", "불가") |
| `basis` | ✓ | 근거 요건 ID 배열. `01_policy_analysis.md`의 요건 ID를 참조 |
| `check_point` | ✓ (conditional일 때) | 이용자가 확인하는 방법 |
| `collected_on` | ✓ | |

**`basis`가 이 스키마의 핵심이다.** 판정의 근거를 기록해두면, 정책 요건이 바뀌었을 때 영향받는 업종을 기계적으로 찾아낼 수 있다. 근거 없이 판정만 있으면 요건 변경 시 전체를 다시 판단해야 하고, 실제로는 아무도 다시 판단하지 않는다.

`verdict`가 `conditional`인데 `check_point`가 비어 있으면 검증관이 결함으로 잡는다.

---

## 파생 값 — 계산해서 쓰는 것

다음 값은 **데이터에 저장하지 않는다.** 저장하는 순간 원본과 어긋날 수 있는 두 번째 진실이 생긴다.

| 파생 값 | 계산 |
|---------|------|
| 총 플랫폼 수 | `items.filter(status === 'active').length` |
| 쇼핑/배달 개수 | `kind`별 집계 |
| 표시용 연번 | 정렬 후 인덱스 |
| 업종별 가능/조건부/불가 개수 | `verdict`별 집계 |
| 페이지 기준일 스탬프 | `min(items[].collected_on)` |

페이지 헤더의 "공식 안내 30곳 — 쇼핑 22 · 배달 8" 같은 문구는 전부 이 계산의 결과여야 한다.
