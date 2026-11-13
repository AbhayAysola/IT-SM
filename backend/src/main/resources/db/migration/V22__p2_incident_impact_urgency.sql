-- P2 intake: keep the reporter's answers that P4's PriorityPolicy turns into the priority, so a priority can be explained
-- and recalculated later (docs/architecture/p4.md section 1). Nullable: incidents created before this migration, and
-- incidents inserted by other paths (tests, P3's fixtures), have no answers.
ALTER TABLE incidents
    ADD COLUMN impact  VARCHAR(10),
    ADD COLUMN urgency VARCHAR(10),
    ADD CONSTRAINT ck_incident_impact  CHECK (impact  IN ('HIGH', 'MEDIUM', 'LOW')),
    ADD CONSTRAINT ck_incident_urgency CHECK (urgency IN ('HIGH', 'MEDIUM', 'LOW')),
    -- Both or neither: a priority is assessed from the pair.
    ADD CONSTRAINT ck_incident_impact_urgency CHECK ((impact IS NULL) = (urgency IS NULL));
