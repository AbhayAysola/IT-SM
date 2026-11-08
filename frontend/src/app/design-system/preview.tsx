'use client';

import React from 'react';
import {
  AlertCircle, Bell, ChevronDown, Home, Inbox, Loader2, LogOut, Plus, Settings, ShieldCheck, Trash2, Users,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog';
import {
  DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader,
  SidebarInset, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider, SidebarTrigger,
} from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';

function Section({ id, title, description, children }: { id: string; title: string; description?: string; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-h`} className="scroll-mt-20 space-y-4">
      <div>
        <h2 id={`${id}-h`} className="text-lg font-semibold tracking-tight">{title}</h2>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </section>
  );
}

const swatches = [
  { name: 'background', cls: 'bg-background', use: 'Page canvas' },
  { name: 'card', cls: 'bg-card', use: 'Cards, popovers' },
  { name: 'muted', cls: 'bg-muted', use: 'Quiet fills, table header' },
  { name: 'accent', cls: 'bg-accent', use: 'Hover / selected fill' },
  { name: 'border', cls: 'bg-border', use: 'Dividers' },
  { name: 'input', cls: 'bg-input', use: 'Control edges' },
  { name: 'primary', cls: 'bg-primary', use: 'Brand, primary action' },
  { name: 'destructive', cls: 'bg-destructive', use: 'Danger, P1' },
  { name: 'success', cls: 'bg-success', use: 'Resolved, healthy' },
  { name: 'warning', cls: 'bg-warning', use: 'At risk, P2' },
  { name: 'sidebar', cls: 'bg-sidebar', use: 'Navigation' },
  { name: 'sidebar-accent', cls: 'bg-sidebar-accent', use: 'Nav hover / active' },
];

const incidents = [
  { id: 'INC1024', title: 'Payment application unavailable', priority: 'P1', status: 'In progress', sla: 'At risk' },
  { id: 'INC1025', title: 'VPN drops every 10 minutes', priority: 'P2', status: 'Assigned', sla: 'On track' },
  { id: 'INC1026', title: 'Printer offline on floor 3', priority: 'P4', status: 'Resolved', sla: 'Met' },
];
const priorityVariant = { P1: 'destructive', P2: 'warning', P3: 'secondary', P4: 'outline' } as const;
const slaVariant = { 'At risk': 'warning', 'On track': 'secondary', Met: 'success' } as const;

export function DesignSystemPreview() {
  const [showArchived, setShowArchived] = React.useState(false);

  return (
    <TooltipProvider delayDuration={150}>
      <div className="min-h-screen bg-background">
        <header className="border-b bg-sidebar text-sidebar-foreground">
          <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-4 sm:px-6">
            <div className="flex items-center gap-3">
              <span className="flex h-8 w-8 items-center justify-center rounded-md bg-sidebar-primary text-sidebar-primary-foreground">
                <ShieldCheck className="h-5 w-5" aria-hidden="true" />
              </span>
              <h1 className="text-lg font-semibold text-white">IT-SM design system</h1>
            </div>
            <span className="rounded-md border border-sidebar-border px-2.5 py-0.5 text-xs font-semibold text-sidebar-foreground">Development only</span>
          </div>
        </header>

        <main className="mx-auto max-w-5xl space-y-12 px-4 py-10 sm:px-6">
          <p className="max-w-prose text-sm text-muted-foreground">
            Every shared component and variant. Reference: <span className="font-mono">DESIGN.md</span> (repository root). Tab through this
            page to check focus states. Data in the table is sample data.
          </p>

          <Section id="colour" title="Colour" description="Semantic tokens only. Components never use hex values.">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4">
              {swatches.map((s) => (
                <div key={s.name} className="overflow-hidden rounded-lg border bg-card">
                  <div className={`h-12 border-b ${s.cls}`} />
                  <div className="p-2.5">
                    <p className="font-mono text-xs font-medium">{s.name}</p>
                    <p className="text-xs text-muted-foreground">{s.use}</p>
                  </div>
                </div>
              ))}
            </div>
          </Section>

          <Section id="type" title="Typography" description="Inter. Sizes are the Tailwind defaults; use only these.">
            <Card>
              <CardContent className="space-y-3 p-6">
                <p className="text-3xl font-semibold tracking-tight">Page title, 30/36 semibold</p>
                <p className="text-2xl font-semibold tracking-tight">Section title, 24/32 semibold</p>
                <p className="text-lg font-semibold tracking-tight">Card or group title, 18/28 semibold</p>
                <p className="text-sm">Body text, 14/20 regular. The default size for dense enterprise screens.</p>
                <p className="text-sm text-muted-foreground">Secondary text, 14/20 in muted-foreground.</p>
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Label / overline, 12/16 medium</p>
                <p className="font-mono text-sm">INC1024 · identifiers use the mono stack</p>
              </CardContent>
            </Card>
          </Section>

          <Section id="buttons" title="Button" description="Variants, sizes and states.">
            <Card>
              <CardContent className="space-y-5 p-6">
                <div className="flex flex-wrap items-center gap-3">
                  <Button>Primary</Button>
                  <Button variant="secondary">Secondary</Button>
                  <Button variant="outline">Outline</Button>
                  <Button variant="ghost">Ghost</Button>
                  <Button variant="link">Link</Button>
                  <Button variant="destructive">Destructive</Button>
                </div>
                <div className="flex flex-wrap items-center gap-3">
                  <Button size="sm">Small</Button>
                  <Button>Default</Button>
                  <Button size="lg">Large</Button>
                  <Button size="icon" aria-label="Add"><Plus /></Button>
                  <Button><Plus /> With icon</Button>
                  <Button disabled>Disabled</Button>
                  <Button disabled><Loader2 className="animate-spin" /> Saving…</Button>
                </div>
              </CardContent>
            </Card>
          </Section>

          <Section id="forms" title="Input, Label, Select" description="Labels are always visible and associated. Invalid fields set aria-invalid.">
            <Card>
              <CardContent className="grid gap-5 p-6 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="ds-name">Full name</Label>
                  <Input id="ds-name" placeholder="Jane Doe" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="ds-email">Email</Label>
                  <Input id="ds-email" type="email" aria-invalid="true" aria-describedby="ds-email-err" defaultValue="not-an-email" />
                  <p id="ds-email-err" className="flex items-center gap-1.5 text-xs text-destructive">
                    <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" /> Enter a valid email address.
                  </p>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="ds-disabled">Disabled</Label>
                  <Input id="ds-disabled" disabled defaultValue="Read only value" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="ds-select">Category</Label>
                  <Select>
                    <SelectTrigger id="ds-select"><SelectValue placeholder="Select a category" /></SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectLabel>Infrastructure</SelectLabel>
                        <SelectItem value="network">Network</SelectItem>
                        <SelectItem value="database">Database</SelectItem>
                        <SelectItem value="storage">Storage</SelectItem>
                      </SelectGroup>
                      <SelectGroup>
                        <SelectLabel>Applications</SelectLabel>
                        <SelectItem value="application">Application</SelectItem>
                        <SelectItem value="auth">Authentication</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </div>
              </CardContent>
            </Card>
          </Section>

          <Section id="badges" title="Badge" description="Status and priority. Colour is never the only signal: the label always says it too.">
            <Card>
              <CardContent className="flex flex-wrap items-center gap-3 p-6">
                <Badge>Default</Badge>
                <Badge variant="secondary">Secondary</Badge>
                <Badge variant="outline">Outline</Badge>
                <Badge variant="destructive">Destructive</Badge>
                <Badge variant="success">Success</Badge>
                <Badge variant="warning">Warning</Badge>
              </CardContent>
            </Card>
          </Section>

          <Section id="cards" title="Card">
            <div className="grid gap-4 md:grid-cols-2">
              <Card>
                <CardHeader>
                  <CardTitle>Open incidents</CardTitle>
                  <CardDescription>Across all services, last 24 hours.</CardDescription>
                </CardHeader>
                <CardContent><p className="text-3xl font-semibold tabular-nums">42</p></CardContent>
                <CardFooter className="gap-2">
                  <Button size="sm">View all</Button>
                  <Button size="sm" variant="outline">Export</Button>
                </CardFooter>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Loading state</CardTitle>
                  <CardDescription>Skeletons mirror the final layout.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-3" role="status" aria-label="Loading">
                  <Skeleton className="h-8 w-24" />
                  <Skeleton className="h-4 w-full" />
                  <Skeleton className="h-4 w-2/3" />
                </CardContent>
              </Card>
            </div>
          </Section>

          <Section id="table" title="Table" description="Sample data. Wide tables scroll horizontally inside their own container.">
            <Card>
              <Table>
                <TableCaption className="mb-3">Sample incidents</TableCaption>
                <TableHeader>
                  <TableRow className="bg-muted/50">
                    <TableHead>ID</TableHead>
                    <TableHead>Title</TableHead>
                    <TableHead>Priority</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>SLA</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {incidents.map((i) => (
                    <TableRow key={i.id}>
                      <TableCell className="font-mono text-xs">{i.id}</TableCell>
                      <TableCell className="font-medium">{i.title}</TableCell>
                      <TableCell><Badge variant={priorityVariant[i.priority as keyof typeof priorityVariant]}>{i.priority}</Badge></TableCell>
                      <TableCell>{i.status}</TableCell>
                      <TableCell><Badge variant={slaVariant[i.sla as keyof typeof slaVariant]}>{i.sla}</Badge></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Card>
          </Section>

          <Section id="tabs" title="Tabs">
            <Tabs defaultValue="overview">
              <TabsList>
                <TabsTrigger value="overview">Overview</TabsTrigger>
                <TabsTrigger value="activity">Activity</TabsTrigger>
                <TabsTrigger value="notes">Notes</TabsTrigger>
              </TabsList>
              <TabsContent value="overview" className="text-sm text-muted-foreground">Arrow keys move between tabs; Tab moves into the panel.</TabsContent>
              <TabsContent value="activity" className="text-sm text-muted-foreground">Activity timeline goes here.</TabsContent>
              <TabsContent value="notes" className="text-sm text-muted-foreground">Investigation notes go here.</TabsContent>
            </Tabs>
          </Section>

          <Section id="overlays" title="Dialog, Sheet, Dropdown menu, Tooltip" description="All trap focus, close on Escape and return focus to the trigger.">
            <Card>
              <CardContent className="flex flex-wrap items-center gap-3 p-6">
                <Dialog>
                  <DialogTrigger asChild><Button variant="outline">Open dialog</Button></DialogTrigger>
                  <DialogContent>
                    <DialogHeader>
                      <DialogTitle>Deactivate category?</DialogTitle>
                      <DialogDescription>Existing incidents keep this category. It will no longer appear for new reports.</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                      <Button variant="outline">Cancel</Button>
                      <Button variant="destructive"><Trash2 /> Deactivate</Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>

                <Sheet>
                  <SheetTrigger asChild><Button variant="outline">Open sheet</Button></SheetTrigger>
                  <SheetContent>
                    <SheetHeader>
                      <SheetTitle>Filters</SheetTitle>
                      <SheetDescription>Side panels for secondary tasks and mobile navigation.</SheetDescription>
                    </SheetHeader>
                  </SheetContent>
                </Sheet>

                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline">Actions <ChevronDown /></Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start" className="w-48">
                    <DropdownMenuLabel>Incident</DropdownMenuLabel>
                    <DropdownMenuItem>Assign</DropdownMenuItem>
                    <DropdownMenuItem>Change priority</DropdownMenuItem>
                    <DropdownMenuCheckboxItem checked={showArchived} onCheckedChange={setShowArchived}>Show archived</DropdownMenuCheckboxItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem className="text-destructive focus:bg-destructive/10 focus:text-destructive">Close incident</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>

                <Tooltip>
                  <TooltipTrigger asChild><Button variant="ghost" size="icon" aria-label="Notifications"><Bell /></Button></TooltipTrigger>
                  <TooltipContent>Notifications</TooltipContent>
                </Tooltip>
              </CardContent>
            </Card>
          </Section>

          <Section id="sidebar" title="Sidebar" description="Deep navy navigation. Collapses to a Sheet below 768px (use the trigger).">
            <div className="relative h-[420px] transform overflow-hidden rounded-lg border [&_.h-svh]:h-full [&_.min-h-svh]:h-full [&_.min-h-svh]:min-h-0">
              <SidebarProvider>
                <Sidebar collapsible="none" className="hidden md:flex">
                  <SidebarHeader className="px-4 py-3">
                    <span className="flex items-center gap-2 font-semibold text-white">
                      <ShieldCheck className="h-5 w-5" aria-hidden="true" /> IT-SM
                    </span>
                  </SidebarHeader>
                  <SidebarContent>
                    <SidebarGroup>
                      <SidebarGroupLabel>Workspace</SidebarGroupLabel>
                      <SidebarGroupContent>
                        <SidebarMenu>
                          <SidebarMenuItem><SidebarMenuButton isActive><Home /> Dashboard</SidebarMenuButton></SidebarMenuItem>
                          <SidebarMenuItem><SidebarMenuButton><Inbox /> Incidents</SidebarMenuButton></SidebarMenuItem>
                          <SidebarMenuItem><SidebarMenuButton><Users /> Users</SidebarMenuButton></SidebarMenuItem>
                          <SidebarMenuItem><SidebarMenuButton><Settings /> Settings</SidebarMenuButton></SidebarMenuItem>
                        </SidebarMenu>
                      </SidebarGroupContent>
                    </SidebarGroup>
                  </SidebarContent>
                  <SidebarFooter>
                    <SidebarMenu>
                      <SidebarMenuItem><SidebarMenuButton><LogOut /> Sign out</SidebarMenuButton></SidebarMenuItem>
                    </SidebarMenu>
                  </SidebarFooter>
                </Sidebar>
                <SidebarInset className="bg-background">
                  <div className="flex items-center gap-2 border-b p-3">
                    <SidebarTrigger className="md:hidden" />
                    <span className="text-sm font-medium">Content area</span>
                  </div>
                  <p className="p-4 text-sm text-muted-foreground">Page content sits on the slate canvas next to the navy navigation.</p>
                </SidebarInset>
              </SidebarProvider>
            </div>
          </Section>

          <Section id="skeleton" title="Skeleton">
            <div className="flex items-center gap-4" role="status" aria-label="Loading">
              <Skeleton className="h-12 w-12 rounded-lg" />
              <div className="space-y-2">
                <Skeleton className="h-4 w-48" />
                <Skeleton className="h-4 w-32" />
              </div>
            </div>
          </Section>
        </main>
      </div>
    </TooltipProvider>
  );
}
