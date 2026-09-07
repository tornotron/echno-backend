# Multi-Tenancy via Hibernate @Filter — Implementation Guide

Data-level organization isolation using Hibernate filters that append `WHERE organization_id = :organizationId` to a query whose **root** is a tenant-scoped entity, backed by a fail-closed check at the load boundary for the primary-key loads the filter never sees.

Read [Explicit Joins](#explicit-joins-what-the-filter-does-not-reach) before assuming a query is covered. The filter reaches a query root and a filtered collection; an entity joined explicitly in HQL is scoped by neither mechanism, and that failure is silent.

---

## Table of Contents

1. [Overview](#overview)
2. [How It Works](#how-it-works)
3. [Architecture](#architecture)
4. [Infrastructure Components](#infrastructure-components)
5. [Request Flow](#request-flow)
6. [Using the X-Organization-Id Header](#using-the-x-organization-id-header)
7. [Entity Annotations](#entity-annotations)
8. [Setting Organization on New Entities](#setting-organization-on-new-entities)
9. [Bypassing the Filter](#bypassing-the-filter)
10. [Exempt Endpoints](#exempt-endpoints)
11. [Adding a New Tenant-Scoped Entity](#adding-a-new-tenant-scoped-entity)
12. [Adding a New Service](#adding-a-new-service)
13. [Native SQL Queries](#native-sql-queries)
14. [Database Migrations](#database-migrations)
15. [Entities Covered](#entities-covered)
16. [Edge Cases](#edge-cases)
17. [Caller-Supplied Organization Ids: Already Checked](#caller-supplied-organization-ids-already-checked)
18. [Explicit Joins: What the Filter Does Not Reach](#explicit-joins-what-the-filter-does-not-reach)
19. [Troubleshooting](#troubleshooting)

---

## Overview

The application supports multiple organizations (tenants) sharing a single database. Previously, data isolation was only enforced at the API layer via `@PreAuthorize`. This implementation adds a **data-level safety net** — every database query on a tenant-scoped entity automatically filters by the active organization.

**What this means in practice:**
- A user in Organization A can **never** see data belonging to Organization B
- Even if a developer forgets a WHERE clause, the Hibernate filter catches it
- Users belong to multiple orgs — the active org is set per-request via a header

---

## How It Works

```
Client Request                   Spring Security              TenantFilter
     |                                |                            |
     |  X-Organization-Id: 5          |                            |
     |------------------------------->|  Authenticate JWT          |
     |                                |--------------------------->|
     |                                |                            | Validate user has
     |                                |                            | ORG_MEMBER_5 authority
     |                                |                            |
     |                                |                            | Set TenantContext
     |                                |                            | .setCurrentOrgId(5)
     |                                |                            |
     |                                                             |
     |                         Controller / Service                |
     |                                |                            |
     |                         repository.findAll()                |
     |                                |                            |
     |                         HibernateFilterConfig (AOP)         |
     |                                |                            |
     |                         Enables orgFilter with orgId=5      |
     |                                |                            |
     |                         SQL: SELECT * FROM entity            |
     |                              WHERE ... AND organization_id=5|
     |                                |                            |
     |<-------------------------------|                            |
     |         Only org 5 data                                     |
```

---

## Architecture

All multi-tenancy infrastructure lives in one package:

```
src/main/java/org/tornotron/echno_backend/
    package-info.java               -- Global @FilterDef declaration
    common/multitenancy/
        TenantContext.java              -- ThreadLocal holder for current org ID
        TenantFilter.java               -- HTTP filter that reads the header
        HibernateFilterConfig.java      -- AOP aspect that enables Hibernate filter
        TenantEntityHelper.java         -- Helper to resolve Organization entity
        TenantScopedEntity.java         -- Marker interface for filtered entities
        BypassTenantFilter.java         -- Annotation for cross-org methods
        TenantFilterBypassAspect.java   -- AOP aspect for the bypass annotation
```

**Key architectural decision:** The `@FilterDef` annotation is defined **once** in `package-info.java` at the root package level. This is a Hibernate requirement — having duplicate `@FilterDef` annotations will cause the application to fail at startup with: `Multiple '@FilterDef' annotations define a filter named 'orgFilter'`.

---

## Infrastructure Components

### 1. TenantContext

A `ThreadLocal` holder that stores the active organization ID for the current request.

```java
// Set the org (done automatically by TenantFilter)
TenantContext.setCurrentOrgId(5L);

// Get the current org ID
Long orgId = TenantContext.getCurrentOrgId();  // returns 5

// Check if filter is bypassed (admin mode)
boolean bypassed = TenantContext.isBypassed();

// Cleared automatically after each request in TenantFilter's finally block
TenantContext.clear();
```

**Important:** You should never need to call `setCurrentOrgId()` or `clear()` manually — the `TenantFilter` handles this automatically for every HTTP request.

### 2. TenantFilter

A `OncePerRequestFilter` that runs after Spring Security authentication. It:

1. Reads the `X-Organization-Id` header from the request
2. Validates the user has `ORG_MEMBER_{orgId}` authority (from Keycloak groups)
3. Sets `TenantContext.setCurrentOrgId(orgId)`
4. Clears the context in the `finally` block after the request completes

**Tenant Resolution Logic:**

| Scenario | Behavior |
|----------|----------|
| Header present + user is member | Sets tenant context to that org |
| Header present + user is NOT member | Returns `403 Forbidden` |
| Header invalid (not a number) | Returns `400 Bad Request` |
| No header + user has 1 org | Auto-infers that org |
| No header + user has multiple orgs | Returns `400 Bad Request` with message |
| No header + user has 0 orgs | No tenant context set (allowed for exempt paths) |
| User has `organization:admin` authority | Bypass mode — no filtering applied |

### 3. HibernateFilterConfig

An AOP `@Aspect` that intercepts every `JpaRepository` method call. Before the repository method executes, it:

1. Checks if `TenantContext` has an org ID and is not bypassed
2. Unwraps the current Hibernate `Session` from the `EntityManager`
3. Enables the `orgFilter` with the current org ID

This is what makes every JPA query automatically include `WHERE organization_id = :orgId`.

```java
@Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
public Object enableOrgFilter(ProceedingJoinPoint joinPoint) throws Throwable {
    Long orgId = TenantContext.getCurrentOrgId();
    if (orgId != null && !TenantContext.isBypassed()) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("orgFilter").setParameter("organizationId", orgId);
    }
    return joinPoint.proceed();
}
```

### 4. TenantEntityHelper

A service that resolves the current `Organization` entity from the `TenantContext`. Used in service classes when creating new entities that need an org reference.

```java
@Service
public class TenantEntityHelper {
    public Organization resolveCurrentOrganization() {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            throw new TenantIdMissingException("No organization context set");
        }
        return organizationRepository.getReferenceById(orgId);
    }
}
```

Uses `getReferenceById()` (a Hibernate proxy) to avoid an extra SELECT — the org ID is all we need for the FK.

### 5. TenantScopedEntity

A marker interface that every tenant-scoped entity implements:

```java
public interface TenantScopedEntity {
    Organization getOrganization();
    void setOrganization(Organization organization);
}
```

This allows type-safe helper methods that work with any tenant-scoped entity.

### 6. Filter Definition (package-info.java)

The Hibernate filter definition is centralized in one location:

```java
// src/main/java/org/tornotron/echno_backend/package-info.java
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
package org.tornotron.echno_backend;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```

**Why package-info.java?**
- Hibernate requires `@FilterDef` to be declared exactly **once** per filter name
- Multiple declarations cause a startup error: `Multiple '@FilterDef' annotations define a filter named 'orgFilter'`
- `package-info.java` is the standard location for package-level annotations
- All entities can reference the filter by name using `@Filter(name = "orgFilter", ...)`

---

## Request Flow

Here is the complete lifecycle of a request:

1. **Client** sends `GET /api/v1/tasks` with header `X-Organization-Id: 5` and a JWT token
2. **Spring Security** authenticates the JWT. User's authorities include `ORG_MEMBER_5`, `ORG_MEMBER_8`
3. **TenantFilter** reads `X-Organization-Id: 5`, confirms user has `ORG_MEMBER_5` authority, calls `TenantContext.setCurrentOrgId(5)`
4. **Controller** calls `taskService.getAllTasks()`
5. **Service** calls `taskRepository.findAll()`
6. **HibernateFilterConfig** AOP intercepts the repository call, enables `orgFilter` with `organizationId=5`
7. **Hibernate** generates SQL: `SELECT * FROM task WHERE ... AND organization_id = 5`
8. **Response** contains only tasks belonging to organization 5
9. **TenantFilter** `finally` block calls `TenantContext.clear()`

---

## Using the X-Organization-Id Header

### For Frontend Developers

Every API request (except [exempt endpoints](#exempt-endpoints)) must include the organization header:

```
GET /api/v1/tasks
Authorization: Bearer <jwt-token>
X-Organization-Id: 5
```

**When can you skip the header?**
- If the user belongs to only **one** organization, the backend auto-infers it
- If the endpoint is [exempt](#exempt-endpoints) (auth, billing, etc.)

**Error responses:**

```json
// Missing header when user has multiple orgs
{ "error": "X-Organization-Id header required when user belongs to multiple organizations" }

// User is not a member of the requested org
{ "error": "You are not a member of organization 5" }

// Invalid header value
{ "error": "Invalid X-Organization-Id header value" }
```

### CORS

The `X-Organization-Id` header is included in the CORS `allowedHeaders` configuration in `SecurityConfig.java`.

---

## Entity Annotations

### Global Filter Definition (package-info.java)

The filter is defined **once** in the root package:

```java
// src/main/java/org/tornotron/echno_backend/package-info.java
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
package org.tornotron.echno_backend;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
```

**Important:** `@FilterDef` must only be declared once in your entire application. If you declare it on multiple entities, you'll get: `Multiple '@FilterDef' annotations define a filter named 'orgFilter'`.

### Entity-Level Filter Application

Every tenant-scoped entity applies the filter using `@Filter` (but does NOT redeclare `@FilterDef`):

```java
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;

@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class Task implements TenantScopedEntity {

    // ... other fields ...

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Override
    public Organization getOrganization() {
        return organization;
    }

    @Override
    public void setOrganization(Organization organization) {
        this.organization = organization;
    }
}
```

**What each annotation/interface does:**

| Component | Location | Purpose |
|-----------|----------|---------|
| `@FilterDef` | `package-info.java` (once) | Declares a named filter with a parameter `organizationId` of type `Long` |
| `@Filter` | Each entity class | Applies the filter — tells Hibernate to add `AND organization_id = :organizationId` to every query for this entity |
| `TenantScopedEntity` | Each entity class | Marker interface ensuring `getOrganization()` / `setOrganization()` exist |
| `@ManyToOne Organization` | Each entity class | The FK column linking this entity to its organization |

---

## Setting Organization on New Entities

When creating a new entity, you **must** set the organization. There are two patterns:

### Pattern A: Derive from Parent Entity

Use this when the new entity has a parent that already has an organization (e.g., Task belongs to Project).

```java
// TaskService.java
public void addTask(TaskCreationDto dto) {
    Project project = projectRepository.findById(dto.getProjectId()).orElseThrow(...);

    Task task = new Task();
    task.setProject(project);
    task.setOrganization(project.getOrganization());  // derive from parent

    taskRepository.save(task);
}
```

**Where this pattern is used:**

| Service | Entity Created | Org Derived From |
|---------|---------------|-----------------|
| TaskService | Task | `project.getOrganization()` |
| IssueService | Issue | `task.getOrganization()` |
| IssueCommentService | IssueComment | `issue.getOrganization()` |
| TeamMemberService | TeamMember | `project.getOrganization()` |
| LeaveApprovalService | LeaveApproval | `leaveRequest.getOrganization()` |
| LeaveBalanceService | LeaveBalance | `employee.getOrganization()` |
| LeaveBalanceService | LeaveTransaction | `employee.getOrganization()` |
| NotificationService | Notification | `recipient.getOrganization()` |
| AttendanceService | Attendance | `employee.getOrganization()` |
| InventoryEventListener | InventoryTransaction | `grn/consumption/transfer.getOrganization()` |

### Pattern B: Use TenantEntityHelper

Use this when the entity has no parent with an organization reference (standalone entities like Material, Vendor).

```java
// MaterialService.java
private final TenantEntityHelper tenantEntityHelper;

public void createMaterial(MaterialCreationDto dto) {
    Material material = new Material();
    material.setMaterialName(dto.getMaterialName());
    material.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

    materialRepository.save(material);
}
```

**Where this pattern is used:**

| Service | Entity Created |
|---------|---------------|
| MaterialService | Material |
| VendorService | Vendor |
| IntendService | Intend |
| IndentItemService | IndentItem |
| PurchaseOrderService | PurchaseOrder + PurchaseOrderItem |
| GoodsReceivedNoteService | GoodsReceivedNote + GrnItem |
| MaterialConsumptionService | MaterialConsumption |
| PayableService | Payable |
| SiteTransferService | SiteTransfer + SiteTransferItem |
| CategoryService | Category |

---

## Bypassing the Filter

For admin operations or batch jobs that need to access data across all organizations, use the `@BypassTenantFilter` annotation:

```java
@Service
public class AdminReportService {

    @BypassTenantFilter
    public List<Task> getAllTasksAcrossOrganizations() {
        // This query will NOT have the organization_id filter applied
        return taskRepository.findAll();
    }
}
```

**How it works:**
1. The `TenantFilterBypassAspect` intercepts the method call
2. Sets `TenantContext.setBypass(true)`
3. Executes the method (Hibernate filter is not enabled because `isBypassed()` returns true)
4. Restores the previous bypass state in the `finally` block

**Important:** The bypass is scoped to the annotated method only. After the method returns, the bypass state is restored.

### Manual Bypass (for scheduled jobs)

For background jobs that run outside an HTTP request (no `TenantFilter` runs):

```java
@Scheduled(cron = "0 0 1 * * *")
public void nightlyBatchJob() {
    // Option 1: Process all orgs (no tenant context = no filter active)
    // The filter only activates when TenantContext has an org ID
    processAllData();

    // Option 2: Process per-org
    for (Long orgId : getAllOrgIds()) {
        TenantContext.setCurrentOrgId(orgId);
        try {
            processOrgData(orgId);
        } finally {
            TenantContext.clear();
        }
    }
}
```

---

## Exempt Endpoints

These paths skip the `TenantFilter` entirely (no tenant context needed):

| Path Pattern | Reason |
|-------------|--------|
| `/actuator/**` | Health checks, metrics |
| `/api/{version}/auth/register` | User registration (no org yet) |
| `/api/{version}/organizations/**` | Org creation and listing |
| `/api/{version}/users/profile` | User profile (cross-org) |
| `/api/{version}/billing/**` | Billing is platform-level |

To add a new exempt path, update the `shouldNotFilter()` method in `TenantFilter.java`:

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/actuator")
            || path.equals("/api/" + backendVersion + "/auth/register")
            || path.startsWith("/api/" + backendVersion + "/organizations")
            || path.startsWith("/api/" + backendVersion + "/users/profile")
            || path.startsWith("/api/" + backendVersion + "/billing")
            || path.startsWith("/api/" + backendVersion + "/your-new-exempt-path");  // add here
}
```

---

## Adding a New Tenant-Scoped Entity

Follow these steps when you create a new entity that should be organization-scoped:

### Step 1: Add the @Filter annotation and organization field to the entity

```java
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MyNewEntity implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ... your existing fields ...

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Override
    public Organization getOrganization() {
        return organization;
    }

    @Override
    public void setOrganization(Organization organization) {
        this.organization = organization;
    }
}
```

**Important Notes:**
- **DO NOT** add `@FilterDef` to your entity — it's already defined in `package-info.java`
- Only import `org.hibernate.annotations.Filter` (not `FilterDef` or `ParamDef`)
- Implement `TenantScopedEntity` interface
- Add the `@ManyToOne` relationship to `Organization`

### Step 2: Create a Liquibase migration

Add a migration to create the `organization_id` column (if it doesn't already exist in your table creation migration):

```xml
<changeSet id="xxx-add-org-id-to-my-entity" author="your-name">
    <addColumn tableName="my_new_entity">
        <column name="organization_id" type="BIGINT"/>
    </addColumn>
    <addForeignKeyConstraint
        baseTableName="my_new_entity"
        baseColumnNames="organization_id"
        constraintName="fk_my_entity_organization"
        referencedTableName="organization"
        referencedColumnNames="id"/>
    <createIndex tableName="my_new_entity" indexName="idx_my_entity_org_id">
        <column name="organization_id"/>
    </createIndex>
</changeSet>
```

### Step 3: Set organization in the service layer

In your service's create method:

```java
// If entity has a parent with an org:
myEntity.setOrganization(parentEntity.getOrganization());

// If entity is standalone:
myEntity.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
```

---

## Adding a New Service

When creating a new service that handles tenant-scoped entities:

### If using TenantEntityHelper (standalone entities):

```java
@Service
public class MyNewService {

    private final MyNewRepository myNewRepository;
    private final TenantEntityHelper tenantEntityHelper;

    public MyNewService(MyNewRepository myNewRepository,
                        TenantEntityHelper tenantEntityHelper) {
        this.myNewRepository = myNewRepository;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    @Transactional
    public void create(MyNewDto dto) {
        MyNewEntity entity = new MyNewEntity();
        // ... set fields ...
        entity.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        myNewRepository.save(entity);
    }
}
```

### If deriving org from parent:

```java
@Transactional
public void create(MyNewDto dto) {
    ParentEntity parent = parentRepository.findById(dto.getParentId()).orElseThrow(...);

    MyNewEntity entity = new MyNewEntity();
    entity.setParent(parent);
    entity.setOrganization(parent.getOrganization());
    myNewRepository.save(entity);
}
```

---

## Native SQL Queries

Hibernate `@Filter` only works with JPQL/HQL queries. **Native SQL queries bypass the filter.** You must manually add the organization condition.

### Example (AttendanceRepository):

```java
@Query(value = """
    SELECT * FROM attendance
    WHERE employee_id = :employeeId
    AND DATE(timestamp) = CURRENT_DATE
    AND (:organizationId IS NULL OR organization_id = :organizationId)
    ORDER BY timestamp DESC
    LIMIT 1
    """, nativeQuery = true)
Optional<Attendance> findLatestRecordForEmployee(
    @Param("employeeId") Long employeeId,
    @Param("organizationId") Long organizationId);
```

In the service, pass `TenantContext.getCurrentOrgId()`:

```java
attendanceRepository.findLatestRecordForEmployee(
    employee.getId(),
    TenantContext.getCurrentOrgId()
);
```

**Pattern:** Use `(:organizationId IS NULL OR organization_id = :organizationId)` to safely handle cases where no tenant context is set.

---

## Database Migrations

The v1.3 migrations added `organization_id` columns to all tenant-scoped entities:

```
src/main/resources/db/changelog/v1.3/
    056-add-org-id-to-task-related-entities.xml     -- task, issue, issue_comments, team_member, category
    057-add-org-id-to-leave-related-entities.xml     -- leave_approval, leave_balance, leave_transaction, notification, attendance
    058-add-org-id-to-procurement-entities.xml       -- 13 procurement tables
    059-backfill-org-id.xml                          -- backfills org from parent relationships
    060-tag-v1.3.xml                                 -- tags the release
```

### Backfill Strategy

**Group A entities** (have a parent with org): Backfilled automatically via SQL UPDATE joins:

```sql
-- Example: task gets org from project
UPDATE task t SET organization_id = p.organization_id
FROM project p WHERE t.project_id = p.id AND t.organization_id IS NULL;
```

**Group B entities** (procurement — no parent path to org): Existing data stays `NULL`. Requires manual admin assignment.

**Rows with NULL organization_id:** The filter condition `organization_id = :organizationId` naturally excludes NULL rows. This means un-backfilled rows are invisible to org-scoped queries — they won't leak but also won't show until assigned.

---

## Entities Covered

### Entities with org filter (31 total):

**Already had organization_id (8):**
Employee, Project, LeavePolicy, LeaveRequest, LeaveCalendar, LeaveRequestSequence, ProjectInviteCode, Attachment

**Added organization_id + backfill path (10 — Group A):**
Task, Issue, IssueComment, TeamMember, LeaveApproval, LeaveBalance, LeaveTransaction, Notification, Attendance, Category

**Added organization_id, no backfill (13 — Group B, procurement):**
Material, Vendor, Intend, IndentItem, PurchaseOrder, PurchaseOrderItem, GoodsReceivedNote, GrnItem, InventoryTransaction, MaterialConsumption, Payable, SiteTransfer, SiteTransferItem

### Entities WITHOUT org filter (platform-level):
User, Organization, Plan, Feature, PlanFeature, Subscription, SubscriptionItem, UsageRecord

---

## Edge Cases

| Scenario | What Happens |
|----------|-------------|
| **User belongs to 1 org, no header sent** | Org auto-inferred from `ORG_MEMBER_` authority |
| **User belongs to multiple orgs, no header** | `400 Bad Request` — header required |
| **User sends header for org they don't belong to** | `403 Forbidden` |
| **Global admin (`organization:admin` authority)** | Filter bypassed — sees all data |
| **Scheduled/async job (no HTTP request)** | No `TenantFilter` runs. Use `@BypassTenantFilter` or manually set `TenantContext` |
| **Entity with NULL organization_id** | Invisible to org-scoped queries (excluded by filter). Must be assigned an org to appear |
| **Native SQL query** | Filter does NOT apply automatically. Add `AND organization_id = :orgId` manually |
| **New entity without `@Filter` annotation** | Not filtered at all — visible to all orgs. Only add filter to org-scoped entities |
| **Duplicate `@FilterDef` on entity** | **Application fails to start** with error: `Multiple '@FilterDef' annotations define a filter named 'orgFilter'`. Remove from entity — it should only exist in `package-info.java` |

---

## Caller-Supplied Organization Ids: Already Checked

A handler that takes an organization id from the caller and looks something up with it is the
shape worth auditing, because it can mean the caller chose which tenant's data was read. Most
occurrences are not that. They are a redundant parameter beside a `TenantContext` read, or a
parameter the entity's `orgFilter` renders inert.

Telling the two apart is slow, and it is the same work every time. This section records the
answers already arrived at, so a later pass can skip them and spend its time on what is new.
Sources: issues #687, #691, #696, #697, #698, #699, #700, and the commits that closed them.

### How to tell them apart, in order

1. **Is the entity a `TenantScopedEntity`?** If it carries `orgFilter`, a foreign id yields an
   empty result, and `TenantIsolationLoadListener` refuses a load by id outright. The parameter
   then decides nothing, and the finding is tidiness rather than exposure.
2. **Is it `Organization` itself?** The tenant root carries neither defence. A caller-supplied id
   reaching an unfiltered `Organization` lookup is the dangerous form. `OrganizationService`
   `batchUpdateOrganization` is the worked example: an explicit per-id `isMemberOrAdmin` loop,
   with a comment saying why it has to be there.
3. **Does the guard read the same id the service reads?** A guard on `TenantContext` beside a
   service on `#organizationId` is a mismatch even when the filter neutralises it. The repair is
   `@orgSecurity.isCurrentTenant(#organizationId)` alongside the role check, which makes the
   segment binding instead of decorative.
4. **Trace the whole workflow, not the call that refused.** A read gated a tier above the write
   beside it strands the caller who is entitled to finish the job (#666).

### Confirmed non-defects

Recorded so they are not re-derived. Each was traced to the line above the lookup.

| Site | Why it is fine |
|------|----------------|
| `AttendanceService` 227, 369, 618, 672 | reads `TenantContext.getCurrentOrgId()` on the line above; loads the caller's own tenant root |
| `ShiftTimingService:37`, `AttendanceSettingsService:56` | same shape |
| `MovementRecordService:92`, `AttendanceRegularizationService:99` | same shape |
| `ProjectService:164`, `LeavePolicyService.createPolicy`, `TenantEntityHelper:20` | same shape |
| `OrganizationController` 142, 200 | guard bound to the caller-supplied id (`isMember(#id)`, `hasAnyOrgRole(#id, ...)`); the correct pattern for the family |
| `OrganizationWebController` 146, 169, 201 | same, including `hasOrgRole(#id,'system-admin')` |
| `OrganizationService.batchUpdateOrganization:419` | explicit per-id `isMemberOrAdmin` loop, with the reason written down; the precedent to copy |
| `EmployeeController:149` (`GET /employee/organization/{id}`) | binds `@orgSecurity.isMember(#id)`; the correctly-written member of the family |

### Repaired, with the repair to copy

| Site | What it was | What it became |
|------|-------------|----------------|
| `EmployeeControllerWeb` managers by organization | guard on `TenantContext`, service on `#organizationId` | `isCurrentTenant(#organizationId)` and the role check |
| `LeavePolicyController` policies by organization (#691) | plus an existence check that read as a tenant check | same pair; the service now says why its `existsById` is not one |
| `LeaveRequestController` requests by organization (#700) | segment published and bound to nothing at all | same pair; the segment now means what the contract says |
| `LeavePolicyService.createPolicy` (#700) | uniqueness checked against `dto.organizationId` and the raw casing | checked against the tenant and the code as stored |
| `AttachmentService.linkToEntity` (#700) | an `Organization` arm loading by a caller-supplied id, overwritten one line later | arm removed, and the repository with it |

### Three traps worth keeping in mind

- **An empty result is not proof of a defence.** It can equally be an id that matches nothing.
  Check the entity for `orgFilter` rather than inferring from an empty list.
- **A check on the request as received is not a check on the row that will be written.** Every
  argument to a guard has to be the value the row will actually hold, and an organization id is
  only the most obvious of them. `LeavePolicyService.createPolicy` is the worked example, and it
  was wrong on both arguments at once for two unrelated reasons. The organization came from the
  request body while the row went to the tenant. And the leave-type code was checked as it
  arrived while the row stores it uppercased, so a lower-case code was compared against a value
  the table never holds, and slipped the uniqueness rule from the caller's own organization with
  no foreign id involved at all. The second one was found only because the first was being
  repaired, and nothing about tenancy would have surfaced it: any normalisation applied on the
  way in (case, trimming, a canonical form) opens the same gap wherever a guard runs before it.
  So when a value is transformed between the request and the write, check the transformed one.
- **Retiring a route or a path segment is a separate decision from repairing its guard.** The
  `echno-core` contract is hand-maintained with no code generation, so a route change breaks the
  web app at runtime rather than at compile time. Repair the guard now; retire the segment in a
  deliberate release. As of #700, no client calls any of the three org-segmented routes above:
  `echno-core` and `echno-web` both reach the `/web` twins, which take no segment.

---

## Explicit Joins: What the Filter Does Not Reach

The `orgFilter` narrows a query **root** and a filtered collection. It does not narrow an entity
joined explicitly in HQL. `JOIN o.employees e` inside `OrganizationRepository.findByIdAndUserEmail`
is the worked example: the query resolves any organization the caller holds an `Employee` row in,
whatever tenant the request is scoped to.

`TenantIsolationLoadListener` does not cover the gap and cannot. It runs on a post-load, and an
entity that is only joined is never loaded, so there is no event for it to judge. Where both hold
at once, nothing scopes that part of the query, and it fails silently rather than loudly. That is
the difference from every other gap in this document: a filter that does not fire produces an empty
result and a puzzled developer, while this produces a plausible answer about the wrong tenant.

Measured against a database in `OrganizationLookupUnderTheOrgFilterIT`, not reasoned about. It had
to be, because two readings of the same evidence came out wrong in opposite directions first: see
the trap at the end of this section.

### The three shapes, and which of them is safe

|  | What it is | Scoped by |
|---|---|---|
| **Fetch join** | `LEFT JOIN FETCH u.employees` | The load listener. The rows are loaded, so a foreign one is refused. Fail-closed. |
| **Association join off a tenant-scoped root** | `FROM CurrentStock cs JOIN cs.material m` | The root's filter, through the foreign key. The root is already in the tenant and the join walks out of it, so the join can narrow the result but not widen it past the tenant. |
| **Entity join, or any join off an unfiltered root** | `FROM Material m LEFT JOIN CurrentStock cs ON ...`, `FROM Organization o JOIN o.employees e` | Nothing, unless the `ON` clause carries a tenant predicate itself. |

The distinction in the third row is the one to hold on to. `JOIN cs.material m` reaches an
association from an alias and inherits the root's scoping through the foreign key.
`JOIN MaterialLocationThreshold t ON ...` names an entity outright and is tied to the root only by
whatever the `ON` clause says, so rows from any organization can satisfy it. Both spell `JOIN`;
only one of them is safe by construction.

### The rule that enforces it

`TenantScopedJoinTest` scans every `@Query` in the codebase and requires each explicit join to be
clear by one of the three structural facts above, or registered in its `CROSS_TENANT_JOINS` map
with a reason. It has the staleness check `UnboundedRepositoryReadTest` has, so the registry can
only shrink. A native query is never narrowed at all, so one that joins is always registered.

Reach for a structural fix before an entry: fetch the joined entity so the listener judges it, root
the query on a tenant-scoped entity and reach the rest through associations, or put the tenant
predicate in the `ON` clause. `LowStockRepository` does the third and is the pattern to copy:

```java
FROM Material m
LEFT JOIN CurrentStock cs ON cs.material = m AND cs.organization.id = :organizationId
```

The join carries its own scoping, with the same parameter the filtered root is narrowed by, read
from `TenantContext` at the service. A left join is what the query needs (a material with no stock
row anywhere still has to appear) and an association join could not have expressed it, so the
predicate is written out instead of being inherited.

### The sweep: cleared, with why

Issue #718, over all 151 `@Query` annotations in the repository. **41 explicit join clauses across
25 of them**, counting `countQuery` beside `value`. Also checked and carrying no join at all: the
five `Specification` classes, every use of the Criteria API, and the three
`EntityManager.createQuery` calls in `VendorSummaryService`, which reach related rows through path
expressions off a filtered root.

**Two were genuinely exposed**, both already known: the pair on `OrganizationRepository`, one of
which is cross-tenant on purpose. Nothing new was found. Everything else was clear on one of the
three facts above. Recorded so a later pass does not re-derive it.

| Site | Join | Why it is clear |
|------|------|-----------------|
| `UserRepository` `findUserWithEmployeesByKeycloakId`, `findUserWithAttachmentsByKeycloakId` | `LEFT JOIN FETCH u.employees` / `u.attachments` | Fetch joins. `User` is unfiltered, but the joined rows are loaded, so the listener refuses a foreign one. |
| `SubscriptionRepository` `findActiveSubscriptionByUserId`, `PlanRepository` (four queries) | `LEFT JOIN FETCH` throughout | Fetch joins, and nothing in the billing catalogue is tenant-scoped in the first place. |
| `SearchRepository` `findTasks`, `findIssues` | `LEFT JOIN t.project p`, `LEFT JOIN i.task t` | Roots `Task` and `Issue` are filtered; associations only. The projected `p.id` is the project of a row already in the tenant. |
| `InventoryTransactionRepository` `findMovementHistoryByMaterial` | three `LEFT JOIN FETCH` | Fetch joins off a filtered root. |
| `EmployeeRepository` (five queries) | `JOIN e.orgRoles r` | `OrgRole` is an enum element collection, not an entity, so there is no tenancy to lose. Root `Employee` is filtered. |
| `ProjectRepository` `averageTaskProgressByProjectIds` | `LEFT JOIN p.tasks t` | Filtered root, association join. `AVG(t.progress)` averages the tasks of a project already in the tenant. |
| `LeaveRequestRepository` `findDistinctByApproverParticipation` | `JOIN lr.approvals la` | Filtered root. The predicate on `la.approver.id` can only narrow the in-tenant result. |
| `LeaveBalanceRepository` `findActiveBalancesByEmployeeAndYear`, `findByOrganizationIdAndYear` | `JOIN lb.leavePolicy lp`, `JOIN lb.employee e` | Filtered root, associations. The caller-supplied `:orgId` on the second can only narrow further. |
| `LowStockRepository` `findLowStockForProject` | `JOIN cs.material m` | Filtered root `CurrentStock`, association join, and the tenant is named in the `WHERE` besides. |

Registered rather than cleared, each a deliberate cross-tenant read:

| Site | Why it is registered |
|------|----------------------|
| `OrganizationRepository.findAllByUserEmail` | The organization switcher. Listing every organization the signed-in user is employed by is the point, and narrowing it to the current tenant would leave the one they are already in. Keyed on the caller's own email, not on a caller-supplied id. |
| `OrganizationRepository.findByIdAndUserEmail` | **A membership probe, and it must not be read as a tenant check.** It answers whether the caller has an `Employee` row in the organization they named, for any organization, and establishes nothing about what they may do there. Every caller has to answer for the target in its own guard. See #698. |
| `LowStockRepository.findLowStockForOrganization`, `findLowStockAtStorageLocation` | Entity joins whose `ON` clauses carry the tenant predicate, as above. |
| `ComplianceGenerationJobRepository.findSweepCandidates` | The nightly sweep's one scan, cross-tenant by necessity: its job is to find which tenants have work, so it runs before any tenant is known. Native, scalars only, never an entity, and the caller establishes a tenant per project before enqueueing. Declared `@WithoutTenant` at the call site. |

### The second half of #718: a filtered root asked about another organization

The join is one direction of the same blind spot. The other is a query root that **is** filtered,
asked about an organization that is not the tenant. `LeavePolicyService.duplicatePolicy` called
`existsByOrganizationIdAndLeaveTypeCode(targetOrganizationId, code)` against `LeavePolicy`, which
carries `orgFilter` as a root. Hibernate adds `organization_id = <tenant>` beside the caller's
`organization_id = <target>`, the two name different organizations, and the query matches nothing
whatever the table holds. So the uniqueness rule was skipped on exactly the path that needs it, and
a real collision reached `uk_leave_policy_org_type` and came back as a 500 instead of the 409 the
method raises.

Two things generalise from the repair:

- **A check that cannot match is not a check that passed.** The filter turns a wrong argument into
  a quiet success, so a predicate naming an organization other than the tenant is always worth a
  second look at the entity it runs against.
- **Naming the current tenant instead is not automatically the smaller repair.** Here it would have
  made the check match the source policy's own code every time, so the endpoint would answer 409 to
  every input. The question genuinely was about another organization, so it had to be asked in a
  query the filter does not narrow.

The repair is `countWithLeaveTypeCodeInOrganizationUnfiltered`: native, so nothing narrows it;
carrying `organization_id = :organizationId` itself, so the tenant predicate is in the query rather
than left to a filter that will not be applied; returning a count and never an entity, so nothing
tenant-scoped is loaded and the listener has nothing to judge; and reached only after
`@orgSecurity.hasAnyOrgRole(#targetOrganizationId, ...)` has established the caller's role in the
target. `ComplianceGenerationJobRepository`'s dispatcher queries are written the same way for the
same reason.

Note what that leaves: a native query is uncovered by both mechanisms whether or not it joins, and
`TenantScopedJoinTest` only takes the part that overlaps its own subject. The
[Native SQL Queries](#native-sql-queries) section is the rule for the rest, and it is a convention
rather than a check.

### The trap that cost three CI cycles

**An AssertJ failure description can raise the exception you are reading as the result.**
`Organization` is a Lombok `@Data` entity whose generated `toString` walks its lazy `employees`
collection. A test asserted that a foreign lookup came back empty and was told a
`TenantAccessDeniedException` came out of it, which reads exactly like the listener refusing the row
under test. It was not. Building the failure description for a *present* `Optional` initialised that
collection, loaded the foreign organization's employees under the current tenant, and tripped the
listener inside the error message. The assertion was raising the exception it was reporting on, and
the first link of the chain, "anything is thrown at all", was already false.

Two habits come out of it, and both apply to any assertion whose failure message could stringify a
`@Data` entity with a lazy collection:

- **Split assertion chains one property per test.** The build reports failures with
  `exceptionFormat 'short'`, so a chained assertion names neither the broken link nor the value.
- **Assert "nothing was thrown" before asserting what came back.** If the second one is what raises,
  the first has already told you.

A third, from the same family: **a guard test that mocks the guard proves nothing.** `@orgSecurity`
is mocked in a web slice, so a `@PreAuthorize` test there exercises the mock. That is how a
permanently dead clause shipped with a passing test. The same holds for the repository in a
uniqueness check: a stub answers the question the filter would have refused to, so the test for
this defect had to run against a real schema.

---

## Troubleshooting

### "Multiple '@FilterDef' annotations define a filter named 'orgFilter'" error

**Cause:** You have `@FilterDef` declared on an entity class, but it's already defined in `package-info.java`.

**Fix:**
1. Remove the `@FilterDef` annotation from your entity
2. Remove unused imports: `org.hibernate.annotations.FilterDef` and `org.hibernate.annotations.ParamDef`
3. Keep only the `@Filter` annotation on your entity
4. The `@FilterDef` should exist **only** in `/src/main/java/org/tornotron/echno_backend/package-info.java`

**Example fix:**
```java
// ❌ WRONG - causes duplicate @FilterDef error
@Entity
@FilterDef(name = "orgFilter", parameters = @ParamDef(name = "organizationId", type = Long.class))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MyEntity { ... }

// ✅ CORRECT - only @Filter on entity
@Entity
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class MyEntity { ... }
```

### "No organization context set" error

**Cause:** `TenantEntityHelper.resolveCurrentOrganization()` was called but `TenantContext` has no org ID.

**Fix:** Ensure the request includes the `X-Organization-Id` header. If this is a background job, set `TenantContext.setCurrentOrgId(orgId)` manually before calling the service.

### Data from other organizations leaking

**Check:**
1. Does the entity have `@Filter` annotation? (NOT `@FilterDef` — that should only be in `package-info.java`)
2. Does the entity implement `TenantScopedEntity`?
3. Does the entity have a `@ManyToOne` relationship to `Organization` with `@JoinColumn(name = "organization_id")`?
4. If using a native SQL query, did you add `AND organization_id = :organizationId`?
5. Is the query running inside a `@BypassTenantFilter` method unintentionally?

### "X-Organization-Id header required" error

**Cause:** User belongs to multiple organizations but didn't send the header.

**Fix:** Include `X-Organization-Id: <orgId>` in the request. The frontend should let users pick their active org and always send this header.

### New entity's data not showing up

**Check:**
1. Was `setOrganization()` called before saving the entity?
2. Is the `organization_id` column populated in the database? (`SELECT organization_id FROM your_table WHERE id = ?`)
3. NULL organization_id rows are excluded by the filter.

### Filter not applying to custom repository methods

**Check:** A `@Query` written in JPQL has its **root** filtered, and that is all. An entity joined
explicitly in it is not narrowed, and because it is never selected the load listener does not see it
either: see [Explicit Joins](#explicit-joins-what-the-filter-does-not-reach), which is the shape to
check for before concluding the filter covers a custom query. Native queries (`nativeQuery = true`)
are not filtered at all — add the condition manually.

### Bypass not working

**Check:**
1. Is the `@BypassTenantFilter` annotation on the actual Spring-managed bean method (not a private method or a method called internally within the same class)?
2. AOP proxies require external method calls. If method A calls method B in the same class, the `@BypassTenantFilter` on method B won't trigger. Extract to a separate service class if needed.
