-- V15 (P1): canonical users. Roles, activation, token invalidation, optimistic locking, normalised unique emails.
-- Fails loudly (instead of guessing) when existing data cannot be mapped safely. No rows are deleted or merged.

-- 1. Case-insensitive duplicate emails must be resolved by a human; never merge or drop accounts silently.
DO $$
DECLARE dup_count integer;
BEGIN
    SELECT count(*) INTO dup_count FROM (
        SELECT 1 FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1
    ) d;
    IF dup_count > 0 THEN
        RAISE EXCEPTION 'V15 aborted: % group(s) of users share an email that differs only by case/whitespace. Resolve them manually, then re-run.', dup_count;
    END IF;
END $$;

-- 2. Roles: normalise case/whitespace, map legacy USER -> EMPLOYEE, and refuse anything else.
UPDATE users SET role = upper(btrim(role));
UPDATE users SET role = 'EMPLOYEE' WHERE role = 'USER';

DO $$
DECLARE bad_count integer;
BEGIN
    SELECT count(*) INTO bad_count FROM users WHERE role NOT IN ('EMPLOYEE', 'TECHNICIAN', 'ADMIN');
    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V15 aborted: % user(s) have a role that is not EMPLOYEE, TECHNICIAN, ADMIN or legacy USER.', bad_count;
    END IF;
END $$;

-- 3. Emails are stored lower-case and trimmed (the application normalises the same way).
UPDATE users SET email = lower(btrim(email));

-- 4. New columns. Existing accounts stay active, with token_version 0 and version 0.
ALTER TABLE users
    ADD COLUMN active        BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN token_version INTEGER     NOT NULL DEFAULT 0,        -- bump to invalidate every issued JWT
    ADD COLUMN version       BIGINT      NOT NULL DEFAULT 0,        -- optimistic locking
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE users ALTER COLUMN role SET DEFAULT 'EMPLOYEE';
ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('EMPLOYEE', 'TECHNICIAN', 'ADMIN')),
    ADD CONSTRAINT chk_users_email_normalised CHECK (email = lower(btrim(email)) AND email <> ''),
    ADD CONSTRAINT chk_users_token_version CHECK (token_version >= 0);

-- 5. Case-insensitive uniqueness (belt and braces with the CHECK above and V1's UNIQUE(email)).
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- Supports the admin list filters.
CREATE INDEX idx_users_role_active ON users (role, active);
