import React from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { TableHead } from '@/components/ui/table';

export function SortHead<K extends string>({
  k, sort, dir, onSort, children,
}: { k: K; sort: K; dir: 'asc' | 'desc'; onSort: (k: K) => void; children: React.ReactNode }) {
  return (
    <TableHead aria-sort={sort === k ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => onSort(k)} className="inline-flex items-center gap-1 rounded font-medium hover:text-foreground">
        {children}
        {sort === k && (dir === 'asc' ? <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" /> : <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />)}
      </button>
    </TableHead>
  );
}
