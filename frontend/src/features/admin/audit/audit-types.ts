/**
 * Shapes of the backend audit API (`GET /api/admin/audit-events`). They mirror the backend DTOs exactly; nothing here
 * is invented. See docs/architecture/audit-retrieval.md for the contract.
 */
export type AuditMetadataValue = string | number | boolean;

export interface AuditActor {
  type: 'USER' | 'SYSTEM';
  /** Stable user id. Null for system actors (and withheld from callers without the audit permission). */
  id: number | null;
  role: string | null;
  /** Job name of a system actor, e.g. SLA_MONITOR. */
  name: string | null;
}

export interface AuditEvent {
  id: string;
  occurredAt: string;
  actor: AuditActor;
  action: string;
  entityType: string;
  entityId: string | null;
  incidentId: string | null;
  previousState: string | null;
  newState: string | null;
  description: string;
  metadata: Record<string, AuditMetadataValue | AuditMetadataValue[]>;
}

export interface AuditPage {
  items: AuditEvent[];
  /** 0-based, as the backend counts. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Sort keys the backend accepts. */
export type AuditSortKey = 'occurredAt' | 'actorRole' | 'action' | 'entityType';

export type AuditRoleFilter = 'ALL' | 'ADMIN' | 'TECHNICIAN' | 'EMPLOYEE' | 'SYSTEM';

export interface AuditFilters {
  action: string;
  entityType: string;
  entityId: string;
  actorId: string;
  actorRole: AuditRoleFilter;
  /** Inclusive UTC calendar dates, `YYYY-MM-DD`. */
  from: string;
  to: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  action: '', entityType: '', entityId: '', actorId: '', actorRole: 'ALL', from: '', to: '',
};

export const EMPTY_AUDIT_PAGE: AuditPage = { items: [], page: 0, size: 0, totalItems: 0, totalPages: 0 };

export const ROLE_FILTER_LABEL: Record<Exclude<AuditRoleFilter, 'ALL'>, string> = {
  ADMIN: 'Administrator', TECHNICIAN: 'Technician', EMPLOYEE: 'Employee', SYSTEM: 'System',
};

// ---- formatting ---------------------------------------------------------------------------------------------------

const ACRONYMS = new Set(['IT', 'SLA', 'ID', 'API', 'SSO', 'VPN']);

/** `USER_CREATED` -> "User created", `IT_SERVICE` -> "IT service". Unknown codes still read sensibly. */
export function humanize(code: string): string {
  const words = code.toLowerCase().split('_').filter(Boolean).map((w) => (ACRONYMS.has(w.toUpperCase()) ? w.toUpperCase() : w));
  const text = words.join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Role and id only, which is all the backend stores about a person: "Administrator #12", "System: SLA monitor". */
export function actorLabel(actor: AuditActor): string {
  if (actor.type === 'SYSTEM') return `System: ${humanize(actor.name ?? 'unknown')}`;
  const role = actor.role ? ROLE_FILTER_LABEL[actor.role as keyof typeof ROLE_FILTER_LABEL] ?? humanize(actor.role) : 'User';
  return actor.id === null ? role : `${role} #${actor.id}`;
}

export function metadataValue(v: AuditMetadataValue | AuditMetadataValue[]): string {
  if (Array.isArray(v)) return v.length ? v.map(metadataValue).join(', ') : '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  return String(v);
}

export type EventTone = 'success' | 'destructive' | 'warning' | 'secondary';
/** Colour hint from the verb at the end of the action code. Purely presentational. */
export function actionTone(action: string): EventTone {
  if (/_(CREATED|ACTIVATED|RESOLVED|REGISTERED)$/.test(action)) return 'success';
  if (/_(DELETED|DEACTIVATED|REJECTED|REVOKED|BREACHED)$/.test(action) || /SESSIONS_REVOKED$/.test(action)) return 'destructive';
  if (/_(CHANGED|ESCALATED|WARNING_RAISED)$/.test(action)) return 'warning';
  return 'secondary';
}

// ---- filters -> query ---------------------------------------------------------------------------------------------

// Same shapes the backend enforces; checking here saves a round trip and gives field-level messages.
const ACTION_RE = /^[A-Z][A-Z0-9_]{2,59}$/;
const ENTITY_TYPE_RE = /^[A-Z][A-Z0-9_]{1,39}$/;
const ENTITY_ID_RE = /^[A-Za-z0-9_-]{1,40}$/;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** "user created" / "user-created" -> USER_CREATED. */
export const toCode = (v: string) => v.trim().toUpperCase().replace(/[\s-]+/g, '_');

export type FilterErrors = Partial<Record<keyof AuditFilters, string>>;

export function validateFilters(f: AuditFilters): FilterErrors {
  const e: FilterErrors = {};
  if (f.action.trim() && !ACTION_RE.test(toCode(f.action))) e.action = 'Use 3–60 letters, digits or underscores, e.g. USER_CREATED.';
  if (f.entityType.trim() && !ENTITY_TYPE_RE.test(toCode(f.entityType))) e.entityType = 'Use 2–40 letters, digits or underscores, e.g. SLA_POLICY.';
  if (f.entityId.trim() && !ENTITY_ID_RE.test(f.entityId.trim())) e.entityId = 'Use up to 40 letters, digits, hyphens or underscores.';
  if (f.actorId.trim() && !/^[1-9]\d{0,17}$/.test(f.actorId.trim())) e.actorId = 'Enter a positive whole number.';
  if (f.from && !isRealDate(f.from)) e.from = 'Enter a valid date.';
  if (f.to && !isRealDate(f.to)) e.to = 'Enter a valid date.';
  if (!e.from && !e.to && f.from && f.to && f.from > f.to) e.to = 'The end date must be on or after the start date.';
  return e;
}

function isRealDate(d: string): boolean {
  if (!DATE_RE.test(d)) return false;
  const t = new Date(`${d}T00:00:00Z`);
  return !Number.isNaN(t.getTime()) && t.toISOString().startsWith(d);
}

/** Start of the UTC day after `d`, as an ISO instant. The backend's `to` is exclusive, so "to 4 Nov" means < 5 Nov 00:00Z. */
function startOfNextUtcDay(d: string): string {
  const t = new Date(`${d}T00:00:00Z`);
  t.setUTCDate(t.getUTCDate() + 1);
  return t.toISOString();
}

export interface AuditQuery {
  filters: AuditFilters;
  sort: AuditSortKey;
  dir: 'asc' | 'desc';
  /** 1-based, as the UI counts. */
  page: number;
  pageSize: number;
}

/** Query string for the list endpoint. Empty filters are omitted; the caller must have validated the filters. */
export function toSearchParams({ filters: f, sort, dir, page, pageSize }: AuditQuery): URLSearchParams {
  const p = new URLSearchParams();
  const set = (k: string, v: string) => v && p.set(k, v);
  set('action', f.action.trim() && toCode(f.action));
  set('entityType', f.entityType.trim() && toCode(f.entityType));
  set('entityId', f.entityId.trim());
  set('actorId', f.actorId.trim());
  if (f.actorRole === 'SYSTEM') p.set('actorType', 'SYSTEM');
  else if (f.actorRole !== 'ALL') p.set('actorRole', f.actorRole);
  set('from', f.from && `${f.from}T00:00:00.000Z`);
  set('to', f.to && startOfNextUtcDay(f.to));
  p.set('sort', sort);
  p.set('dir', dir);
  p.set('page', String(page - 1));
  p.set('size', String(pageSize));
  return p;
}
