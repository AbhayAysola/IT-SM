import { expect } from '@playwright/test';

/**
 * Asserts that `text` does not contain a secret (an exact string) or a secret-shaped pattern, WITHOUT ever printing `text` or the
 * secret. Playwright's own `expect(text).not.toContain(secret)` prints both on failure, which would put the very credential being
 * guarded into the CI log. Use this for every assertion whose operands may hold a password, token, cookie or hash.
 */
export function expectNo(text: string, secret: string | RegExp, label: string) {
  const found = typeof secret === 'string' ? text.includes(secret) : secret.test(text);
  expect(found, `${label} (the value is withheld on purpose)`).toBe(false);
}

export const JWT = /eyJ[A-Za-z0-9_-]{10,}/;
export const BCRYPT = /\$2[aby]\$/;
