# Organization-Scoped Roles — Complete Guide

## The Problem This Solves

Before this feature, your app had **global** permissions. If you gave a user the `organization:admin` permission, they became an admin for **every single organization** in the system. There was no way to say:

> "User Ravi is an admin, but only for TechCorp (org #5). He should have no special powers in BuildInc (org #10)."

Now you can do exactly that.

---

## The Core Idea (Read This First)

The entire system is built on one simple concept from Keycloak: **groups can have child groups (subgroups)**.

Think of it like folders on your computer:

```
org-5/                      ← This is a folder (Keycloak group) for the organization
    ├── system-admin        ← This is a subfolder (subgroup) for the role
    ├── org-manager
    ├── hr-admin
    └── project-manager
```

- When a user **joins an organization**, they are placed inside the `org-5` folder.
- When a user is **assigned a role** (like system-admin), they are placed inside the `org-5/system-admin` subfolder.

Keycloak puts these folder paths into the user's login token (JWT). Your Spring Boot app reads the token and knows:
- "This user is a member of org 5" (because they're in `org-5`)
- "This user is a system-admin in org 5" (because they're in `org-5/system-admin`)

That's the entire concept. Everything below is just the implementation details.

---

## How It Works Step By Step

### Step 1: Organization Is Created

When someone creates an organization (let's say org with ID 5), your `OrganizationService.addOrganization()` calls `KeycloakGroupService.createOrganizationGroup("5", "TechCorp")`.

This method does two things:
1. Creates the parent group `org-5` in Keycloak (this already existed before)
2. **NEW**: Creates role subgroups inside it: `system-admin`, `org-manager`, `hr-admin`, `project-manager`

After this, Keycloak's group tree looks like:
```
org-5/
    ├── system-admin
    ├── org-manager
    ├── hr-admin
    └── project-manager
```

**File:** `KeycloakGroupService.java` → `createOrganizationGroup()` and `createDefaultRoleSubgroups()`

---

### Step 2: User Joins the Organization

When a user joins org 5 (via the employee join flow), `KeycloakGroupService.addUserToOrganization(keycloakId, "5")` is called. This adds the user to the `org-5` group.

This part is **unchanged** from before. It gives the user **membership** in the org.

**File:** `KeycloakGroupService.java` → `addUserToOrganization()`

---

### Step 3: User Is Assigned a Role

This is the **new part**. To make user Ravi a system-admin of org 5, you call:

```java
keycloakGroupService.assignOrgRole(ravi.getKeycloakId(), "5", OrgRole.SYSTEM_ADMIN);
```

This adds Ravi to the `org-5/system-admin` subgroup in Keycloak.

**Important:** The user must ALREADY be a member of the org (step 2) before you assign a role. Membership and roles are separate. A user can be a member without any role, but should not have a role without being a member.

**File:** `KeycloakGroupService.java` → `assignOrgRole()`

---

### Step 4: User Logs In and Gets a Token

When Ravi logs in, Keycloak creates a JWT (login token). Inside this token, there's a field called `groups` that lists all the Keycloak groups Ravi belongs to:

```json
{
  "sub": "ravi-keycloak-uuid",
  "preferred_username": "ravi",
  "groups": [
    "/org-5",
    "/org-5/system-admin"
  ]
}
```

The first entry (`/org-5`) means Ravi is a member of org 5.
The second entry (`/org-5/system-admin`) means Ravi has the system-admin role in org 5.

If Ravi was also a member of org 10 with no special role, his groups would look like:
```json
"groups": ["/org-5", "/org-5/system-admin", "/org-10"]
```

---

### Step 5: Spring Boot Reads the Token

Every time Ravi makes an API request, the JWT token is sent in the `Authorization` header. Your `JwtAuthConverter.java` reads this token and converts the `groups` into Spring Security **authorities** (think of authorities as permission labels that Spring can check).

Here's exactly how the conversion works:

```
JWT group value          →  What JwtAuthConverter does     →  Spring Authority
─────────────────────────────────────────────────────────────────────────────
"/org-5"                 →  Strip "/", no slash after ID   →  "ORG_MEMBER_5"
"/org-5/system-admin"    →  Strip "/", split at slash      →  "ORG_5_ROLE_system-admin"
"/org-10"                →  Strip "/", no slash after ID   →  "ORG_MEMBER_10"
```

The logic is simple:
- If the group path is just `org-{id}` (no slash after the ID) → it's a **membership** → becomes `ORG_MEMBER_{id}`
- If the group path is `org-{id}/{something}` (has a slash after the ID) → it's a **role** → becomes `ORG_{id}_ROLE_{something}`

**File:** `JwtAuthConverter.java` → `extractGroupAuthorities()`

---

### Step 6: Controller Checks the Authority

Now when Ravi tries to delete org 5, the controller checks:

```java
@DeleteMapping("{id}")
@PreAuthorize(
    "@orgSecurity.hasOrgRole(#id, 'system-admin') " +               // Is Ravi a system-admin of THIS org?
    "or (hasAuthority('organization:delete') and @orgSecurity.isMember(#id)) " +  // OR has global delete + is member?
    "or hasAuthority('organization:admin')"                          // OR is a global admin?
)
public ResponseEntity<ApiResponse> deleteOrganization(@PathVariable Long id) { ... }
```

When `id = 5`:
- `@orgSecurity.hasOrgRole(5, 'system-admin')` checks: does Ravi have the authority `ORG_5_ROLE_system-admin`? **YES** → Access granted.

When `id = 10`:
- `@orgSecurity.hasOrgRole(10, 'system-admin')` checks: does Ravi have `ORG_10_ROLE_system-admin`? **NO** (he's not a system-admin in org 10)
- Falls through to next check... and so on.

**File:** `OrganizationSecurityService.java` → `hasOrgRole()` and `hasAnyOrgRole()`

---

## The Files and What Each One Does

### 1. `OrgRole.java` — The List of Available Roles

**Path:** `src/main/java/org/tornotron/echno_backend/common/enums/OrgRole.java`

This is just an enum that lists what roles exist. Each role has a `groupName` which is the exact name of the Keycloak subgroup.

```java
SYSTEM_ADMIN("system-admin")       // Full control over the organization
ORG_MANAGER("org-manager")         // Can manage org settings and employees
HR_ADMIN("hr-admin")               // Can manage HR features (leave, attendance)
PROJECT_MANAGER("project-manager") // Can manage projects within the org
```

**When would you change this file?**
- When you want to add a new role (e.g., `FINANCE_ADMIN("finance-admin")`)
- When you want to rename a role

---

### 2. `JwtAuthConverter.java` — Reads the Token

**Path:** `src/main/java/org/tornotron/echno_backend/common/configuration/JwtAuthConverter.java`

This file runs automatically every time an API request comes in. It reads the JWT token and extracts all the user's authorities. The key method is `extractGroupAuthorities()`.

**When would you change this file?**
- Almost never. It's infrastructure. You'd only touch it if you change the naming format of authorities.

---

### 3. `KeycloakGroupService.java` — Talks to Keycloak

**Path:** `src/main/java/org/tornotron/echno_backend/common/service/KeycloakGroupService.java`

This is the service that makes API calls to Keycloak to manage groups and subgroups. It has these methods:

**Existing (unchanged purpose):**
| Method | What it does |
|--------|-------------|
| `createOrganizationGroup(orgId, name)` | Creates the org group AND its role subgroups |
| `addUserToOrganization(userId, orgId)` | Makes user a member of the org |
| `removeUserFromOrganization(userId, orgId)` | Removes user from the org |
| `deleteOrganizationGroup(orgId)` | Deletes the org group and all subgroups |
| `getUserOrganizations(userId)` | Lists all orgs a user belongs to |

**New:**
| Method | What it does |
|--------|-------------|
| `assignOrgRole(userId, orgId, role)` | Gives a user a role in a specific org |
| `removeOrgRole(userId, orgId, role)` | Takes away a role from a user in an org |
| `getUserOrgRoles(userId, orgId)` | Lists all roles a user has in a specific org |

**When would you change this file?**
- When you need new ways to manage roles (e.g., bulk role assignment)
- When you need to create a subgroup for a role that doesn't exist yet in an existing org

---

### 4. `OrganizationSecurityService.java` — The Guard

**Path:** `src/main/java/org/tornotron/echno_backend/common/service/OrganizationSecurityService.java`

This is what you use in `@PreAuthorize` annotations. It checks if the currently logged-in user has the right authorities.

| Method | What it checks | Example usage |
|--------|---------------|---------------|
| `isMember(orgId)` | Is user a member of this org? | `@orgSecurity.isMember(#id)` |
| `isMemberOrAdmin(orgId)` | Is user a member OR global admin? | `@orgSecurity.isMemberOrAdmin(#id)` |
| `hasOrgRole(orgId, role)` | Does user have this specific role in this org? | `@orgSecurity.hasOrgRole(#id, 'system-admin')` |
| `hasAnyOrgRole(orgId, roles...)` | Does user have ANY of these roles in this org? | `@orgSecurity.hasAnyOrgRole(#id, 'system-admin', 'hr-admin')` |

**When would you change this file?**
- When you need a new kind of check (e.g., "is member AND has role")

---

## Common Scenarios (Copy-Paste Reference)

### Scenario 1: Assign a role when a user creates an organization

In your service layer, after creating the org and adding the user as a member:

```java
// User creates org → automatically becomes system-admin of that org
keycloakGroupService.addUserToOrganization(user.getKeycloakId(), orgId);
keycloakGroupService.assignOrgRole(user.getKeycloakId(), orgId, OrgRole.SYSTEM_ADMIN);
```

### Scenario 2: An admin assigns a role to another user

```java
// Make user an HR admin for org 5
keycloakGroupService.assignOrgRole(targetUser.getKeycloakId(), "5", OrgRole.HR_ADMIN);
```

### Scenario 3: Remove a role from a user

```java
// Remove HR admin role (user stays a member of the org)
keycloakGroupService.removeOrgRole(targetUser.getKeycloakId(), "5", OrgRole.HR_ADMIN);
```

### Scenario 4: Check what roles a user has

```java
List<String> roles = keycloakGroupService.getUserOrgRoles(user.getKeycloakId(), "5");
// Returns: ["system-admin", "hr-admin"]
```

### Scenario 5: Protect an endpoint — only system-admin of THIS org

```java
@GetMapping("/sensitive-data/{orgId}")
@PreAuthorize("@orgSecurity.hasOrgRole(#orgId, 'system-admin')")
public ResponseEntity<?> getSensitiveData(@PathVariable Long orgId) { ... }
```

### Scenario 6: Protect an endpoint — system-admin OR hr-admin of THIS org

```java
@PostMapping("/approve-leave/{orgId}")
@PreAuthorize("@orgSecurity.hasAnyOrgRole(#orgId, 'system-admin', 'hr-admin')")
public ResponseEntity<?> approveLeave(@PathVariable Long orgId) { ... }
```

### Scenario 7: Combine org-scoped role with existing global permissions

```java
// Org system-admin can do it, OR someone with global permission who is a member, OR global admin
@PreAuthorize(
    "@orgSecurity.hasOrgRole(#id, 'system-admin') " +
    "or (hasAuthority('organization:update') and @orgSecurity.isMember(#id)) " +
    "or hasAuthority('organization:admin')"
)
```

### Scenario 8: Add a new role type

1. Add to `OrgRole.java`:
   ```java
   FINANCE_ADMIN("finance-admin"),
   ```

2. For **new** organizations, the subgroup will be created automatically when the org is created.

3. For **existing** organizations, you need to manually create the subgroup. You can do this by:
   - Going to the Keycloak admin console → Groups → find `org-{id}` → create child group `finance-admin`
   - Or writing a one-time migration script that calls `createDefaultRoleSubgroups` for all existing orgs

4. Use it in controllers:
   ```java
   @PreAuthorize("@orgSecurity.hasOrgRole(#orgId, 'finance-admin')")
   ```

---

## How the Three Authorization Layers Work Together

Your app now has three layers of authorization that can be combined:

```
Layer 1: GLOBAL PERMISSIONS (from Keycloak resource/scope permissions via RPT token)
├── These apply everywhere, not scoped to any specific org
├── Examples: "organization:read", "employee:admin", "leave:approve"
├── Checked with: hasAuthority('organization:read')
└── Use for: platform-wide actions (like "can this user create organizations at all?")

Layer 2: ORG MEMBERSHIP (from Keycloak parent groups)
├── Tells you which org a user belongs to
├── Examples: ORG_MEMBER_5, ORG_MEMBER_10
├── Checked with: @orgSecurity.isMember(#orgId)
└── Use for: "is this user even part of this organization?"

Layer 3: ORG-SCOPED ROLES (from Keycloak subgroups) ← THIS IS THE NEW ONE
├── Tells you what a user can do WITHIN a specific org
├── Examples: ORG_5_ROLE_system-admin, ORG_10_ROLE_hr-admin
├── Checked with: @orgSecurity.hasOrgRole(#orgId, 'system-admin')
└── Use for: "is this user an admin of THIS specific organization?"
```

You can combine them in `@PreAuthorize` using `or` and `and`:

```java
// All three layers in one check:
@PreAuthorize(
    "@orgSecurity.hasOrgRole(#orgId, 'system-admin') " +           // Layer 3: org-scoped role
    "or (hasAuthority('employee:read') and @orgSecurity.isMember(#orgId)) " +  // Layer 1 + Layer 2
    "or hasAuthority('employee:admin')"                             // Layer 1: global permission
)
```

This reads as: "Allow if the user is a system-admin of THIS org, OR has global employee:read permission AND is a member of this org, OR is a global employee admin."

---

## What Happens in the JWT Token

For a user who is a member of org 5 (as system-admin) and org 10 (no special role), the JWT looks like:

```json
{
  "sub": "abc-123-keycloak-uuid",
  "preferred_username": "ravi",
  "email": "ravi@example.com",

  "groups": [
    "/org-5",
    "/org-5/system-admin",
    "/org-10"
  ],

  "resource_access": {
    "echno-backend": {
      "roles": ["user"]
    }
  },

  "authorization": {
    "permissions": [
      { "rsname": "organization", "scopes": ["read", "create"] },
      { "rsname": "employee", "scopes": ["read"] }
    ]
  }
}
```

`JwtAuthConverter` processes this and produces these authorities:

```
From groups:
  ORG_MEMBER_5              (from /org-5)
  ORG_5_ROLE_system-admin   (from /org-5/system-admin)
  ORG_MEMBER_10             (from /org-10)

From resource_access:
  ROLE_user

From authorization.permissions:
  organization:read
  organization:create
  employee:read

From standard scopes:
  SCOPE_openid
  SCOPE_profile
```

All of these are available in `@PreAuthorize` for checking.

---

## Important Notes

1. **Role changes require re-login.** When you assign or remove a role, it takes effect on the user's **next login** (next JWT token). The current token won't change. This is standard JWT behavior.

2. **Deleting an org deletes all role subgroups.** When `deleteOrganizationGroup()` is called, Keycloak automatically removes all child subgroups. You don't need to clean them up manually.

3. **Membership and roles are independent.** `addUserToOrganization` adds membership. `assignOrgRole` adds a role. They're separate API calls. Removing a role does NOT remove membership, and removing membership does NOT remove roles (though the roles become meaningless without membership).

4. **The `OrgRole` enum is for type safety only.** It doesn't enforce anything in Keycloak. You could technically create a subgroup with any name in the Keycloak admin console and it would work. The enum just prevents typos in your Java code.

5. **Existing organizations won't have subgroups.** Organizations created before this change don't have the role subgroups. You'll need to either recreate them or add subgroups via the Keycloak admin console.
