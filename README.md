# 코스콤 디지털온누리 가이드

코스콤 임직원을 위한 **디지털온누리상품권 사용처 안내 웹 가이드**. 어디서 쓸 수 있는지(오프라인 가맹점·온라인몰), 어떻게 결제하는지, 카드 실적은 어떻게 잡히는지를 한곳에서 확인하고, 서울·인천·경기·부산(코스콤·한국거래소 소재지)의 가맹점을 지도·목록으로 검색한다.

**라이브:** https://koscomlabor.cloud

> 계산대에서 결제 실패를 겪지 않는 것이 이 가이드의 품질 기준이다. 정책·사용처 정보는 날짜에 종속되므로, 데이터의 "언제 확인한 것인지"를 스탬프로 정직하게 표기한다.

---

## 주요 기능

| 페이지 | 설명 |
|--------|------|
| **가이드(index)** | 오프라인·온라인 사용 요건, 사용처 개요, 하위 페이지 진입 |
| **가맹점 찾기(merchants)** | 78,000여 곳을 지역 계층·업종·브랜드 다중 필터로 검색. 네이버 지도 연동(뷰포트 자동 도트/클러스터 전환), 리스트↔지도 병행 |
| **온라인 사용처 찾기(online)** | 온라인 가맹 플랫폼 30곳 + 취급 물품종류·브랜드 태깅 검색 |
| **결제 방법(payment)** | 카드형·QR형 결제 단계, 전월 실적·선차감·잔액부족 처리 |
| **용어·유의사항(terms)** | 골목형상점가·SSM·선차감 등 용어 풀이와 결제 전 유의사항 |
| **버그 제보(report)** | 익명 오류 제보 게시판 |
| **AI 챗봇** | 공식 출처 기반(RAG) 질의응답 + 페이지 이동·검색 실행. 전 페이지 플로팅 위젯 |

---

## 아키텍처

```
[사용자 브라우저]
      │
      ├─ 정적 프론트엔드 ──────── GitHub Pages (koscomlabor.cloud)
      │   index/merchants/online/payment/terms/report.html
      │   + 공통 셸(shell.css/js) · 챗 위젯 · 네이버 지도 SDK
      │
      └─ 검색·챗 API ─────────── NCP 서버 (api.koscomlabor.cloud)
          Docker Compose: Caddy(자동 HTTPS) + Spring Boot + Postgres(pgvector)
```

- **프론트엔드**: 빌드리스 정적 HTML/CSS/JS. GitHub Pages로 서빙.
- **백엔드**: Spring Boot + Postgres. 가맹점·온라인 검색 API, 챗봇(RAG), 방문자 카운트, 버그 제보를 제공. 소스는 `backend/`에 있다.
- **이중 데이터 소스**: 프론트는 백엔드 API를 우선 사용하되, 장애 시 저장소의 `data/*.json`으로 자동 폴백한다(`config.js`의 `dataMode: "auto"`). 백엔드가 없어도 페이지는 정상 동작한다.

### 단일 브랜치

프론트·백엔드가 모두 **`main` 한 브랜치**에 있다. GitHub Pages는 정적 파일을, GitHub Actions는 `backend/**` 변경 시 백엔드 CI/CD를 각각 처리한다(같은 브랜치에서 path 필터로 분리). `.env`·빌드 산출물은 `.gitignore`로 제외된다.

---

## 저장소 구조

```
├── index.html               # 빌드 산출물 — 직접 수정 금지(아래 참고)
├── merchants/online/payment/terms/report.html
├── shell.css / shell.js      # 3+ 페이지 공통 셸(사이드바·상단바·폭 조절·방문 카운트)
├── chat-widget.css / .js     # 챗봇 플로팅 위젯
├── online-source.js          # 온라인 목록 API↔JSON 이중소스 어댑터
├── config.js                 # 데이터 소스·챗봇 토글 등 런타임 설정
├── favicon.svg               # 코스콤 CI 오렌지 셰브론
├── data/
│   ├── merchants/{seoul,incheon,gyeonggi,busan}.json   # 가맹점(JSON 폴백)
│   ├── online_platforms.json   # 온라인 플랫폼 목록(폴백)
│   ├── online_catalog.json     # 온라인 취급품목·브랜드 태깅
│   └── offline_categories.json
├── _workspace/
│   ├── dev_scripts/          # 데이터 수집·빌드 스크립트
│   ├── 16_arch_decisions.md  # ADR(아키텍처 결정 기록)
│   └── NN_*.md               # 정책 분석·데이터 리포트·설계 문서
├── backend/                  # Spring Boot + 배포 자산(build/·.gradle/·.env 제외)
└── CLAUDE.md                 # 프로젝트 지침 + 상세 변경 이력
```

---

## 데이터

가맹점·온라인 목록의 **단일 진실 공급원은 백엔드 DB**이며, 저장소의 `data/*.json`은 API 장애 시 폴백이다(다소 낡을 수 있음). 그 외 콘텐츠(정책·문구)는 여전히 파일이 진실이다.

- **출처**: 온누리상품권 공식 홈페이지(onnuri.gift) 가맹점찾기·온라인 전통시장관 API, 소상공인시장진흥공단 안내, 관련 법령.
- **지역 범위**: 서울·인천·경기·부산 (코스콤·한국거래소 소재지).
- **자동 갱신**: NCP 서버 crontab이 **매일 00:30(KST)** 배치를 돌려 가맹점·온라인 목록을 공식 API에서 재수집한다. 가맹점은 전일 대비 급변(±20%) 시 기존 데이터를 유지하는 가드가 있고, 온라인은 사람이 큐레이션한 필드를 덮지 않는다. 상세: `backend/DEPLOY.md` 야간 배치 절, `_workspace/16_arch_decisions.md`(ADR-14).

---

## 빌드 · 개발

정적 파일이라 별도 빌드 도구가 없다. 로컬에서 확인하려면 저장소 루트에서 정적 서버를 띄운다.

```bash
python3 -m http.server 8655
# http://localhost:8655/index.html
```

### index.html은 직접 수정하지 않는다

`index.html`은 약 4MB 단일 번들 산출물이다. 소스는 빌더 스크립트이며, 수정 후 재생성한다.

```bash
python3 _workspace/dev_scripts/build_index.py
# 출력의 "리터럴 </ = 0" (D-F1 불변) 확인
```

데이터 수집·재빌드 스크립트는 `_workspace/dev_scripts/`에 있다(`build_region_full.py` 가맹점, `build_rag_corpus.py` 챗봇 코퍼스 등).

### 백엔드 (feat 브랜치)

```bash
cd backend
JAVA_HOME=<JDK21> ./gradlew test    # 계약 테스트
```

배포는 `main`에 `backend/**` 푸시 시 GitHub Actions가 자동 수행한다. 절차·운영 메모는 `backend/DEPLOY.md`.

---

## 문서

- **`CLAUDE.md`** — 프로젝트 제약과 전체 변경 이력(날짜별 무엇을·왜 바꿨는지).
- **`_workspace/16_arch_decisions.md`** — 아키텍처 결정 기록(ADR).
- **`_workspace/NN_*.md`** — 정책 분석, 데이터 수집 리포트, 챗봇·디자인 설계 문서.

---

## 면책

이 가이드의 안내와 챗봇 답변은 AI의 도움으로 작성·생성되어 정확하지 않을 수 있으며, **코스콤 내부 참고용**이다. 공식 안내가 아니므로 결제·이용 전 공식 채널(디지털온누리 앱, 고객센터 1670-1600)에서 최종 확인해야 한다.
