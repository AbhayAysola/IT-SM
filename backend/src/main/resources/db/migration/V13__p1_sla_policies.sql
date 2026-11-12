-- SLA policy configuration (P1). Backs com.itsm.common.spi.SlaPolicyProvider and /api/admin/sla-policies.
-- P1 stores and serves the targets only; P4 owns timers, breach detection and escalation.
--
-- service_id NULL = a global policy for that priority. Policies are never deleted (history); retiring one means
-- active = FALSE. Service and priority identify a policy and cannot be changed after creation.
CREATE TABLE sla_policies (
    id                    BIGSERIAL    PRIMARY KEY,
    service_id            BIGINT       REFERENCES it_services (id),
    priority              VARCHAR(2)   NOT NULL,
    response_minutes      INTEGER      NOT NULL,
    resolution_minutes    INTEGER      NOT NULL,
    warning_threshold_pct SMALLINT     NOT NULL DEFAULT 80,    -- SlaPolicyView.warningThresholdPct
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    version               BIGINT       NOT NULL DEFAULT 0,     -- JPA optimistic lock
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sla_priority CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT chk_sla_response_positive CHECK (response_minutes > 0),
    CONSTRAINT chk_sla_resolution_after_response CHECK (resolution_minutes > response_minutes),
    CONSTRAINT chk_sla_resolution_max CHECK (resolution_minutes <= 43200),            -- 30 days
    CONSTRAINT chk_sla_warning_threshold CHECK (warning_threshold_pct BETWEEN 1 AND 99)
);

-- At most one ACTIVE policy per (service, priority), and at most one ACTIVE global policy per priority. These are
-- partial indexes on purpose: `UNIQUE NULLS NOT DISTINCT (service_id, priority)` would also forbid a second row
-- once the first was deactivated, destroying the history this table must keep. A service-specific and a global
-- policy for the same priority do not conflict (different indexes), and any number of inactive rows may coexist.
CREATE UNIQUE INDEX uq_sla_active_service_priority ON sla_policies (service_id, priority)
    WHERE active AND service_id IS NOT NULL;
CREATE UNIQUE INDEX uq_sla_active_global_priority ON sla_policies (priority)
    WHERE active AND service_id IS NULL;

-- Resolution lookup (SlaPolicyProvider): active rows for one priority and one service or the global scope.
CREATE INDEX idx_sla_policies_resolve ON sla_policies (priority, service_id) WHERE active;
CREATE INDEX idx_sla_policies_service ON sla_policies (service_id);

-- Global defaults from the project specification. Nothing relies on them existing: the provider reports a missing
-- policy explicitly, and an administrator may deactivate any of them.
INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes) VALUES
    (NULL, 'P1',   5,   30),
    (NULL, 'P2',  15,  120),
    (NULL, 'P3',  60,  480),
    (NULL, 'P4', 240, 1440);
