'use client';

import React, { useEffect, useState } from 'react';
import { useRouter, useParams } from 'next/navigation';
import Link from 'next/link';
import { ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { PageHeader } from '@/features/admin/components/page-header';
import { useToast } from '@/features/admin/components/toast';
import { ErrorState } from '@/features/admin/components/states';
import { Skeleton } from '@/components/ui/skeleton';
import { CardHeading } from './components/card-heading';
import { useActor } from './components/workspace-shell';
import { incidentService } from './services/incident-service';
import { IncidentError } from './model/types';

export default function EditIncidentPage() {
  const actor = useActor();
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const id = decodeURIComponent(params.id);
  const toast = useToast();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    incidentService.get(actor, id)
      .then((detail) => {
        const i = detail.incident;
        if (i.state !== 'NEW' && i.state !== 'CLASSIFIED') {
          setLoadError('This incident can no longer be edited — it has already been assigned.');
          return;
        }
        if (actor.role !== 'ADMIN' && i.reporterEmail !== actor.email) {
          setLoadError('Only the reporter or an admin can edit this incident.');
          return;
        }
        setTitle(i.title);
        setDescription(i.description);
        setLoaded(true);
      })
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : 'Could not load incident.'));
  }, [actor, id]);

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!title.trim()) errs.title = 'Title is required.';
    else if (title.trim().length > 200) errs.title = 'At most 200 characters.';
    if (!description.trim()) errs.description = 'Description is required.';
    else if (description.trim().length > 4000) errs.description = 'At most 4000 characters.';
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSubmitting(true);
    setFieldErrors({});
    try {
      await incidentService.update(actor, id, { title: title.trim(), description: description.trim() });
      toast.success('Incident updated.');
      router.push(`/incidents/${id}`);
    } catch (err) {
      if (err instanceof IncidentError) {
        if (err.fields) setFieldErrors(err.fields);
        else toast.error(err.message);
      } else {
        toast.error('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const back = (
    <Link href={`/incidents/${id}`} className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline">
      <ArrowLeft className="h-4 w-4" aria-hidden="true" /> Back to incident
    </Link>
  );

  if (!loaded && !loadError) {
    return (
      <>
        {back}
        <div role="status" aria-label="Loading" className="space-y-3">
          <Skeleton className="h-9 w-1/2" />
          <Skeleton className="h-40 w-full max-w-2xl" />
        </div>
      </>
    );
  }

  if (loadError) {
    return (
      <>
        {back}
        <ErrorState message={loadError} />
      </>
    );
  }

  return (
    <>
      {back}
      <PageHeader title="Edit incident" description="Update the title or description before this incident is assigned." />
      <Card className="max-w-2xl">
        <CardHeader><CardHeading>Incident details</CardHeading></CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} noValidate className="space-y-5">
            <div className="space-y-1.5">
              <Label htmlFor="title">Title <span aria-hidden="true" className="text-destructive">*</span></Label>
              <Input
                id="title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                aria-describedby={fieldErrors.title ? 'title-error' : undefined}
                aria-invalid={!!fieldErrors.title}
              />
              {fieldErrors.title && <p id="title-error" className="text-xs text-destructive">{fieldErrors.title}</p>}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="description">Description <span aria-hidden="true" className="text-destructive">*</span></Label>
              <Textarea
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={6}
                aria-describedby={fieldErrors.description ? 'desc-error' : undefined}
                aria-invalid={!!fieldErrors.description}
              />
              {fieldErrors.description && <p id="desc-error" className="text-xs text-destructive">{fieldErrors.description}</p>}
            </div>

            <div className="flex gap-3 pt-2">
              <Button type="submit" disabled={submitting}>{submitting ? 'Saving…' : 'Save changes'}</Button>
              <Button type="button" variant="outline" onClick={() => router.push(`/incidents/${id}`)}>Cancel</Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </>
  );
}
