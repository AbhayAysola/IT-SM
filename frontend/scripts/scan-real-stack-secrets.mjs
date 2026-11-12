#!/usr/bin/env node
/**
 * Runs BEFORE any artifact upload. Scans everything the real-stack job could publish (Playwright report, backend/bootstrap/web logs)
 * for credentials. On a hit it prints WHICH rule matched in WHICH file (never the matched text), deletes the whole report directory so
 * nothing can be uploaded, and exits 1.
 *
 * Rules: the generated admin password and JWT secret (exact values from the environment), any JWT, any bcrypt hash, a session-cookie
 * value, a Bearer token, and the format of every generated test password (`Aa1!` + 18 hex characters, see e2e-real/support/api.ts):
 * that last rule is what catches persona and seeded-user passwords, which exist only inside the test process.
 */
import { readFileSync, readdirSync, rmSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2] ?? join(new URL('..', import.meta.url).pathname, 'real-stack-report');
if (!existsSync(dir)) { console.log('secret scan: nothing to scan'); process.exit(0); }

const exact = ['E2E_ADMIN_PASSWORD', 'JWT_SECRET'].map((n) => [n, process.env[n]]).filter(([, v]) => v && v.length >= 8);
const rules = [
  ['JWT', /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*/],
  ['bcrypt hash', /\$2[aby]\$\d{2}\$[./A-Za-z0-9]{40,}/],
  ['session cookie value', /itsm_session=[A-Za-z0-9._-]{20,}/],
  ['bearer token', /Bearer\s+[A-Za-z0-9._-]{30,}/],
  ['generated test password', /Aa1![0-9a-f]{18}/],
];

const files = [];
const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (statSync(p).size < 50 * 1024 * 1024) files.push(p); } };
walk(dir);

const hits = [];
for (const f of files) {
  const text = readFileSync(f, 'latin1');
  for (const [name, value] of exact) if (text.includes(value)) hits.push(`${name} value in ${f.replace(dir, '')}`);
  for (const [name, re] of rules) if (re.test(text)) hits.push(`${name} pattern in ${f.replace(dir, '')}`);
}
if (hits.length) {
  console.error('SECRET SCAN FAILED. Matches (text withheld):');
  [...new Set(hits)].forEach((h) => console.error('  - ' + h));
  rmSync(dir, { recursive: true, force: true });
  console.error(`The report directory was deleted so that nothing is uploaded.`);
  process.exit(1);
}
console.log(`secret scan: ${files.length} file(s) clean`);
