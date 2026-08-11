# 16. 아키텍처 결정 기록 (ADR)

경량 ADR 대장 — 형식·운영 규칙은 `.claude/skills/app-architecture/SKILL.md`. 새 결정은 아래에 누적하고, 뒤집을 때는 새 ADR로 "ADR-x를 대체"를 명시한다.

## ADR-1: 검색만 서버로 이관, 나머지는 정적 유지 (2026-08-08)
- 맥락: 가맹점 66k건(20MB+)을 클라이언트가 통째로 받아 필터 — 전송·메모리 부담. 반면 가이드 콘텐츠·온라인 목록은 KB급.
- 결정: 가맹점 검색·집계만 Spring+Postgres API로 이관. 페이지·온라인 목록은 정적 JSON 유지.
- 근거: 서버화의 이득(전송량·페이징)은 MB급 데이터에만 있다. 전면 서버화(SSR) 대안은 정적 호스팅(GitHub Pages)의 무비용·무장애 이점을 버려 기각.
- 결과·롤백: backend/ 신설(feat 브랜치). 롤백 = 프론트 dataMode를 json으로.

## ADR-2: 이중 데이터소스 dataMode(auto/api/json) + 폴백 대칭 불변식 (2026-08-09)
- 맥락: 백엔드 배포 전/장애 시에도 라이브가 죽으면 안 됨.
- 결정: config.js dataMode — auto는 API 프로브 실패 시 JSON 폴백. 서버 필터 규칙 ≡ 프론트 JSON 계산 규칙을 1급 불변식으로.
- 근거: 프록시·캐시 레이어 대안보다 단순하고, 정적 사이트가 최후 보루로 남는다. 대가: 규칙을 항상 양쪽에 구현(경계면 검증 필수 — dev-testing).
- 결과·롤백: 폴백 순간에도 동일 카운트. 스위치 한 줄로 모드 전환 가능.

## ADR-3: index.html은 번들 유지 + 빌더 재생성 (2026-08-06, 지속)
- 맥락: index는 4MB 자가해제 번들(외부 산출물). 직접 수정 시 D-F1(이스케이프 깨짐 → 백지)로 전체 붕괴.
- 결정: 직접 수정 금지. build_index.py가 .bak 원본에서 치환 스텝으로 재생성, "리터럴 </ = 0" 불변 검증.
- 근거: 번들을 일반 페이지로 재작성하는 대안은 축적된 DC 로직 재구현 비용·회귀 위험이 커 기각. 대가: 셸 변경도 빌더 스텝으로 우회(FOUC 방어 등).
- 결과·롤백: 모든 index 변경은 빌더 스텝 + 재실행. 롤백 = 스텝 제거 후 재생성.

## ADR-4: 브랜치 전략 — main=라이브 정적, feat/backend-scaffold=백엔드 (2026-08-08)
- 맥락: 백엔드 미완성 상태에서 라이브(정적)가 계속 배포돼야 함.
- 결정: backend/는 feat 전용(main 미추적). 공용 파일(프론트·데이터·.claude)은 양방향 동기화, config.js dataMode는 필요 시 브랜치별 상이 허용.
- 근거: 모노브랜치 대안은 Pages 빌드에 백엔드가 섞여 배포 리스크. 대가: doc-commit 절차에 동기화 단계 상존.
- 결과·롤백: 백엔드 안정화 후 main 병합으로 수렴 가능(현재는 유지).

## ADR-5: NCP 단일 VM + Docker Compose + 컨테이너 DB (2026-08-09)
- 맥락: 호스팅 결정(국내 클라우드). 관리형 DB(Cloud DB for PostgreSQL) 대안 검토.
- 결정: Server 1대에 Caddy(자동 TLS)+Spring+Postgres 컨테이너. DB는 pgdata 볼륨.
- 근거: 이 규모(단일 앱·78k행)에 관리형 DB는 비용·네트워크 구성 대비 과함. 향후 RAG 시 pgvector 이미지 교체가 자유로움. 대가: 백업 자가 관리(pg_dump).
- 결과·롤백: bootstrap.sh 원샷 배포. 관리형 전환 시 .env의 DB_URL만 교체.

## ADR-6: 온라인 사용처는 플랫폼 단위 태깅 (2026-08-10)
- 맥락: 온라인 브랜드×물품종류 2축 요구. 30개 몰은 각각 독립 사이트.
- 결정: 상품 크롤링 기각, 실측 기반 플랫폼 단위 태깅(online_catalog.json, taxonomy+items). 데이터가 KB급이므로 ADR-1에 따라 프론트 직행(online.html).
- 근거: 30개 이질 사이트 상품 크롤링은 구축·유지 비용이 효용 대비 과대, 봇차단·구조 변경에 취약. 대가: 태그는 스냅샷 — 부분노출 안내 문구로 한계 명시.
- 결과·롤백: 갱신은 재실측+JSON 수정. 상품 수준이 필요해지면 개별 몰 API 확인 후 하이브리드(15_report 근거).

## ADR-7: 지도범위(bounds) 검색은 전 지역 + region 생략 (2026-08-10)
- 맥락: bounds 검색이 시도 탭에 종속되면 지도 위치와 탭이 어긋날 때 0곳.
- 결정: bounds 활성 시 시도 무관 전 지역 검색 — API는 region 파라미터 생략, JSON은 4파일 합본. 지도 idle 시 자동 재검색(수동 재적용 제거).
- 근거: "지도에 보이는 것을 검색"이라는 사용자 멘탈 모델에 지역 개념이 없다. 대가: JSON 모드 첫 실행 시 전 파일 로드(로딩 안내로 완화).
- 결과·롤백: 서버는 hasBounds 분기(Specs). 롤백 = regionParams에 region 복원.

## ADR-8: 하네스 2팀 + 계약 테스트로 TDD 정착 (2026-08-10)
- 맥락: 백엔드 도입으로 단일 5인 팀의 개발 역량·검증 경계가 부족. 테스트 0개.
- 결정: 개발팀(frontend/backend/dev-qa/architect)·업무팀 분리, 단일 오케 라우팅. TDD 이중 게이트. 경계면 계약을 record 컴포넌트 테스트(ApiContractTest)로 코드화.
- 근거: 이 시스템 최악 결함("에러 없이 다른 숫자")은 사람 리뷰보다 계약 테스트가 값싸게 잡는다. 대가: 계약 변경 시 테스트·프론트 동시 수정 의무.
- 결과·롤백: ./gradlew test가 계약 파수꾼. 팀 구조는 오케 스킬에서 조정 가능.

## ADR-9: 셸(사이드바·토큰·폭토글) 공통화 — shell.css/shell.js (2026-08-10)
- 맥락: 셸 변경(폭 토글·CI·FOUC)마다 3곳(merchants·online·빌더) 수정 — 페이지당 ~110줄 중복.
- 결정: 공통 CSS를 shell.css, 드로어+폭토글 JS를 shell.js로 추출. 3페이지가 참조(index는 빌더가 link/script 주입). config.js의 폭토글 로직은 shell.js로 이관(설정 파일은 설정만).
- 근거: index가 이미 외부 리소스를 참조하므로 기술 제약 없음. 인라인 대안(현행)은 드리프트 반복. 대가: 셸 파일 캐시 갱신 시 ?v= 관리.
- 결과·롤백: 페이지별 전용 스타일은 각 페이지에 잔류. 롤백 = 파일 내용 재인라인.

## ADR-10: merchants.html 대분리는 유예 — 스트랭글러 (2026-08-10)
- 맥락: merchants 1,509줄(JS 1,042). 분리 욕구 vs 순수 이동의 회귀 위험.
- 결정: 지금은 셸 추출(ADR-9)로 1차 감량만. 데이터 레이어/지도 모듈 분리는 해당 영역에 다음 기능 요구가 올 때 그 모듈만 파일로 분리(스트랭글러).
- 근거: 동작 불변 순수 이동은 이득(가독성)보다 위험(이중소스·지도 이벤트 회귀)이 큼. 테스트 스냅샷이 두꺼워지는 시점에 재평가.
- 결과·롤백: 다음 지도/데이터 기능 작업 시 이 ADR을 재방문.

## ADR-11: 백엔드 CI/CD — GitHub Actions (2026-08-10)
- 맥락: 테스트 시드 도입 후 수동 실행 의존. 배포도 SSH 수동.
- 결정: CI = feat 푸시·backend/** 변경 시 gradlew test(JDK 21). CD = CI 통과 후 SSH로 NCP에 git pull+compose 재빌드 — GitHub Secrets(NCP_SSH_KEY 등) 등록 시에만 활성(미등록 시 스킵).
- 근거: 셀프호스티드 러너·레지스트리 푸시 대안은 이 규모에 과함. SSH 키는 사용자만 등록(비밀 취급 원칙).
- 결과·롤백: 워크플로 파일 삭제로 즉시 원복. 서버 수동 절차(DEPLOY.md)는 병행 유지.

## ADR-12: 챗봇 — RAG(pgvector) + 서버 도구 루프 + 플로팅 위젯 (2026-08-11)
- 맥락: 정책·사용처 질문을 대화로 해결하는 챗봇 요구. (1) RAG vs 파인튜닝 (2) 페이지 이동·검색 실행 툴킷 (3) 모노톤+오렌지 대화 UI(마크다운·mermaid).
- 결정: ①RAG — pgvector(HNSW)·text-embedding-3-small, 코퍼스=검증된 내부 자산+공식 채록(onnuri.gift FAQ 69건·voucher·공지, semas 4페이지). 파인튜닝 기각(날짜 종속 정보·재학습 비용·출처 인용 불가). ②서버(Spring gift.onnuri.chat)가 gpt-5.6-luna 도구 루프 소유(최대 3왕복): search_policy/search_online(RAG), search_merchants(기존 MerchantService 재사용), navigate(SSE action→위젯 확인 카드, 임의 이동 금지). 도구 결과는 대화 재주입("결과 재-RAG"). ③POST /api/chat SSE, stateless(이력은 프론트 sessionStorage→요청 동봉). 위젯=chat-widget.js/css, marked+DOMPurify+mermaid 첫 오픈 시 CDN 지연 로드, mermaid base 테마+오렌지 themeVariables. 최종 답변은 서버가 비스트리밍 수신 후 청크 SSE(OpenAI 스트림 파싱 복잡도 회피 — 응답 짧아 지연 허용).
- 근거: 키 보호(서버만)·폴백 대칭 원칙 유지. 비용 통제는 IP rate limit(분10·일200, 인메모리 — 단일 인스턴스 ADR-5). LLM 출력은 DOMPurify 필수(신뢰 불가 입력). 숫자는 도구 실시간 조회만(데이터 신선도 원칙).
- 결과·롤백: config.js chatEnabled=false로 위젯 즉시 제거 가능. db 이미지 pgvector/pgvector:pg16(pgdata 유지). 적재는 build_rag_corpus.py(멱등 전체 교체). OPENAI_API_KEY 미설정 시 챗만 비활성(다른 API 무영향). 갱신: 코퍼스 재수집 후 스크립트 재실행.

## ADR-13: 검색 API GET→POST + 착지 URL 파라미터 비노출 (2026-08-11)
- 맥락: 사용자 요청 — 검색 필터값(지역·브랜드·검색어)이 ①API GET 쿼리스트링(브라우저 히스토리·프록시·서버 액세스 로그) ②챗 이동 카드 착지 주소창에 노출.
- 결정: ①검색 5종(merchants·facets·map·regions·brands)에 POST(JSON body, SearchBody) 병행 추가 — 프론트는 POST만 사용, GET은 운영 curl·회귀 스크립트 호환용 유지. 프로브(size=1, 필터 없음)만 GET 잔존. ②챗 착지는 sessionStorage(onnuri_nav_filter) 핸드오프 — 페이지가 읽는 즉시 삭제, 주소창은 클린 URL. URL 파라미터 진입은 직접 링크 호환용 2순위로 유지.
- 근거: 필터값은 민감정보는 아니나 위치·관심사 이력이 로그에 남는 것 자체를 차단. GET 제거 대신 병행: 회귀 기준값 curl·외부 스크립트 비파괴. 계약은 SearchBodyTest가 고정.
- 결과·롤백: 프론트 apiGet 구현만 되돌리면 GET 복귀. 회귀 기준값 검증 명령(dev-testing)은 GET 그대로 유효.
