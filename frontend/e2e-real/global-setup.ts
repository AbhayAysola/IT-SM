import { Api, randomPassword, uniqueEmail } from './support/api';
import { API_URL, need } from './support/env';

/**
 * Provisions the personas through the product's own mechanisms, and fails (never skips) if anything is missing:
 *  - the first ADMIN already exists: the CI job ran the existing command-mode bootstrap (`--itsm.bootstrap-admin=true`) with a random
 *    password it keeps in the environment only;
 *  - EMPLOYEE and TECHNICIAN accounts, and the technician profile, are created by that admin through the real admin API.
 * No test-only endpoint exists or is needed. The credentials are put in process.env for the workers; they are never written to disk.
 */
export default async function globalSetup() {
  const adminEmail = need('E2E_ADMIN_EMAIL');
  const adminPassword = need('E2E_ADMIN_PASSWORD');

  await waitForBackend();

  const admin = await Api.login(adminEmail, adminPassword);
  const me = await admin.ok('GET', '/api/users/me');
  if (me.role !== 'ADMIN') throw new Error('The bootstrap account is not an ADMIN');
  process.env.E2E_ADMIN_ID = String(me.id);

  const make = async (role: 'EMPLOYEE' | 'TECHNICIAN', label: string, name: string, prefix: 'EMPLOYEE' | 'TECH') => {
    const email = uniqueEmail(label);
    const password = randomPassword();
    const created = await admin.ok('POST', '/api/admin/users', { email, name, password, role }, 201);
    process.env[`E2E_${prefix}_EMAIL`] = email;
    process.env[`E2E_${prefix}_PASSWORD`] = password;
    process.env[`E2E_${prefix}_ID`] = String(created.id);
    return created.id as number;
  };
  await make('EMPLOYEE', 'employee', 'Real Stack Employee', 'EMPLOYEE');
  const techId = await make('TECHNICIAN', 'tech', 'Real Stack Technician', 'TECH');
  await admin.ok('POST', '/api/admin/technicians', { userId: techId, maxWorkload: 5, experienceYears: 2, available: true, isTeamLead: false, skills: [] }, 201);
  await admin.dispose();
}

/** The backend answers the public login endpoint (400 for an empty body) once it is up. Only "not reachable" is retried. */
async function waitForBackend() {
  const anon = await Api.anonymous();
  const deadline = Date.now() + 120_000;
  for (;;) {
    try {
      const r = await anon.send('POST', '/api/auth/login', {});
      if (r.status === 400 || r.status === 401) break;
      throw new Error(`unexpected HTTP ${r.status} from the login endpoint`);
    } catch (e) {
      if (Date.now() > deadline) throw new Error(`The backend at ${API_URL} did not become ready within 120s: ${(e as Error).message.split('\n')[0]}`);
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  await anon.dispose();
}
