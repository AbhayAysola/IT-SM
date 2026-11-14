'use client';

import React, { useState } from 'react';
import { Lightbulb, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { CardHeading } from './card-heading';
import { PRIORITY_NAME, suggestionHeadline, type PrioritySuggestionView } from '../model/priority';

interface Props {
  suggestion: PrioritySuggestionView;
  /** Opens the priority dialog with the suggestion prefilled. */
  onApply: (opener: HTMLElement) => void;
  /** May throw; the error is shown on the card. */
  onDismiss: () => Promise<void>;
}

/**
 * P4's priority suggestion for an admin: what it suggests and why, with Apply (through the priority dialog, so the
 * admin still confirms and can edit the reason) or Dismiss (the same priority is not suggested again for this incident).
 */
export function PrioritySuggestionCard({ suggestion, onApply, onDismiss }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dismiss = async () => {
    setBusy(true); setError(null);
    try { await onDismiss(); } catch (e) { setError(e instanceof Error ? e.message : 'That did not work.'); } finally { setBusy(false); }
  };
  return (
    <Card data-testid="priority-suggestion">
      <CardHeader>
        <CardHeading className="flex items-center gap-2"><Lightbulb className="h-4 w-4" aria-hidden="true" /> Suggested priority</CardHeading>
        <CardDescription>
          <span className="font-medium text-foreground">{suggestionHeadline(suggestion)}</span>
          {' '}(now {PRIORITY_NAME[suggestion.currentPriority]}).
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <ul className="list-disc space-y-1 pl-5 text-sm" aria-label="Why">
          {suggestion.reasons.map((r) => <li key={r}>{r}</li>)}
        </ul>
        {error && <p role="alert" className="text-sm font-medium text-destructive">{error}</p>}
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={(e) => onApply(e.currentTarget)} disabled={busy}>Apply…</Button>
          <Button size="sm" variant="outline" onClick={dismiss} disabled={busy}>
            {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
            Dismiss
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
