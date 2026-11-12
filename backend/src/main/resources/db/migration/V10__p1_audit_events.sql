-- Persistent, append-only audit trail (P1). One row per com.itsm.common.event.AuditableEvent.
--
-- Written by AuditEventPersistenceListener in the SAME transaction as the business change (BEFORE_COMMIT), so the
-- row commits or rolls back with it.
--
-- No foreign keys on purpose: audit rows must outlive the users and incidents they describe, and incident ids are
-- plain strings owned by P2. The actor is identified by stable id + role (users) or a job name (system), never by
-- email or any other personal detail.
CREATE TABLE audit_events (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL DEFAULT gen_random_uuid(),   -- stable public identifier
    occurred_at    TIMESTAMPTZ  NOT NULL,                             -- AuditableEvent.occurredAt
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),               -- when the row was written
    actor_type     VARCHAR(10)  NOT NULL,
    actor_id       BIGINT,                                            -- users.id at the time; no FK (see above)
    actor_role     VARCHAR(20),
    actor_name     VARCHAR(40),                                       -- SYSTEM actors only (e.g. SLA_MONITOR)
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      VARCHAR(40),
    incident_id    VARCHAR(30),
    previous_state VARCHAR(40),
    new_state      VARCHAR(40),
    description    TEXT         NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_audit_events_event_id UNIQUE (event_id),
    CONSTRAINT chk_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT chk_audit_actor_shape CHECK (
        (actor_type = 'USER'   AND actor_id IS NOT NULL AND actor_role IS NOT NULL AND actor_name IS NULL) OR
        (actor_type = 'SYSTEM' AND actor_id IS NULL     AND actor_role IS NULL     AND actor_name IS NOT NULL)
    ),
    CONSTRAINT chk_audit_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

-- Future filtering: time range, actor, entity type, action, entity id (+ incident timeline).
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at DESC, id DESC);
CREATE INDEX idx_audit_events_actor       ON audit_events (actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_events_entity_type ON audit_events (entity_type, occurred_at DESC);
CREATE INDEX idx_audit_events_action      ON audit_events (action, occurred_at DESC);
CREATE INDEX idx_audit_events_entity      ON audit_events (entity_type, entity_id) WHERE entity_id IS NOT NULL;
CREATE INDEX idx_audit_events_incident    ON audit_events (incident_id, occurred_at) WHERE incident_id IS NOT NULL;

-- Append-only. This trigger is the authoritative guard; the JPA entity is also @Immutable and the repository has
-- no update/delete API, but neither of those can stop a SQL client.
CREATE FUNCTION audit_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_reject_mutation();
