'use client';

import { useEffect, useState } from 'react';
import { useDebounced } from './use-resource';
import type { SortDir } from '../types';

/**
 * State shared by every admin list: debounced search, sort, and a 0-based page that returns to 0 whenever a filter
 * changes. `filters` is any object of filter values (their change resets the page).
 */
export function useListState<S extends string>(initialSort: S, initialDir: SortDir = 'asc', filters: unknown[] = [], initialSearch = '') {
  const [search, setSearch] = useState(initialSearch);
  const [sort, setSort] = useState<S>(initialSort);
  const [dir, setDir] = useState<SortDir>(initialDir);
  const [page, setPage] = useState(0);
  const debounced = useDebounced(search);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => setPage(0), [debounced, ...filters]);
  const sortBy = (k: S) => {
    if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSort(k); setDir('asc'); }
    setPage(0);
  };
  return { search, setSearch, debounced, sort, dir, page, setPage, sortBy };
}
