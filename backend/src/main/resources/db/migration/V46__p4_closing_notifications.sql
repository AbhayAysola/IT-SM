-- P4 notifications when an incident is finished (block V40-V49; docs/architecture/p4.md section 4): the technician hears
-- that the reporter confirmed the fix (INCIDENT_VERIFIED), and the reporter that it was closed (INCIDENT_CLOSED).
ALTER TABLE notifications DROP CONSTRAINT ck_notification_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'INCIDENT_ASSIGNED', 'ASSIGNMENT_MADE', 'INFO_REQUESTED', 'INCIDENT_RESOLVED', 'INCIDENT_REOPENED',
    'SLA_WARNING', 'SLA_BREACHED', 'SLA_POLICY_MISSING', 'ESCALATED', 'SLA_AT_RISK', 'PRIORITY_CHANGED',
    'PRIORITY_SUGGESTED', 'INCIDENT_VERIFIED', 'INCIDENT_CLOSED'));
