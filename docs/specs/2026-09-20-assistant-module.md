# Assistant module: natural-language questions over Echno

**Date:** 2026-09-20
**Status:** Design note and execution plan. Replaces the earlier separate-service design, which
was not accepted on 10 September. The build is staged for a single new developer with a review
gate at the end of every stage.
**Requirement source:** the EchnoAI business requirements (v1.2). Requirement ids (`BR-nn`,
`FR-nn`, `NFR-nn`) below refer to that document. It is confidential; this note carries none of
its commercial content.
**Architecture:** `docs/specs/2026-08-26-modular-plugin-architecture.md`, sections 3 to 9 and 14.
The Toolbox Talks module is the reference implementation to copy.

## 1. Summary

A user types a question. The backend works out what is being asked and for which project and
period, picks the providers that can answer it from what each provider declares about itself,
runs those providers in parallel under the caller's own tenant context, and has a language model
compose an answer from the evidence they returned, with a citation on every factual sentence.
When the evidence is thin the answer is a stated decline that names what was looked for.
Every step is written to an audit row.

The whole thing is one module, `modules/assistant`, built on the modular skeleton like
`inspections`, `bim` and `toolbox-talks`. It adds two tables, one of which holds document
embeddings in a CockroachDB `VECTOR` column. It adds no service, no second database and no new
authorisation model. Providers are read-only in this release; the write side of the provider
contract is designed as a later interface and not built.

## 2. Why the earlier design does not fit Echno

The rejected design put the assistant in a separate Spring AI microservice with its own
PostgreSQL and pgvector, calling `echno-backend` through a service-account credential, with
Redis holding chat memory. Three things rule it out here.

**Entitlement cannot cross a service-account hop.** FR-28 to FR-30 require entitlement to be
resolved before retrieval and applied inside each provider query, under the asking user's own
scope. Echno enforces that with `orgFilter`, a Hibernate filter enabled per transaction from
`TenantContext`, and with `@PreAuthorize` guards that read the caller's authorities. A separate
service calling the API as a service account has neither the user's authorities nor a tenant
context; it would have to re-implement entitlement as a post-filter, which is the mistake the
requirements name explicitly. Inside the monolith the provider runs as the user, and the
existing machinery does the filtering by construction.

**A second database contradicts BR-17 and the modular-monolith decision.** BR-17 requires the
system to operate on the existing data model. The architecture spec (section 4) settled on one
deployable, one `EntityManagerFactory` and one Liquibase run precisely so that every entity
inherits the tenant filter. A pgvector sidecar would sit outside all three. CockroachDB has had a
`VECTOR` type with distance operators and vector indexes since v25.2; staging runs v26.2.4 and
`'[1,2,3]'::VECTOR <-> '[1,2,4]'::VECTOR` evaluates there today. A second store adds nothing.

**The in-process pattern already exists.** The compliance generator in `modules/inspections`
calls an OpenAI-compatible endpoint through a small `RestClient` wrapper
(`OpenAiCompatibleComplianceService`), takes its key from configuration, and runs its
background work through `TenantScopedJobRunner`. The assistant does the same with the same
client shape. Spring AI, Redis memory and the extra repository go with it.

## 3. Goals and non-goals

Goals for the first release:

- Answer free-text questions about attendance, tasks, materials and uploaded documents for the
  caller's organisation and entitled projects (BR-01 to BR-07).
- Every factual sentence cites evidence the user can open; numbers are reproduced as retrieved,
  never recomputed by the model (FR-14, FR-17, FR-24, FR-26).
- Decline, and say why, when evidence is missing, the question is ambiguous, or a provider or
  the model is unavailable (FR-05, FR-18, FR-23, BR-18).
- A uniform provider contract so a fifth provider is a new class and nothing else (BR-09).
- An audit row per query with providers, evidence ids, answer, cost and model version, readable
  only by the asker and system admins (BR-15, FR-21, FR-34).
- Tenant isolation proven by a test that is a release gate (NFR-03, RISK-1).

Non-goals for the first release:

- Conversation memory beyond one follow-up window (section 7).
- Any write action, workflow trigger or notification (BR-11, BR-12, BR-20 are design
  constraints only; section 5 leaves the seam).
- OCR, drawings, CAD geometry, forecasting, cost estimation, real-time site data.
- Text-to-SQL. The model never sees table names and never writes queries.
- A mobile surface. The `/api/v1/assistant` twin exists for parity; the first client is the web
  panel, built as a later package.

## 4. Architecture

```
POST /api/v1/assistant/web/ask
      |
      v
AssistantService.ask(question, previousQueryId)          @Transactional, orgFilter on
      |
      +-- 1 understand   QuestionInterpreter   (LLM, structured JSON; or clarify)
      +-- 2 plan         ProviderPlanner       (matches intent to describe() metadata)
      +-- 3 retrieve     ProviderRegistry      (parallel; each call under TenantContext)
      |       attendance  task  material  document(VECTOR search)
      +-- 4 assemble     EvidenceAssembler     (dedupe, cap, order, drop personal data
      |                                         the question did not ask for)
      +-- 5 generate     GroundedAnswerer      (LLM; statements with evidence ids;
      |                                         validator drops uncited facts)
      +-- 6 audit        QueryLogWriter        (assistant_query_log)
      v
AskResponse { outcome, statements[], evidence[], clarification?, queryId, cost }
```

Package layout, generated by `./gradlew scaffoldModule -Pid=assistant -Pname="Assistant"` and
then extended:

```
modules/assistant/
  AssistantModule.java              manifest: id "assistant", MODULE_ASSISTANT, permissions
  AssistantModuleEnabled.java       @ConditionalOnProperty echno.modules.assistant.enabled
  api/                              the provider contract (section 5) and the events it publishes
  provider/                         AttendanceProvider, TaskProvider, MaterialProvider,
                                    DocumentProvider, ProviderRegistry
  pipeline/                         QuestionInterpreter, ProviderPlanner, EvidenceAssembler,
                                    GroundedAnswerer, AnswerValidator
  llm/                              AssistantLlmClient (chat + embeddings), AssistantLlmProperties,
                                    FakeLlmClient (test profile)
  ingest/                           DocumentExtractor, Chunker, DocumentIngestionService,
                                    DocumentIngestionJob
  domain/                           AssistantDocumentUnit, AssistantQueryLog
  repository/                       AssistantDocumentUnitRepository, AssistantQueryLogRepository
  dto/                              AskRequest, AskResponse, QueryLogDto, IngestStatusDto
  web/                              AssistantController, AssistantControllerWeb
db/changelog/modules/assistant/     001-assistant-tables.xml, 002-seed-module-assistant-feature.xml
```

The module reads core data only through core services (`AttendanceService`, `TaskService`,
`ProjectService`, `MaterialService`, `InventoryService`, `AttachmentService`) and platform
services in `common/` (`TenantContext`, `TenantScopedJobRunner`, `FileStorageService`, the
module SPI). It touches no core repository and no other module's internals;
`ModuleBoundaryRuleTest` enforces that.

Alternatives considered and rejected:

| Alternative | Why not |
|---|---|
| Separate service with its own store | Section 2. |
| Spring AI as a framework | Its `PgVectorStore` issues pgvector DDL CockroachDB rejects; its abstractions add a large dependency for two HTTP calls we already make. Revisit when a provider needs tool-calling. |
| Redis chat memory | No conversation model in this release; the one follow-up window reads the previous audit row. |
| Text-to-SQL provider | Breaks FR-26 (the model would compute) and FR-28 (raw SQL bypasses guards). |
| Hard-coded intent-to-provider map | BR-09 and the Phase 2 gate require the planner to select from provider metadata. |
| Post-filtered vector search | Reads other tenants' rows and leaks counts; FR-29 forbids it. |

## 5. Provider contract

The contract lives in `modules/assistant/api` so a later module can contribute a provider
without touching the assistant's internals.

```java
public interface AssistantProvider {
    ProviderDescriptor describe();
    ProviderResult retrieve(Question question, Scope scope);
}

public record ProviderDescriptor(
        String id,                       // "attendance"
        String answers,                  // one paragraph: what it can answer, for the planner
        List<FieldSpec> fields,          // name, unit, meaning, as the source system names them
        Set<Subject> subjects,           // ATTENDANCE, TASK, MATERIAL, DOCUMENT, PROJECT
        boolean needsPeriod,
        boolean carriesPersonalData,     // FR-31: attendance is true
        CostProfile cost) {}             // typical latency and token cost, for the planner and NFR-08

public record Scope(Long projectId, LocalDate from, LocalDate to, String subjectHint) {}

public sealed interface ProviderResult {
    record Evidence(List<EvidenceUnit> units) implements ProviderResult {}
    record Empty(String lookedFor) implements ProviderResult {}          // "no rows", not an error
    record Unavailable(String reason) implements ProviderResult {}       // BR-18
}

public record EvidenceUnit(
        String id,                       // "attendance:2026-08-14:project-42"
        String providerId,
        String kind,                     // "attendance-day-summary", "task", "stock-level", "document-chunk"
        Map<String, Object> values,      // field name -> value, units per FieldSpec
        SourceRef source,                // entity type + id, or attachmentId + page + offsets
        double relevance) {}
```

Rules that make entitlement hold by construction:

- `retrieve` runs inside the caller's request thread, so `TenantContext` is already set and the
  services it calls are `@Transactional`, which turns `orgFilter` on. A provider never opens its
  own transaction with a different tenant and never uses `@WithoutTenant`.
- `@PreAuthorize` in Echno sits on controllers, not services. A provider is a Spring bean called
  from the pipeline, so its `retrieve` method carries the same guard expression as the controller
  endpoint it mirrors (for attendance, the project-attendance read; for tasks, the project task
  list; for materials, the stock read). The module's `ProviderGuardParityTest` reads both
  annotations and fails when they differ, so a guard tightened on a controller cannot be left
  loose on the provider.
- Providers call services, never repositories, and never native SQL against core tables. The
  document provider's similarity query is the one native query in the module and it carries
  `organization_id = :orgId` and `project_id IN (:projectIds)` inside the statement (section 6).
- A provider returns `Empty` and `Unavailable` as distinct values. The assembler and the decline
  path depend on telling "no attendance recorded" from "attendance could not be read".
- Values keep the source system's field names and units (`FieldSpec`); derived numbers such as a
  productivity rate are computed in the provider, in Java, and passed as values (FR-26).

Registration is by component scan: `ProviderRegistry` takes `List<AssistantProvider>`, checks
descriptor ids are unique, and exposes `all()` and `byId()`. It mirrors `ModuleRegistry`.

The write seam, designed and not built: a separate `AssistantAction` interface (`describe()`,
`propose(Intent)` returning a typed proposal, `execute(Proposal, Authorisation)` requiring a
named authoriser). It lives beside `AssistantProvider` as an empty interface with a comment, so
the Phase 3 gate in the requirements (separable read and write, BR-20) is already true of the
code shape. No implementation and no endpoint in this release.

First providers and the services they call:

| Provider | Calls | Evidence kinds |
|---|---|---|
| attendance | `AttendanceService.getAttendanceByProject(projectId, date, status)`, once per day in the period | per-day headcount, per-trade counts; named rows only when the question names a person and the caller holds the attendance read guard |
| task | `TaskService.getTasksByProjectId`, `ProjectService` progress totals | task with status, progress, due date, overdue flag |
| material | `MaterialService`, `InventoryService.getCurrentStock(materialId, projectId)` | material with current stock, unit, last GRN date |
| document | `AssistantDocumentUnitRepository.nearest(...)` | chunk text, attachment id, page, offsets, distance |

## 6. Data model and migrations

Two tables, both `TenantScopedEntity` with `@Filter(name = "orgFilter", ...)` and an
`organization_id` column, in `db/changelog/modules/assistant/`.

**`assistant_document_unit`** (one row per chunk):

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `organization_id` | BIGINT NOT NULL | FK organization |
| `project_id` | BIGINT NULL | FK project; null for organisation-wide documents |
| `attachment_id` | BIGINT NOT NULL | FK attachment, `ON DELETE CASCADE` (FR-12, FR-32) |
| `chunk_index` | INT | order within the document |
| `page_no`, `char_start`, `char_end` | INT | for citation and opening at the right place (FR-11) |
| `content` | STRING | the chunk text |
| `content_hash` | STRING | skip re-embedding unchanged chunks on re-ingest |
| `embedding` | `VECTOR(<dim>)` | dimension fixed by `echno.assistant.embeddings.dimension` |
| `embedding_model` | STRING | model name and version that produced the row |
| `created_at` | TIMESTAMPTZ | |

Index: `CREATE VECTOR INDEX assistant_document_unit_embedding_idx ON assistant_document_unit
(organization_id, project_id, embedding)`. CockroachDB vector indexes take prefix columns, and a
search that filters on those columns with equality uses the index partition for that tenant and
project. That is FR-29 in the physical layout: the similarity search cannot touch another
organisation's vectors, and the count it returns is the count within the tenant. The spike
(stage 1) confirms the index syntax on v26.2.4 and whether the cluster setting for vector indexes
needs enabling on staging.

The similarity query is native and parameterised:

```sql
SELECT id, attachment_id, page_no, char_start, char_end, content,
       embedding <-> CAST(:q AS VECTOR) AS distance
FROM assistant_document_unit
WHERE organization_id = :orgId AND project_id IN (:projectIds)
ORDER BY embedding <-> CAST(:q AS VECTOR)
LIMIT :k
```

`:orgId` comes from `TenantContext.getCurrentOrgId()` inside the provider; `:projectIds` is the
resolved scope, which is already the set of projects the caller may read. Both are in the
statement, never applied afterwards.

**JPA mapping of `VECTOR`.** Hibernate 6.6 (Boot 3.4) ships `hibernate-vector`, which maps a
`float[]` with `@JdbcTypeCode(SqlTypes.VECTOR)` and `@Array(length = dim)` on the PostgreSQL
dialect. Whether `CockroachDialect` registers the type is what the spike proves. Two paths, in
order of preference:

1. `hibernate-vector` with `@JdbcTypeCode(SqlTypes.VECTOR)`; entity save inserts the vector and
   the native query above reads it. Cleanest if the dialect cooperates.
2. Otherwise the entity maps `embedding` as `insertable = false, updatable = false`, and a
   `@Modifying` native `UPDATE ... SET embedding = CAST(:v AS VECTOR)` writes it after insert,
   with the vector serialised as `[0.1,0.2,...]` text by a small `VectorText` helper. The read
   side is the native query either way.

The spike PR is one entity, one migration, one integration test on the Testcontainers
CockroachDB harness: insert three rows in two organisations, create the index, run the nearest
query under `orgFilter` for one organisation and assert only its rows come back and in distance
order. Liquibase writes the column and index with `<sql>` because its typed `createTable` has no
`VECTOR`.

**`assistant_query_log`** (one row per `ask`):

`id`, `organization_id`, `project_id`, `user_id`, `question`, `resolved_scope` (JSONB),
`providers_selected` (JSONB list of ids), `evidence_ids` (JSONB), `outcome` (enum), `answer`
(JSONB statements), `llm_model`, `llm_model_version`, `prompt_tokens`, `completion_tokens`,
`embedding_tokens`, `estimated_cost_micros`, `latency_ms`, `created_at`. Retention is a policy
setting, `echno.assistant.audit.retention-days`, with a daily job that deletes older rows per
organisation through `TenantScopedJobRunner` (FR-34, open item Q-4 in the requirements).

The `Feature` seed row `MODULE_ASSISTANT` follows the toolbox-talks `002-seed` changeset, but
granted on no plan by default: the module is premium and dark until billing adds it.

## 7. Query pipeline

**Understand.** One model call with a fixed JSON schema returns `intent`, `subjects`,
`projectRef` (name or id as typed), `period` (from, to, or null), `personRef`, `multiStep`
(boolean) and `ambiguous` (list of what is unclear). Scope resolution is Java: `projectRef` is
matched against the projects the caller can list through `ProjectService` (orgFilter and the
project read guard apply). No match, or more than one, is a clarification (FR-05). A missing
period defaults to the current week for attendance and to "now" for tasks and stock; the default
is stated in the answer. `multiStep = true` declines in this release (FR-06). The one follow-up
window: the request may carry `previousQueryId`; if it names a row in `assistant_query_log`
owned by the same user and younger than 15 minutes, its `resolved_scope` is offered to the
interpreter as the default scope. Nothing else is remembered.

**Plan.** The planner asks the registry for descriptors and selects the providers whose
`subjects` intersect the interpreted subjects and whose `needsPeriod` is satisfiable. It is a
metadata match; adding a provider changes nothing in the planner. A provider flagged `carriesPersonalData` is selected only when the interpreted
intent needs it (FR-31).

**Retrieve.** Selected providers run in parallel on a bounded executor. Each task captures the
caller's `SecurityContext` and the tenant id and re-establishes both on the worker thread through
`TenantScopedJobRunner.callForTenant`, so `orgFilter` and `@PreAuthorize` see the same user on
every thread. A provider that throws becomes `Unavailable`; a timeout does the same.

**Assemble.** Evidence units are de-duplicated by id, capped per provider (default 20) and
overall by a token budget, and ordered by relevance. Providers that returned `Empty` or
`Unavailable` are recorded with their reasons; that record is what the decline text is built
from. If every provider was empty or unavailable the pipeline skips generation and declines.

**Generate.** One model call. The prompt holds the question, the resolved scope, the evidence
as a numbered list with field names and units, and the instruction to return JSON:
`statements[] { text, kind: fact | inference | recommendation, evidenceIds[] }` and
`insufficient: boolean`. The `AnswerValidator` then enforces grounding in Java (FR-22 to FR-26): a `fact` with no `evidenceIds`, or with an id not in the assembled
set, is dropped; a `fact` whose numeric tokens do not appear in the cited units' values is
dropped and counted; `inference` and `recommendation` are kept but shown apart from facts
(FR-19, FR-25). If no fact survives, the outcome is a decline. Provider disagreement is reported
as two cited facts, not resolved (FR-27).

**Audit.** The log row is written in the same transaction as the response, including declines
and failures. `GET /assistant/web/queries` lists the caller's own rows; `GET /assistant/web/audit`
lists every row in the organisation and is guarded on `assistant:admin`.

Outcomes the client sees: `ANSWERED`, `CLARIFY` (with the questions to ask), `DECLINED` (with
`lookedFor`: provider, scope, what was searched), `DEGRADED` (answered with some providers
unavailable, named), `UNAVAILABLE` (the model itself was unreachable; BR-18).

## 8. Security and tenancy

- Every controller method carries `@PreAuthorize`; the class carries
  `@RequireSubscription(feature = AssistantModule.FEATURE_KEY)`. `EndpointAuthorizationTest`
  and the entitlement test from the review checklist cover both.
- Permissions: `assistant:ask` (any member, so the guard is
  `@orgSecurity.isMemberOfCurrentTenant()`), `assistant:ingest` (project manager, system admin),
  `assistant:admin` (system admin). What a member can see through `ask` is still whatever the
  mirrored guards on each provider allow, so the permission adds nothing above the existing
  model; it only names the surface.
- The document provider's ingest endpoint takes an `attachmentId` and loads it through
  `AttachmentService`, which is tenant-scoped, so a foreign id is a 404 before any file is read.
- No secret is read by the module. `AssistantLlmProperties` binds `echno.assistant.llm.api-key`
  and `echno.assistant.embeddings.api-key` from the environment the same way
  `compliance.ai.api-key` does. The data-handling terms of the endpoint in use are recorded in
  the deployment runbook (FR-33).
- The model never receives table names, SQL, user ids or other tenants' data; only the
  assembled evidence, which was fetched under the caller's scope.
- The ingestion job runs per organisation through `TenantScopedJobRunner` and refuses a null
  tenant, the same as the compliance job.

## 9. Configuration

```yaml
echno:
  modules:
    assistant:
      enabled: ${ECHNO_MODULE_ASSISTANT_ENABLED:true}      # operator kill switch
  assistant:
    llm:                                                   # same shape as compliance.ai
      base-url: ${ASSISTANT_LLM_BASE_URL:https://inference.do-ai.run/v1}
      api-key: ${ASSISTANT_LLM_API_KEY:}
      model: ${ASSISTANT_LLM_MODEL:llama3.3-70b-instruct}
      model-version: ""
      max-tokens: 2048
      temperature: 0.1
      connect-timeout-seconds: 10
      read-timeout-seconds: 45
      proxy-host: ""                                       # lab nodes go out through Squid
      proxy-port: 3128
    embeddings:
      base-url: ${ASSISTANT_EMBEDDINGS_BASE_URL:https://inference.do-ai.run/v1}
      api-key: ${ASSISTANT_EMBEDDINGS_API_KEY:}
      model: ${ASSISTANT_EMBEDDINGS_MODEL:}
      dimension: ${ASSISTANT_EMBEDDINGS_DIMENSION:1024}    # fixed per corpus; change = re-index
      batch-size: 32
    retrieval:
      units-per-provider: 20
      document-k: 8
      document-max-distance: 0.35
      provider-timeout-seconds: 8
    followup-window-minutes: 15
    audit:
      retention-days: 365
    cost:
      prompt-token-micros: 0        # set from the provider's price list; 0 = record tokens only
      completion-token-micros: 0
      embedding-token-micros: 0
```

With an empty API key the module boots, `describe()` works, and `ask` returns `UNAVAILABLE`
with a clear reason. Cost per query is computed from token counts and the configured rates and
written to the audit row; per-organisation totals are a query over the log, exposed on the audit
endpoint (BR-19, NFR-09). If billing wants a quota, `ask` takes
`@RequireSubscription(recordUsage = true)` on a quota-typed feature, which the aspect already
supports.

## 10. Testing and the isolation gate

Every stage lands with tests that fail without the code, in the module's own test package:

- `AssistantModuleTest`: manifest, permissions, kill switch, registry sees the module.
- `AssistantDocumentUnitIT` (stage 1 spike): insert, index, nearest under `orgFilter`.
- `ProviderRegistryTest`, `ProviderGuardParityTest`, one `XxxProviderTest` per provider with the
  underlying service mocked, asserting field names and units and the `Empty` versus
  `Unavailable` split.
- `QuestionInterpreterTest` and `ProviderPlannerTest` against `FakeLlmClient`, which returns
  canned JSON keyed on the question, so planning and scope resolution are deterministic.
- `AnswerValidatorTest`: uncited fact dropped, foreign evidence id dropped, number not in
  evidence dropped, inference kept apart, all-dropped becomes decline.
- `AssistantControllerWebAuthzTest`: the standard guard and entitlement matrix.
- `DocumentIngestionServiceIT`: PDF and text extraction, chunk boundaries, re-ingest skips
  unchanged hashes, delete cascades.
- `AssistantIsolationIT`, the release gate. Two organisations, each with a project, attendance
  rows, tasks, stock and one ingested document with overlapping vocabulary. The same question is
  asked as a member of each. Assert: no evidence id, no attachment id and no numeric value from
  the other organisation appears in the response or in the audit row; the document provider's
  result count equals the count within the asking organisation; the query log for one
  organisation is invisible to the other. This test runs on the Testcontainers CockroachDB
  harness with the real migrations and is required green for every merge that touches the
  module.

`ModuleBoundaryRuleTest`, `ModuleBoundaryTest`, `TenantScopedJoinTest` and
`EndpointAuthorizationTest` apply automatically. `docs/openapi.json` is regenerated with
`./gradlew openApiSnapshot -PupdateOpenApiSnapshot` whenever a controller or DTO changes, or
the build fails. Never open that file in an editor; diff it.

## 11. Execution plan by stage

The builder is Ramkumar K R, joining as an intern with Java and Spring but no Echno history.
Heavy builds and integration tests run on lab node `.116` (`lab-node-116-f`), with scripts
named by issue number since the home directory is shared; edits and unit tests run locally. Each stage is
one or two pull requests to `development`, reviewed by Abhijith before the next stage starts.
Across every stage, do not touch: anything under `common/multitenancy`, `common/security` or
the security configuration; any other module's package; core services, repositories or entities;
the master changelog; `EndpointAuthorizationTest` or any ArchUnit rule. If a stage seems to
need one of these, raise it on the ticket and wait.

### Stage 0, week 1: onboarding and the tutorial

- Read, in order: portal getting-started (`local-setup`, `accounts-and-access`,
  `build-machines`), the contributor guide (`module-contract`, `workflow`, `review-checklist`,
  `definition-of-done`), then this note.
- Bring up the local stack with `./gradlew devUp`; run the backend and log in.
- Follow the Toolbox Talks tutorial `00` to `08` end to end (backend only), on a throwaway
  branch `tutorial/ramkumar`, producing a toy module PR that is reviewed and then closed
  unmerged.
- Deliverable: the toy module PR; a docs issue listing what was unclear in the tutorial.
- Gate: Abhijith reviews the PR for the contract basics (tenant-scoped entity, twin guards,
  changelog layout, tests that fail without the code).
- Questions to ask: which lab node to use; how to get a Keycloak test user in each role.

### Stage 1, week 2: the VECTOR spike and the provider contract

- PR 1, the spike: `AssistantDocumentUnit` entity, `001-assistant-tables.xml`, the vector index,
  the nearest-neighbour native query, `AssistantDocumentUnitIT` as described in section 6.
  Report which of the two mapping paths worked and paste the index DDL that ran. Check whether
  the cluster setting for vector indexes is on in the Testcontainers image and on staging.
- PR 2, the contract: `./gradlew scaffoldModule -Pid=assistant`, the manifest with the three
  permissions and `MODULE_ASSISTANT`, the `api` package (section 5), `ProviderRegistry`,
  `AttendanceProvider` with a real `describe()` and a `retrieve` that calls
  `AttendanceService`, `ProviderGuardParityTest`, `AttendanceProviderTest`,
  `AssistantModuleTest`.
- Gate: the IT proves isolation of the vector query; the descriptor carries field names and
  units; the guard parity test exists and passes.
- Questions to ask: which attendance read guard the provider mirrors; whether
  organisation-wide documents (null `project_id`) are wanted in the first release.

### Stage 2, weeks 3 to 4: the ask endpoint with a fake model

- `AssistantLlmClient` interface with `FakeLlmClient` (test profile) and an HTTP implementation
  copied in shape from `OpenAiCompatibleComplianceService`, wired but not exercised.
- `QuestionInterpreter`, scope resolution through `ProjectService`, `ProviderPlanner`, parallel
  retrieval with context propagation, `EvidenceAssembler`, `GroundedAnswerer`,
  `AnswerValidator`, `AssistantQueryLog` with its changeset, `TaskProvider`.
- Controllers: `POST /ask`, `GET /queries` on both twins; `GET /audit` on the web twin.
  OpenAPI snapshot regenerated.
- Tests: all of section 10 except ingestion, including `AssistantIsolationIT` with the document
  provider stubbed to return nothing.
- Gate: a scripted walk on the local stack shows an answer with citations, a clarification, a
  decline naming what was looked for, and a `DEGRADED` answer when a provider is made to throw.
  Abhijith reviews the validator and the isolation test line by line.
- Questions to ask: the default period per subject; how tasks report "progress against
  plan" (which field is the plan); the audit endpoint's page size.

### Stage 3, weeks 5 to 6: documents, materials, the real model, cost

- `DocumentExtractor` (PDF through the PDFBox already on the classpath via openhtmltopdf; plain
  text; anything else rejected with a reason), `Chunker` (by page, then by paragraph, target
  size from config with overlap), `DocumentIngestionService`, the embeddings call,
  `DocumentIngestionJob` per organisation through `TenantScopedJobRunner`, the ingest and status
  endpoints, cascade on attachment delete verified.
- `DocumentProvider` over the stage 1 query with the distance threshold; `MaterialProvider`.
- The HTTP client turned on behind config; the fake remains the test default. Token counts from
  the response written to the log; cost computed from configured rates.
- Tests: `DocumentIngestionServiceIT`, `DocumentProviderTest`, `MaterialProviderTest`, the
  isolation test extended with real ingested documents in both organisations.
- Gate: on `.116` against the DO endpoint with a staging key, ten scripted questions across all
  four providers with their outcomes recorded in the PR; the isolation test green with
  documents.
- Questions to ask: which embedding model and dimension (this is the requirements' open
  item Q-2 and fixes the corpus); the chunk size; whether ingestion is triggered on upload or
  only by the endpoint.

### Stage 4, week 7: hardening, portal page, staging demo

- Timeouts and bounded executors reviewed; the retention job; per-organisation cost query on
  the audit endpoint; error messages read as decline text, never stack traces.
- Portal page under the developer guide: the module's contract, how to add a provider, the
  configuration keys.
- Deploy to the k8s staging through the normal dispatch; demo to the founders on `echno.in` with
  seeded data; record the ten scripted questions and their outcomes as the acceptance baseline
  the requirements ask for (Q-3).
- Gate: Abhijith signs off the module for `development`; the web panel is ticketed as the next
  package.

| Stage | Weeks | Deliverable | Gate |
|---|---|---|---|
| 0 | 1 | Local stack, tutorial toy module PR | Contract basics review |
| 1 | 2 | VECTOR spike IT; provider contract, registry, attendance provider | Vector isolation IT; guard parity |
| 2 | 3 to 4 | `ask` with fake LLM, task provider, grounding validator, audit, controllers | Scripted walk; isolation IT |
| 3 | 5 to 6 | Ingestion, embeddings, document and material providers, real LLM, cost | Ten questions on `.116`; isolation IT with documents |
| 4 | 7 | Hardening, portal page, staging demo | Sign-off |

## 12. Open questions with defaults

| Question | Default until decided | Owner |
|---|---|---|
| LLM endpoint and data-handling terms (Q-1) | The DO inference endpoint the compliance module already uses, same key family, terms recorded in the runbook | Abhijith |
| Embedding model and dimension (Q-2) | The embedding model the same endpoint exposes; dimension 1024; recorded per row so a change is an explicit re-index | Abhijith, stage 3 |
| Acceptance targets (Q-3) | The ten scripted questions from stage 3 become the baseline; numeric targets set after the demo | Product |
| Retention of embeddings and audit rows (Q-4) | Embeddings live with the attachment; audit rows 365 days | Product |
| Project scope for a member (Q-6) | Whatever the existing project read guards allow; no new assignment model | Settled |
| Organisation-wide documents (no project) | Schema supports them; exposed in a later release | Abhijith, stage 1 |
| Ingestion trigger | Explicit endpoint only; automatic on upload deferred | Abhijith, stage 3 |
| Personal data in attendance answers (FR-31) | Aggregates by default; names only when the question names a person and the caller holds the attendance read guard | Settled |
| Follow-up window | One previous query, 15 minutes, scope only | Settled |
| Mobile twin | Same endpoints, no client in this release | Settled |
