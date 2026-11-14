-- P4 escalation (block V40-V49). Rules: docs/architecture/p4.md section 3.

-- One row, id = 1: when escalation fires. Edited through /api/admin/escalation-settings (SLA_CONFIGURE).
CREATE TABLE escalation_settings (
    id                   SMALLINT     PRIMARY KEY DEFAULT 1,
    -- An incident still CLASSIFIED (nobody assigned) this long after it was reported goes to the admins.
    unassigned_minutes   INTEGER      NOT NULL DEFAULT 30,
    -- An open incident whose resolution clock has used this percentage of its target goes one level up the chain...
    second_level_pct     SMALLINT     NOT NULL DEFAULT 150,
    -- ...and at this percentage to the admins.
    admin_level_pct      SMALLINT     NOT NULL DEFAULT 200,
    version              BIGINT       NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by_user_id   BIGINT       REFERENCES users (id),

    CONSTRAINT ck_escalation_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_escalation_unassigned CHECK (unassigned_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_escalation_levels CHECK (second_level_pct BETWEEN 101 AND 1000
        AND admin_level_pct > second_level_pct AND admin_level_pct <= 1000)
);
INSERT INTO escalation_settings (id) VALUES (1);

-- Every escalation that happened, once per incident and trigger. Append-only: escalations are history.
CREATE TABLE incident_escalations (
    id               BIGSERIAL    PRIMARY KEY,
    incident_id      VARCHAR(30)  NOT NULL REFERENCES incidents (id),
    trigger          VARCHAR(30)  NOT NULL,
    tier             VARCHAR(20)  NOT NULL,
    -- Who it was escalated to (users at the time; no FK so the history outlives account changes). May be empty when
    -- nobody qualifies (no team lead and no active admin): the escalation is still recorded so it is not retried forever.
    target_user_ids  BIGINT[]     NOT NULL,
    -- The assignee when it happened, if any.
    assignee_id      BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_escalation_trigger CHECK (trigger IN
        ('RESPONSE_BREACHED', 'RESOLUTION_BREACHED', 'RESOLUTION_OVERDUE', 'RESOLUTION_CRITICAL', 'UNASSIGNED')),
    CONSTRAINT ck_escalation_tier CHECK (tier IN ('TEAM_LEAD', 'NEXT_LEVEL', 'ADMINS')),
    -- "At most once per incident and trigger", also under concurrent runs.
    CONSTRAINT uq_incident_escalation UNIQUE (incident_id, trigger)
);
CREATE INDEX idx_incident_escalations_created ON incident_escalations (created_at DESC);

CREATE FUNCTION p4_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_incident_escalations_append_only
    BEFORE UPDATE OR DELETE ON incident_escalations
    FOR EACH ROW EXECUTE FUNCTION p4_reject_mutation();
CREATE TRIGGER trg_incident_escalations_no_truncate
    BEFORE TRUNCATE ON incident_escalations
    FOR EACH STATEMENT EXECUTE FUNCTION p4_reject_mutation();
