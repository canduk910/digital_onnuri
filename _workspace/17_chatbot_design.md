# 17. 챗봇 설계·운영 문서 (ADR-12)

작성 2026-08-11. 온누리 가이드 챗봇의 구조·코퍼스·운영 절차. 다음 갱신의 델타 기준.

## 구조 요약

```
위젯(chat-widget.js, 3페이지 공통)
  └─ POST /api/chat (SSE: token/action/done/error)     ← 계약: ChatContractTest ↔ chat-widget.js
       ChatController — RateLimiter(IP 분10·일200) → ChatService 도구 루프(최대 3왕복)
         ├─ search_policy  : RagRepository(pgvector cosine top-6) ← rag_chunk
         ├─ search_online  : 동일, source ∈ (online_catalog, online_platforms)
         ├─ search_merchants: MerchantService 재사용 (총수+예시 5곳)
         ├─ navigate       : SSE action 이벤트 → 위젯 확인 카드 → URL 파라미터 착지
         └─ OpenAiClient   : gpt-5.6-luna(챗)·text-embedding-3-small(질의 임베딩)
```

- 모델·키: `application.yml` `app.openai.*` — `OPENAI_API_KEY`는 서버 .env에만. 미설정 시 챗만 비활성(error 이벤트 안내).
- stateless: 서버는 대화를 저장하지 않는다. 위젯이 sessionStorage(`onnuri_chat_hist`) 최근 10턴을 요청에 동봉.
- URL 착지: merchants `?region&si&gu&dong&cat&brand&q` / online `?tab&q&kind&cat&brand` / index `#hash` (applyUrlParams).

### navigate 계약 (params — 위젯이 그대로 실어 나른다)

| page | 파라미터 | 비고 |
|---|---|---|
| merchants | region(서울\|인천\|경기\|부산)·si·gu·dong·cat·brand·q | region 없이는 검색 화면이 전체로 열린다 |
| online | **tab(live\|browse)**·q·kind(shopping\|delivery)·cat·brand | 2026-09-02 신설 — 아래 표 |
| guide | hash(offline\|online) | index 앵커 |
| payment / terms | 없음 | 페이지 이동만 |

**online 착지 탭(2026-09-02, online.html 2탭 분리)**

| 질문 유형 | tab | 함께 보내는 값 | 예 |
|---|---|---|---|
| 상품명으로 묻는다 | `live` (상품 실시간 검색) | q=상품명 | "로봇청소기 어디서 사?" |
| 품목·브랜드·구분으로 묻는다 | `browse` (몰 둘러보기) | kind/cat/brand | "가전 파는 온누리몰", "배달앱 뭐 있어" |

- `tab` 은 **선택 필드**다. 없으면 프론트가 규칙으로 정한다(q 만 있으면 live, kind/cat/brand 가 있으면 browse)
  — 옛 프론트·옛 백엔드 어느 조합이든 깨지지 않는다.
- 허용값은 도구 스키마 enum(`live`/`browse`)으로 좁히고, 서버도 `ChatService.navigate` 에서 한 번 더 검문한다.
  화면이 모르는 값이 실려 가면 프론트 규칙 폴백으로 떨어져 **에러 없이 다른 탭**에 착지하기 때문이다.
  입력 지점이 둘(도구 인자 `tab` · `params` JSON 안의 `tab`)이라 같은 창구에서 함께 거른다.
- `page` 가 online 이 아닌데 tab 이 오면 **오류가 아니라 무시**한다(카드는 정상 표시).
- **live 착지는 자동 조회를 실행하지 않는다** — 이용자가 조회 버튼을 누른다. 시스템 프롬프트가
  "조회했다"는 말을 금지하고, ChatContractTest 가 그 지시를 고정한다.
- `chat-widget.js` 는 params 를 그대로 sessionStorage(`onnuri_nav_filter`)·`onnuriApplyChatFilter` 에
  넘기므로 **위젯 수정이 필요 없다**(2026-09-02 확인).

## RAG 코퍼스 (rag_chunk)

| source | 원천 | 성격 | 갱신 방법 |
|---|---|---|---|
| onnuri_faq | onnuri.gift `/api/v2/onr/faq` 69건 전량(축제 카테고리만 제외) | 공식 | `_raw/faq_complete.json` 재수집(주의: 페이지 파라미터는 `currPage`) |
| onnuri_voucher_guide | onnuri.gift/voucher 채록 정제 | 공식 | rag_corpus/*.md 수기 갱신 |
| onnuri_notices | 공지 중 정책 관련(할인율 7%·수납기관·유효기간·자동충전 등) | 공식 | 동일. 이미지 공지는 제목 팩트만(본문 추측 금지) |
| semas_program_guide | semas.or.kr 4페이지(안내·지류·디지털·구매및사용) 정제 | 공식 | 동일. 옛 체계(제로페이·BC 무기명) 서술 제외 원칙 |
| onnuri_customer_center | 온누리 고객센터(1670-1600) 유선 확인 사항 — 공식 문서 미기재분 | 공식 채널(유선) | rag_corpus/onnuri_customer_center.md 수기 갱신. **답변에 확인일·유선 확인임을 반드시 병기**(공식 FAQ와 출처 등급이 다름) |
| internal_policy_analysis | _workspace/01_policy_analysis.md | 내부 검증 | 원본 문서 갱신 시 자동 반영 |
| offline_categories / online_platforms / online_catalog | data/*.json (SSOT) | 실측 | JSON 갱신 시 자동 반영 |

적재: `python3 _workspace/dev_scripts/build_rag_corpus.py` (전체 교체·멱등, `--dry-run`/`--selftest` 지원).
2026-08-11 기준 130청크. V2__rag_chunk.sql(HNSW cosine) 선행 필요 — db 이미지 `pgvector/pgvector:pg16`.

## 인텐트 사전필터 (2026-08-11)

본 호출 전에 `OpenAiClient.isOnTopic(질문, 직전답변)` 경량 분류(luna, reasoning_effort none,
structured output {on_topic}, ~200tok)로 범위 밖 질문을 차단한다 — 표준 거절(`ChatService.OFF_TOPIC_REPLY`)을
본 루프 없이 즉시 스트림(실측 ~2초). 설계 원칙: **애매하면 통과**(정상 질문 오탐 방지 — 최종 방어선은
시스템 프롬프트 거절), **분류 실패는 fail-open**(분류기 장애가 챗을 죽이면 안 됨). 후속 질문 오탐 방지를
위해 직전 답변 300자를 맥락으로 동봉. 계약: IntentGateTest(범위밖=본호출 0회·거절문구, 범위내=통과, 맥락 전달).

## 원칙 (기존 프로젝트 원칙의 챗 확장)

1. **숫자는 도구만**: 가맹점 수·존재 여부는 search_merchants 결과만 인용(코퍼스에 숫자 없음).
2. **확인 안 되면 모른다**: 코퍼스·도구 밖 내용은 "확인된 정보 없음" + 공식 채널(1670-1600, onnuri.gift) 안내.
3. **출처·수집일 병기**: 시스템 프롬프트가 강제. 기준일 = rag_chunk min(collected_on).
4. **서비스 지역 명시**: 가맹점 데이터는 서울·인천·경기·부산만 — 그 외는 공식 지도 안내.
5. **XSS**: LLM 출력은 DOMPurify 통과 후에만 DOM 삽입(마크다운·mermaid SVG 모두).
6. **임의 이동 금지**: navigate는 확인 카드만 — 사용자가 눌러야 이동.

## 운영 절차

- **서버 최초 활성화**: ① compose db를 pgvector/pgvector:pg16로 재기동(pgdata 유지) ② `.env`에 OPENAI_API_KEY 추가 ③ app 재빌드 ④ 서버에서 `build_rag_corpus.py` 실행(적재) ⑤ 라이브 1문답 검증.
- **코퍼스 갱신**: 공식 사이트 재채록 → rag_corpus/*.md·_raw 갱신 → 스크립트 재실행. collected_on 갱신 원칙은 가이드와 동일(확인한 것만 날짜 올림).
- **비용**: luna $0.10/$0.60 per Mtok — 1문답 ≈ 1.5원, rate limit이 상한(일 200회/IP).
- **장애 시**: config.js `chatEnabled:false` 배포로 위젯 즉시 숨김(다른 기능 무영향).

## 검증 기준 (dev-testing 연동)

- `./gradlew test`: ChatContractTest(SSE 이벤트·DTO 이름·시스템 프롬프트 지시·navigate tab 검문), RateLimiterTest(분·일·IP 독립), 기존 계약 유지.
- 파이프라인: `build_rag_corpus.py --selftest`.
- 시나리오 5종(위젯): 정책(출처 인용)·가맹점 연동(숫자 일치+이동 카드)·온라인·mermaid 렌더·범위 밖 거절.
