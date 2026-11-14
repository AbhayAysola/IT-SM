#!/bin/sh
# Runs once, when the PostgreSQL data directory is first created (docker-entrypoint-initdb.d). Creates the RUNTIME role the application
# connects as. POSTGRES_USER stays the owner of the database and its tables (it runs the Flyway migrations); the runtime role gets its
# table privileges from backend/src/main/resources/db/callback/afterMigrate__runtime_role_grants.sql after every migration.
# The password must not contain a single quote.
set -eu
: "${RUNTIME_DB_PASSWORD:?set RUNTIME_DB_PASSWORD}"
RUNTIME_DB_USER="${RUNTIME_DB_USER:-itsm_app}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE "${RUNTIME_DB_USER}" LOGIN PASSWORD '${RUNTIME_DB_PASSWORD}';
GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${RUNTIME_DB_USER}";
SQL
