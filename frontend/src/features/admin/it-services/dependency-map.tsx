'use client';

import React, { useMemo } from 'react';
import { Network } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { useResource } from '../hooks/use-resource';
import { itServiceApi } from '../services/it-service-api';
import { CRITICALITY_LABEL } from '../types';
import { layoutDependencyGraph, NODE_H, NODE_W } from './dependency-graph';

// Colour is never the only signal: the criticality number is printed in every node and the list view has the same data.
const FILL: Record<number, string> = {
  5: 'hsl(var(--destructive) / 0.14)', 4: 'hsl(var(--warning, 38 92% 50%) / 0.16)', 3: 'hsl(var(--secondary))', 2: 'hsl(var(--muted))', 1: 'hsl(var(--muted))',
};

/** Every service as a node and every dependency as an arrow (dependent -> dependency), fetched in one request. */
export function DependencyMap({ refreshKey }: { refreshKey: number }) {
  const { data, loading, error, reload } = useResource(() => itServiceApi.list({ size: 100, sort: 'name', dir: 'asc' }), [refreshKey]);
  const graph = useMemo(() => layoutDependencyGraph(data?.items ?? []), [data]);
  const nodeById = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph]);

  if (error && !data) return <ErrorState message={error} onRetry={reload} />;
  if (loading && !data) return <TableSkeleton label="Loading dependency map" />;
  if (!data || data.items.length === 0) return <EmptyState icon={Network} title="No IT services yet" description="Add services and their dependencies to see the map." />;
  const truncated = data.totalItems > data.items.length;

  return (
    <div className="p-4" data-testid="dependency-map">
      <p className="mb-3 text-sm text-muted-foreground">
        An arrow points from a service to a service it depends on. A failure in the right-hand service can affect everything pointing at it.
        {truncated && ` Showing the first ${data.items.length} of ${data.totalItems} services.`}
      </p>
      <div className="overflow-x-auto rounded-md border">
        <svg role="img" aria-label={`Service dependency map: ${graph.nodes.length} services, ${graph.edges.length} dependencies`}
          width={graph.width} height={graph.height} viewBox={`0 0 ${graph.width} ${graph.height}`} className="block min-w-full">
          <defs>
            <marker id="dep-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0 0 L10 5 L0 10 z" fill="hsl(var(--muted-foreground))" />
            </marker>
          </defs>
          {graph.edges.map((e) => {
            const a = nodeById.get(e.from)!, b = nodeById.get(e.to)!;
            const x1 = a.x + NODE_W, y1 = a.y + NODE_H / 2, x2 = b.x, y2 = b.y + NODE_H / 2;
            const mid = (x1 + x2) / 2;
            return <path key={`${e.from}-${e.to}`} data-testid="dependency-edge" d={`M${x1} ${y1} C${mid} ${y1} ${mid} ${y2} ${x2 - 2} ${y2}`}
              fill="none" stroke="hsl(var(--muted-foreground))" strokeWidth="1.5" markerEnd="url(#dep-arrow)" />;
          })}
          {graph.nodes.map((n) => (
            <g key={n.id} data-testid="dependency-node" opacity={n.active ? 1 : 0.55}>
              <title>{`${n.name}: ${CRITICALITY_LABEL[n.criticality] ?? n.criticality}${n.active ? '' : ' (inactive)'}`}</title>
              <rect x={n.x} y={n.y} width={NODE_W} height={NODE_H} rx="8" fill={FILL[n.criticality] ?? FILL[1]} stroke="hsl(var(--border))" strokeDasharray={n.active ? undefined : '4 3'} />
              <text x={n.x + 10} y={n.y + 19} fontSize="12" fontWeight="600" fill="hsl(var(--foreground))">{n.name.length > 22 ? `${n.name.slice(0, 21)}…` : n.name}</text>
              <text x={n.x + 10} y={n.y + 35} fontSize="11" fill="hsl(var(--muted-foreground))">{`Criticality ${n.criticality}${n.active ? '' : ' · inactive'}`}</text>
            </g>
          ))}
        </svg>
      </div>
      <div className="mt-3"><Button variant="outline" size="sm" onClick={reload}>Refresh</Button></div>
    </div>
  );
}
