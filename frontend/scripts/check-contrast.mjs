#!/usr/bin/env node
/**
 * WCAG 2.2 contrast checker for the design tokens in src/app/globals.css.
 * No dependencies. Run: npm run check:contrast   (exit code 1 on any failure)
 *
 * Text pairs must reach 4.5:1 (AA). Non-text UI boundaries (form-control edges, focus rings)
 * must reach 3:1 (WCAG 1.4.11). Tinted backgrounds (e.g. success/10) are composited over the
 * surface they sit on before measuring.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '../src/app/globals.css'), 'utf8');
const root = css.slice(css.indexOf(':root'), css.indexOf('}', css.indexOf(':root')));
const tokens = Object.fromEntries([...root.matchAll(/--([a-z-]+):\s*([\d.]+)\s+([\d.]+)%\s+([\d.]+)%/g)].map((m) => [m[1], [+m[2], +m[3], +m[4]]]));

const hslToRgb = ([h, s, l]) => {
  s /= 100; l /= 100;
  const k = (n) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
  const f = (n) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return [f(0), f(8), f(4)].map((v) => v * 255);
};
const rgb = (name) => { if (!tokens[name]) throw new Error(`Unknown token --${name}`); return hslToRgb(tokens[name]); };
const over = (fg, alpha, bg) => fg.map((c, i) => c * alpha + bg[i] * (1 - alpha));       // composite fg@alpha over bg
const lum = (c) => { const [r, g, b] = c.map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

const white = [255, 255, 255];
// [label, foreground rgb, background rgb, minimum]
const T = 4.5, U = 3.0;
const pairs = [
  ['foreground on background', rgb('foreground'), rgb('background'), T],
  ['card-foreground on card', rgb('card-foreground'), rgb('card'), T],
  ['popover-foreground on popover', rgb('popover-foreground'), rgb('popover'), T],
  ['muted-foreground on background', rgb('muted-foreground'), rgb('background'), T],
  ['muted-foreground on card', rgb('muted-foreground'), rgb('card'), T],
  ['muted-foreground on muted', rgb('muted-foreground'), rgb('muted'), T],
  ['secondary-foreground on secondary', rgb('secondary-foreground'), rgb('secondary'), T],
  ['accent-foreground on accent', rgb('accent-foreground'), rgb('accent'), T],
  ['primary-foreground on primary (button)', rgb('primary-foreground'), rgb('primary'), T],
  ['primary-foreground on primary/90 (button hover)', rgb('primary-foreground'), over(rgb('primary'), 0.9, white), T],
  ['primary text on card (links)', rgb('primary'), rgb('card'), T],
  ['primary text on background (links)', rgb('primary'), rgb('background'), T],
  ['destructive-foreground on destructive', rgb('destructive-foreground'), rgb('destructive'), T],
  ['destructive text on card', rgb('destructive'), rgb('card'), T],
  ['destructive text on destructive/10 (alert)', rgb('destructive'), over(rgb('destructive'), 0.1, white), T],
  ['success-foreground on success', rgb('success-foreground'), rgb('success'), T],
  ['success text on success/10 (badge)', rgb('success'), over(rgb('success'), 0.1, white), T],
  ['warning-foreground on warning/15 (badge)', rgb('warning-foreground'), over(rgb('warning'), 0.15, white), T],
  ['warning-foreground on warning (solid)', rgb('warning-foreground'), rgb('warning'), T],
  ['sidebar-foreground on sidebar', rgb('sidebar-foreground'), rgb('sidebar-background'), T],
  ['sidebar-muted-foreground on sidebar', rgb('sidebar-muted-foreground'), rgb('sidebar-background'), T],
  ['sidebar-accent-foreground on sidebar-accent', rgb('sidebar-accent-foreground'), rgb('sidebar-accent'), T],
  ['sidebar-muted-foreground on sidebar-accent/60 (nav hover)', rgb('sidebar-muted-foreground'), over(rgb('sidebar-accent'), 0.6, rgb('sidebar-background')), T],
  ['sidebar-accent-foreground on sidebar-accent/60 (nav hover)', rgb('sidebar-accent-foreground'), over(rgb('sidebar-accent'), 0.6, rgb('sidebar-background')), T],
  ['sidebar-primary-foreground on sidebar-primary', rgb('sidebar-primary-foreground'), rgb('sidebar-primary'), T],
  // non-text
  ['input (control edge) on card', rgb('input'), rgb('card'), U],
  ['input (control edge) on background', rgb('input'), rgb('background'), U],
  ['ring (focus) on background', rgb('ring'), rgb('background'), U],
  ['ring (focus) on card', rgb('ring'), rgb('card'), U],
  ['sidebar-ring (focus) on sidebar', rgb('sidebar-ring'), rgb('sidebar-background'), U],
  ['destructive (invalid edge) on card', rgb('destructive'), rgb('card'), U],
];

let failed = 0;
for (const [label, fg, bg, min] of pairs) {
  const r = ratio(fg, bg), ok = r >= min;
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${r.toFixed(2).padStart(5)}:1  (min ${min})  ${label}`);
}
console.log(`\n${pairs.length - failed}/${pairs.length} pairs pass`);
process.exit(failed ? 1 : 0);
