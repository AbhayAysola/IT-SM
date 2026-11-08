-- Append-only. incident_id is a plain string (no FK): P2 owns incidents and may use any id format.
CREATE TABLE audit_events (
    id             BIGSERIAL    PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor_id       BIGINT,                        -- no FK: the record must outlive the user
    actor_role     VARCHAR(20),
    actor_label    VARCHAR(255) NOT NULL,         -- email, or SYSTEM job name
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      VARCHAR(40),
    incident_id    VARCHAR(30),
    previous_state VARCHAR(40),
    new_state      VARCHAR(40),
    description    TEXT         NOT NULL,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_audit_incident_time ON audit_events (incident_id, occurred_at);
CREATE INDEX idx_audit_actor         ON audit_events (actor_id);
CREATE INDEX idx_audit_action        ON audit_events (action);
CREATE INDEX idx_audit_occurred_at   ON audit_events (occurred_at);

CREATE FUNCTION audit_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_reject_mutation();
