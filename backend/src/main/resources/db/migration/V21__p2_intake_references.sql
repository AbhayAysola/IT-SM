-- P2 intake: add FK constraints from incidents to the category and service catalogs.
-- V11 (incident_categories) and V12 (it_services) are P1's and are now on main.
ALTER TABLE incidents
    ADD CONSTRAINT fk_incidents_category FOREIGN KEY (category_id) REFERENCES incident_categories (id),
    ADD CONSTRAINT fk_incidents_service  FOREIGN KEY (service_id)  REFERENCES it_services (id);
