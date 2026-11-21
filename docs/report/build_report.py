#!/usr/bin/env python3
"""Builds the project report PDF (docs/report/IT-SM-Project-Report.pdf).

    python3 docs/report/diagrams.py        # (re)generate the diagrams (needs Graphviz)
    python3 docs/report/build_report.py    # needs Node + Playwright's Chromium (frontend/node_modules)

The test-case table cites real tests; the build fails if a cited test no longer exists, so the report cannot drift from the code.
Fill in the team names and group number in TEAM / GROUP below before submitting.
"""
import html
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
DIAGRAMS = HERE / "diagrams"
GROUP = "[Group No.]"
TEAM = [  # name, id, area
    ("Abhay Aysola", "[ID]", "P3: incident lifecycle, assignment, investigation, feedback"),
    ("[Name]", "[ID]", "P1: access, administration, audit, deployment and operations"),
    ("[Name]", "[ID]", "P2: incident intake, AI categorisation, chatbot and voice, duplicate hints"),
    ("[Name]", "[ID]", "P4: priority, SLA, escalation, notifications, analytics"),
]
REPO_URL = "https://github.com/AbhayAysola/IT-SM"


def e(s: str) -> str:
    return html.escape(s, quote=False)


def svg(name: str) -> str:
    raw = (DIAGRAMS / f"{name}.svg").read_text()
    raw = re.sub(r"<\?xml.*?\?>|<!DOCTYPE.*?>", "", raw, flags=re.S)
    raw = re.sub(r'(<svg[^>]*?)\swidth="[^"]*"\sheight="[^"]*"', r"\1", raw, count=1)
    return f'<div class="fig">{raw}</div>'


def table(head, rows, cls="") -> str:
    h = "".join(f"<th>{e(c)}</th>" for c in head)
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<table class="{cls}"><thead><tr>{h}</tr></thead><tbody>{body}</tbody></table>'


# ---------------------------------------------------------------------------------------------------------------------
# Test cases: (id, category, scenario, expected, test file under backend/src/test/kotlin/com/itsm, test name)
# ---------------------------------------------------------------------------------------------------------------------
TESTS = [
    ("T01", "Normal", "A reported incident is worked from NEW through ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, VERIFIED to CLOSED.", "Every step is accepted for the right role; the incident ends CLOSED.",
     "lifecycle/IncidentStateMachineTest.kt", "the happy path walks the whole lifecycle"),
    ("T02", "Normal", "The same lifecycle over HTTP: technician works, reporter confirms and rates.", "Each call returns 200/201 and the final state and feedback are persisted.",
     "lifecycle/IncidentApiIntegrationTest.kt", "an incident is worked from assignment to rated feedback over HTTP"),
    ("T03", "Normal", "Load-based assignment with two technicians carrying 3 and 1 open incidents.", "The technician with 1 is chosen; the audit event records the strategy and technician.",
     "lifecycle/IncidentAssignmentServiceTest.kt", "load based picks the least loaded technician and records how"),
    ("T04", "Normal", "Priority from impact, urgency and service criticality (the worked examples of the design).", "HIGH/HIGH on criticality 3 gives P1; MEDIUM/HIGH on criticality 5 gives P1; HIGH/HIGH on criticality 1 gives P2; LOW/LOW on criticality 5 gives P3.",
     "priority/PriorityCalculatorTest.kt", "the worked examples in the design doc hold"),
    ("T05", "Normal", "SLA tracking starts for a new incident.", "Response and resolution deadlines are computed from created_at and the policy targets.",
     "sla/SlaEvaluatorTest.kt", "tracking starts with deadlines from created_at"),
    ("T06", "Boundary", "Criticality 5 on a P1 candidate.", "Priority rises by exactly one level but never past P1.",
     "priority/PriorityCalculatorTest.kt", "criticality 5 makes it exactly one level more urgent, never past P1"),
    ("T07", "Boundary", "Two versus three active services depend on the incident's service.", "Three dependents raise the impact one level; two do not.",
     "priority/PriorityCalculatorTest.kt", "three dependents widen the impact by one level, two do not"),
    ("T08", "Boundary", "A technician with exactly as many open incidents as their limit.", "Capacity is strictly below the limit: at the limit the technician is not eligible.",
     "assignment/EligibilityPolicyTest.kt", "capacity is strictly below the limit"),
    ("T09", "Boundary", "Feedback with ratings 0, 1, 5 and 6.", "Only 1 to 5 are accepted; anything else is a field error.",
     "feedback/FeedbackPolicyTest.kt", "ratings outside 1 to 5 are a field error"),
    ("T10", "Boundary", "A resolution clock reaches 150% and then 200% of its target.", "150% escalates to the next manager and 200% to the administrators (a breach itself goes to the team lead, covered by the neighbouring test).",
     "escalation/EscalationPolicyTest.kt", "the resolution clock escalates further at 150 and 200 percent of its target"),
    ("T11", "Invalid input", "Sign in with a wrong password, an unknown email and a disabled account.", "All three look identical to the caller (no account enumeration).",
     "auth/AuthSecurityIntegrationTest.kt", "login works, and wrong password, unknown email and disabled account are indistinguishable"),
    ("T12", "Invalid input", "Register with invalid fields.", "400 with field errors and no account is created.",
     "auth/AuthSecurityIntegrationTest.kt", "registration validates input and creates nothing on failure"),
    ("T13", "Invalid input", "A reason longer than the allowed length is submitted with a transition.", "Rejected with a field error (reasons that are required but missing are covered by the neighbouring test).",
     "lifecycle/IncidentStateMachineTest.kt", "an over-long reason is rejected"),
    ("T14", "Invalid input", "List incidents with a malformed priority, date or state filter.", "400 naming the parameter, never an empty list.",
     "lifecycle/IncidentApiIntegrationTest.kt", "the list filters by priority, category, technician and date, and rejects malformed filters"),
    ("T15", "Exception", "Apply a transition from a state where it is not legal (even as an admin).", "409 INVALID_STATE_TRANSITION.",
     "lifecycle/IncidentStateMachineTest.kt", "every transition is rejected from every other state with 409 even for an admin"),
    ("T16", "Exception", "A technician fills up between the assignment decision and the lock.", "The assignment is refused (WORKLOAD_EXCEEDED), never over-assigned.",
     "lifecycle/IncidentAssignmentServiceTest.kt", "a technician filled up between deciding and locking is refused, not over-assigned"),
    ("T17", "Exception", "An incident whose service has no SLA policy.", "Reported once, retried, and tracking starts when a policy appears; nothing crashes.",
     "sla/SlaEvaluatorTest.kt", "a missing policy is reported once, retried, and starts tracking when a policy appears"),
    ("T18", "Exception", "Assignment is requested while the user directory or settings provider is absent.", "503 SERVICE_UNAVAILABLE instead of a startup failure.",
     "lifecycle/IncidentAssignmentServiceTest.kt", "without P1's providers assignment answers 503 instead of the application failing to start"),
    ("T19", "Exception", "Trigger a validation error and a missing resource over HTTP.", "RFC 7807 problem+json with a stable error code.",
     "lifecycle/IncidentApiIntegrationTest.kt", "errors are problem json with stable codes"),
    ("T20", "Authorization", "An employee tries to assign or work an incident.", "Refused with 403.",
     "lifecycle/IncidentStateMachineTest.kt", "an employee cannot assign or work an incident"),
    ("T21", "Authorization", "Another employee, a technician or an admin tries to rate someone else's incident.", "Only the reporter may; a stranger learns nothing about the incident.",
     "feedback/FeedbackPolicyTest.kt", "authorisation is checked before state so a stranger learns nothing about the incident"),
    ("T22", "Authorization", "Anyone but the reporter side tries to verify or reopen.", "Refused.",
     "lifecycle/IncidentStateMachineTest.kt", "only reporter-side permission can verify or reopen"),
    ("T23", "Authorization", "A valid token whose user no longer exists.", "401, never a 500.",
     "auth/AuthSecurityIntegrationTest.kt", "token for a deleted or unknown user is 401, never 500"),
    ("T24", "Authorization", "Register with role ADMIN in the request body.", "The account is created as EMPLOYEE whatever the client sends.",
     "auth/AuthSecurityIntegrationTest.kt", "public registration always creates an EMPLOYEE whatever role the client sends"),
    ("T25", "Business rule", "Every non-terminal state can still reach CLOSED.", "No incident can get stuck.",
     "lifecycle/IncidentStateMachineTest.kt", "every non-terminal state can reach CLOSED, so no incident can get stuck"),
    ("T26", "Business rule", "Several threads assign incidents to one technician with one free slot.", "Exactly one succeeds; capacity is never exceeded (advisory lock).",
     "lifecycle/IncidentFlowIntegrationTest.kt", "concurrent assignments cannot overfill a technician"),
    ("T27", "Business rule", "A state change and its timeline entry, with a failure in between.", "Both commit or both roll back.",
     "lifecycle/IncidentFlowIntegrationTest.kt", "the state change and its timeline entry commit or roll back together"),
    ("T28", "Business rule", "A second feedback for the same incident.", "409; feedback is final (also enforced by the primary key).",
     "feedback/FeedbackPolicyTest.kt", "second feedback is a 409 duplicate, because feedback is final"),
    ("T29", "Business rule", "Automatic assignment finds nobody eligible.", "The administrators are escalated to at once, once, and only while nobody has the incident.",
     "escalation/EscalationPolicyTest.kt", "a failed automatic assignment escalates at once, but only while nobody has it, and only once"),
    ("T30", "Business rule", "The resolution clock while the incident waits for the reporter.", "Paused time does not count towards escalation.",
     "escalation/EscalationPolicyTest.kt", "paused time does not count, and a paused clock is judged at the start of its pause"),
]


def verify_tests() -> None:
    base = REPO / "backend/src/test/kotlin/com/itsm"
    missing = []
    for tid, _, _, _, f, name in TESTS:
        path = base / f
        if not path.exists() or f"`{name}`" not in path.read_text():
            missing.append(f"{tid}: {f} :: {name}")
    if missing:
        sys.exit("cited tests not found:\n  " + "\n  ".join(missing))


# ---------------------------------------------------------------------------------------------------------------------
# Content
# ---------------------------------------------------------------------------------------------------------------------
CSS = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; }
@page wide { size: A4 landscape; margin: 12mm 10mm 16mm 10mm; }
* { box-sizing: border-box; }
body { font-family: "Helvetica Neue", Helvetica, Arial, sans-serif; font-size: 10.2pt; line-height: 1.42; color: #1b1f27; }
h1 { font-size: 19pt; margin: 0 0 8pt; padding-bottom: 4pt; border-bottom: 2px solid #2f4b8a; color: #1d3266; page-break-before: always; }
h1.first { page-break-before: auto; }
h2 { font-size: 13pt; margin: 16pt 0 5pt; color: #1d3266; }
h3 { font-size: 11pt; margin: 11pt 0 4pt; color: #2b2f38; }
p { margin: 0 0 6pt; } ul, ol { margin: 0 0 7pt; padding-left: 17pt; } li { margin-bottom: 2pt; }
code { font-family: "DejaVu Sans Mono", Menlo, monospace; font-size: 8.7pt; background: #f1f3f7; padding: 0 2pt; border-radius: 2px; }
table { width: 100%; border-collapse: collapse; margin: 4pt 0 9pt; font-size: 8.8pt; page-break-inside: auto; }
th { background: #e3e9f6; text-align: left; padding: 3.5pt 5pt; border: 1px solid #b9c4de; }
td { padding: 3pt 5pt; border: 1px solid #ccd3e4; vertical-align: top; } tr { page-break-inside: avoid; }
.fig { margin: 6pt 0 4pt; text-align: center; } .fig svg { width: 100%; height: auto; max-height: 215mm; }
.cap { font-size: 8.8pt; color: #555; text-align: center; margin-bottom: 9pt; font-style: italic; }
.note { border-left: 3px solid #d9a21b; background: #fff8e6; padding: 5pt 8pt; margin: 6pt 0 9pt; font-size: 9.4pt; }
.pb { page-break-before: always; } .wide.er .fig svg { max-height: 152mm; } .wide { page: wide; } .wide .fig svg { max-height: 136mm; } .fig { page-break-inside: avoid; }
.cover { text-align: center; padding-top: 60mm; page-break-after: always; }
.cover h0 { display: block; font-size: 27pt; font-weight: 700; color: #1d3266; margin-bottom: 6pt; }
.cover .sub { font-size: 14pt; color: #444; margin-bottom: 26pt; } .cover table { width: 78%; margin: 14pt auto; font-size: 10pt; }
.toc li { margin-bottom: 1pt; } .small { font-size: 8.6pt; color: #555; }
"""


def build_html() -> str:
    team_rows = [(e(n), e(i), e(a)) for n, i, a in TEAM]
    test_rows = [(tid, cat, e(sc), e(ex), f"<code>{e(f.split('/')[-1].removesuffix('.kt'))}</code> &rsaquo; {e(n)}") for tid, cat, sc, ex, f, n in TESTS]

    parts = []
    parts.append(f"""<div class="cover"><h0>IT-SM</h0><div class="sub">Intelligent IT Service Management and Incident Resolution System</div>
<p>Object-Oriented Programming project report &middot; Semester 1, 2026&ndash;2027</p><p><b>Group {e(GROUP)}</b></p>
{table(["Member", "ID", "Responsibility"], team_rows)}
<p class="small">Source code: {REPO_URL}<br/>All data in the repository and in the demonstration is fictitious or local development data.</p></div>""")

    parts.append("""<h1 class="first">Contents</h1><ol class="toc">
<li>Problem analysis</li><li>Requirements specification</li><li>System architecture</li><li>UML diagrams (use case, class, state)</li>
<li>Object-oriented design and design patterns</li><li>Technology stack</li><li>Technology selection report</li><li>Database design</li>
<li>Exception handling and security</li><li>Testing (30 documented test cases)</li><li>Intelligent features and innovation</li>
<li>Demonstration workflows and team responsibilities</li><li>Deviations from the tech design, limitations and future work</li></ol>
<div class="note"><b>How to read the evidence.</b> Class and package names (for example <code>assignment/Strategies.kt</code>) refer to <code>backend/src/main/kotlin/com/itsm/</code> in the repository;
test names refer to <code>backend/src/test/kotlin/com/itsm/</code>. The diagrams are generated from sources in <code>docs/report/diagrams/</code> (<code>diagrams.py</code>), and this report is built by <code>docs/report/build_report.py</code>.
The report builder fails if a cited test no longer exists.</div>""")

    # 1 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>1. Problem analysis</h1>
<h2>1.1 The problem</h2>
<p>A campus (or any organisation) depends on IT services: network, identity, databases, email, business applications. When one fails, people report it by email, phone or chat; reports get lost, are assigned by guesswork,
nobody sees which promises (response and resolution times) are about to be broken, and there is no reliable record of who did what. The consequence is slow recovery, unfair workloads, repeated failures and no evidence for improvement.</p>
<p>IT-SM is a web application that manages the whole life of an IT incident: <i>report &rarr; classify &rarr; prioritise &rarr; assign &rarr; investigate &rarr; resolve &rarr; verify &rarr; close</i>, with SLA tracking, escalation, notifications, an audit trail and analytics.</p>
<h2>1.2 Stakeholders</h2>
""" + table(["Stakeholder", "Interest"], [
        ("Employees (reporters)", "Report a problem in plain words, see its progress, answer questions, confirm the fix, give feedback. Occasional users, often reporting under stress."),
        ("Technicians / support engineers", "Know what is assigned to them, work and document it, see their workload and SLA clocks, reuse past fixes. Team leads additionally receive escalations."),
        ("Administrators / IT managers", "Maintain users, services, categories, SLA policies and assignment strategy; assign fairly; watch SLA violations, workloads and trends; audit what happened."),
        ("The organisation / maintenance division", "Service levels met, accountability (audit), evidence for planning (analytics).")]) + """
<h2>1.3 Existing challenges</h2>
<ul><li><b>Unstructured intake</b>: free-text reports without category, impact or urgency, so priority is argued, not computed.</li>
<li><b>Unfair and slow assignment</b>: assigning by memory ignores skills and current workload.</li>
<li><b>Invisible deadlines</b>: SLA breaches are noticed after the fact, not predicted or escalated.</li>
<li><b>No history</b>: who changed what and when is not recorded; reopened problems lose context.</li>
<li><b>Duplicates and repeated work</b>: many people report the same outage; past fixes are not reused.</li>
<li><b>Weak information flow</b>: reporters are not told what is happening; technicians are not told when something is urgent.</li></ul>
<h2>1.4 Proposed solution</h2>
<ul><li>A guided report form (and an optional AI chat or voice intake) that asks <i>how many people are affected</i> and <i>can you still work</i>; the system derives the priority from those answers, the service's business criticality and its dependent services.</li>
<li>A strict lifecycle (9 states, 11 transitions) that refuses illegal moves, with permissions per role.</li>
<li>Four interchangeable assignment strategies plus a scored, explained technician recommendation, with a hard workload limit enforced under concurrency.</li>
<li>SLA clocks with warnings, breach records, pauses while waiting for the reporter, and a prediction of which incidents will miss their deadline.</li>
<li>Automatic escalation (team lead, next manager, administrators), and notifications through in-app, simulated email and simulated SMS channels, chosen by priority.</li>
<li>An append-only audit trail and an investigation timeline for every incident; analytics and per-role dashboards.</li></ul>
<h2>1.5 Assumptions</h2>
<ul><li>SLA clocks run 24x7 (no business hours or holidays). Email and SMS are simulated (shown in the inbox as channels) because the project has no mail or SMS gateway.</li>
<li>Accounts are created by an administrator or by self-registration (enabled for development, disabled by default elsewhere because there is no email verification). The first administrator is created by a bootstrap command.</li>
<li>A team lead is an attribute of a technician profile, not a separate role. The reporting line (who reports to whom) defines the escalation chain.</li>
<li>Assignment is triggered by an administrator (one click; the configured strategy chooses, or the administrator names a technician). Fully automatic assignment on intake is a documented extension.</li>
<li>The AI features need a Google Gemini API key. Without one the system works fully; categorisation and chat are off and duplicates are found by text search. When a key is set, incident text is sent to Google; the report page and the documentation say so.</li>
<li>Thresholds such as the priority matrix adjustments (3 dependents, criticality 1 and 5), the recommender weights and the escalation percentages (150% and 200%) are our own tunable constants; each is in one place in the code and covered by tests.</li></ul>""")

    # 2 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>2. Requirements specification</h1>
<h2>2.1 User roles and permissions</h2>
""" + table(["Role", "Permissions (<code>common/security/Permission.kt</code>)", "Notes"], [
        ("EMPLOYEE", "INCIDENT_CREATE, INCIDENT_VIEW_OWN, INCIDENT_VERIFY_OWN", "Sees and acts on own incidents only."),
        ("TECHNICIAN", "INCIDENT_CREATE, INCIDENT_VIEW_OWN, INCIDENT_VIEW_ASSIGNED, INCIDENT_WORK, INCIDENT_VERIFY_OWN", "Works incidents assigned to them; can also report and verify their own."),
        ("ADMIN", "All permissions: INCIDENT_VIEW_ALL, INCIDENT_ASSIGN, INCIDENT_CLOSE, USER_MANAGE, CATALOG_MANAGE, SLA_CONFIGURE, ASSIGNMENT_CONFIGURE, AUDIT_VIEW, ANALYTICS_VIEW and the above", "Team lead is a technician attribute, not a role.")]) + """
<h2>2.2 Functional requirements</h2>
""" + table(["ID", "Requirement", "Role"], [
        ("FR-01", "Register, sign in, sign out; passwords are stored hashed; sessions expire.", "All"),
        ("FR-02", "Report an incident (title, description, category, service, impact, urgency) through a form, a chat or by voice; edit it until it is assigned.", "Employee"),
        ("FR-03", "View my incidents, filter and search them, track status in plain language, see the resolution.", "Employee"),
        ("FR-04", "Answer a technician's request for information; confirm that the fix works or reopen the incident; give a rating (1 to 5) and comment once.", "Employee"),
        ("FR-05", "See assigned incidents, acknowledge, start work, add internal or shareable notes, request information (with a reason), resolve (with a resolution note).", "Technician"),
        ("FR-06", "See my workload against my limit, SLA attention, and past fixes for similar incidents.", "Technician"),
        ("FR-07", "Assign or reassign an incident: let the configured strategy choose or name a technician, with ranked advice and reasons; reassignment needs a reason.", "Admin"),
        ("FR-08", "Manage users and technician profiles (skills, workload limit, experience, availability, team lead, reporting line).", "Admin"),
        ("FR-09", "Manage incident categories (extensible, with keywords) and IT services (criticality, owner team, dependencies).", "Admin"),
        ("FR-10", "Configure SLA policies (global and per service and priority), the assignment strategy (global and per category) and escalation thresholds.", "Admin"),
        ("FR-11", "Search and filter incidents by id/text, state, priority, category, service, technician, reported-date range, SLA status and predicted miss; sort by urgency or deadline.", "All (scoped)"),
        ("FR-12", "Change an open incident's priority with a reason; accept or dismiss a system-suggested priority change; close a verified incident.", "Admin"),
        ("FR-13", "Dashboards and reports: incident overview, SLA compliance, response and resolution times, escalations, daily volumes, technician workload and ratings, incidents by service.", "Admin (and role dashboards)"),
        ("FR-14", "View the audit trail (all changes, who, when, previous and new state), filterable by incident; the incident page shows its own history.", "Admin, staff"),
        ("FR-15", "Derive the priority automatically; track response and resolution SLA; warn and record breaches; predict likely breaches.", "System"),
        ("FR-16", "Escalate overdue or unassigned incidents; notify the right people through in-app, email and SMS channels; push notifications live to the browser.", "System")]) + """
<h2>2.3 Non-functional requirements</h2>
""" + table(["Area", "Requirement and how it is met"], [
        ("Security", "Authentication, role-based and object-level authorisation, password hashing, validated input, CSRF protection, login throttling, no default secrets (section 9)."),
        ("Integrity", "The database re-enforces the lifecycle (CHECK constraints), audit and history tables are append-only (triggers), assignments cannot exceed capacity under concurrency (locks)."),
        ("Reliability", "Domain events commit or roll back with the change; monitors are safe to run on several instances (advisory locks); external channels never undo a business change; liveness and readiness probes."),
        ("Performance", "Paged lists (page size 1 to 100) with indexed filters and a full-text index; no per-row queries on list pages (query-count tests). No formal load test was performed."),
        ("Usability and accessibility", "A different interface per role, plain-language statuses, responsive layout, keyboard support, automated WCAG 2.2 AA checks (axe) in the browser tests."),
        ("Extensibility", "New assignment strategies, notification channels and categories are added without changing existing code (sections 4 and 5)."),
        ("Observability", "Prometheus metrics (HTTP, JVM, pool, business gauges such as open P1 and breached SLAs) on a separate management port, with a provisioned Grafana dashboard."),
        ("Portability", "Docker images, Docker Compose, Kubernetes manifests and a Helm chart; CI builds and scans images.")]) + """
<h2>2.4 Business rules</h2>
""" + table(["ID", "Rule"], [
        ("BR-01", "An incident id is <code>INC</code> plus a database sequence starting at 1001."),
        ("BR-02", "The reporter never chooses the priority: it is derived from impact, urgency, service criticality and dependent services (section 4.2)."),
        ("BR-03", "Only the transitions in the table are legal; an illegal one is refused with 409 even for an administrator; permissions are checked after legality."),
        ("BR-04", "A reason is required to reassign, request information, answer, resolve and reopen."),
        ("BR-05", "A technician can be assigned only if available and strictly below their workload limit (their own limit, otherwise the default); ASSIGNED, ACKNOWLEDGED, IN_PROGRESS and PENDING_INFO count towards the workload."),
        ("BR-06", "Only the reporter can verify, reopen and rate; feedback is possible once, after VERIFIED or CLOSED, rating 1 to 5. Only an administrator closes."),
        ("BR-07", "SLA clocks start when the incident is reported; the response clock stops at the first acknowledgement; the resolution clock pauses while waiting for the reporter; a warning is raised at 80% of a target; a breach is recorded once and never removed."),
        ("BR-08", "Escalation: a breach goes to the team lead; 150% of the resolution target goes to the next manager; 200% goes to the administrators; an incident nobody has picked up, or whose automatic assignment found nobody, goes to the administrators."),
        ("BR-09", "Internal notes are hidden from the reporter; a person who may not see an incident is told it does not exist."),
        ("BR-10", "Audit records, investigation entries, feedback and escalation records can be inserted but never updated or deleted.")]))

    # 3 ------------------------------------------------------------------------------------------------------------
    parts.append("""<div class="wide"><h1>3. System architecture</h1>""" + svg("architecture") + """<div class="cap">Figure 1: architecture. Solid arrows are requests, red dashed arrows are in-process domain events, dotted lines show every module depending only on <code>common</code>.</div></div>
<h2>3.1 Components and interactions</h2>
<p><b>Style: layered modular monolith.</b> One Spring Boot application, organised in modules owned by four people (P1 to P4). Inside a module the code is layered: controller (HTTP and validation), service (use case and transaction), domain policy (pure rules, no database), store/repository (persistence).
The three architecture rules that keep the modules independent:</p>
<ol><li><b>Contracts, not classes.</b> A module calls another only through an interface in <code>common/spi</code> (for example <code>UserDirectory</code>, <code>CategoryCatalog</code>, <code>ServiceCatalog</code>, <code>SlaPolicyProvider</code>, <code>PriorityPolicy</code>, <code>AssignmentStrategyCatalog</code>), implemented by exactly one module. Modules of the same owner may use each other; this is a convention reviewed in code review, not enforced by tooling.</li>
<li><b>Events for reactions.</b> When something happens (classified, state changed, assigned, assignment failed, priority changed) the owner publishes a domain event; SLA, escalation, notification and audit react to it. Audit rows are written <i>before</i> commit (atomic with the change); SLA, escalation and notifications run <i>after</i> commit, so a rolled-back change notifies nobody.</li>
<li><b>The database is the last line of defence.</b> Lifecycle invariants are repeated as CHECK constraints, history tables are append-only by trigger, and capacity is protected by row and advisory locks.</li></ol>
<h2>3.2 What happens when an employee reports an incident</h2>
<ol><li>The browser posts the form (<code>POST /api/incidents</code>); the security chain authenticates the session and checks CSRF, then RBAC.</li>
<li>Intake validates the catalog (<code>CategoryCatalog.requireActive</code>, <code>ServiceCatalog.requireActive</code>) and asks P4's <code>PriorityPolicy</code> for the priority; it stores the incident as CLASSIFIED with its impact and urgency and publishes <code>IncidentClassified</code> and audit events in the same transaction.</li>
<li>After commit, the SLA module starts the clocks, the notification module tells the right people, and the escalation module watches for an unassigned incident.</li>
<li>An administrator opens the incident, sees the ranked technician recommendations with reasons, and assigns (strategy or named). The assignment takes a row lock on the incident and an advisory lock on the technician, re-checks capacity, moves the state to ASSIGNED and publishes <code>IncidentAssigned</code>.</li>
<li>The technician is notified (in-app, plus simulated email and SMS for high priority), works the incident, and each transition is audited, added to the timeline and pushed to the browser (server-sent events).</li></ol>
<h2>3.3 Deployment view</h2>
<p>Docker Compose runs PostgreSQL, the backend (port 8080, management port 8081 unpublished) and the frontend. The same images run on Kubernetes (Kind) through raw manifests or a Helm chart with HTTPS-only ingress, split liveness/readiness probes, NetworkPolicies (only the backend reaches PostgreSQL) and an optional in-cluster Prometheus and Grafana. GitHub Actions runs the backend tests, the frontend tests, a real-stack end-to-end suite against PostgreSQL 16, and builds and scans the images.</p>""")

    # 4 ------------------------------------------------------------------------------------------------------------
    parts.append("""<div class="wide"><h1>4. UML diagrams</h1><h2>4.1 Use-case diagram</h2>""" + svg("usecase") + """<div class="cap">Figure 2: use cases by actor. The Gemini API is an optional external actor for the AI-assisted report features.</div></div>
<div class="wide pb"><h2>4.2 Class diagrams</h2>
<p>Five class diagrams cover the core of the system: the lifecycle and security model, assignment, domain events, notification channels with the audit publisher, and the error model. They show classes, attributes, methods, interfaces (stereotype <i>interface</i>), inheritance (hollow triangle), realisation (dashed hollow triangle), composition (filled diamond), aggregation (hollow diamond), association and dependency (open arrows).</p>
""" + svg("class_lifecycle") + """<div class="cap">Figure 3: lifecycle, persistence ports and the role/permission model (<code>lifecycle/</code>, <code>common/security/</code>).</div></div>
<div class="wide pb">""" + svg("class_assignment") + """<div class="cap">Figure 4: assignment, a Strategy pattern (<code>assignment/</code>, <code>lifecycle/IncidentAssignmentService.kt</code>).</div></div>
<div class="wide pb">""" + svg("class_events") + """<div class="cap">Figure 5: domain events and their observers, the Observer pattern (<code>common/event/</code>, <code>sla/</code>, <code>escalation/</code>, <code>notification/</code>).</div></div>
<div class="wide">""" + svg("class_notifications_audit") + """<div class="cap">Figure 6: notification channels (polymorphism) and the audit publisher (<code>notification/</code>, <code>audit/</code>, <code>common/event/AuditableEvent.kt</code>).</div></div>
<div class="wide pb">""" + svg("class_errors") + """<div class="cap">Figure 7: the exception hierarchy and its translation to RFC 7807 responses (<code>common/error/</code>).</div></div>
<div class="wide pb"><h2>4.3 Incident lifecycle (state diagram)</h2>""" + svg("state") + """<div class="cap">Figure 8: the 9 states and 11 transitions of <code>lifecycle/IncidentTransition.kt</code>. CLOSED is terminal; every other state can reach it.</div></div>""")

    # 5 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>5. Object-oriented design and design patterns</h1>
<h2>5.1 Design patterns (at least two required: five are used)</h2>
""" + table(["Pattern", "Where", "Why it fits and what it buys"], [
        ("<b>Strategy</b>", "<code>assignment/AssignmentStrategy</code> with <code>LoadBasedStrategy</code>, <code>SkillBasedStrategy</code>, <code>RoundRobinStrategy</code>, <code>ManualStrategy</code>; chosen at run time by <code>AssignmentEngine</code> from the administrator's settings (global, or per category).",
         "The ranking rule varies; everything around it (eligibility, locking, audit) does not. A new strategy is one class implementing <code>rank</code>, registered by Spring; an unknown configured key falls back to LOAD_BASED and the audit says so. The strategy can be changed in the admin page without redeploying (the specification asks for exactly this)."),
        ("<b>Observer</b>", "<code>IncidentEvent</code> (sealed interface) published through Spring's <code>ApplicationEventPublisher</code>; observers: <code>AuditEventPersistenceListener</code> (before commit), <code>SlaEventListener</code>, <code>EscalationEventListener</code>, <code>NotificationMonitor</code> (after commit).",
         "The publisher (intake, lifecycle, assignment) knows nothing about SLA, escalation, notifications or audit. Adding a new reaction means adding a listener. Phase choice gives atomic audit and no phantom notifications."),
        ("<b>Repository (and Ports and Adapters)</b>", "<code>IncidentStore</code>, <code>InvestigationLog</code>, <code>FeedbackStore</code> interfaces with JPA/JDBC implementations; Spring Data repositories in P1; the SPI interfaces in <code>common/spi</code>.",
         "Business rules are testable without a database (the unit tests use in-memory fakes); persistence can change without touching the rules."),
        ("<b>State (table-driven)</b>", "<code>IncidentState</code> and <code>IncidentTransition</code> (enums with a legal-from set, target, required permission, audit action and reason rule) and the stateless <code>IncidentStateMachine</code>.",
         "The behaviour that differs per state is data, not scattered conditionals: one table defines what is legal, who may do it and what it is called in the audit trail, and a test proves every state can reach CLOSED. We chose this over one class per state because the transitions share all behaviour except the rule row; see section 13."),
        ("<b>Template-style polymorphism for channels</b>", "<code>NotificationChannel</code> with <code>InAppChannel</code>, <code>SimulatedEmailChannel</code>, <code>SimulatedSmsChannel</code>.",
         "Channels are interchangeable behind one interface (<code>deliver</code>); the in-app channel is transactional, external channels run after commit and their failures are swallowed. A real SMTP or SMS channel is one new class."),
        ("Static factory methods", "<code>AuditActor.of(user)</code>, <code>IncidentState.fromName</code>, <code>RolePermissions.of(role)</code>.", "Validated creation in one place instead of constructors scattered across callers.")]) + """
<h2>5.2 Object-oriented concepts</h2>
""" + table(["Concept", "Where and how it is demonstrated"], [
        ("Encapsulation", "Domain values are immutable Kotlin data classes with validating <code>init</code> blocks (<code>Candidate</code> rejects a negative workload; <code>Notification</code> requires a title, body and the in-app channel; <code>AuditableEvent</code> validates its action format). Collections are exposed read-only (<code>RolePermissions</code> returns unmodifiable sets). Persistence entities (<code>IncidentRow</code>) never leave their module. Business rules are in policies (<code>EligibilityPolicy</code>, <code>FeedbackPolicy</code>, <code>InvestigationPolicy</code>), not in controllers."),
        ("Abstraction", "Complex operations sit behind small contracts: <code>IncidentAssignmentService.assignAutomatically</code>, <code>PriorityPolicy.assess</code>, <code>AuditPublisher.publish</code>, <code>IncidentStore</code>, thirteen SPI interfaces in <code>common/spi/ProviderInterfaces.kt</code>."),
        ("Inheritance (only where it is an is-a)", "<code>ItsmException</code> is a <code>RuntimeException</code>; its eleven subclasses (<code>WorkloadExceeded</code>, <code>InvalidStateTransition</code>, <code>TechnicianUnavailable</code> ...) are all errors with a code; <code>ServiceNotFound</code> is a <code>ResourceNotFound</code>. Sealed interfaces model closed families (<code>IncidentEvent</code>, <code>AuditActor</code>). We deliberately did not model Employee, Technician and Administrator as subclasses of User: a role is data that an administrator can change, so it is an enum with a permission set (composition), see section 13."),
        ("Polymorphism", "Four interchangeable <code>AssignmentStrategy</code> implementations and three <code>NotificationChannel</code> implementations are called through their interface; the exception handler treats every <code>ItsmException</code> uniformly."),
        ("Interfaces", "Contracts between modules (SPIs), between rules and storage (stores), and for extension points (strategy, channel)."),
        ("Composition", "<code>AssignmentEngine</code> is composed of the registered strategies and a <code>TechnicianRecommender</code>; services are assembled by constructor injection from small collaborators; <code>RolePermissions</code> composes roles from permissions."),
        ("Association / aggregation", "A technician profile has skills (category and proficiency); a service depends on other services; an incident is associated with its reporter, assignee, category and service (by id across modules, to keep modules independent); a notification aggregates the channels it is sent through. See the class and ER diagrams."),
        ("Exception handling", "A typed hierarchy with stable error codes, translated once to RFC 7807 responses; business outcomes that are not errors (no eligible technician) are returned as values (<code>AssignmentOutcome</code>), not thrown. See section 9."),
        ("Collections and data structures", "<code>EnumMap</code> and unmodifiable sets for role permissions and transition tables; sets for allowed source states and excluded technicians; maps for workload counts and strategy lookup; lists ordered by deterministic comparators (ties end with the technician id) for reproducible assignment; a reverse dependency graph built in memory to find dependent services transitively.")]))

    parts.append("""<h2>5.3 The two algorithms the specification asks us to document</h2>
<h3>Priority (<code>priority/PriorityCalculator</code>, <code>PriorityPolicy</code> SPI)</h3>
<ol><li><b>Effective impact:</b> if three or more active services depend (transitively) on the incident's service, raise the reporter's impact by one level (at most HIGH). Rationale: an outage of a shared service spreads.</li>
<li><b>Base priority</b> from the ITIL impact &times; urgency matrix: HIGH/HIGH P1; HIGH/MEDIUM and MEDIUM/HIGH P2; HIGH/LOW, MEDIUM/MEDIUM and LOW/HIGH P3; the rest P4.</li>
<li><b>Criticality adjustment</b> (service business criticality 1 to 5): 5 makes it one level more urgent, 1 one level less urgent, never beyond P1 or P4.</li></ol>
<p>The function is pure and returns the priority <i>with plain-language reasons</i> (shown in the audit trail), so every priority can be explained. The reporter never types a priority.</p>
<h3>Technician recommendation (<code>assignment/TechnicianRecommender</code>)</h3>
<p>score = 100 &times; (w<sub>skill</sub>&middot;skill + w<sub>headroom</sub>&middot;headroom + w<sub>experience</sub>&middot;experience), where skill = proficiency in the incident's category / 5 (0.5 when there is no category), headroom = 1 &minus; active/limit, experience = min(years, 10)/10. Weights depend on priority: P1 and P2 use 0.45 / 0.20 / 0.35 (skill and experience matter most when it is urgent); P3 and P4 use 0.40 / 0.45 / 0.15 (spread routine work). Each recommendation carries reasons such as &ldquo;Skill 5 of 5 in this category&rdquo; and &ldquo;1 of 6 active incidents&rdquo;.
These weights are our own tunable constants.</p>""")

    # 6 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>6. Technology stack</h1>""" + table(["Layer", "Technology", "Use"], [
        ("Frontend", "Next.js 14 (App Router), React 18, TypeScript, Tailwind CSS 3.4, shadcn/ui (Radix)", "Role-specific web UI, PWA (installable, service worker), server-sent events for live notifications, browser speech recognition for voice input"),
        ("Backend", "Kotlin 1.9, Spring Boot 3.3 (Web MVC, Security, Data JPA, Validation, Actuator), Gradle 8.8, JDK 17", "REST API, business rules, scheduling, event publication"),
        ("Persistence", "PostgreSQL 16, Hibernate/JPA, Spring JDBC, Flyway (V1 to V46)", "Single data store; migrations in per-owner version blocks; full-text search; advisory locks"),
        ("Security", "Spring Security, JWT (jjwt 0.12.5), HttpOnly session cookie with CSRF header, BCrypt", "Authentication, authorisation, session handling"),
        ("AI (optional)", "Google Gemini API", "Incident categorisation, duplicate hints and chatbot intake, only when a key is configured"),
        ("Observability", "Micrometer, Prometheus, Grafana", "HTTP, JVM and business metrics on a separate port; provisioned dashboard"),
        ("Packaging and delivery", "Docker (multi-stage builds) and Compose, Kubernetes (Kind), Helm 3, GitHub Actions, Trivy", "Local stack, cluster deployment, CI with real-stack end-to-end tests, image scanning"),
        ("Testing", "JUnit 5, Mockito, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL 16), Playwright, axe-core", "Unit, integration, concurrency, security and browser accessibility tests")]) + """
<p class="small">Redis and RabbitMQ were planned in the tech design, evaluated, and removed because no feature needed them (see sections 7 and 13).</p>""")

    # 7 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>7. Technology selection report</h1>
<p><b>1. Why this programming language (Kotlin)?</b> It runs on the JVM, so we keep Java's mature ecosystem (Spring, JPA, JDBC) while getting data classes, sealed interfaces, null safety, default arguments and expression bodies. Immutable data classes make the domain objects small and safe; sealed interfaces make the event and actor families exhaustive; enum classes with properties let us express the lifecycle as a table. The result is considerably less boilerplate than Java for the same object model. TypeScript on the frontend gives the same static checking for the API contract.</p>
<p><b>2. Why this frontend (Next.js, React, TypeScript, Tailwind, shadcn/ui)?</b> React's component model fits a UI that differs per role but shares pieces (status badges, timeline, dialogs). Next.js gives routing, a production build and a server we can ship in one container. Tailwind with design tokens let four people produce a consistent interface quickly; shadcn/ui (built on Radix) gives accessible dialogs, menus and tabs, which matters for the accessibility requirement. TypeScript catches contract mismatches between the UI and the API at build time.</p>
<p><b>3. Why this backend (Spring Boot 3)?</b> It is the standard for enterprise Java/Kotlin, and it gives us exactly what the specification lists as minimum requirements out of the box: Spring Security (authentication, role-based authorisation, method security), Bean Validation, transaction management, scheduling, an event publisher (our Observer) and Actuator/Micrometer (observability). Dependency injection makes the Strategy and Repository patterns natural, and Testcontainers-based tests run against the real database.</p>
<p><b>4. Why this database (PostgreSQL 16)?</b> The data is relational (users, technicians, skills, services and their dependencies, incidents, SLA policies) and the rules are about integrity, so we want foreign keys, CHECK constraints and triggers. PostgreSQL adds what a document store would not: transactional DDL migrations, <code>SELECT ... FOR UPDATE</code> and advisory locks (capacity under concurrency), full-text search with a GIN index (duplicate hints and search), <code>jsonb</code> (audit metadata) and array columns. One database kept operations simple.</p>
<p><b>5. Why this architecture?</b> A modular monolith with clear module contracts. The team is four people on a semester timeline: microservices would add network failure, distributed transactions and deployment overhead we do not need, while a layered monolith without module boundaries would let four people entangle each other's code. Our modules own their tables and talk through interfaces and events, so the monolith could be split along the same lines later. The presentation, business and data layers of the layered architecture are visible inside each module.</p>
<p><b>6. What alternatives did we consider?</b> Node.js/Express or NestJS with MongoDB (rejected: weaker integrity guarantees for a rules-heavy domain); Django with PostgreSQL (rejected: team strength is the JVM and we wanted compile-time checks); Angular or Vue for the UI (React has the larger component ecosystem for accessible primitives); GraphQL instead of REST (REST with problem+json errors was simpler to secure and test); microservices (see above); Redis for caching and as the SLA queue, and RabbitMQ for events (planned, then removed: events run in-process and PostgreSQL advisory locks coordinate the schedulers, so neither was needed); Kafka (far beyond the scale); a mobile app (the PWA covers installation and notifications).</p>
<p><b>7. Advantages and limitations of the stack.</b></p>
<ul><li><i>Advantages:</i> strong typing end to end; one transactional store; the database enforces the rules even if the code has a bug; excellent test tooling (real PostgreSQL in tests); one deployable unit; mature security framework.</li>
<li><i>Limitations:</i> in-process events are lost if the process crashes between commit and listener (the SLA, escalation and notification monitors also sweep periodically as a safety net); rate limits and the live notification stream are per instance, so several replicas would need a shared limiter and a broker for fan-out; JVM start-up is slower than Node; the optional AI feature depends on a third party and sends incident text to it (disclosed to users).</li></ul>
<p><b>8. How does the technology support object-oriented design?</b> Kotlin and Spring are object-oriented by construction: interfaces and polymorphism for strategies and channels, sealed hierarchies for events and errors, constructor-injected composition, immutable value objects, enums with behaviour for the lifecycle, and the repository abstraction over JPA. Spring's dependency injection is what lets modules depend on interfaces whose single implementation lives elsewhere.</p>
<p><b>9. How would the system evolve with many more users?</b> (a) Run several stateless backend replicas behind the ingress (sessions are cookie-based and verified from the database, so no sticky sessions are needed); (b) introduce a shared store for login throttling and a message broker for the live notification stream and for the domain events (the event classes and listener boundaries are already the contract: replace the in-process publisher by an outbox plus a broker); (c) add PostgreSQL read replicas for dashboards and analytics, partition the append-only audit and investigation tables by month, and move analytics to a materialised or pre-aggregated view; (d) cache the catalogs (categories, services, SLA policies), which change rarely; (e) page everything with keyset pagination instead of offsets; (f) split out the modules that scale differently (notifications, analytics) along the existing module boundaries. The Kubernetes manifests, probes and Prometheus metrics already support horizontal scaling and capacity monitoring.</p>""")

    # 8 ------------------------------------------------------------------------------------------------------------
    parts.append("""<div class="wide er"><h1>8. Database design</h1><h2>8.1 ER diagram</h2>""" + svg("er") + """<div class="cap">Figure 9: the core of the schema (21 tables in total; this figure shows 16 of them and their key columns). Crow's foot marks the &ldquo;many&rdquo; side.</div></div>
<h2>8.2 Schema overview</h2>
<p>PostgreSQL 16, created by Flyway migrations V1 to V46 in per-owner version blocks (P1: V1, V10 to V16; P2: V20 to V23; P3: V30 to V32; P4: V40 to V46). Every table has a primary key and relationships are foreign keys.</p>
""" + table(["Table", "Purpose", "Key relationships and rules"], [
        ("users", "Accounts: email (unique), BCrypt password hash, role, active flag, token version", "Role is one of EMPLOYEE, TECHNICIAN, ADMIN (CHECK); a trigger stops a role change that would orphan a technician profile."),
        ("technician_profiles, technician_skills", "Technician attributes (workload limit, experience, availability, team lead, reporting line) and skills (category, proficiency 1 to 5)", "Profile PK = user id; <code>reports_to</code> is a self reference that must point to an active team lead (composite FK); skill PK = (user, category)."),
        ("incident_categories, incident_category_keywords", "Extensible categories and the keywords used for suggestions", "Unique code; keyword rows mirror the category's active flag through a composite FK and trigger."),
        ("it_services, service_dependencies", "IT services with business criticality 1 to 5 and the dependency graph", "A trigger rejects dependency cycles; an active service cannot depend on an inactive one."),
        ("sla_policies", "Response and resolution targets per priority, global or per service", "Unique per (service, priority); a global row has no service."),
        ("incidents", "The incident and its lifecycle timestamps", "FKs to users (reporter, assignee), categories, services. CHECKs: state is one of nine; an assignee exists exactly when the state is beyond CLASSIFIED; resolved_at is set exactly in RESOLVED/VERIFIED/CLOSED; closed_at exactly when CLOSED; classified incidents have category, service and priority. Version column for optimistic locking."),
        ("incident_sla, incident_sla_risk", "One SLA record per incident (targets, deadlines, pauses, status) and the append-only predictions of a likely miss", "FK to incidents and the policy used; the record keeps a copy of the targets so a later policy change does not rewrite history."),
        ("investigation_entries", "Per-incident timeline (notes, findings, questions, answers, resolution)", "Append-only (trigger); visibility INTERNAL or REPORTER_VISIBLE decides who reads it."),
        ("incident_feedback", "One rating and comment per incident", "PK is the incident id, so the database also enforces &ldquo;once&rdquo;; append-only; rating CHECK 1 to 5."),
        ("incident_escalations, incident_priority_suggestions", "Escalation history (trigger, tier, targets) and system-suggested priority changes", "Escalations are append-only."),
        ("notifications, notification_watch", "The in-app inbox (type, priority, channels, read time) and bookkeeping for change detection", "Only <code>read_at</code> may change after insert (trigger)."),
        ("assignment_settings, assignment_category_overrides", "The active assignment strategy, the default workload limit and per-category overrides", "Single settings row; a trigger forbids deletion."),
        ("escalation_settings", "Unassigned wait and the 150% and 200% thresholds", "Single row, versioned."),
        ("audit_events", "The audit trail: timestamp, actor (type, id, role), action, entity, incident id, previous and new state, description, metadata", "Append-only by trigger (no update, delete or truncate); no foreign key to incidents so history survives; written in the same transaction as the change.")]) + """
<h2>8.3 Important relationships</h2>
<ul><li>A user reports many incidents and is assigned many; a technician profile belongs to exactly one TECHNICIAN user and may report to a team-lead profile (the escalation chain).</li>
<li>A technician has many skills; a category is the skill dimension and the key for per-category assignment overrides.</li>
<li>A service depends on many services (graph) and has a business criticality and SLA policies; an incident is about one service and one category.</li>
<li>An incident has one SLA record, many timeline entries, at most one feedback, and many escalations, notifications and audit events.</li></ul>
<h2>8.4 Sample data</h2>
<p>Loaded by <code>scripts/seed_demo.py</code> through the public API (password for every demo account: <code>Demo-Pass-123</code>; the demonstration administrator is created by the bootstrap command). All values are fictitious.</p>
<h3>users</h3>""" + table(["id", "name", "email", "role"], [
        ("1", "Administrator", "admin@demo.itsm", "ADMIN"), ("2", "Alice Employee", "alice.employee@demo.itsm", "EMPLOYEE"), ("3", "Bob Employee", "bob.employee@demo.itsm", "EMPLOYEE"),
        ("4", "Carol Employee", "carol.employee@demo.itsm", "EMPLOYEE"), ("5", "Tara Lead", "tara.lead@demo.itsm", "TECHNICIAN"), ("6", "Dev Technician", "dev.tech@demo.itsm", "TECHNICIAN"),
        ("7", "Sam Technician", "sam.tech@demo.itsm", "TECHNICIAN")]) + """
<h3>technician_profiles and technician_skills</h3>""" + table(["technician", "max workload", "experience (years)", "team lead", "reports to", "skills (proficiency)"], [
        ("Tara Lead", "8", "12", "yes", "&ndash;", "Network 5, Cloud 4, Security 4"), ("Dev Technician", "6", "5", "no", "Tara Lead", "Database 5, Application 4, Storage 3"),
        ("Sam Technician", "6", "3", "no", "Tara Lead", "Hardware 5, Software 4, ...")]) + """
<h3>it_services and service_dependencies</h3>""" + table(["id", "service", "criticality", "owner team", "depends on"], [
        ("1", "Network Core", "5", "Infrastructure", "&ndash;"), ("2", "Identity Provider", "5", "Security", "Network Core"), ("3", "Primary Database", "5", "Platform", "Network Core"),
        ("4", "Email", "3", "Workplace", "Identity Provider, Network Core"), ("5", "HR Portal", "3", "Business Apps", "Identity Provider, Primary Database"), ("6", "Print Service", "1", "Workplace", "Network Core")]) + """
<h3>sla_policies (minutes)</h3>""" + table(["scope", "priority", "response", "resolution"], [
        ("global", "P1", "5", "30"), ("global", "P2", "15", "120"), ("global", "P3", "60", "480"), ("global", "P4", "240", "1440"), ("Network Core", "P1", "10", "120"), ("Network Core", "P2", "30", "240")]) + """
<h3>incidents (as loaded)</h3>
<p class="small">Worked example: INC1002 (MEDIUM impact, HIGH urgency) is P2 in the matrix; Identity Provider has criticality 5, so it is raised one level to P1.</p>""" + table(["id", "title", "reporter", "category / service", "impact / urgency", "priority", "state, assignee"], [
        ("INC1001", "VPN drops every few minutes", "Alice", "Network / Network Core", "HIGH / HIGH", "P1", "IN_PROGRESS, Tara"),
        ("INC1002", "Cannot sign in to the HR portal", "Bob", "Authentication / Identity Provider", "MEDIUM / HIGH", "P1", "ASSIGNED, Dev"),
        ("INC1003", "Report query times out", "Carol", "Database / Primary Database", "MEDIUM / MEDIUM", "P2", "ASSIGNED, Sam"),
        ("INC1004", "Printer on floor 3 jams constantly", "Alice", "Hardware / Print Service", "LOW / LOW", "P4", "CLASSIFIED (awaiting assignment)"),
        ("INC1005", "Mailbox is full but I deleted everything", "Bob", "Software / Email", "LOW / MEDIUM", "P4", "CLASSIFIED (awaiting assignment)")]) + """
""")

    # 9 ------------------------------------------------------------------------------------------------------------
    parts.append("""<h1>9. Exception handling and security</h1>
<h2>9.1 Exception handling</h2>
<p>All business errors extend <code>ItsmException</code> (a code plus a message and optional field errors). One <code>@RestControllerAdvice</code> translates them, and every framework error (validation, unreadable JSON, wrong method or media type, database errors), into an RFC 7807 <code>application/problem+json</code> body with a stable <code>code</code>, a safe <code>detail</code> (never a stack trace or SQL) and a trace id. The situations the specification lists:</p>
""" + table(["Situation", "Class / mechanism", "Code and HTTP status"], [
        ("Invalid login", "<code>AuthenticationFailed</code>; unknown email, wrong password and disabled account are indistinguishable", "INVALID_CREDENTIALS, 401 (RATE_LIMITED 429 after repeated failures)"),
        ("Unauthorized operation", "<code>UnauthorizedOperation</code>, method security", "UNAUTHENTICATED 401 / FORBIDDEN 403"),
        ("Invalid incident", "<code>ResourceNotFound</code> (also for incidents the caller may not see), <code>InvalidInput</code>", "RESOURCE_NOT_FOUND 404 / VALIDATION_FAILED 400"),
        ("Invalid state transition", "<code>InvalidStateTransition</code>", "INVALID_STATE_TRANSITION 409"),
        ("Technician unavailable", "<code>TechnicianUnavailable</code>", "TECHNICIAN_UNAVAILABLE 409"),
        ("Technician workload exceeded", "<code>WorkloadExceeded</code> (re-checked under the lock)", "WORKLOAD_EXCEEDED 409"),
        ("Service not found", "<code>ServiceNotFound</code>", "SERVICE_NOT_FOUND 404"),
        ("Missing SLA configuration", "<code>MissingSlaConfiguration</code>; tracking records <i>no policy</i>, reports it once and starts when a policy exists", "MISSING_SLA_CONFIG 422"),
        ("Duplicate incident", "Possible duplicates are <i>suggested</i> to the reporter (text search, or AI when a key is set) and recorded only if the reporter confirms; <code>DuplicateResource</code> covers duplicate accounts and catalog entries", "DUPLICATE_RESOURCE 409"),
        ("Database failure", "<code>DatabaseErrorClassifier</code> separates conflicts and constraint violations from infrastructure failures; the transaction rolls back", "CONFLICT 409 / REFERENCE_CONSTRAINT 409 / SERVICE_UNAVAILABLE 503"),
        ("Network failure (AI service)", "The Gemini call has a typed failure path; the report form falls back to manual entry when the AI is unavailable", "A clear error to the caller; the incident can still be reported"),
        ("Invalid user input", "Bean Validation plus domain validation, with per-field messages", "VALIDATION_FAILED 400 with an <code>errors[]</code> list")]) + """
<h2>9.2 Security considerations</h2>
""" + table(["Concern", "What is implemented"], [
        ("Authentication", "Email and password; passwords hashed with BCrypt; browser sessions use an HttpOnly, SameSite=Strict cookie (the frontend holds no token), scripts may use a signed JWT as a Bearer token. The signing secret has no default and must be at least 32 random bytes or the backend refuses to start. Token versioning invalidates old tokens when a user is disabled or changed; the role is read from the database, not trusted from the token."),
        ("Authorisation", "Two layers: URL and method-level role/permission checks, then object-level checks (an employee sees only their own incidents, a technician those assigned to them, an administrator all). An incident you may not see is reported as not found, so existence is not leaked. Administrative endpoints are gated per permission."),
        ("Password protection", "BCrypt hashes only; secrets are never logged or returned; login failures are generic; login attempts are throttled per address and per email with a Retry-After header, and the throttle events carry hashed identifiers only."),
        ("Input validation", "Bean Validation on every request body, domain validation in the policies, and CHECK constraints in the database as the last line. SQL is parameterised; the few dynamic fragments only interpolate enum names or whitelisted sort columns, and text search escapes LIKE wildcards."),
        ("Secure API access", "CSRF protection for cookie sessions (a required custom header and an Origin check), CORS limited to the configured origin, security headers (no-sniff, frame denial, no-store), HTTPS-only ingress in Kubernetes, actuator on a separate unpublished management port."),
        ("Protection against unauthorised operations", "The lifecycle table and database CHECKs prevent illegal state changes even by an administrator or a buggy client; audit and history tables are append-only; the registration endpoint always creates an EMPLOYEE and is disabled by default outside development."),
        ("Accountability", "Every state change, assignment, priority change, configuration change and sign-in anomaly is recorded in the append-only audit trail in the same transaction, with the actor's role and the previous and new state."),
        ("Privacy of the AI feature", "When a Gemini key is set, the incident title and description, the chat messages, the category and service lists and the titles and opening lines of other open incidents (for the duplicate check) are sent to Google; the reporter's name and email are not. The report page states this next to the submit button and in the chat, and the security documentation lists exactly what is sent."),
        ("Known limitations (documented)", "Login throttling and the live notification stream are per instance; there are no refresh tokens, email verification or per-IP distributed limits. They are listed as future work.")]))

    # 10 -----------------------------------------------------------------------------------------------------------
    parts.append("""<h1>10. Testing</h1>
<h2>10.1 Strategy</h2>
<ul><li><b>Unit tests</b> for the pure domain rules (state machine, priority, assignment strategies and recommender, eligibility, SLA evaluation, escalation, feedback and timeline policies) with in-memory fakes, so no database is needed.</li>
<li><b>Integration tests</b> against a real PostgreSQL 16 (Testcontainers): migrations, constraints and triggers, stores, services, the HTTP API with security, concurrency (several threads assigning to one technician), transactional rollback, query counts.</li>
<li><b>Browser tests</b> (Playwright): a hermetic suite of 353 tests with an in-memory fake API for every role (flows, errors, access, accessibility with axe, keyboard focus, phone width), and a real-stack suite against the real backend and PostgreSQL. CI runs them on every pull request.</li>
<li>The backend has about 800 automated tests; the last full run had no failures and none skipped.</li></ul>
<p>Run them with <code>cd backend && ./gradlew test</code> (Docker required for Testcontainers) and <code>cd frontend && npm run test:e2e</code>.</p>
<h2>10.2 Documented test cases</h2>
<p>Thirty cases (the specification asks for at least 15) covering normal operations, boundary conditions, invalid inputs, exceptions, authorisation and business rules. Each is an executable test; the last column names it.</p>
""" + table(["ID", "Category", "Scenario", "Expected result", "Test (class > name)"], test_rows, "tests"))

    # 11 -----------------------------------------------------------------------------------------------------------
    parts.append("""<h1>11. Intelligent features and innovation</h1>
<h2>11.1 Intelligent / automated features (at least two required)</h2>
""" + table(["Feature (specification letter)", "Status", "How"], [
        ("D. Smart technician recommendation", "Implemented", "Scored recommendation from skill, free capacity and experience, weighted by priority, with plain-language reasons (section 5.3). Works without any AI."),
        ("C. Automatic escalation", "Implemented", "Breach to the team lead, 150% of target to the next manager, 200% to the administrators, unassigned incidents to the administrators; a monitor plus event-driven triggers; escalation history on the incident and an admin settings page."),
        ("E. SLA risk detection", "Implemented", "Predicts which running incidents will miss their resolution deadline from similar past incidents and the assignee's load; shown on the incident, as a filter on the list and as a dashboard queue; the admin is notified."),
        ("G. Suggested resolution", "Implemented", "For a technician, past fixes in the same category (same service first, newest first) with the recorded resolution text."),
        ("A. Duplicate incident detection", "Implemented (hint)", "When the reporter submits, similar open incidents are suggested. With a Gemini key the model flags duplicates; without one a full-text search keeps only incidents that share at least two significant words with the report. The reporter ticks the ones that are the same problem; only those are recorded."),
        ("F. Incident categorisation", "Implemented (needs a key)", "Gemini suggests a category with a confidence and a reason; the reporter accepts or changes it and the audit trail records whether the suggestion was accepted."),
        ("B. Incident correlation", "Not implemented", "Service dependencies are used for priority, but incidents are not grouped by shared component.")]) + """
<h2>11.2 Innovation challenges implemented (beyond the MVP)</h2>
<ul><li><b>AI-assisted classification and chatbot-based incident reporting, voice-based reporting</b> (browser speech recognition feeding the chat) &mdash; with a documented data-flow disclosure.</li>
<li><b>Predictive SLA violation detection</b> and <b>automated incident escalation</b>.</li>
<li><b>Service dependency graph</b>: a visual map in the admin UI; dependent services raise the priority of an outage.</li>
<li><b>Real-time notifications</b> (server-sent events), desktop notifications and an installable <b>PWA</b>; email and SMS by severity (simulated).</li>
<li><b>Analytics and visualisation</b> and a <b>real-time operational dashboard</b> per role.</li>
<li><b>Event-driven architecture</b> (domain events with before-commit and after-commit listeners).</li>
<li><b>Containerisation, Kubernetes/Helm deployment, CI/CD and observability</b> (Prometheus, Grafana, Trivy scan).</li>
<li><b>Incident similarity detection</b> and <b>AI-based resolution recommendation</b> (past-fix suggestions).</li></ul>""")

    # 12 -----------------------------------------------------------------------------------------------------------
    parts.append("""<h1>12. Demonstration workflows and team responsibilities</h1>
<h2>12.1 The three demonstrated workflows</h2>
<ol><li><b>Employee reports &rarr; system prioritises &rarr; technician assigned &rarr; notification generated.</b> Alice reports a VPN outage (answers the impact and urgency questions); the priority is derived and explained; the administrator opens the incident, sees the ranked recommendations and clicks Assign (the configured strategy picks the technician); the technician receives an in-app notification (and a simulated email and SMS for P1).</li>
<li><b>Technician investigates &rarr; SLA monitored &rarr; incident resolved &rarr; user notified.</b> The technician acknowledges, investigates, adds an internal note and asks for information (the resolution clock pauses), the reporter answers (it resumes), the technician resolves with a resolution note, the reporter confirms and rates, the administrator closes; the SLA card, the history card and the audit trail show every step.</li>
<li><b>Multiple incidents reported &rarr; related incidents identified &rarr; escalated.</b> A second report of the same outage shows the first as a possible duplicate (ticked by the reporter); an unattended P1 breaches its response target and escalates to the team lead, then the administrators, with notifications at each step.</li></ol>
<h2>12.2 Team responsibilities</h2>
""" + table(["Member", "Owns (modules, tables, screens)"], [
        (e(n) + " " + e(i), e(a)) for n, i, a in TEAM]) + """
<p>Every member understands the whole system: the contracts between the modules (SPIs and events) are documented in <code>docs/architecture/integration-guide.md</code> and each module has an architecture page.</p>""")

    # 13 -----------------------------------------------------------------------------------------------------------
    parts.append("""<h1>13. Deviations from the tech design, limitations and future work</h1>
<h2>13.1 Deviations from the tech design</h2>
""" + table(["Tech design", "What we built", "Why"], [
        ("Redis for caching and the SLA queue; RabbitMQ (optional)", "Neither. Events are in-process; the SLA and escalation monitors are Spring schedulers that coordinate through PostgreSQL advisory locks.", "No feature needed a cache or a broker; removing them removed two services, their secrets and their health concerns. The event and listener boundaries are the seam where a broker would be added (section 7, question 9)."),
        ("State pattern as a sealed class hierarchy", "A table-driven state machine (enums plus a stateless validator).", "The transitions differ only by their rule row (from, to, permission, reason, audit name); a table is shorter, can be checked exhaustively by a test, and is mirrored by database CHECKs. We describe it as the State pattern implemented as data."),
        ("A User base class extended by Employee, Technician and Admin", "A <code>Role</code> enum with a permission set, plus a technician profile for technician attributes.", "A role is data an administrator can change; a type hierarchy would force changing an object's class. Composition of permissions is simpler and safer. Inheritance is used where an is-a exists (the exception hierarchy)."),
        ("NotificationFactory", "Channels are chosen by a priority rule and selected through the common <code>NotificationChannel</code> interface.", "A factory added nothing: the set of channels is fixed per notification by priority."),
        ("Coroutines for SLA monitoring; JRE 21; Nginx for the frontend", "Spring <code>@Scheduled</code> monitors; JDK 17; the Next.js server runs in its container.", "Simpler and sufficient; the toolchain was pinned to what CI supports.")]) + """
<h2>13.2 Limitations (honest list)</h2>
<ul><li><b>Assignment is triggered by an administrator.</b> Intake does not assign by itself; an incident waits in CLASSIFIED until an administrator clicks Assign, and the escalation module alerts the administrators when it waits too long.</li>
<li>No incident correlation (specification feature B); duplicate detection is a hint, not a merge.</li>
<li>Email and SMS are simulated; SLA clocks are 24x7; there is no business-hours calendar.</li>
<li>The escalation recipient can be notified but, as a team lead who is not the assignee, cannot yet open the incident (a visibility rule limits access to reporter, assignee and administrators).</li>
<li>The AI features have been tested against mocked responses; they need a Google Gemini key and send incident text to Google when one is set.</li>
<li>No formal load or performance test; per-instance login throttling and live stream; no refresh tokens, email verification or password reset.</li></ul>
<h2>13.3 Future work</h2>
<ul><li>Automatic assignment on intake (a listener on <code>IncidentClassified</code> calling the existing <code>assignAutomatically</code>), with an administrator alert when nobody is eligible.</li>
<li>Incident correlation by shared service or dependency; a business-hours SLA calendar; real email/SMS gateways and Web Push.</li>
<li>A shared rate limiter and a message broker for the notification stream and for domain events (outbox pattern) when running several replicas; read replicas and partitioning for analytics.</li>
<li>Refresh tokens, email verification, password reset and single sign-on.</li></ul>
<h2>13.4 Source code and how to run it</h2>
<p>Repository: <b>""" + REPO_URL + """</b>. To run the full stack: <code>export JWT_SECRET="$(openssl rand -base64 48)"; cd infra; docker compose up --build</code>, then create the first administrator with the bootstrap service and optionally load the demo data with <code>scripts/seed_demo.py</code>; the README has the exact commands, the Kubernetes/Helm steps and the configuration reference.</p>""")

    return f"<!doctype html><html lang='en'><head><meta charset='utf-8'><title>IT-SM project report</title><style>{CSS}</style></head><body>{''.join(parts)}</body></html>"


def main() -> None:
    verify_tests()
    html_path = HERE / "report.html"
    html_path.write_text(build_html())
    pdf = HERE / "IT-SM-Project-Report.pdf"
    js = f"""
const {{ chromium }} = require('{REPO}/frontend/node_modules/playwright');
(async () => {{
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.goto('file://{html_path}');
  await p.waitForLoadState('load');
  await p.pdf({{ path: '{pdf}', format: 'A4', printBackground: true, preferCSSPageSize: true, displayHeaderFooter: true,
    headerTemplate: '<div></div>',
    footerTemplate: '<div style="width:100%;font-size:8px;color:#666;padding:0 14mm;display:flex;justify-content:space-between"><span>IT-SM project report, Group {GROUP}</span><span>Page <span class="pageNumber"></span> of <span class="totalPages"></span></span></div>' }});
  await b.close();
}})();
"""
    runner = HERE / ".render.js"
    runner.write_text(js)
    try:
        subprocess.run(["node", str(runner)], check=True)
    finally:
        runner.unlink(missing_ok=True)
        html_path.unlink(missing_ok=True)
    print("wrote", pdf)


if __name__ == "__main__":
    main()
