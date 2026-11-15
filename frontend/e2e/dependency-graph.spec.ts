/** The service dependency map: the pure layout, then the admin page that renders it from the API. */
import { test as pure, expect as pureExpect } from '@playwright/test';
import { test, expect } from './fixtures';
import { openAdmin } from './admin-helpers';
import { layoutDependencyGraph } from '../src/features/admin/it-services/dependency-graph';
import type { ItService } from '../src/features/admin/types';

const svc = (id: number, name: string, dependsOn: number[], extra: Partial<ItService> = {}): ItService => ({
  id, name, description: null, businessCriticality: 3, ownerTeam: null, active: true,
  dependsOn: dependsOn.map((d) => ({ id: d, name: `s${d}`, active: true })), dependents: [], version: 0, createdAt: '', updatedAt: '', ...extra,
});

pure.describe('dependency layout', () => {
  pure('services nothing depends on are in column 0 and every arrow points right', () => {
    const g = layoutDependencyGraph([svc(1, 'Network', []), svc(2, 'Identity', [1]), svc(3, 'Email', [2, 1]), svc(4, 'Print', [1])]);
    const col = Object.fromEntries(g.nodes.map((n) => [n.name, n.column]));
    pureExpect(col).toEqual({ Email: 0, Print: 0, Identity: 1, Network: 2 }); // Network sits behind the longest chain
    pureExpect(g.edges).toHaveLength(4);
    for (const e of g.edges) pureExpect(g.nodes.find((n) => n.id === e.to)!.column).toBeGreaterThan(g.nodes.find((n) => n.id === e.from)!.column);
  });

  pure('drops edges to unknown services, ignores self references and terminates on a cycle', () => {
    pureExpect(layoutDependencyGraph([svc(1, 'A', [99, 1])]).edges).toEqual([]);
    const cyc = layoutDependencyGraph([svc(1, 'A', [2]), svc(2, 'B', [1])]);
    pureExpect(cyc.nodes).toHaveLength(2);
  });

  pure('an empty catalogue has a drawable size and more critical services come first in a column', () => {
    const g = layoutDependencyGraph([]);
    pureExpect(g.nodes).toEqual([]);
    pureExpect(g.width).toBeGreaterThan(0);
    const two = layoutDependencyGraph([svc(1, 'Low', [], { businessCriticality: 1 }), svc(2, 'High', [], { businessCriticality: 5 })]);
    pureExpect(two.nodes.find((n) => n.name === 'High')!.row).toBe(0);
  });
});

test('the services page can switch to a dependency map built from the API', async ({ page, api }) => {
  await openAdmin(page, api, '/admin/services');
  await page.getByRole('button', { name: 'Dependency map' }).click();
  const map = page.getByTestId('dependency-map');
  await expect(map).toBeVisible();
  await expect(map.getByTestId('dependency-node')).toHaveCount(3);
  await expect(map.getByTestId('dependency-edge').first()).toBeVisible();
  await expect(map.getByRole('img')).toHaveAccessibleName(/Service dependency map: 3 services, \d+ dependencies/);
  await expect(map.getByTestId('dependency-node').filter({ hasText: 'VPN' })).toContainText('Criticality 5');
  await page.getByRole('button', { name: 'List', exact: true }).click();
  await expect(page.getByTestId('dependency-map')).toHaveCount(0);
  await expect(page.locator('main tbody tr')).toHaveCount(3);
});
