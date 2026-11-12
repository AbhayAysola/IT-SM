#!/usr/bin/env bash
# Starts / stops the REAL backend for the real-stack suite. Used by the CI job and for local runs, so both do the same thing.
#
#   stack.sh backend-up    bootstrap the first ADMIN (existing command mode), then start the jar and wait until it answers
#   stack.sh backend-down  stop the jar (safe to run when it is not running)
#
# Inputs (environment):
#   SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD   an EMPTY, DISPOSABLE PostgreSQL 16 database (Flyway migrates it)
#   JWT_SECRET                                      >= 32 bytes
#   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD            the first administrator to create (never printed)
#   BACKEND_JAR   (default backend/build/libs/it-sm-0.0.1-SNAPSHOT.jar)   STACK_DIR (default frontend/real-stack-report/logs)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${BACKEND_JAR:-$ROOT/backend/build/libs/it-sm-0.0.1-SNAPSHOT.jar}"
LOGS="${STACK_DIR:-$ROOT/frontend/real-stack-report/logs}"
PIDFILE="$LOGS/backend.pid"

case "${1:-}" in
  backend-up)
    : "${E2E_ADMIN_EMAIL:?}" "${E2E_ADMIN_PASSWORD:?}" "${JWT_SECRET:?}" "${SPRING_DATASOURCE_URL:?}"
    mkdir -p "$LOGS"
    # 1. First administrator: the product's own command-mode bootstrap. It exits (0 created, 1 refused/failed, 2 missing config).
    #    It reads the credentials from ITSM_BOOTSTRAP_ADMIN_*; they are never echoed.
    ITSM_BOOTSTRAP_ADMIN_EMAIL="$E2E_ADMIN_EMAIL" ITSM_BOOTSTRAP_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
      SPRING_PROFILES_ACTIVE=dev AUTH_COOKIE_SECURE=false \
      java -jar "$JAR" --spring.main.web-application-type=none --itsm.bootstrap-admin=true >"$LOGS/bootstrap.log" 2>&1 \
      || { echo "Admin bootstrap failed (see the bootstrap log artifact)"; exit 1; }
    # 2. The server. `dev` + AUTH_COOKIE_SECURE=false only because CI is plain HTTP (production refuses a non-Secure cookie).
    #    Registration is on so the registration behaviour can be tested. Only the IP-wide throttles are raised, because every test
    #    comes from one address; the per-email login limit keeps its production value (5) and is what the rate-limit test hits.
    #    Defaults for everything else, including CORS (http://localhost:3000) and the CSRF/Origin rules.
    SPRING_PROFILES_ACTIVE=dev AUTH_COOKIE_SECURE=false REGISTRATION_ENABLED=true \
      nohup java -jar "$JAR" \
        --app.rate-limit.login-ip.max-failures=100000 \
        --app.rate-limit.register-ip.max-attempts=100000 >"$LOGS/backend.log" 2>&1 &
    echo $! >"$PIDFILE"
    for _ in $(seq 1 90); do
      code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{}' || true)
      [ "$code" = 400 ] || [ "$code" = 401 ] && { echo "backend is up"; exit 0; }
      kill -0 "$(cat "$PIDFILE")" 2>/dev/null || { echo "backend process exited during start-up (see the backend log artifact)"; exit 1; }
      sleep 1
    done
    echo "backend did not become ready within 90s"; exit 1
    ;;
  backend-down)
    if [ -f "$PIDFILE" ]; then
      pid=$(cat "$PIDFILE")
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -9 "$pid" 2>/dev/null || true
      rm -f "$PIDFILE"
    fi
    ;;
  *) echo "usage: $0 backend-up|backend-down" >&2; exit 2 ;;
esac
