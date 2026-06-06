# Echno → Enterprise Project Management Platform
## Backend Scaling Plan: Spring Boot Implementation Roadmap

> **Objective:** Evolve the Echno backend into a fully enterprise-grade API platform
> on par with Oracle Primavera P6 — covering scheduling, resource management, cost control,
> earned value, risk, quality, and executive reporting — while retaining and building on the
> existing HR, attendance, inventory, and finance modules.

---

## Table of Contents

1. [Current Backend State Assessment](#1-current-backend-state-assessment)
2. [Target Capability Gap Analysis](#2-target-capability-gap-analysis)
3. [Architecture Evolution](#3-architecture-evolution)
4. [Phase Roadmap](#4-phase-roadmap)
5. [Phase 1 — Scheduling Engine & WBS](#5-phase-1--scheduling-engine--wbs)
6. [Phase 2 — Resource & Cost Management](#6-phase-2--resource--cost-management)
7. [Phase 3 — Earned Value Management (EVM)](#7-phase-3--earned-value-management-evm)
8. [Phase 4 — Risk & Issue Intelligence](#8-phase-4--risk--issue-intelligence)
9. [Phase 5 — Portfolio & Programme Management](#9-phase-5--portfolio--programme-management)
10. [Phase 6 — Reporting, Analytics & BI](#10-phase-6--reporting-analytics--bi)
11. [Phase 7 — Document Control & Change Management](#11-phase-7--document-control--change-management)
12. [Phase 8 — Mobile & Field Operations API](#12-phase-8--mobile--field-operations-api)
13. [Data Model Summary](#13-data-model-summary)
14. [Package Structure](#14-package-structure)
15. [Implementation Priorities](#15-implementation-priorities)

---

## 1. Current Backend State Assessment

### What We Have (Strengths)

| Module | Package | Maturity | Notes |
|--------|---------|----------|-------|
| Projects | `project` | ✅ Strong | CRUD, team, dates, progress, geo |
| Tasks | `task` | ✅ Strong | Assignees, categories, dates, status (4-state) |
| Issues | `issue` | ✅ Strong | 8-state lifecycle, comments, attachments |
| Materials / Inventory | `material`, `inventoryTransaction`, `storageLocation` | ✅ Strong | Stock tracking, location-level inventory |
| Purchase Orders | `purchaseOrder`, `purchaseOrderItem` | ✅ Strong | PO lifecycle, vendor links, project-scoped |
| GRN / Receiving | `goodsReceivedNote`, `grnItem` | ✅ Strong | Goods receipt against PO |
| Indent / Requisition | `intend`, `indentItem` | ✅ Moderate | Internal material requests |
| Site Transfers | `siteTransfer`, `siteTransferItem` | ✅ Moderate | Cross-project transfers |
| HR / Employee | `employee` | ✅ Strong | Roles, org scoping |
| Attendance | `attendance` | ✅ Strong | Clock in/out, shifts, regularization, geofence |
| Leave | `leave` | ✅ Strong | Full leave lifecycle |
| Vendors | `vendor` | ✅ Strong | Vendor management |
| Payables | `payable` | ✅ Moderate | Accounts payable |
| Organization / Multitenancy | `organization`, `common.multitenancy` | ✅ Strong | `TenantScopedEntity`, org filter |
| Auth (Keycloak) | `auth`, `keycloak` | ✅ Strong | RBAC, multi-tenant |
| Billing | `billing` | ✅ Moderate | Plans, subscriptions, feature gates |
| PDF Generation | `pdfGeneration` | ✅ Moderate | Document rendering |
| Storage Locations | `storageLocation` | ✅ Strong | Project-scoped, typed locations |

### What We Are Missing (Gaps)

| Capability | Primavera Equivalent | Priority |
|------------|---------------------|----------|
| Task dependencies (FS/SS/FF/SF) | Relationship types | P0 |
| CPM / Critical Path calculation | Float & critical path | P0 |
| Work Breakdown Structure (WBS) | WBS hierarchy | P0 |
| Baseline scheduling | Baseline plans | P0 |
| Project calendars (working days/holidays) | Resource calendars | P1 |
| Resource assignments to tasks | Resource loading | P1 |
| Cost accounts (CBS) | Cost breakdown structure | P1 |
| Earned Value Management (EVM) | EVM module | P1 |
| S-curve / time-phased data | Progress curves | P1 |
| Risk register | Risk management | P1 |
| Portfolio aggregation | Portfolio view | P2 |
| Change request management | Change management | P2 |
| RFI / Transmittal / Submittal | Document control | P2 |
| Progress period updates | Short-interval planning | P2 |
| Notification / event system | Alerts & notifications | P2 |
| Audit log for schedule changes | Change history | P2 |
| WebSocket support | Real-time updates | P3 |
| XER / MS Project import | Interoperability | P3 |
| AI schedule analysis | Predictive analytics | P3 |

---

## 2. Target Capability Gap Analysis

### Primavera P6 → Echno Backend Mapping

```
Primavera P6 Capability         → Backend Implementation
────────────────────────────────────────────────────────────────
Activities / WBS                → Task entity + new WbsNode entity
Relationships (FS/SS/FF/SF)     → new TaskDependency entity
Resource assignments            → new ResourceAssignment entity (extends material/employee)
Baseline                        → new ScheduleBaseline + BaselineActivity entities
CPM / Float                     → CpmCalculationService (topological sort + forward/backward pass)
Cost accounts                   → new CostAccount entity (linked to WBS)
Earned value                    → EvmCalculationService + EvmSnapshot entity
Calendars                       → new ProjectCalendar + CalendarException entities
Risk register                   → new Risk + RiskAction entities
Progress updates                → new ProgressUpdate entity (period-based)
Reports                         → new ReportDefinition entity + ReportGenerationService
Portfolio                       → new Portfolio entity (multi-project aggregation)
```

---

## 3. Architecture Evolution

### Current Backend Architecture
```
Spring Boot REST API (/api/v1/)
  ├── Controllers (REST + Web)
  ├── Services (business logic)
  ├── Repositories (Spring Data JPA)
  ├── Entities (JPA / Hibernate)
  ├── DTOs (Creation, Response, Patch)
  └── Mappers
           ↓
      PostgreSQL (multi-tenant, org-scoped via @Filter)
           ↓
      Keycloak (RBAC / JWT)
```

### Target Backend Architecture
```
Spring Boot REST API (/api/v1/) + WebSocket (STOMP)
  ├── Controllers (REST + Web)
  ├── Services (business logic)
  │   ├── CpmCalculationService       ← NEW: CPM engine
  │   ├── EvmCalculationService       ← NEW: EVM metrics
  │   ├── ReportGenerationService     ← NEW: PDF/Excel reports
  │   └── NotificationService         ← NEW: events / alerts
  ├── Repositories (Spring Data JPA)
  ├── Entities (JPA / Hibernate)
  ├── DTOs
  └── Mappers
           ↓
      PostgreSQL + (optional) Redis for CPM result caching
           ↓
      S3-compatible object storage (existing attachments + new docs)
           ↓
      Keycloak (RBAC / JWT)
```

### Key Backend Architectural Principles

1. **CPM is server-authoritative** — Calculation runs in `CpmCalculationService`; result cached per project + data-date
2. **All new entities implement `TenantScopedEntity`** — Mandatory org-scoped `@Filter` on every new entity
3. **Event sourcing for schedule mutations** — Every date/dependency change appends to `ScheduleChangeLog`
4. **Consistent DTO pattern** — Every entity has `*CreationDto`, `*Dto`, `*PatchDto`, and optionally `*SimpleDto`
5. **Feature-flag gating via billing** — New modules check subscription feature access using the existing `billing` module

---

## 4. Phase Roadmap

```
2026 Q1–Q2  │  Phase 1: Scheduling Engine & WBS
2026 Q2–Q3  │  Phase 2: Resource & Cost Management
2026 Q3–Q4  │  Phase 3: Earned Value Management
2026 Q4     │  Phase 4: Risk & Issue Intelligence
2027 Q1     │  Phase 5: Portfolio & Programme Management
2027 Q1–Q2  │  Phase 6: Reporting, Analytics & BI
2027 Q2–Q3  │  Phase 7: Document Control & Change Management
2027 Q3–Q4  │  Phase 8: Mobile & Field Operations API
```

---

## 5. Phase 1 — Scheduling Engine & WBS

> **Goal:** Every project has CPM-calculated scheduling: task dependencies,
> float values, critical path flags, and baseline snapshots.

### 5.1 Work Breakdown Structure (WBS)

**New package:** `org.tornotron.echno_backend.wbs`

**Entity: `WbsNode`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class WbsNode implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private WbsNode parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<WbsNode> children = new ArrayList<>();

    @Column(name = "code", nullable = false)    // e.g. "1.2.3"
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "level", nullable = false)
    private Integer level;                      // 1 = root, n = leaf

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private WbsNodeType nodeType;               // SUMMARY, WORK_PACKAGE, ACTIVITY

    @Column(name = "display_order")
    private Integer displayOrder;

    @OneToMany(mappedBy = "wbsNode")
    private List<Task> tasks = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

// enums/WbsNodeType.java
public enum WbsNodeType {
    SUMMARY, WORK_PACKAGE, ACTIVITY
}
```

**Enhancements to `Task` entity:**
```java
// Add to existing Task.java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "wbs_node_id")
private WbsNode wbsNode;

@Column(name = "activity_id")           // user-facing code, e.g. "A1050"
private String activityId;

@Column(name = "planned_start_date")
private LocalDateTime plannedStartDate;

@Column(name = "planned_end_date")
private LocalDateTime plannedEndDate;

@Column(name = "actual_start_date")
private LocalDateTime actualStartDate;

@Column(name = "actual_end_date")
private LocalDateTime actualEndDate;

@Column(name = "baseline_start_date")
private LocalDateTime baselineStartDate;

@Column(name = "baseline_end_date")
private LocalDateTime baselineEndDate;

@Column(name = "duration_days")
private Integer durationDays;           // working days

@Column(name = "remaining_duration_days")
private Integer remainingDurationDays;

@Column(name = "total_float")
private Double totalFloat;              // calculated by CPM

@Column(name = "free_float")
private Double freeFloat;

@Column(name = "is_critical")
private Boolean isCritical;             // on critical path

@Column(name = "percent_complete")
private Double percentComplete;         // 0.0–100.0

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "calendar_id")
private ProjectCalendar calendar;

@Enumerated(EnumType.STRING)
@Column(name = "constraint_type")
private TaskConstraintType constraintType; // ASAP, ALAP, MSO, MFO, SNET, SNLT, FNET, FNLT

@Column(name = "constraint_date")
private LocalDateTime constraintDate;

@Enumerated(EnumType.STRING)
@Column(name = "milestone_type")
private MilestoneType milestoneType;    // START, FINISH, null = regular task
```

**Enums to add in `task/enums/`:**
```java
public enum TaskConstraintType {
    ASAP, ALAP, MSO, MFO, SNET, SNLT, FNET, FNLT
}

public enum MilestoneType {
    START, FINISH
}
```

**WBS API — `WbsController`:**
```
POST   /api/v1/projects/{projectId}/wbs          → createNode()
GET    /api/v1/projects/{projectId}/wbs          → getWbsTree()
PATCH  /api/v1/wbs/{nodeId}                      → updateNode()
DELETE /api/v1/wbs/{nodeId}                      → deleteNode()
PATCH  /api/v1/wbs/{nodeId}/reorder              → reorderNode()
```

### 5.2 Task Dependency

**New package:** `org.tornotron.echno_backend.taskDependency`

**Entity: `TaskDependency`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class TaskDependency implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predecessor_id", nullable = false)
    private Task predecessor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "successor_id", nullable = false)
    private Task successor;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_type", nullable = false)
    private DependencyType dependencyType;   // FS, SS, FF, SF

    @Column(name = "lag_days")               // positive = lag, negative = lead
    private Integer lagDays = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

// enums/DependencyType.java
public enum DependencyType {
    FS,   // Finish-to-Start (most common)
    SS,   // Start-to-Start
    FF,   // Finish-to-Finish
    SF    // Start-to-Finish
}
```

**Dependency API — `TaskDependencyController`:**
```
POST   /api/v1/task-dependencies                         → createDependency()
GET    /api/v1/projects/{projectId}/task-dependencies    → getDependencies()
PATCH  /api/v1/task-dependencies/{id}                   → updateDependency()
DELETE /api/v1/task-dependencies/{id}                   → deleteDependency()
```

### 5.3 Project Calendar

**New package:** `org.tornotron.echno_backend.projectCalendar`

**Entities: `ProjectCalendar` and `CalendarException`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ProjectCalendar implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "hours_per_day", nullable = false)
    private Integer hoursPerDay = 8;

    // Comma-separated or use @ElementCollection: "MON,TUE,WED,THU,FRI"
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "calendar_work_days", joinColumns = @JoinColumn(name = "calendar_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "work_day")
    private List<DayOfWeek> workDays;

    @OneToMany(mappedBy = "calendar", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CalendarException> exceptions = new ArrayList<>();

    @Column(name = "is_default")
    private Boolean isDefault = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

@Entity
@Data
@NoArgsConstructor
public class CalendarException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id", nullable = false)
    private ProjectCalendar calendar;

    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false)
    private CalendarExceptionType exceptionType;  // HOLIDAY, EXTRA_WORKDAY

    @Column(name = "hours_override")
    private Integer hoursOverride;
}

public enum CalendarExceptionType {
    HOLIDAY, EXTRA_WORKDAY
}
```

**Calendar API — `ProjectCalendarController`:**
```
POST   /api/v1/calendars                     → createCalendar()
GET    /api/v1/calendars                     → getCalendars()
GET    /api/v1/calendars/{id}                → getCalendar()
PATCH  /api/v1/calendars/{id}                → updateCalendar()
DELETE /api/v1/calendars/{id}                → deleteCalendar()
POST   /api/v1/calendars/{id}/exceptions     → addException()
DELETE /api/v1/calendars/{id}/exceptions/{exId} → removeException()
```

### 5.4 CPM Calculation Engine

**New service:** `org.tornotron.echno_backend.scheduling.CpmCalculationService`

```java
@Service
public class CpmCalculationService {

    /**
     * Performs full CPM forward + backward pass for all tasks in a project.
     * Updates: earlyStart, earlyFinish, lateStart, lateFinish, totalFloat,
     * freeFloat, isCritical on each Task.
     *
     * Algorithm:
     * 1. Build adjacency list from TaskDependency records (respecting FS/SS/FF/SF + lag)
     * 2. Topological sort (Kahn's algorithm — handles up to 50,000 activities)
     * 3. Forward pass: compute Early Start / Early Finish per dependency type
     * 4. Backward pass: compute Late Start / Late Finish
     * 5. Float = LS − ES; isCritical = (totalFloat == 0)
     * 6. Persist updated float/critical values to Task table
     */
    public CpmResultDto calculateCpm(Long projectId) { ... }

    /**
     * Returns only the critical path task IDs without persisting.
     * Used for fast Gantt overlay.
     */
    public List<Long> getCriticalPath(Long projectId) { ... }
}
```

**Scheduling API — `SchedulingController`:**
```
POST   /api/v1/projects/{projectId}/calculate-cpm    → triggerCpm()
GET    /api/v1/projects/{projectId}/critical-path    → getCriticalPath()
GET    /api/v1/projects/{projectId}/schedule-summary → getScheduleSummary()
```

### 5.5 Schedule Baseline

**New package:** `org.tornotron.echno_backend.scheduleBaseline`

**Entities: `ScheduleBaseline` and `BaselineActivity`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ScheduleBaseline implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false)             // e.g. "Approved Baseline Rev 1"
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private Employee createdBy;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @OneToMany(mappedBy = "baseline", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BaselineActivity> activities = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

@Entity
@Data
@NoArgsConstructor
public class BaselineActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "baseline_id", nullable = false)
    private ScheduleBaseline baseline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "percent_complete")
    private Double percentComplete;
}
```

**Baseline API — `ScheduleBaselineController`:**
```
POST   /api/v1/projects/{projectId}/baselines        → saveBaseline()
GET    /api/v1/projects/{projectId}/baselines        → getBaselines()
GET    /api/v1/baselines/{id}                        → getBaseline()
PATCH  /api/v1/baselines/{id}/activate               → setActiveBaseline()
DELETE /api/v1/baselines/{id}                        → deleteBaseline()
```

### 5.6 Enhancements to Existing `Project` Entity

```java
// Add to Project.java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "default_calendar_id")
private ProjectCalendar defaultCalendar;

@Column(name = "data_date")         // "as-of" date for CPM / EVM calculations
private LocalDate dataDate;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "active_baseline_id")
private ScheduleBaseline activeBaseline;
```

---

## 6. Phase 2 — Resource & Cost Management

> **Goal:** Tasks have resources (labour, equipment, material) assigned with quantities
> and costs; resource histogram data is available via API.

### 6.1 Resource Assignment

**New package:** `org.tornotron.echno_backend.resourceAssignment`

Reuses existing `Employee` (labour), `Material` (material), and will reference a new `ProjectResource` concept.

**Entity: `ResourceAssignment`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ResourceAssignment implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // One of the following will be set depending on resource type
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;              // for LABOUR type

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;              // for MATERIAL type

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType;      // LABOUR, EQUIPMENT, MATERIAL, SUBCONTRACT, OTHER

    @Column(name = "unit", nullable = false)   // "h", "day", "m3", "kg", "LS"
    private String unit;

    @Column(name = "standard_rate", precision = 15, scale = 2)
    private BigDecimal standardRate;

    @Column(name = "planned_units", precision = 10, scale = 2)
    private BigDecimal plannedUnits;        // e.g. 1.0 = 100% allocation

    @Column(name = "planned_quantity", precision = 15, scale = 4)
    private BigDecimal plannedQuantity;

    @Column(name = "actual_quantity", precision = 15, scale = 4)
    private BigDecimal actualQuantity;

    @Column(name = "remaining_quantity", precision = 15, scale = 4)
    private BigDecimal remainingQuantity;

    @Column(name = "budgeted_cost", precision = 15, scale = 2)
    private BigDecimal budgetedCost;

    @Column(name = "actual_cost", precision = 15, scale = 2)
    private BigDecimal actualCost;

    @Column(name = "remaining_cost", precision = 15, scale = 2)
    private BigDecimal remainingCost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

// enums/ResourceType.java
public enum ResourceType {
    LABOUR, EQUIPMENT, MATERIAL, SUBCONTRACT, OTHER
}
```

**Resource Assignment API:**
```
POST   /api/v1/resource-assignments                      → createAssignment()
GET    /api/v1/tasks/{taskId}/resource-assignments       → getByTask()
GET    /api/v1/projects/{projectId}/resource-assignments → getByProject()
PATCH  /api/v1/resource-assignments/{id}                → updateAssignment()
DELETE /api/v1/resource-assignments/{id}                → deleteAssignment()
GET    /api/v1/projects/{projectId}/resource-histogram  → getHistogram(from, to, resourceType)
```

### 6.2 Cost Account (Cost Breakdown Structure)

**New package:** `org.tornotron.echno_backend.costAccount`

**Entity: `CostAccount`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class CostAccount implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CostAccount parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    private List<CostAccount> children = new ArrayList<>();

    @Column(name = "code", nullable = false)    // e.g. "03.01.02"
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "budgeted_cost", precision = 15, scale = 2)
    private BigDecimal budgetedCost;

    @Column(name = "actual_cost", precision = 15, scale = 2)
    private BigDecimal actualCost;

    @Column(name = "committed_cost", precision = 15, scale = 2)
    private BigDecimal committedCost;       // from POs / sub-contracts

    @Column(name = "forecast_cost", precision = 15, scale = 2)
    private BigDecimal forecastCost;        // EAC

    @Column(name = "currency", length = 3)
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wbs_node_id")
    private WbsNode wbsNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

**Cost Account API:**
```
POST   /api/v1/projects/{projectId}/cost-accounts    → createCostAccount()
GET    /api/v1/projects/{projectId}/cost-accounts    → getCostAccountTree()
PATCH  /api/v1/cost-accounts/{id}                   → updateCostAccount()
DELETE /api/v1/cost-accounts/{id}                   → deleteCostAccount()
GET    /api/v1/projects/{projectId}/cost-summary     → getCostSummary()
```

### 6.3 PurchaseOrder Integration

Link existing `PurchaseOrder` committed costs into `CostAccount`:
- When a PO is approved, its `totalAmount` feeds into `CostAccount.committedCost`
- When a GRN is received, the amount moves from `committedCost` to `actualCost`
- Implement via Spring application events on `PurchaseOrderStatusChangedEvent`

---

## 7. Phase 3 — Earned Value Management (EVM)

> **Goal:** Real-time EVM metrics available per project per data-date:
> BCWS, BCWP, ACWP, CPI, SPI, EAC, VAC, TCPI.

### 7.1 Progress Update

**New package:** `org.tornotron.echno_backend.progressUpdate`

**Entity: `ProgressUpdate`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ProgressUpdate implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;

    @Column(name = "percent_complete", nullable = false)
    private Double percentComplete;

    @Column(name = "actual_start_date")
    private LocalDateTime actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDateTime actualEndDate;

    @Column(name = "remaining_duration_days")
    private Integer remainingDurationDays;

    @Column(name = "actual_cost", precision = 15, scale = 2)
    private BigDecimal actualCost;

    @Column(name = "notes")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", nullable = false)
    private Employee updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

### 7.2 EVM Snapshot

**New package:** `org.tornotron.echno_backend.evm`

**Entity: `EvmSnapshot`** (persisted result of `EvmCalculationService`)
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class EvmSnapshot implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;

    @Column(name = "budget_at_completion", precision = 15, scale = 2)
    private BigDecimal budgetAtCompletion;  // BAC

    // Planned Value
    @Column(name = "bcws", precision = 15, scale = 2)
    private BigDecimal bcws;                // BCWS / PV

    // Earned Value
    @Column(name = "bcwp", precision = 15, scale = 2)
    private BigDecimal bcwp;                // BCWP / EV

    // Actual Cost
    @Column(name = "acwp", precision = 15, scale = 2)
    private BigDecimal acwp;                // ACWP / AC

    // Variances
    @Column(name = "schedule_variance", precision = 15, scale = 2)
    private BigDecimal scheduleVariance;    // SV = EV − PV

    @Column(name = "cost_variance", precision = 15, scale = 2)
    private BigDecimal costVariance;        // CV = EV − AC

    // Indices
    @Column(name = "spi", precision = 8, scale = 4)
    private BigDecimal spi;                 // SPI = EV / PV

    @Column(name = "cpi", precision = 8, scale = 4)
    private BigDecimal cpi;                 // CPI = EV / AC

    // Forecasts
    @Column(name = "eac", precision = 15, scale = 2)
    private BigDecimal eac;                 // EAC = BAC / CPI

    @Column(name = "etc", precision = 15, scale = 2)
    private BigDecimal etc;                 // ETC = EAC − AC

    @Column(name = "vac", precision = 15, scale = 2)
    private BigDecimal vac;                 // VAC = BAC − EAC

    @Column(name = "tcpi", precision = 8, scale = 4)
    private BigDecimal tcpi;                // TCPI = (BAC − EV) / (BAC − AC)

    @Column(name = "percent_complete")
    private Double percentComplete;

    @Column(name = "percent_spent")
    private Double percentSpent;

    @CreationTimestamp
    @Column(name = "calculated_at", nullable = false, updatable = false)
    private LocalDateTime calculatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

**New service: `EvmCalculationService`**
```java
@Service
public class EvmCalculationService {

    /**
     * Calculates and persists an EvmSnapshot for a project at the given dataDate.
     * Derives time-phased BCWS from baseline + calendar, BCWP from ProgressUpdates,
     * ACWP from ResourceAssignment.actualCost + approved PO/GRN amounts.
     */
    public EvmSnapshot calculateAndSave(Long projectId, LocalDate dataDate) { ... }

    /**
     * Returns the historical EVM snapshots for S-curve rendering.
     */
    public List<EvmSnapshot> getHistory(Long projectId) { ... }
}
```

**EVM API:**
```
POST   /api/v1/projects/{projectId}/progress-updates    → submitProgressUpdate()
GET    /api/v1/projects/{projectId}/progress-updates    → getProgressUpdates()
GET    /api/v1/projects/{projectId}/evm                 → getEvmSnapshot(dataDate)
GET    /api/v1/projects/{projectId}/evm/history         → getEvmHistory()
POST   /api/v1/projects/{projectId}/evm/calculate       → triggerEvmCalculation()
```

---

## 8. Phase 4 — Risk & Issue Intelligence

> **Goal:** Structured risk register with probability/impact matrix,
> integrated with the existing `issue` module for automatic escalation.

### 8.1 Risk Register

**New package:** `org.tornotron.echno_backend.risk`

**Entity: `Risk`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Risk implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "risk_ref", nullable = false)    // user-facing: "R-001"
    private String riskRef;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private RiskCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RiskStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Employee owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "probability")
    private RiskProbability probability;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact")
    private RiskImpact impact;

    @Column(name = "risk_score")
    private Integer riskScore;                      // probability × impact (1–25)

    @Enumerated(EnumType.STRING)
    @Column(name = "residual_probability")
    private RiskProbability residualProbability;

    @Enumerated(EnumType.STRING)
    @Column(name = "residual_impact")
    private RiskImpact residualImpact;

    @Column(name = "residual_score")
    private Integer residualScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_type")
    private RiskResponseType responseType;           // AVOID, MITIGATE, TRANSFER, ACCEPT

    @Column(name = "contingency_plan")
    private String contingencyPlan;

    @OneToMany(mappedBy = "risk", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RiskAction> actions = new ArrayList<>();

    // Links to existing entities
    @ManyToMany
    @JoinTable(name = "risk_related_tasks",
            joinColumns = @JoinColumn(name = "risk_id"),
            inverseJoinColumns = @JoinColumn(name = "task_id"))
    private List<Task> relatedTasks = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "risk_related_issues",
            joinColumns = @JoinColumn(name = "risk_id"),
            inverseJoinColumns = @JoinColumn(name = "issue_id"))
    private List<Issue> relatedIssues = new ArrayList<>();

    @Column(name = "identified_date", nullable = false)
    private LocalDate identifiedDate;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Column(name = "closed_date")
    private LocalDate closedDate;

    @Column(name = "cost_impact", precision = 15, scale = 2)
    private BigDecimal costImpact;

    @Column(name = "schedule_impact_days")
    private Integer scheduleImpactDays;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

**Enums in `risk/enums/`:**
```java
public enum RiskCategory {
    SCHEDULE, COST, SCOPE, QUALITY, SAFETY, TECHNICAL, EXTERNAL, RESOURCE
}

public enum RiskStatus {
    IDENTIFIED, ANALYSED, RESPONSE_PLANNED, MITIGATED, CLOSED, OCCURRED
}

public enum RiskProbability {
    VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH
}

public enum RiskImpact {
    NEGLIGIBLE, MINOR, MODERATE, MAJOR, CATASTROPHIC
}

public enum RiskResponseType {
    AVOID, MITIGATE, TRANSFER, ACCEPT
}
```

**Entity: `RiskAction`**
```java
@Entity
@Data
@NoArgsConstructor
public class RiskAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_id", nullable = false)
    private Risk risk;

    @Column(name = "description", nullable = false)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Employee owner;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RiskActionStatus status;    // OPEN, IN_PROGRESS, COMPLETED
}
```

**Issue → Risk escalation:**
- In `IssueService`, when `IssueStatus` changes to `blocked` or when `IssueType = BLOCKER`,
  emit a `RiskEscalationEvent` containing the `issueId`
- A `RiskEscalationListener` creates a draft `Risk` linked to that `Issue`
- Bidirectional: `Risk.relatedIssues` ↔ existing `Issue` entity

**Risk API:**
```
POST   /api/v1/projects/{projectId}/risks        → createRisk()
GET    /api/v1/projects/{projectId}/risks        → getRisks()
GET    /api/v1/risks/{id}                        → getRisk()
PATCH  /api/v1/risks/{id}                        → updateRisk()
DELETE /api/v1/risks/{id}                        → deleteRisk()
POST   /api/v1/risks/{id}/actions                → addAction()
PATCH  /api/v1/risk-actions/{id}                → updateAction()
GET    /api/v1/projects/{projectId}/risk-matrix  → getRiskMatrix()
```

### 8.2 Lessons Learned

**Entity: `LessonLearned`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class LessonLearned implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private RiskCategory category;

    @Column(name = "situation", nullable = false)
    private String situation;

    @Column(name = "impact")
    private String impact;

    @Column(name = "recommendation")
    private String recommendation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LessonLearnedStatus status;     // DRAFT, REVIEWED, APPROVED

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lesson_learned_tags", joinColumns = @JoinColumn(name = "lesson_id"))
    @Column(name = "tag")
    private List<String> tags;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

---

## 9. Phase 5 — Portfolio & Programme Management

> **Goal:** Executive API endpoints aggregating health, cost, and schedule
> across all projects in a portfolio.

### 9.1 Portfolio

**New package:** `org.tornotron.echno_backend.portfolio`

**Entity: `Portfolio`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Portfolio implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Employee manager;

    @ManyToMany
    @JoinTable(name = "portfolio_projects",
            joinColumns = @JoinColumn(name = "portfolio_id"),
            inverseJoinColumns = @JoinColumn(name = "project_id"))
    private List<Project> projects = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "portfolio_strategic_goals",
            joinColumns = @JoinColumn(name = "portfolio_id"))
    @Column(name = "goal")
    private List<String> strategicGoals;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PortfolioStatus status;     // ACTIVE, CLOSED
}
```

**Entity: `ProjectHealthIndicator`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ProjectHealthIndicator implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "data_date", nullable = false)
    private LocalDate dataDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_status")
    private RagStatus scheduleStatus;   // GREEN, YELLOW, RED (derived from SPI)

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_status")
    private RagStatus costStatus;       // derived from CPI

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_status")
    private RagStatus scopeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_status")
    private RagStatus riskStatus;       // derived from open high/very-high risks

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status")
    private RagStatus overallStatus;

    @Column(name = "comment")
    private String comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by")
    private Employee reportedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}

public enum RagStatus { GREEN, YELLOW, RED }
```

**Portfolio API:**
```
POST   /api/v1/portfolios                              → createPortfolio()
GET    /api/v1/portfolios                              → getPortfolios()
GET    /api/v1/portfolios/{id}                         → getPortfolio()
PATCH  /api/v1/portfolios/{id}                         → updatePortfolio()
GET    /api/v1/portfolios/{id}/health                  → getPortfolioHealth()
GET    /api/v1/portfolios/{id}/resource-demand         → getResourceDemand(from, to)
GET    /api/v1/portfolios/{id}/cost-summary            → getCostSummary()
POST   /api/v1/projects/{projectId}/health-indicators  → submitHealthReport()
GET    /api/v1/projects/{projectId}/health-indicators  → getHealthHistory()
```

---

## 10. Phase 6 — Reporting, Analytics & BI

> **Goal:** Configurable, exportable reports generated server-side.
> Frontend calls API; backend handles aggregation and PDF/Excel rendering.

### 10.1 Report Types

| Report | Description | Key Data Sources |
|--------|-------------|-----------------|
| Schedule Status Report | Float analysis + late tasks | `Task`, `TaskDependency`, `CpmCalculationService` |
| Cost Status Report | Budget vs actual, EVM metrics | `CostAccount`, `EvmSnapshot` |
| Resource Loading Report | Histogram by resource/period | `ResourceAssignment` |
| S-Curve Report | BCWS / BCWP / ACWP over time | `EvmSnapshot` history |
| Critical Path Report | Critical activities list | `Task.isCritical` |
| Lookahead Schedule | 3/6-week activities from today | `Task.plannedStartDate` range |
| Risk Register Report | All risks with status | `Risk`, `RiskAction` |
| Inspection Summary | Quality metrics | Existing inspection entities |
| Portfolio Dashboard | Cross-project health | `ProjectHealthIndicator`, `EvmSnapshot` |
| Change Log | All approved changes | `ChangeRequest` |
| Procurement Summary | PO status, commitment | `PurchaseOrder`, `GoodsReceivedNote` |

### 10.2 Report Architecture

**New package:** `org.tornotron.echno_backend.reporting`

```
reporting/
  ReportController.java              ← REST endpoints
  ReportGenerationService.java       ← Orchestrates data + rendering
  PdfReportRenderer.java             ← Extends pdfGeneration module
  ExcelReportRenderer.java           ← Uses Apache POI
  data/
    ScheduleReportDataService.java
    CostReportDataService.java
    EvmReportDataService.java
    RiskReportDataService.java
  dto/
    ReportRequestDto.java
    ReportResponseDto.java
  enums/
    ReportType.java
    ReportFormat.java                 // PDF, EXCEL, JSON
```

**Reporting API:**
```
POST   /api/v1/reports/generate      → generateReport(ReportRequestDto)
GET    /api/v1/reports/{id}/download → downloadReport()
GET    /api/v1/projects/{projectId}/reports → listProjectReports()
```

---

## 11. Phase 7 — Document Control & Change Management

> **Goal:** Backend support for RFIs, transmittals, submittals, and change requests.

### 11.1 Change Request

**New package:** `org.tornotron.echno_backend.changeRequest`

**Entity: `ChangeRequest`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class ChangeRequest implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "change_no", nullable = false, unique = true) // e.g. "CR-0042"
    private String changeNo;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = false)
    private Employee requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;      // SCOPE, SCHEDULE, COST, QUALITY

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChangeRequestStatus status; // DRAFT, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED

    @Column(name = "schedule_impact_days")
    private Integer scheduleImpactDays;

    @Column(name = "cost_impact", precision = 15, scale = 2)
    private BigDecimal costImpact;

    @ManyToMany
    @JoinTable(name = "change_request_tasks",
            joinColumns = @JoinColumn(name = "change_request_id"),
            inverseJoinColumns = @JoinColumn(name = "task_id"))
    private List<Task> affectedTasks = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private Employee approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

### 11.2 RFI (Request for Information)

**New package:** `org.tornotron.echno_backend.rfi`

**Entity: `Rfi`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Rfi implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "rfi_no", nullable = false)   // "RFI-0012"
    private String rfiNo;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "answer")
    private String answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by", nullable = false)
    private Employee submittedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private Employee assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RfiStatus status;           // OPEN, ANSWERED, CLOSED

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private RfiPriority priority;       // LOW, MEDIUM, HIGH

    @Column(name = "date_required")
    private LocalDate dateRequired;

    @Column(name = "has_schedule_impact")
    private Boolean hasScheduleImpact;

    @Column(name = "has_cost_impact")
    private Boolean hasCostImpact;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

**Change & RFI API:**
```
POST   /api/v1/projects/{projectId}/change-requests    → createChangeRequest()
GET    /api/v1/projects/{projectId}/change-requests    → getChangeRequests()
PATCH  /api/v1/change-requests/{id}                   → updateChangeRequest()
PATCH  /api/v1/change-requests/{id}/approve           → approveChangeRequest()
PATCH  /api/v1/change-requests/{id}/reject            → rejectChangeRequest()

POST   /api/v1/projects/{projectId}/rfis              → createRfi()
GET    /api/v1/projects/{projectId}/rfis              → getRfis()
PATCH  /api/v1/rfis/{id}                              → updateRfi()
POST   /api/v1/rfis/{id}/answer                       → answerRfi()
```

---

## 12. Phase 8 — Mobile & Field Operations API

> **Goal:** Lightweight, bandwidth-efficient API endpoints for field mobile apps.
> Support offline-first patterns via sync tokens and delta responses.

### 12.1 Daily Site Report (DSR)

**New package:** `org.tornotron.echno_backend.dailySiteReport`

**Entity: `DailySiteReport`**
```java
@Entity
@Data
@NoArgsConstructor
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class DailySiteReport implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "weather")
    private String weather;

    @Column(name = "temperature_celsius")
    private Integer temperatureCelsius;

    @Column(name = "manpower_count")
    private Integer manpowerCount;

    @Column(name = "equipment_summary")
    private String equipmentSummary;

    @Column(name = "work_done")
    private String workDone;

    @Column(name = "delays")
    private String delays;

    @Column(name = "safety_incidents")
    private String safetyIncidents;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by", nullable = false)
    private Employee submittedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "dailySiteReport")
    private List<Attachment> attachments = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;
}
```

### 12.2 Mobile-Optimised API Endpoints

```
POST   /api/v1/projects/{projectId}/daily-site-reports    → submitDsr()
GET    /api/v1/projects/{projectId}/daily-site-reports    → getDsrs(from, to)

GET    /api/v1/projects/{projectId}/tasks/lookahead       → getLookahead(weeks=3|6)
POST   /api/v1/tasks/{taskId}/quick-progress              → submitQuickProgress(percent)

GET    /api/v1/sync/delta?since={timestamp}&projectId={}  → getDeltaPayload()
POST   /api/v1/sync/batch                                 → batchSyncOfflineData()
```

### 12.3 Push Notification Integration

Extend `NotificationService` (Phase 1 Notification System) with:
- FCM (Firebase Cloud Messaging) for Android/iOS push
- Webhook outbound events for third-party automation

---

## 13. Data Model Summary

### New Entities by Phase

| Entity | Package | Phase |
|--------|---------|-------|
| `WbsNode` | `wbs` | 1 |
| `TaskDependency` | `taskDependency` | 1 |
| `ProjectCalendar` | `projectCalendar` | 1 |
| `CalendarException` | `projectCalendar` | 1 |
| `ScheduleBaseline` | `scheduleBaseline` | 1 |
| `BaselineActivity` | `scheduleBaseline` | 1 |
| `ScheduleChangeLog` | `scheduling` | 1 |
| `ResourceAssignment` | `resourceAssignment` | 2 |
| `CostAccount` | `costAccount` | 2 |
| `ProgressUpdate` | `progressUpdate` | 3 |
| `EvmSnapshot` | `evm` | 3 |
| `Risk` | `risk` | 4 |
| `RiskAction` | `risk` | 4 |
| `LessonLearned` | `risk` | 4 |
| `Portfolio` | `portfolio` | 5 |
| `ProjectHealthIndicator` | `portfolio` | 5 |
| `ChangeRequest` | `changeRequest` | 7 |
| `Rfi` | `rfi` | 7 |
| `DailySiteReport` | `dailySiteReport` | 8 |

### Enhancements to Existing Entities

| Entity | New Fields | Phase |
|--------|-----------|-------|
| `Task` | `wbsNode`, `activityId`, `plannedStartDate`, `plannedEndDate`, `actualStartDate`, `actualEndDate`, `baselineStartDate`, `baselineEndDate`, `durationDays`, `remainingDurationDays`, `totalFloat`, `freeFloat`, `isCritical`, `percentComplete`, `calendar`, `constraintType`, `constraintDate`, `milestoneType` | 1 |
| `Project` | `defaultCalendar`, `dataDate`, `activeBaseline` | 1 |
| `Material` | `standardRate`, `overtimeRate`, `unit` (cost fields) | 2 |
| `PurchaseOrder` | `costAccount` (FK to `CostAccount`) | 2 |

---

## 14. Package Structure

Full backend package tree after all phases:

```
org.tornotron.echno_backend/
  ├── attendance/              ✅ Exists
  ├── auth/                    ✅ Exists
  ├── billing/                 ✅ Exists
  ├── category/                ✅ Exists
  ├── changeRequest/           ← Phase 7
  ├── common/                  ✅ Exists
  │   ├── entity/
  │   └── multitenancy/
  ├── costAccount/             ← Phase 2
  ├── dailySiteReport/         ← Phase 8
  ├── employee/                ✅ Exists
  ├── evm/                     ← Phase 3
  ├── goodsReceivedNote/       ✅ Exists
  ├── grnItem/                 ✅ Exists
  ├── indentItem/              ✅ Exists
  ├── intend/                  ✅ Exists
  ├── inventoryTransaction/    ✅ Exists
  ├── issue/                   ✅ Exists
  ├── IssueComment/            ✅ Exists
  ├── keycloak/                ✅ Exists
  ├── leave/                   ✅ Exists
  ├── material/                ✅ Exists
  ├── materialConsumption/     ✅ Exists
  ├── organization/            ✅ Exists
  ├── payable/                 ✅ Exists
  ├── pdfGeneration/           ✅ Exists
  ├── portfolio/               ← Phase 5
  ├── progressUpdate/          ← Phase 3
  ├── project/                 ✅ Exists
  ├── projectCalendar/         ← Phase 1
  ├── projectInviteCode/       ✅ Exists
  ├── purchaseOrder/           ✅ Exists
  ├── purchaseOrderItem/       ✅ Exists
  ├── reporting/               ← Phase 6
  ├── resourceAssignment/      ← Phase 2
  ├── rfi/                     ← Phase 7
  ├── risk/                    ← Phase 4
  ├── scheduleBaseline/        ← Phase 1
  ├── scheduling/              ← Phase 1 (CPM engine)
  ├── siteTransfer/            ✅ Exists
  ├── siteTransferItem/        ✅ Exists
  ├── storageLocation/         ✅ Exists
  ├── task/                    ✅ Exists (enhanced)
  ├── taskDependency/          ← Phase 1
  ├── user/                    ✅ Exists
  ├── vendor/                  ✅ Exists
  └── wbs/                     ← Phase 1
```

---

## 15. Implementation Priorities

### Immediate Quick Wins (1–4 weeks, no new entities)

1. **Task `percentComplete` field** — Add column to `Task` and expose in `TaskPatchDto` and `TaskDto`
2. **Task date range queries** — Add `findByProjectIdAndPlannedStartDateBetween` to `TaskRepository` for lookahead
3. **Project `dataDate` field** — Add to `Project` entity and `ProjectPatchDto`
4. **Task `activityId` field** — Add user-facing code column to `Task` for Gantt labelling
5. **Issue severity enum** — Add `BLOCKER` to `IssueType` to enable risk escalation hook

### Phase 1 Milestones (Target: 3 months)

- [ ] `WbsNode` entity, repository, service, and controller
- [ ] `TaskDependency` entity with cycle detection in `TaskDependencyService`
- [ ] `ProjectCalendar` + `CalendarException` entities and API
- [ ] `CpmCalculationService` — topological sort, forward/backward pass, float persistence
- [ ] `SchedulingController` — `POST /calculate-cpm` and `GET /critical-path`
- [ ] `ScheduleBaseline` entity — snapshot saves all current `Task` dates
- [ ] `ScheduleChangeLog` — append-only audit log for date/dependency mutations
- [ ] Liquibase / Flyway migration scripts for all new columns and tables
- [ ] Unit tests for `CpmCalculationService` (edge cases: parallel paths, cycles, lag/lead)

### Technical Decisions

| Decision | Recommendation | Rationale |
|----------|----------------|-----------|
| CPM algorithm | Kahn's topological sort + forward/backward pass | Standard CPM; O(V+E); handles 50k+ nodes |
| EVM storage | Persist `EvmSnapshot` per recalculation | Enables S-curve history without re-deriving |
| Database migrations | Flyway or Liquibase | Version-controlled schema changes |
| Redis caching | Cache CPM results keyed by `projectId + dataDate` | Avoid recalculation on every Gantt open |
| WebSocket | Spring WebSocket + STOMP | For Gantt drag-and-drop real-time reconciliation |
| Excel export | Apache POI | Already a common Java dependency |
| XER parser | Custom implementation in `reporting` package | No Java library matches P6 XER spec fully |
| Notification events | Spring `ApplicationEventPublisher` → async listeners | Decoupled; enables future Kafka migration |

### Testing Strategy

- `CpmCalculationService` — unit tests with deterministic graph fixtures (AON networks)
- `EvmCalculationService` — unit tests comparing against known EVM formulas
- Integration tests — use Testcontainers PostgreSQL, real entities, real calculations
- No mocking of the database — follow existing project pattern

---

*Document version: 1.0 — Created: March 2026*
*Translated from PLATFORM_SCALING_PLAN.md to backend implementation specifics*
*Next review: After Phase 1 completion*
