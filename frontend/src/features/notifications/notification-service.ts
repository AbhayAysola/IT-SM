import { request } from '@/lib/api';

/**
 * The signed-in user's notification inbox (P4, docs/architecture/p4.md section 4). The live service calls
 * `/api/notifications`; the mock (the default, like the incident pages) keeps a small demo inbox per account in
 * localStorage, so the bell works offline and the hermetic tests make no extra network calls.
 */

export type NotificationType =
  | 'INCIDENT_ASSIGNED' | 'ASSIGNMENT_MADE' | 'INFO_REQUESTED' | 'INCIDENT_RESOLVED' | 'INCIDENT_REOPENED'
  | 'SLA_WARNING' | 'SLA_BREACHED' | 'SLA_POLICY_MISSING' | 'ESCALATED';

export interface NotificationItem {
  id: number;
  type: NotificationType;
  incidentId: string | null;
  priority: 'P1' | 'P2' | 'P3' | 'P4' | null;
  title: string;
  body: string;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPage { items: NotificationItem[]; total: number; page: number; pageSize: number }

export const NOTIFICATION_PAGE_SIZE = 20;

/** Dispatched on `window` after a notification is marked read, so the bell and the inbox page stay in step. */
export const NOTIFICATIONS_CHANGED = 'itsm:notifications-changed';
const announceChange = () => { try { window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED)); } catch { /* no window (tests) */ } };

/** The backend's `NotificationDto` (channels are left out: the inbox does not show them). */
export interface ApiNotification extends NotificationItem { channels: string[] }

export const toNotification = (d: ApiNotification): NotificationItem =>
  ({ id: d.id, type: d.type, incidentId: d.incidentId, priority: d.priority, title: d.title, body: d.body, createdAt: d.createdAt, readAt: d.readAt });

/** Where a notification leads: its incident, if it has one. */
export const notificationHref = (n: NotificationItem) => (n.incidentId ? `/incidents/${encodeURIComponent(n.incidentId)}` : null);

interface NotificationService {
  /** `page` is 1-based, like the incident list. */
  list(email: string, unreadOnly: boolean, page: number, size?: number): Promise<NotificationPage>;
  unreadCount(email: string): Promise<number>;
  markRead(email: string, id: number): Promise<void>;
  markAllRead(email: string): Promise<void>;
}

// ---- live ----------------------------------------------------------------------------------------------------------

export const liveNotificationService: NotificationService = {
  async list(_email, unreadOnly, page, size = NOTIFICATION_PAGE_SIZE) {
    const q = new URLSearchParams({ unreadOnly: String(unreadOnly), page: String(Math.max(1, page) - 1), size: String(size) });
    const d = await request<{ items: ApiNotification[]; totalItems: number }>(`/api/notifications?${q}`);
    return { items: d.items.map(toNotification), total: d.totalItems, page: Math.max(1, page), pageSize: size };
  },
  async unreadCount() {
    return (await request<{ count: number }>('/api/notifications/unread-count')).count;
  },
  async markRead(_email, id) {
    await request(`/api/notifications/${id}/read`, { method: 'POST' });
    announceChange();
  },
  async markAllRead() {
    await request('/api/notifications/read-all', { method: 'POST' });
    announceChange();
  },
};

// ---- mock ----------------------------------------------------------------------------------------------------------

const memory = new Map<string, string>();
const storageKey = (email: string) => `itsm_p4_v1_notifications_${email.toLowerCase()}`;

function seed(): NotificationItem[] {
  const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
  return [
    { id: 3, type: 'SLA_WARNING', incidentId: 'INC1002', priority: 'P1', title: 'INC1002: resolution SLA at risk', body: 'VPN rejects every staff login. The deadline is close.', createdAt: ago(5), readAt: null },
    { id: 2, type: 'INFO_REQUESTED', incidentId: 'INC1001', priority: 'P2', title: 'The technician has a question about INC1001', body: 'Open the incident to answer: Wi-Fi drops every few minutes in the library reading room', createdAt: ago(40), readAt: null },
    { id: 1, type: 'ASSIGNMENT_MADE', incidentId: 'INC1001', priority: 'P2', title: 'A technician is working on INC1001', body: 'Wi-Fi drops every few minutes in the library reading room', createdAt: ago(180), readAt: ago(170) },
  ];
}

/**
 * Reading never writes: the demo inbox is only saved once something is marked read, so merely signing in leaves
 * localStorage untouched (the session tests require it to stay empty).
 */
function load(email: string): NotificationItem[] {
  let raw: string | null = null;
  try { raw = localStorage.getItem(storageKey(email)); } catch { raw = memory.get(storageKey(email)) ?? null; }
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed as NotificationItem[];
    } catch { /* unparseable: reseed */ }
  }
  return seed();
}

function save(email: string, items: NotificationItem[]) {
  const s = JSON.stringify(items);
  try { localStorage.setItem(storageKey(email), s); } catch { memory.set(storageKey(email), s); }
}

export const mockNotificationService: NotificationService = {
  async list(email, unreadOnly, page, size = NOTIFICATION_PAGE_SIZE) {
    const all = load(email).filter((n) => !unreadOnly || !n.readAt).sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id - a.id);
    const p = Math.max(1, page);
    return { items: all.slice((p - 1) * size, p * size), total: all.length, page: p, pageSize: size };
  },
  async unreadCount(email) {
    return load(email).filter((n) => !n.readAt).length;
  },
  async markRead(email, id) {
    const now = new Date().toISOString();
    save(email, load(email).map((n) => (n.id === id && !n.readAt ? { ...n, readAt: now } : n)));
    announceChange();
  },
  async markAllRead(email) {
    const now = new Date().toISOString();
    save(email, load(email).map((n) => (n.readAt ? n : { ...n, readAt: now })));
    announceChange();
  },
};

export const notificationService: NotificationService =
  process.env.NEXT_PUBLIC_INCIDENTS_API === 'live' ? liveNotificationService : mockNotificationService;
