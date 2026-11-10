-- Assignment configuration (P1). Backs com.itsm.common.spi.AssignmentSettingsProvider and
-- /api/admin/assignment-settings. P1 stores which strategy key is configured; P3 owns the strategies themselves
-- (AssignmentStrategyCatalog) and the algorithms. Strategy keys are validated against P3's catalog in the
-- application, never here, because the set of strategies is P3's to define.
--
-- Exactly one configuration row exists (id = 1), created below and protected from deletion.
CREATE TABLE assignment_settings (
    id                   SMALLINT     PRIMARY KEY,
    active_strategy      VARCHAR(50)  NOT NULL,
    default_max_workload INTEGER      NOT NULL,
    updated_by           BIGINT       REFERENCES users (id),   -- user id only, no personal data
    version              BIGINT       NOT NULL DEFAULT 0,      -- JPA optimistic lock
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_assignment_singleton CHECK (id = 1),
    CONSTRAINT chk_assignment_strategy_key CHECK (active_strategy ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT chk_assignment_default_workload CHECK (default_max_workload BETWEEN 1 AND 100)
);

-- Default taken from the earlier draft and the integration guide: P3 has been asked to register LOAD_BASED.
-- It is NOT verified here; see docs/architecture/integration-guide.md ("Assignment settings").
INSERT INTO assignment_settings (id, active_strategy, default_max_workload) VALUES (1, 'LOAD_BASED', 5);

-- A category's own strategy, overriding active_strategy for incidents in that category. One row per category.
CREATE TABLE assignment_category_overrides (
    settings_id SMALLINT    NOT NULL DEFAULT 1 REFERENCES assignment_settings (id),
    category_id BIGINT      PRIMARY KEY REFERENCES incident_categories (id),
    strategy    VARCHAR(50) NOT NULL,
    CONSTRAINT chk_assignment_override_singleton CHECK (settings_id = 1),
    CONSTRAINT chk_assignment_override_strategy_key CHECK (strategy ~ '^[A-Z][A-Z0-9_]{1,49}$')
);

-- One coherent configuration: the row can be updated, never removed.
CREATE FUNCTION assignment_settings_reject_removal() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'assignment_settings cannot be removed (% rejected)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_assignment_settings_no_delete
    BEFORE DELETE ON assignment_settings
    FOR EACH ROW EXECUTE FUNCTION assignment_settings_reject_removal();

CREATE TRIGGER trg_assignment_settings_no_truncate
    BEFORE TRUNCATE ON assignment_settings
    FOR EACH STATEMENT EXECUTE FUNCTION assignment_settings_reject_removal();
