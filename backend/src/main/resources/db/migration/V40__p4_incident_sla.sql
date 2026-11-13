-- P4 SLA tracking (block V40-V49). One row per incident, written only by the SLA monitor (sla/SlaMonitor), read by the
-- SLA API. Rules: docs/architecture/p4.md section 2. Rows are never deleted (incidents are never deleted either).
CREATE TABLE incident_sla (
    incident_id            VARCHAR(30)  PRIMARY KEY REFERENCES incidents (id),
    -- The priority and service the targets were resolved for.
    priority               VARCHAR(2)   NOT NULL,
    service_id             BIGINT       NOT NULL REFERENCES it_services (id),
    clock_start            TIMESTAMPTZ  NOT NULL,   -- the incident's created_at

    -- Targets copied from the resolved policy, so a later policy edit does not silently move existing deadlines.
    policy_id              BIGINT       REFERENCES sla_policies (id),
    policy_scope           VARCHAR(10),
    response_minutes       INTEGER,
    resolution_minutes     INTEGER,
    warning_threshold_pct  SMALLINT,
    no_policy_reason       VARCHAR(30),

    response_due_at        TIMESTAMPTZ,
    response_warning_at    TIMESTAMPTZ,
    response_warned_at     TIMESTAMPTZ,
    response_met_at        TIMESTAMPTZ,
    response_breached_at   TIMESTAMPTZ,

    resolution_due_at      TIMESTAMPTZ,   -- includes paused_seconds
    resolution_warning_at  TIMESTAMPTZ,
    resolution_warned_at   TIMESTAMPTZ,
    resolution_met_at      TIMESTAMPTZ,
    resolution_breached_at TIMESTAMPTZ,

    paused_since           TIMESTAMPTZ,   -- the resolution clock is paused while this is set
    paused_seconds         BIGINT       NOT NULL DEFAULT 0,

    response_status        VARCHAR(10)  NOT NULL,
    resolution_status      VARCHAR(10)  NOT NULL,
    status                 VARCHAR(10)  NOT NULL,   -- the most serious of the two clocks
    complete               BOOLEAN      NOT NULL DEFAULT FALSE,   -- incident VERIFIED or CLOSED: no longer evaluated

    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_sla_priority      CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_sla_scope         CHECK (policy_scope IN ('SERVICE', 'GLOBAL')),
    CONSTRAINT ck_sla_reason        CHECK (no_policy_reason IN ('MISSING_SLA_CONFIG', 'SERVICE_NOT_FOUND')),
    -- Either complete targets, or none and a reason. Ranges mirror P1's policy validation.
    CONSTRAINT ck_sla_targets       CHECK (
        (policy_id IS NOT NULL AND policy_scope IS NOT NULL AND no_policy_reason IS NULL
            AND response_minutes >= 1 AND resolution_minutes > response_minutes
            AND warning_threshold_pct BETWEEN 1 AND 99)
        OR (policy_id IS NULL AND policy_scope IS NULL AND response_minutes IS NULL AND resolution_minutes IS NULL
            AND warning_threshold_pct IS NULL AND no_policy_reason IS NOT NULL)),
    CONSTRAINT ck_sla_paused        CHECK (paused_seconds >= 0),
    CONSTRAINT ck_sla_clock_status  CHECK (
        response_status IN ('NO_POLICY', 'RUNNING', 'WARNING', 'PAUSED', 'MET', 'BREACHED')
        AND resolution_status IN ('NO_POLICY', 'RUNNING', 'WARNING', 'PAUSED', 'MET', 'BREACHED')),
    CONSTRAINT ck_sla_status        CHECK (status IN ('NO_POLICY', 'BREACHED', 'WARNING', 'PAUSED', 'RUNNING', 'MET')),
    -- A breach is dated at its deadline; deadlines exist exactly when targets do.
    CONSTRAINT ck_sla_deadlines     CHECK ((response_due_at IS NULL) = (policy_id IS NULL)
        AND (resolution_due_at IS NULL) = (policy_id IS NULL)),
    CONSTRAINT ck_sla_breached      CHECK ((response_breached_at IS NULL OR response_status = 'BREACHED')
        AND (resolution_breached_at IS NULL OR resolution_status = 'BREACHED'))
);

-- The monitor's work list and the "needs attention" lists: open SLAs by status.
CREATE INDEX idx_incident_sla_open ON incident_sla (status, resolution_due_at) WHERE NOT complete;
