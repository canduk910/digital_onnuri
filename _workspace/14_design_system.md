# 14. 디자인 시스템 — 화이트 모노톤 + 오렌지 (UX 대개편)

사용자 브리프: 좌측 사이드바(PC 상시 + 모바일 드로어) · 화이트 기반 **중립 모노톤** + 오렌지 포인트 · 모던하고 깨끗한 버튼 · "전형적 클로드 아티팩트 스타일(크림/따뜻한 톤/큰 라운드) 탈피".

기존 팔레트의 문제: #171512(브라운빛 검정)·#FAFAF9·#F0EFED(크림)·#8A8580(따뜻한 회색) = 따뜻한 톤. 이를 **완전 중립(neutral) 그레이스케일**로 교체하고 오렌지만 남긴다.

## 색상 토큰 (CSS 변수)

```css
:root{
  /* 배경·표면 — 순수 화이트 + 중립 그레이 */
  --bg:        #FFFFFF;   /* 페이지 */
  --surface:   #F7F7F7;   /* 카드·패널 (중립, 크림 아님) */
  --surface-2: #F0F0F0;   /* 더 눌린 표면 */
  --border:    #E6E6E6;   /* 기본 보더 */
  --border-2:  #D6D6D6;   /* 강조 보더 */

  /* 텍스트 — 중립 다크 (따뜻함 제거) */
  --text:      #17181A;   /* 본문·제목 */
  --text-sub:  #6B6E73;   /* 보조 */
  --text-faint:#9CA0A6;   /* 캡션·플레이스홀더 */

  /* 오렌지 포인트 — 온누리 (절제해서 액션·활성에만) */
  --accent:      #F26B1D;
  --accent-hover:#DD5E12;
  --accent-press:#C4510F;
  --accent-soft: #FEF3EC;  /* 아주 옅은 오렌지 배경 */
  --accent-line: #F6C9A8;  /* 오렌지 보더 */

  /* 상태 (판정용) — 모노톤 우위, 색은 보조 */
  --ok:   #1F9D57;  --ok-soft:#EAF7EF;
  --warn: #C77A16;  --warn-soft:#FBF3E6;
  --no:   #9CA0A6;  --no-soft:#F2F2F2;  /* 불가는 회색(경고 아님) */

  /* 형태 */
  --r-sm: 6px;   /* 버튼·인풋·칩 */
  --r-md: 10px;  /* 카드 */
  --r-lg: 14px;  /* 큰 패널·드로어 */
  --sb-w: 248px; /* 사이드바 폭 */

  --shadow-sm: 0 1px 2px rgba(20,22,26,.05);
  --shadow-md: 0 6px 24px rgba(20,22,26,.09);
}
```

**원칙**: 그림자보다 1px 보더로 구획(모노톤의 깔끔함). 오렌지는 화면당 소수 지점(활성 메뉴·primary 버튼·핵심 수치)에만. pill(999px) 라운드 금지 — 6~14px 샤프 라운드.

## 타이포

- 페이스: `Pretendard Variable`(한글 최적) 유지. 유틸 숫자는 `tabular-nums`.
- 스케일(웨이트/자간):
  - 페이지 타이틀 26px / 700 / -0.02em
  - 섹션 h2 17px / 650 / -0.01em
  - 본문 14px / 400 / -0.005em
  - 보조 12.5px / 400
  - eyebrow·라벨 11.5px / 700 / +0.06em / 대문자·오렌지
- 줄간격: 본문 1.6, 제목 1.25.

## 레이아웃 (사이드바 셸)

```
PC (≥960px)                     모바일 (<960px)
╭────────┬──────────────╮       ╭──────────────╮
│sidebar │  topbar(얇음) │       │ ☰  [온] 제목  │ ← 상단바+햄버거
│ 248px  ├──────────────┤       ├──────────────┤
│ 고정   │              │       │              │
│ [온]   │   content    │       │   content    │
│ 그룹1  │   max 1080   │       │              │
│  ·항목 │              │       ╰──────────────╯
│ 그룹2  │              │       ☰ → 좌측 드로어(오버레이+dim)
│ 하단↗ │              │
╰────────┴──────────────╯
```

- 사이드바: `position:fixed; left:0; width:var(--sb-w)`. 콘텐츠 `margin-left:var(--sb-w)`.
- 모바일: 사이드바 `transform:translateX(-100%)`, 햄버거로 열기(+반투명 오버레이, ESC·오버레이 클릭 닫기). 상단바 표시.
- 콘텐츠 최대폭 1080px, 좌우 여백 32/20px.

## 사이드바 구성

```
[온]  디지털온누리 가이드      ← 로고(오렌지 라운드 심볼) + 서비스명
────────────────────────
사용 가이드                    ← 그룹 라벨(eyebrow 회색)
  오프라인 사용처              ← 항목(현재 페이지 앵커/링크)
  온라인 가맹 플랫폼
  결제 방법
  용어·유의사항
가맹점 검색
  지역별 찾기
  업종·브랜드별
────────────────────────
공식 가맹점 지도  ↗            ← 외부(새 탭), 하단 고정
```

- 활성 항목: 좌측 3px 오렌지 바 + 텍스트 --text 볼드 + --accent-soft 배경. 나머지 --text-sub.
- 항목 hover: --surface 배경.
- 그룹 라벨: 11.5px/700 대문자 --text-faint.
- 크로스 페이지: 가이드 항목→`index.html#앵커`, 검색 항목→`merchants.html#앵커`. 현재 페이지 항목만 활성.
- 접근성: `<nav aria-label>`, 항목 `<a>`, 활성 `aria-current="page"`, 햄버거 `aria-expanded`, 키보드 포커스 링(오렌지 2px).

## 버튼·컨트롤

| 종류 | 스타일 |
|---|---|
| primary | bg --accent, text #fff, radius --r-sm, 14px/600, padding 9px 16px. hover --accent-hover, press --accent-press |
| secondary | bg #fff, border 1px --border-2, text --text. hover bg --surface |
| ghost | text --text-sub, no border. hover bg --surface |
| 필터 칩 | radius --r-sm(pill 아님), border 1px --border, bg #fff, 13px. **활성**: bg --accent, text #fff (또는 border --accent + bg --accent-soft + text --accent-press) |
| input/select | radius --r-sm, border 1px --border, bg #fff, focus border --accent + ring --accent-soft |

모든 인터랙션 `transition:.15s ease`. 포커스 가시성 필수(키보드).

## 상태 표기 (판정표·배지)

- 가능 ✓: --ok / --ok-soft, 불가 ✕: **--no 회색**(빨강 아님 — 위압감 제거), 조건부 !: --warn.
- 색+아이콘+텍스트 3중(색각). 배지 radius --r-sm.

## signature element

**좌측 오렌지 액티브 레일** — 사이드바 활성 항목의 3px 오렌지 바가 유일한 강한 색. 콘텐츠는 순백·중립 그레이로 조용히, 오렌지는 "지금 여기"와 핵심 액션에만. 정보 도구다운 절제 = 클로드 크림톤과 정반대의 클린 유틸리티 톤.

## 적용 순서

1. **merchants.html**(vanilla) 먼저 — 디자인 확정용. 사이드바+토큰+버튼 전면 적용
2. 사용자 확인 후 **index.html**(번들) 확산 — build_index.py 패턴, `</` 불변식. 동일 사이드바·토큰
3. 두 페이지 사이드바 마크업·CSS 동일(복붙 일관성)
