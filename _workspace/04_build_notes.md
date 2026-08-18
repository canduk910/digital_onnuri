# 04 빌드 노트 — index.html 구현 (dev)

- 작성: guide-frontend-dev (2026-08-06)
- 입력: `_workspace/03_content_spec.md`, `data/online_platforms.json`, `data/offline_categories.json`, 원본 번들 `index.html.bak-20260806`
- 산출: `index.html`, 빌드/검증 스크립트 `_workspace/dev_scripts/`

## 번들 구조와 작업 방식

`index.html`은 4.15MB 자기해제 번들이다. 통째로 편집하지 않는다.

| 라인 | 내용 | 취급 |
|------|------|------|
| L1–373 | HTML head + 로더 스크립트(JS) | **불변** |
| L376 | `__bundler/manifest` — 4MB base64 blob(폰트·JS 청크) | **불변** |
| L379–385 | ext_resources / page_order | 불변 |
| **L388** | `__bundler/template` — JSON 문자열로 이스케이프된 실제 페이지(HTML + DC 앱) | **여기만 수정** |

- 프레임워크는 React 기반 커스텀 **DC**(`class Component extends DCLogic`, `this.setState`, `React.createRef`). 템플릿 바인딩은 머스태시 `{{ }}`, 반복은 `<sc-for>`, 조건은 `<sc-if>`, 이벤트는 `sc-camel-on-click` 등. `{{ }}` 는 `renderVals()` 반환 객체의 키로 해석된다.
- 빌드는 `_workspace/dev_scripts/build_index.py` 가 전담: **원본 .bak → 템플릿 디코드 → 문자열 치환 → 재인코딩 → index.html 생성**. 항상 pristine 원본에서 시작하므로 멱등(재실행 가능).
- 모든 치환은 `replace_once()` 로 "정확히 1회 매칭"을 assert — 앵커가 어긋나면 빌드가 즉시 실패한다.

### DC 바인딩 제약 (설계 판단)

기존 템플릿은 `{{ }}` 바인딩을 **항상 자기 요소에 격리**한다(예: `<span>{{ tipsArrow }}</span>` + 형제 정적 텍스트). 텍스트 노드 하나에 정적 문자열과 바인딩을 섞는 예시는 원본에 없다. 리스크 회피를 위해:
- 정적 문장 중간에 값이 끼는 자리(D1 기준월, D3 인트로, D6 각주)는 값을 `<span>{{ key }}</span>` 로 감싸 격리.
- 여러 숫자가 한 문장에 모이는 자리(D2 탭 부제, D3 인트로 문장)는 **조합 문자열을 단일 computed** 로 만들어 바인딩 1개로 렌더(`onTabText`, `onIntroMid`, `onIntroTail`). 이렇게 하면 다중 인터폴레이션 미지원 리스크가 사라진다.

## 데이터 주입 (task 1)

- ONLINE 배열: `data/online_platforms.json` 의 `status==='active'` 30곳을 순서대로 `{c,n,d,u,m,rl,st,co}` 로 생성. `c`=배달/쇼핑(kind), `rl`=region_limited, `st`=status, `co`=collected_on. URL·비고는 JSON 최신본(cyso·현대홈쇼핑·공영쇼핑 기획전 딥링크 교체분) 반영.
- OFFLINE 배열: `offline_categories.json` 12유형을 `{t,d,g,s,p,co}` 로 생성. `g`= allowed→ok / conditional→cond / denied→no. 생활서비스 `check_point` 는 curator 확정본(약국 병기 문구) 그대로 렌더 — 템플릿에 행 텍스트를 남기지 않음.
- 배열 옆에 `ONLINE_META`(collected_on, pages_checked) · `OFFLINE_META`(collected_on) 를 함께 심어 기준일·페이지수 계산의 출처로 사용.

## 동적 문구 매핑 (D1~D7 → computed)

검증관이 "데이터→렌더" 경계를 검사할 때의 체크리스트다. 모두 `renderVals()` 내 계산.

| # | 자리 | computed / 바인딩 | 계산식 |
|---|------|-------------------|--------|
| D1 | 헤더 부제 기준월 | `baseMonth` → `<span>{{ baseMonth }}</span>` | `min(ONLINE.co, OFFLINE.co, ONLINE_META.co, OFFLINE_META.co)` 정렬 최소값의 `slice(0,7)` |
| D2 | 온라인 탭 부제 | `onTabText` → `{{ onTabText }}` | active 필터 후 `onTotal`/쇼핑·배달 `c` 집계를 문자열 조합 |
| D3 | 온라인 인트로 | `onIntroMid`·`onIntroTail` → 각 `<span>` | `collectedOn`(ONLINE_META), `onTotal`(D2 동일), `pagesChecked`(meta.pages_checked, `-`→`~`) |
| D4 | 오프라인 카운트 칩 | 기존 `chips[].count`(`offCnt`) | `OFFLINE` 의 `g`(verdict) 집계 — 기존 로직 유지, 하드코딩 없음 |
| D5 | 검색 결과 카운트 | 기존 `countText` | 필터 후 표시 행수 — 기존 로직 유지 |
| D6 | 각주 ④ 지역 배달앱 | `regionApps` → `<span>{{ regionApps }}</span>` | `ONLINE.filter(rl===true).map(n).join(', ')` → 현행 5곳(전주맛배달·배달특급·먹깨비·배달의 명수·대구로) |
| D7 | 온라인 표 연번 | `onRows.map((r,i)=>({no:i+1,...}))` | **정렬·필터 후** 부여. 기존 `.map((x,i)=>{no})` 초기화 제거 — 이름순 정렬 시에도 연번이 1..n 으로 정합 |

## 변경 섹션 반영 (task 3)

- **S1** 헤더 부제: `2026-08 기준` 하드코딩 제거 → `baseMonth`(D1).
- **S4 요건3**: "즉시 말소" → 경과조치 명시("최초 갱신 전까지는 이 기준을 적용받지 않습니다").
- **S4 요건4**: 보건업 등 2026.6.17 추가 명시 + 약국 예외에 "연매출 30억은 약국에도 적용" 병기.
- **S9 4단계**: 선차감 medium 어조 — "부족분만 청구" 단정 제거, 두 동작 병기 + 앱 잔액 확인 지시. 각주 ③ 연결.
- **S10 모바일(앱)형 결제 흐름 — QR 방식(신규)**: S9 흐름 카드 바로 아래(isOff 블록 내부)에 **동일 아코디언 패턴**으로 추가. 전용 토글 상태 `mflowOpen`(+`toggleMFlow`/`mflowArrow`) 신설. 5단계(앱 설치·가입 / 충전 / QR 결제 / 인증·차감 / 확인) + 카드형과의 차이 한 줄.
- **S11 온라인 인트로**: 전용관 확대 뉘앙스를 수집일 앵커 문장으로 반영(D3), 숫자·페이지·수집일 전부 동적화.
- **S13 각주**: ②(법령 명시·경과조치·취소), ③(선차감 잔액부족 확인지시, 신설), ④(지역앱 동적 D6).
- **S14 선차감 용어**: "모자란 금액만 카드 청구" 단정 제거 → "충전 잔액이 먼저 빠지는 방식 (…각주 ③ 참고)".

유지 섹션(S2·S3·S5·S6표구조·S7·S8·S12)·디자인·필터·검색·정렬 동작은 원본 그대로. S6 판정표 문구는 SSOT(JSON)에서 렌더되므로 curator 수정이 자동 반영된다.

## 자체 확인 (task 5) — 전체 통과

`_workspace/dev_scripts/verify_build.py`:
- (a) 로더·manifest·base64 무결: 원본 대비 변경 라인은 388행(템플릿)뿐, 라인 수·manifest 동일.
- (b) 388행 JSON 파싱 성공(90,434 chars).
- (c) 주입 개수: ONLINE 30(쇼핑 22·배달 8), OFFLINE 12 — JSON과 일치.
- (d) 하드코딩 잔재(30곳·2026-08·쇼핑22배달8·1~3페이지·지역앱 4곳 나열) 제거 확인.
- (e)(f) 동적 바인딩·신규 섹션·computed 정의 존재 확인.
- 추가로 `node --check` 로 DC 스크립트 문법 유효성 확인(한글·특수문자 이스케이프는 `json.dumps` 로 생성).

## 회귀: 번들 언패킹 실패 → 수정 (D-F1, verifier 지적)

- **증상**: 첫 빌드본이 브라우저에서 전면 미렌더 — 로더 스플래시에 멈추고 토스트 `Error unpacking: Unterminated string in JSON at position 186`. DC 미마운트, 머스태시 리터럴 잔존.
- **원인**: `json.dumps` 는 `/` 를 이스케이프하지 않는다. 원본 번들은 388행 template 문자열 안의 모든 `</` 를 `</` 로 이스케이프해 리터럴 `</` 를 0개로 유지했는데(그래야 HTML 파서가 `<script type="__bundler/template">` 를 첫 `</script>` 에서 조기 종료하지 않는다), 내 재인코딩은 `</` 를 258개 리터럴로 되살렸다. 브라우저가 template 을 첫 `</script>` 에서 끊어 `textContent` 가 char 186 에서 잘리고 → 로더의 `JSON.parse` 실패.
- **핵심 교훈**: `json.loads`·`node --check` 등 정적 검사는 이 HTML-파서-레벨 조기종료를 원리적으로 못 잡는다(1차 자체확인도 통과했으나 렌더는 깨졌다).
- **수정**: `build_index.py` 재인코딩 시 `.replace("</", "<\\u002F")` + `assert 리터럴 </ == 0`. `verify_build.py` 에 **불변식 검사 추가**: 388행 raw 에 리터럴 `</` 가 0개여야 함(원본 번들 불변식과 동일). 이제 이 부류 결함을 정적으로 포착한다.

## 브라우저 실렌더 확인 (수정 후, dev 스모크)

수정 후 로컬 서버에서 실제 마운트를 확인함(에러 토스트·스플래시 없음, 페이지 자체 콘솔 에러 0 — 유일 예외는 크롬 확장 share-modal.js 로 페이지 무관):
- D1 기준월 `2026-08` · D2 탭 `공식 안내 30곳 — 쇼핑 22 · 배달 8` · D4 칩 `전체 12/가능 4/조건부·가맹 시 5/불가 3` · D5 `12개 유형 중 12개 표시`.
- 온라인 탭: `총 30곳 중 30곳 표시`, 칩 `전체 30/쇼핑 22/배달 8`, 표 30행 연번 1–30 순차(D7), D3 인트로(수집일 2026-08-06·30곳·1~3페이지 동적), 주입 데이터가 JSON 최신본(사이소·현대홈쇼핑 기획전 직링크 비고)으로 렌더.
- S4·S9(선차감 병기)·S10(모바일 QR 흐름 헤더)·S11·S13(각주 ②③④, D6 지역앱 5곳 `전주맛배달·배달특급·먹깨비·배달의 명수·대구로`)·S14 문구 모두 반영. 잔여 머스태시 없음.

## 알려진 제약 / 전달

1. **정식 렌더 검증은 verifier**: 위 스모크는 마운트·바인딩 해석만 확인했다. 반응형·색각·정렬/검색 상호작용의 판정은 guide-verifier 의 B2·B4·렌더 재검증 몫.
2. **다중 인터폴레이션 회피**: 위 "DC 바인딩 제약" 참조. 혹시 검증에서 `<span>{{ }}</span>` 격리가 부자연스러운 줄바꿈을 만들면 라이터와 조율 후 재빌드.
3. **pages_checked 표기**: JSON meta 는 `"1-3"`, 화면 표기는 관용상 `~` 로 변환(`1~3페이지`). 데이터 원본은 손대지 않음.
4. **폰트 임베드 불변**: Pretendard woff2 base64(약 4MB)는 원본 유지 — 파일 프로토콜 오프라인 열람 전제. 크기 변화 없음.
5. **재빌드 방법**: 데이터·문구 갱신 시 `python3 _workspace/dev_scripts/build_index.py` 재실행(항상 .bak 기준). 원본 .bak 자체가 바뀌어야 하는 변경이면 별도 논의.

## 델타 (task #17) — S15 '가맹점 찾기' 서브탭 (2026-08-07, 최신 명세)

명세 03 S15 최신본 반영. 오프라인 '사용 요건' 박스 요건 ②·③ 사이에 묻혀 있던 merchants 진입 링크를 **오프라인 콘텐츠 최상단(메인 탭 바로 아래, 사용 요건 박스 앞)의 서브탭 2개로 승격**.

- **배치**: `<sc-if isOff>` 블록 최상단(showConcept/사용 요건 박스 앞)에 삽입. 오프라인 탭에서만 노출, 검색/칩 아래·요건 박스 위. 서브 느낌(서브카드 배경 #FAFAF9로 위계 구분).
- **서브탭 2개**:
  - 1 `가맹점 찾기` [내부·수도권] → `merchants.html`(내부 이동, target 없음).
  - 2 `공식 지도 검색 ↗` [외부·전국] → `https://www.onnuri.gift/place`(외부, `target=_blank rel=noopener`, `aria-label="공식 지도 검색 — 새 창에서 열림"`). 라벨에 "앱" 없음(웹 지도), "전국" 서비스로 지역 한정 서술 없음.
  - 색각: 내부/외부·수도권/전국을 **텍스트 배지 + ↗ 기호**로 구분(색 의존 아님). 링크 `style-focus` 포커스 링 유지.
  - 설명 문구 명세대로, 동적 숫자·날짜 0.
- **제거/유지(정정)**: 구 M7 '수도권 가맹점 검색 ↗' 진입 박스만 제거(이전 M7-add 스텝 삭제 → .bak 원본에 없으므로 미추가). 요건 ② **지도 검색 안내 문장**(onnuri.gift/place 가맹점 지도 검색 ↗ — '가맹 시 가능' 매장 점포 단위 확인법)은 **유지**. 요건 ② 본문도 유지. (앞선 초안의 지도 박스 전체 제거는 writer 정정으로 되돌림 — 안내 문장은 요건 설명이지 검색 진입이 아니다.)
- 정적 섹션, DC state 불요. build_index.py 치환 + `</` 불변식 통과·완전 마운트·머스태시 0 확인. 브라우저: S15가 사용 요건 박스 앞 배치·지도 문장 유지·M7 제거·aria-label 실측 확인.

## 델타 — 온라인 플랫폼 목록 이중소스 (API 우선 + JSON 폴백, 2026-08-12)

플랫폼 목록(online_platforms.json items)이 백엔드 DB로 이관·매일 배치 갱신됨에 따라, online.html·terms.html을 가맹점과 동일한 **API 우선 + JSON 폴백**으로 전환. 취급품목 태깅(online_catalog.json)은 수동 큐레이션이라 이관 대상 아님 — 그대로 JSON.

### 공유 어댑터 `online-source.js` (신규)
online.html·terms.html 두 곳이 **동일 정규화**를 쓰도록 어댑터를 한 파일에 둔다(경계면 원칙: 정규화가 두 곳에 흩어지면 한쪽만 바뀌어 조용히 다른 결과가 난다). `window.OnnuriOnlineSource.load()` → 정규화된 `{meta, items}` Promise 반환.

- **API_BASE**: merchants.html과 동일 규칙 — 로컬(localhost/127.0.0.1/빈 호스트)은 `http://localhost:8080/api`, 그 외 `https://api.koscomlabor.cloud/api`. `CFG.apiBase`로 오버라이드.
- **소스 결정(dataMode)**:
  - `json` : 항상 JSON (프로브 지연 없음).
  - `api`  : 항상 API. 실패 시 reject → 소비 페이지가 오류 표시(폴백 안 함, merchants "api"와 동일 정책).
  - `auto`(기본) : `GET /online/platforms`를 시도, `probeTimeoutMs`(기본 2500ms) 내 성공하면 API·아니면 JSON 폴백. 목록이 작아 별도 헤드 프로브 없이 본 호출을 프로브 겸용.
- **엔드포인트**: `GET {API_BASE}/online/platforms` (필터 없는 단순 목록이라 merchants의 POST 프라이버시 규칙 불필요 — 계약대로 GET). JSON은 `data/online_platforms.json` + dataVersion 버스트(`?v=`).

### 스네이크/카멜 정규화 규칙 (어댑터 핵심)
API 응답은 키가 camelCase일 수 있고 JSON 폴백은 snake_case. **내부 형태는 snake_case로 통일**(기존 소비 코드가 쓰던 형태 — 소비부 무변경). `pick(o, snake, camel)`이 snake 우선으로 있는 값을 집는다.

| 내부 형태(snake) | API(camel) 수용 | JSON(snake) 수용 |
|---|---|---|
| `region_limited` | `regionLimited` | `region_limited` |
| `source_url` | `sourceUrl` | `source_url` |
| `collected_on` | `collectedOn` | `collected_on` |
| `id·kind·name·summary·note·url·no·status` | 동일 | 동일 |
| `regions` | (계약에 없음 → `[]`) | `regions` |
| meta `collected_on` | `collectedOn` 또는 snake | `collected_on` |
| meta `source_url` | `sourceUrl` | `source_url` |

- **removed 처리**: 어댑터는 removed 항목을 **그대로 통과**시킨다. `status==='active'` 필터는 소비부 담당 — online.html boot `PLATFORMS = items.filter(status==='active')`, terms.html regionApps `filter(status==='active' && region_limited)`. 둘 다 이미 필터하고 있어 추가 조치 불필요(검증에서 removed 항목이 목록에 새지 않음 확인).

### 동적 문구 매핑 (변경분)
| 자리 | 소스 | 계산 |
|---|---|---|
| online meta-line "공식 목록 N 수집" | 어댑터 `meta.collected_on` | API=active min(collected_on), JSON=파일 meta.collected_on |
| online 카드 그리드·구분 탭·칩 카운트 | 어댑터 `items`(active 필터 후) | 기존과 동일(소스만 이중화) |
| terms.html `#regionApps` | 어댑터 `items` | `status==='active' && region_limited`인 name 나열. 실패 시 catch 무시 → 기본 문구 "지역 한정 앱" 유지 |
| index S16 카드 최신성 안내 | (정적) | "이 안내 페이지의 목록은 갱신 시점에 고정됩니다 — 최신 플랫폼 목록은 위 '온라인 사용처 찾기'에서 확인하세요." build_index.py S16, 동적 숫자 없음 |

### 검증 (2026-08-12, 로컬 정적 서버 8655 + 백엔드 미기동)
- **auto 폴백 렌더**: online.html 30 카드(쇼핑 22·배달 8), meta-line "공식 목록 2026-08-06 수집 · … 30곳", 구분 탭 카운트 일치. uncaught JS 에러 없음(콘솔 에러 2건 모두 백엔드 미기동 리소스 실패 — `/api/visit`=shell.js 방문카운터, `/api/online/platforms`=프로브 → JSON 폴백).
- **dataMode="json" 강제**: load() 3ms 즉시 반환, 30건 전부 active, 지역한정 5곳(전주맛배달·배달특급·먹깨비·배달의 명수·대구로), 내부 키 snake_case 일관.
- **camelCase 정규화(fetch 모킹)**: `regionLimited/sourceUrl/collectedOn` → snake 변환 확인, camel 잔재 없음(`hasCamelLeftover:false`), meta.collected_on·source_url 정규화, removed 항목은 어댑터 통과.
- **terms.html 폴백**: `#regionApps` = "전주맛배달, 배달특급, 먹깨비, 배달의 명수, 대구로"(기본 문구에서 갱신됨).
- **문법**: online-source.js `node --check` OK, online.html·terms.html 마지막 인라인 script `new Function` OK.
- **index D-F1**: build_index.py 재빌드 "리터럴 </ = 0" 통과.
- **미검증**: 실제 로컬 백엔드가 없어 **API 경로(dataMode=api/auto 성공)는 실서버로 미검증** — 어댑터 정규화는 모킹으로만 확인. 계약 필드가 실제 응답과 일치하는지는 dev-qa 경계면 검증 몫.

### 제약 / 전달
- 신규 자산 `online-source.js`는 online.html·terms.html에 `?v=1`로 참조. 이후 이 파일 수정 시 버전 범프 필요(HTML 자체는 버스트 불요).
- config.js는 무변경(dataMode·probeTimeoutMs·apiBase·dataVersion 기존 값 재사용). 라이브 dataMode=auto면 백엔드 배포 후 자동으로 API 우선.

---

## 델타 — admin-report.html 비밀번호 로그인 (2026-08-18)

### 배경
관리자 키 입력 경로가 ①`?key=` URL ②sessionStorage ③`prompt()` 3가지뿐이라 48자리 키를 사람이 들고 다녀야 했다. 기억 가능한 비밀번호로 서버에서 키를 받아오는 경로를 **추가**한다(기존 3경로는 전부 유지 — 회귀 없음).

### 계약 (리더 확정 · 백엔드 `AdminController` 동일)
| 항목 | 값 |
|---|---|
| 엔드포인트 | `POST {API}/admin/login` (API_BASE는 페이지 기존 로직 재사용 — `CFG.apiBase` → localhost:8080 → api.koscomlabor.cloud) |
| 요청 | `Content-Type: application/json`, body `{"password":"…"}` |
| 200 | `{"key":"<관리자 키>"}` → `sessionStorage.onnuri_admin_key`에 저장 후 **기존 `renderKeyState()` 흐름에 합류** |
| 403 | "비밀번호가 올바르지 않거나 로그인 기능이 비활성 상태입니다." |
| 429 | "시도 횟수를 초과했습니다 — 잠시 후 다시 시도해 주세요." |

403 문구는 서버가 주는 `message`("비밀번호가 올바르지 않습니다")를 쓰지 않고 프론트 고정 문구를 쓴다 — 서버는 오답·비밀번호 미설정·키 미설정을 구분하지 않고 403을 주므로(의도된 설계), "올바르지 않다"고만 단정하면 서버 미설정 상황에서 거짓말이 된다.

### 구현
- **폼 위치**: 키 없음 상태의 `#keyState`(`.key-state`) 안. `<form class="admin-login" id="loginForm">` + `input#adminPw`(type=password, `autocomplete="current-password"`, aria-label) + `button#loginBtn`. 기존 `#keySet`("키 입력", prompt) 버튼은 **보조로 그대로** 둔다.
- **Enter 제출**: form의 `submit` 이벤트를 `preventDefault()` 후 `login()` 호출 — 별도 keydown 핸들러 없이 브라우저 기본 동작을 그대로 쓴다.
- **에러 표시**: `#loginErr`(`.login-err`, `role="alert"`, `flex-basis:100%`)로 폼 아래 새 줄 인라인. 실패 시 폼은 유지하고 입력값을 남긴 채 `focus()+select()`.
- **진행 중 상태**: 버튼 `disabled` + "확인 중…" → 실패 시 "로그인"으로 복원.
- **응답 파싱**: `r.text()` 후 `try/JSON.parse` — 403/429가 본문 없이 오거나 비-JSON이어도 예외로 죽지 않는다(기존 `setStatus`는 `r.json()` 직행이라 이 내성이 없음. 신규 코드에만 적용).
- **레이아웃**: `.key-state`에 `flex-wrap:wrap` 추가 — 좁은 화면에서 폼이 다음 줄로 접힌다.
- **터치 타깃**: input·로그인·"키 입력" 모두 `min-height:40px`(기존 `.key-state button`은 `padding:4px 10px`로 ~26px이었음 → 40px로 상향).

### 보안 취급
비밀번호는 **요청 본문에만** 쓰고 어디에도 저장하지 않는다(localStorage·sessionStorage 미기록). 로그인 성공 시 폼 자체가 DOM에서 사라지므로 입력값도 함께 소멸 — 검증에서 성공 후 `document.body.innerHTML`에 비밀번호 문자열이 남지 않음을 확인했다. 저장되는 것은 서버가 돌려준 키뿐이고, 기존과 동일하게 sessionStorage(탭 종료 시 소멸).

### 검증 (2026-08-18, 로컬 정적 서버 8655 + `fetch` 모킹)
| # | 시나리오 | 결과 |
|---|---|---|
| V1 | 키 없음 → 폼 표시 | 통과 — `#adminPw`(password·current-password)·`#loginBtn` 존재, `#keySet` 유지, 높이 40/40/40px |
| V2 | 정상 로그인(200) | 통과 — 요청 `POST http://localhost:8080/api/admin/login` body `{"password":"…"}` CT json, `keyState.className="key-state ok"`("관리자 키 입력됨"), sessionStorage=응답 키, 폼 소멸, DOM에 비번 잔존 없음 |
| V3 | 오답(403) | 통과 — 지정 문구 표시, 폼·입력값 유지, 버튼 재활성화, sessionStorage `null` |
| V3b | 429 | 통과 — "시도 횟수를 초과했습니다 — 잠시 후 다시 시도해 주세요." |
| V4 | Enter 제출 | 통과 — 로그인 요청 정확히 1회, 페이지 이동·`?password=` 노출 없음 |
| V5 | 기존 `?key=` 진입 | 통과(회귀 없음) — ok 상태, URL에서 `key` 제거, 폼 미표시. "키 지우기" → 폼 복귀 |
| — | 모바일 390px | 통과 — 가로 스크롤 없음(scrollWidth 390 = innerWidth), 폼이 2줄로 wrap |
| — | 문법 | `node --check` OK |

### 미검증 / 전달
- **실서버 연동 미검증**: 위 200/403/429는 전부 `fetch` 모킹이다. 로컬 백엔드를 띄우지 않았으므로 실제 `AdminController`와의 응답 형식·CORS preflight(로그인은 `Content-Type: application/json`이라 preflight 발생)는 **dev-qa 경계면 검증 몫**. 코드 리뷰로는 계약 일치 확인(200 `Map.of("key", …)`, 403/429 `Map.of("message", …)`).
- 이 페이지는 사이드바 미노출·`noindex` 독립 페이지라 다른 페이지·`build_index.py`와 무관 — 캐시버스트·재빌드 불요.
