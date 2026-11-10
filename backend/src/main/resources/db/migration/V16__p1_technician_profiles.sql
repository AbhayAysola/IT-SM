-- V16 (P1): technician profiles. Backs UserDirectory.findTechnician / listAvailableTechnicians / escalationChain and
-- /api/admin/technicians. Identity (name, email, password, role, account state) stays in `users`; nothing is copied here.
-- No rows are created: no user is promoted and no profile is invented for existing accounts.
--
-- The profile's primary key IS the user id (1:1 with users, so at most one profile per user by construction).
-- Profiles are never deleted; retiring one means active = FALSE.
CREATE TABLE technician_profiles (
    user_id            BIGINT      PRIMARY KEY REFERENCES users (id),
    max_workload       INTEGER     NOT NULL,                    -- TechnicianSummary.maxWorkload (P3 enforces it)
    experience_years   INTEGER     NOT NULL,                    -- TechnicianSummary.experienceYears
    available          BOOLEAN     NOT NULL,                    -- ADMIN-set flag (leave, off shift). NOT workload, NOT "assignable"
    is_team_lead       BOOLEAN     NOT NULL DEFAULT FALSE,      -- an attribute, not a role
    reports_to_user_id BIGINT,                                  -- escalation line: the team lead this technician reports to
    -- Always TRUE when reports_to_user_id is set (NULL otherwise). Together with the composite FK below it makes the
    -- database guarantee that the manager has a profile AND is flagged as a team lead; the FK also stops anyone
    -- clearing is_team_lead on a profile that others still report to.
    reports_to_lead    BOOLEAN     GENERATED ALWAYS AS (CASE WHEN reports_to_user_id IS NULL THEN NULL ELSE TRUE END) STORED,
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    version            BIGINT      NOT NULL DEFAULT 0,          -- JPA optimistic lock
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tp_max_workload CHECK (max_workload BETWEEN 1 AND 100),
    CONSTRAINT chk_tp_experience CHECK (experience_years BETWEEN 0 AND 60),
    CONSTRAINT chk_tp_not_own_manager CHECK (reports_to_user_id IS NULL OR reports_to_user_id <> user_id),
    CONSTRAINT uq_tp_user_lead UNIQUE (user_id, is_team_lead),
    CONSTRAINT fk_tp_reports_to FOREIGN KEY (reports_to_user_id, reports_to_lead)
        REFERENCES technician_profiles (user_id, is_team_lead)
);

CREATE INDEX idx_tp_reports_to ON technician_profiles (reports_to_user_id) WHERE reports_to_user_id IS NOT NULL;
CREATE INDEX idx_tp_directory ON technician_profiles (user_id) WHERE active AND available;
CREATE INDEX idx_tp_team_lead ON technician_profiles (user_id) WHERE is_team_lead;

-- Skills = incident categories the technician can handle, by the stable shared key CategoryView.id, with a 1..5
-- proficiency (TechnicianSummary.skills). No taxonomy of its own and no copied names. Rows may refer to a category
-- that is later deactivated: they are kept, and the directory simply stops reporting them.
CREATE TABLE technician_skills (
    user_id     BIGINT  NOT NULL REFERENCES technician_profiles (user_id),
    category_id BIGINT  NOT NULL REFERENCES incident_categories (id),
    proficiency INTEGER NOT NULL,
    PRIMARY KEY (user_id, category_id),                          -- no duplicate skill per technician
    CONSTRAINT chk_ts_proficiency CHECK (proficiency BETWEEN 1 AND 5)
);
CREATE INDEX idx_technician_skills_category ON technician_skills (category_id);

-- Application checks give friendly errors; these triggers are the authority, because CHECK/FK cannot look at another
-- table's column (users.role) or walk a graph. One function guards the profile table:
--  * the owner must have role TECHNICIAN on insert and when a profile is (re)activated; the user row is locked FOR SHARE
--    so a concurrent role change waits for this transaction (and vice versa);
--  * user_id is immutable;
--  * the manager must be an ACTIVE profile (is_team_lead is enforced by the composite FK);
--  * no reporting cycles (graph walk) and a profile cannot be deactivated while ACTIVE technicians report to it.
-- Every graph writer takes advisory lock 7310004 first (same key as AdvisoryLocks.TECHNICIAN_GRAPH), so two concurrent
-- changes cannot each pass the check and together create a cycle.
-- Deliberate, identifiable errors: these triggers raise application SQLSTATEs (not a generic 23xxx), so the application can map
-- exactly these two violations to HTTP 409 and nothing else:
--   IT001  a user with an ACTIVE technician profile cannot stop being a TECHNICIAN
--   IT002  any other technician-profile invariant (role, cycle, manager, active reports, immutable user_id)
-- The messages below are for server logs only; the API answers with fixed wording (DatabaseErrorClassifier).
CREATE FUNCTION technician_profiles_guard() RETURNS trigger AS $$
DECLARE
    owner_role VARCHAR(50);
BEGIN
    PERFORM pg_advisory_xact_lock(7310004);

    IF TG_OP = 'UPDATE' AND NEW.user_id <> OLD.user_id THEN
        RAISE EXCEPTION 'technician profile user_id is immutable' USING ERRCODE = 'IT002';
    END IF;

    IF TG_OP = 'INSERT' OR (NEW.active AND NOT OLD.active) THEN
        SELECT role INTO owner_role FROM users WHERE id = NEW.user_id FOR SHARE;
        IF owner_role IS DISTINCT FROM 'TECHNICIAN' THEN
            RAISE EXCEPTION 'user % does not have the TECHNICIAN role', NEW.user_id USING ERRCODE = 'IT002';
        END IF;
    END IF;

    IF NEW.reports_to_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.reports_to_user_id IS DISTINCT FROM OLD.reports_to_user_id
                                                OR (NEW.active AND NOT OLD.active)) THEN
        -- The manager must have an ACTIVE profile and an ACTIVE account. Their user row is locked FOR SHARE so a concurrent
        -- deactivation of the account waits for this transaction instead of slipping past the check.
        IF NEW.active AND NOT EXISTS (
            SELECT 1 FROM technician_profiles m JOIN users mu ON mu.id = m.user_id
            WHERE m.user_id = NEW.reports_to_user_id AND m.active AND mu.active AND mu.role = 'TECHNICIAN'
            FOR SHARE OF mu
        ) THEN
            RAISE EXCEPTION 'manager % has no active technician profile and account', NEW.reports_to_user_id USING ERRCODE = 'IT002';
        END IF;
        IF EXISTS (
            WITH RECURSIVE up(user_id, reports_to_user_id) AS (
                SELECT user_id, reports_to_user_id FROM technician_profiles WHERE user_id = NEW.reports_to_user_id
                UNION
                SELECT p.user_id, p.reports_to_user_id FROM technician_profiles p JOIN up ON p.user_id = up.reports_to_user_id
            )
            SELECT 1 FROM up WHERE user_id = NEW.user_id
        ) THEN
            RAISE EXCEPTION 'reporting line % -> % would create a cycle', NEW.user_id, NEW.reports_to_user_id USING ERRCODE = 'IT002';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.active AND NOT NEW.active
       AND EXISTS (SELECT 1 FROM technician_profiles WHERE reports_to_user_id = NEW.user_id AND active) THEN
        RAISE EXCEPTION 'technician % still has active direct reports', NEW.user_id USING ERRCODE = 'IT002';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_technician_profiles_guard
    BEFORE INSERT OR UPDATE ON technician_profiles
    FOR EACH ROW EXECUTE FUNCTION technician_profiles_guard();

-- The other direction: a user cannot stop being a TECHNICIAN while they hold an ACTIVE profile (deactivate it first).
-- A deactivated profile is history and stays; the role may then change freely.
CREATE FUNCTION users_role_keeps_technician_profile() RETURNS trigger AS $$
BEGIN
    IF OLD.role = 'TECHNICIAN' AND NEW.role <> 'TECHNICIAN'
       AND EXISTS (SELECT 1 FROM technician_profiles WHERE user_id = OLD.id AND active) THEN
        RAISE EXCEPTION 'user % has an active technician profile; deactivate it before changing the role', OLD.id
            USING ERRCODE = 'IT001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_role_keeps_technician_profile
    BEFORE UPDATE OF role ON users
    FOR EACH ROW EXECUTE FUNCTION users_role_keeps_technician_profile();
