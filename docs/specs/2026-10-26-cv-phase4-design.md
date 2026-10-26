# Phase 4: computer vision for QA/QC

**Date:** 2026-10-26
**Status:** Design for review. Build plan for Phase 4; work packages are dispatched from section 4
onward. No code lands with this note.
**Roadmap:** Next Vision roadmap (ClickUp `86d45b7nn`) sections 8, 9, 10, 11, 25, 27, 32.4, 33.4
and 35. Dataset ClickUp `14zdkkvrf2d`.
**Builds on:** `2026-09-12-qaqc-observation.md` (Observation, intake, review),
`2026-09-12-qaqc-reinspection-audit-log.md` (event log), `2026-09-12-qaqc-spatial-hierarchy.md`,
`2026-08-26-modular-plugin-architecture.md` (module manifest, entitlement gate),
`2026-09-13-bim-import-contract.md` (worker job pattern) and
`echno-roadmap/datasets/construction-image-dataset.md` (collection, annotation spec, bucket layout,
v0.1 definition of done).

---

## 1. Summary

Echno already stores machine findings as `PENDING` observations with model name, version and
confidence, and a person accepts, modifies or rejects each one. Phase 4 puts a model in that loop.
A detector runs on inspection photos, each detection becomes an observation with a region drawn on
the photo it came from, a reviewer works through the detections in bulk, and every decision flows
back into the dataset so the next model learns from it. The order is: give observations geometry
and a validated producer path (WP1 to WP5), add a small model registry and an inference worker that
follows the BIM worker pattern (WP2, WP6 to WP8), close the loop from review decision to dataset
(WP9), add a training and evaluation pipeline (WP10), run a stand-in safety model through the whole
chain on public images (WP11, WP12), and only then train on pilot photos (WP13, WP14). Everything up
to WP12 can be built and proven before the Asset Homes pilot produces a single photo. The roadmap
rule holds throughout: AI detection, then confidence, then human verification, then the formal QA
decision. No path in this design turns a model output into a failed check item, a defect or an NCR
without a person.

### 1.1 Work packages

Sizes: S is up to 2 developer-days, M is 3 to 5, L is 6 to 10.

| WP | Title | Repo(s) | Depends on | Blocked on pilot | Size |
|---|---|---|---|---|---|
| WP0 | Export-to-tooling contract fix (`consent`, `evidence_id`), golden-file test | echno-backend, echno-datasets | | No | S (in flight) |
| WP1 | Observation regions: data model, intake, MODIFY edits | echno-backend | | No | M |
| WP2 | `vision` module, model registry, class taxonomy, intake check | echno-backend | WP1 | No | M |
| WP3 | Producer evidence upload, `evidenceRefs` validation | echno-backend | WP1 | No | S |
| WP4 | Batch intake, bulk review, list filters | echno-backend | WP1, WP2 | No | M |
| WP5 | Core types, region overlay and editor, grid bulk review, filters | echno-core, echno-web | WP1, WP4 | No | L |
| WP6 | Inference jobs: table, triggers, ingestion, org settings | echno-backend | WP2, WP4 | No | M |
| WP7 | `echno-vision-worker` (new repo) | echno-vision-worker | WP6 | No | M |
| WP8 | Deploy the worker, buckets, DB user and policies on both stagings | echno-deployment | WP7 | No | S |
| WP9 | Feedback export and dataset tooling (import-feedback, import-cvat, group split, ingest-session) | echno-backend, echno-datasets | WP0, WP1 | No | M |
| WP10 | `echno-vision-train` (new repo): train, evaluate, export, publish | echno-vision-train | WP2, WP9 | No | L |
| WP11 | Reference safety models R0 and R1 | echno-vision-train | WP7, WP10 (R1 only) | No | S |
| WP12 | End-to-end proof on public images, release `v0.0-synthetic` | all | WP1 to WP11, decisions D1 to D4 | No | M |
| WP13 | Pilot capture session and v0.1 release | echno-datasets, field | WP9, WP12 | **Yes** | M |
| WP14 | Pilot baseline models and validation report | echno-vision-train | WP10, WP13 | **Yes** | L |

WP0 is the consent and `evidence_id` fix being built now: the backend writes `consent: true` and an
`evidence_id` the tooling's regex accepts, and a shared golden fixture keeps the two from drifting.
This note does not redesign it and refers to "the WP0 `evidence_id`" wherever the format matters.

WP1, WP3 and WP9 can start in parallel today. WP2 can start alongside WP1 once the region class field
is settled (section 4.2). The critical path to the first proof is WP1, WP2, WP4, WP6, WP7, WP8, WP12.

## 2. Decisions for Abhijith

| # | Decision | Recommendation |
|---|---|---|
| D1 | Detector licence family | **RF-DETR** (Apache-2.0 sizes only) for both box and polygon tasks; no Ultralytics. Section 3. |
| D2 | Training on hp-z's GPUs | Ask Prof. Sarith for **one A5000 (GPU 1)** in agreed windows, data under `/hdd2/fereydon`, Python venv only, no driver, Docker or reboot changes. Pilot data trains only on IITM hardware. Section 4.10. |
| D3 | Run the end-to-end proof through the `.116` compose staging | **Yes**, through the app's own endpoints, in a dedicated test organisation that holds only public images. The worker is added as one CPU-capped compose service through the Ansible apps role; nothing existing is restarted or removed. Section 4.12. |
| D4 | CVAT annotator accounts | **Named accounts**, no shared login: Abhijith as admin and reviewer, two annotators (interns or students) who can start on public images now. Pilot images only after the pilot consent clause covers named annotators. Section 4.12. |
| D5 | Stand-in helmet data | Use **SHWD** (MIT repository, web-collected images) to train the R1 stand-in only. Its images never enter a release, a client org or client-facing weights. Section 4.11. |

## 3. Licence analysis (D1)

Licences below were read from each repository with `gh api repos/<repo>` on 26 Oct 2026, and for
RF-DETR from the per-model table in its README.

| Option | Code | Weights | Tasks | Notes |
|---|---|---|---|---|
| Ultralytics YOLO (`ultralytics/ultralytics`) | AGPL-3.0 | AGPL-3.0 by Ultralytics' terms, including models trained with it | box, polygon (seg) | Enterprise licence available at a price that is not published. |
| RF-DETR (`roboflow/rf-detr`) | Apache-2.0 | N, S, M, L detection and all Seg sizes Apache-2.0; Atto, Femto, Pico, XL, 2XL detection are PML 1.0 | box, polygon (seg) | DINOv2 backbone, `facebookresearch/dinov2` Apache-2.0. Actively maintained. |
| RT-DETR (`lyuwenyu/RT-DETR`, also in HF `transformers`) | Apache-2.0 | Apache-2.0 | box | No native segmentation. |
| D-FINE (`Peterande/D-FINE`) | Apache-2.0 | Apache-2.0 | box | No native segmentation. |
| YOLOX (`Megvii-BaseDetection/YOLOX`) | Apache-2.0 | Apache-2.0 | box | Last push June 2025. |
| torchvision (`pytorch/vision`) | BSD-3-Clause | COCO-trained; subject to the dataset terms | box, mask (Mask R-CNN) | Older architectures, slower on CPU. |
| MMDetection (`open-mmlab/mmdetection`) | Apache-2.0 | mostly Apache-2.0 | box, mask | Last push August 2024. Not recommended. |
| YOLOv9 (`WongKinYiu/yolov9`) | GPL-3.0 | | box | Same copyleft question as Ultralytics. |

### 3.1 What AGPL-3.0 means for us in practice

- **Hosted inference (staging, a shared production).** AGPL section 13 obliges whoever runs a
  modified AGPL program for users over a network to offer those users its source. Our users talk to
  the Echno backend, and the worker sits behind a job table, so a narrow reading puts only the worker
  in scope. Ultralytics reads it more broadly: in their terms, any commercial product that uses
  their code or models must either be released in full under AGPL-3.0 or buy an Enterprise licence.
  We would be relying on the narrow reading against the licensor's stated position.
- **Per-client production.** Each client gets its own DigitalOcean instance. If
  we operate it, the hosted case applies per client. If a client ever runs it on its own hardware,
  we convey the worker and weights to them, and they receive AGPL rights to both, including the
  right to redistribute weights trained on another client's photos.
- **Echno's own licence.** Backend, web and core are public with an AGPL plan. An AGPL vision stack
  is compatible with that plan. It also removes options: Fereydon could not later ship a proprietary
  or dual-licensed edition of the vision stack, or sell a model to a partner under its own terms,
  without the Enterprise licence.
- **Weights as an asset.** The weights trained on pilot data are the product's moat. Under
  Ultralytics' terms they would carry AGPL to every recipient.

The constraint is commercial: cheap to avoid now, expensive to undo after the first client model is
trained.

### 3.2 Recommendation

RF-DETR, Apache-2.0 sizes only: Nano or Small for CPU inference, Seg-Small for polygon defects. It
covers both task types under one licence, it fine-tunes well on small datasets (its README reports
RF100-VL results, a benchmark of 100 small domain datasets), and it exports to ONNX for a CPU
worker. The registry (WP2) enforces this: a model version can only be activated if its `licence` is
on `echno.vision.allowed-licences` (default `Apache-2.0,BSD-3-Clause,MIT`), and WP10 refuses to
publish a checkpoint whose name is on the PML list. torchvision (BSD-3-Clause) is the fallback if an
RF-DETR export or accuracy problem appears. **Decision for Abhijith.**

## 4. Work packages

### 4.1 WP1: Observation regions

**Goal.** A region is a box or polygon on one specific image, with a class and a score. One
observation is one finding, so a model that sees five helmets on a photo produces five observations,
each with one region. This keeps accept and reject per detection, which is what the feedback loop
needs. An observation may carry more than one region (the same crack seen in two photos, or a
reviewer adding a region the model missed).

**Data model.** New table in `modules/inspections`, changeset
`db/changelog/modules/inspections/<n>-observation-regions.xml`, where `<n>` is the next free global
changeset number at merge time (132 at the time of writing; WP0 may take one first).

```
inspection_observation_regions
  id                uuid pk
  organization_id   bigint not null                 orgFilter, same as the parent
  observation_id    uuid not null  fk inspection_observations on delete cascade
  attachment_id     bigint not null fk attachments   the image the region is drawn on
  shape             varchar(10) not null            BOX | POLYGON
  x1, y1, x2, y2    numeric(9,6) not null           bounding box, fractions of width/height
  points            jsonb null                      POLYGON only: [[x,y],...], fractions
  image_width       int null                        pixels, after EXIF orientation
  image_height      int null
  class_key         varchar(50) not null            from the class taxonomy (WP2)
  score             numeric(5,4) null               producer score in [0,1]; null for people
  origin            varchar(20) not null            PRODUCER | REVIEWER | HUMAN
  supersedes_id     uuid null      fk inspection_observation_regions
  retired_at        timestamp null                  set when a review replaces or removes it
  retired_by_id     bigint null
  line_order        int not null
  created_at        timestamp not null
  created_by_id     bigint null

index (observation_id)
index (attachment_id)
index (organization_id, class_key)

inspection_observations
  + reject_reason   varchar(30) null    FALSE_POSITIVE | DUPLICATE | OUT_OF_SCOPE | POOR_IMAGE
  + review_batch_id uuid null           set by bulk review (WP4)
```

Coordinates follow `DefectPhotoAnnotation`: fractions in `[0, 1]`, scale 6, origin top left, x to
the right, y down, measured on the image **as displayed**, after EXIF orientation is applied. For a
polygon, `x1..y2` hold its bounding box, so filtering, cropping and COCO `bbox` work the same for
both shapes. Polygons have 3 to 200 vertices, are stored open (the last vertex is not repeated), and
must not self-intersect (checked with JTS if it is already on the classpath, else a plain
segment-intersection check; reject with `INVALID_GEOMETRY`).

`DefectPhotoAnnotation` stays as it is. It annotates defect photos, which are stored as key strings
on the defect with no attachment row, and it carries a different meaning (an inspector's markup on a
report photo). Merging the two is out of scope.

**Deleting an image that a region points at.** The FK is `on delete restrict`. The inspection and
observation evidence delete services check for active regions first and return 409 with the
observation ids. An observation's evidence must not vanish from under its decision record.

**Intake.** `IntakeObservationRequest` gains an optional `regions` list (max 20):

```json
"regions": [
  {"attachmentId": 18342, "shape": "box", "box": [0.412, 0.188, 0.471, 0.262],
   "classKey": "ppe_helmet", "score": 0.81, "imageWidth": 4032, "imageHeight": 3024},
  {"attachmentId": 18342, "shape": "polygon",
   "points": [[0.10, 0.52], [0.18, 0.49], [0.21, 0.61]], "classKey": "crack", "score": 0.66}
]
```

Validation per region: attachment in the caller's org and allowed for this producer (WP3 rules);
`box` has four values in `[0,1]` with `x1 < x2` and `y1 < y2` after rounding to 6 places; `points`
as above; `classKey` passes the intake check (WP2). The human create endpoint
(`POST /inspections/web/observations`) accepts the same `regions` field; those rows get
`origin = HUMAN`, no score.

**Read.** `ObservationDto` gains `regions: ObservationRegionDto[]` (active rows, ordered by
`line_order`) and `rejectReason`. `GET /inspections/web/observations/{id}?includeRetiredRegions=true`
also returns retired rows, so the history of a corrected box can be shown. `GET .../{id}/evidence`
returns the `OBSERVATION_EVIDENCE` attachments plus every attachment a region points at, deduplicated,
each with its presigned GET URL.

**MODIFY edits a region.** `ReviewObservationRequest.Changes` gains `regions`, an ordered list of
operations:

```json
"changes": {
  "regions": [
    {"op": "replace", "regionId": "…", "shape": "box", "box": [0.40, 0.18, 0.48, 0.27], "classKey": "ppe_helmet"},
    {"op": "remove",  "regionId": "…"},
    {"op": "add", "attachmentId": 18342, "shape": "box", "box": [0.61, 0.20, 0.66, 0.27], "classKey": "ppe_helmet"}
  ]
}
```

- `replace`: a new row with `origin = REVIEWER`, `supersedes_id` = the old row, no score; the old row
  gets `retired_at` and `retired_by_id`. A class change is a `replace` with a different `classKey`
  of the same shape kind.
- `remove`: the row is retired, nothing is inserted.
- `add`: a new `REVIEWER` row with no `supersedes_id`. This is how a missed object is recorded.
- The producer's rows are never updated in place. The prediction and the correction both survive,
  and the export (WP9) emits both.
- A MODIFY that would leave no active region on an observation that had producer regions is refused
  with 400 ("reject the observation instead").
- `review_changes` gains one entry `{"field": "regions", "before": [ids], "after": [ids]}`, and the
  `OBSERVATION_REVIEWED` event carries the same diff with each op.

**REJECT.** `rejectReason` is required when the observation has `PRODUCER` regions and optional
otherwise. Only `FALSE_POSITIVE` turns the rejected region into a hard negative (WP9). `DUPLICATE`
(the same object reported twice), `OUT_OF_SCOPE` (real but not relevant, for example outside the
site boundary) and `POOR_IMAGE` (unusable photo) are kept out of training signal. `note` stays
required on REJECT, as today.

**Tests.** Testcontainers: round-trip of box and polygon; each validation failure (out of range,
inverted box, 2 points, 201 points, self-intersection, attachment in another org returns 404);
replace, remove and add produce the right rows and retire the right ones; MODIFY removing every
region is 400; REJECT without `rejectReason` on a producer observation is 400; deleting an image
with an active region is 409; tenant isolation on regions read through another org. OpenAPI
snapshot regenerated (`-PupdateOpenApiSnapshot`).

**Acceptance.** An intake with two regions on one attachment shows both on GET; a MODIFY that moves
one box leaves the producer row retired and a `REVIEWER` row active, and the event log shows the
diff; the snapshot and `ModuleBoundaryTest` pass.

### 4.2 WP2: `vision` module, model registry, class taxonomy

**Module.** New `modules/vision` with manifest `VisionModule`: id `vision`, name "Computer Vision",
`entitlementFeatureKey = MODULE_VISION`, `dependsOn = ["inspections"]`, `enabledByDefault = false`.
Permissions: `vision:read`, `vision:run`, `vision:models-manage`. Kill switch
`echno.modules.vision.enabled`, read by a `VisionModuleEnabled` condition for its scheduled beans,
the same as `InspectionsModuleEnabled`. Controllers carry
`@RequireSubscription(feature = "MODULE_VISION")` at class level, except the registry read used by
intake (below). Seed: one `Feature` row `MODULE_VISION`, and a `PlanFeature` only on the internal
plan used by staging test orgs. Production plans get it when Anand prices the add-on.

Regions live in `inspections` because a person can draw one without any model. The registry, the
jobs and the settings live in `vision`. `inspections` never depends on `vision`: it publishes an
extension point in `modules/inspections/api`:

```java
public interface ObservationIntakeCheck {
    /** Throws IntakeRejectedException(code, field, message) to refuse one item. */
    void check(IntakeCandidate candidate);
}
```

`ObservationService` calls every bean of that type (none if `vision` is not compiled in or is
killed). `IntakeCandidate` is a small record in the same `api` package (source, model name and
version, regions as class key and shape). This passes `modulesReachEachOtherOnlyThroughApi`.

**Registry tables.** Platform-wide catalogue, so both entities carry `@GlobalReferenceData`.
Changeset under `db/changelog/modules/vision/`.

```
cv_models
  id          uuid pk
  key         varchar(50) unique not null     e.g. ppe, concrete-defects
  name        varchar(100) not null
  task        varchar(10) not null            DETECT | SEGMENT
  family      varchar(20) not null            SAFETY | DEFECT | OBJECT | PROGRESS
  created_at, updated_at

cv_model_versions
  id                 uuid pk
  model_id           uuid not null fk cv_models
  version            varchar(50) not null      semver, unique (model_id, version)
  status             varchar(10) not null      DRAFT | ACTIVE | RETIRED
  classes            jsonb not null            [{"key":"ppe_helmet","shape":"BOX","threshold":0.35}]
  licence            varchar(50) not null      SPDX id
  base_weights       varchar(200) not null     e.g. "rf-detr-nano COCO (Apache-2.0)"
  weights_uri        varchar(500) not null     s3://echno-models/<model>/<version>/model.onnx
  weights_sha256     char(64) not null
  runtime            varchar(30) not null      onnxruntime
  input_size         int not null
  training_release   varchar(50) null          dataset release, e.g. v0.0-synthetic
  training_run_uri   varchar(500) null         run record (WP10)
  metrics            jsonb null                summary from the run record
  eval_report_uri    varchar(500) null
  registered_by_id   bigint null
  activated_at, retired_at, created_at, updated_at

unique (model_id) where status = 'ACTIVE'      partial index; one active version per model
```

Separate model families per inspection problem (roadmap section 8) fall out of this: a family is a
`cv_models` row with its own class list and its own versions.

**Class taxonomy.** The class keys are the ones in `echno_datasets/classes.py` (object, defect,
negative-type and progress lists). WP9 makes echno-datasets emit `classes.json` from that module and
test it; the backend vendors the file at `src/main/resources/vision/classes.json` with the source
commit in a header field, and `CvClassTaxonomy` loads it. Object and PPE classes are `BOX`, defect
classes are `POLYGON`. A version's `classes` must be a subset of the taxonomy; a reviewer may relabel
only to a taxonomy class of the same shape. A unit test fails if the vendored file and
`CvClassTaxonomy` disagree.

**Intake check (the `vision` implementation of `ObservationIntakeCheck`).** Applies when the source
is `AI`, or when any region is present with a `score`:

- `modelName` and `modelVersion` are required and must resolve to `cv_models.key` and a version of
  it. Unknown: `UNKNOWN_MODEL_VERSION`.
- Status must be `ACTIVE`. `DRAFT` is accepted only when `echno.vision.accept-draft-models=true`
  (staging only). Otherwise `MODEL_NOT_ACTIVE`.
- Every region's `classKey` must be in that version's `classes` (`UNKNOWN_CLASS`) and its shape must
  match (`SHAPE_MISMATCH`).
- `category`, when given, must equal the class key of the first region; when absent it is set to it.
  This makes `category` filterable as the class.

The check runs whether or not the org is entitled to `vision`: the registry is global, and a
producer should not be able to bypass it by posting to an org without the add-on. The compliance
generator does not pass through intake and is unaffected.

**API.**

```
GET  /api/v1/vision/models                          vision:read     families with versions
GET  /api/v1/vision/models/{key}/versions/{version} vision:read
POST /api/v1/vision/admin/models                    system-admin    create a family
POST /api/v1/vision/admin/models/{key}/versions     system-admin    register, lands DRAFT
POST /api/v1/vision/admin/models/{key}/versions/{version}/status   system-admin
       { status: ACTIVE | RETIRED, note }
```

Activation refuses (400) when `licence` is not on `echno.vision.allowed-licences`, or `metrics` or
`eval_report_uri` is empty. Activating a version retires the previous active one in the same
transaction. RETIRED is terminal. Observations keep their `model_version` string, so retiring
never touches history.

**Tests.** Registration, activation swap, licence refusal, metrics refusal; intake check codes;
`ModuleBoundaryTest` (one manifest bean, `@GlobalReferenceData` on the two catalogue entities); an
org without `MODULE_VISION` gets the structured 403 on `/vision/models`.

**Acceptance.** A version registered with class `ppe_helmet` accepts an intake region of that class
and refuses `crack` with `UNKNOWN_CLASS`; a `PML-1.0` version cannot be activated.

### 4.3 WP3: Producer evidence

**Problem.** A drone, a fixed camera or a partner model must upload the image it detected on. Today
evidence upload needs `canManageInspections()`, and intake stores `evidenceRefs` unvalidated.

**Design.** Two intake-scoped endpoints, guarded by the existing `canIntakeObservations()`, so the
`observation-producer` service account gets upload rights without any manage role:

```
POST /api/v1/observations/intake/evidence/presign
       { projectId, uploads: [UploadRequest] }        max 20 files
       -> { uploadId, uploads: [PresignedUpload] }
POST /api/v1/observations/intake/evidence/register
       { uploadId, uploads: [RegisterUploadRequest] }
       -> [AttachmentDto]                             201
```

The attachments get a new owner type `OBSERVATION_INTAKE_UPLOAD` with `entity_uuid = uploadId`,
`project` set and the service account as creator. Content types `image/jpeg`, `image/png`,
`image/webp`; 25 MB per file. The presigned key prefix is
`observations/intake/<orgId>/<uploadId>/`. A daily sweep in `inspections` deletes intake uploads
older than 7 days that no region and no `evidenceRefs` entry references.

**Validation at intake.** For every region `attachmentId` and every `evidenceRefs` entry that has an
`attachmentId`:

1. The attachment is in the caller's org (else 404 `ATTACHMENT_NOT_FOUND`, same as the human path).
2. Its project equals the intake `projectId`.
3. It is either an `OBSERVATION_INTAKE_UPLOAD` created by the same principal, or an
   `INSPECTION_EVIDENCE` or `OBSERVATION_EVIDENCE` attachment of that project. Anything else is
   `ATTACHMENT_NOT_ALLOWED`.

`evidenceRefs` entries without `attachmentId` (frame index, point-cloud id) still pass through
unchanged, now capped at 20 entries and 8 KB of JSON.

**Tests.** A service account with only `observation-producer` presigns, registers and posts an
observation whose region points at its upload; another producer's upload is refused; an attachment
from another project is refused; the sweep keeps referenced uploads.

**Acceptance.** End to end with a Keycloak client-credentials token on staging: upload, intake,
the image appears in the review sheet.

### 4.4 WP4: Batch intake, bulk review, filters

**Batch intake.**

```
POST /api/v1/observations/intake/batch            canIntakeObservations()
       { items: [IntakeObservationRequest] }       1..100 items, body up to 2 MB
200 -> { created, existing, rejected,
         results: [{ index, externalRef, status: CREATED | EXISTING | REJECTED,
                     observationId?, error?: { code, field?, message } }] }
```

- Each item runs in its own transaction (`TransactionTemplate` with `REQUIRES_NEW`) through the same
  service method as single intake, including the unique-violation retry. One bad item never rolls
  back the others.
- `externalRef` is the idempotency key per org, as today. A repeat returns `EXISTING` with the stored
  id and does not compare payloads. The same `externalRef` twice inside one batch: the second is
  `REJECTED` with `DUPLICATE_IN_BATCH`.
- Envelope errors (empty, over 100, malformed JSON, over 2 MB) are a plain 400 and nothing is
  written.
- Error codes: `VALIDATION`, `PROJECT_NOT_FOUND`, `ATTACHMENT_NOT_FOUND`, `ATTACHMENT_NOT_ALLOWED`,
  `INVALID_GEOMETRY`, `UNKNOWN_MODEL_VERSION`, `MODEL_NOT_ACTIVE`, `UNKNOWN_CLASS`,
  `SHAPE_MISMATCH`, `DUPLICATE_IN_BATCH`, `INTERNAL`.
- The service-level method `intakeAll(List<IntakeObservationRequest>, IntakeActor)` is published in
  `modules/inspections/api` so the `vision` job ingestion (WP6) uses the same path in-process.

**Bulk review.**

```
POST /api/v1/inspections/web/observations/review-batch     canReviewObservations()
       { decision: ACCEPT | REJECT, observationIds: [uuid] (1..100),
         note?, rejectReason? }
200 -> { batchId, results: [{ observationId,
                              status: REVIEWED | ALREADY_REVIEWED | NOT_FOUND | REFUSED,
                              message? }] }
```

- ACCEPT applies outcome `NONE`. Creating a defect, setting a check item or MODIFY stays in the
  single-item sheet, where the reviewer sees the whole photo.
- REJECT takes one `rejectReason` and one `note` for the whole batch, with the same rules as WP1.
- Every reviewed row gets `review_batch_id = batchId`, and its `OBSERVATION_REVIEWED` event carries
  `batchId`, so an audit can tell a bulk decision from a single one.
- There is no "accept everything matching this filter". The client sends ids the reviewer has seen.

**List filters.** `GET /inspections/web/observations` gains `modelName`, `modelVersion`,
`category` (repeatable), `minConfidence`, `maxConfidence`, `hasRegions`, `attachmentId`, and
`sort=confidence,asc|desc` next to the existing sort. New index
`(organization_id, model_name, model_version, review_status)` and
`(organization_id, category, review_status)` in the WP4 changeset.

**Tests.** Partial failure (item 3 of 5 invalid, the other four created); repeat batch is all
`EXISTING`; duplicate inside batch; bulk accept with one already reviewed; bulk reject without reason
on producer observations refused per item; filters combine; tenant isolation on `observationIds`
from another org (`NOT_FOUND`).

**Acceptance.** 100 items with 20 regions each take in under 5 s on staging; a bulk accept of 50
leaves 50 events with one `batchId`.

### 4.5 WP5: Core and web

**echno-core.** Types `ObservationRegion`, `RegionShape`, `RegionOrigin`, `RejectReason`,
`RegionEditOp`, `ReviewBatchRequest`, `ReviewBatchResult`; new list filter params; `vision` service
(`listModels`, `getSettings`, `updateSettings`, `queueRun`). Additive, parsed with non-strict zod as
the rest of core. Minor version bump.

**Region overlay.** One component, `RegionOverlay`, used in the review sheet and the evidence viewer:
the `<img>` with an absolutely positioned `<svg viewBox="0 0 1 1" preserveAspectRatio="none">` on top,
so stored fractions are drawn without conversion. Boxes are `<rect>`, polygons `<polygon>`, both with
`vector-effect="non-scaling-stroke"`. Labels (class and score) are HTML elements positioned from the
same fractions, since SVG text would stretch. One colour per class from a fixed palette; producer
regions dashed until reviewed, reviewer regions solid, retired regions hidden unless "show history" is
on. Browsers apply EXIF orientation to `<img>` by default, which matches the stored coordinates.

**Region editor (MODIFY).** In the review sheet's modify mode: select a region, drag its corners or
edges, move it, drag polygon vertices, change class (taxonomy classes of the same shape only), draw a
new box by dragging, delete with the Delete key. The editor emits the WP1 `regions` op list. Vertex
insertion on polygons is out of scope for this package.

**Grid review.** The observations queue gets a "Grid" view, offered when the source filter is `AI`.
Each tile is a crop of the observation's first region (CSS background crop, box padded by 20 percent,
at least 64 px), with class, score, model version and a checkbox. Click toggles, Shift-click selects
a range, a header checkbox selects the page. A bulk bar offers "Accept (no action)", "Reject as false
positive" and "Reject..." (reason and note dialog). Results come back per id; failures stay selected
with their message. Keyboard: `a` accept, `r` reject as false positive, arrow keys move focus.

**Filters.** Model (from `/vision/models`), version, class (multi-select), confidence range, "has
regions", sort by confidence.

**Org settings page.** `/users/dashboard/settings/vision` for org admins: auto-inference on or off,
and the model families to run (WP6). Hidden unless `vision` is in `/api/v1/modules/enabled`.

**Tests.** Vitest: fraction-to-pixel and crop maths; editor op list for replace, remove, add and
class change; grid range selection; bulk result handling with mixed statuses.

**Acceptance.** On staging, a reviewer can accept 40 helmet detections and reject 10 as false
positives from the grid in under two minutes, and correct one box in the sheet; the corrected box
shows on reload.

### 4.6 WP6: Inference jobs, triggers and ingestion

**What triggers a job.**

- **Automatic.** When the org is entitled to `vision` and its settings have `auto_infer = true`, every
  image registered as `INSPECTION_EVIDENCE` or `OBSERVATION_EVIDENCE` queues one job per enabled
  family (its `ACTIVE` version). The evidence services publish an `EvidenceImageRegistered` event
  (record in `modules/inspections/api`: org, project, inspection or observation id, attachment id,
  content type), and a `vision` listener handles it after commit.
- **On demand.** `POST /api/v1/vision/web/runs` (`vision:run`)
  `{ inspectionId? | observationId? | attachmentIds?, modelKeys?, rerun? }`, up to 200 images per call,
  returns `{ queued, skippedDuplicate, skippedNotImage }`.
- Producer uploads (`OBSERVATION_INTAKE_UPLOAD`) are not inferred again; they arrive with their
  detections.
- Defect photos are out of scope (they are not attachment rows).

Dataset consent does **not** gate inference. Running a model on an org's photos is a product feature
gated by entitlement and the org setting. Consent gates only the use of images and decisions for
training, which WP9 enforces in the export.

**Org settings.** Tenant-scoped table `cv_org_settings`: `organization_id` pk, `auto_infer` boolean
default false, `model_keys` jsonb (default `[]`), `updated_by_id`, `updated_at`. API
`GET|PUT /api/v1/vision/web/settings` (`vision:read` to read, org admin to write).

**Job table.** Changeset under `db/changelog/modules/vision/`. Columns the worker reads are R, writes W,
following the BIM contract.

| Column | Type | Who | Meaning |
|---|---|---|---|
| `id` | uuid | R | Job id. |
| `organization_id` | bigint | R | Tenant; log lines only. |
| `project_id` | bigint | | Copied for ingestion. |
| `attachment_id` | bigint | R | The image. |
| `inspection_id`, `observation_id` | uuid null | | Where the image came from. |
| `source_key` | varchar(500) | R | Object key of the image. |
| `content_type` | varchar(100) | R | |
| `model_version_id` | uuid | | Registry row. |
| `model_key`, `model_version` | varchar | R | Copied from the registry. |
| `weights_uri`, `weights_sha256` | varchar | R | Copied. |
| `input_size` | int | R | Copied. |
| `classes` | jsonb | R | Snapshot of the version's class list and thresholds. |
| `output_key` | varchar(500) | R | `vision/jobs/<jobId>/detections.json`. |
| `trigger` | varchar(20) | | `AUTO`, `ON_DEMAND`. |
| `requested_by_id` | bigint null | | On demand only. |
| `status` | varchar(20) | R W | `QUEUED`, `RUNNING`, `DONE`, `FAILED`. |
| `worker_id` | varchar(100) | W | |
| `attempt`, `max_attempts` | int | W, R | Default 3. |
| `lease_expires_at` | timestamp | W | |
| `detection_count` | int | W | Lines in `detections`. |
| `inference_ms` | int | W | Model time only. |
| `worker_version` | varchar(50) | W | |
| `error` | text | W | One line. |
| `queued_at`, `started_at`, `finished_at` | timestamp | R, W, W | |
| `ingested_at`, `observation_count` | | backend | Set by ingestion. |
| `created_at`, `updated_at` | timestamp | W (`updated_at`) | |

Indexes: `(status, queued_at)`; partial unique `(attachment_id, model_version_id) where status in
('QUEUED','RUNNING')`, so a second trigger for the same image and version while one is pending is
skipped. A finished job can be run again only on demand with `rerun = true`.

Claim, heartbeat and close are the BIM contract's statements with the table name changed, a lease of
5 minutes and a heartbeat every 60 seconds. The status transitions are the same, including the
backend poller that requeues expired leases and fails jobs out of attempts.

**`detections.json`** (written by the worker under `output_key`, content type `application/json`):

```json
{"jobId": "…", "modelKey": "ppe", "modelVersion": "0.1.0",
 "image": {"width": 4032, "height": 3024, "sha256": "…", "exifOrientationApplied": 6},
 "detections": [
   {"classKey": "ppe_helmet", "score": 0.81, "shape": "box", "box": [0.412, 0.188, 0.471, 0.262]},
   {"classKey": "crack", "score": 0.66, "shape": "polygon", "points": [[0.10,0.52],[0.18,0.49],[0.21,0.61]]}
 ],
 "inferenceMs": 214, "workerVersion": "0.1.0", "runtime": "onnxruntime 1.x"}
```

The worker writes every detection at or above the lowest class threshold in the snapshot, so the file
also serves for later threshold analysis.

**Ingestion.** A `vision` poller (every 10 s, guarded by `VisionModuleEnabled`) picks DONE jobs with
`ingested_at` null and, per job:

1. Reads `detections.json`.
2. Keeps detections at or above their class threshold, best score first, at most 50 per image
   (`echno.vision.max-observations-per-image`).
3. Builds one intake item per detection: source `AI`; `externalRef = "cv/<jobId>/<index>"`;
   `sourceDeviceId = "echno-vision-worker/<workerVersion>"`; `modelName = modelKey`;
   `modelVersion`; `confidence = score`; `category = classKey`; title "`<Class label>` detected";
   `inspection_id` and `spatial_node_id` from the source inspection when the image is inspection
   evidence; `observedAt` from the attachment's capture time, else its upload time; one region on
   `attachment_id`.
4. Calls `intakeAll` with an `IntakeActor` of type AI. Validation, events and idempotency are the
   same as for external producers. A crash between steps re-runs cleanly because the external refs
   repeat.
5. Sets `ingested_at` and `observation_count`.

The worker writes only its job row and its output object, the same rule as the BIM worker.

**Tests.** Trigger fires for an entitled org with auto-infer on and not otherwise; duplicate trigger
skipped; lease expiry requeues; ingestion threshold and cap; re-ingestion creates no duplicates;
module dark for the org means no jobs queued.

**Acceptance.** Uploading one photo to an inspection in the proof org yields a QUEUED job within a
second; with a fake worker that writes a fixture `detections.json`, the observations appear in the
queue with regions.

### 4.7 WP7: `echno-vision-worker`

New private repo `tornotron/echno-vision-worker`, modelled on `echno-bim-worker`: Python 3.12, `uv`,
Docker image on GHCR by digest, all configuration from the environment.

- **Dependencies.** `onnxruntime` (MIT), `numpy`, `Pillow`, `opencv-python-headless` (for mask to
  polygon), `psycopg`, `boto3`. No PyTorch in the image.
- **Environment.** `VISION_DATABASE_URL`, `VISION_S3_ENDPOINT_URL`, `VISION_S3_REGION`,
  `VISION_S3_ACCESS_KEY`, `VISION_S3_SECRET_KEY`, `VISION_S3_FORCE_PATH_STYLE`,
  `VISION_ATTACHMENTS_BUCKET`, `VISION_MODELS_BUCKET`, `VISION_THREADS` (default 4),
  `VISION_CACHE_DIR` (default `/var/cache/echno-vision`).
- **Loop.** Claim; download weights by `weights_uri` into the cache keyed by sha256 and verify the
  hash (mismatch fails the job); read the image; apply EXIF orientation; refuse anything other than
  JPEG, PNG or WebP and anything over 40 megapixels with a clear `error`; run the model; decode;
  write `detections.json`; close DONE. Sleep 5 s only when a claim returns nothing.
- **Model package.** Next to `model.onnx` the version folder holds `model.json`: input size,
  normalisation, output decoder (`rfdetr-detect` or `rfdetr-seg`) and class index to class key. The
  worker refuses a package whose class keys differ from the job's `classes` snapshot.
- **Polygons.** Masks are thresholded at 0.5, the largest external contour is traced and simplified
  (Douglas-Peucker, epsilon 0.002 of the image diagonal) to at most 200 points.
- **Commands.** `serve`; `predict <image> --package <dir>` prints `detections.json` locally, for
  debugging and for WP11.
- **Tests.** Claim race between two workers on a CockroachDB container; golden test with a fixture
  image and a tiny ONNX model against expected detections (tolerance 1e-3); an EXIF-rotated JPEG
  whose box must come out in display orientation; hash mismatch fails the job.
- **Performance.** CPU only. Measure RF-DETR Nano at its native input on a `.117` core set and
  record the number in the README; the design assumes well under 2 s per image, which is ample for
  inspection photos. Video, CCTV and tiled inference for small distant objects are later work and
  the reason for a GPU worker.

**Acceptance.** The worker processes 100 queued jobs on staging with no FAILED rows and the
detections match `predict` on the same images.

### 4.8 WP8: Deployment

In `echno-deployment`, mirroring the BIM worker:

- **Compose staging (`.116`).** `ansible/roles/apps`: `compose.vision-worker.yml.j2`,
  `vision-worker.env.j2`, `vision-worker-access.yml`, `vision-worker-minio-policy.json.j2`. Limits
  `cpus: "4"`, `mem_limit: 4g`. This adds one service through the role; no existing service is
  stopped or recreated. Needs D3.
- **k8s staging.** Helm values `visionWorker.enabled`, a Deployment with requests 2 CPU and 2 Gi,
  limits 4 CPU and 4 Gi, preferring `.117` and `.118` (AVX-512 VNNI), a NetworkPolicy allowing only
  CockroachDB and MinIO, secrets through ksops.
- **Database user** `vision_worker` with `SELECT, UPDATE` on `cv_inference_jobs` only.
- **MinIO policy.** `GetObject` on the attachments bucket, `PutObject` on `<attachments>/vision/jobs/*`,
  `GetObject` on `echno-models/*`.
- **Buckets.** `echno-models` on both stagings. `echno-datasets` on the k8s MinIO, which does not
  have it today. Production buckets on Spaces wait for the first client model.
- **Backend config.** `echno.modules.vision.enabled=true` and
  `echno.vision.accept-draft-models=true` on staging only.

**Acceptance.** `make deploy` on both stagings is idempotent; the worker logs a claim loop; a manual
on-demand run completes.

### 4.9 WP9: Feedback export and dataset tooling

**Backend: feedback in the export.** Each export run also writes
`export/<runKey>/feedback.jsonl`: one line per reviewed observation that has regions, in a consenting
org, reviewed after the previous successful run's cursor. New columns on the export run table:
`feedback_from`, `feedback_until`, `feedback_lines`. A new source kind `INTAKE_UPLOAD` (folder
`intake-uploads`) exports producer-uploaded images so their feedback has an image to join to.

```json
{"observation_id": "…", "evidence_id": "<WP0 format>", "attachment_id": 18342,
 "org_id": 7, "consent_org_id": 7, "project_id": 12,
 "model_key": "ppe", "model_version": "0.1.0",
 "review_status": "MODIFIED", "reject_reason": null, "reviewed_at": "2026-11-20T10:14:03Z",
 "review_batch": false, "reviewer_ref": "3f9a1c0d7e2b4a65",
 "regions": [
   {"region_id": "…", "origin": "producer", "state": "replaced", "class_key": "ppe_helmet",
    "shape": "box", "bbox": [0.412, 0.188, 0.471, 0.262], "points": null, "score": 0.81},
   {"region_id": "…", "origin": "reviewer", "state": "active", "supersedes": "…",
    "class_key": "ppe_helmet", "shape": "box", "bbox": [0.40, 0.18, 0.48, 0.27], "points": null, "score": null}
 ]}
```

`reviewer_ref` is a per-org salted hash of the reviewer's employee id (first 16 hex characters of
SHA-256), so annotator agreement can be studied without personal data in the bucket. This WP starts
after WP0 merges, since it touches the same export code.

**Tooling: `echno-datasets import-feedback --run <runKey> --version f<N>`.**

- ACCEPTED and MODIFIED active regions become pre-annotations for a CVAT task, tagged
  `source=review`, `model_version`, `reviewer_ref`.
- REJECTED with `FALSE_POSITIVE`: the rejected region goes to
  `annotations/f<N>/hard_negatives.jsonl` (image, bbox or polygon, predicted class, score, model
  version) and the image joins a hard-negative pool that WP10 oversamples (weight 2).
- Other reject reasons are recorded and ignored for training.
- Feedback never goes straight into a release. A reviewed image is only partly labelled (the model
  saw some objects, the reviewer judged those), so it is completed in CVAT first: the annotator adds
  missed objects and confirms the image, and only then does it become part of an annotation version.

**Tooling: `import-cvat --task <id> --version a<N>`.** Pulls a CVAT COCO export through the CVAT
API, maps CVAT `file_name` back to `raw/` keys, checks every image exists in `raw/`, and writes
`annotations/a<N>/coco.coco.json` with a per-image `preannotated` flag. This closes the CVAT-to-release
gap the recon found.

**Tooling: group split.** `release build` splits by capture id hash today, so near-identical frames
from one standpoint can land in train and test. Change `_split_for` to hash a group key (session id
for field sessions, inspection id for exported evidence, the image itself only when neither exists).
The test split also excludes images with `preannotated = true`, so the independent test set is never
labelled from a model's own suggestions.

**Tooling: `ingest-session <folder>`.** Reads a field-card session folder with `session.json`,
validates it against the schema, and writes `raw/<session_id>/` plus manifest lines with
`source = field_session`. Built now, first used in WP13.

**Tooling: `classes.json`.** Generated from `classes.py`, committed, checked by a test; vendored by
WP2.

**Tests.** Backend: feedback lines for each review status and reason, consent off means no lines,
cursor advances. Tooling: fixtures for each feedback case; CVAT export with mismatched names fails
loudly; group split keeps all images of a session in one split.

**Acceptance.** A run on staging produces `feedback.jsonl`; `import-feedback` creates a CVAT task with
the pre-annotations; `import-cvat` brings it back as `a1`.

### 4.10 WP10: `echno-vision-train`

New private repo `tornotron/echno-vision-train`. PyTorch and `rfdetr` pinned to exact versions; a
`uv` lock; runs from a venv.

**Where it runs.**

| Place | Use | Condition |
|---|---|---|
| hp-z, GPU 1 (RTX A5000 24 GB) | All training, including pilot data | D2. Venv under `/hdd2/fereydon/vision`, PyTorch CUDA 12.1 wheels on the installed 535 driver, `CUDA_VISIBLE_DEVICES=1`, run under `nice` and a `systemd-run --user` CPU quota. No driver, CUDA, Docker or reboot changes: hp-z is the primary subnet router. Data pulled over the lab LAN from MinIO. |
| Amrita School HPC | Public-data training (R1) | Only data with no client consent attached. |
| Rented cloud GPU by the hour | Public-data training | Same. |
| `.117`/`.118` CPU | Evaluation and ONNX export | Always available. |

Pilot images stay on IITM hardware unless the pilot consent clause names other processing locations.

**Experiment tracking.** A run record per training run in the `echno-models` bucket, with a fixed
layout. MLflow (Apache-2.0) is the upgrade path once more than one person trains; the record layout
maps onto MLflow's run model, so nothing is lost.

```
echno-models/runs/<runId>/                     runId = <modelKey>-<UTC yyyymmddThhmm>-<git short sha>
  config.yaml          full training config, resolved
  git.json             repo sha; a dirty tree refuses to start
  env.json             torch, rfdetr, CUDA, GPU name, host
  dataset.json         release version, manifest sha256, split counts, hard-negative pool size
  metrics.json         summary (below)
  eval/report.md       human-readable report
  eval/slices.csv      per-slice metrics
  eval/pr_curves.json
  weights/model.onnx, weights/model.json
  log.txt
```

**Metrics.** Computed on the release's `test` split, never on val:

- Per class at the chosen operating threshold: TP, FP, FN, precision, recall, F1.
- AP50 and AP50:95 (box for DETECT, mask for SEGMENT).
- The operating threshold per class is chosen on `val`: the lowest threshold where precision reaches
  0.5. Review is human, so the default favours recall; the value goes into the version's `classes`.
- Slices, from the image-level fields of the annotation spec: `lighting`, `device`, `stage`,
  `distance_band`, and the `negative_type` of hard negatives (false-positive rate per type). Each
  slice reports images, instances, P, R and F1. Slices with fewer than 30 instances are printed and
  flagged "too small to judge".
- Hard-negative false-positive rate: share of hard-negative regions the model still fires on.

**Commands.** `fetch --release v0.1` (downloads and verifies sha256 per image), `train <config>`,
`evaluate --run <runId>`, `export --run <runId>` (ONNX plus `model.json`; refuses PML checkpoint
names), `publish --run <runId> --model <key> --version <semver>` (uploads to
`echno-models/<key>/<version>/` and registers a DRAFT version through the WP2 admin API with
`metrics`, `eval_report_uri`, `training_release`, `training_run_uri`, `licence`, `base_weights`).

**Promotion.** A system-admin reads the eval report and activates the version (WP2). The registry
page shows the candidate's metrics beside the active version's when both were measured on the same
test release, and says "not comparable" otherwise. Activation is a human decision; there is no
automatic promotion.

**Tests.** Unit tests on metric maths against hand-computed cases, slice grouping, threshold
selection; a smoke train of one epoch on 16 fixture images on CPU in CI.

**Acceptance.** A run on `v0.0-synthetic` produces a complete run record, and `publish` creates a
DRAFT version the worker can load.

### 4.11 WP11: First model families and the reference safety models

**Families and classes** (defaults from the dataset note; Anand confirms in A1):

| Model key | Family | Task | Classes | Shape |
|---|---|---|---|---|
| `ppe` | SAFETY | DETECT | `worker`, `ppe_helmet`, `ppe_vest` | BOX |
| `site-objects` | OBJECT | DETECT | `rebar` (more 8.1 classes later) | BOX |
| `concrete-defects` | DEFECT | SEGMENT | `crack`, `honeycombing` | POLYGON |

Safety gets its own family and its own review flow (it shares the infrastructure, as roadmap section
11 asks), and defects get a segmentation family, since a crack's extent matters more than its box.

**No usable pretrained helmet and vest detector exists under a permissive licence.** A search of the
Hugging Face hub on 26 Oct 2026 found helmet detectors that are either Ultralytics derivatives
(AGPL-3.0) or course fine-tunes with no licence and no provenance. So the reference model comes in
two steps:

- **R0, `ppe` 0.0.1.** RF-DETR Nano with its COCO weights (Apache-2.0), zero-shot, class `worker`
  only (COCO `person`). No training. It proves the whole pipe today: upload, job, worker, intake,
  review, export.
- **R1, `ppe` 0.1.0.** RF-DETR Nano fine-tuned for `worker`, `ppe_helmet`, `ppe_vest`. Helmet data:
  SHWD (`njvisionpower/Safety-Helmet-Wearing-Dataset`, MIT repository; its images were collected from
  web search, so it is used only for this stand-in, D5). Vest data: the 60 public proof images
  labelled in CVAT during WP12. It exists to exercise the loop. Its report is labelled as a stand-in,
  and the version is never enabled for a client org.

**Acceptance.** R0 registered and active on staging; R1 trained, evaluated with a full run record,
registered DRAFT and activated on staging.

### 4.12 WP12: End-to-end proof without pilot data

Runs on the `.116` compose staging (`echno.in`, D3) in a new organisation "CV Proof", with dataset
consent on, `MODULE_VISION` granted, auto-inference on for `ppe`, and a Keycloak service account
`cv-proof-producer` holding `observation-producer` in that org. No real client data is involved.

**Proof image set** (committed as `proof-set/sources.csv` in echno-datasets: file, source URL,
author, licence):

- 60 Wikimedia Commons photos of construction work with helmets and vests, licence read per file from
  the Commons API (`imageinfo` with `extmetadata.LicenseShortName`). Only CC0, public domain and
  CC BY (2.0 to 4.0); CC BY-SA is left out so no share-alike question attaches to our annotations.
  Most contain faces, which exercises the anonymiser.
- 20 crack images from SDNET2018 (Utah State University, CC BY 4.0 per its DigitalCommons page),
  for a polygon class in CVAT.
- 10 negatives from Commons (concrete surfaces with formwork lines and joints).

**Steps and expected artefacts.**

1. Upload the 90 images as inspection evidence on two inspections in the proof org. Expect 90
   attachments and, through auto-inference with R0, 90 QUEUED jobs.
2. Worker runs. Expect 90 DONE jobs and `worker` observations with regions in the queue.
3. Review in the grid: bulk accept most, reject at least 5 as `FALSE_POSITIVE` and 2 as
   `DUPLICATE`, modify 3 boxes and add 1 missed region in the sheet. Expect events with a `batchId`
   for the bulk decisions.
4. Producer path: `cv-proof-producer` uploads 5 images through WP3 and posts a batch of 10 detections
   through WP4. Expect 10 CREATED, and 10 EXISTING on a repeat.
5. Dataset export run (system-admin). Expect `manifest.jsonl` with 95 lines that pass the tooling
   validator (WP0) and a `feedback.jsonl` whose counts match step 3.
6. `echno-datasets anonymise`. Expect 95 images in `raw/`, faces blurred.
7. `import-a0`, then `import-feedback --version f1`. Expect a CVAT task with pre-annotations and a
   `hard_negatives.jsonl` with at least 5 lines.
8. CVAT: annotators label `worker`, `ppe_helmet`, `ppe_vest` on the 60 Commons images and `crack`
   polygons on the 20 SDNET2018 images, then `import-cvat --version a1`.
9. `release build --version v0.0-synthetic` with the v0.1 milestone check off. Confirm the builder
   accepts this version string; extend its pattern if it does not. Expect `manifest.jsonl`,
   `splits.json` (group split) and `counts.json`.
10. Train R1 (WP11) on SHWD plus the `v0.0-synthetic` train split; evaluate on its test split;
    publish and activate `ppe` 0.1.0.
11. On-demand rerun of one inspection with R1. Review again. Export again. Expect feedback lines with
    `model_version` 0.1.0.

**Acceptance.** Every step's artefact exists with the expected counts, recorded in a short run log
in echno-datasets (`docs/proof-runs/2026-11-<dd>.md`). This meets the roadmap's section 35 definition
of done for computer vision and safety on stand-in data: a versioned dataset trains and validates a
baseline model, outputs are stored with confidence and model version, and safety detections create
reviewable observations with evidence and human verification.

### 4.13 WP13: Pilot capture and release v0.1 (blocked on pilot)

Needs A2 and A3. One half-day field-card session at an Asset Homes site, ingested with
`ingest-session`; the pilot org's own inspection photos exported under consent; annotation in CVAT
by the D4 annotators; masks and severities reviewed by a second person and boxes spot-checked at 10
percent (dataset note); release `v0.1` when `counts.json` meets the dataset note's milestone (300
images per object class, 150 masks per defect class, one negative per two positives).

### 4.14 WP14: Pilot baselines and validation report (blocked on pilot)

Train `ppe`, `site-objects` and `concrete-defects` on `v0.1` with WP10; full slice report by
lighting, device, stage and distance band; activate for the pilot org only after Abhijith reads the
report and Anand agrees the detections can be shown to the client's users. Accuracy claims to a
client are made only from this report.

## 5. Decisions for Anand

| # | Decision | Default if not decided |
|---|---|---|
| A1 | First target classes, by business value for Asset Homes | Safety first (`worker`, `ppe_helmet`, `ppe_vest`): every site photo carries examples and the review is quick. Then `crack` and `honeycombing`, then `rebar`. |
| A2 | Pilot consent clause | Asset Homes agrees that site photos captured in Echno and in capture sessions may be used by Fereydon to train and evaluate models; faces and number plates are blurred before annotation; images are not redistributed; the client may withdraw (removal from future releases); **models trained on them may be used for other clients**, without the images. The last point is commercial and needs his explicit call. |
| A3 | Capture session | One half-day session on the field card, at one site, with a named site contact, before the end of November. |
| A4 | Whether the pilot org's users see AI detections during the pilot | Internal only (Fereydon reviewers) until WP14's report exists. |

## 6. What stays blocked on the pilot

- Any model trained or validated on Indian site conditions, and any accuracy figure given to a
  client.
- Release `v0.1` and its milestone counts.
- Defect models: cracks and honeycombing need site images with the negatives the dataset note lists
  (joints, formwork lines, scaffold shadows), which public sets do not cover.
- Slice metrics by lighting, device and stage that mean anything.
- Progress-state recognition, which needs repeat captures from fixed standpoints over weeks plus BIM
  element association.
- Annotation of real images, which also needs annotator time (D4) and the consent clause (A2).

## 7. Non-goals for Phase 4

- Auto-accept above any confidence. No such path exists or will be added.
- Face recognition or identifying people. `worker` means "a person is present"; nothing links a
  detection to an employee.
- Video, CCTV streams and live inference.
- Edge inference on drones or robots (roadmap section 27.1). The site-edge role in 27.2 is served by
  the lab worker for now.
- Azure. Model management stays in the registry and the `echno-models` bucket.

## 8. Open questions, defaulted

| Question | Default taken |
|---|---|
| One observation per detection or per image? | Per detection. |
| Does dataset consent gate inference? | No. It gates training use only. |
| Can a producer reference existing inspection photos? | Yes, within the same project. |
| Bulk MODIFY? | No. MODIFY is single-item. |
| Bulk ACCEPT with an outcome other than NONE? | No. |
| Operating threshold rule | Lowest threshold with precision at least 0.5 on val, per class. |
| Max observations per image | 50. |
| Where do worker outputs live? | Attachments bucket under `vision/jobs/<jobId>/`, like BIM under `bim/`. |
| Experiment tracking server | None at first; run records in the bucket; MLflow later. |
| Production model storage | Decided with the first client model. |
