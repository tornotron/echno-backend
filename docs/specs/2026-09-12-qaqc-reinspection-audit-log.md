# QA/QC extension, note 3 of 4: reinspection record and audit log

**Date:** 2026-09-12
**Status:** Design for review. No code lands with this note. ClickUp `14zdkkvrf2b`; roadmap
sections 3.1, 3.2 and 24.
**Scope:** (a) A `Reinspection` record that links an NCR or defect to the new `Inspection` that
re-checks it and stores the outcome; (b) an append-only event log for inspections,
observations, defects, NCRs and reinspections that records who did what, when, and the before
and after of each change, so the audit history becomes a product feature in place of a pair of
timestamps.

Independent of notes 1, 2 and 4. Note 2 (Observation) records through this note's event
recorder once both exist.

---

## 1. Motivation

The NCR life cycle already runs `OPEN > ASSIGNED > CORRECTIVE_ACTION_COMPLETE > VERIFIED >
CLOSED` with `REJECTED` and `REOPENED` side paths, and `verified_by_id`, `verified_at`,
`verification_remarks` on the row. What is missing is the inspection that produced the
verification. A verifier ticks "verified" and the system has no record of which check items were
re-run, what was seen, or how many attempts it took. Roadmap 3.1 lists Reinspection as its own
step, and section 3.2 lists it as an entity.

For history, every entity carries `created_at` and `updated_at` and some carry by-ids. A
disputed NCR can show when it last changed and nothing else. Roadmap section 24 asks that every
observation remain traceable to its source, with timestamp, location, model version and the
human decision preserved, and 3.1 ends the lifecycle with "complete audit history".

## 2. What core already has for auditing

Checked before designing anything:

- `finance/ledger/domain/BaseEntity`: a `@MappedSuperclass` with Spring Data auditing
  (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`) and `@Version`. Used by
  the finance ledger only.
- No Hibernate Envers dependency, no `@Audited`, no `_AUD` tables.
- No generic event or history table; no `@EntityListeners` outside the finance base class.
- `ApplicationEventPublisher` is unused in `modules/inspections`.

Decision: reuse the Spring Data auditing pattern for `created_by` / `updated_by` on every new
QA/QC table (cheap, consistent with finance), and build the event log as explicit domain events
written by the services. Envers is rejected for three reasons: it snapshots whole rows per
transaction, which gives a diff per table and no timeline across inspection, item, defect and
NCR; it has never been exercised against CockroachDB in this codebase; and its output is for
engineers, while roadmap 3.1 wants the history shown to site staff and used in disputes. A
domain event with a readable type and a small before/after is what the product needs.

## 3. Design: reinspection

### 3.1 Record

`Reinspection`, table `inspection_reinspections`, tenant scoped, one row per attempt:

- `ncr_id` (nullable) and `defect_id` (nullable), at least one set; the originating
  non-conformance.
- `original_inspection_id`: the inspection that found it.
- `reinspection_inspection_id`: the new `Inspection` created for the re-check, unique (one
  inspection serves one reinspection attempt).
- `sequence`: 1 for the first attempt on that NCR or defect, 2 for the next, and so on.
- `requested_by_id`, `requested_at`, `assigned_inspector_id`, `target_date`.
- `outcome`: `PENDING | PASSED | FAILED`, `outcome_by_id`, `outcome_at`, `remarks`.

### 3.2 Flow

1. From an NCR in `CORRECTIVE_ACTION_COMPLETE` (or a defect in `RESOLVED`), the verifier
   chooses "schedule reinspection". The service creates the record and a new `Inspection`:
   same project, type, category, trade and spatial node as the original; title
   "Reinspection {sequence} of {ncrNumber}"; `origin = MANUAL`; check items copied from the
   original's `FAILED` items with status reset to `PENDING` (all items on request); inspector
   as assigned; status `SCHEDULED`.
2. The inspector runs it through the normal inspection flow.
3. Completing the reinspection inspection sets the outcome: `PASSED` when every copied item
   passed (or the inspector records pass explicitly), `FAILED` otherwise, with remarks.
4. NCR transition: `PASSED` allows `CORRECTIVE_ACTION_COMPLETE > VERIFIED`, and the verify call
   carries `reinspectionId`, which fills `verified_by_id`/`verified_at` from the outcome.
   `FAILED` moves the NCR to `REJECTED` (already an allowed next state), from which it goes back
   to `ASSIGNED` and a new attempt gets `sequence + 1`.
5. Defect-only path: the same, driving `DefectStatus RESOLVED > VERIFIED`.

`NcrStatus`'s transition map does not change. What changes is that `verify` accepts an optional
`reinspectionId` and, when given, requires that record to be `PASSED` and to belong to the same
NCR (409 otherwise). Verification without a reinspection stays allowed this release (defaulted
below) and is recorded as its own event type so it can be reported on.

## 4. Design: event log

### 4.1 Shape

`InspectionEvent`, table `inspection_events`, append-only:

- `subject_type` (`INSPECTION | CHECK_ITEM | DEFECT | NCR | OBSERVATION | REINSPECTION`) and
  `subject_id`.
- `inspection_id`, nullable, denormalised so one query returns the whole timeline of an
  inspection including its items, defects, NCRs and reinspections.
- `event_type`: a dot-namespaced string constant from `InspectionEventType`
  (`inspection.status.changed`, `check_item.result.recorded`, `defect.created`,
  `ncr.assigned`, `ncr.verified`, `ncr.verified.without_reinspection`, `observation.reviewed`,
  `reinspection.scheduled`, `reinspection.outcome.recorded`, `evidence.attached`, and so on).
  Strings so a new type is a constant, never a migration.
- `actor_type` (`USER | DEVICE | AI | SYSTEM`) and `actor_id` (employee id, device id, model
  name, or job name). Resolved from the security context; the machine intake and the
  compliance job set `DEVICE` and `AI` explicitly.
- `occurred_at`, `before` (JSON), `after` (JSON), `note`, `request_id`.

`before`/`after` hold only the fields that changed, as the DTO names the web app already knows,
so the timeline renders "status: assigned to corrective-action-complete" without a mapping
table. Bulk updates that touch many fields store the full changed set.

### 4.2 Recording

A component `InspectionEventRecorder` with one method:
`record(subject, eventType, before, after, note)`. Services call it inside the same
`@Transactional` method that makes the change, so an event never exists without its change and
a change never commits without its event. JPA listeners are rejected: they cannot name the
business event ("verified" versus "field updated") and cannot see the actor's decision.

Every state-changing service method in the module records at least one event. The instrumented
list for the first release: inspection create, update, status change, result, cancel; check
item result and remarks; defect create, update, status; NCR create, assign, corrective action
complete, verify, reject, close, reopen; reinspection schedule and outcome; observation create
and review; evidence attach and remove; compliance generation (one event per generated
inspection, actor `AI`).

### 4.3 Append-only guarantees

- `InspectionEventRepository` exposes `save` and finders only. A unit test (ArchUnit style,
  with `@AnalyzeClasses`) asserts the repository declares no `delete*` and no modifying query.
- No service holds a reference that mutates an event after save; the entity has no setters
  beyond construction (builder only).
- No row-level TTL is configured; retention is indefinite.
- The existing `created_at` / `updated_at` columns on the entities stay. They are cheap and
  useful for sorting; the event log is the record of who and why.

## 5. Data model

```
inspection_reinspections
  id                          uuid pk
  organization_id             bigint not null (orgFilter)
  project_id                  bigint not null
  ncr_id                      uuid null  fk ncrs
  defect_id                   uuid null  fk inspection_defects
  original_inspection_id      uuid not null  fk inspections
  reinspection_inspection_id  uuid not null  fk inspections, unique
  sequence                    int not null
  requested_by_id             bigint not null
  requested_at                timestamp not null
  assigned_inspector_id       bigint null
  target_date                 date null
  outcome                     varchar(20) not null default PENDING
  outcome_by_id               bigint null
  outcome_at                  timestamp null
  remarks                     text null
  created_at, updated_at, created_by, updated_by
  check (ncr_id is not null or defect_id is not null)
  unique (ncr_id, sequence) where ncr_id is not null
  unique (defect_id, sequence) where defect_id is not null

inspection_events
  id               uuid pk
  organization_id  bigint not null (orgFilter)
  project_id       bigint null
  inspection_id    uuid null
  subject_type     varchar(20) not null
  subject_id       uuid not null
  event_type       varchar(80) not null
  actor_type       varchar(10) not null
  actor_id         varchar(100) null
  occurred_at      timestamp not null
  before           jsonb null
  after            jsonb null
  note             text null
  request_id       varchar(64) null
  index (inspection_id, occurred_at)
  index (subject_type, subject_id, occurred_at)
  index (project_id, occurred_at)
```

Both entities implement `TenantScopedEntity` so `ModuleBoundaryTest`'s tenancy rule holds.

## 6. API

```
POST /api/v1/ncrs/web/{ncrId}/reinspections
       { assignedInspectorId?, targetDate?, copyAllItems?: false }
       creates the record and the new inspection; 409 unless NCR is CORRECTIVE_ACTION_COMPLETE
GET  /api/v1/ncrs/web/{ncrId}/reinspections
POST /api/v1/inspections/web/defects/{defectId}/reinspections   same, for defect-only cases
GET  /api/v1/inspections/web/reinspections/{id}
POST /api/v1/inspections/web/reinspections/{id}/outcome   { outcome: PASSED | FAILED, remarks? }
       permission inspections.ncr.sign-off

POST /api/v1/ncrs/web/{ncrId}/verify    existing; body gains optional reinspectionId

GET  /api/v1/inspections/web/{id}/events          full timeline incl. children, paged, oldest first
GET  /api/v1/ncrs/web/{id}/events
GET  /api/v1/inspections/web/observations/{id}/events   (note 2)
GET  /api/v1/inspections/web/events?projectId&subjectType&eventType&actorId&from&to   project-wide, paged
```

Reads of events need `inspections.read`. Nothing writes events directly through the API.
OpenAPI snapshot regenerated.

## 7. Migration

One module changeset under `db/changelog/modules/inspections/`: the two tables and their
indexes. No columns change on existing tables. Existing NCRs that are already `VERIFIED` have no
reinspection row and no events; the timeline for them starts at the release. No synthetic
"created" events are backfilled from `created_at` (defaulted below).

## 8. Core and web impact

`echno-core`: `types/inspection/reinspection.ts`, `types/inspection/event.ts`,
`services/reinspection-service.ts`, `services/inspection-event-service.ts`; `NcrDto` gains
`reinspections` summary (count, latest outcome). Additive.

`echno-web`:

- NCR detail: a "Reinspections" section with the attempts, their outcome and a link to each
  reinspection inspection; "Schedule reinspection" action in `CORRECTIVE_ACTION_COMPLETE`;
  verify dialog offers the passed reinspection to attach.
- Inspection and NCR detail: a "History" tab rendering the timeline (actor, time, event,
  before to after), with a filter by event type.
- Reports: the PDF inspection report appends the timeline as its last section.

## 9. Definition of done

- From an NCR awaiting verification, a user can schedule a reinspection; a new inspection
  appears with the failed items; recording its outcome drives the NCR to `VERIFIED` or
  `REJECTED`; a second attempt gets sequence 2.
- Every listed service action writes an event in the same transaction; a failed transaction
  writes none.
- The inspection timeline shows events from the inspection, its items, defects, NCRs and
  reinspections in order, with actor and before/after.
- The repository test proving no delete or update path passes; `ModuleBoundaryTest` passes.
- Changesets apply on fresh and seeded databases; OpenAPI snapshot updated; core published;
  web history tab and reinspection section merged.

## 10. Open questions, defaulted

| Question | Default taken |
|---|---|
| Is a reinspection mandatory before NCR `VERIFIED`? | No, this release. Verification without one is allowed and logged as `ncr.verified.without_reinspection`. A per-org setting can make it mandatory later. |
| Which items are copied into the reinspection? | Failed items only; `copyAllItems` opt-in. |
| Envers or explicit events? | Explicit events (section 2). |
| Backfill events from `created_at`? | No. |
| Retention | Indefinite; no TTL. |
| Event log in core or in the module? | Module. If a second module needs one, lift the recorder and table pattern to core then. |
| Who may record an outcome? | `inspections.ncr.sign-off`, same as verify. |
| Actor for scheduled jobs | `SYSTEM` with the job name as `actor_id`; `AI` for the compliance generator. |

## 11. Sequencing and work packages

Independent of notes 1 and 4; runs in parallel with both. Note 2 uses the recorder from package
A when it is available. Packages A and C are independent of each other.

- **A. echno-backend: "Inspection event log: append-only table, recorder and read endpoints"**
  Entity, changeset, `InspectionEventRecorder`, repository guard test, timeline endpoints.
- **B. echno-backend: "Record events from inspection, check item, defect, NCR and compliance services"**
  Instrument every state change in the list of 4.2. Depends on A.
- **C. echno-backend: "Reinspection record: schedule from NCR or defect, outcome, verify linkage"**
  Entity, changeset, inspection cloning, outcome to NCR/defect transition, endpoints.
- **D. echno-core: "Reinspection and event timeline types and services"**
  Depends on A and C for the contract.
- **E. echno-web: "NCR reinspection section and history tab on inspection and NCR detail"**
  Depends on D.
