# QA/QC extension, note 4 of 4: the inspection ontology as data

**Date:** 2026-09-12
**Status:** Design for review. No code lands with this note. ClickUp `14zdkkvrf2b`; roadmap
section 4.
**Scope:** `InspectionTrade` (a fixed enum of 16 values) becomes a seeded, organisation-extensible
table with the trades the roadmap lists and the code lacks (tiling, painting, ceilings, doors and
windows, fire systems). Templates and inspections reference a trade by id. The enum stays as a
compatibility shim for one release. The same pattern gives an element-type catalogue. The
`Element > Rule > Item > Evidence > Observation > Defect > Corrective action` chain from roadmap
section 4 is written down as the reference model and mapped onto the entities.

Independent of notes 1, 2 and 3. Note 1's `element_type` slug gains validation from this note's
catalogue once both exist.

---

## 1. Motivation

`InspectionTrade` has 16 values: pre-construction documentation, shuttering/formwork,
reinforcement, RCC, masonry, plastering, waterproofing, flooring, fabrication, aluminium/uPVC,
electrical fixtures, plumbing fixtures, sanitary fixtures, finishing, dimensional check,
progress check. `ChecklistTemplate` is unique per `(organization, trade)`,
`StarterChecklistTemplate` is unique per `trade`, and `Inspection.trade` filters and reports.

Roadmap section 4 lists the trades the ontology must cover. Five are absent from the enum:
tiling, painting, ceilings, doors and windows, fire systems. Adding them to the enum is a
one-line change and misses the point of the section, which is that a customer adds a trade
("precast erection", "facade glazing") without a release, and that the trade is one node in a
model that also names element types and rules. An enum cannot be extended by a tenant.

## 2. Reference model

Roadmap section 4:

```
Construction Element > Inspection Rule > Inspection Item > Evidence > Observation > Defect > Corrective action
```

Mapped onto Echno:

| Roadmap node | Echno entity | Note |
|---|---|---|
| Construction Element | `SpatialNode` with `level = ELEMENT`, typed by `element_type` | note 1; catalogue here |
| Inspection Rule | `ChecklistTemplate` for a trade, plus its applicability (which element types, which project types) | this note adds applicability |
| Inspection Item | `ChecklistTemplateItem` (definition) and `InspectionCheckItem` (result) | exists; definition and result are already separate tables |
| Evidence | attachments on inspection, check item, defect and observation | exists |
| Observation | `Observation` | note 2 |
| Defect | `InspectionDefect` and `Ncr` | exists |
| Corrective action | `Ncr.correctiveActionRemarks`, `siteEngineerId`, `targetDate`; `InspectionDefect.correctiveAction` | exists; reinspection in note 3 |

The trade is the axis that groups rules: a template belongs to a trade, and a trade belongs to a
group (structural, finishes, MEP, fire, general). Roadmap section 4's own grouping ("Structural
works" containing reinforcement, formwork, concrete) is a group, and "MEP" is a group holding
the three fixture trades the enum already has.

So the ontology as data is three catalogues, all seeded, all org-extensible: trades, trade
groups (a column on trade, no table), and element types. Rules stay templates.

## 3. Design

### 3.1 Two tables per catalogue, following the starter-template pattern

The codebase already solves "global seed plus tenant ownership" once: `StarterChecklistTemplate`
is global reference data (`@GlobalReferenceData`, no organisation), and `ChecklistTemplate` is
the tenant-owned copy. The trade catalogue uses the same split:

- `trade_catalogue`: global, seeded by Liquibase, `@GlobalReferenceData`. Columns: `code`
  (slug, unique), `name`, `group_code`, `description`, `sort_order`, `active`. The 16 enum slugs
  are the first 16 codes, unchanged, so a client that sends `"masonry"` today sends the same
  string tomorrow.
- `inspection_trades`: tenant-owned, `TenantScopedEntity`, `orgFilter`. Columns: `id`,
  `organization_id`, `code` (unique per org), `name`, `group_code`, `description`, `sort_order`,
  `active`, `catalogue_code` (nullable; set for rows copied from the catalogue, null for
  org-defined trades), `legacy_enum` (nullable; the enum constant name for the 16 seeded rows,
  dropped with the shim).

Every reference from a tenant-owned row (`ChecklistTemplate`, `Inspection`) points at
`inspection_trades.id`. `StarterChecklistTemplate.trade` becomes `trade_code`, a catalogue code,
and copying a starter template into an org resolves the code to that org's trade row. This keeps
the tenant filter honest: no tenant row references a global row by id.

The org copy is created by `TradeService.ensureOrgTrades(organization)`: for each active
catalogue code with no org row, insert one. It runs lazily on the first trade read for an
organisation and once in the migration for every existing organisation, so it is idempotent by
`(organization_id, catalogue_code)`. New catalogue codes added in a later release reach existing
orgs the same way.

A single table with a nullable `organization_id` was considered and rejected: the `orgFilter`
condition `organization_id = :organizationId` hides global rows, and `ModuleBoundaryTest`
requires module entities to be tenant scoped or owned children. Two tables cost one extra copy
step and keep every existing guard.

### 3.2 Seed

Catalogue rows at first release (code, name, group):

```
structural : shuttering-formwork, reinforcement, rcc, fabrication, pre-construction-documentation,
             dimensional-check
masonry    : masonry, plastering, waterproofing
finishes   : flooring, tiling*, painting*, ceilings*, finishing
openings   : aluminium-upvc, doors-windows*
mep        : electrical-fixtures, plumbing-fixtures, sanitary-fixtures
fire       : fire-systems*
general    : progress-check
```

Starred codes are new (21 codes in total). Group codes are a string column, seeded with those
seven; an org may use any group string. "Concrete" from the roadmap list maps to `rcc`; "MEP" is
a group; "Structural works" is a group.

Starter checklists for the five new trades are seeded with a short generic item set in the same
changeset so a new org sees a usable template for each; the content is a first draft for Anand
or a site engineer to refine in the checklist builder.

### 3.3 Element types

`element_type_catalogue` (global, seeded) and `org_element_types` (tenant-owned), same columns
as the trade pair minus `legacy_enum`. Seed: column, beam, slab, wall, staircase, footing,
lintel, door, window, ceiling, floor-finish, wall-finish, pipe-run, duct, cable-tray, fire-door,
sprinkler-branch. `SpatialNode.element_type` (note 1) stays a slug string; `SpatialNodeService`
validates it against the org's active element types once this note lands. No foreign key is
added, so note 1 and this note deploy in either order.

### 3.4 Rule applicability

`ChecklistTemplate` gains `applicable_element_types` (JSON array of element-type codes, null
meaning any) and `applicable_project_types` (JSON array of `ProjectType`, null meaning any).
The inspection form uses them to suggest templates once a spatial node with an element type is
picked: "column in structural works" narrows to the RCC, reinforcement and formwork templates.
This is the "Inspection Rule" of the reference model, and it stays a template with a filter
instead of becoming a new entity.

### 3.5 The enum shim, one release

Release N (this note):

- Entities gain `trade_id` (uuid, nullable, fk `inspection_trades`) beside the existing `trade`
  enum column on `inspections` and `checklist_templates`. The unique constraint on
  `checklist_templates` becomes `(organization_id, trade_id)`; the old one on `trade` is dropped.
- Writes accept either `tradeId` or `trade` (slug). A slug resolves to the org's trade row by
  code; a slug that is one of the 16 also sets the enum column so old readers keep working. A
  slug with no row is a 422.
- Reads return both `trade` (the slug string, which is what `@JsonValue` has always put on the
  wire) and `tradeId`, plus `tradeName` and `tradeGroup`. For an org-defined trade with no enum
  equivalent, `trade` returns its code string; on the wire nothing changes shape because the
  enum was always a string.
- `InspectionTrade` stays in the code, marked deprecated, used only by the resolver.
- Filters accept `trade` and `tradeId`.

Release N+1:

- Drop the `trade` enum column from both tables and the `legacy_enum` column; delete
  `InspectionTrade`; `trade` on the wire becomes a plain string field populated from the row's
  code. Web is unaffected because it already treats the field as a string by then.

## 4. Data model

```
trade_catalogue                          (global, @GlobalReferenceData)
  code varchar(50) pk, name varchar(200) not null, group_code varchar(50) not null,
  description text null, sort_order int not null, active boolean not null

inspection_trades                        (tenant)
  id uuid pk, organization_id bigint not null, code varchar(50) not null,
  name varchar(200) not null, group_code varchar(50) not null, description text null,
  sort_order int not null default 0, active boolean not null default true,
  catalogue_code varchar(50) null, legacy_enum varchar(50) null,
  created_at, updated_at, created_by, updated_by
  unique (organization_id, code)
  unique (organization_id, catalogue_code) where catalogue_code is not null

element_type_catalogue                   (global)
  code pk, name, group_code, description, sort_order, active

org_element_types                        (tenant)
  id, organization_id, code, name, group_code, description, sort_order, active, catalogue_code
  unique (organization_id, code)

checklist_templates
  + trade_id uuid null fk inspection_trades
  + applicable_element_types jsonb null
  + applicable_project_types jsonb null
  unique (organization_id, trade_id)        replaces uk_checklist_template_trade

starter_checklist_templates
  trade varchar(50)  ->  trade_code varchar(50) fk trade_catalogue(code)

inspections
  + trade_id uuid null fk inspection_trades   + index
```

## 5. API

```
GET    /api/v1/inspections/web/trades                     org trades, active by default, ?includeInactive
POST   /api/v1/inspections/web/trades                     { code, name, groupCode, description?, sortOrder? }
PATCH  /api/v1/inspections/web/trades/{id}                { name?, groupCode?, description?, sortOrder?, active? }
                                                          catalogue-copied rows: code immutable, may be deactivated
GET    /api/v1/inspections/web/trades/catalogue           global catalogue, read only
GET    /api/v1/inspections/web/element-types              org element types
POST   /api/v1/inspections/web/element-types
PATCH  /api/v1/inspections/web/element-types/{id}
```

Trade and element-type management needs `inspections.checklists.define`, the same authority as
the checklist builder. Deactivation, never deletion: an inactive trade disappears from pickers
and stays valid on the rows that reference it. Checklist template and inspection endpoints gain
`tradeId`, `tradeName`, `tradeGroup`, `applicableElementTypes`, `applicableProjectTypes`.
OpenAPI snapshot regenerated.

## 6. Migration

One module changeset file under `db/changelog/modules/inspections/`, in this order:

1. Create `trade_catalogue`, `element_type_catalogue`; `loadData` from CSV files beside the
   changelog (codes are the stable key).
2. Create `inspection_trades`, `org_element_types`.
3. For every existing organisation, insert org rows from the catalogues (SQL changeset,
   idempotent on `(organization_id, catalogue_code)`).
4. Add `trade_id` to `checklist_templates` and `inspections`; backfill by joining
   `inspection_trades.legacy_enum` to the existing `trade` enum column within the same
   organisation. Every existing row resolves, since every current value is one of the 16.
5. Swap the unique constraint on `checklist_templates`.
6. Rename `starter_checklist_templates.trade` to `trade_code`; the existing values are already
   the slugs; add the foreign key.
7. Seed starter templates for the five new trades.

Release N+1 ships the drop changeset (section 3.5). The QA seed backup restores rows with the
enum column, which step 4 backfills, so `make seed` needs no change.

## 7. Core and web impact

`echno-core`: `InspectionTrade` today is a union of 16 string literals; it widens to `string`
with the 16 kept as named constants for callers that still switch on them. New
`types/inspection/trade.ts` (`InspectionTradeRow`, `ElementType`) and a `trade-service.ts`.
Optional `tradeId`, `tradeName`, `tradeGroup` on template and inspection responses. The
widening is the only non-additive change in the four notes: any web `switch` over the union
with an exhaustive check gets a default branch.

`echno-web`:

- Checklist builder gains "Trades" and "Element types" management tabs: list, add, rename,
  deactivate, grouped by `groupCode`.
- Trade pickers on the inspection form, template form and filters read from the org list
  (today a hard-coded array); group headings from `groupCode`.
- Template form gains applicability pickers (element types, project types).

## 8. Definition of done

- A fresh org sees 21 trades in seven groups and the seeded element types; an admin adds a
  custom trade, it appears in the inspection form and can back a checklist template.
- Existing inspections and templates carry the right `tradeId` after migration; the old `trade`
  string still round-trips through create, update and filter.
- Starter templates copy into the org with the org's trade id; the five new trades have a
  starter template.
- A template with applicability set is suggested when an element of a matching type is picked.
- Changesets apply on fresh and seeded databases; `ddl-auto: validate` and
  `ModuleBoundaryTest` pass (the catalogue tables carry `@GlobalReferenceData`); OpenAPI
  snapshot updated; core published with the widened type; web pickers merged.
- The N+1 drop changeset is written and filed as a follow-up issue at merge time.

## 9. Open questions, defaulted

| Question | Default taken |
|---|---|
| Trade groups as a table or a column? | Column (`group_code`). Seven seeded strings; a table adds nothing yet. |
| Can an org rename a catalogue-copied trade? | Rename yes, code no, deactivate yes. |
| Delete a trade? | Never; deactivate. |
| Roadmap "Concrete" and "Structural works" | `rcc` and the `structural` group; no new codes. |
| Starter checklist content for the five new trades | Short generic draft seeded; content to be refined by Anand or a site engineer in the builder. |
| Element type catalogue scope | Seventeen common types; orgs extend. |
| Applicability enforcement | Suggestion only; any template may still be used on any element. |
| Where the enum shim ends | One release after this lands; drop changeset filed at merge. |

## 10. Sequencing and work packages

Independent of notes 1, 2 and 3; runs in parallel with all of them. Package C touches note 1's
`SpatialNodeService` and waits for note 1 package A only for that one validation hook; the rest
of C does not.

- **A. echno-backend: "Trade catalogue and org trade tables: entities, seed, org copy, migration and backfill"**
  Catalogue and org entities, `ensureOrgTrades`, changeset steps 1 to 6, starter template
  `trade_code`.
- **B. echno-backend: "Trade id on templates and inspections with the enum compatibility shim"**
  `trade_id` writes and reads, slug resolver, filters, unique constraint swap, deprecation.
  Depends on A.
- **C. echno-backend: "Element type catalogue, org element types and template applicability"**
  Tables, seed, endpoints, `applicable_*` columns, spatial node validation hook.
- **D. echno-backend: "Starter checklists for tiling, painting, ceilings, doors and windows, fire systems"**
  Seed content (changeset step 7). Depends on A.
- **E. echno-core: "Widen `InspectionTrade` to string; trade and element-type types and service"**
  Depends on B and C for the contract.
- **F. echno-web: "Trade and element-type management tabs; org-driven trade pickers; template applicability"**
  Depends on E.
