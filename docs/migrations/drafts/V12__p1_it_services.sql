CREATE TABLE it_services (
    id                   BIGSERIAL    PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL UNIQUE,
    description          TEXT,
    business_criticality SMALLINT     NOT NULL CHECK (business_criticality BETWEEN 1 AND 5),
    owner_team           VARCHAR(100),
    active               BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE service_dependencies (
    service_id    BIGINT NOT NULL REFERENCES it_services (id),
    depends_on_id BIGINT NOT NULL REFERENCES it_services (id),
    PRIMARY KEY (service_id, depends_on_id),
    CONSTRAINT chk_no_self_dependency CHECK (service_id <> depends_on_id)
);
-- Reverse lookups ("what depends on X?") used by correlation / root-cause analysis.
CREATE INDEX idx_service_dependencies_depends_on ON service_dependencies (depends_on_id);
