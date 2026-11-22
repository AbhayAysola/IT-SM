# IT-SM demo video: script (target 18 minutes)

Four presenters, one per part of the system. Swap names if the split is different. Speaking pace is about 140 words a minute, so each narration block fits its time slot.

| Presenter | Part | Segments |
|---|---|---|
| P1 | Access and administration | 1, 2, 9 |
| P2 | Incident intake | 3 |
| P3 | Lifecycle and assignment | 4, 5 |
| P4 | SLA, escalation, notifications, analytics | 6, 7, 8 |

Use cases (UC) refer to the 15 use cases list. Every account uses the password `Demo-Pass-123`.

---

## Before recording (do this once, off camera)

1. Start the stack and create the first admin (README, "First administrator"). Run `scripts/seed_demo.py`, extended to the 15 users.
2. Create the short SLA policy: a service called **Demo Fast Lane** with P1 response 1 minute and resolution 3 minutes. Frank reports against it in segment 6.
3. Set the escalation percentages low in the admin escalation settings, so a breach and its escalation happen within a few minutes.
4. **Do a full dry run first and time it.** The SLA clocks run in real time, and the background sweep runs on a schedule. Note how long WARNING and BREACHED take, and plan the cut points from that.
5. Open these browser windows (separate profiles or private windows, so the sessions don't mix):
   - Window A: Priya (admin)
   - Window B: Alice (employee)
   - Window C: Dev (technician), with the bell visible
   - Window D: Frank (employee), used only for the escalation part
6. Allow desktop notifications for Window C.
7. Record at 1080p. Zoom the browser to 125% so text is readable. Hide bookmarks.

---

## Segment 1: Introduction and architecture (0:00 to 1:30), P1

**Screen:** title slide, then the architecture diagram from the report.

> "This is IT-SM, an IT incident management system. An employee reports a problem, the system works out its priority, assigns the right technician, tracks a service-level deadline, escalates when the deadline is at risk, and keeps a full audit trail. It runs as a Spring Boot backend, a Next.js frontend and PostgreSQL. We split the work four ways: access and administration, incident intake, the incident lifecycle and assignment, and SLA, escalation, notifications and analytics. In the next eighteen minutes we walk one incident from report to close, and then show the cases around it: the wrong user, the missed deadline, and the technician who is not available."

---

## Segment 2: Users, roles and the catalog (1:30 to 3:30), P1

**Screen:** Window A (Priya, admin). Admin > Users.

> "There are three roles: employee, technician and admin. Everything is permission-based, so what you see depends on who you are. I'm signed in as Priya, an admin. Here are our fifteen demo users."

**Actions:**
1. Scroll the user list. Point out the roles and the deactivated user (Hal).
2. Open Admin > Technicians. Open Nina (max workload 2) and Kofi (availability off). Show skills, team lead and "reports to".

> "Technicians have skills with a proficiency level, a maximum workload, an availability flag, and a reporting line. Nina can only hold two incidents. Kofi is switched off, so he will never be assigned. Rhea reports to Dev, and Dev reports to Tara. That chain is the order of escalation."

3. Open Admin > Services. Show the dependency map.

> "Services have a business criticality from one to five and depend on each other. The Identity Provider depends on the Network Core, so an outage in the core is more serious than it looks. This feeds the priority calculation."

4. Open Admin > SLA policies. Show the global defaults and the Demo Fast Lane policy.

> "Each service and priority has response and resolution targets. This Demo Fast Lane service has a one-minute response target, which is what lets us show a breach in this video."

---

## Segment 3: Reporting an incident (3:30 to 6:30), P2

**Screen:** Window B (Alice).

**UC1: report an incident (about 1:15)**

> "Alice is an employee. She opens the report form, picks a category and the affected service, and says how big the impact is and how urgent it feels."

**Actions:** Report "VPN drops every few minutes", category Network, service Network Core, impact High, urgency High. Submit.

> "She never picks a priority. The system calculates it from impact, urgency and the service's criticality. Here it came out as P1."

Show the incident detail: priority, state CLASSIFIED, the Service level card with both clocks running.

**UC2: duplicate warning (about 1:00)**

**Screen:** switch to Bob's window.

> "Now Bob reports almost the same problem."

**Actions:** Start typing a similar VPN incident. When matches appear, show them.

> "The form offers existing incidents that look the same. Only a match Bob confirms is recorded as a duplicate. If he ignores it, nothing is recorded."

**UC3: employees see only their own (about 0:45)**

**Screen:** Carol's window.

> "Carol is another employee. Her list only shows her own incidents. If she tries Alice's incident by its id, she is refused."

**Actions:** Show Carol's list, then paste Alice's incident URL and show the error.

---

## Segment 4: Assignment (6:30 to 9:00), P3

**Screen:** Window A (Priya).

**UC4: automatic assignment (about 1:15)**

> "Assignment is triggered by an admin. The system recommends technicians by skill match and current load, and automatic assignment picks the best one. Let me assign Alice's VPN incident."

**Actions:** Open the incident > Auto-assign. Show the result, then show the recommendation reasons if the UI displays them.

> "It went to Tara, who has the Network skill and spare capacity. Kofi was not even considered because he is switched off."

Switch to Window C (Dev) and show the bell, if the incident went to Dev. If it went to someone else, show that person's inbox instead.

> "Both the assignee and the reporter were notified at once."

**UC5: manual assignment (about 0:45)**

**Actions:** Open Bob's incident. Assign it to Dev by hand.

> "An admin or team lead can also choose the technician. The audit log keeps who did it."

Open Audit > filter by that incident. Show the assignment event.

---

## Segment 5: Working an incident to the end (9:00 to 12:00), P3

**Screen:** Window C (Dev, the assignee).

**UC6: work to resolution (about 1:00)**

**Actions:** Acknowledge, Start work, show the state changes.

> "The technician acknowledges. That is the first response, so the response clock is marked as met. Then starts work."

**UC7: ask a question (about 1:00)**

**Actions:** Request info: "Which floor are you on?". Switch to Alice's window, show she is notified, and answer. Switch back to the Service level card.

> "While we wait for the reporter, the resolution clock is paused. It paused at the exact moment the question was asked, and it resumes when Alice answers. The technician is not penalised for waiting."

**Actions:** Dev resolves the incident with a note.

**UC8: verify and rate (about 0:45)**

**Screen:** Alice's window.

**Actions:** Open the resolved incident, Verify, give a rating and comment.

> "Only the reporter can confirm the fix. The rating goes to the technician's record and shows up in analytics later."

**UC9: reopen (about 0:30)**

**Actions:** On Bob's incident (resolve it first as Dev), Bob chooses Reopen with a reason.

> "If the fix did not work, the reporter reopens it with a reason. It goes back to in progress, the technician is told, and the clock resumes. The time it sat in resolved counts as paused."

**UC10: technician reports their own incident (about 0:30)**

**Screen:** log in as Sam.

> "Technicians have problems too. Sam reports one, and he can verify it himself, because verification belongs to whoever reported the incident, whatever their role."

---

## Segment 6: SLA, risk and escalation (12:00 to 15:00), P4

**Screen:** Window D (Frank), then Window A.

**UC12: warning and breach (about 1:30)**

**Actions:** Frank reports a P1 on Demo Fast Lane (start this earlier if your dry run says the breach takes longer than 3 minutes). Switch to Window A > Dashboard > SLA queue.

> "This service has a one-minute response target. The SLA queue lists incidents by how close they are to missing a deadline. The clock shows running, then warning at the threshold, then breached. Before that, the system also forecasts which incidents will probably miss, based on the technician's workload. You can see the forecast line on the card."

Show the status moving. Cut to the breached state if needed.

**Actions:** Open the escalation history for that incident.

> "When a deadline is breached, the incident is escalated up the reporting chain: the assignee's lead first, then the next level. Each step is recorded."

**UC11: nobody eligible (about 1:00)**

**Actions:** Open Nina's profile and note her workload is full (or switch availability off for the remaining technicians). Report another incident as Frank, then auto-assign as Priya.

> "If no technician can take the incident, it doesn't just sit there. The admins are told immediately, and the escalation is recorded with the reason."

Show the admin notification in Window A.

**UC13: change a priority (about 0:30)**

**Actions:** Open an incident, Change priority to P1 with a reason. Show the new deadline.

> "The system can suggest a priority change when an incident looks out of date, but only an admin applies it. When the priority is raised, the deadline is recalculated so an incident never breaches the instant it is raised. The reason goes in the audit log."

---

## Segment 7: Real-time notifications (15:00 to 16:00), P4

**Screen:** Windows A and C side by side.

> "Notifications reach people without a refresh. When Priya assigns an incident, Dev's bell updates by itself over a live stream, and the browser shows a desktop notification. The app is also installable as a progressive web app, so it can sit on a phone's home screen."

**Actions:** Assign an incident in Window A and watch the bell in Window C. Click the desktop notification. Show the browser's "Install app" option briefly.

---

## Segment 8: Analytics (16:00 to 17:00), P4

**Screen:** Window A > Analytics.

> "For managers, analytics shows incidents by priority, how many are open now and how many are P1, compliance with the SLA for each service and category, and each technician's workload, resolved count and average rating."

**Actions:** Scroll the tiles, the by-priority table, the service table and the technician table. Point to the rating Alice gave.

---

## Segment 9: Access control, audit and wrap-up (17:00 to 18:30), P1

**UC14: role limits (about 0:45)**

**Actions:**
1. Alice opens the admin URL (refused).
2. Dev tries to change a priority (refused).
3. Try to sign in as Hal (refused, account deactivated).

> "Each role only gets its own permissions, and the checks are on the server, not just hidden buttons. A deactivated user can't sign in at all."

**UC15: admin governance (about 0:45)**

**Screen:** Priya.

**Actions:** Deactivate a technician profile (Kofi), reset a password. Open Audit and filter by actor.

> "Admins can deactivate a technician, which removes them from assignment, or reset a password. Every one of these actions is written to an append-only audit log with the actor's name, and the database itself refuses edits to it."

**Closing slide (about 0:30):**

> "That was one incident from report to verified, and the cases around it: duplicates, assignment, a paused clock, a reopen, a breach, an escalation, and the limits on each role. The code, the tests, the architecture and the report are in the repository. Thank you."

---

## Cut plan and timing

| Segment | Start | Length |
|---|---|---|
| 1 Introduction | 0:00 | 1:30 |
| 2 Users, roles, catalog | 1:30 | 2:00 |
| 3 Intake (UC1 to 3) | 3:30 | 3:00 |
| 4 Assignment (UC4, 5) | 6:30 | 2:30 |
| 5 Lifecycle (UC6 to 10) | 9:00 | 3:00 |
| 6 SLA and escalation (UC11 to 13) | 12:00 | 3:00 |
| 7 Real time | 15:00 | 1:00 |
| 8 Analytics | 16:00 | 1:00 |
| 9 Access, audit, close (UC14, 15) | 17:00 | 1:30 |

If you run long, cut UC9 and UC10 first, then shorten Segment 2. If you run short, show the by-service analytics table in more detail and the dependency map.

## Tips

- Record each segment as its own clip, so a mistake only costs that clip, and join them afterwards.
- Pre-warm each window (sign in, load the page) before recording so there are no loading screens on camera.
- For the SLA breach, record the "report the incident" clip early and the "it's breached" clip later, and cut between them.
- Don't show passwords on camera. Type them off screen or use a password manager.
