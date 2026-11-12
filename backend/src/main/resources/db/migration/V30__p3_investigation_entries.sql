-- P3 investigation timeline. Append-only, like audit_events. incident_id is a plain string with no FK:
-- P2 owns incidents and the format is theirs (1..30 chars of [A-Za-z0-9_-]).
CREATE FUNCTION p3_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE investigation_entries (
    id             BIGSERIAL    PRIMARY KEY,
    incident_id    VARCHAR(30)  NOT NULL CHECK (incident_id ~ '^[A-Za-z0-9_-]{1,30}$'),
    author_id      BIGINT       NOT NULL REFERENCES users (id),
    entry_type     VARCHAR(20)  NOT NULL CHECK (entry_type IN
                   ('NOTE', 'FINDING', 'ACTION_TAKEN', 'INFO_REQUEST', 'INFO_RESPONSE', 'RESOLUTION', 'REOPEN_REASON', 'REASSIGNMENT')),
    visibility     VARCHAR(20)  NOT NULL CHECK (visibility IN ('INTERNAL', 'REPORTER_VISIBLE')),
    body           TEXT         NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 4000),
    -- the incident's state when the entry was written (plain strings: this table knows no P3 enum)
    incident_state VARCHAR(20)  NOT NULL CHECK (incident_state IN
                   ('NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED', 'VERIFIED', 'CLOSED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Same invariants as the domain model: a question or answer can never be hidden from the reporter,
    -- and a reassignment note can never be shown to them.
    CONSTRAINT ck_investigation_visibility CHECK (
        (entry_type IN ('INFO_REQUEST', 'INFO_RESPONSE', 'RESOLUTION', 'REOPEN_REASON') AND visibility = 'REPORTER_VISIBLE')
        OR (entry_type = 'REASSIGNMENT' AND visibility = 'INTERNAL')
        OR entry_type IN ('NOTE', 'FINDING', 'ACTION_TAKEN'))
);
CREATE INDEX idx_investigation_incident ON investigation_entries (incident_id, created_at, id);

CREATE TRIGGER trg_investigation_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON investigation_entries
    FOR EACH ROW EXECUTE FUNCTION p3_reject_mutation();
CREATE TRIGGER trg_investigation_entries_no_truncate
    BEFORE TRUNCATE ON investigation_entries
    FOR EACH STATEMENT EXECUTE FUNCTION p3_reject_mutation();
