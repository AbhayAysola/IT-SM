import { request, type APIRequestContext } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { API_URL, COOKIE_NAME, WEB_ORIGIN } from './env';

/** A random string that satisfies the password policy (>= 8 chars, <= 72 bytes). */
export const randomPassword = () => `Aa1!${randomBytes(9).toString('hex')}`;
/** Short unique token for names, codes and emails. Letters and digits only. */
export const uid = () => randomBytes(4).toString('hex');
export const uniqueEmail = (label: string) => `rs-${label}-${uid()}@example.test`;

export interface Res<T = any> { status: number; body: T; headers: Record<string, string> }

/**
 * The real backend over HTTP, exactly as the browser app talks to it: `X-ITSM-Client: web` and the app's own Origin on every call, and
 * the HttpOnly session cookie in the context's cookie jar. Nothing is mocked and no security header is dropped.
 *
 * Failures never print request bodies (they can hold passwords) or response bodies (they can hold tokens): only method, path and status.
 */
export class Api {
  private constructor(readonly ctx: APIRequestContext) {}

  static async anonymous(headers: Record<string, string> = { Origin: WEB_ORIGIN, 'X-ITSM-Client': 'web' }): Promise<Api> {
    return new Api(await request.newContext({ baseURL: API_URL, extraHTTPHeaders: headers }));
  }

  /** Signs in the way the browser app does (web mode): the cookie lands in this context's jar. */
  static async login(email: string, password: string): Promise<Api> {
    const api = await Api.anonymous();
    const res = await api.send('POST', '/api/auth/login', { email, password });
    if (res.status !== 200) throw new Error(`Sign-in for a provisioned account failed with HTTP ${res.status}`);
    return api;
  }

  async send(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Res> {
    const r = await this.ctx.fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', ...headers },
      data: body === undefined ? undefined : JSON.stringify(body),
      failOnStatusCode: false,
      maxRedirects: 0,
    });
    const text = await r.text();
    let parsed: any = text;
    try { parsed = text ? JSON.parse(text) : null; } catch { /* not JSON (CORS refusal, for instance) */ }
    return { status: r.status(), body: parsed, headers: r.headers() };
  }
  get = (p: string) => this.send('GET', p);
  post = (p: string, b?: unknown) => this.send('POST', p, b ?? {});
  put = (p: string, b?: unknown) => this.send('PUT', p, b ?? {});

  /** Like `send`, but throws unless the status is the expected one. The message carries no payload. */
  async ok(method: string, path: string, body?: unknown, status = 200): Promise<any> {
    const r = await this.send(method, path, body);
    if (r.status !== status) throw new Error(`${method} ${path} answered ${r.status}, expected ${status}${r.body?.code ? ` (${r.body.code})` : ''}`);
    return r.body;
  }

  /** The current session cookie value (for replay tests). Never log it. */
  async sessionCookie(): Promise<string | undefined> {
    return (await this.ctx.storageState()).cookies.find((c) => c.name === COOKIE_NAME)?.value;
  }

  dispose = () => this.ctx.dispose();
}

/** A request context that presents `cookieValue` as the session cookie, with the web-client headers (a replayed browser session). */
export async function withCookie(cookieValue: string): Promise<Api> {
  return Api.anonymous({ Origin: WEB_ORIGIN, 'X-ITSM-Client': 'web', Cookie: `${COOKIE_NAME}=${cookieValue}` });
}

/** Seeds through the real admin API as the provisioned administrator. Each helper creates uniquely named data. */
export class Seed {
  private constructor(readonly admin: Api) {}
  static async asAdmin(email: string, password: string) { return new Seed(await Api.login(email, password)); }
  dispose = () => this.admin.dispose();

  async user(label = 'user', role: 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN' = 'EMPLOYEE') {
    const email = uniqueEmail(label), password = randomPassword(), name = `RS ${label} ${uid()}`;
    const u = await this.admin.ok('POST', '/api/admin/users', { email, name, password, role }, 201);
    return { ...u, email, password, name } as { id: number; email: string; password: string; name: string; version: number; role: string };
  }
  async category(label = 'CAT') {
    const code = `RS_${label.toUpperCase()}_${uid().toUpperCase()}`, name = `RS ${label} ${uid()}`;
    const c = await this.admin.ok('POST', '/api/admin/categories', { code, name, description: null, keywords: [`kw${uid()}`] }, 201);
    return c as { id: number; code: string; name: string; version: number };
  }
  async service(label = 'svc', dependsOn: number[] = []) {
    const name = `RS ${label} ${uid()}`;
    const s = await this.admin.ok('POST', '/api/admin/services', { name, description: null, businessCriticality: 3, ownerTeam: null, dependsOnServiceIds: dependsOn }, 201);
    return s as { id: number; name: string; version: number };
  }
  async technicianProfile(userId: number, extra: Record<string, unknown> = {}) {
    return this.admin.ok('POST', '/api/admin/technicians', { userId, maxWorkload: 5, experienceYears: 1, available: true, isTeamLead: false, skills: [], ...extra }, 201);
  }
}
