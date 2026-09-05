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

Flyway가 빈 테이블만 만든다. 데이터 적재가 필요하다(2026-09-05 기준 **79,800건** — 매일 바뀐다).

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
curl -sG "$D/api/merchants" --data-urlencode region=서울 --data-urlencode gu=강남구 --data-urlencode dong=개포동 --data-urlencode size=1 | grep -o '"total":[0-9]*'  # 144~146
curl -sG "$D/api/merchants" --data-urlencode region=경기 --data-urlencode si=수원시 --data-urlencode gu=팔달구 --data-urlencode size=1 | grep -o '"total":[0-9]*'      # 1250~1270
```

> **이 값들은 고정 상수가 아니다.** 가맹점은 야간 배치가 매일 새로 수집하므로 조금씩 움직인다
> (2026-08-10 적재 당시 145·1241 → 2026-09-05 실측 145·**1259**). 그래서 **범위**로 적는다 —
> "정확히 1241 이 아니면 실패"로 읽으면 정상인데 실패로 판정하거나, 몇 번 겪은 뒤 아예 안 보게 된다.
>
> 진짜로 보아야 할 것은 **자릿수가 맞는가**와 **0 이 아닌가**다. 크게 어긋나면(예: 절반)
> 적재가 덜 됐거나 필터가 깨진 것이다. 정확한 회귀 대조는 이 값이 아니라 배치 자신의
> ±20% 가드(단계 A3)와 카나리아(단계 E)가 한다.

```bash
```

### 프론트 배포 직후 라이브 실측 (2026-09-05 신설)

백엔드 회귀 대조와 별개로 **배달된 화면**을 잰다. 로컬에서 통과한 것과 라이브에 나간 것은
다른 질문이다 — 캐시버스트를 빠뜨리거나 배포에서 파일이 빠지면 **한 조각만 옛것**이 나가고,
`merchants.html` 은 여섯 조각(CSS·거리뷰·스플리터·리사이저·저장·브랜드팝업·상세팝업)으로
나뉘어 있어 **하나가 404 여도 나머지는 멀쩡히 돈다**(조용히 반쪽이 된다).

```bash
export ONNURI_BASE=https://onnuri.koscomlabor.cloud   # 생략하면 로컬 http://localhost:8655
node _workspace/dev_scripts/test_merchants_smoke.js     # 모듈 6종 로드·표·손잡이·핸들·마커
node _workspace/dev_scripts/test_saved_live.js          # 즐겨찾기·최근 본·공유 15경로
node _workspace/dev_scripts/test_brandmodal_live.js     # 브랜드 검색 팝업 14경로
node _workspace/dev_scripts/test_infowindow_live.js     # 지도 상세 팝업 16경로
```

- **로컬로 돌릴 때 포트는 8655 고정**이다(`python3 -m http.server 8655`). 네이버 지도
  Client ID 가 도메인+포트 허용 목록이라 다른 포트는 401 이고 지도가 아예 안 뜬다.
- Playwright + Chrome 이 필요하다. `NODE_PATH` 로 playwright 위치를 알려 줘야 할 수 있다.
- 실동작 조회 카나리아(단계 E)는 앱이 스스로 돈다 — 아래 「단계 E」 절 참조.

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
단계 A(가맹점 stage-swap 무중단)·B(온라인 upsert)·C(RAG)·D(채록 탐지)·F(상품명 색인)·E(조회 카나리아)를
fail-open으로 실행한다. 배치 전체 실패로 치는 것은 **A 단계 실패뿐**이다.
**A 실패만 배치 실패(exit≠0)**, B·C 실패는 로그만 남기고 기존 데이터를 유지한다.

> "기준" 스탬프(`meta.collected_on`)가 소스별로 다른 것은 **의도된 동작**이다 — API는 배치 수집일(최신),
> JSON 폴백은 파일 수집일(다소 낡음). 폴백은 API 장애 시 비상용이라 낡은 날짜가 표시될 수 있고, 그것이
> "언제 확인한 데이터인지"를 속이지 않는 정직한 표기다(버그 아님).

> **배치는 00:30 시점의 `origin/main` 을 본다 — 낮에 푸시한 것은 그날 밤에야 반영된다.**
> `run.sh` 가 배치 시작 직후 `git pull` 하므로, 그 뒤에 푸시한 배치 관련 변경(`backend/tools/**`·
> `data/*.json`)은 **다음 회차까지 하루를 기다린다.** 2026-09-04 에 실제로 그랬다 — 전날 밤 늦게
> 푸시한 저장소↔DB 드리프트 감시가 그날 배치에 없었다(그 감시를 만든 목적이 바로 조용한 어긋남을
> 막는 것이었다). CD 와 달리 배치에는 "지금 반영" 경로가 없다.
> 급하면 손으로 당긴다 — `run.sh` 와 같은 순서를 지켜야 산출물 때문에 pull 이 죽지 않는다:
>
> ```bash
> cd ~/onnuri_batch/repo
> git checkout -- data/merchants _workspace/13_brand_candidates.csv 2>/dev/null || true
> git pull --ff-only origin main
> ```

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
ENV=~/digital_onnuri/backend/deploy/.env
# 비밀값은 서버 .env에서 런타임에 읽는다(이 파일에 하드코딩 금지)
DB_NAME=$(grep ^DB_NAME "$ENV" | cut -d= -f2)
DB_USER=$(grep ^DB_USER "$ENV" | cut -d= -f2)
DB_PASSWORD=$(grep ^DB_PASSWORD "$ENV" | cut -d= -f2)
export DB_DSN="host=127.0.0.1 port=5432 dbname=$DB_NAME user=$DB_USER password=$DB_PASSWORD"
export DB_URL="postgresql://$DB_USER:$DB_PASSWORD@127.0.0.1:5432/$DB_NAME"
# 단계 C(RAG 코퍼스 재적재)용
export OPENAI_API_KEY=$(grep ^OPENAI_API_KEY "$ENV" | cut -d= -f2)
# 단계 D(채록)용 Node 20 — 시스템 node 는 v12 라 playwright 가 안 돈다.
# 다른 서비스(koscomlabor-*)가 시스템 node 를 쓸 수 있어 교체하지 않고 배치에서만 앞세운다.
export PATH=/opt/node20/bin:$PATH
export SURVEY_OUT_DIR=~/onnuri_batch/survey
# 단계 E(실시간 조회 카나리아)용. app 은 포트를 호스트에 노출하지 않는다(XFF 스푸핑 방지)
# — 그래서 Caddy 를 거쳐 자기 도메인으로 부른다. 하루 1회라 비용은 무시할 만하다.
# `|| true` 필수: set -euo 라 키가 없으면 grep 이 1 을 반환해 **배치 전체가 죽는다.**
# 단계 E 는 fail-open 이어야 하므로 빈 값이면 E 만 스킵되게 둔다.
export APP_BASE_URL=https://api.koscomlabor.cloud
export APP_ADMIN_KEY=$(grep ^APP_ADMIN_KEY "$ENV" | cut -d= -f2- || true)
cd "$REPO"
# 배치가 만든 산출물(data/merchants·후보 CSV)은 버리고 당긴다. set -e + --ff-only 라
# 산출물이 남아 있으면 pull 이 실패해 배치 전체가 죽는다 — 2026-09-01 실제로 그랬다
# (저장소의 data/merchants 를 갱신한 순간 서버 로컬과 충돌).
git checkout -- data/merchants _workspace/13_brand_candidates.csv 2>/dev/null || true
git pull --ff-only origin main >>"$LOG" 2>&1
# flock: 앞 배치가 안 끝났으면 이번 회차는 건너뛴다
flock -n /tmp/onnuri_nightly.lock \
  python3 backend/tools/nightly_update.py >>"$LOG" 2>&1
SH
chmod +x ~/onnuri_batch/run.sh
```

> **이 블록은 2026-09-05 에 서버의 실제 `run.sh` 와 대조해 맞춘 정본이다.**
> 종전 블록은 2026-08-22 상태에 멈춰 있어 **그대로 복사하면 여섯 단계 중 셋이 조용히
> 안 돌고, 산출물 충돌이 나는 순간 배치 전체가 죽었다.** 빠져 있던 것과 그 결과:
>
> | 없던 줄 | 없으면 |
> |---|---|
> | `export PATH=/opt/node20/bin:$PATH` | 시스템 node v12 를 집어 **단계 D 스킵** |
> | `export APP_BASE_URL`·`APP_ADMIN_KEY` | **단계 E(카나리아) 스킵** |
> | `export OPENAI_API_KEY` (옛 블록은 대신 `--skip-rag`) | **단계 C 스킵** |
> | `export SURVEY_OUT_DIR` | 리포트가 기본 위치로 흩어진다 |
> | `git checkout -- data/merchants …` | `pull` 실패 → `set -e` 로 **배치 전체 사망** |
>
> **여기를 고칠 때는 서버의 실제 파일도 같이 고칠 것.** 이 문서와 서버가 갈라지면
> 다음 사고 때 이 블록이 아니라 서버를 믿어야 하는데, 그 사실을 아는 사람이 없어진다.

- 비밀값은 스크립트에 하드코딩하지 않는다 — 위 블록처럼 `.env`에서 런타임에 읽는다.
- 서버에 `/opt/node20` 이 없으면 단계 D 가 스킵된다(설치는 아래 「단계 D」 절).

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

### 단계 F — 온라인 상품명 색인 (2026-09-02, ADR-18)

실시간 조회가 닿지 않는 몰(아래 표 — 곳 수는 RECIPES 가 정한다)을 야간에 한 번 열어 **상품명과 주소만** 걷고,
`online_product_index` 테이블을 몰 단위로 교체 적재한다. 단계 D 다음, 단계 E 앞에 돈다.

**어느 쪽도 검색이 아니다** — 둘 다 시장·매장이 자기 상품을 내놓는 경로이고, 질의어를 실을 자리가 없다.

| 몰 | 무엇을 받나 | 요청 |
|---|---|---|
| 온누리 놀장 | sitemap 의 시장 페이지에 그려진 상품명(Next.js 서버 렌더라 DOM 에서 걷는다) | 시장 수(14) |
| 인어교주해적단 | 온누리 매장 목록 → 매장 화면이 스스로 받는 메뉴 목록 | 매장 수 + 1 |

**둘 다 화면이 그려져야 상품이 보여 브라우저가 필요하다.** playwright가 없거나 크롬이 뜨지 않으면
크롤러가 종료코드 2로 끝나고 이 단계는 스킵된다(어제 색인은 그대로 남는다).
`run.sh`의 `export PATH=/opt/node20/bin:$PATH`가 여기서도 전제다 — 시스템 `node` v12를 집으면 문법 단계에서 죽는다.

> 한때 기획전·스토어를 fetch만으로 걷는 **정적 레시피 경로**가 있었다(11번가·공영쇼핑·롯데ON).
> 셋 다 실시간 조회 대상이 되면서 쓰는 레시피가 없어져 경로째 지웠다. 다시 필요하면
> 2026-09-03 이전 이력에 `get()`·`STATIC_UA`·`decodeXmlText`가 그대로 있다.

> 지니어스몰은 **색인 대상이 아니다.** 2026-09-02 에 `?search={q}` 정적 검색이 확인되어
> 실시간 조회 대상이 됐고, 앱은 색인 층에서 실시간 대상을 걸러 낸다(ADR-18). 색인으로 걷어도
> 화면에 닿지 않으므로 레시피를 두지 않는다.

> **11번가 온누리마켓·공영쇼핑·롯데ON 상생스토어도 색인 대상이 아니다.** 2026-09-03에 셋 다
> 전체 검색의 온누리 필터로 실시간 조회 대상이 됐다(19절 6-10-1·6-10-2).
> 같은 원칙(색인 ∩ 실시간 = ∅)으로 레시피를 지웠다.

> **색인은 "지금 있다"고 말하지 않는다.** 말할 수 있는 것은 "어제 이 몰이 이 이름의 상품을
> 올려 두고 있었다"까지다. 그래서 실시간 조회의 상태 목록(none/likely/…)에 섞지 않고
> 별도 층으로 그린다(ADR-18). 결제 가능 여부는 각주 그대로다.

이 단계는 **선택**이다. node·playwright가 없거나 `online_product_index` 테이블이 아직
없으면(V8 마이그레이션 전) 로그만 남기고 건너뛴다 — 배치 실패가 아니다.

설치는 단계 D와 같다(같은 Node 20 + Playwright를 쓴다). `run.sh`에 `export PATH=/opt/node20/bin:$PATH`가
이미 있으면 추가 작업이 없다. 리포트는 `SURVEY_OUT_DIR`에 `product-index-YYYY-MM-DD.json`으로
쌓인다(단계 D·E와 같은 위치).

#### 적재 규칙 — 반쯤 걷힌 회차로 색인을 덮지 않는다

크롤은 HTML·화면 응답에 기댄다. 사이트가 느리거나 개편 중이면 **에러 없이 절반만 걷힌다.**
그대로 반영하면 이용자에게는 "어제까지 있던 상품이 사라진" 것으로 보인다. 그래서 몰마다 여러 겹으로 막는다.

| 겹 | 어디서 | 판정 | 걸리면 |
|---|---|---|---|
| 수집(0건) | `index_nightly.js` `harvestGuard` | 한 건도 못 걷으면 `ok:false` | 그 몰을 실패로 표시(적재 시도 안 함) |
| 수집(커버리지) | 레시피의 `warn` | 열려던 곳의 절반도 못 읽으면 `ok:false` — 놀장=sitemap 시장 수, 인어교주=온누리 매장 수 | 위와 같음 |
| 수집(범위) | 롯데ON 레시피 | 응답의 `dshopNm` 에 '온누리'가 없으면 `ok:false` — 스토어가 바뀌면 다른 몰 상품이 들어온다 | 위와 같음 |
| 적재 | `nightly_update.py` `_index_guard` | 새 건수가 **DB 기존 건수**의 50% 미만이면 유지 | 어제 색인을 그대로 둔다 |

어느 몰도 "상품이 총 몇 개"인지 말해 주지 않는다. 그래서 건수 자체가 아니라 **커버리지**를 센다 —
열려던 곳 대비 실제로 읽은 곳의 비율이다. 롯데ON만 요청이 하나뿐이라 셀 커버리지가 없어,
대신 응답이 스스로 밝히는 스토어 이름으로 범위를 확인한다.

한 몰의 적재가 실패해도(외래키 위반·컬럼 초과 등) 그 몰만 유지하고 나머지는 계속한다.

#### 실시간 조회 대상이 된 몰은 크롤하지 않는다 (2026-09-03)

앱의 `IndexJudge`가 색인 층에서 실시간 조회 대상을 뺀다(ADR-18 — 한 몰이 두 층에서 다른 말을
하지 않게). 그래서 그런 몰을 걷어 적재해도 **앱이 그 행을 아예 읽지 않는다.** 몰당 수십~수백
페이지를 걷어 상대 사이트에 부담만 주고 얻는 것이 0이고, **배치 로그는 정상으로 찍혀 조용하다.**

단계 F는 셀프테스트 응답의 `robots[]`·`probeEndpoints[]`에 실린 `platformId`(= 실시간 조회 대상)와
레시피 id를 대조해, 겹치는 몰을 크롤에서 뺀다. 목록은 새로 실을 것이 없다 — 앱이 `ProbeTargets`에서
파생시키므로 대상이 늘거나 줄면 따라온다. 건너뛸 때 로그를 남긴다.

```
! 색인 건너뜀: tpirates 는 실시간 조회 대상이다(앱이 색인 행을 읽지 않는다). 레시피에서 지울 것.
```

**앱 응답이 없는 회차(`app-unreachable`·`no-admin-key`)에는 평소대로 크롤한다** — 모르면 하던 대로다.
여기서 건너뛰면 앱이 잠깐 안 뜬 날 색인이 통째로 비어 적재 50% 가드에 걸린다.

저장소에서는 `RECIPES ∩ ProbeTargets = ∅`를 테스트가 지키지만 그건 **저장소 상태**다. 배치는
`git pull`한 클론에서 돌고 앱은 CD로 따로 배포되니, pull이 실패한 날이나 배포 시차에는 앱이 앞서고
배치가 뒤처진 상태가 실재한다(2026-09-01에 pull 실패로 배치가 죽은 적이 있다). 이 가드는 그때 값을 한다.

셀프테스트는 **한 회차에 한 번만** 부른다 — 단계 F와 단계 E가 같은 응답을 나눠 쓴다.
그 호출은 앱이 몰들에 실제 조회를 보내는 카나리아라, 두 번 부르면 상대 사이트에 가는 요청이 두 배가 된다.

첫 적재(기존 0건)는 언제나 통과한다 — 기준이 없으면 비교할 것도 없다.
적재는 몰 단위 트랜잭션 `DELETE` → `INSERT`라 도중에 죽어도 **그 몰만** 비지 않는다.

#### 예의 (상대 사이트 부담)

호스트당 요청 간격 1초 이상(레시피의 `intervalMs` — 둘 다 1초),
몰당 요청 상한(놀장 40·인어교주 200),
이미지·폰트·미디어·분석 스크립트 차단(호스트명으로만 판정 — 주소 문자열 포함으로 막으면
몰 자신의 `uploads.js` 같은 스크립트까지 걸려 화면이 안 그려진다).
**검색 API를 직접 부르거나 번들의 토큰을 재사용하지 않는다**(ADR-18 기각 대안).
인어교주해적단의 상품 목록은 화면을 열면 브라우저가 스스로 보내는 요청의 응답을 읽는다.

색인 크롤러가 두드리는 호스트는 단계 E의 robots 감시가 매일 다시 본다. 목록은 손으로 적지 않는다 —
배치가 `node index_nightly.js --print-hosts`로 `RECIPES`의 `hosts`를 직접 받아 간다
(아래 "robots 감시" 절).

단독 실행(수동 점검용):

```bash
cd ~/onnuri_batch/repo
node backend/tools/index_nightly.js                      # 2곳 전부, 요약만
node backend/tools/index_nightly.js --ids tpirates        # 특정 몰만
node backend/tools/index_nightly.js --limit 5 --out /tmp  # 페이지 상한을 낮춰 시험
python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag --skip-survey --skip-canary   # F만
```

> **DB_DSN 이 필요하다.** `nightly_update.py` 의 기본 DSN 은 로컬 개발용 기본값이라 서버에서는
> 인증 실패로 즉시 죽는다(2026-09-02 수동 실행에서 실제로 그랬다). `run.sh` 가 `.env` 에서 만드는
> `DB_DSN` 을 같은 방식으로 먼저 export 한다:
>
> ```bash
> ENV=~/digital_onnuri/backend/deploy/.env
> export DB_DSN="host=127.0.0.1 port=5432 dbname=$(grep ^DB_NAME $ENV|cut -d= -f2) user=$(grep ^DB_USER $ENV|cut -d= -f2) password=$(grep ^DB_PASSWORD $ENV|cut -d= -f2)"
> export PATH=/opt/node20/bin:$PATH SURVEY_OUT_DIR=~/onnuri_batch/survey
> ```

#### 롤백

색인 층을 통째로 내리려면 두 가지를 한다. 실시간 조회 층에는 영향이 없다.

```bash
# 1) 배치가 더는 수집·적재하지 않게
#    run.sh 의 nightly_update.py 호출에 --skip-index 를 붙인다
# 2) 이미 들어간 색인을 비운다 (화면의 색인 블록이 사라진다)
docker compose exec -T db psql -U <DB_USER> -d <DB_NAME> -c 'DELETE FROM online_product_index;'
```

한 몰만 내리려면 `index_nightly.js`의 `RECIPES` 표에서 그 줄을 빼고
`DELETE FROM online_product_index WHERE platform_id='<id>';`를 실행한다.
(레시피 id는 테스트가 `data/online_platforms.json`과 대조하므로 오타는 즉시 잡힌다.)

#### 서버 반영 시 1회 정리 (2026-09-02)

지니어스몰·11번가 온누리마켓·공영쇼핑·롯데ON 상생스토어를 색인 대상에서 뺐다(넷 다 실시간 조회 대상이 됐다).
이 커밋 이전 배치가 한 번이라도 돌아 그 행이 남아 있으면 지운다(멱등 — 행이 없으면 0건 삭제로 끝난다).
앱은 이미 그 행을 읽지 않으므로 화면 변화는 없다.

```bash
docker compose exec -T db psql -U <DB_USER> -d <DB_NAME> \
  -c "DELETE FROM online_product_index WHERE platform_id='genius-mall';"
docker compose exec -T db psql -U <DB_USER> -d <DB_NAME> \
  -c "DELETE FROM online_product_index WHERE platform_id IN ('11st-onnuri-market','gongyoung-shopping','lotte-on-sangsaeng-store');"
```

### 저장소↔DB 온라인몰 목록 드리프트 경고 (2026-09-03)

단계 B 는 공식 목록에서 **새 몰을 받아 DB 에만** 넣는다(`ec-{post_no}` 로 id 를 만든다). 저장소
`data/online_platforms.json` 은 사람이 따라와야 하는데, 잊으면 **백엔드가 멈춰 폴백으로 도는 날
이용자가 그 몰을 못 본다.** 온누리로 결제할 수 있는 곳을 안 보여 주는 쪽이라 이 프로젝트가 가장
피하려는 방향이다.

2026-09-03 에 실제로 그랬다 — 라이브 31곳 / 저장소 30곳, 빠진 곳은 배달앱 `ec-35 온누리 권율로`.
그날 로그에는 `신규 1` 이라는 **숫자만** 남아 있었고 어느 몰인지도, 저장소에 반영하라는 말도 없었다.

그래서 매 회차 두 목록의 **id 집합**을 비교해 로그에 남긴다(건수만 보면 하나 들어오고 하나 빠진
날을 놓친다).

```
! 저장소에 없는 몰 1곳 — 백엔드가 멈춘 날 이용자가 이 곳들을 못 본다. data/online_platforms.json 에 반영할 것
      ec-35 · 온누리 권율로(delivery)
```

이 줄이 보이면 그 몰을 저장소에 넣고 커밋한다(배치가 `git pull` 하므로 다음 날 반영된다). 배달앱은
음식 주문이라 물품종류 태깅 대상이 아니므로 `data/online_catalog.json` 은 건드리지 않는다.
반대 방향(`DB 에 없는 몰`)은 공식 목록에서 빠졌거나 id 가 어긋난 것이다. 목록이 같으면
`· 저장소↔DB 목록 일치(N곳)` 한 줄만 남는다 — **침묵이 아니라 확인했다는 기록이다.**

### 온라인 큐레이션 필드는 배치가 매일 저장소와 맞춘다 (2026-09-02)

`note`·`region_limited`·`search_url_template` 은 공식 API 가 주지 않는, 우리가 손으로 정한 값이다.
단계 B 의 upsert 는 이 컬럼을 건드리지 않아 잘 **보존**되지만 — **새 값이 들어갈 길도 없었다.**
지금까지는 사람이 `load_online_platforms.py` 를 따로 돌려야 했고, 2026-09-02 에 실제로 그걸 잊어
전날 추가한 검색 링크 5곳이 DB 에 없었다(화면이 그 몰들을 홈으로 보내고 있었다).

이제 단계 B 끝에서 `data/online_platforms.json` 의 큐레이션 필드를 DB 에 반영한다.
저장소가 SSOT 이고 배치는 이미 `git pull` 을 하므로, **커밋만 하면 다음 날 반영된다.**
값이 같으면 UPDATE 하지 않는다(로그의 갱신 건수가 곧 변경분).

즉시 반영이 필요하면 직접 넣는다:

```bash
docker exec onnuri-db psql -U onnuri -d onnuri \
  -c "SELECT id, search_url_template FROM online_platform WHERE search_url_template IS NOT NULL;"
```

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
판정은 이용자가 받는 것과 정확히 같은 경로가 한다. 요청량은 몰당 2질의 × 조회 대상 수(`ProbeTargets.ALL` 이 정한다 — 대상이 늘 때마다 여기 적힌 숫자가 거짓이 됐으므로 적지 않는다).

`run.sh`에 두 줄을 더한다(`APP_ADMIN_KEY`는 제보 관리자 페이지와 같은 값이고, `$ENV`는
run.sh가 이미 위에서 정의한 서버 `.env` 경로다):

```bash
# app 은 포트를 호스트에 노출하지 않는다(XFF 스푸핑 방지 — 위 "신뢰 프록시 1홉" 참조).
# 그래서 localhost:8080 이 아니라 Caddy 를 거쳐 자기 도메인으로 부른다.
# 하루 1회, 몰당 2요청이라 TLS 왕복 비용은 무시할 만하다.
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
| `! 재시도 잦음: <몰>` | 무작위 absent 질의가 **자주** 걸린다 | 그 몰 검색이 느슨해지는 중일 수 있다. `ProbeTargets`의 absent 질의 규칙 재검토 |
| `! robots <호스트>: 전면 차단 X → Y` | **어제 대비** robots.txt가 바뀌었다 | 차단으로 바뀌었으면 **즉시 대상에서 뺀다**. 풀렸으면 대상 확대 검토 |
| `· robots <호스트> 감시 시작` | 대상이 늘어 새 호스트를 보기 시작했다 | 그 몰이 실제로 그 호스트를 두드리는지만 확인 |
| `· robots <호스트>: 조회 실패` | robots.txt를 못 읽었다(404·410 제외) | **판정이 아니라 관측 실패다.** 단축주소·리다이렉트 호스트를 보고 있지 않은지 확인 |

#### 재시도가 잦아지는 것을 신호로 본다 (2026-09-03)

앱은 무작위 absent 질의가 우연히 상품을 물면 **새 말로 한 번 다시 묻는다.** 그 재시도는 우연을
걸러 주지만 **가리기도 한다** — 어느 몰의 검색이 점점 느슨해져 무작위 낱말을 자주 물기 시작하면
재시도가 매번 통과시켜 주면서 FAIL이 영영 안 뜬다. 그래서 통과한 재시도에도 note가 붙고,
배치는 그 note가 **잦아지는 것**을 본다.

**한 회차의 1건은 정상이다. 문제는 추세다.** 최근 7회차에서 같은 몰에 3번 이상 걸리면 WARN을 올린다.
회차가 3개보다 적으면 판단하지 않는다 — 표본 없이 추세를 말하면 새 서버에서 첫 주 내내 거짓 경고가 난다.

```
· 재시도 1곳(onnuri-paldo-sijang) — 최근 7회차 기준 아직 추세 아님(알림 기준 3회)
! 재시도 잦음: onnuri-paldo-sijang — 최근 7회차 중 3번 무작위 질의가 걸려 다시 물었다.
  그 몰 검색이 느슨해지는 중일 수 있다(재시도가 FAIL 을 가린다) — absent 질의 규칙 재검토
```

**자동으로 아무것도 끄지 않는다**(ADR-17이 기각한 조용한 축소). 리포트·로그까지다.
**판단 근거는 리포트에 남는다** — `retryTrend`에 몇 회차 중 몇 번이었는지, 어떤 기준으로 판단했는지
(`window`·`alertMin`), 무엇으로 셌는지(`countedBy`)가 들어간다. 그래야 사람이 보고 규칙을 고칠지 정한다.

> `countedBy`가 `note`면 **note 문구로 추론한 것**이다. 앱이 그 문구를 바꾸면 판별이 조용히 0건이 된다.
> 앱이 `cases[].retried` 불리언을 실어 주면 그쪽을 우선하고 `countedBy`가 `flag`가 된다 — 그편이 튼튼하다.

#### robots 감시 — 판정은 앱이, 배치는 비교만 (2026-09-03 개편)

ADR-19가 robots를 대상 선정 기준에서 빼면서 내건 균형점이 "정책이 강해지면 즉시 끈다"이고,
그 방아쇠가 이 감시다. **관측점이 어긋나거나 판정이 둘이면 근거가 반만 선다.**

**판정은 앱이 한다. 배치에 도메인 상수도 파서도 없다.** 셀프테스트 응답 맨 뒤 세 필드를 그대로 옮긴다.

| 필드 | 내용 |
|---|---|
| `probeEndpoints[]` | `{platformId, host, path}` — **실제 조회 주소**(조회가 꺼져 있어도 온다) |
| `robotsUserAgent` | `onnuri-guide` |
| `robots[]` | `{platformId, allowed, rule, group, error}` |

앱이 `ProbeTargets`에서 파생하므로 **조회 대상이 늘거나 줄면 자동으로 따라온다.**
`allowed`는 전면 차단 여부가 아니라 *그 경로가 허용되는가*다 — 경로와 쿼리를 함께 보고
`Disallow: /` + `Allow: /plan/front/` 같은 경로별 허용을 표준 규칙(최장 일치, 동률이면 Allow 우선)으로 읽는다.
그래서 키가 호스트가 아니라 **엔드포인트(platformId)**다. 한 호스트라도 경로가 다르면 답이 다를 수 있다.
`path`의 `Q`는 검색어 자리를 대신하는 고정 토큰이라 이용자 질의가 아니다(로그에 남아도 된다).

이전 감시가 **엉뚱한 사이트를 보고 있었다는 것이 값으로 확인됐다** — 링크 호스트를 봤기 때문이다.
11번가 조회는 `apis.11st.co.kr`(링크는 `search.11st.co.kr`), 온누리5일장은 `api.samaint.co.kr`(본몰이 아님),
롯데ON은 몰 본체(`www.lotteon.com`, 링크는 단축주소 `s.lotteon.com`이라 301 루프였다).

**판정 등급이 둘이다. 섞어 읽으면 안 된다.**

| 등급 | 누가 | 무엇을 | 어디에 |
|---|---|---|---|
| `app` | 앱 | 경로·쿼리까지 본 판정 + 근거 규칙 | 실시간 조회 대상 |
| `coarse` | 배치 | **전면 차단 여부만** | 색인 몰(앱이 모른다) · 앱을 못 부른 회차의 폴백 |

**배치는 앱이 판정한 호스트를 다시 가져오지 않는다.** 상대 사이트 입장에서 robots.txt를 하루 두 번
두드리는 셈이 되고, 부담 억제를 내세운 설계와 어긋난다. 배치가 직접 조회하는 것은 색인 몰의
호스트뿐이고, 그 목록도 손으로 적지 않는다 — `node index_nightly.js --print-hosts`로
`RECIPES`의 `hosts`를 받아 간다. 조회 목록을 만들 때 **앱이 판정한 호스트를 빼는 것이 불변식**이다:
오늘은 두 집합이 겹치지 않지만 그건 성질이 아니라 우연이고, 서로 다른 몰이 한 도메인을 쓰면
그날부터 겹친다. 테스트가 그 불변식을 지킨다(`배치 조회 ∩ 앱 판정 = ∅`).
**델타 비교는 같은 등급끼리만** 한다(정밀 판정과 거친 판정을 맞대면 매번 거짓 변화가 난다).

**꺼진 층은 두드리지 않는다.** 킬 스위치를 켜는 대표적인 상황이 *운영사 항의를 받아 끄는 것*인데,
그 상태에서 그 몰의 robots.txt를 계속 긁으면 "두드리지 말라"는 요청을 받고도 계속 두드리는 셈이다.
조회가 꺼지면 앱의 `robots`는 비어 오고, 배치도 그 호스트를 보지 않는다. 색인 몰은 단계 F가 도는
회차에만 본다 — 층마다 그 층의 스위치를 따른다.

안 본 이유는 성격이 다른 넷을 구분해 적는다: `kill-switch-off`(의도된 중단) ·
`app-unreachable` · `no-admin-key`(배치 설정 미비) · `app-no-robots`(앱이 아직 필드를 안 준다).

**폴백도 손으로 적지 않는다.** 앱을 못 부른 회차(`app-unreachable`·`no-admin-key`)에는
**어제 리포트의 조회 대상**을 거칠게 본다 — "어제까지 우리가 두드리던 곳"이라는 뜻이 정확하고,
그 목록은 매일 리포트가 스스로 갱신한다. 로그와 리포트가 **어느 날짜 회차를 썼는지** 밝힌다
(`폴백 18[2026-09-02 회차 기준]`, 리포트의 `fallbackFrom`).
리포트가 하나도 없는 첫 실행에서는 **아무것도 관측하지 않고 사유를 남긴다** —
손으로 적은 목록으로 흉내 내는 것보다 "확인하지 않았다"가 정직하다.

> 처음엔 `data/online_platforms.json`의 링크 주소로 폴백했는데 그건 *이용자에게 줄 링크*의
> 호스트이지 우리가 두드리는 호스트가 아니다(11번가·5일장·롯데ON이 갈린다 — 이번 라운드가 고친 병).
> 그다음엔 id 20개를 상수로 적었는데, 조회 대상이 늘 때 거기 넣는 걸 잊으면 **폴백 회차에서만
> 조용히 빠진다** — 방금 없앤 `ROBOTS_BLOCKED_AT_SURVEY`와 정확히 같은 노후화다.

**기준선은 상수가 아니라 어제 리포트다.** 손으로 관리하던 `ROBOTS_BLOCKED_AT_SURVEY`는 없앴다 —
몰을 편입하면서 거기 넣는 걸 잊으면 조용히 거짓 통과가 났다(2026-08-31 굿데이 도메인 오기와 같은 자리).
`robots-YYYY-MM-DD.json`을 매일 남기고 어제 것과 비교한다.

**차단이 매일 여러 곳 뜬다.** 그걸 "늘 그런 값"으로 넘기기 시작하면 진짜 변화를 놓친다.
그래서 요약 한 줄에 어제 대비를 담고, WARN은 **목록이 달라졌을 때만** 올린다.

```
· robots 조회 대상 N곳(앱 판정) — 차단 M곳(어제와 같음)          ← 평소, WARN 없음
· robots 조회 대상 N곳(앱 판정) — 차단 M+1곳(**어제와 다름**)
    차단: 11st-onnuri-market, gongyoung-shopping, …
! robots 11st-onnuri-market: 허용 True → False — 조회 대상 재검토   ← 이때만 WARN
```

**곳 수를 예시에 박지 않는다** — N도 M도 `ProbeTargets`가 정하고 앱이 파생시킨다.
숫자를 적어 두면 대상이 늘 때마다 문서가 조용히 거짓이 된다(2026-09-02 문서 정합 점검에서 세운 원칙).
그날의 실제 값은 리포트와 로그가 말한다.

`allowed`가 거짓이어도 **`error`가 채워진 행은 차단이 아니라 못 읽은 것**이다(모르는 것을 허용으로
적지 않으려는 값이다). 로그와 집계에서 구분한다. `rule`이 바뀌는 것도 신호로 본다 —
같은 차단이라도 근거 규칙이 달라졌으면 상대가 정책을 손본 것이다.

리포트는 `SURVEY_OUT_DIR`에 두 개가 쌓인다 — `probe-canary-*.json`(조회가 돌았을 때만)과
`robots-*.json`. 후자에 판정 등급이 함께 남는다.

> **이 절을 고친 뒤에는 실행 경로 5가지를 실제로 돌려 볼 것** — ①정상(앱 판정) ②이튿날 판정이
> 바뀐 날 ③어제와 같은 날 ④킬 스위치 OFF ⑤관리자 키 없음(폴백). 순수 함수 테스트만으로는
> 안 잡히는 층이 있다. 2026-09-03에 헬퍼 블록이 통째로 두 벌 들어가 뒤 사본이 앞 사본을 가렸고,
> 단계 E가 `_robots_watch() takes 2 positional arguments but 4 were given`으로 죽었다.
> 함수는 각각 멀쩡했고 테스트도 통과했다 — 실행해 보고서야 드러났다.
>
> ```bash
> python3 _workspace/dev_scripts/test_robots_watch.py   # robots 감시(바깥 요청 없음)
> python3 _workspace/dev_scripts/test_canary_trend.py   # 재시도 추세(바깥 요청 없음)
> ```
> 이 테스트가 지키는 것: 앱 판정을 그대로 옮기는지 · **중복 조회 0**(앱 판정 호스트 ∩ 배치가
> 두드린 호스트 = ∅) · 꺼진 층의 아웃바운드 0 · 404·410만 "파일 없음" · 판정 등급 격리 ·
> 건너뛴 호스트를 "감시 이탈"로 읽지 않기 · 어제 대비 비교. robots 규칙 해석 자체는 앱의
> `RobotsRulesTest` 가 덮는다 — 여기서 다시 테스트하지 않는다(파서가 두 곳이 되면 안 된다).

2026-09-01 서버 가동 확인 — cron 최소 환경에서 12건 전부 통과, 16초. 키를 비운 모의 실행에서
배치가 죽지 않고 E만 스킵되는 것도 확인했다. 첫날은 비교할 어제 리포트가 없어 응답 길이
경고가 나오지 않는다(정상).

규칙을 고친 뒤에는 실제 조회 대상 전부를 두드려 확인한다(평소 CI에서는 돌지 않는다):

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
# 색인만(F): 크롤 10분 + 적재. DB 가 필요하다
python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag --skip-survey --skip-canary
# 채록만(D): DB 없이 돌아간다 — --skip-index 를 빼면 F 때문에 DB 를 열려고 한다
python3 backend/tools/nightly_update.py --skip-merchants --skip-online --skip-rag --skip-index --skip-canary
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
- [ ] `app` 헬스 UP + 가맹점 적재 검증(2026-09-05 기준 79,800건대 — 자릿수와 0 여부를 본다)
- [ ] `https://api.koscomlabor.cloud` 회귀 검증(개포동 144~146 · 팔달구 1250~1270)
- [ ] 백엔드 검증 후 `API_BASE` 반영분을 main에 머지 → 라이브 전환
