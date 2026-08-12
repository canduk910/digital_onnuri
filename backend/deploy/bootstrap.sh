#!/usr/bin/env bash
# NCP Server(Ubuntu 22.04)에서 백엔드를 한 번에 세우는 부트스트랩.
# 전제: 서버 생성 + ACG(80/443/22) + Cloud DB 생성 + 서브도메인 A레코드가 이미 되어 있어야 함.
# 사용:
#   curl -fsSL https://raw.githubusercontent.com/canduk910/digital_onnuri/feat/backend-scaffold/backend/deploy/bootstrap.sh -o bootstrap.sh
#   bash bootstrap.sh
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/canduk910/digital_onnuri.git}"
BRANCH="${BRANCH:-feat/backend-scaffold}"
WORKDIR="${WORKDIR:-$HOME/digital_onnuri}"

echo "==[1/6] Docker/Compose 설치 확인=="
if ! command -v docker >/dev/null 2>&1; then
  # NCP 미러 등 배포판 저장소에는 docker-compose-plugin이 없음 → Docker 공식 설치 스크립트 사용
  # (docker-ce + buildx + compose plugin 일괄 설치)
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
  sudo usermod -aG docker "$USER" || true
  echo ">> docker 그룹 반영을 위해 재로그인이 필요할 수 있습니다(root면 무관). 계속 진행합니다."
fi
# 데이터 적재용 파이썬 pip (git은 대부분 기본 포함)
command -v pip3 >/dev/null 2>&1 || sudo apt-get install -y python3-pip
command -v git  >/dev/null 2>&1 || sudo apt-get install -y git

echo "==[2/6] 소스 가져오기 ($BRANCH)=="
if [ -d "$WORKDIR/backend" ] && [ ! -d "$WORKDIR/.git" ]; then
  echo "  소스가 이미 있음(scp/tar 배포) — clone 생략"   # private repo: 토큰 없이 tarball로 전송한 경우
elif [ -d "$WORKDIR/.git" ]; then
  git -C "$WORKDIR" fetch origin "$BRANCH" && git -C "$WORKDIR" checkout "$BRANCH" && git -C "$WORKDIR" pull --ff-only
else
  git clone -b "$BRANCH" "$REPO_URL" "$WORKDIR"
fi

cd "$WORKDIR/backend/deploy"

echo "==[3/6] .env 확인=="
if [ ! -f .env ]; then
  cp .env.example .env
  echo "!! .env 를 생성했습니다. 지금 값(DB_URL/DB_USER/DB_PASSWORD/API_DOMAIN/TLS_EMAIL)을 채운 뒤 다시 실행하세요:"
  echo "   vi $WORKDIR/backend/deploy/.env"
  exit 1
fi
# 필수 값 비어있는지 점검
missing=0
for k in DB_URL DB_USER DB_PASSWORD API_DOMAIN TLS_EMAIL; do
  v=$(grep -E "^$k=" .env | sed "s/^$k=//") || true
  if [ -z "$v" ] || echo "$v" | grep -q "REPLACE\|여기에\|example.com\|<"; then
    echo "!! .env의 $k 가 아직 기본/빈 값입니다."; missing=1
  fi
done
[ "$missing" = "1" ] && { echo "→ .env를 채우고 다시 실행하세요."; exit 1; }

echo "==[4/6] 빌드 + 기동 (Flyway가 스키마 생성)=="
docker compose -f docker-compose.prod.yml up -d --build

echo "==[5/6] 앱 헬스 대기=="
for i in $(seq 1 30); do
  if docker compose -f docker-compose.prod.yml exec -T app curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "앱 UP"; break
  fi
  sleep 5
  [ "$i" = "30" ] && { echo "!! 앱이 정상 기동하지 못했습니다. 로그: docker compose -f docker-compose.prod.yml logs app"; exit 1; }
done

echo "==[6/6] 데이터 적재(최초 1회, 66,211건)=="
# DB는 같은 VM의 컨테이너(127.0.0.1:5432로 퍼블리시). 호스트에서 로더 실행.
set -a; . ./.env; set +a
pip3 install --quiet 'psycopg[binary]' || pip3 install --quiet --break-system-packages 'psycopg[binary]'
DB_DSN="host=127.0.0.1 port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASSWORD}" \
  python3 "$WORKDIR/backend/tools/load_merchants.py"
# 온라인 플랫폼 초기 적재(멱등, 30건) — 이후 야간 배치가 공식 API로 갱신
DB_DSN="host=127.0.0.1 port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASSWORD}" \
  python3 "$WORKDIR/backend/tools/load_online_platforms.py"

echo
echo "완료. 검증:"
echo "  curl -s https://$API_DOMAIN/actuator/health"
echo "  curl -sG https://$API_DOMAIN/api/merchants --data-urlencode region=서울 --data-urlencode gu=강남구 --data-urlencode dong=개포동 --data-urlencode size=1"
echo "→ 이후 merchants.html의 API_BASE를 https://$API_DOMAIN/api 로 교체하면 라이브가 API로 전환됩니다."
