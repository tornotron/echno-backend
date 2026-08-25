# RBAC composite job roles (audit finding H-1)

## Problem

The `echno-realm` ships around 52 realm roles. After removing the Keycloak built-ins
(`default-roles-echno-realm`, `offline_access`, `uma_authorization`) and the two app base roles
(`user`, `admin`), 47 of them are fine-grained occupation roles: `mason`, `carpenter`,
`site-engineer`, `architect`, `accountant`, `procurement-officer`, and so on. Each is a flat,
non-composite realm role with a short job description.

Two problems follow from this:

1. Granting a real person their job means attaching several of these occupation roles one by one.
   There is no coarser handle, so assignment is slow and error prone, and composites are unused today.
2. The set is a long, undifferentiated list with no functional grouping, which makes it hard to reason
   about who can do what.

Audit finding H-1 recommends collapsing the granular roles into a smaller set of composite job roles
that can be assigned as a unit.

## What the realm actually contains (verified)

The occupation roles were read from the live seed
(`echno-deployment/ansible/roles/seed/files/keycloak-seed.json`, an Ansible-vault file) rather than
assumed. Findings that shaped the design:

- The 47 granular roles are occupation titles, not `resource:scope` permission roles. They are all
  `composite: false`.
- No seeded user holds an occupation role. The 28 seeded users carry only `user` and
  `default-roles-echno-realm`; org membership is expressed through the `/org-{id}` groups.
- The backend has no `@PreAuthorize` check that references an occupation role by name. Occupation
  roles are a catalogue that is available for assignment, not a live gate on any endpoint.
- The org-scoped role subgroups (`system-admin`, `org-manager`, `hr-admin`, `project-manager`) are a
  separate authorization layer. They live as subgroups under `/org-{id}` and are resolved by
  `JwtAuthConverter` from the JWT `groups` claim into `ORG_{id}_ROLE_{role-name}` authorities, which
  `@orgSecurity.hasAnyOrgRoleForCurrentTenant(...)` checks. This is path-based and unrelated to realm
  roles.

Because occupation roles currently gate nothing in application code, adding composites over them is a
safe, additive taxonomy change. It gives the realm the coarse job handles it lacks without altering
any existing authority resolution.

## Design: nine composite job roles

Each composite is a new realm role, marked composite, whose children are existing occupation roles.
Granting a composite grants every child occupation role through Keycloak's composite-role evaluation,
so behaviour is identical to granting the children individually. Names are prefixed `job-` so they do
not collide with any existing occupation role (several occupation roles, for example `site-manager`,
`hr-manager` and `project-manager`, already carry the plain name) and are not confused with the
`resource:scope` permission authorities produced from the RPT.

| Composite | Children (existing occupation roles) |
|-----------|--------------------------------------|
| `job-site-management` | site-manager, site-supervisor, site-engineer, foreman, supervisor, safety-officer, technical-coordinator, document-controller |
| `job-engineering` | architect, civil-engineer, structural-engineer, planning-engineer, quantity-surveyor |
| `job-skilled-trades` | mason, carpenter, electrician, plumber, painter, welder, scaffolder, crane-operator, equipment-operator, driver |
| `job-general-workforce` | laborer, helper, site-cleaner, security-guard |
| `job-finance-procurement` | accountant, procurement-officer |
| `job-office-admin` | hr-manager, admin-staff, office-assistant, receptionist, it-support |
| `job-leadership` | director, owner-representative, project-manager |
| `job-external-stakeholders` | client, consultant, contractor, sub-contractor, vendor, material-supplier |
| `job-early-career` | intern, student, trainee |

The nine families cover 46 of the 47 occupation roles. The one deliberately left out is
`system-admin`: it is the administrative role driven through the org-scoped subgroup layer, not a job
title, so bundling it into a job family would be misleading.

## Backward compatibility

The change is additive and cannot alter how any current authority resolves:

- The 52 existing roles are untouched. No role is renamed, deleted or repointed.
- No user or group is auto-assigned a composite. Assignment stays an org-admin action. This change
  only makes the composites available.
- Users and groups that still hold granular roles directly keep working exactly as before.
- `JwtAuthConverter` needs no change. It reads three things: `resource_access` roles into `ROLE_*`,
  the RPT `authorization.permissions` into `resource:scope` authorities, and the `groups` claim into
  `ORG_MEMBER_*` / `ORG_{id}_ROLE_*`. A composite realm role surfaces its children as effective realm
  roles, which flow through the same paths that already handle realm roles; the org-scoped
  `@orgSecurity` checks are group-path based and are not affected. Verified: no converter change is
  required.

## Implementation

Reconciled in code, matching the existing idempotent, guarded, check-before-create style already used
for admin MFA in `common/configuration/KeycloakInitializer`.

- `ensureCompositeJobRoles()` runs on both startup paths: from `initKeycloak()` on a fresh realm, and
  from the existing-realm branch of `init()` on every subsequent startup, next to
  `ensureAuthorizationSetup()` and `ensureAdminMfa()`.
- For each composite, `ensureCompositeJobRole(...)` ensures the composite realm role exists (creates
  it only when missing, never overwriting), reads the currently attached children with
  `getRealmRoleComposites()`, and attaches only the missing children with `addComposites(...)`. A
  child that does not exist in the realm is logged and skipped rather than failing the run.
- Every step is wrapped so a failure logs and never aborts startup, consistent with the other
  reconcile methods. Re-running only fills in what is missing; it never removes or reassigns anything.

The composite definitions live in a static list `COMPOSITE_JOB_ROLES` in the same class, so adding a
family or a child role later is a one-line edit picked up on the next deploy reconcile.

## How a group would use a composite (optional, future)

Assignment is out of scope for this change, but the intended path once an org decides to use a
composite: attach the composite realm role to the relevant `/org-{id}/{role}` subgroup (group role
mapping), or to the user directly. Members then inherit the composite's children on their next login.
This keeps day-to-day role assignment to a single coarse handle while the granular roles remain
available for finer cases.
