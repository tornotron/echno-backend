# Multi-Tenancy via Hibernate @Filter — Implementation Guide

Data-level organization isolation using Hibernate filters that automatically append `WHERE organization_id = :organizationId` to every query on tenant-scoped entities.

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
17. [Troubleshooting](#troubleshooting)

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

**Check:** Custom queries using `@Query` with JPQL will be filtered. Native queries (`nativeQuery = true`) will NOT be filtered — add the condition manually.

### Bypass not working

**Check:**
1. Is the `@BypassTenantFilter` annotation on the actual Spring-managed bean method (not a private method or a method called internally within the same class)?
2. AOP proxies require external method calls. If method A calls method B in the same class, the `@BypassTenantFilter` on method B won't trigger. Extract to a separate service class if needed.
