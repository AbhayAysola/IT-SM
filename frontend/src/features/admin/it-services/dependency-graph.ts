import type { ItService } from '../types';

export interface GraphNode {
  id: number;
  name: string;
  criticality: number;
  active: boolean;
  column: number;
  row: number;
  x: number;
  y: number;
}
export interface GraphEdge { from: number; to: number }
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[]; width: number; height: number }

export const NODE_W = 168;
export const NODE_H = 44;
const GAP_X = 96;
const GAP_Y = 20;
const PAD = 16;

/**
 * Layers the catalogue left to right: services nothing depends on are in column 0, and every service sits one column to the right of the
 * furthest-left service that depends on it (longest chain), so each arrow (dependent -> dependency) points right. Edges to services that
 * are not in the list are dropped. The backend rejects cycles; the iteration cap only keeps a corrupt response from hanging the page.
 */
export function layoutDependencyGraph(services: ItService[]): Graph {
  const byId = new Map(services.map((s) => [s.id, s]));
  const edges: GraphEdge[] = [];
  const dependentsOf = new Map<number, number[]>();
  for (const s of services) {
    for (const d of s.dependsOn) {
      if (!byId.has(d.id) || d.id === s.id) continue;
      edges.push({ from: s.id, to: d.id });
      dependentsOf.set(d.id, [...(dependentsOf.get(d.id) ?? []), s.id]);
    }
  }
  const column = new Map<number, number>(services.map((s) => [s.id, 0]));
  for (let pass = 0; pass <= services.length; pass += 1) {
    let changed = false;
    for (const e of edges) {
      const want = (column.get(e.from) ?? 0) + 1;
      if (want > (column.get(e.to) ?? 0) && want <= services.length) { column.set(e.to, want); changed = true; }
    }
    if (!changed) break;
  }
  const columns: ItService[][] = [];
  for (const s of [...services].sort((a, b) => b.businessCriticality - a.businessCriticality || a.name.localeCompare(b.name))) {
    const c = column.get(s.id) ?? 0;
    (columns[c] ??= []).push(s);
  }
  const nodes: GraphNode[] = [];
  let tallest = 0;
  columns.forEach((col, c) => {
    if (!col) return;
    tallest = Math.max(tallest, col.length);
    col.forEach((s, r) => nodes.push({
      id: s.id, name: s.name, criticality: s.businessCriticality, active: s.active, column: c, row: r,
      x: PAD + c * (NODE_W + GAP_X), y: PAD + r * (NODE_H + GAP_Y),
    }));
  });
  const cols = Math.max(columns.length, 1);
  return {
    nodes, edges,
    width: PAD * 2 + cols * NODE_W + (cols - 1) * GAP_X,
    height: PAD * 2 + Math.max(tallest, 1) * NODE_H + (Math.max(tallest, 1) - 1) * GAP_Y,
  };
}
