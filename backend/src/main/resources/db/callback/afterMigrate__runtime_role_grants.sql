-- Opt-in privilege model: the application connects as a RUNTIME role that does not own the tables, while Flyway runs as the owner.
-- Not on the default Flyway locations on purpose (a plain `./gradlew bootRun` or `docker compose up` keeps one database user). Turn it on with
--   SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/callback
--   SPRING_FLYWAY_PLACEHOLDERS_RUNTIMEROLE=<the runtime role's name>   (no underscore in the name: Spring maps `_` in an environment
--     variable to `.`, so a placeholder called runtime_role would never be found)
--   SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD = the owner (migration) account, SPRING_DATASOURCE_USERNAME / _PASSWORD = the runtime role
-- and create the runtime role beforehand (infra/postgres/init-runtime-role.sh does it for the compose override).
--
-- Why: the append-only trigger on audit_events is only a guard if the application's own account cannot switch it off. Only a table's
-- owner (or a superuser) can DISABLE a trigger or ALTER the table, so a role that merely holds DML privileges cannot.
--
-- Runs after EVERY successful migrate (a callback, not a migration), so tables added by later migrations are covered without
-- ALTER DEFAULT PRIVILEGES, and the grants cannot drift. It only ever grants to, or revokes from, the named runtime role.
DO $grants$
DECLARE
    runtime_role text := '${runtimerole}';
BEGIN
    IF runtime_role = '' THEN
        RAISE EXCEPTION 'placeholder runtimerole is empty: set spring.flyway.placeholders.runtimerole';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        RAISE EXCEPTION 'runtime role % does not exist: create it first', runtime_role;
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', runtime_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', runtime_role);

    -- The audit trail: read and append only. (The trigger already rejects UPDATE/DELETE/TRUNCATE for everyone; this removes the
    -- privileges too, so it holds even for a role that were ever made the table owner's peer.)
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM %I', runtime_role);

    -- Migration bookkeeping is the owner's business.
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', runtime_role);
END
$grants$;
