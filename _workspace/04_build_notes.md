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

---

## index.html 파비콘·탭 제목 소실 수정 (2026-08-18)

### 증상
`onnuri.koscomlabor.cloud` 접속 시 index(홈)에만 파비콘이 안 보이고, 탭 제목 자리에 URL이 뜬다. 나머지 7개 정적 페이지는 정상. **도메인 변경과 무관** — 2026-08-12 파비콘 도입 시점부터 index는 계속 이 상태였다.

### 근본 원인
번들 로더가 템플릿을 파싱해 문서 루트를 통째로 교체한다:

```js
const doc = new DOMParser().parseFromString(template, 'text/html');
document.documentElement.replaceWith(doc.documentElement);   // index.html:318
```

파비콘을 심은 곳은 `build_index.py` 8단계인데, 그 주석 그대로 **"외곽 `<title>` — 템플릿 밖 정적 라인"**만 고친다. 교체해 들어오는 템플릿 내부 `<head>`에는 `meta charset`·`viewport`·로더 script뿐이라 `<title>`도 `<link rel=icon>`도 없다. 결과적으로 JS 실행 순간 둘 다 소실.

실측(수정 전, 라이브): `document.readyState === "complete"` 시점에 `document.title === ""`, `link[rel=icon]` **0개**, `document.head` 링크 **0개**. 같은 조건에서 `merchants.html`은 둘 다 정상.

### 수정
`build_index.py`에 **7h 스텝** 신설 — 템플릿 내부 `<head>` 여는 태그 직후에 `<title>`·`<link rel=icon>` 주입. 외곽(8단계)은 그대로 둔다(언패킹 중 첫 화면용).

- **인코딩 앞에 두어야 한다.** `json.dumps` 뒤에 두면 조용히 무반영 — 2026-08-11 7g가 실제로 걸렸던 함정이라 주석에 명시했다.
- 중복 주입 가드: 템플릿 head에 `<title>`/`rel="icon"`이 이미 있으면 빌드 실패.
- **파비콘 참조는 상대 경로(`favicon.svg?v=1`)** — data URI 인라인도 검토했으나 기각. index 번들은 이미 `shell.css`·`shell.js`·`config.js`·`chat-widget.*`·`assets/koscom_ci.png`를 외부 참조하는 구조(서버 로그로 확인)라 자기완결이 아니고, 나머지 7페이지와 같은 참조를 쓰면 파비콘 교체 시 한 곳만 고치면 된다.

### verify_build.py (D-F1) 복구
검증 스크립트가 **이미 죽어 있었다.** 8단계가 외곽에 link 라인을 1줄 늘리면서 라인 인덱스가 통째로 밀려, (a) 무결성부터 (b) 템플릿 JSON 파싱까지 전부 FAIL — 2026-08-12 이후 D-F1이 사실상 무의미했다.

- (a)(b): 외곽 head 차이(제목 치환 + link 라인)를 되돌린 **정규화본**으로 비교하도록 수정.
- **(h) 절 신설**: 외곽 title/link **그리고 템플릿 내부** title/link 존재를 각각 검사. 외곽만 보면 이번 결함을 놓치므로 내부 검사가 회귀 방지의 핵심이다.

### 검증 (2026-08-18)
| # | 항목 | 결과 |
|---|---|---|
| V1 | 수정 전 RED | (h) 템플릿 내부 title·link **FAIL** 2건 — 결함이 검사에 잡힘 |
| V2 | 재빌드 후 GREEN | (h) 5개 항목 전부 PASS, (a)(b) 무결성·JSON 파싱 복구 |
| V3 | 실브라우저(로컬 8900, 캐시 없는 새 오리진) | 서버 로그에 `GET /favicon.svg?v=1 200` **실제 요청 확인** |
| V4 | 로드 완료 후 DOM | `document.title = "코스콤 디지털온누리 가이드"`, `link[rel=icon]` 1개, fetch 200 `image/svg+xml` |
| V5 | 렌더 회귀 | 사이드바·탭·표·챗 위젯 정상, `__bundler_err` 없음 |

### 미해결 — 별건 (verify_build.py 기존 드리프트 19건)
수정 전후 **동일하게** 실패하는 낡은 검사 19건이 남아 있다(내 변경으로 새로 깨진 것은 0건). 대부분 그동안의 의도된 변경을 검사 목록이 따라오지 못한 것으로 보인다:

- `id="payment"`, `{{ regionApps }}`, `대통령령 제36415호` 등 → 2026-08-11 payment/terms 페이지 분리로 index에서 제거된 토큰
- `--accent:#F26B1D`, `--sb-w:248px`, `.sb-item.active::before`, `matchMedia("(max-width:959px)")` 등 → 셸이 `shell.css`/`shell.js`로 외부화되며 템플릿에서 빠짐
- 웜톤 hex 잔존 `#26231F`·`#8A8580` → 2026-08-11 `TERMS_POINTER` 문자열에 하드코딩되어 재유입

각 항목이 "의도된 변경"인지 "진짜 회귀"인지는 건별 판단이 필요하므로 임의로 지우지 않았다 — 검사를 지우는 것은 회귀를 덮는 일이 될 수 있다.

---

## verify_build.py 낡은 검사 19건 정리 (2026-08-19)

파비콘 수정(2026-08-18) 때 발견한, 수정 전후 동일하게 실패하던 19건을 건별로 판정했다. 각 토큰이 **지금 어디에 있는지**(템플릿 / 다른 페이지 / shell.css / shell.js / 어디에도 없음)를 실제로 조회한 뒤 분류했다.

### 판정 결과 — 의도된 변경 18건, 진짜 회귀 1건

| 검사 토큰 | 현재 소재 | 판정 | 조치 |
|---|---|---|---|
| `{{ regionApps }}` · `각주 ③ 참고` · `대통령령 제36415호` · `가맹 제외 대상은 직영점 기준` | terms.html | 의도 (2026-08-11 분리) | index 검사에서 제거 → **(i)** 이관 검사로 |
| `{{ mflowArrow }}` · `모바일(앱)형 결제 흐름 — QR 방식` | payment.html에 **재작성**(문구 불일치) | 의도 | 제거 → (i)에서 `앱(QR)형`·`카드형`·`선차감`으로 검사 |
| `내부 · 수도권` | `내부 · 서울·인천·경기·부산` | 의도 (2026-08-10 부산 추가) | 현재 문구로 갱신 |
| `전국 가맹점을 지역별로` | 개정된 설명문 | 의도 | 현재 문구로 갱신 |
| `--accent` · `--sb-w` · `.sb-item.active::before` | shell.css | 의도 (2026-08-10 셸 공통화) | **shell.css 대상**으로 이관 |
| `--text:#17181A` · `--surface:#F7F7F7` · `--border:#E6E6E6` | shell.css에 **다른 값**(`#0B0C0E`·`#F6F6F7`·`#E5E6E8`) | 의도 (2026-08-10 잉크 블랙·그레이 재조정) | shell.css 현행 값으로 갱신 |
| `href="merchants.html#catChips"` | 없음 | 의도 (2026-08-10 메뉴 통합) | 검사 삭제 |
| `id="payment"` | 없음 | 의도 (2026-08-11 분리) | 삭제 → `href="payment.html"` 포인터 검사로 |
| `window.addEventListener("resize"` · `matchMedia("(max-width:959px)")` | shell.js | 의도 (2026-08-10) | **shell.js 대상**으로 이관 |
| 웜톤 hex `#26231F` · `#8A8580` | 템플릿(TERMS_POINTER) | **회귀** | 아래 |

### 진짜 회귀 — 7f 색상 매핑 우회

`build_index.py` 7f(웜톤→모노톤 일괄 치환)는 주석에 **"마지막에 한 번 수행"**이라고 전제를 적어뒀는데, 2026-08-11에 7g(payment·terms 분리)가 **그 뒤에** 추가되면서 전제가 깨졌다. 7g가 삽입하는 `TERMS_POINTER` 문자열이 매핑을 통과하지 않아 웜톤이 되살아났다. 7f 자체의 잔존 검사도 7g보다 먼저 끝나므로 잡지 못했다.

- 수정: `TERMS_POINTER`의 hex를 COLOR_MAP 목표값으로 직접 교정 (`#8A8580→#6B6E73`, `#26231F→#17181A`).
- 재발 방지: **7g-guard** 신설 — 7f 뒤에 문자열을 삽입하는 스텝이 또 생겨도 웜톤 잔존을 빌드 시점에 잡는다.
- 부수 효과(접근성): 각주 12px 본문의 흰 배경 대비가 **3.65:1(AA 미달) → 5.12:1(AA 통과)**로 개선됐다. 렌더 실측 `rgb(107,110,115)`·`rgb(23,24,26)`.

### (i) 이관 무결성 절 신설

검사에서 토큰을 **지우기만 하면 "이관됨"과 "소실됨"을 구분할 수 없다.** 그래서 목적지 실물과 index의 포인터를 함께 본다:

- index에 `href="payment.html"`·`href="terms.html"` 포인터 존재
- payment.html에 `앱(QR)형`·`카드형`·`선차감`
- terms.html에 `대통령령 제36415호`·`가맹 제외 대상은 직영점 기준`·`regionApps`

이 절을 만들면서 payment.html에 앱(QR)형 섹션이 온전한지(QR 13회 언급, `<h2>앱(QR)형 — 실물 카드 없이 폰으로</h2>`) 실제로 확인했다 — 콘텐츠 소실은 없었다.

### 검증
- 검사 항목 **83개(64 PASS + 19 FAIL) → 84개 전부 PASS**. 검사를 지워서 통과시킨 것이 아니라 순증했다(이관 검사 8개 추가).
- 재빌드: `7g-guard` 통과, D-F1 리터럴 `</` = 0.
- 렌더: `#terms` 문단 색 실측 일치, 포인터 링크 2개 존재, `__bundler_err` 없음, 탭 제목 유지.

### 남은 관찰 (수정 안 함 — 판단 필요)
1. **디자인 토큰 이원화**: index 인라인 스타일은 7f 매핑의 구 모노톤(`#17181A`·`#6B6E73`·`#E6E6E6`·`#F7F7F7`)을 쓰고, shell.css는 2026-08-10 재조정 팔레트(`#0B0C0E`·`#585D64`·`#E5E6E8`·`#F6F6F7`)를 쓴다. 육안차는 거의 없으나 토큰 출처가 둘이다. 통일하면 index 전역 색이 바뀌는 **시각 변경**이라 임의로 하지 않았다.
2. **죽은 computed**: 템플릿에 `const regionApps =` 정의는 남아 있으나 `{{ regionApps }}` 사용처는 terms.html로 갔다. 무해하지만 정리 후보.

---

## 디자인 토큰 출처 단일화 (2026-08-19)

index 인라인 색과 `shell.css` 팔레트가 갈라져 있던 것을 `shell.css` 기준으로 합쳤다. 사용자 승인 후 진행(비교 자료로 판단).

### 무엇이 갈라져 있었나
index 본문의 인라인 색은 빌더 `7f` 매핑표의 목표값에서, 사이드바·셸의 색은 `shell.css`에서 왔다. 같은 역할인데 값이 달랐다 — 2026-08-10 UI 대비 강화 때 `shell.css`만 새 팔레트(잉크 블랙)로 갔고 `7f` 매핑표는 그대로 남았기 때문이다.

### 변경 — 소스 12곳 → 템플릿 121곳
| 역할 | 구 index 인라인 | 통일 후 (= shell.css) | 템플릿 반영 |
|---|---|---|---|
| `--text` | `#17181A` | `#0B0C0E` | 21곳 |
| `--text-sub` | `#6B6E73` | `#585D64` | 33곳 |
| `--border` | `#E6E6E6` | `#E5E6E8` | 33곳 |
| `--surface` | `#F7F7F7` | `#F6F6F7` | 19곳 |
| `--surface-2` | `#F0F0F0` | `#EFF0F2` | 14곳 |
| (매핑 누락분) | `#9A968F` | `#585D64` | 1곳 |

`7f` COLOR_MAP의 **우변만** 바꾸면 되므로 좌변(원본 웜톤 탐지)은 그대로다.

### 매핑을 빠져나간 웜톤 1곳
`#9A968F`(R>G>B 웜 그레이)가 온라인 진입 카드의 11.5px 보조 안내문에 남아 있었다. 2026-08-10 S16 작성 때 새로 도입된 값이라 원본 번들에는 없었고, `7f` COLOR_MAP에도 웜톤 잔존 검사 목록에도 없어 **양쪽 그물을 다 빠져나갔다**.

대체값은 `--text-sub`(`#585D64`)로 정했다. `--text-faint`(`#989DA5`)가 위계상 자연스러워 보이지만 11.5px 본문에서 흰 배경 대비가 **2.73:1로 AA 미달**이고, 원래 값 `#9A968F`도 2.94:1이었다. 위계는 글자 크기(11.5px vs 12.5px)가 이미 만들고 있으므로 색까지 흐릴 이유가 없다. 결과 **2.94:1 → 6.64:1**.

웜톤 검사 정규식 2곳(7f 본검사·7g-guard)에 `9A968F`를 추가해 재발을 막았다.

### 판단 근거 (변경 전 실측)
| 색 쌍 | ΔE(CIE76) | 흰 배경 대비 변화 |
|---|---|---|
| `#6B6E73`→`#585D64` | 7.21 | 5.12:1 → **6.64:1** |
| `#17181A`→`#0B0C0E` | 4.95 | 17.77:1 → **19.57:1** |
| `#E6E6E6`→`#E5E6E8` | 1.09 | 1.25:1 → 1.25:1 |
| `#F7F7F7`→`#F6F6F7` | 0.60 | — |
| `#F0F0F0`→`#EFF0F2` | 1.08 | — |

배경·선 3종은 ΔE<1.1로 사실상 같은 색이고, 실제로 달라지는 건 텍스트 2종인데 둘 다 진해지는 방향이라 대비가 올라간다. 동일 조건(1080px) 렌더 픽셀 비교에서 **뚜렷한 변화 0.06%**, 미세 변화 3.43%가 전부 글자·선 가장자리에 몰렸다. 레이아웃은 불변(색값만 바뀌므로).

### 검사 추가
`verify_build.py` (g)에 **출처 일치 검사 8개**:
- 구 모노톤 5종이 템플릿에 잔존하지 않을 것
- `#0B0C0E`·`#585D64`·`#E5E6E8`이 shell.css와 템플릿 **양쪽**에 존재할 것

한쪽만 고치고 나머지를 잊는 것이 이 저장소의 상습 결함이라(7f 우회 회귀가 그랬다) 양쪽 동시 존재를 조건으로 걸었다.

### 검증 (2026-08-19)
- **인라인 hex 10종 전부 shell.css 토큰과 1:1 대응** — 대응 없는 고아 색 0.
- D-F1 전체 통과, 7g-guard 통과, 리터럴 `</` = 0.
- 렌더 실측: 각주 `rgb(88,93,100)`=`#585D64`, 본문 `rgb(11,12,14)`=`#0B0C0E`, 구 `#9A968F` 자리 `rgb(88,93,100)`, 사이드바 활성 항목 정상, `__bundler_err` 없음, 표·필터·챗 위젯 정상.
