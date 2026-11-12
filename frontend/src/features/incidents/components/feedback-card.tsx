'use client';

import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { CardHeading } from './card-heading';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/features/admin/components/form-field';
import { useToast } from '@/features/admin/components/toast';
import { IncidentError, type Actor, type Feedback } from '../model/types';
import { COMMENT_MAX, incidentService } from '../services/incident-service';
import { formatDay } from './badges';

export const RATING_LABEL: Record<number, string> = { 1: 'Poor', 2: 'Fair', 3: 'Good', 4: 'Very good', 5: 'Excellent' };

/** Shows the given feedback, or the form when this reporter may still give it. Renders nothing otherwise. */
export function FeedbackCard({
  actor, incidentId, feedback, canGive, onGiven,
}: { actor: Actor; incidentId: string; feedback: Feedback | null; canGive: boolean; onGiven: () => void }) {
  const toast = useToast();
  const [rating, setRating] = useState<number | null>(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  if (feedback) {
    return (
      <Card>
        <CardHeader>
          <CardHeading>Feedback</CardHeading>
          <CardDescription>Given on {formatDay(feedback.submittedAt)}. It can’t be changed.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <p className="font-medium">Rated {feedback.rating} of 5: {RATING_LABEL[feedback.rating]}</p>
          {feedback.comment && <p className="whitespace-pre-wrap break-words text-muted-foreground">{feedback.comment}</p>}
        </CardContent>
      </Card>
    );
  }
  if (!canGive) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setErrors({});
    try {
      await incidentService.submitFeedback(actor, incidentId, rating ?? 0, comment);
      toast.success('Thank you for your feedback.');
      onGiven();
    } catch (err) {
      if (err instanceof IncidentError && err.fields) setErrors(err.fields);
      else toast.error(err instanceof Error ? err.message : 'Could not send your feedback.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardHeading>How did we do?</CardHeading>
        <CardDescription>Optional, and it can’t be changed later. If the problem is not fixed, use “No, it’s not fixed” above instead.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} noValidate className="space-y-4">
          <fieldset className="space-y-2" aria-describedby={errors.rating ? 'rating-error' : undefined}>
            <legend className="mb-1.5 text-sm font-medium">Your rating</legend>
            {[1, 2, 3, 4, 5].map((n) => (
              <label key={n} className="flex items-center gap-2 text-sm">
                <input type="radio" name="rating" value={n} checked={rating === n} onChange={() => setRating(n)} className="h-4 w-4 accent-primary" />
                {n} of 5: {RATING_LABEL[n]}
              </label>
            ))}
            {errors.rating && <p id="rating-error" role="alert" className="text-sm font-medium text-destructive">{errors.rating}</p>}
          </fieldset>
          <FormField id="feedback-comment" label="Comment (optional)" error={errors.comment}>
            <Textarea id="feedback-comment" value={comment} onChange={(e) => setComment(e.target.value)} maxLength={COMMENT_MAX + 1}
              aria-invalid={!!errors.comment} aria-describedby={errors.comment ? 'feedback-comment-error' : undefined} />
          </FormField>
          <Button type="submit" disabled={busy}>
            {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
            Send feedback
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
