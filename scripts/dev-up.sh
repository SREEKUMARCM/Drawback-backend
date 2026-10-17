#!/usr/bin/env bash
# Starts everything for local development: Redis + Kafka (Docker), the backend services and the web app.
# Usage: scripts/dev-up.sh [path-to-drawback-web]     Stop with: scripts/dev-down.sh
# Needs: Java 21, Maven, Node 20+, Docker, and a local PostgreSQL (see step 1 below).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
WEB="${1:-$ROOT/../drawback-web}"
LOGS="$ROOT/.logs"; mkdir -p "$LOGS"
export JWT_SECRET="${JWT_SECRET:-dev-only-secret-change-me-0123456789abcdef}"
export OTP_DEV_LOG=true

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null || { echo "Missing: $1"; exit 1; }; }
need java; need mvn; need node; need npm; need docker

say "1/6 PostgreSQL check (user drawback / password drawback)"
if ! PGPASSWORD=drawback psql -h localhost -U drawback -d review_db -c 'select 1' >/dev/null 2>&1; then
  cat <<'MSG'
Cannot log in to review_db as drawback/drawback. Create it once with:
  sudo -u postgres psql -c "CREATE USER drawback WITH PASSWORD 'drawback' CREATEDB;"
  sudo -u postgres psql -c "CREATE DATABASE auth_db OWNER drawback;"
  sudo -u postgres psql -c "CREATE DATABASE review_db OWNER drawback;"
  sudo -u postgres psql -c "CREATE DATABASE notification_db OWNER drawback;"
then run this script again.
MSG
  exit 1
fi

say "2/6 Redis + Kafka (Docker)"
(cd deploy/docker && docker compose up -d redis kafka)

say "3/6 Build shared modules"
mvn -q install -DskipTests

start() { # name, command...
  local name=$1; shift
  nohup "$@" >"$LOGS/$name.log" 2>&1 &
  echo $! >"$LOGS/$name.pid"; echo "started $name (pid $!) -> .logs/$name.log"
}
wait_http() { # name url
  for _ in $(seq 1 90); do curl -fsS "$2" >/dev/null 2>&1 && { echo "$1 is UP"; return; }; sleep 2; done
  echo "$1 did not come up - see .logs/$1.log"; exit 1
}

say "4/6 Backend services"
start auth-service         mvn -q -pl auth-service spring-boot:run
start review-service       mvn -q -pl review-service spring-boot:run
start notification-service mvn -q -pl notification-service spring-boot:run
wait_http auth-service         http://localhost:8081/actuator/health
wait_http review-service       http://localhost:8082/actuator/health
wait_http notification-service http://localhost:8084/actuator/health

say "5/6 Frontend"
[ -d "$WEB" ] || { echo "Frontend not found at $WEB - pass its path as the first argument"; exit 1; }
cd "$WEB"
[ -f .env ] || cp .env.example .env
sed -i 's/^VITE_USE_MOCK=.*/VITE_USE_MOCK=false/' .env
npm install --no-audit --no-fund
start web npm run dev -- --host

say "6/6 Ready"
echo "App:        http://localhost:5173"
echo "Phone OTPs: grep OTP .logs/auth-service.log   (or: tail -f .logs/auth-service.log)"
echo "Logs:       .logs/*.log      Stop: scripts/dev-down.sh"
