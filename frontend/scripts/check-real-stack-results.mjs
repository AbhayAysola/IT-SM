#!/usr/bin/env node
/**
 * Fails the real-stack job unless EVERY required test actually ran and passed. Reads:
 *   real-stack-report/results.json   (Playwright JSON reporter)
 *   e2e-real/manifest.json           (required spec files with exact test counts; documented exclusions)
 *
 * Three different things are reported separately and never mixed up:
 *   PASSED    ran and passed
 *   EXCLUDED  deliberately not part of this suite (manifest `excluded`, each with a reason and where it IS covered)
 *   SKIPPED   anything Playwright marked skipped (environment, missing persona, test.skip ...). There is no acceptable number but 0.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const failures = [];
const fail = (m) => failures.push(m);

const manifest = JSON.parse(readFileSync(join(root, 'e2e-real/manifest.json'), 'utf8'));
const reportPath = join(root, 'real-stack-report/results.json');
if (!existsSync(reportPath)) { console.error('FAIL: no Playwright report (real-stack-report/results.json). The run did not complete.'); process.exit(1); }
const report = JSON.parse(readFileSync(reportPath, 'utf8'));

// 1. Source rules: nothing in the suite may skip, mark itself expected-to-fail, or focus.
const specDir = join(root, 'e2e-real');
const sources = [];
const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (e.name.endsWith('.ts')) sources.push(p); } };
walk(specDir);
const banned = /\b(?:test|it|describe)\s*\.\s*(?:skip|fixme|fail|only)\s*\(|\btestInfo\s*\.\s*(?:skip|fixme|fail)\s*\(|\btest\s*\.\s*skip\b|\.skip\s*\(\s*(?:true|false|[a-z]+\s*\))/;
for (const f of sources) {
  const lines = readFileSync(f, 'utf8').split('\n');
  lines.forEach((l, i) => { if (!l.trim().startsWith('//') && !l.trim().startsWith('*') && banned.test(l)) fail(`forbidden skip/only/fail construct at ${f.replace(root, '')}:${i + 1}`); });
}

// 2. Every spec file on disk must be registered in the manifest (a new spec cannot run unchecked), and vice versa.
const onDisk = sources.filter((f) => f.endsWith('.spec.ts')).map((f) => f.split('/').pop());
for (const f of onDisk) if (!(f in manifest.required)) fail(`${f} exists but is not listed in e2e-real/manifest.json "required"`);
for (const f of Object.keys(manifest.required)) if (!onDisk.includes(f)) fail(`${f} is listed as required but does not exist`);

// 3. Results.
if (report.errors?.length) for (const e of report.errors) fail(`runner error (global setup or config): ${String(e.message ?? e).split('\n')[0].slice(0, 200)}`);
const byFile = {};
const tally = { passed: 0, skipped: 0, failed: 0, flaky: 0, interrupted: 0 };
const bad = [];
const visit = (suite, file) => {
  const f = suite.file ?? file;
  for (const spec of suite.specs ?? []) for (const t of spec.tests ?? []) {
    const s = t.status; // expected | unexpected | flaky | skipped
    const name = `${(f ?? '').split('/').pop()} › ${spec.title}`;
    byFile[(f ?? '').split('/').pop()] ??= { passed: 0, other: 0 };
    if (s === 'expected') { tally.passed++; byFile[(f ?? '').split('/').pop()].passed++; }
    else { byFile[(f ?? '').split('/').pop()].other++; if (s === 'skipped') tally.skipped++; else if (s === 'flaky') tally.flaky++; else tally.failed++; bad.push(`${s.toUpperCase()}: ${name}`); }
  }
  for (const c of suite.suites ?? []) visit(c, f);
};
for (const s of report.suites ?? []) visit(s, s.file);

for (const [file, expected] of Object.entries(manifest.required)) {
  const got = byFile[file]?.passed ?? 0;
  if (got !== expected) fail(`${file}: ${got} passed, manifest requires exactly ${expected}`);
}
for (const file of Object.keys(byFile)) if (!(file in manifest.required)) fail(`${file} ran but is not in the manifest`);
if (tally.skipped) fail(`${tally.skipped} test(s) were SKIPPED; the real-stack suite allows none`);
if (tally.flaky) fail(`${tally.flaky} test(s) were FLAKY (passed only on retry); not allowed`);
if (tally.failed) fail(`${tally.failed} test(s) FAILED or were interrupted`);
const required = Object.values(manifest.required).reduce((a, b) => a + b, 0);
if (tally.passed !== required) fail(`${tally.passed} passed, ${required} required in total`);

// 4. Report.
console.log('\nReal-stack results');
console.log(`  PASSED    ${tally.passed} / ${required} required`);
console.log(`  SKIPPED   ${tally.skipped}   (must be 0)`);
console.log(`  FLAKY     ${tally.flaky}   (must be 0)`);
console.log(`  FAILED    ${tally.failed}   (must be 0)`);
console.log(`  EXCLUDED  ${manifest.excluded.length} documented categories (deliberately not in this suite):`);
for (const x of manifest.excluded) console.log(`    - ${x.what}\n        why: ${x.reason}\n        covered by: ${x.coveredBy}`);
if (bad.length) { console.log('\nNot passed:'); bad.forEach((b) => console.log('  ' + b)); }
if (failures.length) { console.error('\nFAIL:'); failures.forEach((f) => console.error('  - ' + f)); process.exit(1); }
console.log('\nOK: every required real-stack test ran and passed; none skipped, flaky or missing.');
