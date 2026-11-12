-- Incidents. P2's block (V20-V29) and P2's table: this is the minimal shape P3 needs, written by P3 so work is not
-- blocked. P2 extends it with later migrations (V21+); it never needs to rewrite this one.
--
-- category_id and service_id are plain BIGINTs for now. Their targets (incident_categories V11, it_services V12) are
-- P1's and are not on main yet; a V21 adds the foreign keys once they are.
CREATE SEQUENCE incident_number_seq START 1001;

CREATE TABLE incidents (
    id               VARCHAR(30)  PRIMARY KEY DEFAULT ('INC' || nextval('incident_number_seq')),
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    state            VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    reporter_id      BIGINT       NOT NULL REFERENCES users (id),
    assignee_id      BIGINT       REFERENCES users (id),
    category_id      BIGINT,
    service_id       BIGINT,
    priority         VARCHAR(2),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    assigned_at      TIMESTAMPTZ,   -- when the CURRENT assignee received it
    first_response_at TIMESTAMPTZ,  -- first acknowledgement ever; never cleared (the response SLA is measured to it)
    resolved_at      TIMESTAMPTZ,   -- set while the fix stands (RESOLVED, VERIFIED, CLOSED); cleared by a reopen
    closed_at        TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,   -- JPA optimistic lock

    CONSTRAINT ck_incident_id          CHECK (id ~ '^[A-Za-z0-9_-]{1,30}$'),
    CONSTRAINT ck_incident_title       CHECK (title = btrim(title) AND title <> ''),
    CONSTRAINT ck_incident_description CHECK (char_length(btrim(description)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_incident_state       CHECK (state IN
        ('NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED', 'VERIFIED', 'CLOSED')),
    CONSTRAINT ck_incident_priority    CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    -- The lifecycle's promises, re-enforced here so a bug or a manual UPDATE cannot break them:
    CONSTRAINT ck_incident_classified  CHECK (state = 'NEW'
        OR (category_id IS NOT NULL AND service_id IS NOT NULL AND priority IS NOT NULL)),
    CONSTRAINT ck_incident_assignee    CHECK ((assignee_id IS NOT NULL) = (state NOT IN ('NEW', 'CLASSIFIED'))),
    CONSTRAINT ck_incident_assigned_at CHECK ((assigned_at IS NOT NULL) = (assignee_id IS NOT NULL)),
    CONSTRAINT ck_incident_resolved_at CHECK ((resolved_at IS NOT NULL) = (state IN ('RESOLVED', 'VERIFIED', 'CLOSED'))),
    CONSTRAINT ck_incident_closed_at   CHECK ((closed_at IS NOT NULL) = (state = 'CLOSED'))
);

-- Workload: one technician's open incidents (the states that count are IncidentState.countsTowardWorkload).
CREATE INDEX idx_incidents_workload ON incidents (assignee_id)
    WHERE state IN ('ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO');
-- Round robin: when did this technician last receive an incident.
CREATE INDEX idx_incidents_last_assigned ON incidents (assignee_id, assigned_at DESC) WHERE assignee_id IS NOT NULL;
-- "My incidents" for a reporter, and the queue by state.
CREATE INDEX idx_incidents_reporter ON incidents (reporter_id, created_at DESC);
CREATE INDEX idx_incidents_state ON incidents (state, created_at);
-- Past fixes for similar incidents.
CREATE INDEX idx_incidents_history ON incidents (category_id, resolved_at DESC) WHERE resolved_at IS NOT NULL;
