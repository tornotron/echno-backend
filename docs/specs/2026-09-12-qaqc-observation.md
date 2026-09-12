# QA/QC extension, note 2 of 4: Observation

**Date:** 2026-09-12
**Status:** Design for review. No code lands with this note. ClickUp `14zdkkvrf2b`; roadmap
sections 3.2, 3.3, 20, 24 and 25.
**Scope:** A source-agnostic `Observation` entity in `modules/inspections`: something seen on
site by a human, an AI model, a drone, a ground robot or a fixed camera, recorded with a
persistent id ahead of human verification, carrying spatial location, evidence, source and model
metadata, confidence, and the human decision that turns it into a check-item result, a defect or
an inspection. The AI compliance generator becomes the first non-human producer.

Depends on `2026-09-12-qaqc-spatial-hierarchy.md` (note 1) for `spatial_node_id`. Event
recording is defined in `2026-09-12-qaqc-reinspection-audit-log.md` (note 3) and is optional
for this note's first release.

---

## 1. Motivation

Today a finding on site exists only as the outcome it produced: a `FAILED` `InspectionCheckItem`
or an `InspectionDefect` row. There is no record of the finding itself, no confidence, no model
version, no device, and no place to hold a machine-produced finding while a person decides what
it is. `InspectionOrigin { MANUAL, AI_GENERATED }` tells you who created an inspection, which is
one level up from the finding.

Roadmap section 3.3 asks that the source of an observation be metadata, with one downstream
model for all sources. Section 3.2 asks that every observation carry a persistent id that stays
connected to the evidence, corrective action, reinspection and closure that follow. Section 25
asks that the human decision (accepted, rejected, modified) be stored as structured data.
Section 3.3 also says what to avoid: no autonomous contractual approval. AI and robots produce
evidence and probable observations; a person verifies.

## 2. Design

### 2.1 One entity, source as metadata

`Observation` lives in `modules/inspections/domain`, table `inspection_observations`, tenant
scoped. Its `source` is an enum `ObservationSource { HUMAN, AI, DRONE, ROBOT, FIXED_CAMERA }`
and the only thing that differs by source is which metadata columns are filled. The review
workflow, evidence handling, outcome linkage and event log are identical.

`inspection_id` is nullable. A drone mission or a compliance model can produce an observation
before any inspection exists for it. Attaching it to an inspection is part of the human review
when it makes sense, and some observations never get one (the compliance case in 2.5).

### 2.2 Life cycle

```
proposed (PENDING)  -->  reviewed: ACCEPTED | REJECTED | MODIFIED  -->  outcome linked
```

- `PENDING`: created by any source, no human decision yet. Machine intake always lands here.
- `ACCEPTED`: the reviewer agrees with the proposal as stated.
- `MODIFIED`: the reviewer agrees there is a finding and changes something (severity, location,
  description). The proposal columns stay as the producer wrote them; the reviewer's edits are
  stored in `review_changes` (JSON of field, before, after). This is the feedback record
  section 25 asks for, and it is self-contained even before the note 3 event log exists.
- `REJECTED`: no finding. Reason in `review_note`. The row stays; rejected observations are the
  negative examples a model needs.

The outcome of an accepted or modified observation is one of:

| `outcome_kind` | `outcome_ref` points at | Meaning |
|---|---|---|
| `NONE` | null | Noted, no action (a passing spot check, an informational note) |
| `CHECK_ITEM` | `inspection_check_items.id` | Sets that item's result (`PASSED`, `FAILED`, `NOT_APPLICABLE`) |
| `DEFECT` | `inspection_defects.id` | Creates or attaches a defect (and, from there, an NCR) |
| `INSPECTION` | `inspections.id` | Created or confirmed a whole inspection (compliance suggestions) |

The reverse links exist so the chain reads from the outcome back to the observation:
`inspection_defects.observation_id` and `ncrs.observation_id`, both nullable. An NCR raised from
a defect inherits the defect's observation id; an NCR raised directly from an inspection may
carry its own.

### 2.3 Human producers: explicit and implicit

Explicit: an inspector adds an observation during or outside an inspection ("crack at column
C-14, hairline"). Source `HUMAN`, and the creator's act is the decision: the row is created
`ACCEPTED` with `reviewed_by_id = reported_by_id`. A second-person review is available (a
supervisor may set `REJECTED` later) but is not required for human observations.

Implicit: the existing endpoints keep working unchanged. When a person records `FAILED` on a
check item, or raises a defect or an NCR through the current forms, the service creates an
`Observation` automatically (`HUMAN`, `ACCEPTED`, outcome linked, description copied from the
remarks or defect description). This gives every finding a persistent id without changing the
inspector's workflow. A `PASSED` result creates no observation: it is a result, and the roadmap
lists item execution and observation creation as separate steps.

### 2.4 Machine producers

Drones, robots and fixed cameras post through one intake endpoint (section 4). Identity is a
Keycloak client-credentials service account per fleet or integration with the permission
`inspections.observations.intake`; the device itself is named in `source_device_id` and the run
in `mission_ref`. Both are strings here; the robot/drone data layer (`14zdkkvrf2e`) owns the
capture and mission tables, and `capture_ref` is the pointer into them. `evidence_refs` (JSON
array) holds pointers to captures that live outside the Echno attachment store (frame index,
point-cloud id, crop bounds). Images and clips uploaded into Echno use the existing attachment
path with owner type `OBSERVATION_EVIDENCE`, the same mechanism as `InspectionEvidence`.

Idempotency: `external_ref` (the producer's own id for the finding) is unique per organisation
when set, so a retried upload returns the existing row.

The payload shape a producer must fill is: project, spatial node (or a free-text location when
the tree has no matching node yet), observed time, title, description, optional suggested
severity and category, model name and version for AI-derived findings, confidence in `[0, 1]`,
evidence. What a capture looks like on the robot side is the data-layer note's contract; this
note fixes only what arrives in Echno.

### 2.5 The compliance generator becomes the first non-human producer

`ComplianceGenerationService` today asks a model which `ComplianceRule`s apply to a project and
creates one `Inspection` per applicable rule with `origin = AI_GENERATED`, `status = SUGGESTED`,
the model's rationale in `ai_rationale` and its risk level in `risk_level`. A person then
approves or dismisses the suggested inspection.

Change: for each suggestion the service also persists an `Observation` with source `AI`,
`model_name` and `model_version` from `ComplianceAiProperties`, `confidence` null unless the
model returns one, `description = rationale`, `outcome_kind = INSPECTION`,
`outcome_ref = the suggested inspection`, `review_status = PENDING`. Approving the suggested
inspection sets the observation `ACCEPTED`; dismissing sets `REJECTED`; editing before approval
sets `MODIFIED` with the diff. `ai_rationale` and `risk_level` stay on `Inspection` for this
release so nothing in web breaks; the observation is the durable record and a later cleanup may
drop `ai_rationale`.

A note on shape: roadmap section 4 places Observation downstream of Inspection and Item. The
compliance generator produces an inspection from a judgement, which is upstream of any item.
The model here allows that (nullable `inspection_id`, outcome kind `INSPECTION`) and the chain
in section 4 remains the normal case for site findings. `InspectionOrigin` on `Inspection` is
unchanged and keeps meaning "who created the inspection record".

### 2.6 Permissions

Add to `InspectionsModule.PERMISSIONS`: `inspections.observations.review` (decide on pending
observations) and `inspections.observations.intake` (machine producers). Creating a human
observation uses the existing `inspections.manage`. The manifest is the source of truth for the
permission list, so the change is one line there plus the `@PreAuthorize` on the endpoints.

## 3. Data model

```
inspection_observations
  id                 uuid pk
  organization_id    bigint not null (orgFilter)
  project_id         bigint not null
  inspection_id      uuid null       fk inspections
  spatial_node_id    uuid null       fk project_spatial_node on delete restrict
  location_note      varchar(300) null   free-text fallback, same rule as note 1
  source             varchar(20) not null   HUMAN | AI | DRONE | ROBOT | FIXED_CAMERA
  source_device_id   varchar(100) null
  mission_ref        varchar(100) null
  capture_ref        varchar(200) null
  external_ref       varchar(200) null   unique (organization_id, external_ref) where not null
  model_name         varchar(100) null
  model_version      varchar(50) null
  confidence         numeric(5,4) null   0..1
  observed_at        timestamp not null
  reported_by_id     bigint null     employee, HUMAN source
  title              varchar(200) not null
  description        text null
  category           varchar(200) null
  suggested_severity varchar(20) null    DefectSeverity values
  evidence_refs      jsonb null
  review_status      varchar(20) not null   PENDING | ACCEPTED | REJECTED | MODIFIED
  reviewed_by_id     bigint null
  reviewed_at        timestamp null
  review_note        text null
  review_changes     jsonb null
  outcome_kind       varchar(20) not null default NONE
  outcome_ref        uuid null
  created_at, updated_at, created_by, updated_by

index (project_id, review_status)
index (inspection_id)
index (spatial_node_id)
index (source, observed_at)

inspection_defects.observation_id  uuid null  fk inspection_observations
ncrs.observation_id                uuid null  fk inspection_observations
```

Two new enums beside the existing ones in `modules/inspections`: `ObservationSource`,
`ObservationReviewStatus`, plus `ObservationOutcomeKind`. Same `@JsonValue` slug style.

## 4. API

Human and review endpoints sit with the other inspection web controllers:

```
POST /api/v1/inspections/web/observations
       { projectId, inspectionId?, spatialNodeId?, locationNote?, observedAt?, title,
         description?, category?, suggestedSeverity?, evidenceAttachmentIds? }
       source HUMAN, created ACCEPTED by the caller
GET  /api/v1/inspections/web/observations
       ?projectId&reviewStatus&source&inspectionId&spatialNodeId(subtree)&from&to  paged
GET  /api/v1/inspections/web/observations/{id}
POST /api/v1/inspections/web/observations/{id}/review     permission inspections.observations.review
       { decision: ACCEPT | REJECT | MODIFY,
         note?,
         changes?: { title?, description?, severity?, spatialNodeId?, category? },
         outcome: { kind: NONE }
                | { kind: CHECK_ITEM, checkItemId, status }
                | { kind: DEFECT, defect: InspectionDefectRequest | { defectId } }
                | { kind: INSPECTION, inspectionId } }
       409 if already reviewed; MODIFY requires changes
POST /api/v1/inspections/web/observations/{id}/evidence   presigned attachment flow, owner OBSERVATION_EVIDENCE
```

Machine intake is a separate controller so its auth and rate limit can differ:

```
POST /api/v1/observations/intake                 permission inspections.observations.intake
       { projectId, externalRef, source: AI | DRONE | ROBOT | FIXED_CAMERA,
         sourceDeviceId, missionRef?, captureRef?, spatialNodeId? | locationNote,
         observedAt, title, description?, category?, suggestedSeverity?,
         modelName?, modelVersion?, confidence?, evidenceRefs? }
       returns 201 with the row, or 200 with the existing row on a repeated externalRef
```

Existing endpoints unchanged in shape; their services gain the implicit observation creation of
2.3. `InspectionDefectDto` and `NcrDto` gain `observationId`. OpenAPI snapshot regenerated.

## 5. Migration

One module changeset under `db/changelog/modules/inspections/`: create
`inspection_observations`, add `observation_id` to `inspection_defects` and `ncrs`. Existing
defects and NCRs keep `observation_id = null`; no backfill is created for them (an observation
invented after the fact would carry a false `observed_at`). From the release onward every new
failed item, defect and NCR has one.

The compliance change touches no schema.

## 6. Core and web impact

`echno-core`: new `types/inspection/observation.ts` and `services/observation-service.ts`;
optional `observationId` on defect and NCR responses. Additive.

`echno-web`:

- "Observations" list under the inspections nav with the `PENDING` queue first, filters by
  source and project, and a review drawer: evidence viewer, proposal fields, accept / modify /
  reject, and the outcome chooser (mark item, create defect, none).
- On the inspection page, an "Add observation" action beside "Add defect".
- The compliance approval screen shows the model name, version and rationale from the
  observation instead of the inspection's `aiRationale` once both exist.

## 7. Definition of done

- A human can create an observation with evidence and a spatial node; it appears with a
  persistent id on the inspection and in the project list.
- A service-account client can post an observation through intake; a repeat with the same
  `externalRef` does not duplicate; it shows in the pending queue.
- A reviewer can accept, modify or reject a pending observation; accept and modify can mark a
  check item or create a defect; the defect and its NCR carry `observationId`.
- Recording a failed check item or raising a defect through the existing forms creates a
  linked `HUMAN` observation.
- Every compliance suggestion produces an `AI` observation; approving or dismissing the
  suggested inspection reviews it.
- `ModuleBoundaryTest` passes; Testcontainers suite covers intake idempotency and review
  transitions; OpenAPI snapshot updated; core types published; web queue merged.

## 8. Open questions, defaulted

| Question | Default taken |
|---|---|
| Does a `PASSED` check item create an observation? | No. Only failures, defects and NCRs do. |
| Do human observations need a second-person review? | No. Created `ACCEPTED`; a supervisor may reject later. |
| Where does device identity come from? | Keycloak client-credentials service account per fleet; device named in the payload. |
| Confidence threshold to auto-accept? | None. Roadmap 3.3: humans verify. No auto-accept path exists. |
| Backfill observations for historic defects? | No. |
| Keep `ai_rationale` on `Inspection`? | Yes for this release; drop in a later cleanup. |
| Reject reason mandatory? | Yes, `note` required on `REJECT`. |
| Retention of rejected observations | Kept indefinitely; they are training negatives. |

## 9. Sequencing and work packages

This note follows note 1: package A needs `spatial_node_id` to exist. Notes 3 and 4 run in
parallel with it. If note 3 lands first, packages A and C record events through its recorder;
if not, `review_changes` on the row is the audit record until note 3's instrumentation package
adds the events.

- **A. echno-backend: "Observation entity, review decision and outcome linkage"**
  Entity, enums, changeset, service with review transitions, reverse links on defect and NCR,
  implicit creation from failed items, defects and NCRs. Tests. Depends on note 1 package A.
- **B. echno-backend: "Observation intake endpoint for device and AI producers"**
  Separate controller, service-account permission, `externalRef` idempotency, evidence refs.
  Depends on A.
- **C. echno-backend: "Compliance generator writes an AI observation per suggestion"**
  Observation per suggested inspection; approve/dismiss reviews it. Depends on A.
- **D. echno-core: "Observation types and service"**
  Depends on A and B for the contract.
- **E. echno-web: "Observation queue, review drawer and add-observation action"**
  Depends on D.
