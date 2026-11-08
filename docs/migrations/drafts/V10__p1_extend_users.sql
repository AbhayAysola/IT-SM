-- DRAFT (P1). Not under src/main/resources: it is applied only when P1's access branch lands.
ALTER TABLE users
    ADD COLUMN active        BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN token_version INTEGER      NOT NULL DEFAULT 0,      -- bump to revoke issued JWTs
    ADD COLUMN failed_logins INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN locked_until  TIMESTAMPTZ,
    ADD COLUMN department    VARCHAR(100),
    ADD COLUMN phone         VARCHAR(30),
    ADD COLUMN updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW();

-- Boilerplate default was 'USER'; those rows are employees.
UPDATE users SET role = 'EMPLOYEE' WHERE role = 'USER';
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'EMPLOYEE';
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('EMPLOYEE', 'TECHNICIAN', 'ADMIN'));
