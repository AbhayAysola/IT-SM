-- P3 post-resolution feedback: one row per incident, final once written. The primary key on incident_id is what
-- stops two racing submissions (the domain's duplicate check only sees committed rows).
CREATE TABLE incident_feedback (
    incident_id  VARCHAR(30) PRIMARY KEY CHECK (incident_id ~ '^[A-Za-z0-9_-]{1,30}$'),
    reporter_id  BIGINT      NOT NULL REFERENCES users (id),
    rating       INTEGER     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment      TEXT        CHECK (comment IS NULL OR char_length(btrim(comment)) BETWEEN 1 AND 1000),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_incident_feedback_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_feedback
    FOR EACH ROW EXECUTE FUNCTION p3_reject_mutation();
CREATE TRIGGER trg_incident_feedback_no_truncate
    BEFORE TRUNCATE ON incident_feedback
    FOR EACH STATEMENT EXECUTE FUNCTION p3_reject_mutation();
