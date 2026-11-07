# Document ingestion: tender documents to reviewed project data

**Date:** 2026-11-08
**Status:** Design for review. In scope for this version by the CTO's decision. No code lands with
this note; section 11 is the build plan.
**Tickets:** ClickUp `14zdkkvrnku` (Priority 3 Document Ingestion Protocol) and subtasks `14zdkkvrnxf`,
`14zdkkvrnxg`, `14zdkkvrnxk`, `14zdkkvrnxh`, `14zdkkvrnxu`, `14zdkkvrny5`, `14zdkkvrny9`, `14zdkkvrnye`.
GitHub #957.
**Builds on:** `2026-08-26-modular-plugin-architecture.md`, `2026-09-13-bim-import-contract.md` (worker
pattern), `2026-10-04-ra-milestone-billing.md` (contract BOQ), `2026-09-12-qaqc-ontology-as-data.md`.

---

## 1. Summary

A person uploads tender files. Echno reads them, proposes values, and shows each value beside the
page, sheet or cell it came from. Nothing reaches live project data until a person accepts it. Every
accepted value keeps a link to its source for good.

- **One module**, `document-ingestion`, with one pipeline and one extractor per document kind.
- **One new worker**, `echno-ingest-worker`, which parses files and holds no model key. The backend
  makes every LLM call, through the existing `compliance.ai.*` configuration.
- **Off by default in every entitlement mode** (section 6.7).
- **The first useful thing** is a BOQ Excel file loaded into a contract's BOQ after review. It needs no
  LLM and no PDF parsing. That is step 9 of the plan.

Three parts of the tickets cannot be delivered as written, and this note says so where each comes up:

1. **"Tender to project in one click"** is delivered as a review screen that creates part of the list
   the ticket names (section 4.1). Materials, activities, procurement and HSE plans are mostly absent
   from tender documents, so an LLM would be inventing them.
2. **Drawing understanding** (column counts, reinforcement, room areas, BOQ validation from drawings)
   is not reliable with anything we can ship now. This version reads title blocks and keeps a drawing
   register.
3. **DWG files** cannot be read by any library we are willing to ship. They are stored and registered
   by file name, and the user is asked for a PDF or DXF copy.

## 2. What the tickets say, and how conflicts were settled

The latest ticket or comment wins, as instructed. The QA/QC manual and project-management material the
ticket calls "uploaded" are on neither the ticket nor the repositories, so this note uses only what the
ticket text says about them. The two attachments are screenshots of the Edit Project page and the
project tabs.

| Conflict | Earlier text | Later text | Settled as |
|---|---|---|---|
| Who starts it | Comment of 21 Sep 2026: files added to Project Attachments on an existing project fill the tabs | Comment of 29 Sep 2026: "If they upload the documents, Echno will automatically reads the details and create project automatically" | Both entry points: a new package creates a project, and a package can target an existing project |
| Automatic or reviewed | Comments say "automatically" | Main ticket section 5: "Review Before Creating Project" | Reviewed always. This is also the CTO's rule |
| Project or pre-award | Main ticket assumes a project results | Qualification stub: "relevant only at tendering stage, if the project is already awarded, this doesn't matter" | A package can exist with no project; it carries a stage, `TENDER` or `AWARDED` |
| Order | Main ticket lists a long pipeline | BOQ stub: "first thing we should build"; Corrigendum stub: "built into the architecture from day one" | BOQ ships first. The revision chain is in the schema from step 4, the corrigendum screens come later |
| Priority | Parent is urgent | QA/QC stub is the only subtask at normal priority | QA/QC comes late in the plan |
| Scale | Section 5 shows 612 activities, 387 materials, 17 conflicts | Drawing stub says "Eventually" | The figures are treated as an illustration of the vision and are no acceptance target |

## 3. What exists today, verified

The brief asked that nothing be assumed reusable. Each line was checked against `development` at
`85ae06d7` or against the named PR.

| Thought to exist | What is there | Consequence |
|---|---|---|
| Excel import for BOQ in `modules/workprogress` | **Does not exist.** No Excel library in `build.gradle`, no parser in backend or web. `2026-10-04-ra-milestone-billing.md` section 8 lists it as not built: "no sample layout yet". The BOQ is entered by hand through `ContractBillingService.addBoqItem` | Step 7 builds it. The landing table `contract_boq_item` is real |
| A BOQ for the project | **Only per sub-contract.** `contract_boq_item.sub_contract_id` is required, and an approved bill creates a `Payable`, which is money going out | A tender BOQ is what the client pays the contractor. It has no home today. See question Q1 |
| Module contract and Toolbox Talks | Real. `EchnoModule`, `ModuleManifest`, `./gradlew scaffoldModule`, ArchUnit rules in `ModuleBoundaryTest` | Reused. But the scaffold's kill switch has `matchIfMissing = true`, which section 6.7 must override |
| Module boundary | `modulesReachEachOtherOnlyThroughApi`. `work-progress` has no `api` package | Step 7 adds `modules/workprogress/api` with a BOQ port |
| Attachments and presigned upload | Real, with limits to respect. Multipart is capped at 10 MB in `application.yml` and in the web project form. `presignUploads` enforces neither size nor type. `objectExists` checks presence only | The project form cannot take a tender package. We follow `BimUploadService`: own presign, own limits, `generateUploadUrlForKey` |
| Compliance LLM call | Real but tied to its use: `OpenAiCompatibleComplianceService` takes a `Project` and rules, and sits outside any `api` package. It sends no `response_format`. It reads `usage.completion_tokens` only to spot a cut-off answer, and records no token counts | Step 10 extracts a small client into `common/llm`, bound to the same `compliance.ai` prefix |
| Compliance background job | Real: conditional-update claim, lease, attempt count, deterministic failures closed at once. Bound to `inspections` | The shape is copied for the backend extract queue. `TenantScopedJobRunner` and `TransactionRetryTemplate` are in `common` and reused directly |
| Assistant document provider and vector store | **Mostly not built.** #891 adds the `assistant_document_unit` table and vector query. #892 adds the provider contract and an attendance provider only. `DocumentProvider`, `DocumentExtractor` and `AssistantLlmClient` are stage 3 of `2026-09-20-assistant-module.md`, which plans PDFBox, no OCR, no drawings. Both PRs are open; #891 has changes requested | Nothing here depends on them. Extraction reads pages in order and needs no embeddings. Section 12 gives the seam for later |
| `echno-bim-worker` | Real and proven: job row claim, heartbeat, close; object-store prefix; backend reads outputs. It carries IfcOpenShell and a 4 GiB limit | Pattern copied into a new repo. Not extended, because the two have nothing to share at run time |
| `2026-10-26-cv-phase4-design.md` | A design with work on branches; `modules/vision` is not on `development` | Same job pattern and licence stance. Nothing to call |
| Project fields | `Project` has name, address, type, dates, description, `customerId`. It has no contract value, tender deadline or bid validity | Those go to a module table (section 6.3) |
| Quality checklists | `ChecklistTemplate` is unique per organization and trade, so one template serves every project | A spec for one project must not silently change it (section 4.4) |
| Company profile for bidding | **Does not exist.** `Organization` holds contact details only. Assets and employees exist but carry no turnover or past projects | The module adds a small profile (section 4.7) |
| Risk import | Real. `ProjectRiskService.importRisks`, at most 500, idempotent on `importRef` per project | Used as is |
| Bulk WBS | Real. `POST` bulk on the WBS controller; `WbsElement` has `isMilestone`, `budgetedCost`, dates | Used as is |

## 4. The eight stubs

**Rules common to all eight.**

- Extraction writes only to `doc_ingest_*` candidate rows. Applying to any other table happens in one
  code path, inside a request made by a signed-in person who has accepted the rows. An ArchUnit rule
  and a test hold this (section 6.5).
- Each accepted value has a source reference: document, content hash, and page plus box for PDF, sheet,
  row and cell for Excel, or paragraph number for DOCX, which has no fixed pages.
- Values the model states with no supporting text are dropped or flagged, per section 8.3.
- "Done when" lines are the acceptance tests.

The ticket lists 50 document classes. This version classifies 13: `NIT`, `ITB`, `ELIGIBILITY`, `GCC`,
`SCC`, `CONTRACT_DATA`, `SCOPE`, `BOQ`, `SPECIFICATION`, `DRAWING`, `MILESTONE_SCHEDULE`, `CORRIGENDUM`,
`OTHER`. Everything else is `OTHER`: stored, registered, downloadable, and not read further.

### 4.1 Documents to Project setup (`14zdkkvrnxf`)

| | |
|---|---|
| **Input** | A ZIP, or up to 500 loose files: PDF, XLSX, XLS, DOCX, DXF, DWG. Limits in section 9 |
| **Extracted** | Per file: class, revision label, date. One PDF or workbook holding several documents is split by page range or sheet, as the main ticket's last paragraph asks. From `NIT`, `ITB`, `SCOPE`: project name, location, client, estimated value, EMD, tender fee, bid due date, bid validity, duration, consultant, engineer |
| **Lands in** | `Project` (name, address fields, type, dates, description) through the normal create service, with status `upcoming`, so the compliance run still waits for approval. The fields `Project` lacks go to `doc_ingest_tender_profile`. The document list itself is the Document Register |
| **Reviewer** | `system-admin` or `project-manager`, the roles on project creation |
| **Done when** | A fixture package with a NIT, GCC and BOQ shows the right classes; a person corrects one and confirms; the project form fields appear with citations; accepting creates one `Project` with provenance on each field; rejecting creates nothing |

What the ticket's project tree gets in this version:

| Ticket item | This version | Why |
|---|---|---|
| Document Register | **Yes** | Every file is a row |
| Scope | **Yes**, as text on the tender profile | Quoted from `SCOPE` |
| BOQ | **Yes** | Section 4.2 |
| WBS | **Partly**: BOQ sections become WBS levels | The structure is in the BOQ. Activities are absent from it |
| Payment Milestones, Schedule | **Partly**: milestones only | Section 4.6 |
| Contract, Risks | **Partly**: terms, obligations, risk candidates | Section 4.5 |
| Drawings, Specifications | **Register only**, plus quality requirements | Sections 4.3, 4.4 |
| QA/QC | **Requirements, per project** | Section 4.4 |
| Compliance | **Unchanged** | The existing run fires on project approval |
| Cost Baseline | **Partly**: section totals on `WbsElement.budgetedCost` | Budget categories need a person's mapping |
| Resources, Materials, Procurement, HSE | **No** | Tenders rarely state them item by item. See section 10 |

### 4.2 BOQ Ingestion (`14zdkkvrnxg`)

| | |
|---|---|
| **Input** | XLSX or XLS first. Text PDF second. Scanned PDF is accepted with every row flagged for checking |
| **Extracted** | Section headings; per item: code, description, unit, quantity, rate, amount, and cell references. Excel uses no LLM: header detection by column synonyms, a person confirms the column mapping per sheet |
| **Checks** | Quantity times rate equals amount; section totals; duplicate codes; Indian-format numbers held as text; formula cells with no cached value; hidden rows and sheets; lump-sum and provisional-sum lines with no quantity (proposed as 1 LS and flagged); an unpriced BOQ (rate 0 is allowed by `BoqItemRequest`) |
| **Lands in** | `contract_boq_item` on a contract the person picks, within its limits (code 50, unit 30, description 4000 characters, quantity above zero). Optionally a WBS tree from the sections. With no contract yet, accepted rows stay as the package's BOQ baseline. Q1 covers the main-contract case |
| **Reviewer** | `system-admin` or `project-manager` (`BILLING_SETUP_GUARD`) |
| **Done when** | The ticket's four-row sample, a 2,000-row workbook and a three-sheet workbook load with no row lost; a wrong amount is flagged; accepting 2,000 rows survives a retry midway with no duplicate; each row shows its sheet and row, as in `BOQ.xlsx, Sheet 2, Row 47` |

**Not achievable as written.** The stub chains a BOQ item through Work Package, Activity, Resource,
Material, QA/QC checklist, Inspection and Payment Milestone, for example "RCC M25" giving cement,
aggregate, slump test and pre-pour inspection. Those facts are absent from the BOQ row, so they have
no source page and cannot pass the provenance rule. They are engineering knowledge that a model would
supply from memory. Moved to section 10 as a separately labelled suggestion feature.

### 4.3 Drawing Ingestion (`14zdkkvrnxk`)

| | |
|---|---|
| **Input** | PDF (vector or scanned), DXF, DWG |
| **Extracted** | Title block only: drawing number, title, discipline, revision, date, scale, and a revision history table if it is printed. PDF: words in the title block region of the page with their boxes, parsed by patterns, with the LLM only when patterns fail. Scanned PDF: OCR on the cropped region. DXF: `ezdxf` block attributes and layout names. DWG: file name only |
| **Lands in** | `doc_ingest_drawing`, the register: current revision per number, and older revisions kept. No core table changes |
| **Reviewer** | `system-admin`, `project-manager` or `site-engineer` |
| **Done when** | A 100-sheet PDF set gives 100 candidates with the crop shown beside each; two revisions of one number link up with the newer marked current; a DWG gives a register row that says "not read, add a PDF or DXF copy" |

**Say plainly: the rest of this stub is v2 or later.** Floors, rooms, dimensions, areas, door and window
schedules, grid lines, column and beam IDs, reinforcement, pipe and cable sizes, MEP quantities, and the
ticket's own example (drawing shows 42 columns, BOQ says 38) are all out.

- A PDF drawing is lines and text fragments. Knowing that a mark is a column of type C1, on this level,
  and counted once across plan, section and schedule needs trained symbol recognition per office
  drafting style. We hold no labelled data, and Phase 4 shows how long such data takes.
- A count that is wrong is worse than none here: the feature's use is flagging disputes on money.
- The dependable route to quantities is the model. Where an IFC exists, the BIM module already has
  elements and quantity sets, and comparing those with the BOQ is the better next step (section 10).
- A small next candidate: schedule tables printed as text on vector PDFs can be read as ordinary
  tables. Worth a spike after this version, with no commitment here.

### 4.4 QA/QC Ingestion (`14zdkkvrnxh`)

| | |
|---|---|
| **Input** | `SPECIFICATION`, and quality sections of other documents, as text PDF. Scanned PDF is accepted and reported separately against its gate |
| **Extracted** | Per requirement: activity or trade (matched to the org's trades), material, grade, standards cited (`IS 456:2000` pattern first), test, frequency, acceptance criterion, hold or witness point, required documents |
| **Lands in** | `doc_ingest_quality_requirement`, a per-project ITP view with citations. A second, separate action copies chosen rows as items into the org's `ChecklistTemplate` for that trade |
| **Reviewer** | `system-admin`, `qa-engineer` or `project-manager` to accept. The template copy needs `@inspectionSecurity.canDefineChecklists()` |
| **Done when** | The ticket's M25 sentence gives activity, material, grade and standard with quotes; frequency and acceptance criterion stay empty and flagged when the spec does not state them; accepting a requirement leaves the org template untouched until the second action |

**Limit to state.** The stub's example fills in slump and cube tests from a sentence naming neither.
Under the provenance rule such a value appears only if the spec says it. Frequency and acceptance values
are quote-only: the model may never supply them from its own knowledge, because a wrong acceptance
criterion carries into site work.

### 4.5 Contract Intelligence (`14zdkkvrnxu`)

| | |
|---|---|
| **Input** | `GCC`, `SCC`, `CONTRACT_DATA` |
| **Extracted** | The stub's 19 terms: duration, mobilization, EMD, performance security, retention, advance, payment cycle, payment period, LD rate, LD cap, DLP, insurance, escalation, subcontracting, extension of time, variation, dispute resolution, applicable law, termination. Each has a value, clause reference and quote. Where SCC or contract data restates a GCC term, both are shown and the SCC value is proposed. Also obligations with a stated deadline ("within 14 days of...") as register rows: clause, responsible party, trigger, deadline, required document, financial impact |
| **Lands in** | `doc_ingest_contract_term` and `doc_ingest_obligation`, the Contract Obligation Register. A term or obligation can raise a `ProjectRisk` through `importRisks`, with `importRef` set from the candidate id |
| **Reviewer** | `system-admin` or `project-manager` (`RiskAccess.MANAGE_GUARD` for the risk action) |
| **Done when** | On corpus contracts each of the 19 terms is found or marked "not found, pages searched: N to M"; an SCC override shows both clauses; accepting an LD term and raising a risk twice creates one risk |

**Limits to state.** The fixed 19 terms are a realistic target. A complete list of every obligation in a
200-page GCC is not, and the screen says "obligations found" and never "all obligations". Echno does not
interpret clauses and this is no legal advice. These terms are also not written to
`contract_deduction_rule` or to sub-contract fields, because tender GCC terms govern the main contract
and those tables describe sub-contracts. See Q1.

### 4.6 Milestone Intelligence (`14zdkkvrny5`)

| | |
|---|---|
| **Input** | `MILESTONE_SCHEDULE` as Excel or a table in a PDF; milestone tables inside contract data |
| **Extracted** | Name, date or relative time ("Month 4"), payment percent or amount |
| **Checks** | Percentages total 100; dates ascend. "Month N" is resolved against the accepted project start date, and the original text is kept. With no start date the target stays empty and flagged |
| **Lands in** | `WbsElement` with `isMilestone = true` for the schedule. `ContractMilestone` on a picked contract for the payment side |
| **Reviewer** | `system-admin` or `project-manager` |
| **Done when** | The ticket's five-row table gives five WBS milestones at the right dates from a start date, and five contract milestones that a Milestone bill can use; percentages totalling 95 are flagged |

**Limit to state.** The stub's chain continues to activities, progress measurement, client inspection and
RA bill. Ingestion stops at the milestone rows. The rest of that chain is the existing Work Progress and
billing flow run by people, and activities are absent from a milestone table.

### 4.7 Qualification and Bidding (`14zdkkvrny9`)

| | |
|---|---|
| **Input** | `NIT`, `ITB`, `ELIGIBILITY` on a package at stage `TENDER`. Hidden at `AWARDED`, as the stub says |
| **Extracted** | Minimum turnover, number and value of similar projects, years of experience, required equipment, required personnel, each with its quote |
| **Lands in** | `doc_ingest_qualification_requirement`, compared against `doc_ingest_company_profile`, a form an admin fills in by hand with evidence attachments. Nothing else is written |
| **Output** | The Tender Compliance Matrix: requirement, tender value, company value, status (`MATCH`, `GAP`, `UNKNOWN`), evidence |
| **Reviewer** | `system-admin` or `project-manager` accept the requirements. The bid decision is theirs |
| **Done when** | The stub's example yields its five-row matrix with key personnel as `GAP`; a blank profile field gives `UNKNOWN` and never `GAP` |

**Limits to state.** Numeric comparison is automatic: turnover, counts, value, years. Equipment and
personnel are text, so a person sets those statuses. Echno shows no bid or no-bid recommendation.

### 4.8 Corrigendum (`14zdkkvrnye`)

| | |
|---|---|
| **Input** | `CORRIGENDUM` as PDF, or a revised BOQ or schedule file |
| **Extracted** | One change per affected item: target, old value, new value, source quote. A revised Excel gives a row-by-row comparison with no LLM. A PDF gives statements the LLM parses and the backend then matches to prior candidates by natural key, such as BOQ code |
| **Computed** | Quantity variance and money impact from accepted values: the stub's +70 m3 at 9,800 gives 686,000. These are arithmetic on sourced numbers |
| **Lands in** | `CHANGE` candidates. Accepting one supersedes the prior candidate. Applying updates the target only if the target still holds the value last applied; otherwise a person sees all three values and chooses. Provenance keeps the whole chain |
| **Reviewer** | Whoever may review the kind being changed |
| **Done when** | The stub's example gives one change, 850 to 920, +70, 686,000; a statement matching no item goes to an "unmatched" list and is never dropped; a target edited by hand since the last apply is never overwritten silently |

**Limits to state.** "Related drawing: verify" is honest and we show it whenever no link is known.
"Related specification: unchanged" is a claim we cannot back, so it is not shown. "Tender submission
impact: YES" becomes a flag when the corrigendum changes a date, BOQ or specification before the bid due
date, and a person decides what it means.

## 5. How reliable each part can be

These are expectations. No tender corpus exists yet, so step 1 builds one and each extractor has a gate
measured on it. A gate missed means that part ships flagged for row-by-row checking, or waits.

| Part | Text PDF or Excel | Scanned | Gate on the corpus |
|---|---|---|---|
| BOQ from Excel | High; deterministic | n/a | Rows captured 99%; numbers exact 99.5% on captured rows |
| BOQ from PDF tables | Medium; ruled tables go well, borderless ones less so | Low; flagged always | Rows captured 90% on text PDF; report scanned separately |
| Document class | High for the 13 classes | Medium | 90% correct per document |
| Project and contract terms | High when stated plainly | Medium, tied to OCR | Precision 95%, recall 85% on text PDF |
| Obligations with deadlines | Medium | Low | Precision 85%; recall reported, no target |
| Quality requirements | Medium | Low | Precision 90% on stated values; zero values with no quote |
| Milestones | High in Excel, medium in PDF | Low | Rows 95% |
| Title block | High on one drafting style, varies across offices | Medium | Number and revision 90% on text PDF |
| Corrigendum | High for a revised file; medium for prose | Low | Revised file 100%; prose precision 90% |
| Quantities or objects from drawings | **Not reliable** | **Not reliable** | Out of scope |

## 6. Design

### 6.1 One module

One module, id `document-ingestion`, package `modules/documentingestion`, feature key
`MODULE_DOCUMENT_INGESTION`, table prefix `doc_ingest_`, version `0.1.0`.

All eight stubs share upload, parsing, classification, candidates, review, apply and provenance. They
differ in one extractor class, one payload shape, and one applier. Eight modules would give eight
switches for one pipeline and invent dependencies among them. An operator can still turn off an
extractor by key: `echno.modules.document-ingestion.extractors.<key>.enabled`, default true once the
module is on.

Manifest `dependsOn` stays empty. Project, WBS, risk and sub-contract are core. An applier that needs
`work-progress` or `inspections` asks the registry and refuses with a clear message when that module is
off for the organization, so a tenant with no billing module can still read a contract.

### 6.2 Flow

```
browser --presigned PUT--> object store (doc-ingest/<orgId>/<packageId>/...)
   |
   | register: document row + parse job
   v
parse job row <--claim, heartbeat, close-- echno-ingest-worker
   |                                        reads source, writes pages.jsonl, tables.jsonl,
   | DONE                                   page images. No model key. No other table.
   v
backend poller reads outputs --> classify --> PERSON confirms classes and page splits
   |
   v
extract job row (backend replicas claim, compliance pattern)
   |   deterministic extractors: no LLM
   |   LLM extractors: 8-page chunks through common/llm, needs org consent
   v
candidates + source references --> PERSON accepts, edits or rejects --> apply --> live tables + provenance
```

The worker holds no LLM key for three reasons: one place for the secret and for egress settings, one
cost ledger, and tenant context handled only by code already under the tenant rules. Excel and
title-block paths work with the LLM off.

### 6.3 Data model

All tables: UUID key, `organization_id` foreign key, `TenantScopedEntity` behind `orgFilter`, audit
columns, changesets in `db/changelog/modules/document-ingestion/`. Timestamps in UTC.

**Pipeline**

| Table | Holds |
|---|---|
| `doc_ingest_org_settings` | LLM consent with who and when, monthly budget. A missing row means off |
| `doc_ingest_package` | Name, `project_id` null, `sub_contract_id` null, stage (`TENDER`, `AWARDED`), status, spend counters, created by |
| `doc_ingest_document` | Package, parent ZIP document, `attachment_id`, `sha256`, file name, type, size, class and who set it, revision label, issue date, `supersedes_document_id`, page count, pages OCRed, failed pages |
| `doc_ingest_segment` | Document, class, page range or sheet name, confirmed by. One document can hold several |
| `doc_ingest_parse_job` | Worker queue, per section 6.4 |
| `doc_ingest_extract_job` | Backend queue: segment, extractor key and version, status, attempt, lease, chunks done and total, failed chunks |
| `doc_ingest_llm_call` | Ledger: job, chunk key (unique together), model, prompt version, tokens, estimated cost, finish reason, latency, outcome. Holds no document text |

**Review and provenance**

| Table | Holds |
|---|---|
| `doc_ingest_candidate` | Package, segment, kind (`BOQ_ITEM`, `PROJECT_FIELD`, `MILESTONE`, `CONTRACT_TERM`, `OBLIGATION`, `QUALITY_REQUIREMENT`, `DRAWING`, `QUALIFICATION_REQUIREMENT`, `CHANGE`), natural key, payload JSONB, flags JSONB, method (`DETERMINISTIC`, `LLM`, `OCR_LLM`), status (`PROPOSED`, `ACCEPTED`, `REJECTED`, `APPLIED`, `APPLY_FAILED`, `SUPERSEDED`), `supersedes_candidate_id`, reviewer and time |
| `doc_ingest_source_ref` | Candidate, payload field, document, document hash, page and box (normalized 0 to 1), or sheet, row and cell, quote, `quote_verified` |
| `doc_ingest_provenance` | Append only. Target type and id, target field, value as extracted, value as accepted, candidate, source reference, accepted by and when, applied when |

The review service refuses `ACCEPTED` on a candidate with no source reference, and a test holds it. A field
a person corrects keeps its original source and records both values. A field the extractor left empty and a
person fills in shows as "entered by hand", which is the one case with no source link.

There is no `confidence` column. Models report confidence badly. Flags come from checks a reader can
understand: arithmetic failed, quote not found, OCR page, date out of order.

A package with applied values cannot be deleted, only archived, or provenance would lose its source.

**Registers with no home in core**

`doc_ingest_tender_profile`, `doc_ingest_contract_term`, `doc_ingest_obligation`,
`doc_ingest_quality_requirement`, `doc_ingest_drawing`, `doc_ingest_qualification_requirement`,
`doc_ingest_company_profile`. Core tables gain no column. The obligation register is called the backbone
of risk in the ticket; if it outgrows the module it moves later by the usual path.

### 6.4 Job and worker shape

**Parse queue, worker side.** Contract file `docs/specs/<date>-ingest-worker-contract.md`, added in step
3, binding on both repos in the manner of the BIM contract. The claim, heartbeat and close statements are
the BIM contract's with the table name changed. Lease 10 minutes, heartbeat every 60 seconds and after each
100 pages. Job types are `UNPACK` and `PARSE`.

```
doc-ingest/<orgId>/<packageId>/<documentId>/source
doc-ingest/<orgId>/<packageId>/<documentId>/output/meta.json
doc-ingest/<orgId>/<packageId>/<documentId>/output/pages.jsonl
doc-ingest/<orgId>/<packageId>/<documentId>/output/tables.jsonl
doc-ingest/<orgId>/<packageId>/<documentId>/output/page-images/<n>.webp
doc-ingest/<orgId>/<packageId>/members/<sha256>                 UNPACK output
doc-ingest/<orgId>/<packageId>/<zipDocumentId>/output/manifest.json
```

- `pages.jsonl`: page number, method (`TEXT`, `OCR`, `NONE`), size, lines with box and OCR confidence,
  and a status with reason for a failed page.
- `tables.jsonl`: sheet or page, and rows with cell reference, value, displayed text, formula flag and
  merge origin.
- Page images are capped at 2000 pixels on the long edge. They exist so the review screen can draw a
  source box on any page, scanned or not, without a PDF library in the web app.

The worker writes only its job table and its prefix, runs one job at a time in a work directory wiped
per job, and runs parsing in a child process with memory and time limits. Python 3.12, `uv`, GHCR image by
digest, all configuration from the environment, as `echno-bim-worker`. `jobs.py`, `storage.py` and
`health.py` are copied in as a starting point; a shared library is not worth it for two workers.

**Extract queue, backend side.** Compliance pattern: every replica polls, one conditional update claims,
the lease moves with each chunk, a replica that dies is recovered by the next poll. Runs under
`TenantScopedJobRunner`. `max-in-flight` 2 per replica and four calls at once per job, as compliance.

**Why two queues.** Parsing is CPU-heavy, handles hostile files, and belongs outside the API process.
Extraction is mostly waiting on HTTP and needs the tenant and the secret.

### 6.5 Review, apply and provenance

The review API is generic over kinds: `GET` candidates by package, kind and flag; `POST` decisions with
accept, reject or edit, one row or many. Bulk accept skips flagged rows unless each flag is acknowledged.

Apply runs in the signed-in person's request. For each kind an `IngestionApplier` in the module:

1. Checks the same guard the target's own endpoint carries, because in this repo guards sit on
   controllers and calling a service alone would skip them. **The workflow is traced end to end** so a
   reviewer can also read every list the screen needs, such as sub-contracts and trades.
2. Calls the target's service, or a module's `api` port.
3. Writes provenance in the same transaction.

Batches are 200 rows per transaction through `TransactionRetryTemplate`, and each row turns `APPLIED` as
it lands, so a failed apply resumes where it stopped. A 2,000-row BOQ reaches its end by resuming and never
needs one long transaction, which CockroachDB would be likely to abort.

**The guarantee, enforced in code.** An ArchUnit rule: no class in the module's extraction, parsing or job
packages may depend on a core service, a repository outside the module, or a `workprogress.api` port, apart
from a short named list of read-only lookups such as trades. Only the `apply` package may write, and every
public method there requires an authenticated user. An integration test
runs a whole package and asserts row counts in `project`, `wbs_element`, `contract_boq_item`,
`contract_milestone` and `project_risk` are unchanged until the apply call.

Provenance is read with `GET /provenance?targetType=&targetId=`, and the web app shows a source link on
imported values. Core pages learn of it through the module's nav and API only.

### 6.6 Permissions

Vocabulary `<module>:<action>`, as other modules:

| Permission | Roles | Allows |
|---|---|---|
| `document-ingestion:read` | any member of the tenant | See packages, candidates, provenance |
| `document-ingestion:upload` | `system-admin`, `project-manager`, `qa-engineer`, `site-engineer` | Create packages, confirm classes |
| `document-ingestion:review` | `system-admin`, `project-manager`; plus `qa-engineer` for quality kinds, `site-engineer` for drawings | Accept, reject, edit |
| `document-ingestion:apply` | `system-admin`, `project-manager`, plus the target's own guard | Write to live tables |
| `document-ingestion:admin` | `system-admin` | Give LLM consent, set the budget, edit the company profile |

Guard strings live as one constant each on the manifest, using `@orgSecurity`. When the access matrix on
`auth-workos` (#948) reaches `development` they move to `@access` with the rest of the codebase.

A person may review what they uploaded. Teams this size have no second reviewer, and each decision is
recorded by name.

### 6.7 Kill switch: the property

In this repo a feature key does not keep a module off. `ModuleRegistry.isEntitled` asks
`BillingModuleEntitlementResolver`, which asks `EntitlementPolicy.permits`, which **allows the call in
`advisory` mode and only logs**. `advisory` is the default in `application.yml`. `enabledByDefault = false`
is read only for modules with no feature key. `isSwitchedOn` treats a missing property as on, the scaffold's
condition has `matchIfMissing = true`, and `SubscriptionAspect` never reads the kill switch, so on existing
modules the switch stops jobs and nav and leaves endpoints answering.

**The property this module must hold:**

> With no configuration set, in `advisory` or `enforce` mode, for every organization on every plan: all
> module endpoints refuse any signed-in caller with 403 `MODULE_DISABLED`, the module is absent from
> `/api/v1/modules/web/enabled`, no poller or listener bean exists, no row is written, no job is queued, and
> no LLM call is made.

It takes all of these, in this order:

1. **Operator switch, default off.** `application.yml` carries
   `echno.modules.document-ingestion.enabled: ${ECHNO_MODULE_DOCUMENT_INGESTION_ENABLED:false}`. The
   `DocumentIngestionModuleEnabled` condition has `havingValue = "true"` and **no** `matchIfMissing`.
2. **Organization allow-list, default empty.** `echno.modules.document-ingestion.allowed-org-ids`. Needed
   because with step 1 on, `advisory` mode would otherwise open the module and its spend to every tenant.
3. **Feature key**, seeded on the internal plan only, as `MODULE_VISION`. It counts only in `enforce` mode.
4. **LLM consent per organization**, default off, on `doc_ingest_org_settings`, set by `system-admin` with
   name and time recorded. Without it, deterministic paths still run and LLM extraction stays waiting.
5. **Budget** per package and per month (section 7.3). Hitting it pauses a job.

**Endpoint gate.** Until the platform gate in `2026-10-31-module-tiers-and-independence.md` (#927) lands, a
class-level `@DocumentIngestionGate` aspect reads the switch with a default of `false` in its own code, so
losing the YAML line fails closed. It also checks the allow-list and `isEnabledForOrg`. The code
`MODULE_DISABLED` matches that note, so clients do not change when the platform gate replaces it. When
`organization_module` lands it can replace item 2, since it adds a chosen-by-organization state. Item 1 stays.

**Worker.** Deployed with replicas 0. It holds no way to spend: a stray running worker can only parse rows
already queued, and with the switch off no new rows appear.

**Test** (`DocumentIngestionOffByDefaultIT`, step 2). Boots the full context with no module property, in
`advisory` mode, with an organization that holds no subscription, and asserts each clause of the property. A
second context with the switch on and an empty allow-list asserts the same for that organization. Liquibase
still creates the tables; they stay empty.

## 7. LLM calls

### 7.1 Configuration

Step 10 extracts `common/llm/ChatCompletionClient` and `LlmEndpointProperties`, bound to the existing
`compliance.ai` prefix: `base-url`, `api-key`, `model`, `model-version`, proxy, timeouts. There is no new
secret and no new endpoint setting. `compliance.ai.enabled = false` also stops ingestion's LLM calls, which is
what an operator pressing that switch expects. Compliance keeps its behaviour and its tests. The envelope and
truncation checks in `ComplianceResponseReader` move with it, and the client adds what compliance does not
keep: prompt and completion tokens per call. `2026-09-20-assistant-module.md` plans its own
`AssistantLlmClient`, unwritten as of #892, and could use this one.

Ingestion passes its own `max_tokens` of 1,500. `application.yml` records a measured rate of about 33
completion tokens per second (57 tokens and 1.7 s per rule) against a 60 s read timeout, so a full answer at the
configured `max-tokens: 4096` could outlast the timeout. 1,500 tokens is about 45 s.

Before step 12, check that the configured model slug still resolves. `llama3.3-70b-instruct`, the default in
`application.yml`, is absent from DigitalOcean's public model and price pages as read on 8 Nov 2026. We could
not confirm whether it has been withdrawn.

Prompts are versioned constants; the version is on the ledger. JSON output is parsed and validated against a
schema per kind. `response_format` is used if step 12 shows the endpoint honours it, and the parser does not
rely on it. No images go to the model in this version, so it needs no vision capability.

### 7.2 Cost per document, with sources

Prices: Llama 4 Maverick on DigitalOcean serverless inference, $0.25 input and $0.87 output per million tokens
([pricing page](https://docs.digitalocean.com/products/gradient-ai-platform/details/pricing/), "last verified 5 Nov
2026"). Context window 128,000 tokens
([model page](https://docs.digitalocean.com/products/inference/details/models/)).

Assumptions, all estimates until step 12 records real `usage`: 700 tokens per text page, 8 pages per chunk,
1,500 tokens of prompt per chunk.

| Document | Calls | Input tokens | Output tokens | Cost |
|---|---|---|---|---|
| BOQ or milestones in Excel | 0 | 0 | 0 | $0.00 |
| NIT and ITB, 10 pages | 2 | 10,000 | 1,000 | $0.003 |
| GCC, SCC and contract data, 200 pages | 25 | 177,500 | 20,000 | $0.06 |
| Technical specification, 400 pages | 50 | 355,000 | 50,000 | $0.13 |
| Classifying 50 documents | 50 | 125,000 | 5,000 | $0.04 |
| 200 drawings, every title block falling through to the LLM (the worst case) | 200 | 380,000 | 20,000 | $0.11 |
| **One large package** | | **1,047,500** | **96,000** | **$0.35** |

With every chunk retried once that is about $0.70. A model priced at $3 and $15 per million would take the same
package to about $4.60, so the model slug is the setting that moves this. Default budgets: $5 per package, $50 per
organization per month. Time: a 400-page spec is 50 calls of about 30 s, four at once, so about 6 minutes.

LLM spend is the small cost here. The real cost is reviewer time and worker CPU for OCR, which step 11 measures.

### 7.3 Budget

Before each call the job compares the ledger with the budget. Over budget, the job goes to `PAUSED_BUDGET` with
what is done kept, and `document-ingestion:admin` can raise the limit. Cost uses rates in configuration, as the
assistant note does; rates at 0 mean tokens are recorded with no cost figure, and the token budget still applies.

### 7.4 Tenant isolation and data handling

- **Rows.** `orgFilter` on every entity. Native SQL carries `organization_id` in the statement, since the Hibernate
  filter skips it. A two-tenant integration test covers each table and endpoint: another tenant's package, candidate,
  provenance or page image is not found.
- **Files.** Object keys begin with the organization id. Keys are never accepted from a client: presign composes them,
  and register checks the key against the package. Presigned reads are short-lived and issued only after a scoped row
  lookup.
- **Worker.** A database user with `SELECT` and `UPDATE` on `doc_ingest_parse_job` only, and an object-store policy on
  `doc-ingest/` only, as `bim-worker-access.yml` does for BIM. The organization id reaches it in the row, for log lines.
- **LLM calls.** One call holds text from one document of one organization. System prompts are static. Examples in
  prompts are synthetic. Nothing from one tenant may enter a shared prompt prefix.
- **No shared caches.** The `sha256` dedupe that skips re-parsing is scoped to one organization. A file hash shared
  across tenants would reveal that another tenant holds the same tender.
- **Outside the company.** Tender and contract text leaves Echno for the configured endpoint. DigitalOcean states that
  for its hosted models it does not store inputs or outputs and does not train on them
  ([data privacy](https://docs.digitalocean.com/products/inference/details/data-privacy/), "last verified 12 Oct 2026").
  The same page treats third-party models differently, so the model slug matters here too. This is why consent is per
  organization (Q2).
- **Prompt injection.** Document text is untrusted. The model has no tools. Its output goes only to candidates, passes the
  quote check, and reaches live data only through a person. The web app renders it as text.

## 8. Parsing and model choices

### 8.1 What ships

| Job | Choice | Licence (checked 8 Nov 2026) |
|---|---|---|
| Open, render, read text from PDF | `pypdfium2` (PDFium) | Apache-2.0 / BSD-3-Clause |
| Words with boxes, tables from text PDF | `pdfplumber`, with `pdfminer.six` | MIT, MIT |
| OCR | Tesseract 5, `tessdata_fast`, via `pytesseract` | Apache-2.0 on all three |
| Second OCR engine for the bake-off in step 11 | RapidOCR on ONNX Runtime | Apache-2.0 on the repo; its weights derive from PaddleOCR (Apache-2.0). Confirm the licence of the exact model files before any adoption |
| XLSX | `openpyxl` with `defusedxml` | MIT, PSF |
| XLS | `xlrd` 2.x | BSD |
| DOCX | `python-docx` | MIT |
| DXF | `ezdxf` | MIT |
| File type by content | `filetype` | MIT |
| Plumbing as in the BIM worker | `boto3`, `psycopg`, `pydantic` | Apache-2.0, LGPL-3.0-only, MIT |

The backend gains no new parser and `build.gradle` no new dependency. Apache POI (Apache-2.0) would be
fine on licence; Excel goes through the worker anyway, so that one malformed file cannot take memory from the API
process and so there is one pipeline.

OCR runs only on pages with no usable text layer, decided per page. Tender PDFs often mix both. Tesseract is the default
for its small image and CPU speed. PaddleOCR-family engines read better in published comparisons, though those are on
other document types, so step 11 measures both on our corpus and records the result.

### 8.2 Rejected, and why

| Candidate | Licence | Decision |
|---|---|---|
| PyMuPDF | AGPL-3.0 or paid | **Rejected.** Best-known PDF library, and exactly what the AGPL rule excludes |
| Ghostscript | AGPL per its repository licence, or paid | **Rejected** |
| OCRmyPDF | MPL-2.0, but its README requires Ghostscript | **Rejected** for that dependency |
| Ultralytics, DocLayout-YOLO | AGPL-3.0 | **Rejected** for layout detection; same reasoning as `2026-10-26-cv-phase4-design.md` section 3 |
| Marker, Surya | Code Apache-2.0; weights a modified OpenRAIL-M, free only under $5M funding or revenue | **Rejected.** A limit we would pass as a product grows |
| LibreDWG | GPL-3.0 | **Rejected** for shipping |
| ODA File Converter | Terms are not on its public page | **Not used** until someone reads the licence that comes with it. Commercial DWG libraries likewise need a purchase decision (section 10) |
| Docling, Table Transformer | MIT (code; Docling sends you to each model's own licence) | **Held back.** Heavy PyTorch images. Revisit if the PDF table gate fails |
| LibreOffice for `.doc` | Large image | **Not in this version.** `.doc` is refused with "save as DOCX or PDF" |

**AGPL position.** No AGPL component ships, and CI enforces it: `pip-licenses` fails the worker build on any AGPL or GPL
dependency, with LGPL allowed by name (`psycopg`), and an image test fails if `gs` or `mutool` is on the path, since
`pip-licenses` cannot see system packages. Echno's own repositories have an AGPL plan and `echno-bim-worker` declares
AGPL-3.0-only for its own code. That is our choice over our own code and is a separate matter. This rule keeps out third-party
copyleft we would owe obligations to and could never relicense, which is the concern `2026-10-26-cv-phase4-design.md` set
out for model weights.

### 8.3 The quote check

This is the main defence against a model inventing values. For every LLM value:

1. The model must return the page number and a verbatim quote. The page must be inside the chunk it was sent.
2. The backend normalizes whitespace and looks for the quote on that page. On OCR pages it allows a small edit distance.
3. A number or date in the value must appear in the quote.
4. Failing 1 or 2 sets `quote_verified = false`, and the row cannot be bulk accepted. Failing 3 drops the value and logs it.

The box comes from the text position of the quote on that page. Values on a checked page therefore cite a real place,
which a reviewer can see in one glance.

## 9. Large and malformed files, and retries

| Situation | Default limit | What happens |
|---|---|---|
| Single file | 200 MB | Refused at presign. The worker checks the real size again, as BIM does |
| ZIP | 1 GiB compressed, 4 GiB expanded, 500 members, ratio 100:1, one nesting level | Expansion is counted while reading; over limit fails the job with the sizes |
| ZIP paths | | Traversal and absolute paths refused; links ignored; hidden system folders skipped |
| RAR, 7z, `.doc` | | Refused by content type with a message saying what to upload |
| PDF pages | 2,000 | `FAILED` with the count, before any OCR |
| Scanned page | Rendered up to 50 megapixels; 120 s per page | A page over either fails alone and is listed |
| A0 drawing scan | | Title-block crop only |
| Encrypted PDF | | `FAILED`, "remove the password". Passwords are not requested |
| Corrupt PDF | | Pages that open are kept. Bad pages are listed. The review screen says which pages were not read |
| XLSX | 50 sheets, 200,000 rows, 1 GiB expanded | Bomb check before parsing. `defusedxml` refuses entity attacks. Macros are ignored and never run |
| Excel formula with no cached value | | Cell flagged. Formulas are not calculated |
| Malware | | No scanner in this version. Files are parsed with limits, never executed, and downloaded by presigned link as attachments |
| Same file again | | Same `sha256` in one organization: linked and not parsed or paid for twice |

**Retries.**

| Failure | Handling |
|---|---|
| Deterministic: bad format, too big, encrypted | No retry. `FAILED` with one plain sentence, on the same rule as compliance |
| Worker killed, node lost | Lease expires, backend requeues, 3 attempts. On start-up a worker fails its own rows left `RUNNING`, as the BIM worker does |
| OOM on a job | Child process killed, job `FAILED` with the size, and the worker continues |
| LLM 429, 5xx, timeout | Per call: backoff 2, 8, 30 s. Then the chunk is marked failed and the job continues with others |
| `finish_reason = length` | Chunk split in half and sent again, down to one page. Never read as complete |
| Invalid JSON | One retry, then chunk failed |
| LLM not configured or consent off | Job stays `WAITING_LLM` and resumes when it clears. Never reported as "nothing found" |
| Job retried | Chunks with a ledger row of outcome `OK` are skipped; candidates are upserted on job, chunk and natural key |
| Module switched off with work queued | Rows wait. When the switch returns, jobs resume. An organization removed from the allow-list has its queued rows closed `FAILED` |

**Partial results.** Compliance discards a partial run because a user would read it as complete. Here a person reads every
row, and a 400-page spec should not be thrown away for one bad chunk. So the segment becomes `PARTIAL`, the review screen
shows a banner naming the unread pages, and "retry failed parts" reruns only those.

## 10. Out of scope for this version

**OUT OF SCOPE. Each is a later decision, with no commitment here.**

| Item | Ticket says | Why not now |
|---|---|---|
| Drawing understanding: rooms, members, reinforcement, MEP, quantities | Drawing stub | Section 4.3. Needs labelled data we lack |
| Drawing against BOQ validation | Drawing stub | Depends on the above. Comparing IFC quantities with the BOQ is the likely first step |
| Reading DWG | Main ticket | No acceptable library. Needs a licence purchase or a client PDF |
| BOQ to materials, resources, QA/QC, inspections, risk by inference | BOQ stub | No source page. A later "suggestions" feature, clearly labelled as such |
| Generating activities or a critical path from a tender | Main ticket section 3C | Absent from the documents |
| Procurement, approved makes to vendors, HSE plans, resources | Main ticket | Parsed as `OTHER` for now |
| Cross-document consistency engine, conflicts, missing information, risk scoring | Main ticket sections 2, 4 | Needs reliable extraction first. This version holds only arithmetic checks, SCC over GCC, and corrigenda |
| Bid or no-bid advice, estimation | Main ticket section 4 | A person decides from the matrix |
| The other 37 document classes | Main ticket section 1 | Stored and registered |
| Soil, survey, environmental report values | Main ticket | Stored and registered |
| Site photos and video | Main ticket row 50 | The CV track covers site images |
| Hindi or regional-language extraction | Unstated | See Q4 |
| Vector search or questions over documents | Assistant note | The assistant's concern. See section 12 |
| Automatic processing of existing project attachments | First comment | Not every attachment should go to a model; also capped at 10 MB. Later, a "read this file" action |
| Learning from reviewer corrections, saved column mappings per org | | After real use |
| Main-contract billing, receivables | | See Q1 |

## 11. Build plan

Each step is one PR with its tests. **S** about a day, **M** a few days, **L** a week or more. Repos: **B** `echno-backend`,
**C** `echno-core`, **W** `echno-web`, **K** `echno-ingest-worker` (new, private), **D** `echno-deployment`, **X**
`echno-datasets`.

| # | Step | Repo | Size | After | Tests in the PR |
|---|---|---|---|---|---|
| 1 | Evaluation corpus and scorer: public tender packages with hand-labelled answers, stored privately | X | M | | Scorer against hand-computed cases. No tender file enters a public repo |
| 2 | Module skeleton, off by default, gate, feature seed | B | S | | `DocumentIngestionOffByDefaultIT` (section 6.7), manifest and vocabulary, ArchUnit stays green |
| 3 | Packages, documents, upload with limits, parse queue, poller, worker contract file | B | M | 2 | Two-tenant IT, presign limits, forged key refused, lease requeue, attempts exhausted, OpenAPI snapshot |
| 4 | Candidates, source refs, provenance, review API, apply framework, revision chain | B | M | 3 | No-write-before-apply IT, refusal with no source reference, ArchUnit package rule, resume after failed batch, guard parity |
| 5 | Worker: claim, heartbeat, limits, Excel parsing, licence gate | K | M | contract from 3 | Claim race on CockroachDB, golden XLSX and XLS, XML and ZIP bomb fixtures, formula with no cache, `pip-licenses` |
| 6 | Worker deployment: image, replicas 0, database grant, object-store policy | D | S | 5 | Template render checks; grant covers one table only |
| 7 | BOQ extractor, `workprogress/api` port, contract BOQ applier, optional WBS | B | M | 4 | Ticket sample, 2,000 rows, three sheets, every flag, retried apply, target limits |
| 8 | Core client for packages and review | C | S | 7 | Zod parse tests, write-call check against OpenAPI |
| 9 | **Web: BOQ upload, column mapping, review grid with source cell, apply. FIRST USEFUL THING TO SHOW** | W | M | 8 | Component tests; browser run with the switch on for a test org |
| 10 | Shared LLM client from compliance, keeping token counts | B | S | 2 | All compliance tests unchanged; usage and `finish_reason` parsing |
| 11 | Worker: PDF text and tables, OCR per page, page images, ZIP, OCR bake-off recorded | K | L | 5 | Golden PDFs, mixed PDF, encrypted, corrupt, 2,000 pages, traversal, ratio limit, licence gate |
| 12 | Extract queue, ledger, quote check, budget, consent, classification and page splits | B | L | 4, 10, 11 | Chunk retry and split on truncation, partial state, budget pause, consent off, invented quote refused, class gate on corpus |
| 13 | Project setup and milestone extractors, tender profile, appliers | B | M | 12 | Done-when lines of 4.1 and 4.6, corpus gates |
| 14 | Corrigendum: change candidates, revised-file diff, prose matching, safe apply | B | M | 12, 7 | Done-when lines of 4.8, three-value conflict |
| 15 | Core client: package, classes, change sets, new kinds | C | S | 13, 14 | As step 8 |
| 16 | Web: package screen, class confirmation, page viewer with source box, project, milestones, corrigendum review | W | L | 15 | Component tests; browser run on a fixture package |
| 17 | Contract terms, obligation register, risk action | B | L | 12 | Done-when lines of 4.5, corpus gates |
| 18 | Worker: title-block crop, DXF attributes | K | S | 11 | Golden DXF, cropped scan |
| 19 | Drawing extractor and register, revision linking | B | M | 12, 18 | Done-when lines of 4.3, corpus gate |
| 20 | Quality requirements, per-project ITP, copy to template | B | M | 12 | Done-when lines of 4.4; no value with no quote; template untouched until the second action |
| 21 | Company profile, qualification requirements, matrix | B | M | 12 | Done-when lines of 4.7; `UNKNOWN` against `GAP` |
| 22 | Core client: contract, drawings, quality, qualification | C | S | 17, 19, 20, 21 | As step 8 |
| 23 | Web: contract and obligations, drawing register, ITP, compliance matrix | W | L | 22 | Component tests; browser run |
| 24 | Staging run on the corpus, gate results recorded, operator runbook | D | S | 23 | Gate table filled in; off-by-default confirmed on a real deployment before and after |
| 25 | User guide page | `echno-docs` | S | 23 | Docs build; screenshots from the staging run |

**In parallel.**

- After step 2, three tracks move together: **B** 3, 4, 7; **K** 5, 6, 11, 18; **X** 1 and **B** 10.
- After step 12, steps 13, 14, 17, 19, 20 and 21 touch separate extractor packages and can run at once.
- Web steps wait on their backend through the OpenAPI snapshot. Steps 16 and 23 can start on fixtures.
- Steps 2 to 9 are all that is needed to show the BOQ flow. Each extractor after that can be cut with no harm to
  the others.

**Coordination.** The module-tier work (#927) and the access matrix (#948) are moving through `auth-workos`. If they land
first, step 2 uses the platform gate and `@access` and drops its own aspect. The property in section 6.7 and its test
stay as they are, including the switch that defaults off.

## 12. Later seam for the assistant

When parsing is done the module publishes `DocumentTextReady` in its `api` package, with document id, organization, project,
and output prefix. Nothing listens in this version. The assistant's stage 3 could read `pages.jsonl` and so gain OCR,
which avoids a second PDF parser with a weaker result. Only the event record is added, in step 12.

## 13. Questions for the product owner

Only these need Anand. Each has a default the build proceeds on.

**Q1. When Echno reads a tender's BOQ, whose contract is it?** Today a BOQ belongs to a sub-contract, and approving a bill
there creates money we owe. A tender BOQ is the opposite: what the client pays us.
*Default:* the person picks a sub-contract if the BOQ is for one. Otherwise the BOQ stays as the project's tender BOQ with
source links, for viewing and for spotting corrigendum changes. Billing the client through Echno is not built here.

**Q2. May tender and contract text be sent to the outside AI service?** Each file's text would go to that service to be read.
The service says it does not keep the text or train on it.
*Default:* off for each company until its admin turns it on, and the choice is recorded. Excel BOQs, milestones in Excel, and
the document list work with it off.

**Q3. Can you give us 3 to 5 real tender packages, including a private project's?** We have none. Results on real files are the
only honest test, and private tenders may look different.
*Default:* we build the test set from public government tender portals, and accept weaker results on private formats until
samples arrive.

**Q4. Do we need Hindi or other languages in this version?** Many government tenders have Hindi sections.
*Default:* English only. Other-language pages are stored and shown as "not read".

**Q5. Is the bid or no-bid matrix needed for this version?** It helps only before a project is won, and it needs a company
profile typed in by hand.
*Default:* built last (step 21), and the first to drop if time runs short.

## 14. Defaults taken without asking

| Topic | Default |
|---|---|
| Entry point | A new "Import from documents" action, and a Documents tab on the project. The project form's attachment box is unchanged |
| Class confirmation | Required before extraction, with one "confirm all" button |
| Plans | Feature key on the internal plan only until priced |
| Retention | Source files and outputs live as long as the package. Page images can be regenerated |
| Mobile | Web only in this version |
| Worker size | 2 GiB memory, one job at a time, 2 CPUs. Step 11 confirms it |
| Sample data in tests | Synthetic fixtures in public repos. Real files only in the private corpus |

## Sources

- DigitalOcean: [pricing](https://docs.digitalocean.com/products/gradient-ai-platform/details/pricing/),
  [models](https://docs.digitalocean.com/products/inference/details/models/),
  [data privacy](https://docs.digitalocean.com/products/inference/details/data-privacy/). Read 8 Nov 2026.
- Licences read 8 Nov 2026 from each project's GitHub licence record or PyPI entry; Marker and Surya weights from their READMEs;
  OCRmyPDF's Ghostscript requirement from its README; Ghostscript from the licence file in `ArtifexSoftware/ghostpdl`; ODA from
  [its download page](https://www.opendesign.com/guestfiles/oda_file_converter), which states no terms.
- OCR comparisons are second-hand and on receipts, so the note relies on none of them; step 11 measures on our files.
