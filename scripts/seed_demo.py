#!/usr/bin/env python3
"""Seed a running IT-SM stack with demo data through its public admin API (no database access, no backend changes).

Creates, idempotently (existing items are left alone, so it is safe to run twice):
  * employees, technicians (with skills, a team lead and reporting lines) and a second admin-managed user set
  * a service catalogue with dependencies and per-service SLA policies
  * a handful of incidents raised by the employees, some assigned and moved along the lifecycle

Usage (the first admin must already exist: see README "First administrator"):
  ITSM_ADMIN_EMAIL=admin@example.com ITSM_ADMIN_PASSWORD='...' python3 scripts/seed_demo.py [--url http://localhost:8080]
Every demo account gets the password in ITSM_DEMO_PASSWORD (default: Demo-Pass-123). Demo data is for demos only.
Standard library only; Python 3.8+.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEMO_PASSWORD = os.environ.get("ITSM_DEMO_PASSWORD", "Demo-Pass-123")

EMPLOYEES = [("alice.employee@demo.itsm", "Alice Employee"), ("bob.employee@demo.itsm", "Bob Employee"), ("carol.employee@demo.itsm", "Carol Employee")]
# (email, name, maxWorkload, experience, teamLead, reportsTo email, skills {category code: proficiency})
TECHNICIANS = [
    ("tara.lead@demo.itsm", "Tara Lead", 8, 12, True, None, {"NETWORK": 5, "SECURITY": 4, "CLOUD": 4}),
    ("dev.tech@demo.itsm", "Dev Technician", 6, 5, False, "tara.lead@demo.itsm", {"DATABASE": 5, "APPLICATION": 4, "STORAGE": 3}),
    ("sam.tech@demo.itsm", "Sam Technician", 6, 3, False, "tara.lead@demo.itsm", {"HARDWARE": 5, "SOFTWARE": 4, "AUTHENTICATION": 3}),
]
# (name, description, criticality, owner team, depends on names)
SERVICES = [
    ("Network Core", "Routers, switches and DNS", 5, "Infrastructure", []),
    ("Identity Provider", "Single sign-on and directory", 5, "Security", ["Network Core"]),
    ("Primary Database", "Shared PostgreSQL cluster", 5, "Platform", ["Network Core"]),
    ("Email", "Corporate mail", 3, "Workplace", ["Identity Provider", "Network Core"]),
    ("HR Portal", "Leave and payroll web app", 3, "Business Apps", ["Identity Provider", "Primary Database"]),
    ("Print Service", "Office printing", 1, "Workplace", ["Network Core"]),
]
# criticality >= 4 services get tighter targets: (priority, response min, resolution min)
STRICT = {"P1": (10, 120), "P2": (30, 240), "P3": (120, 1440), "P4": (480, 4320)}
# (reporter, title, description, category code, service, impact, urgency, then: None | "assign" | "progress")
INCIDENTS = [
    ("alice.employee@demo.itsm", "VPN drops every few minutes", "The VPN disconnects roughly every five minutes since this morning.", "NETWORK", "Network Core", "HIGH", "HIGH", "progress"),
    ("bob.employee@demo.itsm", "Cannot sign in to the HR portal", "Login loops back to the sign-in page after entering my password.", "AUTHENTICATION", "Identity Provider", "MEDIUM", "HIGH", "assign"),
    ("carol.employee@demo.itsm", "Report query times out", "The monthly payroll query on the HR portal now takes minutes.", "DATABASE", "Primary Database", "MEDIUM", "MEDIUM", "assign"),
    ("alice.employee@demo.itsm", "Printer on floor 3 jams constantly", "Paper jams on every third page.", "HARDWARE", "Print Service", "LOW", "LOW", None),
    ("bob.employee@demo.itsm", "Mailbox is full but I deleted everything", "Email says my mailbox is at 100 percent.", "SOFTWARE", "Email", "LOW", "MEDIUM", None),
]


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, ok=(200, 201, 202, 204)):
        req = urllib.request.Request(self.base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {self.token}"} if self.token else {})})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                data = json.loads(raw) if raw else None
            except ValueError:
                data = {"raw": raw[:200].decode(errors="replace")}
            return e.code, data

    def login(self, email, password):
        status, data = self.call("POST", "/api/auth/login", {"email": email, "password": password})
        if status != 200 or not data or "token" not in data:
            raise SystemExit(f"Login failed for {email} (HTTP {status}).")
        self.token = data["token"]
        return data["user"]


def find_user(admin, email):
    status, page = admin.call("GET", f"/api/admin/users?search={urllib.request.quote(email)}&size=50")
    for u in (page or {}).get("items", []):
        if u["email"].lower() == email.lower():
            return u
    return None


def ensure_user(admin, email, name, role):
    existing = find_user(admin, email)
    if existing:
        return existing["id"], False
    status, data = admin.call("POST", "/api/admin/users", {"email": email, "name": name, "password": DEMO_PASSWORD, "role": role})
    if status not in (200, 201):
        raise SystemExit(f"Could not create user {email}: HTTP {status} {data}")
    return data["id"], True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("ITSM_URL", "http://localhost:8080"), help="backend base URL (no /api)")
    ap.add_argument("--no-incidents", action="store_true")
    args = ap.parse_args()
    email, password = os.environ.get("ITSM_ADMIN_EMAIL"), os.environ.get("ITSM_ADMIN_PASSWORD")
    if not email or not password:
        sys.exit("Set ITSM_ADMIN_EMAIL and ITSM_ADMIN_PASSWORD (the first administrator; see README).")

    admin = Api(args.url)
    admin.login(email, password)

    status, cats = admin.call("GET", "/api/admin/categories?size=100")
    category = {c["code"]: c["id"] for c in (cats or {}).get("items", [])}
    if not category:
        sys.exit("No categories found; is this the IT-SM backend?")

    ids, created = {}, 0
    for e, n in EMPLOYEES:
        ids[e], new = ensure_user(admin, e, n, "EMPLOYEE"); created += new
    for e, n, *_ in TECHNICIANS:
        ids[e], new = ensure_user(admin, e, n, "TECHNICIAN"); created += new
    print(f"users: {created} created, {len(EMPLOYEES) + len(TECHNICIANS) - created} already present")

    # Leads first so that reporting lines point at an existing profile.
    for e, n, workload, years, lead, reports_to, skills in sorted(TECHNICIANS, key=lambda t: not t[4]):
        status, _ = admin.call("GET", f"/api/admin/technicians/{ids[e]}")
        if status == 200:
            continue
        body = {"userId": ids[e], "maxWorkload": workload, "experienceYears": years, "available": True, "isTeamLead": lead,
                "reportsToUserId": ids[reports_to] if reports_to else None,
                "skills": [{"categoryId": category[c], "proficiency": p} for c, p in skills.items() if c in category]}
        status, data = admin.call("POST", "/api/admin/technicians", body)
        print(f"technician {n}: {'created' if status in (200, 201) else f'HTTP {status} {data}'}")

    svc = {}
    status, page = admin.call("GET", "/api/admin/services?size=100")
    for s in (page or {}).get("items", []):
        svc[s["name"]] = s["id"]
    for name, desc, crit, team, deps in SERVICES:
        if name in svc:
            continue
        status, data = admin.call("POST", "/api/admin/services", {"name": name, "description": desc, "businessCriticality": crit, "ownerTeam": team,
                                                                    "dependsOnServiceIds": [svc[d] for d in deps]})
        if status in (200, 201):
            svc[name] = data["id"]
        else:
            print(f"service {name}: HTTP {status} {data}")
    print(f"services: {len(svc)} in catalogue")

    for name, _, crit, *_ in SERVICES:
        if crit < 4 or name not in svc:
            continue
        for prio, (resp, reso) in STRICT.items():
            status, data = admin.call("POST", "/api/admin/sla-policies", {"serviceId": svc[name], "priority": prio, "responseMinutes": resp, "resolutionMinutes": reso})
            if status not in (200, 201, 409):
                print(f"SLA {name} {prio}: HTTP {status} {data}")
    print("SLA policies: stricter per-service targets for services with criticality >= 4 (others use the global defaults)")

    if args.no_incidents:
        return
    status, existing = admin.call("GET", "/api/incidents?size=1&q=VPN%20drops")
    if (existing or {}).get("items"):
        print("incidents: demo incidents already present, skipped")
        return
    tech_ids = [ids[t[0]] for t in TECHNICIANS]
    made = 0
    for reporter, title, desc, cat, service, impact, urgency, then in INCIDENTS:
        user = Api(args.url)
        user.login(reporter, DEMO_PASSWORD)
        status, inc = user.call("POST", "/api/incidents", {"title": title, "description": desc, "categoryId": category[cat], "serviceId": svc[service],
                                                            "impact": impact, "urgency": urgency})
        if status not in (200, 201):
            print(f"incident '{title}': HTTP {status} {inc}")
            continue
        made += 1
        if then:
            status, data = admin.call("POST", f"/api/incidents/{inc['id']}/assignment", {})
            if status not in (200, 201, 204):
                print(f"  assign {inc['id']}: HTTP {status} (left unassigned)")
                continue
        if then == "progress":
            tech = Api(args.url)
            # The assignee acts on it: find who was picked and sign in as them.
            status, detail = admin.call("GET", f"/api/incidents/{inc['id']}")
            assignee = (detail or {}).get("assigneeId")
            email_by_id = {v: k for k, v in ids.items()}
            if assignee in email_by_id:
                tech.login(email_by_id[assignee], DEMO_PASSWORD)
                for t in ("ACKNOWLEDGE", "START_WORK"):
                    tech.call("POST", f"/api/incidents/{inc['id']}/transitions", {"transition": t})
    print(f"incidents: {made} created")
    print(f"Demo accounts use password '{DEMO_PASSWORD}': " + ", ".join(e for e, _ in EMPLOYEES) + ", " + ", ".join(t[0] for t in TECHNICIANS))


if __name__ == "__main__":
    main()
