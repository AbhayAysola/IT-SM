/**
 * Where the real stack is. The defaults are the ones the production build and the backend's default CORS allow-list already use,
 * so nothing about CORS or CSRF has to be relaxed to run this suite.
 */
export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080';
export const WEB_ORIGIN = process.env.E2E_WEB_ORIGIN ?? `http://localhost:${process.env.E2E_WEB_PORT ?? 3000}`;
export const COOKIE_NAME = 'itsm_session';

/** Reads a required variable; the message names the variable, never a value. */
export function need(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Required environment variable ${name} is not set (provisioning did not run or failed).`);
  return v;
}

export interface Persona { email: string; password: string; name: string; id: number }

/** The three accounts provisioned by global-setup. Passwords are random per run and exist only in this process tree's environment. */
export const admin = (): Persona => ({ email: need('E2E_ADMIN_EMAIL'), password: need('E2E_ADMIN_PASSWORD'), name: 'Administrator', id: Number(need('E2E_ADMIN_ID')) });
export const employee = (): Persona => ({ email: need('E2E_EMPLOYEE_EMAIL'), password: need('E2E_EMPLOYEE_PASSWORD'), name: 'Real Stack Employee', id: Number(need('E2E_EMPLOYEE_ID')) });
export const technician = (): Persona => ({ email: need('E2E_TECH_EMAIL'), password: need('E2E_TECH_PASSWORD'), name: 'Real Stack Technician', id: Number(need('E2E_TECH_ID')) });
