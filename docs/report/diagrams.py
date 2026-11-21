#!/usr/bin/env python3
"""Generates the diagrams of the project report into docs/report/diagrams/ (needs Graphviz `dot` on the PATH).

    python3 docs/report/diagrams.py

Every .dot file is kept next to its .svg so the diagrams can be edited and re-rendered. The use-case diagram is drawn
directly as SVG (Graphviz has no stick-figure actors). Class and ER diagrams reflect the code on `main` (see the
source paths in the report) and the Flyway schema (V1 to V46).
"""
import html
import pathlib
import subprocess

OUT = pathlib.Path(__file__).resolve().parent / "diagrams"
OUT.mkdir(exist_ok=True)

FONT = "Helvetica"


def render(name: str, dot_source: str) -> None:
    (OUT / f"{name}.dot").write_text(dot_source)
    svg = subprocess.run(["dot", "-Tsvg"], input=dot_source, capture_output=True, text=True, check=True).stdout
    (OUT / f"{name}.svg").write_text(svg)


def esc(s: str) -> str:
    return html.escape(s, quote=False)


def cls(name: str, attrs=(), methods=(), stereo: str | None = None, fill: str = "#ffffff") -> str:
    """A UML class box (three compartments) as a Graphviz HTML-like label."""
    rows = []
    title = f'<I>&laquo;{esc(stereo)}&raquo;</I><BR/><B>{esc(name)}</B>' if stereo else f"<B>{esc(name)}</B>"
    rows.append(f'<TR><TD BGCOLOR="{fill}" ALIGN="CENTER">{title}</TD></TR>')
    a = "".join(f'{esc(x)}<BR ALIGN="LEFT"/>' for x in attrs) or " "
    m = "".join(f'{esc(x)}<BR ALIGN="LEFT"/>' for x in methods) or " "
    rows.append(f'<TR><TD ALIGN="LEFT" BALIGN="LEFT">{a}</TD></TR>')
    rows.append(f'<TR><TD ALIGN="LEFT" BALIGN="LEFT">{m}</TD></TR>')
    key = name.replace(" ", "_").replace("<", "_").replace(">", "_")
    return f'  {key} [label=<<TABLE BORDER="1" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">{"".join(rows)}</TABLE>>];\n'


def key(name: str) -> str:
    return name.replace(" ", "_")


HEADER = f'''digraph G {{
  graph [rankdir=BT, fontname="{FONT}", nodesep=0.45, ranksep=0.65, splines=true, pad=0.2];
  node [shape=plain, fontname="{FONT}", fontsize=10];
  edge [fontname="{FONT}", fontsize=9];
'''

# UML arrow styles
INHERIT = 'arrowhead=empty, color="#333333"'
REALIZE = 'arrowhead=empty, style=dashed, color="#333333"'
COMPOSE = 'arrowhead=none, arrowtail=diamond, dir=back, color="#333333"'
AGGREGATE = 'arrowhead=none, arrowtail=odiamond, dir=back, color="#333333"'
ASSOC = 'arrowhead=vee, color="#333333"'
DEPEND = 'arrowhead=vee, style=dashed, color="#666666"'


def edge(a: str, b: str, style: str, label: str = "") -> str:
    lab = f', label="{label}"' if label else ""
    return f"  {key(a)} -> {key(b)} [{style}{lab}];\n"


# ---------------------------------------------------------------------------------------------------------------------
# 1. Architecture
# ---------------------------------------------------------------------------------------------------------------------
ARCHITECTURE = f'''digraph G {{
  graph [rankdir=TB, fontname="{FONT}", nodesep=0.4, ranksep=0.6, compound=true, pad=0.2, splines=true, newrank=true];
  node [shape=box, style="rounded,filled", fillcolor="#ffffff", fontname="{FONT}", fontsize=10, margin="0.14,0.08"];
  edge [fontname="{FONT}", fontsize=9, color="#444444"];

  subgraph cluster_clients {{ label="Clients"; style="rounded,filled"; fillcolor="#eef3fb"; color="#9db4d8";
    browser [label="Browser\\nNext.js 14, React 18, TypeScript\\nTailwind, shadcn/ui, PWA, SSE"];
    scripts [label="Scripts / API clients\\n(Bearer token)"];
  }}

  subgraph cluster_backend {{ label="Backend: Kotlin + Spring Boot 3 (modular monolith, one deployable)"; style="rounded,filled"; fillcolor="#f4f8ee"; color="#a8c28a";
    sec [label="Security filter chain: JWT or HttpOnly session cookie, CSRF, login throttle,\\nRBAC (@PreAuthorize), RFC 7807 error handler", fillcolor="#fff4d6"];
    p1 [label="P1: access and\\nadministration\\nauth, users, technicians,\\ncategories, IT services,\\nSLA policies, assignment\\nsettings, audit trail"];
    p2 [label="P2: incident intake\\ncreate / edit incident,\\ncatalog, Gemini analysis,\\nchatbot, duplicate hint,\\nIncidentAccessPolicy"];
    p3 [label="P3: lifecycle and\\nassignment\\nstate machine, 4 strategies\\n+ recommender,\\ninvestigation timeline,\\nfeedback"];
    p4 [label="P4: priority, SLA,\\nescalation, notifications,\\nanalytics\\nschedulers, predictive risk,\\nSSE, simulated email / SMS"];
    common [label="common: SPI interfaces, domain events, error model, security model, API conventions", fillcolor="#e9e9e9"];
    {{ rank=same; p1; p2; p3; p4; }}
    p1 -> p2 -> p3 -> p4 [style=invis];
  }}

  subgraph cluster_data {{ label="Data"; style="rounded,filled"; fillcolor="#fbeeee"; color="#d8a0a0";
    pg [label="PostgreSQL 16\\nFlyway V1..V46, CHECK constraints, append-only triggers,\\nadvisory locks, full-text index", shape=cylinder, fillcolor="#ffffff"];
  }}

  subgraph cluster_ext {{ label="External and operations"; style="rounded,filled"; fillcolor="#f1eefb"; color="#b6a4dc";
    gemini [label="Google Gemini API\\n(optional, only with a key)"];
    mon [label="Prometheus + Grafana\\n(management port 8081)"];
    deploy [label="Docker Compose, Kubernetes (Kind, Helm)\\nGitHub Actions CI, Trivy image scan"];
  }}

  browser -> sec [label=" REST / JSON, SSE"];
  scripts -> sec;
  sec -> p1; sec -> p2; sec -> p3; sec -> p4;
  p2 -> p3 [label="domain events", style=dashed, constraint=false, color="#b0402a", fontcolor="#b0402a"];
  p3 -> p4 [label="domain events", style=dashed, constraint=false, color="#b0402a", fontcolor="#b0402a"];
  p1 -> common [style=dotted, arrowhead=none]; p2 -> common [style=dotted, arrowhead=none];
  p3 -> common [style=dotted, arrowhead=none]; p4 -> common [style=dotted, arrowhead=none];
  common -> pg [label=" JPA, JDBC"];
  p2 -> gemini [label="HTTPS", constraint=false];
  mon -> sec [label="scrape", style=dashed, constraint=false];
  gemini -> mon -> deploy [style=invis];
}}
'''

# ---------------------------------------------------------------------------------------------------------------------
# 2. State diagram
# ---------------------------------------------------------------------------------------------------------------------
STATE = f'''digraph G {{
  graph [rankdir=LR, fontname="{FONT}", nodesep=0.5, ranksep=0.7, pad=0.2];
  node [shape=box, style="rounded,filled", fillcolor="#ffffff", fontname="{FONT}", fontsize=10];
  edge [fontname="{FONT}", fontsize=9, color="#333333"];
  start [shape=circle, label="", width=0.18, style=filled, fillcolor="#000000"];
  end [shape=doublecircle, label="", width=0.18, style=filled, fillcolor="#000000"];
  NEW; CLASSIFIED; ASSIGNED; ACKNOWLEDGED; IN_PROGRESS; PENDING_INFO; RESOLVED; VERIFIED;
  CLOSED [fillcolor="#e8e8e8"];
  start -> NEW [label="report"];
  NEW -> CLASSIFIED [label="CLASSIFY\\n(intake, automatic)"];
  CLASSIFIED -> ASSIGNED [label="ASSIGN (admin)"];
  ASSIGNED -> ACKNOWLEDGED [label="ACKNOWLEDGE\\n(technician)"];
  ACKNOWLEDGED -> IN_PROGRESS [label="START_WORK"];
  IN_PROGRESS -> PENDING_INFO [label="REQUEST_INFO\\n(reason)"];
  PENDING_INFO -> IN_PROGRESS [label="PROVIDE_INFO\\n(reporter)"];
  IN_PROGRESS -> RESOLVED [label="RESOLVE\\n(how it was fixed)"];
  RESOLVED -> VERIFIED [label="VERIFY (reporter)"];
  RESOLVED -> IN_PROGRESS [label="REOPEN (reporter,\\nreason)", constraint=false];
  VERIFIED -> CLOSED [label="CLOSE (admin)"];
  CLOSED -> end;
  ASSIGNED -> ASSIGNED [label="REASSIGN (admin, reason):\\nfrom ASSIGNED, ACKNOWLEDGED,\\nIN_PROGRESS or PENDING_INFO"];
}}
'''

# ---------------------------------------------------------------------------------------------------------------------
# 3. Class diagrams
# ---------------------------------------------------------------------------------------------------------------------


def class_lifecycle() -> str:
    s = HEADER
    s += cls("IncidentState", ["NEW, CLASSIFIED, ASSIGNED, ACKNOWLEDGED,", "IN_PROGRESS, PENDING_INFO, RESOLVED,", "VERIFIED, CLOSED",
                               "-countsTowardWorkload: Boolean", "-terminal: Boolean"], ["+fromName(name): IncidentState"], "enumeration", "#fff4d6")
    s += cls("IncidentTransition", ["CLASSIFY, ASSIGN, REASSIGN, ACKNOWLEDGE, START_WORK,", "REQUEST_INFO, PROVIDE_INFO, RESOLVE, VERIFY,", "REOPEN, CLOSE",
                                    "-from: Set<IncidentState>", "-to: IncidentState", "-permission: Permission", "-auditAction: String", "-requiresReason: Boolean"],
                 ["+allowsFrom(state): Boolean"], "enumeration", "#fff4d6")
    s += cls("IncidentStateMachine", [], ["+transitionsFrom(state): List<IncidentTransition>", "+availableTo(state, user): List<IncidentTransition>",
                                          "+canApply(transition, state): Boolean", "+apply(record, transition, reason, user): Result"], "object")
    s += cls("IncidentRecord", ["-id: String", "-state: IncidentState", "-reporterId: Long", "-assigneeId: Long?", "-categoryId: Long?", "-serviceId: Long?",
                                "-priority: Priority?", "-assignedAt / firstResponseAt / resolvedAt / closedAt: Instant?"], ["+copy(...): IncidentRecord  (immutable)"])
    s += cls("IncidentStore", [], ["+find(id): IncidentRecord?", "+lock(id): IncidentRecord?", "+save(record)", "+activeCounts(ids): Map<Long, Int>",
                                   "+lockTechnician(id)", "+list(query): IncidentPage", "+similarResolved(...): List<ResolvedIncident>"], "interface", "#e6f0ff")
    s += cls("JpaIncidentStore", ["-em: EntityManager", "-jdbc: JdbcTemplate"], ["(implements IncidentStore)"])
    s += cls("IncidentLifecycleService", ["-store: IncidentStore", "-log: InvestigationLog", "-feedbacks: FeedbackStore", "-audit: AuditPublisher", "-events: ApplicationEventPublisher"],
             ["+get(id, user)", "+list(...)", "+transition(id, transition, reason, user)", "+addNote(...)", "+timeline(id, user)", "+submitFeedback(id, rating, comment, user)"])
    s += cls("Permission", ["INCIDENT_CREATE, INCIDENT_VIEW_OWN, INCIDENT_VIEW_ASSIGNED,", "INCIDENT_VIEW_ALL, INCIDENT_ASSIGN, INCIDENT_WORK,", "INCIDENT_VERIFY_OWN, INCIDENT_CLOSE, USER_MANAGE, CATALOG_MANAGE,",
                             "SLA_CONFIGURE, ASSIGNMENT_CONFIGURE, AUDIT_VIEW, ANALYTICS_VIEW"], [], "enumeration", "#fff4d6")
    s += cls("Role", ["EMPLOYEE", "TECHNICIAN", "ADMIN"], ["+authority: String"], "enumeration", "#fff4d6")
    s += cls("RolePermissions", ["-grants: Map<Role, Set<Permission>>  (EnumMap, unmodifiable)"], ["+of(role): Set<Permission>"], "object")
    s += cls("InvestigationPolicy", [], ["+newManualEntry(...)", "+entryFor(incidentId, result, author, now)", "+canSee(user, entry): Boolean", "+visibleTo(user, entries)"], "object")
    s += cls("FeedbackPolicy", [], ["+submit(...): Feedback  (once, reporter only, rating 1..5)"], "object")
    s += edge("IncidentTransition", "IncidentState", ASSOC, "from / to")
    s += edge("IncidentTransition", "Permission", ASSOC, "requires")
    s += edge("IncidentStateMachine", "IncidentTransition", DEPEND, "reads table")
    s += edge("IncidentRecord", "IncidentState", ASSOC, "state")
    s += edge("JpaIncidentStore", "IncidentStore", REALIZE)
    s += edge("IncidentLifecycleService", "IncidentStore", AGGREGATE)
    s += edge("IncidentLifecycleService", "IncidentStateMachine", DEPEND)
    s += edge("IncidentLifecycleService", "InvestigationPolicy", DEPEND)
    s += edge("IncidentLifecycleService", "FeedbackPolicy", DEPEND)
    s += edge("RolePermissions", "Role", ASSOC, "keys")
    s += edge("RolePermissions", "Permission", AGGREGATE, "values")
    return s + "}\n"


def class_assignment() -> str:
    s = HEADER.replace("nodesep=0.45", "nodesep=0.18").replace("ranksep=0.65", "ranksep=0.5")
    s += cls("AssignmentStrategy", ["+key: String", "+autoAssigns: Boolean"], ["+rank(candidates, request, defaultMaxWorkload): List<Candidate>"], "interface", "#e6f0ff")
    s += cls("LoadBasedStrategy", [], ["+rank(...)  lowest active/max first"])
    s += cls("SkillBasedStrategy", [], ["+rank(...)  highest proficiency; drops the unskilled"])
    s += cls("RoundRobinStrategy", [], ["+rank(...)  longest since last assignment"])
    s += cls("ManualStrategy", [], ["+rank(...)  autoAssigns = false"])
    s += cls("AssignmentEngine", ["-byKey: Map<String, AssignmentStrategy>", "-recommender: TechnicianRecommender"],
             ["+decide(settings, request, candidates): AssignmentDecision", "+registeredKeys(): Set<String>"])
    s += cls("TechnicianRecommender", [], ["+weightsFor(priority): Weights", "+recommend(candidates, request, defaultMax, limit): List<Recommendation>"])
    s += cls("EligibilityPolicy", [], ["+effectiveMax(c, default): Int", "+hasCapacity(c, default): Boolean", "+eligible(...): List<Candidate>", "+requireCanTake(c, default)"], "object")
    s += cls("Candidate", ["+technician: TechnicianSummary", "+activeCount: Int", "+lastAssignedAt: Instant?"], ["require(activeCount >= 0)"])
    s += cls("AssignmentRequest", ["+categoryId: Long?", "+priority: Priority", "+excludeTechnicianIds: Set<Long>"])
    s += cls("AssignmentDecision", ["+outcome: AssignmentOutcome", "+strategyKey: String", "+chosen: TechnicianSummary?", "+recommendations: List<Recommendation>", "+fellBackFrom: String?"])
    s += cls("AssignmentOutcome", ["ASSIGNED", "MANUAL_REQUIRED", "NO_ELIGIBLE_", "TECHNICIAN"], [], "enumeration", "#fff4d6")
    s += cls("IncidentAssignmentService", ["-engine: AssignmentEngine", "-store: IncidentStore", "-directory: UserDirectory (SPI)", "-settings: AssignmentSettingsProvider (SPI)"],
             ["+assignAutomatically(id, reason, user)", "+assignTo(id, technicianId, reason, user)", "+recommendations(id, user)"])
    for c in ("LoadBasedStrategy", "SkillBasedStrategy", "RoundRobinStrategy", "ManualStrategy"):
        s += edge(c, "AssignmentStrategy", REALIZE)
    s += edge("AssignmentEngine", "AssignmentStrategy", AGGREGATE, "1..*")
    s += edge("AssignmentEngine", "TechnicianRecommender", COMPOSE)
    s += edge("AssignmentEngine", "EligibilityPolicy", DEPEND)
    s += edge("AssignmentEngine", "AssignmentDecision", DEPEND, "returns")
    s += edge("AssignmentDecision", "AssignmentOutcome", ASSOC)
    s += edge("AssignmentEngine", "Candidate", DEPEND)
    s += edge("AssignmentEngine", "AssignmentRequest", DEPEND)
    s += edge("IncidentAssignmentService", "AssignmentEngine", AGGREGATE)
    return s + "}\n"


def class_events() -> str:
    s = HEADER.replace("nodesep=0.45", "nodesep=0.2")
    s += cls("IncidentEvent", ["+incidentId: String", "+occurredAt: Instant"], [], "sealed interface", "#e6f0ff")
    for n, a in (("IncidentClassified", ["reporterId, serviceId, categoryId, priority"]), ("IncidentStateChanged", ["fromState, toState, transition, actorId"]),
                 ("IncidentAssigned", ["assigneeId, previousAssigneeId, actorId"]), ("IncidentAssignmentFailed", ["reason"]),
                 ("IncidentPriorityChanged", ["fromPriority, toPriority, actorId"])):
        s += cls(n, a)
        s += edge(n, "IncidentEvent", INHERIT)
    s += cls("ApplicationEventPublisher", [], ["+publishEvent(event)"], "Spring", "#eeeeee")
    s += cls("SlaEventListener", [], ["AFTER_COMMIT: starts and updates", "the SLA clocks"])
    s += cls("EscalationEventListener", [], ["AFTER_COMMIT: escalates a", "failed assignment"])
    s += cls("NotificationMonitor", [], ["AFTER_COMMIT: notifies;", "@Scheduled sweep as safety net"])
    s += edge("ApplicationEventPublisher", "IncidentEvent", DEPEND, "publishes")
    for l in ("SlaEventListener", "EscalationEventListener", "NotificationMonitor"):
        s += edge(l, "IncidentEvent", DEPEND, "observes")
    return s + "}\n"


def class_notifications_audit() -> str:
    s = HEADER
    s += cls("NotificationChannel", ["+key: ChannelKey", "+transactional: Boolean"], ["+deliver(notification)"], "interface", "#e6f0ff")
    s += cls("InAppChannel", [], ["+deliver(...)  stored, shown by the bell"])
    s += cls("SimulatedEmailChannel", [], ["+deliver(...)  P1 and P2 only"])
    s += cls("SimulatedSmsChannel", [], ["+deliver(...)  P1 only"])
    s += cls("Notification", ["+recipientId: Long", "+type: NotificationType", "+incidentId: String?", "+priority: Priority?", "+title, body: String", "+channels: Set<ChannelKey>"],
             ["init { in-app channel is mandatory }"])
    s += cls("NotificationMonitor", [], ["@TransactionalEventListener(AFTER_COMMIT)", "@Scheduled sweep (safety net)"])
    s += cls("AuditableEvent", ["+actor: AuditActor", "+action: String", "+entityType: String", "+incidentId: String?", "+previousState / newState: String?", "+description: String",
                                "+metadata: Map", "+occurredAt: Instant"], ["init { validates action and entityType format }"])
    s += cls("AuditPublisher", [], ["+publish(event: AuditableEvent)  (MANDATORY transaction)"], "interface", "#e6f0ff")
    s += cls("AuditEventPersistenceListener", [], ["@TransactionalEventListener(BEFORE_COMMIT)", "inserts the audit row in the caller's transaction"])
    for c in ("InAppChannel", "SimulatedEmailChannel", "SimulatedSmsChannel"):
        s += edge(c, "NotificationChannel", REALIZE)
    s += edge("NotificationMonitor", "NotificationChannel", AGGREGATE, "0..*")
    s += edge("NotificationMonitor", "Notification", DEPEND, "creates")
    s += edge("AuditPublisher", "AuditableEvent", DEPEND, "publishes")
    s += edge("AuditEventPersistenceListener", "AuditableEvent", DEPEND, "observes")
    return s + "}\n"


def class_errors() -> str:
    s = HEADER.replace("rankdir=BT", "rankdir=RL").replace("nodesep=0.45", "nodesep=0.12").replace("ranksep=0.65", "ranksep=0.9")
    s += cls("RuntimeException", [], [], None, "#eeeeee")
    s += cls("ItsmException", ["+code: ErrorCode", "+message: String", "+fieldErrors: List<FieldError>"], [], None, "#fff4d6")
    s += edge("ItsmException", "RuntimeException", INHERIT)
    subs = [("AuthenticationFailed", "401"), ("RateLimited", "429"), ("UnauthorizedOperation", "403"), ("ResourceNotFound", "404"), ("DuplicateResource", "409"),
            ("InvalidInput", "400"), ("InvalidStateTransition", "409"), ("MissingSlaConfiguration", "SLA has no policy"), ("TechnicianUnavailable", "409"),
            ("WorkloadExceeded", "409"), ("ExternalServiceFailure", "502")]
    for n, c in subs:
        s += cls(n, [f"HTTP {c}" if c[0].isdigit() else c])
        s += edge(n, "ItsmException", INHERIT)
    s += cls("ServiceNotFound", ["HTTP 404"])
    s += edge("ServiceNotFound", "ResourceNotFound", INHERIT)
    s += cls("ErrorCode", ["UNAUTHENTICATED, INVALID_CREDENTIALS, FORBIDDEN, RATE_LIMITED,", "RESOURCE_NOT_FOUND, DUPLICATE_RESOURCE, INVALID_INPUT, ...", "-status: Int", "-title: String"], [], "enumeration", "#fff4d6")
    s += cls("GlobalExceptionHandler", [], ["+handle(ItsmException): ProblemDetail (RFC 7807)", "+handle(validation / database / unexpected errors)"], "@RestControllerAdvice")
    s += edge("ItsmException", "ErrorCode", ASSOC)
    s += edge("GlobalExceptionHandler", "ItsmException", DEPEND, "translates")
    return s + "}\n"


# ---------------------------------------------------------------------------------------------------------------------
# 4. ER diagram (core of the schema; the full table list is in the report appendix)
# ---------------------------------------------------------------------------------------------------------------------
ER_TABLES = {
    "users": [("id", "bigint", "PK"), ("email", "varchar(255)", "UQ"), ("password", "varchar(255)", "BCrypt"), ("name", "varchar(255)", ""), ("role", "varchar(50)", "EMPLOYEE|TECHNICIAN|ADMIN"),
              ("active", "bool", ""), ("token_version", "int", "")],
    "technician_profiles": [("user_id", "bigint", "PK, FK users"), ("max_workload", "int", ""), ("experience_years", "int", ""), ("available", "bool", ""), ("is_team_lead", "bool", ""),
                            ("reports_to_user_id", "bigint", "FK self")],
    "technician_skills": [("user_id", "bigint", "PK, FK"), ("category_id", "bigint", "PK, FK"), ("proficiency", "int", "1..5")],
    "incident_categories": [("id", "bigint", "PK"), ("code", "varchar(40)", "UQ"), ("name", "varchar(100)", ""), ("active", "bool", "")],
    "it_services": [("id", "bigint", "PK"), ("name", "varchar(100)", ""), ("business_criticality", "smallint", "1..5"), ("owner_team", "varchar(100)", ""), ("active", "bool", "")],
    "service_dependencies": [("service_id", "bigint", "PK, FK"), ("depends_on_id", "bigint", "PK, FK")],
    "sla_policies": [("id", "bigint", "PK"), ("service_id", "bigint", "FK (null = global)"), ("priority", "varchar(2)", "P1..P4"), ("response_minutes", "int", ""), ("resolution_minutes", "int", ""),
                     ("warning_threshold_pct", "smallint", "")],
    "incidents": [("id", "varchar(30)", "PK  INC1001+"), ("title", "varchar(200)", ""), ("description", "text", ""), ("state", "varchar(20)", "9 states, CHECK"), ("reporter_id", "bigint", "FK users"),
                  ("assignee_id", "bigint", "FK users"), ("category_id", "bigint", "FK"), ("service_id", "bigint", "FK"), ("priority", "varchar(2)", "P1..P4"), ("impact / urgency", "varchar(10)", ""),
                  ("created_at, assigned_at, first_response_at,", "", ""), ("resolved_at, closed_at", "timestamptz", ""), ("version", "bigint", "optimistic lock")],
    "incident_sla": [("incident_id", "varchar(30)", "PK, FK"), ("policy_id", "bigint", "FK"), ("response_due_at / resolution_due_at", "timestamptz", ""), ("response_status / resolution_status", "varchar(10)", ""),
                     ("status", "varchar(10)", "worst of both"), ("paused_since, paused_seconds", "", "")],
    "investigation_entries": [("id", "bigint", "PK"), ("incident_id", "varchar(30)", "FK"), ("author_id", "bigint", "FK users"), ("entry_type", "varchar(20)", ""), ("visibility", "varchar(20)", "INTERNAL|REPORTER_VISIBLE"),
                              ("body", "text", ""), ("incident_state", "varchar(20)", "")],
    "incident_feedback": [("incident_id", "varchar(30)", "PK, FK  (one per incident)"), ("reporter_id", "bigint", "FK users"), ("rating", "int", "1..5"), ("comment", "text", "")],
    "incident_escalations": [("id", "bigint", "PK"), ("incident_id", "varchar(30)", "FK"), ("trigger", "varchar(30)", ""), ("tier", "varchar(20)", "TEAM_LEAD|NEXT_LEVEL|ADMINS"), ("target_user_ids", "bigint[]", "")],
    "notifications": [("id", "bigint", "PK"), ("recipient_id", "bigint", "FK users"), ("incident_id", "varchar(30)", "FK"), ("type", "varchar(30)", ""), ("priority", "varchar(2)", ""), ("channels", "text[]", ""),
                      ("read_at", "timestamptz", "")],
    "audit_events": [("id", "bigint", "PK"), ("occurred_at", "timestamptz", ""), ("actor_type / actor_id / actor_role", "", ""), ("action", "varchar(60)", ""), ("incident_id", "varchar(30)", "no FK: survives deletes"),
                     ("previous_state / new_state", "varchar(40)", ""), ("description", "text", ""), ("metadata", "jsonb", "")],
    "assignment_settings": [("id", "smallint", "PK (single row)"), ("active_strategy", "varchar(50)", "LOAD_BASED|SKILL_BASED|ROUND_ROBIN|MANUAL"), ("default_max_workload", "int", "")],
    "assignment_category_overrides": [("settings_id", "smallint", "PK, FK"), ("category_id", "bigint", "PK, FK"), ("strategy", "varchar(50)", "")],
}
ER_FKS = [  # child, parent, label
    ("technician_profiles", "users", "1:0..1"), ("technician_skills", "technician_profiles", "N:1"), ("technician_skills", "incident_categories", "N:1"),
    ("technician_profiles", "technician_profiles", "reports to"), ("service_dependencies", "it_services", "N:1 (x2)"), ("sla_policies", "it_services", "N:0..1"),
    ("incidents", "users", "reporter, assignee"), ("incidents", "incident_categories", "N:1"), ("incidents", "it_services", "N:1"),
    ("incident_sla", "incidents", "1:1"), ("incident_sla", "sla_policies", "N:0..1"), ("investigation_entries", "incidents", "N:1"), ("investigation_entries", "users", "author"),
    ("incident_feedback", "incidents", "1:0..1"), ("incident_escalations", "incidents", "N:1"), ("notifications", "incidents", "N:0..1"), ("notifications", "users", "recipient"),
    ("assignment_category_overrides", "assignment_settings", "N:1"), ("assignment_category_overrides", "incident_categories", "N:1"),
]


def er() -> str:
    s = f'''digraph G {{
  graph [rankdir=LR, fontname="{FONT}", nodesep=0.35, ranksep=1.0, pad=0.2, splines=true];
  node [shape=plain, fontname="{FONT}", fontsize=9];
  edge [fontname="{FONT}", fontsize=8, color="#555555", arrowtail=crow, arrowhead=tee, dir=both];
'''
    for t, cols in ER_TABLES.items():
        rows = f'<TR><TD COLSPAN="3" BGCOLOR="#dfe8f7"><B>{t}</B></TD></TR>'
        for n, ty, note in cols:
            bold = "<B>" if note.startswith("PK") else ""
            endb = "</B>" if bold else ""
            rows += f'<TR><TD ALIGN="LEFT">{bold}{esc(n)}{endb}</TD><TD ALIGN="LEFT">{esc(ty)}</TD><TD ALIGN="LEFT">{esc(note)}</TD></TR>'
        s += f'  {t} [label=<<TABLE BORDER="1" CELLBORDER="0" CELLSPACING="0" CELLPADDING="3">{rows}</TABLE>>];\n'
    for child, parent, label in ER_FKS:
        s += f'  {child} -> {parent} [label="{label}"];\n'
    return s + "}\n"


# ---------------------------------------------------------------------------------------------------------------------
# 5. Use-case diagram (drawn as SVG)
# ---------------------------------------------------------------------------------------------------------------------
USE_CASES = [
    ("Employee", [["Register / sign in", "Report an incident (form)", "Report with chat or voice", "Track my incidents"],
                  ["Answer a request for information", "Confirm the fix or reopen", "Rate the resolution", "Read notifications"]]),
    ("Technician", [["See my assigned incidents", "Acknowledge an incident", "Investigate and add notes", "Ask the reporter for information"],
                    ["Resolve with a resolution note", "See my workload", "See similar past fixes", "Read notifications"]]),
    ("Administrator", [["Manage users and technicians", "Manage categories and IT services", "Configure SLA policies", "Choose the assignment strategy"],
                       ["Assign / reassign (ranked advice)", "Change priority, close incident", "Search and filter incidents", "View analytics and dashboards"],
                       ["View the audit trail", "Configure escalation", "Review SLA risk and priority suggestions", "Read notifications"]]),
    ("System (schedulers and events)", [["Derive priority (impact, urgency, criticality)", "Track SLA, warn, record breach", "Predict SLA risk", "Escalate: lead, manager, admins"],
                                        ["Send in-app, email and SMS notifications", "Record every change in the audit trail"]]),
]


def usecase_svg() -> str:
    W, X0, CW, CH, GX, GY = 1330, 270, 218, 50, 18, 22
    parts, y = [], 70
    boxes = []
    for actor, rows in USE_CASES:
        h = len(rows) * (CH + GY) + 6
        boxes.append((actor, rows, y, h))
        y += h + 14
    H = y + 20
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="{FONT}, Arial, sans-serif" font-size="12">',
           f'<rect x="{X0 - 22}" y="38" width="{W - X0 - 8}" height="{H - 48}" rx="10" fill="#fbfcff" stroke="#6b7a99" stroke-width="1.5"/>',
           f'<text x="{X0 - 10}" y="30" font-weight="bold" font-size="14">IT-SM</text>']
    ai = None
    for actor, rows, y0, h in boxes:
        cy = y0 + h / 2 - 4
        ax = 120
        # stick figure
        svg.append(f'<g stroke="#222" stroke-width="2" fill="none"><circle cx="{ax}" cy="{cy - 38}" r="10"/><line x1="{ax}" y1="{cy - 28}" x2="{ax}" y2="{cy + 8}"/>'
                   f'<line x1="{ax - 20}" y1="{cy - 18}" x2="{ax + 20}" y2="{cy - 18}"/><line x1="{ax}" y1="{cy + 8}" x2="{ax - 16}" y2="{cy + 36}"/>'
                   f'<line x1="{ax}" y1="{cy + 8}" x2="{ax + 16}" y2="{cy + 36}"/></g>')
        svg.append(f'<text x="{ax}" y="{cy + 56}" text-anchor="middle" font-weight="bold">{esc(actor.split(" (")[0])}</text>')
        if "(" in actor:
            svg.append(f'<text x="{ax}" y="{cy + 70}" text-anchor="middle" font-size="10" fill="#555">(schedulers and events)</text>')
        lines, ells = [], []
        for r, row in enumerate(rows):
            for c, label in enumerate(row):
                cx = X0 + c * (CW + GX) + CW / 2
                cyy = y0 + 4 + r * (CH + GY) + CH / 2
                lines.append(f'<line x1="{ax + 22}" y1="{cy - 6}" x2="{cx - CW / 2 + 8}" y2="{cyy}" stroke="#9aa5bd" stroke-width="1"/>')
                words, line1, line2 = label.split(), "", ""
                for w in words:
                    if not line2 and (len(line1) + len(w) < 22 or not line1):
                        line1 += (" " if line1 else "") + w
                    else:
                        line2 += (" " if line2 else "") + w
                txt = (f'<text x="{cx}" y="{cyy - 3}" text-anchor="middle">{esc(line1)}</text><text x="{cx}" y="{cyy + 12}" text-anchor="middle">{esc(line2)}</text>'
                       if line2 else f'<text x="{cx}" y="{cyy + 4}" text-anchor="middle">{esc(line1)}</text>')
                ells.append(f'<ellipse cx="{cx}" cy="{cyy}" rx="{CW / 2}" ry="{CH / 2}" fill="#ffffff" stroke="#3b5aa0" stroke-width="1.3"/>{txt}')
                if actor == "Employee" and label == "Report with chat or voice":
                    ai = (cx, cyy)
        svg.extend(lines + ells)
    # external actor: Google Gemini
    if ai:
        gx, gy = W - 60, boxes[0][2] + 50
        svg.append(f'<line x1="{ai[0] + CW / 2 - 6}" y1="{ai[1] - 8}" x2="{gx - 26}" y2="{gy + 10}" stroke="#9aa5bd" stroke-dasharray="4 3"/>')
        svg.append(f'<rect x="{gx - 38}" y="{gy - 12}" width="76" height="56" rx="6" fill="#f1eefb" stroke="#6b5aa0"/><text x="{gx}" y="{gy + 8}" text-anchor="middle" font-size="11">&#171;external&#187;</text>'
                   f'<text x="{gx}" y="{gy + 24}" text-anchor="middle" font-weight="bold" font-size="11">Gemini API</text><text x="{gx}" y="{gy + 38}" text-anchor="middle" font-size="9" fill="#555">(optional)</text>')
    svg.append(f'<text x="{X0 - 10}" y="{H - 8}" font-size="10" fill="#555">Every human actor also signs in. &#171;include&#187;: reporting an incident uses the System\'s priority derivation and audit.</text>')
    svg.append("</svg>")
    return "\n".join(svg)


def main() -> None:
    render("architecture", ARCHITECTURE)
    render("state", STATE)
    render("class_lifecycle", class_lifecycle())
    render("class_assignment", class_assignment())
    render("class_events", class_events())
    render("class_notifications_audit", class_notifications_audit())
    render("class_errors", class_errors())
    render("er", er())
    (OUT / "usecase.svg").write_text(usecase_svg())
    print("wrote", ", ".join(sorted(p.name for p in OUT.glob("*.svg"))))


if __name__ == "__main__":
    main()
