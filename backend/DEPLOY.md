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
단계 A(가맹점 stage-swap 무중단)·B(온라인 upsert)·C(RAG)·D(채록 탐지)·E(조회 카나리아)를
fail-open으로 실행한다. 배치 전체 실패로 치는 것은 **A 단계 실패뿐**이다.
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

### 단계 D — 온라인 취급품목·브랜드 변화 탐지 (2026-08-22, ADR-16)

매 회차 온라인 몰 3~4곳을 열어 카테고리·브랜드를 채록하고, 현재 `data/online_catalog.json`과
비교해 **새로 생긴 것만** 로그·리포트로 남긴다. 22곳을 일주일에 한 바퀴 돈다.

> **데이터를 자동으로 고치지 않는다.** 커밋도 푸시도 하지 않는다. 단계 B(온라인 플랫폼)는
> 공식 API라 계약이 안정적이지만 채록은 HTML 스크래핑이다. 사이트 개편이나 지연 로드로
> 절반만 걷힌 회차를 자동 반영하면 데이터가 조용히 나빠진다. 반영은 사람이 리포트를 보고 결정한다.

이 단계는 **선택**이다. node나 playwright가 없으면 로그만 남기고 건너뛴다(배치 실패 아님).

#### 설치 — 시스템 Node를 교체하지 말 것 (2026-08-23 실제 구성)

이 서버에는 온누리 외 다른 서비스(`koscomlabor-web/api/db`)도 돌고, `koscomlabor-web/deploy.sh`는
매일 00:10에 `npm run build`를 수행한다. 시스템 `nodejs`(우분투 기본 v12)를 NodeSource 등으로
갈아치우면 apt의 `node-*` 의존 패키지가 연쇄 제거되고 그쪽 배포가 깨질 수 있다.
**배치 전용 Node를 따로 두고 PATH로만 앞세운다.**

```bash
# 1) 배치 전용 Node 20 (시스템 node 는 v12 그대로 둔다)
cd /tmp && V=v20.18.1
curl -fsSL -o node.tar.xz "https://nodejs.org/dist/$V/node-$V-linux-x64.tar.xz"
mkdir -p /opt/node20 && tar -xJf node.tar.xz -C /opt/node20 --strip-components=1 && rm node.tar.xz
/opt/node20/bin/node -v          # v20.18.1
/usr/bin/node -v                 # v12.22.9 — 그대로여야 정상

# 2) playwright + chromium (PATH 를 앞세워야 한다. npm 셔뱅이 `env node` 라 그냥 쓰면 v12 를 집는다)
export PATH=/opt/node20/bin:$PATH
cd ~/onnuri_batch/repo
npm i playwright
npx playwright install --with-deps chromium   # 브라우저 + 시스템 의존 라이브러리(~115MB)
```

`run.sh`에 두 줄을 넣는다. **PATH가 핵심이다** — cron은 PATH가 최소라 이게 없으면
`D 스킵: node 없음`으로 넘어간다(2026-08-23 실제로 그렇게 찍혔다):

```bash
export PATH=/opt/node20/bin:$PATH
export SURVEY_OUT_DIR=~/onnuri_batch/survey   # 날짜별 JSON 리포트(생략하면 로그로만)
```

이미 Chrome이 깔린 서버이거나 playwright 번들과 캐시 버전이 어긋나면 채널을 지정한다:

```bash
export PLAYWRIGHT_CHANNEL=chrome
```

설치 후 cron과 같은 최소 환경에서 확인한다(전체 배치를 기다릴 필요 없다):

```bash
env -i HOME=/root PATH=/usr/bin:/bin /bin/bash -c '
  export PATH=/opt/node20/bin:$PATH
  cd /root/onnuri_batch/repo
  python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag'
```

단독 실행(수동 재실측·점검용):

```bash
cd ~/onnuri_batch/repo
node backend/tools/survey_nightly.js              # 오늘 몫 3~4곳
node backend/tools/survey_nightly.js --all        # 22곳 전부
node backend/tools/survey_nightly.js --ids cyso   # 특정 몰만
python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag   # D만
```

리포트를 읽는 법:

| 표시 | 뜻 | 할 일 |
|---|---|---|
| `새 브랜드` / `새 카테고리` | 현재 카탈로그에 없는 값이 관찰됨 | 확인 후 반영 판단 |
| `※ 기획전 딥링크` | 몰 루트가 아닌 기획전/전용관 링크 | 호스트 몰 전체 GNB가 섞였을 수 있음 — **온누리 결제 범위인지 확인 필수** |
| `[의심] 본문이 얇다` | 수집이 반쯤 실패했을 가능성 | 사이트 개편·로그인 요구 여부 확인 |
| `[실패]` | 페이지를 열지 못함 | URL 변경 여부 확인 |

반영할 때는 `data/online_catalog.json`을 고치고 `_workspace/15_online_catalog_report.md`에 근거를 남긴다.
**확인하지 못한 몰의 `surveyed_on`은 올리지 않는다** — 화면 스탬프가 그 날짜를 근거로 계산된다.

### 가맹점 수집 방식 — 좌표 격자 (2026-09-01 개편)

공식이 가맹점 API 를 v2(`/api/v2/onr/...`)에서 v3(`/api/v3/onrgt/...`)로 옮기고 v2 를
닫았다(`resCode 9998`). v3 의 결정적 차이는 **좌표가 필수이고 반경 2km 로 고정**이라는
점이다 — `addrCd` 만 주면 0건이고 `baseRange` 는 어떤 값을 넣어도 무시된다. 그래서
기존의 "구·군 addrCd 순회"가 성립하지 않는다.

`build_region_full.py` 는 **2.8km 좌표 격자**(반경 2km 원이 완전히 덮는 정사각형 한 변)를
순회해 모으고, 응답의 `addrCd` 로 시도·구를 확정한다. 계층 원천은 여전히 API 다 —
`POST /api/v3/onrgt/addr/{시도코드}` 가 구·군 목록을 준다.

| 항목 | 실측(2026-09-01) |
|---|---|
| 격자 지점 | 1,251 (시드 457 + 이웃 1칸) |
| 요청 | 약 1,284 (지점당 1.03페이지) |
| 소요 | 약 22분 (0.7초 스로틀) — 구 방식은 약 200초 |
| 커버리지 | 99.56% |

**`run.sh` 의 pull 은 산출물을 먼저 버려야 한다.** 배치가 매일 `data/merchants/*.json` 과
후보 CSV 를 새로 쓰는데, `set -e` + `git pull --ff-only` 조합이라 그 변경이 남아 있으면
pull 이 실패하고 **배치 전체가 죽는다**(2026-09-01 실제로 그랬다 — 저장소의
`data/merchants` 를 갱신한 순간 서버 로컬과 충돌).

```bash
git checkout -- data/merchants _workspace/13_brand_candidates.csv 2>/dev/null || true
git pull --ff-only origin main >>"$LOG" 2>&1
```

**격자 시드**는 `_workspace/raw/merchant_grid_seed.json` 에 **누적** 저장된다. 매번 결과로
덮어쓰지 않는다 — 그날 0건이던 셀이 빠지면 다음 날 그 자리에 새 가맹점이 생겨도 영영
못 본다. 시드 파일이 없으면 기존 `data/merchants/*.json` 좌표로 만든다.

주의할 두 가지:

- **`addrCd` 필터는 필수다.** 격자가 시도 경계를 넘어 인접 지역(대구·경남 등)까지
  물어온다(실측 9,803건). 안 걸러내면 "부산 사람에게 대구 가맹점"이 나간다.
- **구·군 이름을 주소에서 뽑지 않는다.** 2026년 인천 자치구 개편(중구·동구 → 제물포구 등)이
  API 목록에는 반영돼 있는데 **가맹점 주소 문자열에는 옛 이름이 다수 남아 있다**
  (28125 의 주소 두 번째 토큰: 중구 767 · 동구 580 · 제물포구 45). 주소로 판정하면
  화면에서 신설 구가 사라진다.

### 가맹점 갱신이 멈췄을 때 (2026-09-01)

단계 A 가 실패하면 배치가 `app_meta` 에 두 값을 남기고, `/api/meta` 가 그대로 노출해
`merchants.html` 이 화면 상단에 경고를 띄운다.

| 키 | 뜻 |
|---|---|
| `merchants_stale_since` | **첫 실패일**(매일 덮어쓰지 않는다 — 덮으면 며칠째인지 알 수 없다) |
| `merchants_stale_reason` | 사유 한 줄 |

성공하면 배치가 두 키를 지운다. 남아 있으면 정상인데도 화면이 경고를 띄운다.

왜 만들었나 — 2026-08-29 온누리가 가맹점 API 를 v2→v3 로 옮기며 v2 를 닫았고, 배치는
설계대로 fail-open 해 기존 데이터를 지켰다. 그런데 **나흘 동안 아무도 몰랐다.**
로그에만 `판정 FAIL(가맹점)` 이 남았고 화면은 "매일 00:30 자동 최신화"라고 말하고 있었다.
fail-open 은 데이터를 지키지만 **사실을 알리지는 않는다** — 알리는 것은 별도 장치가 필요하다.

상태 확인·수동 조작:

```bash
docker exec -it onnuri-db psql -U onnuri -d onnuri -c \
  "SELECT k, v FROM app_meta WHERE k LIKE 'merchants%';"

# 수동 해제(원인을 고친 뒤에만)
docker exec -it onnuri-db psql -U onnuri -d onnuri -c \
  "DELETE FROM app_meta WHERE k IN ('merchants_stale_since','merchants_stale_reason');"
```

### 단계 E — 실시간 조회 판정 카나리아 (2026-08-31, ADR-17)

실시간 조회는 세 갈래로 **조용히** 깨진다. 없음-문구가 바뀌면 없는 것을 `likely`로,
문구 오탐이 넓어지면 있는 것을 `none`으로(가장 위험), `titlePattern`이 낡으면 근거 없이
카운트만으로 `likely`가 된다. 어느 쪽도 에러를 내지 않는다.

**배치는 파서를 갖지 않는다.** 앱의 셀프테스트를 하루 한 번 부르고 결과만 남긴다 —
판정은 이용자가 받는 것과 정확히 같은 경로가 한다. 요청량은 몰당 2질의 × 6곳 = 하루 12건.

`run.sh`에 두 줄을 더한다(`APP_ADMIN_KEY`는 제보 관리자 페이지와 같은 값이고, `$ENV`는
run.sh가 이미 위에서 정의한 서버 `.env` 경로다):

```bash
# app 은 포트를 호스트에 노출하지 않는다(XFF 스푸핑 방지 — 위 "신뢰 프록시 1홉" 참조).
# 그래서 localhost:8080 이 아니라 Caddy 를 거쳐 자기 도메인으로 부른다.
# 하루 1회 12요청이라 TLS 왕복 비용은 무시할 만하다.
export APP_BASE_URL=https://api.koscomlabor.cloud
# `|| true` 가 필요하다 — run.sh 는 set -e 라 키가 없으면 grep 이 1 을 반환해
# 배치 전체가 죽는다. 단계 E 는 fail-open 이어야 하므로 빈 값으로 넘긴다.
export APP_ADMIN_KEY=$(grep ^APP_ADMIN_KEY "$ENV" | cut -d= -f2- || true)
```

키가 비면 **스킵**한다(실패가 아니다 — 로그에 `E 스킵: APP_ADMIN_KEY 없음`). 앱이 죽어 있거나
403이어도 로그만 남기고 배치는 계속된다.

리포트는 `SURVEY_OUT_DIR`에 `probe-canary-YYYY-MM-DD.json`으로 쌓인다(단계 D와 같은 위치).
어제 리포트가 있으면 응답 길이를 비교해 ±50% 넘는 변화를 함께 알린다 —
판정이 아직 맞더라도 몰 개편의 조기 신호다.

수동 실행:

```bash
cd ~/onnuri_batch/repo
python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag --skip-survey   # E만

# 배치를 거치지 않고 직접 보고 싶을 때(20~30초 걸린다 — 가장 느린 몰이 8초다)
ENV=~/digital_onnuri/backend/deploy/.env
curl -s -m 150 -H "X-Admin-Key: $(grep ^APP_ADMIN_KEY "$ENV" | cut -d= -f2-)" \
  https://api.koscomlabor.cloud/api/online/search/selftest | python3 -m json.tool
```

로그를 읽는 법:

| 표시 | 뜻 | 할 일 |
|---|---|---|
| `✗ … [absent] 기대=none 실제=likely` | 없음-문구가 바뀌었다 | `ProbeTargets`의 해당 몰 마커 재실측 |
| `✗ … [present] 기대=likely 실제=none` | **문구 오탐이 넓어졌다(가장 위험)** — 있는 걸 없다고 말하는 중 | 즉시 해당 몰 마커 점검 |
| `✗ … 상품명 샘플이 없다` | `titlePattern`이 낡았다 | 검색 결과 HTML을 받아 마크업 재확인 |
| `· … echoesQuery 선언과 실측이 다르다` | 토큰 0 판정의 전제가 흔들린다 | 선언값 재검토 |
| `· … 응답 길이 A→B (±%)` | 몰이 개편됐을 수 있다 | 판정이 아직 맞아도 한 번 열어 본다 |
| `! robots … 전면 차단 X → Y` | robots.txt가 바뀌었다 | 차단으로 바뀌었으면 **즉시 대상에서 뺀다**. 풀렸으면 대상 확대 검토 |

2026-09-01 서버 가동 확인 — cron 최소 환경에서 12건 전부 통과, 16초. 키를 비운 모의 실행에서
배치가 죽지 않고 E만 스킵되는 것도 확인했다. 첫날은 비교할 어제 리포트가 없어 응답 길이
경고가 나오지 않는다(정상).

규칙을 고친 뒤에는 실제 6곳을 두드려 확인한다(평소 CI에서는 돌지 않는다):

```bash
cd backend && PROBE_LIVE=1 ./gradlew test --tests '*SelfTestLiveTest' --rerun -i
```

**실패해도 배치가 조회 기능을 끄지 않는다.** 자동 비활성화는 조용한 축소이고,
ADR-16이 채록 자동 반영을 기각한 논리와 같다 — 끌지 말지는 사람이 정한다.

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
  - 비밀번호 설정(2026-08-18, 로그인용): 서버 `.env`에 `APP_ADMIN_PASSWORD=<기억 가능한 비밀번호>` 추가 후 `up -d --build app`.
    비면 로그인 API(`POST /api/admin/login`)는 전부 403 — 로그인 비활성(키 직접 입력 방식은 그대로 동작). 무차별 대입 방지로 IP당 분 5회·일 30회 초과 시 429.
  - 접속: `https://onnuri.koscomlabor.cloud/admin-report.html` — 비밀번호로 로그인하면 서버가 `APP_ADMIN_KEY`를 돌려주고, 그 키를 sessionStorage에 둔다.
    `?key=<APP_ADMIN_KEY>` 직접 진입도 유지(첫 진입 시 키를 sessionStorage로 옮기고 URL에서 지운다).
  - 폴백(SQL 직접): `docker compose -f docker-compose.prod.yml exec -T db psql -U onnuri -d onnuri -c "UPDATE report SET status='반영' WHERE id=<번호>;"`
- **⚠ Caddy 앞단에 CDN·LB를 추가하면 rate limit이 깨진다**(2026-08-18): 세 한도(로그인·제보·챗)는
  `X-Forwarded-For`의 **마지막** 값을 클라이언트 IP로 쓴다 — 현 구성이 Caddy 1홉이라 마지막 값이
  실제 IP이고 위조가 불가능하기 때문이다(첫 값을 쓰면 클라이언트가 헤더를 위조해 한도를 통째로 우회한다).
  홉이 하나 늘면 마지막 값이 그 중계자 IP가 되어 **모든 이용자가 한 버킷을 공유**한다(정상 이용자가
  서로의 한도에 걸려 차단된다). 앞단을 추가할 때는 `gift/onnuri/web/ClientIp.java`와 Caddy
  `trusted_proxies`를 함께 다시 정할 것.
  같은 이유로 **`app` 서비스에 `ports:`를 추가해 직접 노출하지 말 것** — Caddy를 거치지 않은 요청은
  XFF를 통째로 클라이언트가 지어낼 수 있어 한도 우회(F-6)가 그대로 재발한다. 현 구성의 `expose: 8080`을 유지한다.
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
