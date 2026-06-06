# Project-Level Inventory Management

This document explains the project-level inventory management system - what it does, why it exists, how every entity is linked to a project, how storage locations work, and how to call every API endpoint.

---

## Table of Contents

1. [Overview](#overview)
2. [Why Project-Level Inventory?](#why-project-level-inventory)
3. [Storage Locations](#storage-locations)
4. [Task-Level Material Tracking](#task-level-material-tracking)
5. [Architecture](#architecture)
6. [Data Model](#data-model)
7. [Inventory Transaction Types](#inventory-transaction-types)
8. [How Stock Is Calculated](#how-stock-is-calculated)
9. [Event-Driven Inventory System](#event-driven-inventory-system)
10. [Stock Value Tracking (Weighted Average Cost)](#stock-value-tracking-weighted-average-cost)
11. [API Reference](#api-reference)
    - [Storage Locations](#storage-locations-api)
    - [Materials & Stock](#materials--stock)
    - [Indent (Material Requisition)](#indent-material-requisition)
    - [Purchase Orders](#purchase-orders)
    - [Goods Received Note (GRN)](#goods-received-note-grn)
    - [Material Consumption](#material-consumption)
    - [Site Transfer (Project-to-Project)](#site-transfer-project-to-project)
    - [Inventory Transactions](#inventory-transactions)
    - [Payables](#payables)
12. [Complete Workflow](#complete-workflow)
13. [Key Design Decisions](#key-design-decisions)
14. [File Structure](#file-structure)

---

## Overview

The Goods & Material Management system tracks procurement, inventory, consumption, and inter-project transfers. **Every inventory operation is scoped to a specific Project (site)** and can optionally specify a **Storage Location** (warehouse, godown, or project site store). This means:

- Stock is tracked **per material, per project** (not globally)
- Stock can optionally be tracked at the **storage location** level within a project
- Material consumption can optionally be attributed to a **specific task** within a project for activity-level tracking
- A GRN adds stock to the project (and optionally a specific storage location) where goods were received
- Material consumption reduces stock at the specific project (and optionally at a specific storage location)
- Site transfers move stock **from one project to another**, with optional source and destination storage locations, creating two inventory transactions (OUT at source, IN at destination)
- Purchase orders and indents are raised **for a specific project**
- Payables are attributed to a specific project for cost tracking

All entities also carry the `organization` (tenant) field for multi-tenancy.

---

## Why Project-Level Inventory?

Previously, inventory was tracked at the **organization level** - a single pool of stock shared across all projects. This caused problems:

| Problem | Example |
|---|---|
| No visibility into per-site stock | "How much cement does Project Alpha have?" - impossible to answer |
| Transfers were one-sided | Materials transferred OUT reduced global stock, but no record at the receiving site |
| No project cost tracking | Couldn't attribute material costs to specific projects |
| No project manager autonomy | Every PM saw the same global inventory |

The solution follows the industry standard (SAP, Oracle, Procore) of **location-based inventory management** where each project/site is a distinct inventory location.

---

## Storage Locations

In the real world, goods ordered for a project may not always be delivered directly to the project site. They might be stored in a central warehouse, godown, or other storage facility before being moved to the actual site. The **StorageLocation** entity models this.

### Why Storage Locations?

| Problem | Example |
|---|---|
| No physical location tracking | "Where is the cement physically stored?" - impossible to answer |
| No godown/warehouse management | Goods delivered to a central warehouse had no location record |
| No intra-project movement visibility | Moving goods from godown to site within the same project was not trackable |

### StorageLocation Entity

```java
StorageLocation {
    id
    locationName        // "Central Godown", "Project Alpha Site Store"
    locationType        // ENUM: PROJECT_SITE, WAREHOUSE, GODOWN
    address             // optional
    project             // nullable - a godown may serve multiple projects
    organization        // multi-tenancy
}
```

### Location Types

```java
public enum StorageLocationType {
    PROJECT_SITE,   // Storage at the project site itself
    WAREHOUSE,      // Central warehouse
    GODOWN          // Godown / storage facility
}
```

### Key Concepts

- **Storage location is optional** on all operations (GRN, consumption, transfer, inventory transactions). If not specified, the system works exactly as before at the project level.
- **`project` on StorageLocation is nullable** - a central warehouse or godown may serve multiple projects across the organization, while a project site store belongs to one project.
- **Two dimensions of tracking**: `project` = who owns/is paying for the stock (cost center), `storageLocation` = where the stock physically sits. These can differ (e.g., goods owned by Project Alpha stored at Central Warehouse).

---

## Task-Level Material Tracking

Within a project, different tasks (e.g., "Foundation Work", "Plastering", "Electrical Wiring") consume materials. Without task-level tracking, you can answer "Project Alpha used 100 bags of cement" but not "the foundation task used 60 and the plastering task used 40."

### Why Task-Level Tracking?

| Problem | Example |
|---|---|
| No activity-level visibility | "Which task consumed the most cement?" - impossible to answer |
| No task-level cost attribution | Cannot compare budgeted vs. actual material cost per task |
| No resource planning insight | Cannot predict material needs based on upcoming tasks |

This follows the industry standard approach used by SAP (WBS Element / Network Activity), Oracle Primavera (Work Order / Activity), and Procore (Cost Code) for activity-based material consumption.

### How It Works

An optional `task` field (ManyToOne to Task) has been added to both `MaterialConsumption` and `InventoryTransaction`:

- **Task is optional** — if not specified, consumption works exactly as before at the project level
- **Task must belong to the same project** — the system validates that `task.project == consumption.project` when a task is provided
- **Task propagates to InventoryTransaction** — when a material consumption event creates an inventory transaction, the task reference is carried through automatically
- **Stock is still tracked at (material, project, location)** — task does not affect stock calculations. It is purely an attribution/reporting dimension

### Three Dimensions of Tracking

| Dimension | Field | Purpose |
|---|---|---|
| `project` | Required | **Cost center** — who owns/is paying for the stock |
| `storageLocation` | Optional | **Physical location** — where the stock physically sits |
| `task` | Optional | **Activity** — what work consumed the material |

---

## Architecture

```
Indent (Project A needs materials)
  |
  v
Purchase Order (Buy for Project A)
  |
  v
GRN (Goods received at Project A, stored at Central Godown)
  --[GrnCreatedEvent]--> InventoryTransaction (GRN, +qty, Project A, Central Godown)
  |
  v
Material Consumption (Used at Project A, from Site Store, for Task "Foundation Work")
  --[MaterialConsumedEvent]--> InventoryTransaction (USE, -qty, Project A, Site Store, Task "Foundation Work")

Site Transfer (Project A, Godown -> Project B, Site Store)
  --[SiteTransferCreatedEvent]--> InventoryTransaction (TRANSFER_OUT, -qty, Project A, Godown)
                               --> InventoryTransaction (TRANSFER_IN, +qty, Project B, Site Store)

Payable (Cost attributed to Project A)
```

All inventory transactions are created **automatically** via Spring Application Events (AFTER_COMMIT phase). No manual stock updates are needed.

---

## Data Model

### Entity-Project Relationships

Every goods management entity has a **required** `project` field (ManyToOne to Project):

| Entity | Project Field | Storage Location Field | Task Field | Description |
|---|---|---|---|---|
| `InventoryTransaction` | `project` | `storageLocation` (optional) | `task` (optional) | Which project's stock changed, at which location, for which task |
| `GoodsReceivedNote` | `project` | `storageLocation` (optional) | - | Which project received the goods, at which location |
| `MaterialConsumption` | `project` | `storageLocation` (optional) | `task` (optional) | Which project consumed the materials, from which location, for which task |
| `SiteTransfer` | `sendingProject` + `receivingProject` | `sendingStorageLocation` + `receivingStorageLocation` (both optional) | - | Source and destination projects and locations |
| `PurchaseOrder` | `project` | - | - | Which project the PO is for |
| `Intend` | `project` | - | - | Which project raised the requisition |
| `Payable` | `project` | - | - | Which project the cost is attributed to |
| `StorageLocation` | `project` (optional) | - | - | Which project the location belongs to (null for org-level locations) |

### SiteTransfer - Special Case

SiteTransfer has **two** project references and **two** optional storage location references:

```
SiteTransfer
  - sendingProject            (FK to Project)          -- where materials come FROM
  - receivingProject          (FK to Project)          -- where materials go TO
  - sendingStorageLocation    (FK to StorageLocation)  -- optional: which location at the sending project
  - receivingStorageLocation  (FK to StorageLocation)  -- optional: which location at the receiving project
  - sendingPerson             (FK to Employee)         -- who initiated the transfer
```

Both projects must be valid within the same organization.

---

## Inventory Transaction Types

```java
public enum InventoryTransactionType {
    GRN,            // Stock IN - goods received from vendor
    USE,            // Stock OUT - materials consumed at project
    TRANSFER_OUT,   // Stock OUT - materials sent to another project
    TRANSFER_IN,    // Stock IN - materials received from another project
    ADJUST          // Manual adjustment
}
```

| Type | Quantity Sign | When Created |
|---|---|---|
| `GRN` | Positive (+) | GRN is created |
| `USE` | Negative (-) | Material consumption is recorded |
| `TRANSFER_OUT` | Negative (-) | Site transfer is created (at sending project) |
| `TRANSFER_IN` | Positive (+) | Site transfer is created (at receiving project) |
| `ADJUST` | +/- | Manual stock adjustment |

---

## How Stock Is Calculated

### Per-Project Stock

Stock is calculated as the `closingStock` of the **latest** `InventoryTransaction` for a given `(materialId, projectId)` pair.

```
GET /api/v1/materials/{materialId}/stock?projectId=1
```

Internally: queries the latest `InventoryTransaction` where `material_id = :materialId AND project_id = :projectId`, ordered by `transaction_date DESC, id DESC`, and returns `closingStock`.

### Aggregate Stock (Across All Projects)

```
GET /api/v1/materials/{materialId}/stock
```

Without `projectId`, the system sums the latest closing stock across every project that has transactions for that material.

### Per-Location Stock

```
GET /api/v1/materials/{materialId}/stock?projectId=1&storageLocationId=2
```

Location-level stock is calculated by summing all `quantityChanged` values from inventory transactions matching the `(materialId, projectId, storageLocationId)` triple. This provides a view of how much stock is physically at a specific location.

### Stock Validation

Before any stock-reducing operation (consumption, transfer), the system validates sufficient stock **at the specific project**:

```
InsufficientStockException:
"Insufficient stock for material ID 5 at project ID 3. Required: 50, Available: 30"
```

---

## Event-Driven Inventory System

The system uses Spring Application Events to automatically create inventory transactions.

### Event Flow

| Source Operation | Event Published | Inventory Transactions Created |
|---|---|---|
| Create GRN | `GrnCreatedEvent` | 1 per GRN item: `GRN`, +quantity, at GRN's project and storage location |
| Create MaterialConsumption | `MaterialConsumedEvent` | 1 transaction: `USE`, -quantity, at consumption's project, storage location, and task |
| Create SiteTransfer | `SiteTransferCreatedEvent` | **2 per item**: `TRANSFER_OUT` (-qty at sending project/location) + `TRANSFER_IN` (+qty at receiving project/location) |

All events are processed in the `AFTER_COMMIT` phase, meaning inventory transactions are only created after the source document is successfully saved.

### InventoryEventListener

Located at: `common/events/listeners/InventoryEventListener.java`

Each handler:
1. Gets the current stock for the material at the relevant project
2. Calculates: `closingStock = openingStock + quantityChanged`
3. Creates and saves the `InventoryTransaction` with all fields populated (material, project, storageLocation, task, organization, createdBy, referenceNumber, remarks)

The `storageLocation` and `task` are passed through from the source document (GRN, MaterialConsumption, SiteTransfer) to the resulting inventory transaction. If the source document has no storage location or task, those fields will be null on the transaction.

---

## Stock Value Tracking (Weighted Average Cost)

In addition to tracking stock **quantity**, the system tracks stock **value** using the **Weighted Average Cost (WAC)** method. This allows you to know the monetary value of inventory at any project or storage location at any point in time.

### How It Works

Every `CurrentStock` record holds both `currentQuantity` and `stockValue`. Every `InventoryTransaction` and `GrnItem` can carry a `unitCost`.

#### Inbound (GRN, Opening Balance, Transfer In)

When stock enters a location with a known `unitCost`:

```
newStockValue = existingStockValue + (incomingQty × unitCost)
```

Example: You have 100 units worth ₹5,000 (avg ₹50/unit). A GRN adds 50 units at ₹60/unit:
```
stockValue = 5000 + (50 × 60) = 8000
avgCost = 8000 / 150 = 53.33
```

#### Outbound (Consumption, Transfer Out)

When stock leaves a location, the system computes the weighted average cost from the current stock and reduces the value proportionally:

```
avgCost = currentStockValue / currentQuantity
valueReduced = outgoingQty × avgCost
newStockValue = currentStockValue - valueReduced
```

Example: From the 150 units worth ₹8,000 above, consume 30 units:
```
avgCost = 8000 / 150 = 53.33
valueReduced = 30 × 53.33 = 1600.00
newStockValue = 8000 - 1600 = 6400.00
```

#### Site Transfers

Transfers carry the weighted average cost from the **sending** location to the **receiving** location:

1. The system reads the avg cost at the sending `CurrentStock` before the transfer
2. **TRANSFER_OUT**: stock value decreases at sending side using avg cost (outbound logic)
3. **TRANSFER_IN**: stock value increases at receiving side using the same avg cost as `unitCost` (inbound logic)

This preserves the cost basis across locations.

### Fields Added

| Entity / DTO | Field | Type | Description |
|---|---|---|---|
| `InventoryTransaction` | `unitCost` | DECIMAL(15,2) | Cost per unit for this transaction (null for outbound using avg) |
| `CurrentStock` | `stockValue` | DECIMAL(15,2) | Total monetary value of stock at this location (default 0.00) |
| `GrnItem` | `unitCost` | DECIMAL(15,2) | Cost per unit of goods received |
| `MaterialCreationDto` | `unitCost` | BigDecimal | Cost per unit for opening balance |
| `GrnItemDto` | `unitCost` | BigDecimal | Cost per unit when creating a GRN |
| `StockDto` | `stockValue` | BigDecimal | Value of stock for a material at a location |
| `InventoryMaterialStockDto` | `totalStockValue` | BigDecimal | Sum of stock values across all materials |
| `MaterialDto` | `stockValue` | BigDecimal | Aggregate stock value across all projects |
| `MaterialWithStockDto` | `stockValue` | BigDecimal | Stock value at the queried scope |
| `InventoryTransactionDto` | `unitCost` | BigDecimal | Unit cost recorded on the transaction |

### Stock Value Query Methods

| Method | Description |
|---|---|
| `InventoryService.getStockValue(materialId, projectId)` | Total stock value for a material at a project (across all locations) |
| `InventoryService.getAggregateStockValue(materialId)` | Total stock value for a material across all projects |
| `InventoryService.getStockValueAtLocation(materialId, projectId, storageLocationId)` | Stock value at a specific storage location |
| `InventoryService.getAverageCost(materialId, projectId, storageLocationId)` | Weighted average cost per unit at a location |

### Providing Unit Cost

- **Creating a material with opening stock**: pass `unitCost` in `MaterialCreationDto`
- **Receiving goods (GRN)**: pass `unitCost` on each item in `GrnItemDto`
- **Consumption**: no `unitCost` needed — the system automatically uses the weighted average
- **Site transfers**: no `unitCost` needed — the system reads the avg cost from the sending side and carries it to the receiving side

---

## API Reference

### Storage Locations API

**Create a storage location:**
```
POST /api/v1/storage-locations
```
```json
{
  "locationName": "Central Godown Sector 4",
  "locationType": "GODOWN",
  "address": "Plot 42, Industrial Area, Sector 4",
  "projectId": null
}
```

`projectId` is optional. Set it to associate the location with a specific project (e.g., a site store). Leave it null for org-level locations like central warehouses.

**Create a project site store:**
```json
{
  "locationName": "Project Alpha Site Store",
  "locationType": "PROJECT_SITE",
  "address": "Project Alpha construction site",
  "projectId": 1
}
```

**Response format:**
```json
{
  "id": 1,
  "locationName": "Central Godown Sector 4",
  "locationType": "GODOWN",
  "address": "Plot 42, Industrial Area, Sector 4",
  "projectId": null,
  "projectName": null
}
```

**Query:**
```
GET /api/v1/storage-locations
GET /api/v1/storage-locations/{id}
GET /api/v1/storage-locations/all?pageNo=0&pageSize=10
GET /api/v1/storage-locations/project/{projectId}
GET /api/v1/storage-locations/type/GODOWN
GET /api/v1/storage-locations/type/WAREHOUSE
GET /api/v1/storage-locations/type/PROJECT_SITE
```

---

### Materials & Stock

**Get stock at a specific project:**
```
GET /api/v1/materials/{id}/stock?projectId=1
```
Response:
```json
{
  "id": 1,
  "sku": "MAT001",
  "materialName": "Portland Cement",
  "unit": "bag",
  "currentStock": 95,
  "stockValue": 42750.00
}
```

**Get stock at a specific storage location within a project:**
```
GET /api/v1/materials/{id}/stock?projectId=1&storageLocationId=2
```
Returns the stock for that material at the specific location, calculated by summing all `quantityChanged` values from inventory transactions at that `(material, project, storageLocation)` triple.

**Get aggregate stock across all projects:**
```
GET /api/v1/materials/{id}/stock
```
Returns the sum of latest closing stock across all projects.

---

### Indent (Material Requisition)

**Create indent for a project:**
```
POST /api/v1/intends
```
```json
{
  "intendNumber": "IND-2026-001",
  "projectId": 1,
  "createdByEmployeeId": 5,
  "status": "PENDING",
  "expectedOn": "2026-04-01T00:00:00",
  "remarks": "Materials for Block A construction"
}
```

Response includes `projectId` and `projectName`.

**Query:**
```
GET /api/v1/intends
GET /api/v1/intends/{id}
GET /api/v1/intends/all?pageNo=0&pageSize=10
DELETE /api/v1/intends/{id}
```

---

### Purchase Orders

**Create PO for a project:**
```
POST /api/v1/purchase-orders
```
```json
{
  "poNumber": "PO-2026-001",
  "vendorId": 1,
  "projectId": 1,
  "intendId": 1,
  "status": "DRAFT",
  "createdBy": 5,
  "expectedDeliveryDate": "2026-04-10T00:00:00",
  "remarks": "Rush order",
  "items": [
    {
      "materialId": 1,
      "indentItemId": 1,
      "orderedQuantity": 100,
      "unitPrice": 450.00,
      "totalPrice": 45000.00,
      "remarks": "Grade 53 cement"
    }
  ],
  "totalAmount": 45000.00
}
```

Response includes `projectId` and `projectName`.

**Status flow:** `DRAFT` -> `APPROVED` -> `SENT_TO_VENDOR` -> `PARTIALLY_RECEIVED` -> `FULLY_RECEIVED`

**Query:**
```
GET /api/v1/purchase-orders
GET /api/v1/purchase-orders/{id}
GET /api/v1/purchase-orders/all?pageNo=0&pageSize=10
GET /api/v1/purchase-orders/vendor/{vendorId}
GET /api/v1/purchase-orders/intend/{intendId}
GET /api/v1/purchase-orders/status/{status}
PUT /api/v1/purchase-orders                          # Update PO
PATCH /api/v1/purchase-orders/{id}/status?status=APPROVED
```

---

### Goods Received Note (GRN)

**Create GRN at a project (triggers automatic stock increase):**
```
POST /api/v1/grns
```
```json
{
  "grnNumber": "GRN-2026-001",
  "receivedOn": "2026-04-10T10:30:00",
  "receivedByEmployeeId": 5,
  "vendorId": 1,
  "projectId": 1,
  "storageLocationId": 2,
  "purchaseOrderId": 1,
  "deliveryChallanNumber": "DC-2026-001",
  "invoiceNumber": "INV-2026-001",
  "invoiceAmount": 45000.00,
  "items": [
    {
      "materialId": 1,
      "orderedQuantity": 100,
      "receivedQuantity": 95,
      "unitCost": 450.00
    }
  ]
}
```

`storageLocationId` is optional. If provided, it records where the goods were physically delivered.

`unitCost` on each item is optional. If provided, it is used to track stock value via the Weighted Average Cost method (e.g., a central godown instead of the project site). The inventory transaction will also carry this storage location.

**What happens automatically:**
1. GRN is saved with the specified project and optional storage location
2. `GrnCreatedEvent` is published
3. `InventoryEventListener` creates `InventoryTransaction` records (type: `GRN`, positive quantity) **at the specified project and storage location**
4. Stock increases for each material at that project

Response includes `projectId`, `projectName`, `storageLocationId`, and `storageLocationName`.

**Query:**
```
GET /api/v1/grns
GET /api/v1/grns/{id}
GET /api/v1/grns/all?pageNo=0&pageSize=10
GET /api/v1/grns/vendor/{vendorId}
GET /api/v1/grns/date-range?startDate=2026-04-01T00:00:00&endDate=2026-04-30T23:59:59
```

---

### Material Consumption

**Record consumption at a project (triggers automatic stock decrease):**
```
POST /api/v1/material-consumptions
```
```json
{
  "consumptionDate": "2026-04-12T14:00:00",
  "materialId": 1,
  "quantity": 20,
  "consumptionType": "USED_FROM_STOCK",
  "details": "Used for Block A foundation work",
  "projectId": 1,
  "storageLocationId": 3,
  "taskId": 7,
  "createdBy": 5
}
```

`storageLocationId` is optional. If provided, it records which storage location the materials were consumed from.

`taskId` is optional. If provided, the system validates the task exists in the same organization and belongs to the same project. The task reference is then carried through to the resulting `InventoryTransaction` for activity-level tracking.

**What happens automatically:**
1. **Stock validation** at the specified project (throws `InsufficientStockException` if not enough)
2. **Task validation** (if provided): task must exist and belong to the same project
3. Consumption record is saved
4. `MaterialConsumedEvent` is published
5. `InventoryTransaction` created (type: `USE`, negative quantity) at the specified project, storage location, and task
6. Stock decreases at that project

Response includes `projectId`, `projectName`, `storageLocationId`, `storageLocationName`, `taskId`, and `taskTitle`.

**Query:**
```
GET /api/v1/material-consumptions
GET /api/v1/material-consumptions/{id}
GET /api/v1/material-consumptions/all?pageNo=0&pageSize=10
GET /api/v1/material-consumptions/material/{materialId}
GET /api/v1/material-consumptions/type/{consumptionType}
GET /api/v1/material-consumptions/date-range?startDate=...&endDate=...
GET /api/v1/material-consumptions/task/{taskId}
```

---

### Site Transfer (Project-to-Project)

Transfers move stock **between two projects** (and optionally between specific storage locations) and create inventory transactions on **both sides**.

**Create transfer between projects:**
```
POST /api/v1/site-transfers
```
```json
{
  "transferNumber": "ST-2026-001",
  "issueDate": "2026-04-15T09:00:00",
  "sendingPerson": 5,
  "sendingProjectId": 1,
  "sendingStorageLocationId": 2,
  "receivingProjectId": 2,
  "receivingStorageLocationId": 3,
  "status": "PENDING",
  "items": [
    {
      "materialId": 1,
      "sentQuantity": 30,
      "remarks": "Transfer for Project Beta requirements"
    }
  ]
}
```

`sendingStorageLocationId` and `receivingStorageLocationId` are both optional. They record which physical locations materials are moving between.

**What happens automatically:**
1. **Stock validation** at the **sending project** (throws `InsufficientStockException` if not enough)
2. Transfer record is saved
3. `SiteTransferCreatedEvent` is published
4. For each item, **TWO** inventory transactions are created:
   - `TRANSFER_OUT` at sending project/location: stock **decreases** by sentQuantity
   - `TRANSFER_IN` at receiving project/location: stock **increases** by sentQuantity
5. Net stock across the organization stays the same (zero-sum transfer)

Response includes `sendingProjectId`, `sendingProjectName`, `sendingStorageLocationId`, `sendingStorageLocationName`, `receivingProjectId`, `receivingProjectName`, `receivingStorageLocationId`, `receivingStorageLocationName`.

**Status flow:** `PENDING` -> `PARTIALLY_TRANSFERRED` -> `COMPLETED`

**Query:**
```
GET /api/v1/site-transfers
GET /api/v1/site-transfers/{id}
GET /api/v1/site-transfers/all?pageNo=0&pageSize=10
GET /api/v1/site-transfers/status/{status}
GET /api/v1/site-transfers/sending-project/{projectId}
GET /api/v1/site-transfers/receiving-project/{projectId}
PATCH /api/v1/site-transfers/{id}/status?status=COMPLETED
```

---

### Inventory Transactions

Inventory transactions are **auto-generated** (never created directly via API). They serve as a complete audit trail.

**Query all transactions:**
```
GET /api/v1/inventory-transactions
GET /api/v1/inventory-transactions/{id}
GET /api/v1/inventory-transactions/all?pageNo=0&pageSize=10
```

**Filter by material:**
```
GET /api/v1/inventory-transactions/material/{materialId}
```

**Filter by project (see all stock movements at a project):**
```
GET /api/v1/inventory-transactions/project/{projectId}
```

**Filter by storage location (see all stock movements at a location):**
```
GET /api/v1/inventory-transactions/storage-location/{storageLocationId}
```

**Filter by type:**
```
GET /api/v1/inventory-transactions/type/GRN
GET /api/v1/inventory-transactions/type/USE
GET /api/v1/inventory-transactions/type/TRANSFER_OUT
GET /api/v1/inventory-transactions/type/TRANSFER_IN
```

**Filter by date range:**
```
GET /api/v1/inventory-transactions/date-range?startDate=2026-04-01T00:00:00&endDate=2026-04-30T23:59:59
```

**Filter by task (see all stock movements attributed to a specific task):**
```
GET /api/v1/inventory-transactions/task/{taskId}
```

**Task material usage summary (aggregated material usage grouped by task for a project):**
```
GET /api/v1/inventory-transactions/project/{projectId}/task-summary
```
Response:
```json
[
  {
    "taskId": 7,
    "taskTitle": "Foundation Work",
    "materials": [
      {
        "materialId": 1,
        "materialName": "Portland Cement",
        "unit": "bag",
        "totalQuantityUsed": 60.0,
        "totalCost": 27000.00
      },
      {
        "materialId": 2,
        "materialName": "TMT Steel Bar",
        "unit": "kg",
        "totalQuantityUsed": 200.0,
        "totalCost": 14000.00
      }
    ],
    "totalQuantityUsed": 260.0,
    "totalCost": 41000.00
  },
  {
    "taskId": 8,
    "taskTitle": "Plastering",
    "materials": [ ... ],
    "totalQuantityUsed": 40.0,
    "totalCost": 18000.00
  }
]
```

**Response format (single transaction):**
```json
{
  "id": 1,
  "transactionDate": "2026-04-10T10:30:00",
  "materialId": 1,
  "materialName": "Portland Cement",
  "openingStock": 0,
  "quantityChanged": 95,
  "closingStock": 95,
  "transactionType": "GRN",
  "referenceNumber": "GRN-2026-001",
  "remarks": "GRN received from ABC Suppliers",
  "projectId": 1,
  "projectName": "Project Alpha",
  "storageLocationId": 2,
  "storageLocationName": "Central Godown Sector 4",
  "taskId": null,
  "taskTitle": null,
  "createdBy": { ... },
  "unitCost": 450.00
}
```

`storageLocationId`/`storageLocationName` and `taskId`/`taskTitle` will be `null` if the source operation did not specify a storage location or task.

---

### Payables

**Create payable for a project:**
```
POST /api/v1/payables
```
```json
{
  "payableNumber": "PAY-2026-001",
  "contractorName": "ABC Suppliers",
  "contractType": "MATERIAL",
  "amountRecorded": 45000.00,
  "amountPaid": 20000.00,
  "vendorId": 1,
  "goodsReceivedNoteId": 1,
  "projectId": 1,
  "createdBy": 5
}
```

Response includes `projectId` and `projectName`, plus computed `amountDue`.

---

## Complete Workflow

Here is a typical end-to-end flow showing how a project manager procures and uses materials, including storage location tracking:

### 1. Create Material Master Data (one-time setup)
```
POST /api/v1/materials
{ "sku": "CEM-53", "materialName": "Portland Cement Grade 53", "unit": "bag", "createdBy": 1, "openingStock": 100, "projectId": 1, "unitCost": 50.00 }
```
If `openingStock` and `unitCost` are provided, the system seeds `CurrentStock` with `stockValue = openingStock × unitCost` (e.g., 100 × 50.00 = 5000.00).

### 2. Create Storage Locations (one-time setup)
```
POST /api/v1/storage-locations
{ "locationName": "Central Godown", "locationType": "GODOWN", "address": "Industrial Area" }

POST /api/v1/storage-locations
{ "locationName": "Project Alpha Site Store", "locationType": "PROJECT_SITE", "projectId": 1 }

POST /api/v1/storage-locations
{ "locationName": "Project Beta Site Store", "locationType": "PROJECT_SITE", "projectId": 2 }
```

### 3. Raise Indent for Project Alpha
```
POST /api/v1/intends
{ "intendNumber": "IND-001", "projectId": 1, "createdByEmployeeId": 5, "status": "PENDING", ... }
```
Then add indent items via the indent items API.

### 4. Create Purchase Order for Project Alpha
```
POST /api/v1/purchase-orders
{ "poNumber": "PO-001", "vendorId": 1, "projectId": 1, "intendId": 1, "status": "DRAFT", ... }
```
Approve: `PATCH /api/v1/purchase-orders/1/status?status=APPROVED`

### 5. Receive Goods at Central Godown (for Project Alpha)
```
POST /api/v1/grns
{ "grnNumber": "GRN-001", "projectId": 1, "storageLocationId": 1, "vendorId": 1, ...,
  "items": [{ "materialId": 1, "orderedQuantity": 100, "receivedQuantity": 95, "unitCost": 450.00 }] }
```
Stock automatically increases at Project Alpha (qty: +95, stockValue: +42,750). The inventory transaction records that goods are physically at the Central Godown.

### 6. Check Stock
```
GET /api/v1/materials/1/stock?projectId=1                          -> { "currentStock": 95 }
GET /api/v1/materials/1/stock?projectId=1&storageLocationId=1      -> { "currentStock": 95 }  (at godown)
GET /api/v1/materials/1/stock?projectId=1&storageLocationId=2      -> { "currentStock": 0 }   (at site store)
GET /api/v1/materials/1/stock?projectId=2                          -> { "currentStock": 0 }
GET /api/v1/materials/1/stock                                      -> { "currentStock": 95 }  (aggregate)
```

### 7. Use Materials at Project Alpha (from Site Store, for a specific Task)
```
POST /api/v1/material-consumptions
{ "materialId": 1, "quantity": 20, "projectId": 1, "storageLocationId": 2, "taskId": 7, "consumptionType": "USED_FROM_STOCK", ... }
```
Stock at Project Alpha: 95 -> 75. The consumption and resulting inventory transaction are attributed to Task 7 ("Foundation Work").

### 8. Transfer to Project Beta (from Godown to Site Store)
```
POST /api/v1/site-transfers
{
  "sendingProjectId": 1,
  "sendingStorageLocationId": 1,
  "receivingProjectId": 2,
  "receivingStorageLocationId": 3,
  "items": [{ "materialId": 1, "sentQuantity": 30 }],
  ...
}
```
Project Alpha stock: 75 -> 45 (TRANSFER_OUT from Central Godown)
Project Beta stock: 0 -> 30 (TRANSFER_IN to Project Beta Site Store)

### 9. Verify
```
GET /api/v1/materials/1/stock?projectId=1                          -> { "currentStock": 45 }
GET /api/v1/materials/1/stock?projectId=1&storageLocationId=1      -> { "currentStock": 65 }  (godown: 95 received - 30 transferred)
GET /api/v1/materials/1/stock?projectId=1&storageLocationId=2      -> { "currentStock": -20 } (site: -20 consumed)
GET /api/v1/materials/1/stock?projectId=2                          -> { "currentStock": 30 }
GET /api/v1/materials/1/stock?projectId=2&storageLocationId=3      -> { "currentStock": 30 }  (site store)
GET /api/v1/materials/1/stock                                      -> { "currentStock": 75 }  (aggregate unchanged after transfer)
```

### 10. View Audit Trail
```
GET /api/v1/inventory-transactions/project/1
```
Shows: GRN (+95 at Godown), USE (-20 at Site Store, Task "Foundation Work"), TRANSFER_OUT (-30 at Godown) -> closing stock 45

### 11. View Task-Level Material Usage
```
GET /api/v1/inventory-transactions/project/1/task-summary
```
Shows material usage aggregated by task — e.g., Task "Foundation Work" consumed 20 bags of cement.

```
GET /api/v1/inventory-transactions/task/7
```
Shows all inventory transactions attributed to Task 7 ("Foundation Work").

```
GET /api/v1/inventory-transactions/storage-location/1
```
Shows: GRN (+95), TRANSFER_OUT (-30) -> all movements at the Central Godown

```
GET /api/v1/inventory-transactions/project/2
```
Shows: TRANSFER_IN (+30 at Site Store) -> closing stock 30

---

## Key Design Decisions

### 1. Project is Required (Not Optional)
All goods management entities require a `project` field. There is no "unassigned" or "organization-level" stock. Every material movement must belong to a project.

### 2. Storage Location is Optional
Storage location is an **optional** field on GRN, MaterialConsumption, SiteTransfer, and InventoryTransaction. If not specified, operations work at the project level exactly as before. This ensures backward compatibility - existing integrations and data are unaffected.

### 3. Two Dimensions: Ownership vs. Physical Location
`project` represents **ownership/cost center** (who is paying for this stock). `storageLocation` represents **physical location** (where is it sitting). These can differ: goods owned by Project Alpha may be stored at a central warehouse.

### 4. Site Transfers Create Two Transactions
Unlike the old system where transfers only reduced stock at the source, the new system creates matched pairs: `TRANSFER_OUT` at source and `TRANSFER_IN` at destination. Each transaction carries the respective storage location. This ensures stock is tracked on both sides and aggregate totals remain consistent.

### 5. Stock Validation is Per-Project
When consuming materials or creating transfers, stock is validated **at the specific project**, not at the organization level or storage location level. You can't consume cement at Project Alpha just because Project Beta has stock.

### 6. Location-Level Stock Uses SUM
Project-level stock uses the `closingStock` field from the latest transaction. Location-level stock is calculated by summing all `quantityChanged` values for a `(material, project, storageLocation)` triple. This avoids adding a separate `closingLocationStock` field and keeps the transaction model simple.

### 7. Aggregate Stock is Derived
Organization-wide stock is computed by summing per-project stock. There is no separate "org-level" inventory record. This is a read-only view.

### 8. StorageLocation.project is Nullable
A storage location's `project` field is nullable. Central warehouses and godowns that serve the entire organization are not tied to any single project (`projectId = null`). Project site stores are linked to their project (`projectId = 1`).

### 9. Backward Compatibility with Old `TRANSFER` Enum
The old `TRANSFER` enum value has been replaced with `TRANSFER_OUT` and `TRANSFER_IN` for clarity. Any existing data using the old `TRANSFER` value will need migration.

### 10. Stock Value Uses Weighted Average Cost (WAC)
Stock value is tracked using the **Weighted Average Cost** method, the same approach used by SAP and Oracle. Each inbound operation (GRN, opening balance, transfer in) adds `qty × unitCost` to the stock value. Each outbound operation (consumption, transfer out) reduces the stock value by `qty × avgCost`, where `avgCost = stockValue / currentQuantity`. This means the average cost per unit changes with every inbound receipt at a different price. WAC was chosen over FIFO/LIFO because it is simpler to implement (no need to track individual cost layers), widely used in construction industry ERP systems, and produces a smoothed cost that avoids price volatility.

### 11. Unit Cost is Optional
The `unitCost` field on GRN items and opening balance is optional. If not provided, stock value for that receipt is not tracked (treated as zero cost). This ensures backward compatibility — existing integrations that don't provide cost data continue to work without changes. Stock quantity tracking is completely unaffected by whether cost data is provided.

### 12. Task is Optional (Activity-Level Tracking)
The `task` field on `MaterialConsumption` and `InventoryTransaction` is optional. If not specified, consumption works exactly as before at the project level. This follows the same optional pattern as `storageLocation` and ensures backward compatibility. When provided, the task must belong to the same project as the consumption — the system validates `task.project == consumption.project` and throws `IllegalArgumentException` if they differ. Task does not affect stock calculations — it is purely a reporting/attribution dimension, not a stock-tracking dimension.

### 13. Task Propagates Through Events
When a `MaterialConsumedEvent` creates an `InventoryTransaction`, the `task` reference is automatically carried from the consumption to the transaction. This means the inventory audit trail records which task consumed materials without requiring any additional event handling. GRN and SiteTransfer transactions do not carry a task reference — only `USE` transactions can be attributed to tasks, which matches the real-world model where tasks consume materials but don't receive or transfer them.

---

## File Structure

```
storageLocation/            - Storage location management (NEW)
  StorageLocation.java      - Entity (locationName, locationType, address, project?, organization)
  StorageLocationRepository.java
  StorageLocationService.java
  StorageLocationController.java
  dto/
    StorageLocationDto.java
    StorageLocationCreationDto.java
  enums/
    StorageLocationType.java  - PROJECT_SITE, WAREHOUSE, GODOWN
goodsReceivedNote/          - GRN management (+ project field, + optional storageLocation)
grnItem/                    - GRN line items
indentItem/                 - Indent line items
intend/                     - Material requisitions (+ project field)
inventoryTransaction/       - Inventory ledger (+ project field, + optional storageLocation, + optional task, per-project stock)
  InventoryService.java     - getCurrentStock(materialId, projectId), getStockAtLocation(materialId, projectId, storageLocationId), getAggregateStock(), validateSufficientStock()
  dto/TaskMaterialUsageDto.java - Aggregated material usage per task with per-material breakdown
material/                   - Material master
materialConsumption/        - Usage records (+ project field, + optional storageLocation, + optional task)
payable/                    - Financial obligations (+ project field)
purchaseOrder/              - PO management (+ project field)
purchaseOrderItem/          - PO line items
siteTransfer/               - Project-to-project transfers (sendingProject + receivingProject, + optional sending/receivingStorageLocation)
siteTransferItem/           - Transfer line items
vendor/                     - Vendor master
common/events/              - GrnCreatedEvent, MaterialConsumedEvent, SiteTransferCreatedEvent
common/events/listeners/    - InventoryEventListener (handles all three events, passes through storageLocation and task)
DtoConversions/             - DTO convertors (all updated with project, storageLocation, and task fields)
```

Each module follows the standard structure: Entity, Repository, Service, Controller, DTOs, and Enums.
