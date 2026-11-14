-- P4 priority suggestions (block V40-V49; docs/architecture/p4.md section 1, "Priority suggestions"). The priority monitor
-- suggests a new priority for an open incident when its priority looks out of date; an admin applies it (through P2's
-- priority endpoint) or dismisses it. One open suggestion per incident; a dismissed priority is not suggested again.
CREATE TABLE incident_priority_suggestions (
    id                  BIGSERIAL     PRIMARY KEY,
    incident_id         VARCHAR(30)   NOT NULL REFERENCES incidents (id),
    -- The incident's priority when the suggestion was made, and the suggested one.
    current_priority    VARCHAR(2)    NOT NULL,
    suggested_priority  VARCHAR(2)    NOT NULL,
    -- Which rules fired (CRITICALITY, DUPLICATES) and plain-language reasons shown to admins.
    rules               VARCHAR(20)[] NOT NULL,
    reasons             TEXT[]        NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    -- OPEN until an admin applies (APPLIED: the priority became the suggested one) or dismisses it, or the incident moves on
    -- (OUTDATED: the priority changed to something else, or it is no longer being worked on).
    status              VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    closed_at           TIMESTAMPTZ,
    closed_by           BIGINT,        -- the admin who dismissed it (no FK: history outlives account changes)

    CONSTRAINT ck_suggestion_priorities CHECK (current_priority IN ('P1', 'P2', 'P3', 'P4')
        AND suggested_priority IN ('P1', 'P2', 'P3', 'P4') AND current_priority <> suggested_priority),
    CONSTRAINT ck_suggestion_rules      CHECK (cardinality(rules) >= 1 AND rules <@ ARRAY['CRITICALITY', 'DUPLICATES']::VARCHAR(20)[]),
    CONSTRAINT ck_suggestion_reasons    CHECK (cardinality(reasons) >= 1),
    CONSTRAINT ck_suggestion_status     CHECK (status IN ('OPEN', 'APPLIED', 'DISMISSED', 'OUTDATED')),
    CONSTRAINT ck_suggestion_closed     CHECK ((status = 'OPEN') = (closed_at IS NULL))
);
CREATE UNIQUE INDEX uq_incident_open_suggestion ON incident_priority_suggestions (incident_id) WHERE status = 'OPEN';

-- Every active admin is told about a new suggestion (notification type PRIORITY_SUGGESTED).
ALTER TABLE notifications DROP CONSTRAINT ck_notification_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'INCIDENT_ASSIGNED', 'ASSIGNMENT_MADE', 'INFO_REQUESTED', 'INCIDENT_RESOLVED', 'INCIDENT_REOPENED',
    'SLA_WARNING', 'SLA_BREACHED', 'SLA_POLICY_MISSING', 'ESCALATED', 'SLA_AT_RISK', 'PRIORITY_CHANGED',
    'PRIORITY_SUGGESTED'));
