'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { PageHeader } from '@/features/admin/components/page-header';
import { useToast } from '@/features/admin/components/toast';
import { CardHeading } from './components/card-heading';
import { useActor } from './components/workspace-shell';
import { incidentService } from './services/incident-service';
import { IncidentError } from './model/types';

const PRIORITIES = [
  { value: 'P1', label: 'P1 — Critical' },
  { value: 'P2', label: 'P2 — High' },
  { value: 'P3', label: 'P3 — Medium' },
  { value: 'P4', label: 'P4 — Low' },
] as const;

type Priority = 'P1' | 'P2' | 'P3' | 'P4';

interface CatalogItem { id: number; name: string; code?: string }

export default function ReportIncidentPage() {
  const actor = useActor();
  const router = useRouter();
  const toast = useToast();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [serviceId, setServiceId] = useState('');
  const [priority, setPriority] = useState<Priority>('P3');
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const [categories, setCategories] = useState<CatalogItem[]>([]);
  const [services, setServices] = useState<CatalogItem[]>([]);
  const [catalogError, setCatalogError] = useState<string | null>(null);

  useEffect(() => {
    incidentService.loadCatalog()
      .then(({ categories: cats, services: svcs }) => {
        setCategories(cats);
        setServices(svcs);
      })
      .catch(() => setCatalogError('Could not load categories and services. Please try again.'));
  }, []);

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!title.trim()) errs.title = 'Title is required.';
    else if (title.trim().length > 200) errs.title = 'At most 200 characters.';
    if (!description.trim()) errs.description = 'Description is required.';
    else if (description.trim().length > 4000) errs.description = 'At most 4000 characters.';
    if (!categoryId) errs.categoryId = 'Please select a category.';
    if (!serviceId) errs.serviceId = 'Please select a service.';
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSubmitting(true);
    setFieldErrors({});
    try {
      const id = await incidentService.create(actor, {
        title: title.trim(),
        description: description.trim(),
        categoryId: Number(categoryId),
        serviceId: Number(serviceId),
        priority,
      });
      toast.success('Incident reported.');
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

  return (
    <>
      <Link href="/incidents" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline">
        <ArrowLeft className="h-4 w-4" aria-hidden="true" /> All incidents
      </Link>
      <PageHeader title="Report an incident" description="Describe what went wrong. We'll route it to the right person." />

      {catalogError && (
        <div role="alert" className="mb-4 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">{catalogError}</div>
      )}

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
                placeholder="Short summary of the problem"
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
                placeholder="What happened? When did it start? What have you tried?"
                rows={6}
                aria-describedby={fieldErrors.description ? 'desc-error' : undefined}
                aria-invalid={!!fieldErrors.description}
              />
              {fieldErrors.description && <p id="desc-error" className="text-xs text-destructive">{fieldErrors.description}</p>}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="category">Category <span aria-hidden="true" className="text-destructive">*</span></Label>
                <Select value={categoryId} onValueChange={setCategoryId}>
                  <SelectTrigger id="category" aria-invalid={!!fieldErrors.categoryId}>
                    <SelectValue placeholder="Select a category" />
                  </SelectTrigger>
                  <SelectContent>
                    {categories.map((c) => (
                      <SelectItem key={c.id} value={String(c.id)}>{c.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {fieldErrors.categoryId && <p className="text-xs text-destructive">{fieldErrors.categoryId}</p>}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="service">Service <span aria-hidden="true" className="text-destructive">*</span></Label>
                <Select value={serviceId} onValueChange={setServiceId}>
                  <SelectTrigger id="service" aria-invalid={!!fieldErrors.serviceId}>
                    <SelectValue placeholder="Select a service" />
                  </SelectTrigger>
                  <SelectContent>
                    {services.map((s) => (
                      <SelectItem key={s.id} value={String(s.id)}>{s.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {fieldErrors.serviceId && <p className="text-xs text-destructive">{fieldErrors.serviceId}</p>}
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="priority">Priority</Label>
              <Select value={priority} onValueChange={(v) => setPriority(v as Priority)}>
                <SelectTrigger id="priority"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {PRIORITIES.map((p) => (
                    <SelectItem key={p.value} value={p.value}>{p.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="flex gap-3 pt-2">
              <Button type="submit" disabled={submitting}>{submitting ? 'Submitting…' : 'Submit incident'}</Button>
              <Button type="button" variant="outline" onClick={() => router.push('/incidents')}>Cancel</Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </>
  );
}
