-- P4 predictive SLA risk (block V40-V49; docs/architecture/p4.md section 2). One row per incident the SLA monitor
-- predicted would miss its resolution deadline, written once, never changed or deleted: it records what was predicted,
-- when, and on what basis, so the warning can be explained later.
CREATE TABLE incident_sla_risk (
    incident_id             VARCHAR(30)  PRIMARY KEY REFERENCES incidents (id),
    raised_at               TIMESTAMPTZ  NOT NULL,
    -- The resolution deadline at the time, and when the incident was predicted to be resolved (after it).
    due_at                  TIMESTAMPTZ  NOT NULL,
    predicted_finish_at     TIMESTAMPTZ  NOT NULL,
    -- How the forecast was made: which past incidents (same priority and category, or same priority), how many, the
    -- typical remaining minutes they suggest, and the assignee load factor applied (1.00 = none).
    basis                   VARCHAR(10)  NOT NULL,
    sample_size             INTEGER      NOT NULL,
    typical_remaining_min   BIGINT       NOT NULL,
    load_factor             NUMERIC(4,2) NOT NULL,
    -- The assignee when it was raised, if any (no FK: history outlives account changes).
    assignee_id             BIGINT,

    CONSTRAINT ck_sla_risk_basis    CHECK (basis IN ('CATEGORY', 'PRIORITY')),
    CONSTRAINT ck_sla_risk_late     CHECK (predicted_finish_at > due_at),
    CONSTRAINT ck_sla_risk_numbers  CHECK (sample_size >= 5 AND typical_remaining_min >= 0 AND load_factor BETWEEN 1 AND 3)
);

CREATE TRIGGER trg_incident_sla_risk_append_only
    BEFORE UPDATE OR DELETE ON incident_sla_risk
    FOR EACH ROW EXECUTE FUNCTION p4_reject_mutation();
CREATE TRIGGER trg_incident_sla_risk_no_truncate
    BEFORE TRUNCATE ON incident_sla_risk
    FOR EACH STATEMENT EXECUTE FUNCTION p4_reject_mutation();

-- The person responsible is told once (notification type SLA_AT_RISK).
ALTER TABLE notifications DROP CONSTRAINT ck_notification_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'INCIDENT_ASSIGNED', 'ASSIGNMENT_MADE', 'INFO_REQUESTED', 'INCIDENT_RESOLVED', 'INCIDENT_REOPENED',
    'SLA_WARNING', 'SLA_BREACHED', 'SLA_POLICY_MISSING', 'ESCALATED', 'SLA_AT_RISK'));
