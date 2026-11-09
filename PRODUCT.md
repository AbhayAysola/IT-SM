# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Three first-class audiences, sharing one system. Each gets its own portal and sets its own screen density.

- **Employees** report IT problems and track them. They are occasional users, not IT specialists, and often reporting under mild stress (a service is down and they cannot work or serve customers).
- **Technicians / support engineers** work assigned incidents: acknowledge, investigate, add notes, request more information, resolve. They watch their workload and SLA clocks.
- **Administrators / IT managers** manage users and technicians, IT services, incident categories, SLA policies and assignment strategies, and monitor incidents, SLA violations, reports and audit logs.

Primary-user decision (confirmed): **balanced**. No single audience is primary; employee screens favour simplicity, technician and admin screens favour scanning density.

## Product Purpose

IT-SM is an Intelligent IT Service Management and Incident Resolution System for a university OOP course project. It manages the full incident lifecycle (report, classify, prioritise, assign, investigate, resolve, verify, close) with automatic priority, technician recommendation, SLA monitoring, notifications, an append-only audit trail, and reporting. Success is a working, defensible system whose object-oriented design can be explained, and which could be adopted by the campus maintenance division / CCIT.

## Operating Context

- Authentication is JWT-based with role-based authorisation (EMPLOYEE, TECHNICIAN, ADMIN). Team leads are technician profile attributes, not a role.
- SLA response/resolution targets are central: deadlines, warnings and violations must be legible at a glance.
- Incident priority is P1 Critical, P2 High, P3 Medium, P4 Low. Incident ids look like `INC1024`.
- Frontend: Next.js 14 (App Router), React 18, Tailwind 3.4, shadcn/ui. Backend: Kotlin + Spring Boot. Light theme only for now.

## Capabilities and Constraints

- Distinct UI per role; links to move between a role's options.
- Notifications are in-app (web/PWA) with email/SMS simulated by severity.
- Open decisions: dark theme (not planned), PWA behaviour, push notifications, chatbot/voice reporting.

## Brand Commitments

- Product name **IT-SM** and the shield-check mark used on the sign-in screen and navigation (confirmed).
- No binding institutional colours or logo today. Colour is token-driven so campus branding can be applied later without touching components.

## Evidence on Hand

- Project specification and tech-stack documents provided by the team (not in the repository).
- No real user research, screenshots of a prior system, testimonials or metrics exist. Do not fabricate any.

## Product Principles

1. **Status must be unambiguous.** Priority, state and SLA risk are always readable without relying on colour alone.
2. **One system, three portals.** Shared components and tokens; density and navigation vary per role.
3. **Plain language for non-specialists.** Employee-facing copy avoids IT jargon.
4. **Auditable by default.** Anything that changes an incident is visible in its history.

## Accessibility & Inclusion

WCAG 2.2 AA is the required standard: keyboard-operable everything, visible focus, 4.5:1 text contrast, labels on every field, status never conveyed by colour alone, reduced-motion respected.
