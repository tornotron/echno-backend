# Work Breakdown Structure (WBS) Module

This document explains the Work Breakdown Structure module - what it is, how the hierarchy works, how WBS elements integrate with existing entities (tasks, purchase orders, indents, payables, material consumption), and how to call every API endpoint.

---

## Table of Contents

1. [Overview](#overview)
2. [What is a WBS?](#what-is-a-wbs)
3. [Architecture](#architecture)
4. [Data Model](#data-model)
5. [WBS Element Fields](#wbs-element-fields)
6. [Status Lifecycle](#status-lifecycle)
7. [Progress Rollup](#progress-rollup)
8. [Cost Rollup](#cost-rollup)
9. [Integration with Existing Entities](#integration-with-existing-entities)
10. [API Reference](#api-reference)
    - [Create a WBS Element](#create-a-wbs-element)
    - [Bulk Create WBS Elements](#bulk-create-wbs-elements)
    - [Get WBS Tree](#get-wbs-tree)
    - [Get WBS Flat List](#get-wbs-flat-list)
    - [Get Single WBS Element](#get-single-wbs-element)
    - [Update a WBS Element](#update-a-wbs-element)
    - [Delete a WBS Element](#delete-a-wbs-element)
    - [Move a WBS Element](#move-a-wbs-element)
    - [Get Leaf Elements](#get-leaf-elements)
    - [Recalculate Progress and Costs](#recalculate-progress-and-costs)
11. [Complete Workflow](#complete-workflow)
12. [Key Design Decisions](#key-design-decisions)
13. [File Structure](#file-structure)

---

## Overview

The WBS module adds hierarchical project decomposition to the Echno platform. A project can be broken down into phases, deliverables, and work packages organized as a tree. Each node in the tree tracks its own budget, actual cost, progress, and schedule.

Existing entities - **Task**, **PurchaseOrder**, **Intend**, **Payable**, and **MaterialConsumption** - can optionally be linked to a WBS element. This enables cost and progress tracking at every level of the breakdown.

All WBS endpoints are scoped to a specific project and respect multi-tenancy (organization filtering).

**Base URL:** `GET/POST /api/v1/project/{projectId}/wbs`

**Authorization:** All endpoints require the `system-admin` org role.

---

## What is a WBS?

A Work Breakdown Structure is a hierarchical decomposition of a project into manageable sections. In construction, this typically looks like:

```
Project: "Greenfield Tower"
├── 1.0 Foundation
│   ├── 1.1 Excavation
│   │   ├── 1.1.1 Site Clearing
│   │   └── 1.1.2 Trench Digging
│   ├── 1.2 Concrete Work
│   │   ├── 1.2.1 Formwork
│   │   └── 1.2.2 Concrete Pouring
│   └── 1.3 Curing
├── 2.0 Structural Work
│   ├── 2.1 Column & Beam
│   └── 2.2 Slab Casting
├── 3.0 Finishing
│   ├── 3.1 Plastering
│   ├── 3.2 Painting
│   └── 3.3 Flooring
└── 4.0 MEP (Mechanical, Electrical, Plumbing)
    ├── 4.1 Electrical Wiring
    └── 4.2 Plumbing
```

**Key terminology:**

| Term | Meaning | Example |
|---|---|---|
| Phase | Top-level grouping (level 0) | "Foundation", "Structural Work" |
| Deliverable | Mid-level grouping | "Excavation", "Concrete Work" |
| Work Package | Lowest-level item where actual work happens | "Site Clearing", "Formwork" |
| WBS Code | Hierarchical identifier, unique per project | `1.0`, `1.2.1`, `3.3` |
| Leaf Node | A node with no children - tasks and costs attach here | `1.1.1 Site Clearing` |
| Parent Node | A node with children - its progress and cost are aggregated from children | `1.1 Excavation` |

---

## Architecture

```
WBS Tree (per Project)
├── Phase 1.0 (parent, progress = weighted avg of children)
│   ├── Deliverable 1.1 (parent, progress = weighted avg of children)
│   │   ├── Work Package 1.1.1 (leaf)
│   │   │   ├── Task: "Clear vegetation"
│   │   │   ├── Task: "Remove debris"
│   │   │   ├── MaterialConsumption: diesel for excavator
│   │   │   └── Payable: contractor payment for clearing
│   │   └── Work Package 1.1.2 (leaf)
│   │       ├── Intend: requisition for excavation equipment
│   │       ├── PurchaseOrder: hire excavator
│   │       └── Task: "Dig foundation trench"
│   └── Deliverable 1.2 (parent)
│       └── ...
└── Phase 2.0 (parent)
    └── ...
```

Tasks, PurchaseOrders, Indents, Payables, and MaterialConsumptions link to **leaf nodes** (work packages). Parent nodes aggregate progress and costs from their children.

---

## Data Model

### WbsElement Entity

```java
WbsElement {
    id                  // Long, auto-generated
    wbsCode             // "1.0", "1.2.1" - unique per project
    title               // "Excavation"
    description         // optional detailed scope
    level               // 0 = root phase, 1 = deliverable, 2+ = work package
    sortOrder           // ordering among siblings
    status              // NOT_STARTED, IN_PROGRESS, COMPLETED, ON_HOLD, CANCELLED
    startDate           // planned start (LocalDate)
    endDate             // planned end (LocalDate)
    actualStartDate     // when work actually began
    actualEndDate       // when work actually finished
    budgetedCost        // planned cost (BigDecimal 15,2)
    actualCost          // incurred cost (BigDecimal 15,2)
    progress            // 0.0 - 100.0
    weight              // relative weight for progress rollup (default 1.0)
    isLeaf              // true = work package, false = summary node
    project             // required - which project this WBS belongs to
    parent              // nullable - parent WBS element (null = root)
    children            // list of child WBS elements
    organization        // multi-tenancy
    createdBy           // Employee who created this element
    createdAt           // auto-generated
    updatedAt           // auto-generated
}
```

### Relationships to Existing Entities

All of these are **optional** (nullable FK). Existing workflows are unaffected if WBS is not used.

| Entity | New Field | Description |
|---|---|---|
| `Task` | `wbsElement` (ManyToOne) | Which work package this task belongs to |
| `MaterialConsumption` | `wbsElement` (ManyToOne) | Which work package consumed materials |
| `PurchaseOrder` | `wbsElement` (ManyToOne) | Which work package the PO is for |
| `Payable` | `wbsElement` (ManyToOne) | Which work package the payment is for |
| `Intend` | `wbsElement` (ManyToOne) | Which work package raised the requisition |
| `Project` | `wbsElements` (OneToMany) | All WBS elements for the project |

### Database

- Table: `wbs_element`
- Unique constraint: `(project_id, wbs_code)` - no duplicate codes within a project
- Self-referential FK: `parent_id` references `wbs_element(id)` with `ON DELETE CASCADE`
- Indexes: `(project_id, parent_id)`, `(project_id, wbs_code)`, `(organization_id)`
- FK columns `wbs_element_id` added to: `task`, `material_consumption`, `purchase_order`, `payable`, `intend` (all nullable, `ON DELETE SET NULL`)

---

## WBS Element Fields

### Required Fields

| Field | Type | Validation | Example |
|---|---|---|---|
| `wbsCode` | String (max 50) | Not blank, unique per project | `"1.0"`, `"1.2.1"` |
| `title` | String (max 255) | Not blank | `"Excavation"` |

### Optional Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `description` | String | null | Detailed scope description |
| `parentId` | Long | null | ID of parent element. Null = root-level phase |
| `sortOrder` | Integer | 0 | Controls display order among siblings |
| `status` | String | `"NOT_STARTED"` | One of the WbsStatus enum values |
| `startDate` | LocalDate | null | Planned start date |
| `endDate` | LocalDate | null | Planned end date |
| `budgetedCost` | BigDecimal | 0 | Planned budget for this element |
| `weight` | Double | 1.0 | Relative weight for progress calculation |
| `createdBy` | Long | null | Employee ID of creator |

### Auto-Calculated Fields (not set directly via API)

| Field | Description |
|---|---|
| `level` | Auto-calculated: `parent.level + 1`, or `0` if root |
| `isLeaf` | `true` if the element has no children, `false` otherwise. Updated automatically when children are added/removed |
| `actualCost` | Aggregated from children (for parent nodes) or from linked Payables/MaterialConsumptions (for leaf nodes) |
| `progress` | For leaf nodes: set directly. For parent nodes: weighted average of children's progress |

---

## Status Lifecycle

```
NOT_STARTED ──> IN_PROGRESS ──> COMPLETED
                    │
                    ├──> ON_HOLD ──> IN_PROGRESS
                    │
                    └──> CANCELLED
```

| Status | Meaning |
|---|---|
| `NOT_STARTED` | Work has not begun (default) |
| `IN_PROGRESS` | Work is actively being done |
| `COMPLETED` | Work is finished |
| `ON_HOLD` | Work paused temporarily |
| `CANCELLED` | Work will not be done |

Status is set manually via the update endpoint. There is no automatic status transition.

---

## Progress Rollup

Progress flows **upward** from leaves to root. When you update progress on a leaf node, all ancestor nodes are automatically recalculated.

### For Leaf Nodes

Progress is set directly (0.0 to 100.0) via the update endpoint.

### For Parent Nodes

Progress is calculated as a **weighted average** of children:

```
parent.progress = Σ(child.weight × child.progress) / Σ(child.weight)
```

**Example:**

```
1.0 Foundation (weight doesn't matter for root)
├── 1.1 Excavation     (weight: 2.0, progress: 100.0)
├── 1.2 Concrete Work   (weight: 3.0, progress: 40.0)
└── 1.3 Curing          (weight: 1.0, progress: 0.0)

Foundation progress = (2.0 × 100.0 + 3.0 × 40.0 + 1.0 × 0.0) / (2.0 + 3.0 + 1.0)
                    = (200 + 120 + 0) / 6
                    = 53.33
```

The `weight` field controls how much each child influences the parent's progress. A concrete-heavy project might give `weight: 3.0` to "Concrete Work" and `weight: 1.0` to "Curing" because concrete work represents more of the overall effort.

---

## Cost Rollup

### For Leaf Nodes

`actualCost` on a leaf node is set by linking Payables and MaterialConsumptions to the WBS element. Use the `/recalculate` endpoint to aggregate costs from linked entities.

### For Parent Nodes

`actualCost` is the sum of all children's `actualCost`:

```
parent.actualCost = Σ(child.actualCost)
```

This rolls up recursively to the root. Combined with `budgetedCost`, this enables variance analysis:

```
variance = budgetedCost - actualCost
```

Positive variance = under budget. Negative variance = over budget.

---

## Integration with Existing Entities

When creating or updating a Task, PurchaseOrder, Intend, Payable, or MaterialConsumption, you can optionally pass a `wbsElementId` to link it to a WBS work package. This is purely additive - all existing functionality remains unchanged.

### Linking a Task to a WBS Element

When creating a task, include the WBS element ID:

```json
{
  "title": "Clear vegetation from site",
  "projectId": 1,
  "creatorId": 5,
  "wbsElementId": 3,
  ...
}
```

The same pattern applies to PurchaseOrder, Intend, Payable, and MaterialConsumption creation DTOs - add a `wbsElementId` field.

### Querying Linked Entities

Once tasks and costs are linked to WBS elements, the WBS tree provides a structured view of what work and costs belong to each part of the project.

---

## API Reference

All endpoints are under:
```
/api/v1/project/{projectId}/wbs
```

All endpoints require `system-admin` role: `@PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")`.

---

### Create a WBS Element

```
POST /api/v1/project/{projectId}/wbs
```

**Request body:**
```json
{
  "wbsCode": "1.0",
  "title": "Foundation",
  "description": "All foundation-related work including excavation, concrete, and curing",
  "parentId": null,
  "sortOrder": 1,
  "status": "NOT_STARTED",
  "startDate": "2026-04-01",
  "endDate": "2026-06-30",
  "budgetedCost": 500000.00,
  "weight": 1.0,
  "createdBy": 5
}
```

| Field | Required | Description |
|---|---|---|
| `wbsCode` | Yes | Unique code within the project |
| `title` | Yes | Display name |
| `description` | No | Scope description |
| `parentId` | No | ID of parent WBS element. Null = root-level phase |
| `sortOrder` | No | Order among siblings (default: 0) |
| `status` | No | Default: `NOT_STARTED` |
| `startDate` | No | Planned start |
| `endDate` | No | Planned end |
| `budgetedCost` | No | Planned budget (default: 0) |
| `weight` | No | Progress weight (default: 1.0) |
| `createdBy` | No | Employee ID |

**Creating a child element:**
```json
{
  "wbsCode": "1.1",
  "title": "Excavation",
  "parentId": 1,
  "sortOrder": 1,
  "budgetedCost": 200000.00,
  "weight": 2.0,
  "createdBy": 5
}
```

When a child is added to an element, the parent's `isLeaf` is automatically set to `false`.

**Response (201 Created):**
```json
{
  "id": 1,
  "wbsCode": "1.0",
  "title": "Foundation",
  "description": "All foundation-related work including excavation, concrete, and curing",
  "level": 0,
  "sortOrder": 1,
  "status": "NOT_STARTED",
  "startDate": "2026-04-01",
  "endDate": "2026-06-30",
  "actualStartDate": null,
  "actualEndDate": null,
  "budgetedCost": 500000.00,
  "actualCost": 0.00,
  "progress": 0.0,
  "weight": 1.0,
  "isLeaf": true,
  "projectId": 1,
  "projectName": "Greenfield Tower",
  "parentId": null,
  "parentWbsCode": null,
  "createdBy": { "id": 5, "employeeName": "Rahul Kumar", ... },
  "createdAt": "2026-04-01T10:00:00",
  "updatedAt": "2026-04-01T10:00:00",
  "children": null
}
```

**Error cases:**
- `409 Conflict` - WBS code already exists in this project
- `404 Not Found` - Project or parent element not found
- `400 Bad Request` - Parent element belongs to a different project

---

### Bulk Create WBS Elements

Create multiple elements in one request. Elements are created in order, so parents should come before their children.

```
POST /api/v1/project/{projectId}/wbs/bulk
```

**Request body:**
```json
{
  "elements": [
    {
      "wbsCode": "1.0",
      "title": "Foundation",
      "sortOrder": 1,
      "budgetedCost": 500000.00,
      "createdBy": 5
    },
    {
      "wbsCode": "2.0",
      "title": "Structural Work",
      "sortOrder": 2,
      "budgetedCost": 800000.00,
      "createdBy": 5
    },
    {
      "wbsCode": "3.0",
      "title": "Finishing",
      "sortOrder": 3,
      "budgetedCost": 300000.00,
      "createdBy": 5
    }
  ]
}
```

To create a tree in bulk, include `parentId` referencing elements that were created earlier in the same request (use the IDs from the response of the first elements, or create root elements first in a separate call, then children in the bulk call).

**Response (201 Created):** Array of `WbsElementDto`.

---

### Get WBS Tree

Returns the full WBS hierarchy as a nested tree structure. This is the primary endpoint for rendering the WBS in a UI.

```
GET /api/v1/project/{projectId}/wbs/tree
```

**Response (200 OK):**
```json
[
  {
    "id": 1,
    "wbsCode": "1.0",
    "title": "Foundation",
    "level": 0,
    "status": "IN_PROGRESS",
    "budgetedCost": 500000.00,
    "actualCost": 320000.00,
    "progress": 64.0,
    "isLeaf": false,
    "children": [
      {
        "id": 2,
        "wbsCode": "1.1",
        "title": "Excavation",
        "level": 1,
        "status": "COMPLETED",
        "budgetedCost": 200000.00,
        "actualCost": 195000.00,
        "progress": 100.0,
        "isLeaf": true,
        "children": [],
        ...
      },
      {
        "id": 3,
        "wbsCode": "1.2",
        "title": "Concrete Work",
        "level": 1,
        "status": "IN_PROGRESS",
        "budgetedCost": 300000.00,
        "actualCost": 125000.00,
        "progress": 40.0,
        "isLeaf": false,
        "children": [
          {
            "id": 4,
            "wbsCode": "1.2.1",
            "title": "Formwork",
            "level": 2,
            "status": "COMPLETED",
            "budgetedCost": 100000.00,
            "actualCost": 95000.00,
            "progress": 100.0,
            "isLeaf": true,
            "children": [],
            ...
          },
          {
            "id": 5,
            "wbsCode": "1.2.2",
            "title": "Concrete Pouring",
            "level": 2,
            "status": "NOT_STARTED",
            "budgetedCost": 200000.00,
            "actualCost": 30000.00,
            "progress": 0.0,
            "isLeaf": true,
            "children": [],
            ...
          }
        ],
        ...
      }
    ],
    ...
  },
  {
    "id": 6,
    "wbsCode": "2.0",
    "title": "Structural Work",
    "level": 0,
    "children": [ ... ],
    ...
  }
]
```

The response is an array of root-level elements, each containing their `children` recursively.

---

### Get WBS Flat List

Returns all WBS elements as a flat list ordered by WBS code. Useful for table views, exports, or when you need all elements without nesting.

```
GET /api/v1/project/{projectId}/wbs
```

**Response (200 OK):**
```json
[
  { "id": 1, "wbsCode": "1.0", "title": "Foundation", "level": 0, "sortOrder": 1, "status": "IN_PROGRESS", "budgetedCost": 500000.00, "actualCost": 320000.00, "progress": 64.0, "isLeaf": false, "parentId": null },
  { "id": 2, "wbsCode": "1.1", "title": "Excavation", "level": 1, "sortOrder": 1, "status": "COMPLETED", "budgetedCost": 200000.00, "actualCost": 195000.00, "progress": 100.0, "isLeaf": true, "parentId": 1 },
  { "id": 3, "wbsCode": "1.2", "title": "Concrete Work", "level": 1, "sortOrder": 2, "status": "IN_PROGRESS", "budgetedCost": 300000.00, "actualCost": 125000.00, "progress": 40.0, "isLeaf": false, "parentId": 1 },
  { "id": 4, "wbsCode": "1.2.1", "title": "Formwork", "level": 2, "sortOrder": 1, "status": "COMPLETED", "budgetedCost": 100000.00, "actualCost": 95000.00, "progress": 100.0, "isLeaf": true, "parentId": 3 },
  { "id": 5, "wbsCode": "1.2.2", "title": "Concrete Pouring", "level": 2, "sortOrder": 2, "status": "NOT_STARTED", "budgetedCost": 200000.00, "actualCost": 30000.00, "progress": 0.0, "isLeaf": true, "parentId": 3 },
  { "id": 6, "wbsCode": "2.0", "title": "Structural Work", "level": 0, "sortOrder": 2, "status": "NOT_STARTED", "budgetedCost": 800000.00, "actualCost": 0.00, "progress": 0.0, "isLeaf": false, "parentId": null }
]
```

The flat DTO is a lighter response (no nested children, no creator details) suitable for large WBS structures.

---

### Get Single WBS Element

Returns one element with its children tree.

```
GET /api/v1/project/{projectId}/wbs/{elementId}
```

**Response (200 OK):** Same format as tree response, but for a single element. The `children` array is populated recursively.

---

### Update a WBS Element

Update any combination of fields on an existing element.

```
PUT /api/v1/project/{projectId}/wbs/{elementId}
```

**Request body (all fields optional):**
```json
{
  "title": "Foundation & Substructure",
  "description": "Updated scope to include substructure work",
  "status": "IN_PROGRESS",
  "startDate": "2026-04-01",
  "endDate": "2026-07-15",
  "actualStartDate": "2026-04-05",
  "actualEndDate": null,
  "budgetedCost": 550000.00,
  "weight": 1.5,
  "sortOrder": 1,
  "progress": 75.0
}
```

**Progress update rules:**
- `progress` can only be set directly on **leaf** elements (`isLeaf = true`)
- Attempting to set progress on a parent node returns `400 Bad Request`: "Progress can only be set directly on leaf WBS elements"
- When a leaf's progress is updated, all ancestor nodes are automatically recalculated

**Response (200 OK):** Updated `WbsElementDto`.

---

### Delete a WBS Element

Deletes an element and all its descendants (cascade delete).

```
DELETE /api/v1/project/{projectId}/wbs/{elementId}
```

**What happens:**
1. The element and all its children are deleted (database cascade)
2. Linked entities (tasks, POs, etc.) have their `wbs_element_id` set to `null` (database `ON DELETE SET NULL`)
3. If the deleted element was the last child of its parent, the parent's `isLeaf` is set back to `true`
4. Parent progress is recalculated

**Response (200 OK):**
```json
{
  "message": "WBS element with id: 3 deleted"
}
```

---

### Move a WBS Element

Re-parent an element, change its WBS code, or change its sort order. This is how you reorganize the tree structure.

```
POST /api/v1/project/{projectId}/wbs/{elementId}/move
```

**Request body (all fields optional):**
```json
{
  "newParentId": 6,
  "newWbsCode": "2.1",
  "newSortOrder": 1
}
```

| Field | Description |
|---|---|
| `newParentId` | ID of the new parent. Set to `null` to make it a root element |
| `newWbsCode` | New WBS code. Must be unique within the project |
| `newSortOrder` | New position among siblings |

**Safety checks:**
- Cannot move an element under its own descendant (cycle detection)
- Cannot move to a parent in a different project
- New WBS code must be unique

**What happens automatically:**
1. Element is re-parented
2. `level` is recalculated for the element and all its descendants
3. Old parent's `isLeaf` is set to `true` if it has no remaining children
4. New parent's `isLeaf` is set to `false`
5. Progress is recalculated for both old and new parent chains

**Response (200 OK):** Updated `WbsElementDto`.

---

### Get Leaf Elements

Returns only the leaf nodes (work packages) - elements that can have tasks, costs, and materials linked to them. This is designed for dropdown/select menus in the UI when linking tasks or costs to a WBS element.

```
GET /api/v1/project/{projectId}/wbs/leaves
```

**Response (200 OK):**
```json
[
  { "id": 2, "wbsCode": "1.1", "title": "Excavation", "level": 1, "sortOrder": 1, "status": "COMPLETED", "budgetedCost": 200000.00, "actualCost": 195000.00, "progress": 100.0, "isLeaf": true, "parentId": 1 },
  { "id": 4, "wbsCode": "1.2.1", "title": "Formwork", "level": 2, "sortOrder": 1, "status": "COMPLETED", "budgetedCost": 100000.00, "actualCost": 95000.00, "progress": 100.0, "isLeaf": true, "parentId": 3 },
  { "id": 5, "wbsCode": "1.2.2", "title": "Concrete Pouring", "level": 2, "sortOrder": 2, "status": "NOT_STARTED", "budgetedCost": 200000.00, "actualCost": 30000.00, "progress": 0.0, "isLeaf": true, "parentId": 3 }
]
```

---

### Recalculate Progress and Costs

Triggers a full recalculation of `actualCost` and `progress` for an element and its descendants. Use this after linking payables or material consumptions to WBS elements, or if values seem stale.

```
POST /api/v1/project/{projectId}/wbs/{elementId}/recalculate
```

No request body required.

**What happens:**
1. For each leaf node: `actualCost` stays as-is (set from linked entities)
2. For each parent node: `actualCost` = sum of children's `actualCost`
3. For each parent node: `progress` = weighted average of children's progress
4. Rolls up recursively from leaves to the specified element

**Response (200 OK):** Recalculated `WbsElementDto`.

---

## Complete Workflow

### Step 1: Create the WBS structure for a project

Create root-level phases first, then add children.

```
POST /api/v1/project/1/wbs
{ "wbsCode": "1.0", "title": "Foundation", "sortOrder": 1, "budgetedCost": 500000.00, "createdBy": 5 }
→ Returns id: 1

POST /api/v1/project/1/wbs
{ "wbsCode": "2.0", "title": "Structural Work", "sortOrder": 2, "budgetedCost": 800000.00, "createdBy": 5 }
→ Returns id: 6

POST /api/v1/project/1/wbs
{ "wbsCode": "3.0", "title": "Finishing", "sortOrder": 3, "budgetedCost": 300000.00, "createdBy": 5 }
→ Returns id: 10
```

### Step 2: Add deliverables and work packages

```
POST /api/v1/project/1/wbs
{ "wbsCode": "1.1", "title": "Excavation", "parentId": 1, "sortOrder": 1, "budgetedCost": 200000.00, "weight": 2.0, "createdBy": 5 }
→ Returns id: 2. Parent (id:1) isLeaf becomes false.

POST /api/v1/project/1/wbs
{ "wbsCode": "1.2", "title": "Concrete Work", "parentId": 1, "sortOrder": 2, "budgetedCost": 300000.00, "weight": 3.0, "createdBy": 5 }
→ Returns id: 3

POST /api/v1/project/1/wbs
{ "wbsCode": "1.2.1", "title": "Formwork", "parentId": 3, "sortOrder": 1, "budgetedCost": 100000.00, "createdBy": 5 }
→ Returns id: 4

POST /api/v1/project/1/wbs
{ "wbsCode": "1.2.2", "title": "Concrete Pouring", "parentId": 3, "sortOrder": 2, "budgetedCost": 200000.00, "createdBy": 5 }
→ Returns id: 5
```

### Step 3: View the tree

```
GET /api/v1/project/1/wbs/tree
```

Returns the full nested hierarchy.

### Step 4: Link tasks to work packages

When creating tasks, include `wbsElementId`:

```
POST /api/v1/tasks (multipart)
data: { "title": "Clear vegetation", "projectId": 1, "creatorId": 5, "wbsElementId": 2, ... }
```

### Step 5: Link costs to work packages

When creating payables or material consumptions, include `wbsElementId`:

```
POST /api/v1/payables
{ "payableNumber": "PAY-001", "projectId": 1, "wbsElementId": 2, "amountRecorded": 150000.00, ... }

POST /api/v1/material-consumptions
{ "materialId": 1, "projectId": 1, "wbsElementId": 2, "quantity": 50, ... }
```

### Step 6: Update progress on leaf nodes

```
PUT /api/v1/project/1/wbs/2
{ "progress": 100.0, "status": "COMPLETED", "actualStartDate": "2026-04-05", "actualEndDate": "2026-04-20" }
```

Parent nodes (1.0 Foundation) automatically recalculate their progress.

### Step 7: Recalculate costs

After linking payables and consumptions:

```
POST /api/v1/project/1/wbs/1/recalculate
```

Aggregates actual costs from all children up to the Foundation phase.

### Step 8: Check budget variance

```
GET /api/v1/project/1/wbs/tree
```

Compare `budgetedCost` vs `actualCost` at each level:

```
Foundation:  budgeted 500,000  |  actual 320,000  |  variance +180,000 (under budget)
  Excavation: budgeted 200,000  |  actual 195,000  |  variance +5,000
  Concrete:   budgeted 300,000  |  actual 125,000  |  variance +175,000 (in progress)
```

---

## Key Design Decisions

### 1. WBS is Optional

The `wbs_element_id` field on Task, PurchaseOrder, Intend, Payable, and MaterialConsumption is **nullable**. Existing projects and workflows work exactly as before. WBS is opt-in per project.

### 2. Self-Referential Hierarchy

The tree is modeled with a `parent_id` self-referential FK on the `wbs_element` table. This is the standard adjacency list pattern, simple to query and well-supported by JPA.

### 3. Cascade Delete

Deleting a parent WBS element cascades to all its descendants. Linked entities (tasks, POs, etc.) have their `wbs_element_id` set to null rather than being deleted.

### 4. WBS Code is User-Defined

The `wbs_code` is entered by the user (e.g., `"1.0"`, `"1.2.1"`). It is not auto-generated. This gives users control over their coding scheme. The only constraint is uniqueness within a project.

### 5. Progress on Leaves Only

Progress can only be set directly on leaf nodes. Parent progress is always a weighted average of children. This prevents inconsistencies where a parent shows 80% but its children are all at 0%.

### 6. Weight Controls Progress Influence

Each element has a `weight` (default 1.0) that determines how much it influences its parent's progress calculation. A work package with `weight: 3.0` has three times the influence of one with `weight: 1.0`.

### 7. Scoped to Project

WBS elements belong to exactly one project. The `/api/v1/project/{projectId}/wbs` URL structure makes this explicit. All queries are scoped to the project AND the current organization (multi-tenancy).

### 8. Move with Cycle Detection

The move operation prevents moving an element under its own descendant, which would create an infinite loop in the tree.

---

## File Structure

```
wbs/                                - Work Breakdown Structure module
  WbsElement.java                   - Entity (self-referential tree with project, org scoping)
  WbsElementRepository.java         - Repository (tree queries, org-scoped lookups)
  WbsElementService.java            - Service (CRUD, tree ops, progress/cost rollup, move)
  WbsElementController.java         - REST controller (/api/v1/project/{projectId}/wbs)
  dto/
    WbsElementCreationDto.java      - Input for creation
    WbsElementDto.java              - Full response with nested children
    WbsElementFlatDto.java          - Lightweight response for flat list/table views
    WbsElementUpdateDto.java        - Partial update fields
    WbsBulkCreateDto.java           - Wrapper for bulk creation
    WbsMoveDto.java                 - Input for move/re-parent operation
  enums/
    WbsStatus.java                  - NOT_STARTED, IN_PROGRESS, COMPLETED, ON_HOLD, CANCELLED

DtoConversions/
  WbsElementDtoConvertor.java       - Entity-to-DTO conversion (tree and flat modes)

db/changelog/v2.1/
  085-create-wbs-element.xml        - Creates wbs_element table, indexes, constraints
  086-add-wbs-element-to-entities.xml - Adds wbs_element_id FK to task, material_consumption,
                                       purchase_order, payable, intend
```

Modified entities (added nullable `wbsElement` ManyToOne field):
- `task/Task.java`
- `materialConsumption/MaterialConsumption.java`
- `purchaseOrder/PurchaseOrder.java`
- `payable/Payable.java`
- `intend/Intend.java`
- `project/Project.java` (added `wbsElements` OneToMany)
