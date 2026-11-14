-- P4 notifications (block V40-V49). Rules: docs/architecture/p4.md section 4.

-- Every user's in-app inbox. A row is written once; only read_at ever changes (once, from NULL), enforced below.
CREATE TABLE notifications (
    id            BIGSERIAL     PRIMARY KEY,
    recipient_id  BIGINT        NOT NULL REFERENCES users (id),
    type          VARCHAR(30)   NOT NULL,
    incident_id   VARCHAR(30)   REFERENCES incidents (id),
    -- The incident's priority when it was sent: it chose the channels.
    priority      VARCHAR(2),
    title         VARCHAR(200)  NOT NULL,
    body          VARCHAR(1000) NOT NULL,
    -- Where it was delivered: IN_APP always, EMAIL and SMS (simulated) by priority.
    channels      VARCHAR(10)[] NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,

    CONSTRAINT ck_notification_type CHECK (type IN (
        'INCIDENT_ASSIGNED', 'ASSIGNMENT_MADE', 'INFO_REQUESTED', 'INCIDENT_RESOLVED', 'INCIDENT_REOPENED',
        'SLA_WARNING', 'SLA_BREACHED', 'SLA_POLICY_MISSING', 'ESCALATED')),
    CONSTRAINT ck_notification_priority CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_notification_channels CHECK (channels <@ ARRAY['IN_APP', 'EMAIL', 'SMS']::VARCHAR(10)[]
        AND 'IN_APP' = ANY (channels)),
    CONSTRAINT ck_notification_text CHECK (btrim(title) <> '' AND btrim(body) <> '')
);
-- The inbox, newest first, and the unread badge.
CREATE INDEX idx_notifications_inbox ON notifications (recipient_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;

CREATE FUNCTION p4_notification_mark_read_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION 'notifications cannot be deleted (% rejected)', TG_OP;
    END IF;
    IF OLD.read_at IS NOT NULL OR NEW.read_at IS NULL
       OR (NEW.id, NEW.recipient_id, NEW.type, NEW.incident_id, NEW.priority, NEW.title, NEW.body, NEW.channels, NEW.created_at)
          IS DISTINCT FROM
          (OLD.id, OLD.recipient_id, OLD.type, OLD.incident_id, OLD.priority, OLD.title, OLD.body, OLD.channels, OLD.created_at) THEN
        RAISE EXCEPTION 'a notification can only be marked read, once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notifications_mark_read_only
    BEFORE UPDATE OR DELETE ON notifications
    FOR EACH ROW EXECUTE FUNCTION p4_notification_mark_read_only();
CREATE TRIGGER trg_notifications_no_truncate
    BEFORE TRUNCATE ON notifications
    FOR EACH STATEMENT EXECUTE FUNCTION p4_reject_mutation();

-- The last state and assignee P4 saw for each incident, so lifecycle changes are noticed (and notified) once. Polling
-- bookkeeping until P2/P3 publish domain events (docs/architecture/p4.md section 6).
CREATE TABLE notification_watch (
    incident_id  VARCHAR(30)  PRIMARY KEY REFERENCES incidents (id),
    state        VARCHAR(20)  NOT NULL,
    assignee_id  BIGINT,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
