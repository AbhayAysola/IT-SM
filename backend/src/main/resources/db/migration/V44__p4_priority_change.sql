-- P4 support for changing an incident's priority while it is open (block V40-V49; docs/architecture/p4.md section 1).
--
-- When the targets change during tracking (a new priority or service), each clock's deadline is moved deliberately
-- rather than re-dated from when the incident was reported, which would make a raised priority breach at once:
--  * a clock that already stopped (met) or breached keeps its deadline;
--  * a tighter target moves the deadline to the earlier of the old deadline and (time of the change + the new target);
--  * a looser target re-dates it from the report as usual.
-- The shift is what is added to "clock start + target (+ pauses)" to get that deadline; 0 for every SLA so far.
ALTER TABLE incident_sla ADD COLUMN response_shift_seconds   BIGINT NOT NULL DEFAULT 0;
ALTER TABLE incident_sla ADD COLUMN resolution_shift_seconds BIGINT NOT NULL DEFAULT 0;

-- The assignee is told when an incident's priority changes (notification type PRIORITY_CHANGED).
ALTER TABLE notifications DROP CONSTRAINT ck_notification_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'INCIDENT_ASSIGNED', 'ASSIGNMENT_MADE', 'INFO_REQUESTED', 'INCIDENT_RESOLVED', 'INCIDENT_REOPENED',
    'SLA_WARNING', 'SLA_BREACHED', 'SLA_POLICY_MISSING', 'ESCALATED', 'SLA_AT_RISK', 'PRIORITY_CHANGED'));
