# QA/QC extension, note 1 of 4: spatial hierarchy

**Date:** 2026-09-12
**Status:** Design for review. No code lands with this note. ClickUp `14zdkkvrf2b`; roadmap
sections 3.2, 20 and 29.
**Scope:** First-class `Building > Floor > Zone > ConstructionElement` entities under `Project`,
with stable ids that inspections, BIM (`14zdkkvrf2c`) and the robot/drone data layer
(`14zdkkvrf2e`) all reference. Replaces the free-text location fields on `Inspection` and
`InspectionDefect` with a nullable spatial reference while keeping the free text as a fallback.

Companion notes: `2026-09-12-qaqc-observation.md` (depends on this one),
`2026-09-12-qaqc-reinspection-audit-log.md`, `2026-09-12-qaqc-ontology-as-data.md`.

---

## 1. Motivation

Location on an inspection today is three free-text columns on `Inspection` (`location`,
`area_inspected`, `drawing_reference`) and one on `InspectionDefect` (`location`).
`InspectionCheckItem` carries a bare `bim_element_guid` string. Nothing joins these to anything.
Two inspections of the same slab cannot be found together, a defect cannot be rolled up to its
floor, and there is no id for a BIM element or a drone capture to attach to.

Roadmap section 3.2 lists Building, Floor, Zone, Construction Element and Spatial Location as
required entities. Sections 20 and 29 hang the unified data layer and the digital-twin model off
`Project > Building > Floor > Zone > Element`. The gap analysis (memory
`echno-roadmap-qaqc-gap-analysis.md`) rates this the load-bearing gap: every later workstream
needs these ids to exist first.

## 2. Design

### 2.1 Where it lives: core, under `project`

Decision: the hierarchy is a core concern in package `org.tornotron.echno_backend.project.spatial`.
It is not part of `modules/inspections` and is not gated by `MODULE_INSPECTIONS`.

Reasons:

1. Three consumers, each a module of its own: inspections (live), BIM (`14zdkkvrf2c`) and the
   robot/drone data layer (`14zdkkvrf2e`). `ModuleBoundaryTest` lets a module reach another only
   through its `api` subpackage. If the hierarchy sat inside inspections, BIM and robotics would
   import `modules.inspections.api`, and a tenant licensed for BIM without inspections would still
   carry the inspection module's tables and code paths for its site structure.
2. The project owns its buildings. `ProjectService` is core, and core reaches a module only
   through `api` (it already does so for `IndianStateResolver`). Routing project structure CRUD
   through a module's `api` inverts the dependency for no gain.
3. Core is reachable from every module without restriction, so the inspection module references
   spatial ids as plain columns, the same way it already holds `project_id` as a `Long` column
   with no JPA relation across the boundary.

Cost accepted: the feature ships to every tenant. A project tree with no licensed consumer is
inert metadata behind one settings tab.

### 2.2 Shape: one table, four levels

Decision: one entity `SpatialNode`, table `project_spatial_node`, with a `level` discriminator
(`BUILDING`, `FLOOR`, `ZONE`, `ELEMENT`), a `parent_id`, and a materialised `path`.

The alternative, four tables with typed foreign keys, reads well on paper and fails at the first
consumer: an inspection can cover a whole floor, a defect sits on one element, a drone mission
covers a zone. A nullable reference that may point at any level needs either four nullable
foreign keys or a polymorphic pair. One table with a level column gives every consumer one
`spatial_node_id` column and one join.

Per-level rules, enforced in `SpatialNodeService`:

| Level | Parent must be | Extra fields |
|---|---|---|
| `BUILDING` | none | |
| `FLOOR` | `BUILDING` | `level_index` (integer; negative for basements, 0 ground) |
| `ZONE` | `FLOOR` | |
| `ELEMENT` | `ZONE` | `element_type` (slug), `bim_element_guid`, `external_ref` |

The chain is strict. A floor with no zoning gets one zone named after the floor, created by the
service helper `ensureDefaultZone(floorId)` when the client adds elements directly to a floor.
This keeps every element path the same depth, which is what BIM storey/space mapping and
path-prefix queries want.

`path` is the node ids joined by `/` from the building down, maintained by the service on create
and move; `depth` is derived. CockroachDB has no `ltree`, and a text prefix match on an indexed
column is fine at the expected size (hundreds to a few thousand nodes per project). Subtree
queries use `path LIKE :prefix || '%'`. Moving a node rewrites the paths of its descendants in
the same transaction and is allowed only within the same project and only to a parent of the
correct level.

### 2.3 Stable ids

Ids are UUIDs generated at creation and never reused. Nodes are archived, never deleted, once
created: `archived_at` is set, the node disappears from pickers, and every existing reference
stays valid. Archiving a node archives its subtree. The foreign keys from inspection tables use
`ON DELETE RESTRICT` as a second guard.

`bim_element_guid` (the IFC GlobalId, 22 characters, stored as `varchar(100)` to match the
existing check-item column) is unique per project when set. How it gets populated (IFC import,
manual entry) is the BIM note's decision; this note only reserves the column and the constraint.
`InspectionCheckItem.bim_element_guid` stays as is for now; the BIM note decides whether it
becomes derived from the element node.

No geometry lives here. Bounding boxes, local coordinate frames and registration transforms are
the BIM and robotics notes' tables, keyed by `spatial_node_id`. This table is the id anchor only.

### 2.4 Spatial reference on inspection entities

`Inspection`, `InspectionDefect` and `InspectionCheckItem` each gain a nullable
`spatial_node_id`. The free-text columns stay:

- `Inspection.location`, `area_inspected`, `drawing_reference`: kept, unchanged names, now
  documented as the free-text fallback and as a "location note" when a node is also set.
- `InspectionDefect.location`: kept, same treatment.

Validation on write: the node must belong to the inspection's project and organisation, and must
not be archived (an existing reference to a since-archived node is fine on read). An inspection
may reference a `BUILDING` or `FLOOR`; a defect or check item should reference a `ZONE` or
`ELEMENT`, enforced as a warning in the DTO validation, not a hard rule, so early adopters with
coarse trees are not blocked.

Read DTOs gain a derived `spatialPath`: the ordered list of `{id, level, code, name}` from the
building down to the referenced node, so the web app renders a breadcrumb with no second call.

### 2.5 How `WbsElement` relates

`WbsElement` is a schedule and cost node: dates, budget, progress, weight, tasks, purchase
orders, indents. It answers "what work, when, at what cost". A spatial node answers "where".
"Slab casting, level 3" is a WBS activity that happens at floor L03. Roadmap section 29 places
"Construction Activity" between the spatial chain and Inspection, which matches this split.

Decision: no foreign key between the two now. When progress-by-location is needed, a link table
`wbs_spatial_scope (wbs_element_id, spatial_node_id)` records which places an activity covers.
That is a later note. Merging the two trees is rejected: their lifecycles differ (a WBS is
re-baselined; a building is not) and their consumers differ.

## 3. Data model

```
project_spatial_node
  id               uuid pk
  organization_id  bigint not null  (fk organization; orgFilter applies)
  project_id       bigint not null  (fk "Project"; denormalised on every node)
  parent_id        uuid null        (fk project_spatial_node)
  level            varchar(20) not null   BUILDING | FLOOR | ZONE | ELEMENT
  code             varchar(50) not null   short label, unique among siblings
  name             varchar(200) not null
  sort_order       int not null default 0
  path             text not null    "/<building-id>/<floor-id>/..."
  depth            int not null     0..3
  level_index      int null         floors only
  element_type     varchar(50) null elements only; free slug until note 4 lands its catalogue
  bim_element_guid varchar(100) null
  external_ref     varchar(200) null  drawing / grid reference
  archived_at      timestamp null
  created_at, updated_at, created_by, updated_by   (Spring Data auditing, as finance BaseEntity)

unique (project_id, parent_id, code)      sibling codes; parent_id null for buildings
unique (project_id, bim_element_guid)     partial, where bim_element_guid is not null
index  (project_id, level)
index  (project_id, path)
```

Changes to inspection tables (all nullable, all `fk project_spatial_node on delete restrict`):

```
inspections.spatial_node_id             + index
inspection_defects.spatial_node_id      + index
inspection_check_items.spatial_node_id  + index
```

`SpatialNode implements TenantScopedEntity` with the `orgFilter` Hibernate filter, as every
other tenant-owned entity.

## 4. API

Project-scoped, no `/web` twin (the controller-twin collapse is in progress; new controllers
follow the collapsed shape). Reads require project membership; writes require the same authority
as `ProjectService`'s patch path (`@orgSecurity` plus project role).

```
GET    /api/v1/projects/{projectId}/spatial                 whole tree, nested, archived excluded
GET    /api/v1/projects/{projectId}/spatial?includeArchived=true
GET    /api/v1/projects/{projectId}/spatial/nodes/{nodeId}  node with its path
POST   /api/v1/projects/{projectId}/spatial/nodes
         { parentId | null, level, code, name, sortOrder?, levelIndex?,
           elementType?, bimElementGuid?, externalRef? }
PATCH  /api/v1/projects/{projectId}/spatial/nodes/{nodeId}
         { code?, name?, sortOrder?, levelIndex?, elementType?, bimElementGuid?, externalRef? }
POST   /api/v1/projects/{projectId}/spatial/nodes/{nodeId}/move      { parentId }
POST   /api/v1/projects/{projectId}/spatial/nodes/{nodeId}/archive
POST   /api/v1/projects/{projectId}/spatial/nodes/{nodeId}/restore
POST   /api/v1/projects/{projectId}/spatial/import
         rows: [{ building, floor, levelIndex?, zone?, element?, elementType? }]
         idempotent on the code path; returns { created, skipped }
```

Errors: 409 on sibling code clash or wrong parent level, 404 on cross-project node, 422 on
archived target. The import endpoint exists so a site team can stand up a tree from a
spreadsheet in one call; the web app converts CSV to these rows client-side.

Inspection endpoints change only by the new optional fields: `spatialNodeId` on
`CreateInspectionRequest`, `UpdateInspectionRequest`, `InspectionDefectRequest`,
`InspectionCheckItemRequest`; `spatialNodeId` and `spatialPath` on the matching DTOs. List
endpoints gain a `spatialNodeId` filter that matches the node and its subtree (path prefix).

`docs/openapi.json` is committed and CI-verified; every controller change regenerates it with
`-PupdateOpenApiSnapshot`.

## 5. Migration

Liquibase, two changesets in two places because the table is core and the columns are module:

1. `db/changelog/v4.0/0xx-project-spatial-node.xml`: create table, constraints, indexes.
2. `db/changelog/modules/inspections/0xx-spatial-node-refs.xml`: add the three nullable
   columns, foreign keys and indexes.

Existing rows need nothing. Every current inspection, defect and check item has
`spatial_node_id = null` and its free text intact, which is exactly the fallback state. No
automated parse of the old `location` strings into nodes (defaulted below). Rollback is
drop-column then drop-table.

`ddl-auto: validate` means the entities and the changesets must agree at boot; the Testcontainers
suite catches a mismatch.

## 6. Core and web impact

`echno-core` (additive, no break: parsers are non-strict):

- `types/project/spatial.ts`: `SpatialLevel`, `SpatialNode`, `SpatialPathSegment`,
  `SpatialImportRow`, request types.
- `services/spatial-service.ts`: the eight calls above.
- `types/inspection/*`: optional `spatialNodeId` and `spatialPath` on inspection, defect and
  check-item responses and requests.

`echno-web`:

- Project settings gains a "Site structure" tab: a tree editor (add building / floor / zone /
  element, rename, reorder, archive) and a CSV import that posts `import` rows.
- A `SpatialLocationPicker` (cascading selects: building, floor, zone, element, each optional
  below the level chosen) on the inspection form, the defect row and the check-item row. The
  existing free-text field stays under it, labelled "Location note".
- Inspection and NCR lists show the breadcrumb from `spatialPath` and accept the subtree filter.

Neither BIM nor robotics work is in this note. They take `spatial_node_id` as given.

## 7. Definition of done

- A project admin can build a `Building > Floor > Zone > Element` tree in the UI or by import,
  archive and restore nodes, and move a node between parents of the correct level.
- An inspection, a defect and a check item can each reference a node; the reference survives
  archiving; a node of the wrong project is rejected.
- Existing inspections with only free-text location continue to display and edit unchanged.
- List filters by node return the subtree.
- Liquibase changesets apply on a fresh database and on a database holding the QA seed;
  `ddl-auto: validate` passes; `ModuleBoundaryTest` passes with the spatial package in core.
- `echno-core` types published; web picker and tree editor merged; OpenAPI snapshot updated.

## 8. Open questions, defaulted

| Question | Default taken |
|---|---|
| Elements directly under a floor? | No. Strict chain; `ensureDefaultZone` covers unzoned floors. |
| Delete or archive? | Archive only. Ids must stay valid for BIM and captures. |
| Backfill nodes from old free-text `location`? | No. Strings are inconsistent; the fallback keeps them readable. |
| Element type vocabulary | Free slug now; note 4's catalogue validates it later, no FK yet. |
| Gate behind a module feature key? | No. Core feature, every tenant. |
| Which levels an inspection may point at | Any. Defects and check items get a soft warning below ZONE. |
| Import format | JSON rows on the API; CSV conversion in the web app. |
| Coordinates or geometry on nodes | None here. BIM and robotics notes own geometry. |
| `WbsElement` link | None now; `wbs_spatial_scope` link table later. |

## 9. Sequencing and work packages

This note is independent of notes 3 and 4 and can be built in parallel with them. Note 2
(Observation) depends on it and starts once package A below is merged. Each package is sized for
one agent or one developer and is filed as a GitHub issue in the repo named.

- **A. echno-backend: "Spatial hierarchy: `SpatialNode` entity, service, repository and Liquibase changeset"**
  Core package, level rules, path maintenance, archive/restore, unit and Testcontainers tests.
- **B. echno-backend: "Spatial hierarchy: project spatial tree endpoints and bulk import"**
  Controller, DTOs, import endpoint, OpenAPI snapshot. Depends on A.
- **C. echno-backend: "Inspection, defect and check item take a spatial node reference with free-text fallback"**
  Module changeset, request/DTO fields, `spatialPath` derivation, subtree filter. Depends on A.
- **D. echno-core: "Spatial node types, service and inspection DTO fields"**
  Additive types and client; publish. Depends on B and C for the contract.
- **E. echno-web: "Site structure tab and spatial location picker"**
  Tree editor, CSV import, picker on inspection/defect/check-item forms, breadcrumb. Depends on D.
