# Inspection module extension: taxonomy, QA/QC workflows, and BIM digital twin

**Date:** 2026-08-26
**Status:** Draft for review. One large item (the BIM viewer) is deliberately phased and carries open decisions.
**Scope:** Extend the existing Echno inspection capability into the four-way taxonomy Anand's FRS
describes (Safety, QA/QC, Compliance, Other), deliver the QA/QC inspection workflow as the first
feature, and lay out an honest, phased path to the BIM digital-twin viewer. Source spec is the
Functional Specification Document FSD-CRB-001 (Autonomous Construction QA, QC & Safety Inspection
Robotics Platform).

This document covers the Echno data platform and web application: the inspection taxonomy, QA/QC
checklists and criteria, defect and corrective-action tracking, Non-Conformance Reports (NCRs),
reports, and the BIM viewer. The robotics stack (mission planning, navigation, SLAM, scan
acquisition) and the AI inspection engine are separate programs; this design defines the integration
points where their output lands as inspection data, and does not attempt to build them.

---

## 1. What already exists, and the design principle that follows from it

The inspection module is already a clean, self-contained package in `echno-backend`
(`org.tornotron.echno_backend.inspection`), mirrored by types and parsers in `echno-core`
(`@tornotron/echno-core/inspection`) and a feature folder in `echno-web` (`features/inspections`).
The relevant shape today:

- **`Inspection`** is a `TenantScopedEntity` with the Hibernate `orgFilter`. It carries a document
  number (`INSP` series), title, `type`, `status`, `result`, a scalar `projectId` (no cross-module
  FK, kept decoupled on purpose), scheduling and actor fields, summary counts
  (total / passed / failed check points, defects found), and a block of nullable compliance-extension
  columns (`origin`, `compliancePhase`, `riskLevel`, `resolutionOptions`, `complianceRuleRef`,
  `aiRationale`). Child collections are `checkItems` and `defects`.
- **`InspectionCheckItem`** already models a checkpoint with `category`, `checkPoint`,
  `specification`, a `CheckItemStatus`, `remarks`, `photosRequired`, a `photos` list of URLs,
  `measurement`, `expectedValue`, and `priority`. Tenancy is inherited from the parent inspection.
- **`InspectionDefect`** already models a defect with `description`, `severity`, `location`, `photos`,
  `correctiveAction`, `responsibleParty`, `targetDate`, `status` (text, defaulting to `open`), and
  `resolvedDate`.
- **Enums:** `InspectionType` (safety, quality, progress, final, structural, electrical, plumbing,
  finishing, compliance), `InspectionStatus` (scheduled, in-progress, completed, failed, passed,
  passed-with-remarks, cancelled, suggested), `InspectionResult`, `InspectionOrigin` (manual,
  ai-generated).
- **Compliance** is already built and deployed. `ComplianceRule` is deliberately global reference
  data (not tenant-scoped), keyed by `(state, projectType, code)`. `ComplianceGenerationService`
  resolves a project's state and type, asks an OpenAI-compatible model which curated rules apply, and
  materialises each decision as a `SUGGESTED`, `AI_GENERATED`, `COMPLIANCE`-type inspection.
  Critically, compliance did **not** create a parallel entity: it added nullable columns to
  `Inspection` and reused the same table, service, and DTO path.

**Design principle inherited from the compliance work:** extend the single `Inspection` table with
additive, nullable columns and a discriminator, and add new sibling tables only where a genuinely new
aggregate exists (checklist templates, NCRs, BIM models). Do not introduce JOINED inheritance or a
table-per-type split. This keeps every existing row valid, keeps the tenancy model uniform, and keeps
the module additive, which is exactly how compliance shipped without disruption.

**Storage and object handling that already exists and will be reused:**

- `FileStorageService` is an S3-compatible client (DO Spaces in production, self-hosted MinIO at the
  IITM staging) with `uploadFile`, `generateUploadUrl` (presigned PUT), `generateDownloadUrl`
  (presigned GET), and delete operations.
- `PresignedUpload` and the `/api/v1/attachment/web/presign` + `/register` endpoints already provide
  a direct-to-storage upload path that keeps large files off the API and out of the CDN (which caps
  bodies at 100 MB). This is the path the BIM upload will reuse.
- The polymorphic `Attachment` entity keys on `entityType` (string) + `entityId` (**Long**). Note the
  mismatch: `Inspection` ids are UUID, so `Attachment` cannot cleanly reference an inspection by its
  id today. Check-item and defect photos are already stored as raw URL strings, sidestepping this.
  The BIM model therefore gets its own dedicated table rather than riding on `Attachment` (section 6).

---

## 2. The inspection-type taxonomy: Inspection to {Safety, QA/QC, Compliance, Other}

The FRS organises inspections into four top-level kinds. The existing `type` enum conflates two
different axes: a top-level kind (safety, quality, compliance) and a trade or discipline (structural,
electrical, plumbing, finishing). QA/QC in the FRS needs sixteen trades as first-class, configurable
subjects (FR-QA-01 to FR-QA-16), so the two axes must be separated rather than piled into one enum.

### 2.1 Recommended model: a category discriminator plus a trade axis

Add one discriminator column and one sub-classification, both on the existing `Inspection` table:

- **`InspectionCategory`** enum (new): `SAFETY`, `QA_QC`, `COMPLIANCE`, `OTHER`. This is the
  Inspection to {Safety, QA/QC, Compliance, Other} axis Anand wants. Non-null, defaulted, indexed.
- **`InspectionTrade`** enum (new, nullable): the QA/QC construction stage or trade, one value per
  FR-QA requirement (pre-construction documentation, shuttering/formwork, reinforcement, RCC, masonry,
  plastering, waterproofing, flooring, fabrication, aluminium/UPVC, electrical fixtures, plumbing
  fixtures, sanitary fixtures, finishing, plus dimensional and progress checks). Populated only for
  `QA_QC` (and optionally `OTHER`) inspections.

Keep the existing `InspectionType` enum for backward compatibility and as a free finer label, but
`category` becomes the authoritative grouping the UI and RBAC filter on. This avoids a breaking rename
of `type` while giving the FRS taxonomy a clean home.

### 2.2 Migration and backfill

New Liquibase changelogs under `src/main/resources/db/changelog/v4.0/` (next free numbers, the
current tail is `052`), each registered in `db.changelog-master.xml`:

- `053-add-inspection-category-and-trade.xml`: add `category` (not null, default `OTHER`) and `trade`
  (nullable) columns plus an index on `category`.
- A backfill `update` in the same changeset derives `category` from the existing `type`:
  `safety` to `SAFETY`; `compliance` to `COMPLIANCE`; `quality`, `structural`, `electrical`,
  `plumbing`, `finishing`, `progress`, `final` to `QA_QC`. This is a pure data migration, no row is
  lost, and every existing compliance and manual inspection keeps working.

### 2.3 Tenancy

`category` and `trade` are plain columns on the already tenant-scoped `Inspection`, so they inherit
the `orgFilter` with no extra work. The new aggregate tables introduced later (checklist templates,
NCRs, BIM models) are each declared `implements TenantScopedEntity` with the
`@Filter(name = "orgFilter", condition = "organization_id = :organizationId")` annotation and a
`@ManyToOne Organization`, exactly as `Inspection` does. Child rows (template items, BIM elements)
inherit tenancy from their parent, the way `InspectionCheckItem` already does.

---

## 3. QA/QC inspection: the first feature

This is the FRS section 4.6 (FR-QA) plus the reporting and NCR pieces (FR-REP) that give QA/QC its
lifecycle. Much of the leaf data model already exists; the gaps are a reusable checklist library, a
proper NCR workflow, and the RBAC roles the FRS defines.

### 3.1 Checklists and criteria (FR-QA-01..16, FR-AI-07)

A QA Engineer defines the criteria once and reuses them across inspections. Today an inspection's
check items are entered ad hoc. Introduce a reusable template library:

- **`ChecklistTemplate`** (new, tenant-scoped): keyed by `InspectionTrade`, with a name, description,
  and `active` flag. One template per trade per org, versioned by an integer.
- **`ChecklistTemplateItem`** (new, child of template): `category`, `checkPoint`, `specification`,
  `expectedValue`, a new `acceptanceCriterion` and `tolerance` (the measurable form FR-AI-07 asks
  for, for example rebar spacing 150 mm +/- 10 mm, or concrete cover 40 mm minimum), `photosRequired`,
  and `lineOrder`.

Follow the compliance precedent for the starter content: ship a **global seed** of default per-trade
templates (the same way `ComplianceRule` seeds 21 statutory rules), and let each org copy and edit
them into its own tenant-scoped templates. This gives a working system on day one without forcing
every tenant to author checklists from scratch.

When an inspection is created for a trade, `InspectionService` instantiates the matching template's
items into `InspectionCheckItem` rows (a straightforward copy, so subsequent template edits do not
mutate historical inspections). This is a small extension to the existing create path.

Extend `InspectionCheckItem` with `acceptanceCriterion`, `tolerance`, and a computed `deviation`
(measurement minus expected), which serves FR-QA-15 (dimensions, alignment, levelness against
BIM/drawing references). Add a nullable `bimElementGuid` here now so the later BIM phase has the link
column already in place (section 6).

### 3.2 Pass / fail / defect capture

The leaf model is already close. `CheckItemStatus` gives per-checkpoint pass/fail/pending, and
`InspectionDefect` captures the defect with photos, corrective action, and a target date. Two
tightening changes:

- Promote `InspectionDefect.severity` and `InspectionDefect.status` from free text to enums
  (`DefectSeverity`: critical, major, minor; `DefectStatus`: open, in-progress, resolved, verified),
  matching the string unions the web contract already documents. This is a validation and reporting
  win, not a new capability.
- The summary counts on `Inspection` (total / passed / failed / defects) are already recomputed on
  every save; no change needed.

### 3.3 Corrective action and Non-Conformance Reports (FR-REP-04..08)

The FRS makes the NCR a first-class object with its own assignment and closure workflow and a strict
RBAC boundary. The defect's inline `correctiveAction` field is not enough. Add:

- **`Ncr`** (new, tenant-scoped): `ncrNumber` (its own document series, for example `NCR`), a
  `NcrType` (`QUALITY` or `SAFETY`), scalar links to the originating `inspectionId` (UUID) and
  optional `defectId`, `description`, `severity`, an assigned `siteEngineerId`, `targetDate`,
  `raisedById`, `closedById`, and an `NcrStatus` lifecycle:
  `OPEN -> ASSIGNED -> CORRECTIVE_ACTION_COMPLETE -> VERIFIED -> CLOSED` (with `REJECTED`/`REOPENED`
  as needed). This directly encodes FR-REP-05 (QA Engineer or Safety Officer assigns to a Site
  Engineer), FR-REP-06 (Site Engineer marks corrective work complete for re-verification), and
  FR-REP-07 (only QA Engineer or Safety Officer closes).

Corrective actions for v1 stay on the defect (`correctiveAction`, `responsibleParty`, `targetDate`,
`resolvedDate` already exist); the NCR is the workflow and accountability wrapper over them. A
punch-list view (FR-REP-08) is a query over open NCRs plus outstanding defects, not a new entity.

### 3.4 Status lifecycle

`InspectionStatus` is already rich enough (scheduled, in-progress, completed, passed,
passed-with-remarks, failed, cancelled, suggested). The new lifecycle machinery lives on the NCR, not
the inspection. The inspection service should gain a small guarded transition method rather than
setting status blindly, so an inspection cannot jump illegally between states.

### 3.5 RBAC (FR chapter 3, NFR-08)

The FRS defines seven roles; Echno's `@orgSecurity.hasAnyOrgRoleForCurrentTenant(...)` today limits
inspection endpoints to `system-admin` and `project-manager`. Map the FRS roles onto org roles and
widen the guards per action:

| FRS role | Inspection module authority |
|---|---|
| QA Engineer | Define QA/QC checklist templates and criteria; review quality defects; create, assign, close **quality** NCRs |
| Safety Officer | Define safety criteria; review safety violations; create, assign, close **safety** NCRs |
| Site Engineer | View assigned NCRs; mark corrective action complete; cannot close |
| Project Manager | Schedule and approve missions; full read on QA/QC/safety; administer integrations; approval visibility on NCRs |
| Consultant | Read inspection results and reports; approval on selected QA sign-offs |
| Client | Read-only summary dashboards and closed reports |
| Robotics Operator | Create and launch inspection missions (robotics side) |

Concretely: replace the single two-role guard with per-action `@PreAuthorize` expressions keyed to
these roles, and add a defect-type check so a quality NCR cannot be closed by a Safety Officer and
vice versa. This is the same `@orgSecurity` pattern already in use.

### 3.6 Reports (FR-REP-01..03, 10..13)

Echno already generates PDF reports. Add a QA/QC inspection report and an NCR report to that path,
plus annotated defect images (FR-REP-10, the defect already stores photo URLs and a location). Defect
density and safety heatmaps (FR-REP-11) depend on element or spatial coordinates and belong with the
BIM phase (section 6), not the first feature.

### 3.7 Concrete change list for the first feature

- **echno-backend:** new enums `InspectionCategory`, `InspectionTrade`, `DefectSeverity`,
  `DefectStatus`, `NcrType`, `NcrStatus`; new entities `ChecklistTemplate`, `ChecklistTemplateItem`,
  `Ncr`; new columns on `Inspection` (`category`, `trade`) and `InspectionCheckItem`
  (`acceptanceCriterion`, `tolerance`, `bimElementGuid`); Liquibase changelogs `053`+ with backfill
  and template seed; services `ChecklistTemplateService`, `NcrService`; controllers
  `ChecklistTemplateControllerWeb`, `NcrControllerWeb`; extend `InspectionService` to instantiate
  templates and guard transitions; widen `@PreAuthorize` guards.
- **echno-core:** add `InspectionCategory`/`InspectionTrade`/`DefectSeverity`/`DefectStatus`/
  `NcrType`/`NcrStatus` enums and parsers; `ChecklistTemplate`, `ChecklistTemplateItem`, `Ncr` types,
  parsers, and services; publish a new `@tornotron/echno-core` minor version (this is the coupling
  point: web cannot consume the new shapes until core is released to GitHub Packages).
- **echno-web:** extend `features/inspections` (category and trade selectors, criteria display,
  deviation); new `features/checklist-templates` and `features/ncr` feature folders with hooks; nav
  metadata entries; role-gated views.

---

## 4. Safety and Other (near-term follow-on)

Safety (FR-SAF-01..12) reuses the Phase 1 machinery almost entirely: safety checklist templates keyed
to hazard categories (PPE, scaffolding, edge protection, floor openings, electrical, housekeeping,
fire, lifting, crane, restricted-area, vehicle-pedestrian), safety-type NCRs, and the Safety Officer
role. No new aggregate is needed beyond seeding safety templates and pointing the `SAFETY` category at
them. `OTHER` is the catch-all category with no mandated trade, and needs nothing new. Both are small
increments once QA/QC is proven, so they are Phase 2, not a separate build.

---

## 5. Where robotics and AI plug in (integration seams, not this build)

The FRS describes autonomous missions and an AI inspection engine that produce inspection data. This
design keeps them at arm's length behind two seams, so the data platform can be built and used with
manual inspections first and robot/AI feeds added later without a rework:

- **Mission to inspection:** a completed mission (FR-MP, FR-NAV, FR-ACQ) creates or fills an
  inspection, exactly as the compliance flow already creates `AI_GENERATED` inspections today. The
  `InspectionOrigin` enum already distinguishes `MANUAL` from `AI_GENERATED`; a `ROBOT` origin can be
  added when that feed is real.
- **AI findings to check items and defects:** the AI inspection engine (FR-AI) writes
  `InspectionCheckItem` results and `InspectionDefect` rows against the criteria, with its confidence
  and rationale, reusing the `aiRationale` pattern already on the entity. Captured images and scans
  land in the object store through the existing presigned-upload path and are referenced by URL, as
  photos already are.

Nothing in the QA/QC build blocks on the robots or the AI engine existing.

---

## 6. BIM digital-twin viewer

This is the large new piece and the one to be honest about. The goal: an interactable 3D building
model in `echno-web` where clicking an element reveals its inspections, defects, and corrective
actions, with the model aligned to drawing dimensions. This realises FR-DT-05 (a complete inspection
history linked to each element in the digital twin) and FR-INT-01 (integrate BIM models in IFC and
Revit for as-built comparison).

### 6.1 Honest complexity statement

A production BIM viewer is not a component drop-in. It is three separate hard problems: parsing and
rendering large IFC models in a browser, a conversion pipeline to make those models streamable, and
survey-grade alignment of scan and drawing coordinates to the model. The first is a solved,
open-source problem. The second is real engineering but tractable. The third (aligning to drawing
dimensions at the ±2 cm / ±5 mm tolerances the NFRs quote) is a robotics and surveying problem, not a
viewer problem, and must be scoped out of the viewer MVP. The MVP delivers element-linked inspection
data on a navigable model; it does not deliver survey-accurate overlay.

### 6.2 Technology recommendation

**Recommended: That Open Engine (the successor to IFC.js), i.e. `web-ifc` plus
`@thatopen/components` and `@thatopen/fragments`, rendering on three.js.** Reasons:

- It is the mature, actively developed open-source stack for IFC in the browser. `web-ifc` is a
  WebAssembly IFC parser; the Fragments format is its compact, streamable geometry representation;
  the components layer gives selection, highlighting, and property display on top of three.js.
- Self-hosted and cost-free, which fits Echno's per-client production model (one DO instance per
  client) and the self-hosted MinIO object store. There is no per-seat or per-model cloud viewer fee.
- IFC GlobalId (the IFC GUID) is preserved through parsing, which is exactly the key needed to map an
  element to its inspections.

**Alternative considered: Autodesk Platform Services (APS, formerly Forge) Viewer.** It renders Revit
natively and aligns with FR-INT-02 (Autodesk Construction Cloud integration), but it is proprietary,
carries cloud translation and viewer costs, and pushes model data through Autodesk's cloud. The
recommendation is to build on That Open for the self-hosted IFC path, and keep an APS adapter as a
later option specifically for native Revit and ACC-sourced models, since FR-INT-01 asks for both IFC
and Revit and Revit is not natively an IFC format (it needs an IFC export or an APS translation).

### 6.3 Data model

- **`BimModel`** (new, tenant-scoped): per project, with `projectId`, a `name`/`discipline`, the
  object-store `storageKey` of the source IFC, the `storageKey` of the derived Fragments file, a
  `unit` and a `georeference` transform (origin offset, rotation, scale) for later alignment, upload
  metadata, and `active`. It gets its own table rather than riding on the polymorphic `Attachment`
  because `Attachment.entityId` is a `Long` and inspections and BIM models are UUID-keyed.
- **`BimElement`** (optional, tenant-scoped, child of `BimModel`): an index of `guid` (IFC GlobalId),
  `ifcType`, `storey`, and a bounding box, materialised during conversion. This is an optimisation so
  the backend can answer "which inspections touch this element" without re-parsing the IFC; for an
  MVP the link can live purely on the check item and defect via `bimElementGuid` (added in section
  3.1) and this table can be deferred.
- **Element to inspection mapping:** `InspectionCheckItem.bimElementGuid` and a matching column on
  `InspectionDefect` carry the IFC GUID. Clicking an element in the viewer calls a new endpoint
  (`GET /api/v1/bim/web/element/{guid}/inspections`) that returns every inspection, defect, and NCR
  bound to that GUID in the current tenant. This is the concrete realisation of FR-DT-05.

### 6.4 Storage and serving pipeline

- **Upload:** the source IFC is uploaded direct-to-storage through the existing `PresignedUpload`
  path (IFC files run from tens of megabytes into gigabytes, so they must not pass through the API),
  then registered against a new `BimModel` row.
- **Conversion:** a background job converts IFC to Fragments. IFC parsing is CPU and memory heavy, so
  this runs off the request thread, ideally on a lab build node (`.116`/idle lab node) or a dedicated
  worker, not inline. The Fragments output is written back to the object store. This is a real
  pipeline step, not a library call, and it is the main new operational cost of the feature.
- **Serving:** the browser streams the Fragments file from a presigned GET URL and renders it
  client-side. Element property and inspection lookups hit the Echno API by GUID.

### 6.5 Web module

A new `features/bim-viewer` in `echno-web`: a client component wrapping a three.js canvas and the That
Open components, lazy-loaded (the WASM parser and three.js are heavy and must not enter the main
bundle), placed under the digital-twin/inspection area and behind a feature flag. Selecting an element
opens a side panel that lists its inspections, defects, and NCRs, each linking back into the existing
inspection and NCR views. Next.js 16 and React 19 handle this fine as a dynamically imported
client-only module; server-side rendering is disabled for the canvas.

### 6.6 Dimension and drawing alignment (deferred within the BIM phase)

IFC geometry is in model coordinates; engineering drawings and robot-captured scans are in their own
frames. Aligning them needs a per-model transform (the `georeference` field on `BimModel`) plus a
calibration step that ties known survey points across the frames. Doing this to the FRS tolerances is
a surveying and robotics calibration task. The BIM MVP therefore ships with element-linked inspection
data and manual model placement; automated drawing-dimension alignment and as-built-vs-BIM deviation
overlay (FR-QA-15/16, FR-DT-04) are a later increment tied to the scan-acquisition program.

---

## 7. Phased build plan

- **Phase 0, taxonomy foundation (small, low risk).** `InspectionCategory` discriminator with
  backfill, `InspectionTrade` axis, defect severity/status promoted to enums. No behaviour change for
  existing rows. Unblocks everything else.
- **Phase 1, QA/QC first feature (the headline deliverable).** Checklist template library with
  per-trade global seed and tenant copies; template instantiation into inspections; pass/fail/defect
  capture (mostly existing); NCR entity, lifecycle, and RBAC (QA Engineer, Safety Officer, Site
  Engineer); QA/QC and NCR reports; annotated defect images. End-to-end and usable with manual
  inspections, no BIM and no robots required.
- **Phase 2, safety and other parity.** Safety checklist templates keyed to hazard categories, safety
  NCRs, Safety Officer role wiring. Reuses Phase 1 machinery; small.
- **Phase 3, BIM viewer MVP.** `BimModel` upload and IFC-to-Fragments conversion pipeline; That Open
  viewer as a lazy-loaded `features/bim-viewer` module; element-to-inspection linkage by IFC GUID
  (click an element, see its inspections/defects/NCRs). No survey alignment yet.
- **Phase 4, digital-twin depth (long horizon, robotics-coupled).** Drawing-dimension alignment and
  georeferencing, as-built-vs-BIM deviation, defect and safety heatmaps (FR-REP-11), progress-vs-BIM
  (FR-QA-16), and the robot/scan and AI-engine feeds from section 5.

Phases 0 to 2 are autonomous Echno work with no external dependency. Phase 3 introduces the
conversion pipeline as a new operational surface. Phase 4 depends on the robotics and AI programs and
should not be committed to a date from here.

---

## 8. Fit with the modular / plugin direction

Echno has no plugin or module registry yet, so "module" here means a clean package boundary, not a
runtime plugin. The inspection package is already a strong first candidate for that boundary: it uses
scalar cross-references with no foreign keys, additive columns, and a single nav entry, so it can be
enabled or disabled per tenant without touching other domains. Two guidelines keep it that way as it
grows:

- Keep checklist templates, NCRs, and the BIM viewer inside the inspection module boundary, exposed
  under one nav section and gated by a single feature flag, so a client's production instance (the
  per-client DO model) can turn the whole capability on or off as a unit.
- Gate the BIM viewer behind its own flag additionally, because its heavy WASM and 3D footprint and
  its storage and conversion cost make it a capability some clients will not want loaded at all.

If and when a real module registry lands, inspection is the natural pilot: it already behaves like a
self-contained module, and this extension does not compromise that.

---

## 9. Open questions and risks

- **BIM data volume and cost.** IFC sources run to gigabytes and Fragments files stay large. Per-client
  object-store footprint and egress, browser memory limits, and the CPU cost of conversion are real.
  Needs level-of-detail streaming and a worker for conversion; budget storage per client.
- **IFC tooling maturity and Revit.** That Open is active but its API moves quickly, so pin versions
  and expect churn. Revit is not natively IFC; FR-INT-01 asking for both IFC and Revit means Revit
  arrives either via an IFC export step or via an APS adapter, which is a separate integration.
- **Dimension alignment tolerances.** The ±2 cm / ±5 mm targets (NFR-02/03) are a surveying and
  robotics calibration problem, not something the viewer can deliver alone. Scope the viewer MVP to
  element-linked data and manual placement, and tie automated alignment to the scan program.
- **Attachment UUID mismatch.** The polymorphic `Attachment.entityId` is a `Long`, but inspections and
  BIM models are UUID-keyed. Photos already dodge this by storing raw URLs; BIM gets a dedicated table.
  If richer attachment linkage to inspections is wanted later, this mismatch has to be resolved.
- **Template ownership.** Compliance rules are global; QA checklist templates are recommended as
  tenant-owned with an optional global starter seed. Confirm this split, since a purely global library
  would stop a QA Engineer from tailoring criteria per org, which FR-AI-07 implies they must.
- **Core release coupling.** Web cannot consume any new shape until `@tornotron/echno-core` is
  published to GitHub Packages, so every phase lands as backend + core release + web, in that order.
- **Scope boundary.** This design is the data platform, QA/QC workflows, and BIM viewer. The robotics
  stack and the AI inspection engine are large separate programs; here they are integration seams only.
