---
name: backend-server-dev
description: "가맹점 검색 백엔드(backend/, Spring Boot 3+JPA Specification+Postgres+Flyway) 개발 절차 — API 파라미터·집계 추가, SearchQuery/MerchantSpecs 확장, 스키마 마이그레이션, 적재기 수정, NCP 프로덕션 반영. 백엔드 코드·스키마·배포 구성을 만들거나 고칠 때, API 확장이나 서버 성능·운영 변경 요청 시 반드시 이 스킬을 사용할 것. (프론트 페이지 수정은 guide-page-build, 테스트 절차 자체는 dev-testing)"
---

# 백엔드 서버 개발 — 계약과 대칭을 지키는 절차

## 코드 구조 (레이어와 소유권)

```
backend/src/main/java/gift/onnuri/
├── merchant/
│   ├── Merchant.java            # @Entity — data/*.json 필드와 1:1, 스키마는 Flyway가 소유
│   ├── MerchantRepository.java  # JpaRepository + JpaSpecificationExecutor
│   ├── MerchantSpecs.java       # SearchQuery → Specification. 프론트 폴백 규칙과 1:1
│   ├── MerchantService.java     # 검색·facets·map·regionTree 집계
│   ├── MerchantController.java  # /api/** 계약의 표면
│   └── dto/                     # SearchQuery(record)·MerchantView·MapResult·PageResult·CountItem
└── config/CorsConfig.java       # 허용 오리진은 application.yml app.cors
```

## 확장 패턴 (기존 관례를 따른다)

**SearchQuery 확장**: record라 컴포넌트 추가 시 **모든 생성자 호출 지점이 깨진다** — Service의 withCat/withBrand/withMtype 헬퍼와 regionTree의 직접 생성 2곳을 반드시 함께 갱신. 편의 판정은 record 본문 메서드로(hasBounds 선례).

**필터 추가**: MerchantSpecs.from()에 `has()`(null/blank/"전체" 무시) 규칙으로 추가. **같은 규칙을 프론트 jRegionFiltered/jBase/jFull에도 동시에 넣는다** — 이중 소스 대칭이 이 시스템의 1급 불변식. 센티넬("동 미상")·특수 의미(bounds가 지역 대체) 같은 계약은 dto 주석에 프론트 참조를 남긴다.

**facet 추가**: Service에 withX(qy, null) 컨텍스트 헬퍼 + countBy, Controller facets 맵에 키 추가. 프론트 SNAP 소비부와 키 이름을 맞춘다.

**스키마 변경**: 새 Flyway `V{n}__*.sql`만 추가(기존 V 파일 수정 금지 — 체크섬 불일치로 기동 실패). 컬럼 타입은 Hibernate validate와 호환 확인(CHAR(1) 사건: VARCHAR(1) 사용). 적재기 `tools/load_merchants.py`의 COLS·FILES 동기화.

## TDD (dev-testing 스킬 준수)

새 동작은 실패하는 테스트부터. 순수 로직(record 메서드·정규화)은 스프링 없는 단위 테스트로, Specs·Service는 로컬 Postgres 전제 통합으로. 완료 조건 = `./gradlew test` 녹색 + 회귀 기준값 대조.

## 로컬 실행

```bash
cd backend
docker compose up -d                       # 로컬 Postgres (기존 볼륨에 적재 데이터 유지)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun
# 적재(멱등): python3 tools/load_merchants.py
```

시스템 JDK가 25라도 **JDK 21**로 실행한다(Gradle 8.10/Boot 3.3 호환). 검증 curl은 한글 파라미터에 `-G --data-urlencode`.

## 프로덕션 반영 (api.koscomlabor.cloud)

절차의 정본은 `backend/DEPLOY.md`. 요약: 서버 SSH → `cd ~/digital_onnuri && git pull`(feat 브랜치) → `cd backend/deploy && docker compose -f docker-compose.prod.yml up -d --build`. 데이터 갱신은 `bash bootstrap.sh`(멱등 재적재). 반영 후 dev-testing의 프로덕션 기준값 대조를 돌린다.

주의: NCP apt 미러엔 docker-compose-plugin이 없다(bootstrap은 get.docker.com 사용). `.env`는 서버에만 — 커밋 금지.

## 브랜치·커밋

backend/는 **main 브랜치**(프론트·백엔드 단일 브랜치). 커밋·푸시는 doc-commit 절차(문서 갱신→비밀 스캔→푸시). 프론트 계약을 함께 바꾼 경우 프론트 변경과 백엔드를 **한 커밋**에 담아 경계면이 갈라지지 않게 한다.

## 계약 변경 체크리스트 (하나라도 해당하면 frontend·dev-qa 사전 통지)

- [ ] 응답 필드 추가/이름 변경 (MerchantView·MapPin·CountItem)
- [ ] 파라미터 의미 변경 (기본 정렬, 상한, 센티넬)
- [ ] 필터 규칙 변경 (Specs 조건)
- [ ] 에러/빈 결과 형태 변경
