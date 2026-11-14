'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { ArrowLeft, Loader2, MessageSquare, PenLine, Sparkles, TriangleAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { PageHeader } from '@/features/admin/components/page-header';
import { useToast } from '@/features/admin/components/toast';
import { cn } from '@/lib/utils';
import { CardHeading } from './components/card-heading';
import { ChatIntake } from './components/chat-intake';
import { useActor } from './components/workspace-shell';
import { type AnalysisResult, type ExtractedIncidentFields, type Impact, type Urgency, incidentService } from './services/incident-service';
import { IncidentError } from './model/types';

type FormState = 'filling' | 'analyzing' | 'reviewing' | 'creating';
type InputMode = 'form' | 'chat';

interface CatalogItem { id: number; name: string; code?: string }

const IMPACT_OPTIONS: { value: Impact; label: string; sub: string }[] = [
  { value: 'HIGH',   label: 'Many people',          sub: 'The whole org or a large group' },
  { value: 'MEDIUM', label: 'My team or department', sub: 'A group I work with' },
  { value: 'LOW',    label: 'Just me',               sub: 'Only affects my work' },
];

const URGENCY_OPTIONS: { value: Urgency; label: string; sub: string }[] = [
  { value: 'HIGH',   label: "No — work has stopped",   sub: 'No workaround available' },
  { value: 'MEDIUM', label: 'Partially',                sub: 'I have a workaround' },
  { value: 'LOW',    label: 'Yes, I can still work',    sub: "It's just inconvenient" },
];

const CONFIDENCE_STYLE: Record<string, string> = {
  HIGH: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-200',
  MEDIUM: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-200',
  LOW: 'bg-muted text-muted-foreground',
};

export default function ReportIncidentPage() {
  const actor = useActor();
  const router = useRouter();
  const toast = useToast();

  const [inputMode, setInputMode] = useState<InputMode>('form');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [serviceId, setServiceId] = useState('');
  const [impact, setImpact] = useState<Impact | ''>('');
  const [urgency, setUrgency] = useState<Urgency | ''>('');
  const [formState, setFormState] = useState<FormState>('filling');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);

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

  const handleChatReady = (fields: ExtractedIncidentFields) => {
    if (fields.title) setTitle(fields.title);
    if (fields.description) setDescription(fields.description);
    if (fields.impact) setImpact(fields.impact);
    if (fields.urgency) setUrgency(fields.urgency);
    if (fields.suggestedCategoryId) setCategoryId(String(fields.suggestedCategoryId));
    if (fields.suggestedServiceId) setServiceId(String(fields.suggestedServiceId));
    setInputMode('form');
    setFormState('filling');
  };

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!title.trim()) errs.title = 'Title is required.';
    else if (title.trim().length > 200) errs.title = 'At most 200 characters.';
    if (!description.trim()) errs.description = 'Description is required.';
    else if (description.trim().length > 4000) errs.description = 'At most 4000 characters.';
    if (!categoryId) errs.categoryId = 'Please select a category.';
    if (!serviceId) errs.serviceId = 'Please select a service.';
    if (!impact) errs.impact = 'Please tell us how many people are affected.';
    if (!urgency) errs.urgency = 'Please tell us if you can still work.';
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const doCreate = async () => {
    setFormState('creating');
    try {
      const id = await incidentService.create(actor, {
        title: title.trim(),
        description: description.trim(),
        categoryId: Number(categoryId),
        serviceId: Number(serviceId),
        impact: impact as Impact,
        urgency: urgency as Urgency,
        aiSuggestedCategoryId: analysis?.suggestedCategoryId ?? null,
        aiDuplicateIds: analysis?.possibleDuplicates.map((d) => d.id) ?? [],
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
      setFormState(analysis ? 'reviewing' : 'filling');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setFormState('analyzing');

    let result: AnalysisResult | null = null;
    try {
      result = await incidentService.analyze({ title: title.trim(), description: description.trim() });
    } catch {
      // AI unavailable — skip the review step and create directly
    }

    if (result) {
      setAnalysis(result);
      setFormState('reviewing');
    } else {
      await doCreate();
    }
  };

  const busy = formState === 'analyzing' || formState === 'creating';
  const reviewing = formState === 'reviewing' || formState === 'creating';

  return (
    <>
      <Link href="/incidents" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline">
        <ArrowLeft className="h-4 w-4" aria-hidden="true" /> All incidents
      </Link>
      <PageHeader title="Report an incident" description="Describe what went wrong. We'll route it to the right person." />

      {catalogError && (
        <div role="alert" className="mb-4 rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">{catalogError}</div>
      )}

      <div className="max-w-2xl space-y-4">
        {/* Mode toggle */}
        {formState === 'filling' && (
          <div className="flex gap-2">
            <Button
              type="button"
              variant={inputMode === 'form' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setInputMode('form')}
              className="gap-1.5"
            >
              <PenLine className="h-3.5 w-3.5" aria-hidden="true" /> Fill form
            </Button>
            <Button
              type="button"
              variant={inputMode === 'chat' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setInputMode('chat')}
              className="gap-1.5"
            >
              <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" /> Chat with AI
            </Button>
          </div>
        )}

        {/* Chat mode */}
        {inputMode === 'chat' && formState === 'filling' && (
          <Card className="border-blue-200 bg-blue-50/40 dark:border-blue-800 dark:bg-blue-950/20">
            <CardHeader>
              <CardHeading>
                <span className="flex items-center gap-2">
                  <Sparkles className="h-4 w-4 text-blue-600 dark:text-blue-400" aria-hidden="true" />
                  Chat with AI assistant
                </span>
              </CardHeading>
            </CardHeader>
            <CardContent>
              <ChatIntake onReady={handleChatReady} />
            </CardContent>
          </Card>
        )}

        {/* Form mode */}
        {(inputMode === 'form' || reviewing) && (
          <Card>
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

                <AnswerGroup
                  id="impact" question="How many people are affected?" options={IMPACT_OPTIONS}
                  value={impact} onChange={setImpact} error={fieldErrors.impact}
                />
                <AnswerGroup
                  id="urgency" question="Can you still work?" options={URGENCY_OPTIONS}
                  value={urgency} onChange={setUrgency} error={fieldErrors.urgency}
                />

                {!reviewing && (
                  <div className="flex gap-3 pt-2">
                    <Button type="submit" disabled={busy}>
                      {formState === 'analyzing' ? (
                        <><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> Analyzing…</>
                      ) : 'Submit incident'}
                    </Button>
                    <Button type="button" variant="outline" onClick={() => router.push('/incidents')}>Cancel</Button>
                  </div>
                )}
              </form>
            </CardContent>
          </Card>
        )}

        {/* AI analysis review card */}
        {reviewing && analysis && (
          <Card className="border-blue-200 bg-blue-50/40 dark:border-blue-800 dark:bg-blue-950/20">
            <CardHeader>
              <CardHeading>
                <span className="flex items-center gap-2">
                  <Sparkles className="h-4 w-4 text-blue-600 dark:text-blue-400" aria-hidden="true" />
                  AI Analysis
                </span>
              </CardHeading>
            </CardHeader>
            <CardContent className="space-y-4">

              {analysis.suggestedCategoryName && (
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="space-y-0.5">
                    <p className="text-sm font-medium">
                      Suggested category: <span className="text-foreground">{analysis.suggestedCategoryName}</span>
                    </p>
                    {analysis.reasoning && (
                      <p className="text-xs text-muted-foreground">{analysis.reasoning}</p>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', CONFIDENCE_STYLE[analysis.confidence] ?? CONFIDENCE_STYLE.LOW)}>
                      {analysis.confidence}
                    </span>
                    {analysis.suggestedCategoryId !== null && String(analysis.suggestedCategoryId) !== categoryId && (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => setCategoryId(String(analysis.suggestedCategoryId!))}
                      >
                        Accept
                      </Button>
                    )}
                  </div>
                </div>
              )}

              {analysis.possibleDuplicates.length > 0 && (
                <div className="space-y-2 rounded-md border border-yellow-200 bg-yellow-50 p-3 dark:border-yellow-800 dark:bg-yellow-950/30">
                  <p className="flex items-center gap-1.5 text-sm font-medium text-yellow-800 dark:text-yellow-200">
                    <TriangleAlert className="h-4 w-4 shrink-0" aria-hidden="true" />
                    Similar open incidents found
                  </p>
                  <ul className="space-y-1">
                    {analysis.possibleDuplicates.map((d) => (
                      <li key={d.id} className="text-sm">
                        <Link href={`/incidents/${d.id}`} className="font-medium text-primary underline-offset-4 hover:underline">{d.id}</Link>
                        {' — '}{d.title}
                        {d.reason && <span className="ml-1 text-xs text-muted-foreground">({d.reason})</span>}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div className="flex flex-wrap gap-3 pt-1">
                <Button type="button" onClick={doCreate} disabled={formState === 'creating'}>
                  {formState === 'creating' ? (
                    <><Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> Creating…</>
                  ) : 'Create incident'}
                </Button>
                <Button type="button" variant="outline" onClick={() => setFormState('filling')}>Back to edit</Button>
                <Button type="button" variant="ghost" onClick={() => router.push('/incidents')}>Cancel</Button>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </>
  );
}

function AnswerGroup<T extends string>({ id, question, options, value, onChange, error }: {
  id: string; question: string; options: { value: T; label: string; sub: string }[];
  value: T | ''; onChange: (v: T) => void; error?: string;
}) {
  return (
    <div className="space-y-1.5">
      <p id={`${id}-label`} className="text-sm font-medium leading-none">
        {question} <span aria-hidden="true" className="text-destructive">*</span>
      </p>
      <div role="group" aria-labelledby={`${id}-label`} aria-describedby={error ? `${id}-error` : undefined} className="flex flex-wrap gap-2">
        {options.map((opt) => {
          const selected = value === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              aria-pressed={selected}
              onClick={() => onChange(opt.value)}
              className={cn(
                'rounded-md border px-3 py-2 text-left text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                selected ? 'border-primary bg-primary text-primary-foreground' : 'border-input bg-background hover:bg-accent hover:text-accent-foreground',
              )}
            >
              <span className="block font-medium">{opt.label}</span>
              <span className={cn('block text-xs', selected ? 'text-primary-foreground/80' : 'text-muted-foreground')}>{opt.sub}</span>
            </button>
          );
        })}
      </div>
      {error && <p id={`${id}-error`} className="text-xs text-destructive">{error}</p>}
    </div>
  );
}
