# 백엔드 NCP 배포 가이드

디지털온누리 가맹점 검색 백엔드(Spring Boot)를 **NCP Server(VM) 한 대**에 Docker Compose로 배포한다 — Postgres·Spring 앱·Caddy(자동 HTTPS)를 함께 올린다.

## 구성 개요

```
GitHub Pages (canduk910.github.io, HTTPS)
        │  fetch https://api.koscomlabor.cloud/api/...
        ▼
NCP Server(VM) ── Docker Compose ─────────────────────
   ├─ Caddy : 80/443 공개, Let's Encrypt 자동 TLS, → app:8080 프록시
   ├─ app   : Spring 컨테이너(8080, 외부 미공개)
   └─ db    : Postgres 컨테이너(pgdata 볼륨 영속, 127.0.0.1:5432만 노출)
──────────────────────────────────────────────────────
```

- **HTTPS 필수**: 프론트가 HTTPS(GitHub Pages)이므로 백엔드가 HTTP면 브라우저가 mixed-content로 차단한다. Caddy가 도메인 기반 무료 TLS를 자동 처리한다.
- **비밀값 비노출**: DB 비밀번호 등은 서버의 `.env`에만 둔다(저장소 커밋 금지, `.gitignore` 처리됨). 네이버 지도 키는 프론트(클라이언트 ID, 도메인 제한)로 유지 — 백엔드와 무관.

## 사전 준비 (사용자가 NCP 콘솔에서 수행)

Claude가 대신 수행할 수 없는 항목(계정·결제·콘솔 작업)이다. 모바일에서 콘솔 버튼이 밀리면 브라우저 "데스크톱 사이트 요청" 모드로 진행.

1. **NCP Server 생성**: Ubuntu 22.04, 최소 2 vCPU / 4 GB 권장(빌드 여유). 공인 IP 할당.
2. **ACG(방화벽)**: 인바운드 **80, 443**(전체), **22**(관리 IP만) 허용. 8080·5432는 열지 않는다(DB는 VM 내부·로컬만).
3. **도메인 연결**: 보유 도메인의 서브도메인 `api.koscomlabor.cloud` A레코드를 Server 공인 IP로 지정. (전파 후 `dig api.koscomlabor.cloud` 로 확인)

> DB는 별도 관리형 서비스가 아니라 **compose의 `db` 컨테이너**로 함께 뜬다. 백업은 `pgdata` 볼륨을 직접 관리한다.

## 배포 절차 (Server에서)

```bash
# 1) Docker + Compose 설치 (Ubuntu)
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER   # 재로그인 후 sudo 없이 docker 사용

# 2) 소스 가져오기 (main — 프론트·백엔드 단일 브랜치)
git clone https://github.com/canduk910/digital_onnuri.git
cd digital_onnuri/backend/deploy

# 3) 환경변수 작성 (서버에만 존재)
cp .env.example .env
vi .env    # DB_NAME / DB_USER / DB_PASSWORD / DB_URL / API_DOMAIN / TLS_EMAIL 입력

# 4) 빌드 + 기동 (db 컨테이너 기동 후, 앱 첫 부팅 시 Flyway가 스키마 생성)
docker compose -f docker-compose.prod.yml up -d --build

# 5) 앱 헬스 확인
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app   # "Started OnnuriApplication" 확인
```

### 데이터 적재 (최초 1회, 스키마 생성 후)

Flyway가 빈 테이블만 만든다. 66,211건 적재가 필요하다.

`bootstrap.sh`를 쓰면 아래가 자동 수행된다. 수동으로 할 경우:

```bash
# Server에서(파이썬) — db 컨테이너는 127.0.0.1:5432로 퍼블리시됨
sudo apt-get install -y python3-pip && pip3 install 'psycopg[binary]'
cd ~/digital_onnuri
DB_DSN="host=127.0.0.1 port=5432 dbname=onnuri user=onnuri password=<DB_PASSWORD>" \
  python3 backend/tools/load_merchants.py
# 출력: 적재 검증 {'경기': 29523, '서울': 29450, '인천': 7238} | 총 66211
```

> `load_merchants.py`는 멱등(TRUNCATE 후 재적재)이라 데이터 갱신 시 재실행하면 된다.

### 검증 (배포 직후 회귀 대조)

```bash
D=https://api.koscomlabor.cloud
curl -s "$D/actuator/health"                                            # {"status":"UP"}
curl -sG "$D/api/merchants" --data-urlencode region=서울 --data-urlencode gu=강남구 --data-urlencode dong=개포동 --data-urlencode size=1 | grep -o '"total":[0-9]*'  # 145
curl -sG "$D/api/merchants" --data-urlencode region=경기 --data-urlencode si=수원시 --data-urlencode gu=팔달구 --data-urlencode size=1 | grep -o '"total":[0-9]*'      # 1241
```

## 프론트 연결 (마지막 단계)

`merchants.html`의 `API_BASE`는 **이미 `https://api.koscomlabor.cloud/api`로 설정**돼 있다(비-로컬 호스트에서 사용). 별도 수정 불필요.

```js
// merchants.html — API_BASE 정의부 (설정 완료)
return "https://api.koscomlabor.cloud/api";
```

프론트·백엔드는 **단일 브랜치 `main`**에 함께 있다(2026-08-12 통합). `config.js`의 `dataMode: "auto"`로
백엔드 API를 우선 사용하되 장애 시 저장소 JSON으로 자동 폴백하므로, 백엔드가 없어도 페이지는 정상 동작한다.

## 자동 배포 (CI/CD — 2026-08-10 활성화)

`.github/workflows/backend-ci.yml`이 배포를 자동화한다. 수동 배포는 아래 "운영 메모"의 절차가 여전히 유효하다(장애 시 폴백).

- **트리거**: `main`에 `backend/**` 푸시 → 테스트(JDK 21, `./gradlew test`) → 통과 시 SSH 배포 → 헬스체크(2분 대기). 수동 재배포는 Actions 탭 → backend-ci → Run workflow.
- **인증**: 배포 전용 ed25519 키. 개인키는 GitHub Secrets `NCP_SSH_KEY`에만, 공개키는 서버 `~/.ssh/authorized_keys`에만 존재(저장소·채팅 비노출). 로컬 사본: `~/.ssh/onnuri_deploy_ed25519`.
- **배포 동작**: 서버에서 `git reset --hard origin/main` 후 **app 컨테이너만 재빌드**(`up -d --build app`) — `pgdata`(DB)·`caddy_data`(TLS 인증서) 보존. 데이터 재적재는 하지 않으므로, 데이터 갱신은 별도로 `load_merchants` 절차 수행.
- **선택 Secrets**: `NCP_HOST`(기본 api.koscomlabor.cloud), `NCP_SSH_USER`(기본 root).
- **키 회전**: 새 키 생성 → 서버 authorized_keys 교체 → `gh secret set NCP_SSH_KEY < 새키` → 옛 키 라인 삭제.

## 챗봇(RAG) 활성화 — ADR-12 (2026-08-11)

챗봇은 `OPENAI_API_KEY`가 있어야만 동작한다(미설정 시 챗만 비활성 — 다른 API 무영향).

```bash
# 1) db 이미지가 pgvector/pgvector:pg16인지 확인(compose에 반영됨) 후 재기동 — pgdata 볼륨은 유지된다
cd ~/digital_onnuri/backend/deploy
docker compose -f docker-compose.prod.yml up -d db
# 2) .env에 OPENAI_API_KEY=... 추가 (이 서버에만 — 저장소·채팅 금지)
# 3) 앱 재빌드 (V2 마이그레이션이 rag_chunk 테이블 생성)
docker compose -f docker-compose.prod.yml up -d --build app
# 4) RAG 코퍼스 적재 (호스트에서 — DB는 127.0.0.1:5432)
cd ~/digital_onnuri
OPENAI_API_KEY=$(grep ^OPENAI_API_KEY backend/deploy/.env | cut -d= -f2) \
DB_URL="postgresql://<DB_USER>:<DB_PASSWORD>@127.0.0.1:5432/<DB_NAME>" \
python3 _workspace/dev_scripts/build_rag_corpus.py
# 5) 검증: 위젯에서 1문답 + 아래 회귀 유지 확인
```

- 코퍼스 갱신: `_workspace/rag_corpus/` 재수집 후 4)만 재실행(전체 교체·멱등).
- 챗 비상 차단: main의 `config.js`에서 `chatEnabled:false` 배포(위젯 숨김).
- 상세 구조·원칙: `_workspace/17_chatbot_design.md`.

## 야간 배치 (00:30) — ADR-14 (2026-08-12)

매일 00:30(서버 로컬시각) 가맹점·온라인 데이터를 자동 갱신한다. `tools/nightly_update.py`가
단계 A(가맹점 stage-swap 무중단)·B(온라인 upsert)·C(RAG)를 fail-open으로 실행한다.
**A 실패만 배치 실패(exit≠0)**, B·C 실패는 로그만 남기고 기존 데이터를 유지한다.

> "기준" 스탬프(`meta.collected_on`)가 소스별로 다른 것은 **의도된 동작**이다 — API는 배치 수집일(최신),
> JSON 폴백은 파일 수집일(다소 낡음). 폴백은 API 장애 시 비상용이라 낡은 날짜가 표시될 수 있고, 그것이
> "언제 확인한 데이터인지"를 속이지 않는 정직한 표기다(버그 아님).

### 배치 전용 클론 (배포 클론과 분리)

CD(자동 배포)는 배포 클론에서 `git reset --hard`를 돌린다. 배치가 여기서 재수집 파일을 쓰면
충돌하므로 **배치는 별도 클론**을 쓴다.

```bash
# 배치 전용 클론(최초 1회) — 배포 클론(~/digital_onnuri)과 별개
git clone <repo-url> ~/onnuri_batch/repo
mkdir -p ~/onnuri_batch/logs
# psycopg 필요(호스트 파이썬)
pip3 install 'psycopg[binary]'
```

### 실행 스크립트 (flock 중복 방지 + 로그)

```bash
cat > ~/onnuri_batch/run.sh <<'SH'
#!/usr/bin/env bash
set -euo pipefail
REPO=~/onnuri_batch/repo
LOG=~/onnuri_batch/logs/$(date +\%Y\%m\%d).log
# db 컨테이너는 127.0.0.1:5432로 퍼블리시됨(.env의 값으로 치환)
export DB_DSN="host=127.0.0.1 port=5432 dbname=<DB_NAME> user=<DB_USER> password=<DB_PASSWORD>"
# RAG까지 돌리려면(선택): export OPENAI_API_KEY=$(grep ^OPENAI_API_KEY ~/digital_onnuri/backend/deploy/.env | cut -d= -f2)
cd "$REPO"
git pull --ff-only origin main
# flock: 앞 배치가 안 끝났으면 이번 회차는 건너뛴다
flock -n /tmp/onnuri_nightly.lock \
  python3 backend/tools/nightly_update.py --skip-rag >>"$LOG" 2>&1
SH
chmod +x ~/onnuri_batch/run.sh
```

- 비밀값은 스크립트에 하드코딩하지 말고 `.env`에서 읽거나 별도 관리. 위 `<…>`는 서버 `.env` 값으로 치환.
- RAG까지 자동화하려면 `--skip-rag`를 빼고 `OPENAI_API_KEY`를 export.

### crontab 등록 (서버 TZ 먼저 확인)

```bash
# 1) 서버 타임존 확인 — cron은 서버 로컬시각으로 돈다
timedatectl | grep 'Time zone'      # 예) Asia/Seoul (KST) 또는 UTC
date                                 # 현재 시각 육안 확인

# 2) crontab -e 로 아래 한 줄 추가
#   - 서버 TZ가 Asia/Seoul 이면: 00:30 KST
30 0 * * *  ~/onnuri_batch/run.sh
#   - 서버 TZ가 UTC 이면: 00:30 KST = 전날 15:30 UTC
30 15 * * * ~/onnuri_batch/run.sh
```

NCP 기본 이미지는 UTC인 경우가 많다 — **반드시 `timedatectl`로 확인 후** 위 두 줄 중 하나만 등록한다.

### 로컬 단계별 테스트 (스킵 플래그)

```bash
# 온라인만: 공식 API 순회 upsert(큐레이션 note·region_limited 보존 확인)
python3 backend/tools/nightly_update.py --skip-merchants --skip-rag
# 가맹점 스왑 로직만: 재수집(15분+) 생략, 기존 JSON으로 stage-swap 경로 검증(멱등)
python3 backend/tools/nightly_update.py --no-collect --skip-online --skip-rag
```

### 실패 시 확인

- 로그: `~/onnuri_batch/logs/<날짜>.log`. 각 단계 전/후 카운트·판정(OK/SKIP/FAIL)을 남긴다.
- **A 실패(가드 위반)**: "가드 위반 — <지역> old→new" 로그. stage는 자동 DROP, 기존 merchant 무결. 수집본이
  정상인지(공식 API 장애·부분 수집) 확인 후 수동 재실행. 가드 기준은 지역별 ±20%·총계 ≥ 50,000.
- **잔여 stage 테이블**: 정상 종료 시 `merchant_stage`/`merchant_old`는 남지 않는다. 남아 있으면 직전 실행이
  중단된 것 — `DROP TABLE IF EXISTS merchant_stage, merchant_old;` 후 재실행(다음 실행이 자동 DROP도 함).
- **B 실패(온라인)**: "B 스킵: <사유>" 로그(요청 실패·resCode≠0000·totalCnt<10). 배치는 계속 OK. 공식 API 상태 확인.
- **인덱스명**: 스왑 후 인덱스는 `merchant_*`(예 `merchant_cat_idx`), PK는 `merchant_pkey`. `merchant_stage_*`가
  남아 있으면 정규화 단계가 실패한 것 — 로그 확인.

## 운영 메모

- **업데이트 배포**: `git pull` 후 `docker compose -f docker-compose.prod.yml up -d --build`.
- **인증서**: `caddy_data` 볼륨에 영속. 재기동해도 재발급하지 않는다(레이트리밋 회피).
- **⚠ db 이미지 계열 교체 시 REINDEX 필수**: 알파인(musl)↔데비안(glibc) 계열이 다른 이미지로
  바꾸면(예: postgres:16-alpine → pgvector/pgvector:pg16) 콜레이션 버전이 달라져 **한글 텍스트
  B-tree 인덱스가 조용히 손상**된다 — 순차 스캔은 정상이라 일부 쿼리만 어긋나는 형태(2026-08-11
  실사고: 동작구 total=1·노량진동 0곳, 데이터는 무결). 교체 직후 반드시
  `REINDEX DATABASE onnuri;` 실행 후 회귀 기준값(동 단위 포함)으로 검증한다.
- **로그**: `docker compose logs app` / `logs caddy`.
- **버그 제보 상태 갱신**(report.html 게시판): 관리자 페이지 `admin-report.html`에서 접수↔반영 토글(2026-08-14).
  - 키 설정(최초 1회): 서버 `.env`에 `APP_ADMIN_KEY=<랜덤값>` 추가(`openssl rand -hex 24`로 생성) 후 `up -d --build app`.
    키가 비어 있으면 상태 변경 API(`POST /api/reports/{id}/status`, `X-Admin-Key` 헤더)는 전부 403 — 기능 비활성.
  - 접속: `https://onnuri.koscomlabor.cloud/admin-report.html?key=<APP_ADMIN_KEY>` — 첫 진입 시 키를 sessionStorage에 옮기고 URL에서 지운다.
  - 폴백(SQL 직접): `docker compose -f docker-compose.prod.yml exec -T db psql -U onnuri -d onnuri -c "UPDATE report SET status='반영' WHERE id=<번호>;"`
- **DB 백업**: `pgdata` 볼륨 백업. 예) `docker exec onnuri-db pg_dump -U onnuri onnuri > backup.sql`.
- **CORS**: 기본값(application.yml)에 `https://canduk910.github.io`·`https://koscomlabor.cloud`·`https://onnuri.koscomlabor.cloud` 포함(2026-08-15 서브도메인 이전 — ADR-15). 다른 오리진 추가 시 `.env`의 `APP_CORS_ALLOWED_ORIGINS` 주석 해제.
- **pgvector(RAG) 전환 시**: compose `db` 이미지를 `pgvector/pgvector:pg16`으로 교체(같은 `pgdata` 볼륨 유지 가능) 후 `CREATE EXTENSION vector`.

## 체크리스트

- [ ] NCP Server 생성 + ACG(80/443/22) 설정 (모바일이면 데스크톱 모드)
- [ ] 서브도메인 A레코드 → Server 공인 IP → `dig`로 전파 확인
- [ ] Docker/Compose 설치, 소스 clone
- [ ] `.env` 작성(DB 비밀번호·도메인·이메일)
- [ ] `bootstrap.sh` 실행 (또는 `up -d --build` + 데이터 적재)
- [ ] `app` 헬스 UP + 66,211건 적재 검증
- [ ] `https://api.koscomlabor.cloud` 회귀 검증(145·1241 일치)
- [ ] 백엔드 검증 후 `API_BASE` 반영분을 main에 머지 → 라이브 전환
