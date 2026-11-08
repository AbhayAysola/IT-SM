CREATE TABLE assignment_settings (
    id                   SMALLINT    PRIMARY KEY CHECK (id = 1),     -- singleton row
    active_strategy      VARCHAR(50) NOT NULL,
    default_max_workload INTEGER     NOT NULL CHECK (default_max_workload > 0),
    updated_by           BIGINT      REFERENCES users (id),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- P3 must register a strategy with this key (see docs/architecture/integration-guide.md).
INSERT INTO assignment_settings (id, active_strategy, default_max_workload) VALUES (1, 'LOAD_BASED', 5);

CREATE TABLE assignment_category_overrides (
    category_id BIGINT      PRIMARY KEY REFERENCES incident_categories (id),
    strategy    VARCHAR(50) NOT NULL
);
