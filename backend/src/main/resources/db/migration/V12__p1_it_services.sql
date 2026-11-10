-- IT service catalog (P1). Backs com.itsm.common.spi.ServiceCatalog and /api/admin/services.
--
-- Services are never deleted (incidents and SLA policies reference them); retiring one means active = FALSE.
CREATE TABLE it_services (
    id                   BIGSERIAL    PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    description          VARCHAR(300),
    business_criticality SMALLINT     NOT NULL,              -- 1 (low) .. 5 (mission critical), ServiceView contract
    owner_team           VARCHAR(100),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    version              BIGINT       NOT NULL DEFAULT 0,    -- JPA optimistic lock
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_service_criticality CHECK (business_criticality BETWEEN 1 AND 5),
    CONSTRAINT chk_service_name CHECK (name = btrim(name) AND char_length(name) > 0),
    CONSTRAINT chk_service_description CHECK (description IS NULL OR (description = btrim(description) AND description <> '')),
    CONSTRAINT chk_service_owner_team CHECK (owner_team IS NULL OR (owner_team = btrim(owner_team) AND owner_team <> ''))
);
CREATE UNIQUE INDEX uq_it_services_name_ci ON it_services (lower(name));
CREATE INDEX idx_it_services_owner_team_ci ON it_services (lower(owner_team));

-- Direct edges only: "service_id depends on depends_on_id". Transitive impact is derived by walking the edges.
CREATE TABLE service_dependencies (
    service_id    BIGINT NOT NULL REFERENCES it_services (id),
    depends_on_id BIGINT NOT NULL REFERENCES it_services (id),
    PRIMARY KEY (service_id, depends_on_id),                  -- no duplicate edge
    CONSTRAINT chk_no_self_dependency CHECK (service_id <> depends_on_id)
);
-- Reverse lookups ("what depends on X?"): ServiceCatalog.dependentsOf, deactivation checks.
CREATE INDEX idx_service_dependencies_depends_on ON service_dependencies (depends_on_id);

-- Authoritative cycle guard. The transaction-scoped advisory lock serialises every writer of the graph (the
-- application takes the same lock before its own checks), so two concurrent inserts cannot each miss the other's edge
-- and close a cycle together. Raised as SQLSTATE 23000 -> HTTP 409 CONFLICT.
CREATE FUNCTION service_dependencies_reject_cycle() RETURNS trigger AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(7310002);
    IF EXISTS (
        WITH RECURSIVE reachable (id) AS (
            SELECT depends_on_id FROM service_dependencies WHERE service_id = NEW.depends_on_id
            UNION
            SELECT d.depends_on_id FROM service_dependencies d JOIN reachable r ON d.service_id = r.id
        )
        SELECT 1 FROM reachable WHERE id = NEW.service_id
    ) THEN
        RAISE EXCEPTION 'service dependency % -> % would create a cycle', NEW.service_id, NEW.depends_on_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_service_dependencies_reject_cycle
    BEFORE INSERT OR UPDATE ON service_dependencies
    FOR EACH ROW EXECUTE FUNCTION service_dependencies_reject_cycle();
