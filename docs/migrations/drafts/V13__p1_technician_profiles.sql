-- Role check ("this user is a TECHNICIAN") is enforced in the application layer.
CREATE TABLE technician_profiles (
    user_id          BIGINT   PRIMARY KEY REFERENCES users (id),
    max_workload     INTEGER  NOT NULL DEFAULT 5 CHECK (max_workload > 0),
    experience_years INTEGER  NOT NULL DEFAULT 0 CHECK (experience_years >= 0),
    available        BOOLEAN  NOT NULL DEFAULT TRUE,
    is_team_lead     BOOLEAN  NOT NULL DEFAULT FALSE,
    reports_to       BIGINT   REFERENCES users (id),        -- escalation chain
    CONSTRAINT chk_not_own_manager CHECK (reports_to IS NULL OR reports_to <> user_id)
);

CREATE TABLE technician_skills (
    user_id     BIGINT   NOT NULL REFERENCES technician_profiles (user_id) ON DELETE CASCADE,
    category_id BIGINT   NOT NULL REFERENCES incident_categories (id),
    proficiency SMALLINT NOT NULL CHECK (proficiency BETWEEN 1 AND 5),
    PRIMARY KEY (user_id, category_id)
);
CREATE INDEX idx_technician_skills_category ON technician_skills (category_id);
