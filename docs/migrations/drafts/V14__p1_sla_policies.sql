CREATE TABLE sla_policies (
    id                    BIGSERIAL   PRIMARY KEY,
    service_id            BIGINT      REFERENCES it_services (id),   -- NULL = global default
    priority              VARCHAR(2)  NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    response_minutes      INTEGER     NOT NULL CHECK (response_minutes > 0),
    resolution_minutes    INTEGER     NOT NULL,
    warning_threshold_pct SMALLINT    NOT NULL DEFAULT 80 CHECK (warning_threshold_pct BETWEEN 1 AND 99),
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_resolution_ge_response CHECK (resolution_minutes >= response_minutes),
    -- NULLS NOT DISTINCT (PostgreSQL 15+): at most one global default per priority.
    CONSTRAINT uq_sla_policy_scope UNIQUE NULLS NOT DISTINCT (service_id, priority)
);

-- Global defaults from the project specification.
INSERT INTO sla_policies (service_id, priority, response_minutes, resolution_minutes) VALUES
    (NULL, 'P1',   5,   30),
    (NULL, 'P2',  15,  120),
    (NULL, 'P3',  60,  480),
    (NULL, 'P4', 240, 1440);
