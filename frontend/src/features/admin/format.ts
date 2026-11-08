/** 90 -> "1 h 30 min", 1440 -> "1 day", 45 -> "45 min". */
export function formatMinutes(total: number): string {
  if (!Number.isFinite(total)) return '—';
  if (total < 60) return `${total} min`;
  if (total < 1440) {
    const h = Math.floor(total / 60);
    const m = total % 60;
    return m ? `${h} h ${m} min` : `${h} h`;
  }
  const d = Math.floor(total / 1440);
  const h = Math.round((total % 1440) / 60);
  return h ? `${d} d ${h} h` : `${d} ${d === 1 ? 'day' : 'days'}`;
}
