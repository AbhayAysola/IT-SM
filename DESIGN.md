---
name: IT-SM
description: A calm, plain-spoken light interface for reporting, working and administering IT incidents.
colors:
  slate-canvas: "hsl(210 40% 98%)"
  paper-white: "hsl(0 0% 100%)"
  mist: "hsl(210 40% 96%)"
  mist-hover: "hsl(210 40% 94%)"
  hairline: "hsl(214 32% 91%)"
  control-edge: "hsl(215 16% 47%)"
  slate-ink: "hsl(222 47% 11%)"
  slate-gray: "hsl(215 19% 35%)"
  harbor-blue: "hsl(224 76% 48%)"
  signal-red: "hsl(0 72% 45%)"
  signal-green: "hsl(142 64% 24%)"
  signal-amber: "hsl(38 92% 50%)"
  amber-ink: "hsl(26 83% 20%)"
  deep-navy: "hsl(222 47% 11%)"
  navy-text: "hsl(214 32% 91%)"
  navy-muted: "hsl(215 20% 70%)"
  navy-raised: "hsl(222 35% 20%)"
  navy-line: "hsl(222 30% 22%)"
  navy-focus: "hsl(213 94% 68%)"
typography:
  display:
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 600
    lineHeight: "2.25rem"
    letterSpacing: "-0.025em"
  headline:
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: "2rem"
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: "1.75rem"
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.25rem"
  label:
    fontFamily: "Inter, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: "1rem"
    letterSpacing: "0.025em"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: "1rem"
rounded:
  sm: "4px"
  md: "6px"
  lg: "8px"
  xl: "12px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.harbor-blue}"
    textColor: "{colors.paper-white}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
    height: "36px"
  button-outline:
    backgroundColor: "{colors.slate-canvas}"
    textColor: "{colors.slate-ink}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
    height: "36px"
  button-destructive:
    backgroundColor: "{colors.signal-red}"
    textColor: "{colors.paper-white}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
    height: "36px"
  input:
    backgroundColor: "{colors.paper-white}"
    textColor: "{colors.slate-ink}"
    rounded: "{rounded.md}"
    padding: "4px 12px"
    height: "36px"
  card:
    backgroundColor: "{colors.paper-white}"
    textColor: "{colors.slate-ink}"
    rounded: "{rounded.xl}"
    padding: "24px"
  badge-secondary:
    backgroundColor: "{colors.mist}"
    textColor: "{colors.slate-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "2px 10px"
  nav-bar:
    backgroundColor: "{colors.deep-navy}"
    textColor: "{colors.navy-text}"
    height: "56px"
---

# Design System: IT-SM

> **Status: draft for team review.** The north star, voice and rule names below are proposals made on the
> basis of the product's confirmed answers (see [PRODUCT.md](PRODUCT.md)). Tokens and measurements are
> extracted from the built code and are normative. Where prose and `frontend/src/app/globals.css`
> disagree, `globals.css` wins; fix this file.

## Overview

**Creative North Star: "The Clean Service Counter"**

IT-SM should feel like a well-run service counter: you walk up, it is obvious where to go, the person behind
it is calm, and nothing is trying to impress you. Surfaces are quiet slate and white. One restrained blue
carries every action. Colour beyond that is reserved for facts that matter (a P1, an SLA at risk, a failure)
and is never decoration. The deep-navy navigation is the only dark element, and it exists to orient, not to
perform.

The system serves three audiences at three densities. Employees get generous spacing and plain words.
Technicians and administrators get tighter, scannable tables, but from the same tokens and the same
components. A screen is allowed to be dense; it is not allowed to be noisy.

**Key Characteristics:**
- Light theme only; neutral slate canvas, white cards, navy navigation, one blue accent.
- Flat at rest: hairline borders do most of the structural work, shadows are small and mostly for overlays.
- Plain language and visible labels; status always spelled out in words.
- One shape language: a 4 / 6 / 8 / 12 px radius scale, 1 px borders, no pills, no gradients.
- Accessible by construction: 2 px focus ring, contrast verified from tokens, keyboard-first overlays.

## Colors

A neutral slate ground with a single blue voice and three signal colours. All values are semantic CSS
variables (HSL channels) in `frontend/src/app/globals.css`; components reference token names such as
`bg-primary`, never hex.

### Primary
- **Harbor Blue** (`hsl(224 76% 48%)`, token `--primary`, also `--ring`): the only brand colour. Primary buttons, links, the focus ring, selected-state accents and the brand mark. 6.65:1 on white.

### Neutral
- **Slate Canvas** (`hsl(210 40% 98%)`, `--background`): the page ground behind cards.
- **Paper White** (`hsl(0 0% 100%)`, `--card` / `--popover`): cards, menus, dialogs, form surfaces.
- **Mist** (`hsl(210 40% 96%)`, `--muted` / `--secondary`): quiet fills, table header, secondary buttons, the sign-in toggle track.
- **Mist Hover** (`hsl(210 40% 94%)`, `--accent`): hover and selected fill for menu items and ghost buttons. It is a surface tone, not a brand accent.
- **Hairline** (`hsl(214 32% 91%)`, `--border`): decorative dividers and card edges.
- **Control Edge** (`hsl(215 16% 47%)`, `--input`): edges of form controls only. Deliberately darker than Hairline so a field is identifiable (4.7:1 on white, above the 3:1 non-text minimum).
- **Slate Ink** (`hsl(222 47% 11%)`, `--foreground`): body and heading text. 17:1 on the canvas.
- **Slate Gray** (`hsl(215 19% 35%)`, `--muted-foreground`): secondary text and placeholders. 7:1 on the canvas.

### Signal
- **Signal Red** (`hsl(0 72% 45%)`, `--destructive`): destructive actions, errors, P1.
- **Signal Green** (`hsl(142 64% 24%)`, `--success`): resolved, met, healthy. Used as text on a 10% tint (6.2:1).
- **Signal Amber** (`hsl(38 92% 50%)`) with **Amber Ink** (`hsl(26 83% 20%)`, `--warning-foreground`): at risk, P2. Amber is only a fill or indicator; text on it or on its 15% tint is always Amber Ink.

### Navigation (deep navy family)
- **Deep Navy** (`hsl(222 47% 11%)`, `--sidebar-background`), **Navy Text** (`hsl(214 32% 91%)`), **Navy Muted** (`hsl(215 20% 70%)`, 8.1:1), **Navy Raised** (`hsl(222 35% 20%)`, hover and active), **Navy Line** (`hsl(222 30% 22%)`), **Navy Focus** (`hsl(213 94% 68%)`, focus ring on navy). Used by the top bar and the Sidebar component only.

### Named Rules
**The One Voice Rule.** Harbor Blue is the only chromatic accent. A screen that needs a second accent colour is carrying information that belongs in a label.

**The Signal-Means-Something Rule.** Red, amber and green appear only when the state they name is true. Never use them to make a page livelier, and never as the sole carrier of meaning: the word (P1, At risk, Met) always accompanies the colour.

**The Token-Only Rule.** No hex, rgb or arbitrary colour values in components. If a needed colour has no token, add a token in `globals.css`, run `npm run check:contrast`, then use it.

## Typography

**Font:** Inter (loaded with `next/font`, exposed as `--font-inter`), with system sans fallbacks.
**Mono:** the system mono stack, used for identifiers such as `INC1024`.

**Character:** One family, differentiated by size and weight only. Plain, neutral and legible at 14 px; the hierarchy comes from semibold weight and slightly negative tracking on headings, not from a second typeface.

### Hierarchy
- **Display** (600, 30 / 36 px, `-0.025em`): the sign-in brand name. One per screen at most.
- **Headline** (600, 24 / 32 px, `-0.025em`): page titles (`h1`).
- **Title** (600, 18 / 28 px, `-0.025em`): card, section and dialog titles.
- **Body** (400, 14 / 20 px): the default for everything. This is a dense-capable system; do not enlarge body text to fill space. On mobile, text inputs use 16 px to prevent iOS zoom (`text-base md:text-sm`).
- **Label** (500, 12 / 16 px, `+0.025em`, uppercase for field captions and overlines): captions, table-adjacent metadata, badges.
- **Mono** (400, 12 / 16 px): ids and codes only.

Keep running text to about 65 to 75 characters per line. Paragraphs use `text-wrap: pretty` globally to avoid one-word last lines.

### Named Rules
**The Two Sizes Rule.** Everyday UI uses 14 px and 12 px. Reach for 18 px and above only for titles. Do not invent in-between sizes.

## Layout

Content sits on a Slate Canvas page. Spacing uses Tailwind's 4 px base; the working steps are 4, 8, 16, 24 and 32 px.

- **Page container:** `mx-auto`, side padding `16px` (mobile), `24px` (sm), `32px` (lg), vertical padding `40px`. Reading/profile pages cap at `max-w-4xl` (896 px); the navigation bar caps at `max-w-7xl`; the sign-in card is `max-w-md`. Data-heavy admin pages may use `max-w-7xl`.
- **Page header:** `h1` Headline, then one line of Slate Gray body text, `32px` above the content.
- **Cards:** `24px` padding (`32px` from `sm` for the sign-in and profile cards). Groups of related cards use a `16px` gap.
- **Forms:** one column; label above control with a `6px` gap; `16px` between fields; the submit button full width on the sign-in card.
- **Density per portal:** employee screens use the comfortable defaults above. Technician and admin tables may use `text-sm` rows and compact padding but must keep the same tokens, header treatment (Mist fill, Slate Gray text) and 36 px minimum control height.
- **Navigation:** a 56 px top bar in Deep Navy for the current app shell. The Sidebar component (also navy) is the pattern for role portals with several destinations; below 768 px it becomes a Sheet.

Responsive behaviour is covered in its own section below.

## Elevation & Depth

Flat by default. Structure comes from tonal layering (white cards on a slate canvas) and 1 px hairline borders, with small shadows reserved for controls and floating layers. There is no glow, blur, glass or coloured shadow.

### Shadow Vocabulary
- **Control** (`shadow-sm`): outline, secondary and destructive buttons, inputs, the select trigger.
- **Surface** (`shadow`): cards and the primary button.
- **Menu** (`shadow-md`): the select list.
- **Overlay** (`shadow-lg`): dropdown menus, dialogs, sheets. Behind dialogs and sheets sits a scrim of Slate Ink at 60% (`bg-foreground/60`).

### Named Rules
**The Flat-At-Rest Rule.** Surfaces do not change elevation on hover. Interaction is shown with fills and rings, not by lifting cards.

## Shapes

One radius scale driven by `--radius: 0.5rem`: **sm 4 px, md 6 px, lg 8 px, xl 12 px**.

- Form controls, buttons, badges, menu panels, tooltips: `md` (6 px).
- Menu items and small chips: `sm` (4 px).
- Avatar tiles and icon tiles: `lg` (8 px).
- Cards: `xl` (12 px).
- Borders are 1 px. Form controls use Control Edge; everything else uses Hairline.
- No fully rounded pills and no mixed radii inside one component. Icons are 16 px outline icons (`lucide-react`), marked `aria-hidden` when decorative.

## Components

Components are shadcn/ui (style `new-york`, installed with the pinned CLI `shadcn@2.3.0`). They live in
`frontend/src/components/ui/` and we own the code. Compose them; do not build competing primitives.
All 13 required components exist, plus Label and Separator: Button, Input, Label, Select, Dialog,
Dropdown Menu, Table, Badge, Card, Tabs, Sidebar, Sheet, Tooltip, Skeleton, Separator.

### Buttons
- **Shape:** 6 px radius, 14 px medium text. Heights: `sm` 32, default 36, `lg` 40, icon 36 x 36 px.
- **Primary:** Harbor Blue fill, white text, `8px 16px` padding. One primary action per view or dialog.
- **Secondary / Outline / Ghost / Link:** Mist fill; Slate Canvas fill with Control Edge border; transparent with Mist Hover on hover; blue underlined text.
- **Destructive:** Signal Red fill. Confirm destructive actions in a Dialog.
- **Loading:** keep the label, add a spinning `Loader2` icon, set `disabled`.

### Inputs / Fields
- **Style:** 36 px high, 6 px radius, Control Edge border, transparent fill, 12 px horizontal padding. Placeholder in Slate Gray.
- **Label:** always a visible `Label` bound with `htmlFor`; placeholders never replace labels.
- **Focus:** 2 px Harbor Blue ring with a 2 px offset.
- **Error:** set `aria-invalid="true"` (border and ring turn Signal Red), and describe the error in text under the field with `aria-describedby` and an icon.
- **Disabled:** 50% opacity, not focusable.

### Cards / Containers
- 12 px radius, Hairline border, Paper White, `shadow`, 24 px padding. Use `CardHeader`, `CardTitle`, `CardDescription`, `CardContent`, `CardFooter`.
- Related details inside a card use a Mist tint at 50%, a 1 px border and 8 px radius, structured as a valid `dl`.

### Badges
- 6 px radius, 12 px semibold. Variants: default (blue), secondary, outline, destructive (solid red), **success** and **warning** (soft tinted fill, coloured text, tinted border).
- Convention for ITSM data: priority P1 destructive, P2 warning, P3 secondary, P4 outline; SLA Met success, At risk warning, On track secondary. The text is always shown.

### Table
- Header row Mist at 50% with Slate Gray text; rows separated by hairlines, Mist hover. Identifiers in mono. Wide tables scroll horizontally inside their own container.

### Navigation
- **Top bar:** Deep Navy, 56 px, Navy Text. The brand mark is a 32 px Harbor Blue tile with the shield icon. Links are 14 px medium with an icon; the active link is Navy Raised with white text and carries `aria-current="page"`; inactive links are Navy Muted and brighten on hover.
- **Account menu:** a Dropdown Menu from the user chip (initial, name, email). Shows role badge, mobile-only links and Sign out in Signal Red. Below `md` the primary links live in this menu.
- **Sidebar:** Deep Navy panel, grouped menu, active item on Navy Raised; collapses to a Sheet below 768 px.

### Overlays
- **Dialog / Sheet / Dropdown / Select / Tooltip** are Radix-based: they trap focus, close on Escape and return focus to the trigger. Tooltips are Slate Ink with white text, 12 px, and are supplementary only (never the only place information appears).

### Local deviations from stock shadcn@2.3.0
The generated components were adjusted. **If you re-add a component with `--overwrite`, re-apply these:**
1. Button, Input, Select trigger: 2 px focus ring with 2 px offset (stock is 1 px, no offset).
2. Badge: added `success` and `warning` variants.
3. Input: added `aria-invalid` border and ring styling.
4. Dialog and Sheet: scrim is `bg-foreground/60` (stock `bg-black/80`).
5. Tooltip: `bg-foreground text-background` (stock is the primary blue).

Stock shadcn@2.3.0 also writes `oklch()` colours into `globals.css`, which Tailwind 3 cannot use inside `hsl(var(--x))`. Never accept that overwrite; tokens here are HSL channels.

### Preview page
Every component and variant is rendered at `/design-system`. It exists only for development: reachable when `NODE_ENV` is not `production`, and in a production build only if the server is started with `ENABLE_DESIGN_SYSTEM=true`; otherwise it returns 404. It is not indexed.

## Do's and Don'ts

### Do:
- **Do** use semantic tokens (`bg-primary`, `text-muted-foreground`, `border-input`) for every colour.
- **Do** pair signal colour with a word or icon (`P1`, `At risk`, `Met`).
- **Do** keep one primary button per view and use outline or ghost for the rest.
- **Do** give every field a visible label and every icon-only button an `aria-label`.
- **Do** keep body text at 14 px and let density come from spacing, not from smaller type.
- **Do** run `npm run check:contrast` after changing any token, and scan changed pages with `impeccable detect`.

### Don't:
- **Don't** use gradients, glassmorphism, blur, glow shadows, coloured shadows, or decorative background shapes.
- **Don't** hardcode colours or reintroduce the old dark-theme classes (`glass-panel`, `bg-mesh`, `shadow-glow`).
- **Don't** add a second accent colour, or use signal colours decoratively.
- **Don't** lift or scale cards on hover.
- **Don't** use placeholder text as the label, or `aria-label` text that differs from visible text.
- **Don't** add a dark theme piecemeal. `darkMode: 'class'` stays so the `dark:` variants inside shadcn components remain inert; switching it to `media` would half-darken the app for every visitor whose OS is in dark mode.

## Interaction States

| State | Treatment |
|---|---|
| Default | Flat fill, 1 px border, as specified per component. |
| Hover | Fill step only: primary to 90% opacity, ghost/outline to Mist Hover, menu items to Mist Hover, table rows to Mist at 50%. No elevation or movement. |
| Focus (keyboard) | 2 px Harbor Blue ring with 2 px offset (Navy Focus on navy). Never removed without a replacement. Fallback `:focus-visible` outline in `globals.css`. |
| Active / pressed | Same as hover; toggles expose state with `aria-pressed` and `aria-current`. |
| Selected | Mist Hover fill for menu items; Navy Raised for navigation. |
| Disabled | 50% opacity, `disabled` attribute, no pointer events. Exempt from contrast rules but never the only way to convey that something is unavailable (explain why in text). |
| Loading | Spinner inside the button, or Skeletons shaped like the final layout inside a `role="status"` region. |
| Error | `aria-invalid`, Signal Red border and ring, message text with icon under the field; page-level errors use `role="alert"` with a red tint. |
| Empty | Say what is missing and what to do; no illustration required. |

Transitions are short fades or colour changes (about 150 ms). `prefers-reduced-motion` reduces all animation and transition durations to near zero.

## Responsive Behavior

Tailwind breakpoints: `sm` 640, `md` 768, `lg` 1024. Mobile first; no horizontal page scroll at 390 px (verified).

- **< 768 px:** the top-bar links collapse into the account menu; the Sidebar becomes a Sheet opened from a trigger; profile detail grids are one column.
- **>= 640 px:** profile details become two columns; user name and email appear in the top bar chip; card padding rises from 24 to 32 px.
- **Tables** scroll horizontally inside their own container; the page itself never does.
- **Touch targets:** keep at least 24 x 24 px (WCAG 2.2 AA); default controls are 36 px and the top bar targets are 40 px. Prefer `lg` (40 px) for primary mobile actions.
- **Text inputs** are 16 px on mobile to prevent zoom.

## Accessibility

Required standard: **WCAG 2.2 AA.**

- **Contrast:** body and secondary text 4.5:1 or better; non-text boundaries (form-control edges, focus rings) 3:1 or better. All 31 token pairs are computed from `globals.css` by `npm run check:contrast` (exits non-zero on any failure). Rendered pages are additionally scanned with axe-core.
- **Keyboard:** every control reachable and operable; logical tab order; overlays trap focus, close on Escape, and return focus to the trigger; menus and selects work with arrow keys.
- **Focus:** always visible (see Interaction States).
- **Labels and names:** visible labels bound to inputs; an accessible name must contain its visible text (WCAG 2.5.3); icon-only controls carry `aria-label`.
- **Structure:** one `h1` per page; landmarks (`header`, `nav` with a label, `main`); `dl` only with `dt`/`dd` children; do not use ARIA tabs unless a panel exists for each tab.
- **Colour independence:** status is never conveyed by colour alone.
- **Motion:** respect `prefers-reduced-motion`; no auto-playing or flashing content.
- **Forms:** use `autocomplete` tokens on identity fields (`email`, `current-password`, `new-password`, `name`).
- **Verification:** `npm run test:e2e` (in `frontend/`; see `frontend/e2e/README.md`) runs axe at 1280 px and 390 px (including with overlays open), keyboard-focus and layout checks on every page; also run `npm run check:contrast` and `npx impeccable detect <url>`.
