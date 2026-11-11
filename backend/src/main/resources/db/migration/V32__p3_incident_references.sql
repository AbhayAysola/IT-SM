-- P3's two tables reference incidents now that the table exists (V30 and V31 deliberately had no foreign key).
-- The tables are append-only, so the constraints can only ever reject new rows for an unknown incident.
ALTER TABLE investigation_entries
    ADD CONSTRAINT fk_investigation_incident FOREIGN KEY (incident_id) REFERENCES incidents (id);
ALTER TABLE incident_feedback
    ADD CONSTRAINT fk_feedback_incident FOREIGN KEY (incident_id) REFERENCES incidents (id);
