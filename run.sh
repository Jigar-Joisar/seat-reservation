#!/usr/bin/env bash
# Build (if needed) and run the service. Every setting has a default and can be overridden:
#   PORT=9090 ADMIN_SECRET=s3cret ./run.sh
#   or put KEY=VALUE lines in a .env file next to this script (git-ignored).
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then set -a; . ./.env; set +a; fi

export PORT="${PORT:-8080}"
export JWT_SECRET="${JWT_SECRET:-dev-only-jwt-secret-change-me}"
export ADMIN_SECRET="${ADMIN_SECRET:-dev-admin-secret}"
export PER_USER_LIMIT="${PER_USER_LIMIT:-4}"
export HOLD_TTL_SECONDS="${HOLD_TTL_SECONDS:-300}"
export HOLD_SWEEP_MS="${HOLD_SWEEP_MS:-5000}"
export DB_POOL_SIZE="${DB_POOL_SIZE:-32}"
export DATABASE_URL="${DATABASE_URL:-jdbc:h2:file:./data/seats;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=15000;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE}"
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+UseSerialGC}"
JAR=target/seat-reservation-1.0.0.jar

[ "${FRESH_DB:-0}" = "1" ] && rm -rf data && echo "FRESH_DB=1: removed ./data"
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  echo "Building..."; mvn -q clean package -DskipTests
fi

echo "Starting on :$PORT  limit=$PER_USER_LIMIT hold_ttl=${HOLD_TTL_SECONDS}s pool=$DB_POOL_SIZE"
[ "$ADMIN_SECRET" = "dev-admin-secret" ] && echo "WARNING: using dev default ADMIN_SECRET/JWT_SECRET (fine locally, not for deploy)"
exec java $JAVA_OPTS -jar "$JAR"
