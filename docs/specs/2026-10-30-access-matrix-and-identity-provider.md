# Access matrix and identity provider

Status: proposed, for review. Target repos: `echno-backend`, `echno-core`, `echno-web`, and for
invitations and sign-in also `echno-android` and `echno-ios`.
Scope: the design the access matrix work is rebuilt on after the review of the first attempt
(#904 to #913). It replaces those PRs' design where they differ. Business rules marked
**owner sign-off** are proposals until the product owner approves them on the ticket.

Background, kept as written: [Auth, Tenancy and Plan Tiers](2026-10-28-auth-and-tenancy-options.md),
and [Access Permission Mapping](2026-10-30-access-permission-mapping.md) with its
[endpoint table](2026-10-30-access-permission-mapping-endpoints.csv).

## 1. What changes and why

Today access is decided by role names. A person's roles in an organization live in two places, the
`employee_org_roles` table and a Keycloak subgroup per role (`/org-{id}/{role}`), and 611 guards
check them with `@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', ...)`. Adding a role or
changing what one may do means a code change and a release, and the identity provider has to know
our roles.

The target splits the two jobs:

- **The identity provider authenticates.** It knows who a person is and their credentials. It
  knows nothing about roles, and Echno's own records, not the provider, say which organizations a
  person belongs to (3.1).
- **The access matrix authorizes.** It knows which roles a person holds in an organization, what
  each role may do in each area of the product, and over which records.

Each half sits behind one interface, so the engine behind each can change without touching the
rest of the product.

## 2. The access matrix

### 2.1 Vocabulary

| Term | Meaning |
| --- | --- |
| Area | A part of the product a permission is about: `assets`, `finance`, `access`. One per module id, or per core domain until it becomes a module. |
| Action | `read`, `create`, `edit`, `approve`, `delete`, or a named action an area declares, such as `access:edit-employee-role`. `read` matches the `<resource>:read` authorities in use today (backend #829); the screen shows it as "View". |
| Permission | `<area>:<action>`, the colon form of the module contract. |
| Scope | Which records a grant covers: `ALL`, `ASSIGNED_PROJECTS`, `RELATED` (records linked to the person) or `OWN` (records they created). |
| Grant | A permission with a scope. |
| Role | A named set of grants in one organization. |
| Baseline role | The role every active employee holds, shown as "Employee". |

The screen offers the levels the owner chose on the ticket: **Full**, **View**, **Edit**, **Delete**
and **No Access**, plus **Approve** on areas with an approval step. Full is every action the area
enforces, with scope `ALL`. No Access is no grant on the area. View is `read`; Edit, Delete and
Approve are the actions of the same names. Whether Edit also covers `create` is open (section 7).

Which records a level covers is a separate, second choice, offered only on areas that filter by
scope (2.3): "All records", "Projects they are on", "Records linked to them" or "Their own
records". It defaults to all records. Own and Limited from the proposed roles guide are not levels;
"their own records" is this second choice. Every starting role, the Employee role included, shows
and edits its grants this way, so an administrator sees, for example, that Employee may view only
their own leave, and can change it (Employee only with `access:edit-employee-role`, rule 3 in 2.6).

### 2.2 Decision API

`modules/access/api` is the only way the rest of the code asks about access. It speaks in
decisions, not in roles:

- `check(subject, permission, RecordFacts)`: may this person do this to this record.
- `filter(subject, permission)`: an `AccessFilter` a list query applies, so a person sees only the
  records their scope covers.
- `grantsFor(subject)`: everything a person holds, for `/current-user-access`.
- `isActiveMember(subject)`.

Endpoints are guarded with `@PreAuthorize("@access.can('<area>', '<action>')")` or
`@access.isMember()`. Every engine behind the API passes `AccessServiceContract`, run against both
the in-memory engine and the database engine on CockroachDB.

### 2.3 What is code and what is data

- **Areas and actions are code.** A permission only means something where a guard checks it, so the
  permissions an area offers are declared in its module manifest (or, for a core domain not yet
  carved out, in the access catalog) and validated at boot.
- **Which areas are enforced is derived, not declared.** At boot the access module collects every
  permission a `@PreAuthorize` expression names. The catalog endpoint marks each area and action
  as enforced or not. The screen lists every module the ticket names (21), so the matrix is
  complete from the start, but offers a choice only for enforced actions. A module not yet
  converted is shown as "not yet controlled here", with no selectors, until its PR lands. A startup
  test parses every `@access.can(...)` literal, so a malformed key fails the build instead of
  returning 500.
- **Which scopes an area supports is declared by the area.** An area that applies `access.filter`
  in its list and get queries registers itself as scope-aware. A grant with a scope other than
  `ALL` on any other area is refused on write.
- **Starting roles and their grants are data.** They live in two platform tables,
  `access_role_template` and `access_role_template_grant` (`@GlobalReferenceData`), seeded by
  changesets. An organization's roles are copied from them when it is provisioned. Changing the
  starting matrix is a data change, not a Java change.
- **An organization's roles are data**, as before: `access_role`, `access_role_grant`,
  `access_employee_role`, `access_employee_grant`, `access_role_grantable`.

### 2.4 Departments and groups

Not built. The ticket excludes a Permissions Groups tab and any groups functionality, and the owner
confirmed on the ticket: groups are removed for now, to stabilize what is needed first. The first
attempt's `access_group*` tables, endpoints and screen tab are dropped.

### 2.5 Roles and starting values

The owner decided on the ticket to start with the ticket's nine construction roles (System Admin,
Management Director, Project Manager, Site Engineer, QA/QC Engineer, Procurement Manager, Store
Keeper, Finance Manager, Client / Owner) across its 21 modules. The 27-role proposal on the subtask
for default roles is not used. The value in every cell still comes from the owner's sign-off on the
ticket, because the mockup's values are examples only, and lands as `access_role_template` rows.
**Owner sign-off** for the values.

Until that sign-off, the templates mirror today's roles one for one, with the grants that give
each the access it has today (the [Access Permission Mapping](2026-10-30-access-permission-mapping.md) table), so
nobody gains or loses access when an area is converted. The mapping table is an input, not the
proof: it is known to have errors (it maps some approval-list reads to `approve`, and leaves out the
attachment writes every member can make today). So:

- A `GET` is always `read`. `approve` and other named actions apply only to writes.
- Each converted area's PR carries an equivalence test: for every endpoint in the area, a starting
  role reaches it exactly when its `OrgRole` counterpart did, and the baseline role reaches exactly
  what any member reached.

The signed-off roles then arrive as a data change to the templates, and the backfill (section 4)
applies them to existing organizations.

Seeded roles are not all `is_system`. Only System Admin (the administrator of one organization; the
ticket's name, final name open in section 7) and the baseline role are system roles. Every other starting role can be renamed, edited and deleted by the organization.

### 2.6 Write rules

The Roles & Permissions endpoints enforce these, each with a test:

1. **Holding `access:edit` is not enough.** A caller changes, assigns or lists as grantable only
   roles their own roles may assign (`RoleAssignability`), and gives or takes only grants they hold,
   with that scope or `ALL`.
2. **System roles are fixed.** The grants and grantable list of a system role cannot be changed,
   and it cannot be renamed or deleted.
3. **The baseline role** is changed only by holders of `access:edit-employee-role`, which only the
   System Admin starts with. No `access:*` grant is ever allowed on it.
4. **No self-service.** Nobody changes their own roles or grants.
5. **There is always an administrator.** A change that would leave an organization with no active
   holder of the System Admin role is refused, counted inside the transaction.
6. **History is kept.** Every write appends a row to `access_change_log`: organization, actor,
   target, the before and after state as JSON, and the time. The table is append-only; nothing
   updates or deletes its rows.
7. **Organization integrity.** Every link between an employee, a role and a grantable role checks
   that both sides belong to the same organization, in the service and in the fact queries
   (`r.organization.id = :organizationId`).
8. **Role names are unique** within an organization, ignoring case, enforced by a unique key and a
   clear validation error.

### 2.7 Giving a person roles

A person gets their roles when they are added, not afterwards. The form that adds an employee or
sends an invitation (3.4) asks for the roles, and offers only the roles the sender may assign
(rule 1). Department does not choose or filter roles, because departments are not built (2.4).
Every assignment goes through `AccessRoleAssignments` (3.3), so the rules in 2.6 apply to it.

**Roles are held on a project.** The owner decided on the ticket that access is project specific: a
role given on one project counts only for that project's records, and a person on a second project
gets a role on that project too. So a person can be Project Manager on one project and Site
Engineer on another. Each assignment carries a project; an assignment with no project holds across
the organization, for the few roles that need it (System Admin, Management Director, Finance
Manager). A role means the same on every project; its grants never change per project. The
`ASSIGNED_PROJECTS` scope stays, for grants that should follow every project a person is on.

A grant held through a project assignment never reaches past that project. Its effective scope is
the grant's scope narrowed to the project: `ALL` on a project assignment means every record of that
project, and `OWN` means the person's own records in that project. Only an assignment with no
project can reach the whole organization. The engine applies this in `check` and `filter`, and a
contract test covers it.

### 2.8 Engine

- Facts (a person's roles, grants and project memberships in one organization) load once per
  request and are memoized for its length, so a per-record check in a list does not query per row.
- Across requests they are cached for five minutes in Redis, with Caffeine in front.
- Eviction runs in `TransactionSynchronization.afterCommit`, never before commit, so a concurrent
  read cannot cache the old state. Project membership changes evict too.
- A person whose employee status is null, pending, inactive or left holds nothing. A pending
  employee's roles take effect only when their invitation is accepted and the account linked (3.4).
- `AccessFilter.admits` checks the record's organization as well as its scope.

## 3. The identity-provider port

### 3.1 The port

One interface in `common/identity`, `IdentityProvider`, with an adapter per provider. The owner
decided on the ticket that Echno moves to WorkOS and does not continue with Keycloak, so
`WorkOsIdentityProvider` is the target and `KeycloakIdentityProvider` only wraps today's calls until
the move. Switching providers without changing the apps is designed in
[Identity Provider Independence](2026-10-30-identity-provider-independence.md) (option C, Echno's own
tokens, approved by the reviewer). Nothing outside an adapter
imports a Keycloak or WorkOS type, and an ArchUnit rule says so.

| Concern | Operations |
| --- | --- |
| Users and credentials | `createUser(NewIdentity) -> ExternalUserId`, `deleteUser`, `updateProfile`, `sendPasswordReset` |
| Organizations | `createOrganization(Organization) -> ExternalOrgId`, `deleteOrganization` |
| Membership | `addMember(user, org)`, `removeMember(user, org)`, `memberships(user)` |
| Invitations | `invite(email, org, inviter, expiry) -> ExternalInvitationId`, `revokeInvitation`. Section 3.4 has the flow. Only the sending is per provider: WorkOS sends its own invitation; the Keycloak adapter uses Keycloak's built-in email that asks the person to fill in their profile and set a password, with no pages of our own, so little is thrown away when Keycloak goes. |
| Token to principal | `IdentityTokenMapper`: maps a validated provider token to our `Principal(issuer, subject, email, authentication)`. `authentication` records how the person signed in (password, MFA, their company's single sign-on), mapped per adapter; a missing or unreadable value counts as the weakest, so a rule that requires MFA fails closed. The JWT decoder is configured per provider. |

Roles are not on the port. The provider never stores or returns them.

The active organization is never taken from the token. The `X-Organization-Id` header names it, as
today, and every request checks it against the person's active `Employee` record. A provider's own
organization claims are ignored.

### 3.2 Who calls the provider today, and where each call goes

| Today | Calls | Through the port |
| --- | --- | --- |
| `UserService` registration | `KeycloakUserProvisioningService.createUser`, `deleteUser` | `createUser`, `deleteUser` |
| `OrganizationService` | `KeycloakGroupService.createOrganizationGroup`, `deleteOrganizationGroup`, `addUserToOrganization` | `createOrganization`, `deleteOrganization`, `addMember` |
| `EmployeeService` | `addUserToOrganization`, `removeUserFromOrganization`, `assignOrgRole`, `removeOrgRole` | `addMember`, `removeMember`; the role calls move to the legacy mirror (3.3) |
| `DevFixtureProvisioner` | group and role writes | the port and the legacy mirror |
| `JwtAuthConverter` | reads `/org-{id}/{role}` from the `groups` claim, and permissions from the Keycloak authorization claim | `IdentityTokenMapper`. From step 8, membership and org roles come from Echno's tables (3.3); the permission claim stays until the guards that use it are converted, and goes in step 14 |
| `RPTExchangeFilter`, `KeycloakAuthorizationService`, `KeycloakManagementService` | Keycloak Authorization Services | stay inside the Keycloak adapter, and are deleted with the role-name checks |

The port lands before anything else calls Keycloak. The first attempt added direct Keycloak writes
in the assignment step; the rework does not.

### 3.3 Keeping old and new checks in step

Until every guard is converted, two systems decide access: the matrix for converted areas, and
`@orgSecurity` role-name checks for the rest. They must never disagree about the same person.

- Each starting role that has a counterpart in `OrgRole` records it (`legacy_org_role` on the
  template and the copied role).
- `AccessRoleAssignments` is the only writer of role assignments. It writes each change of a role
  with a counterpart into `employee_org_roles` **in the same transaction**, so the two tables never
  disagree. The Keycloak role subgroup follows through an outbox: the change is recorded in the same
  transaction and a repeat-safe job applies it after commit, retrying until it succeeds, with a
  nightly check for drift (the outbox in [Auth, Tenancy and Plan Tiers](2026-10-28-auth-and-tenancy-options.md)).
  The mirror is the `LegacyRoleMirror`, deleted in the last step.
- Today three things read roles and memberships from the token's Keycloak groups:
  `@orgSecurity`, `OrgRoleAuthority` (used for salary visibility) and `TenantFilter` (which
  organizations a person may name). From step 8 they read `employee_org_roles` and `Employee`
  instead, cached and evicted after commit like the matrix facts, so a change takes effect on the
  next request.
- The 156 or so guards written as `hasAuthority('<area>:<action>')` still get their permissions from
  Keycloak's authorization services, which follow the Keycloak role subgroups. That is why the
  subgroup mirror stays until those guards are converted. Until then, removing a role reaches those
  guards only when the person's token refreshes, at most one token lifetime later; the areas behind
  them are converted early to shorten that window.
- A custom role has no counterpart. It affects only converted areas, which is the intended
  behavior.
- `EmployeeService` role writes go through `AccessRoleAssignments`, so the old screens and the new
  one write the same rows.

### 3.4 Invitations by link

The owner decided on the ticket that a person joins an organization only through an invitation
link, never an invite code, and never by registering themselves first. Invite codes go completely.
Today's codes give the person no role, which is why this work started. The owner points to
Atlassian's invitations as the model. The flow:

1. An administrator enters the person's email and the roles they get, with the project for each
   (2.7). Echno creates the employee record, pending, with those roles, and an invitation.
2. Echno sends the link through the port (`invite`). The link is single use, expires, can be
   revoked, and opens only for the invited email.
3. The link opens the organization's own sign-in page, where the person enters their details (name,
   and a password unless their company signs in with its own directory). No password is ever sent
   or stored by Echno.
4. On first sign-in, the account is linked to the pending employee record, never a second one, and
   the employee becomes active with the roles chosen in step 1.
5. Administrators see each invitation's state (sent, accepted, expired, revoked) and can resend or
   revoke it.

**Starting a new organization** is the one sign-up without an invitation: the person creates their
account and the organization together, and holds System Admin in it. Whether anyone may do that, or
only Echno's own staff, is open (section 7).

Today a person joins by redeeming an invite code (`projectInviteCode`, `/api/v1/invitation/web`,
and `joinOrganization` on the employee controllers). Those codes and that route are removed when
this lands.

## 4. Existing organizations

Every organization on both stagings has employees and `employee_org_roles` rows but no matrix rows.
A dev reset is not an option there. The backfill:

- runs as an `ApplicationRunner` in the access module, once per boot, under a database lock row so
  two replicas cannot run it at once;
- reconciles every organization on every run, rather than picking those without matrix roles: it
  ensures each starting role from the templates and, for each `employee_org_roles` row whose role
  has a counterpart, the matching `access_employee_role` row;
- does each organization in its own transaction, so an organization is either complete or
  untouched, and a run that stops part way is finished by the next one;
- is idempotent: unique keys on every row it writes, and a rerun changes nothing;
- is tested from a pre-migration organization: an organization and employees created through the
  old path, then the runner, then checks that each person holds the same access as before.

## 5. Platform changes this needs

The module contract says these are raised, not made. Each is proposed here for review.

| Change | Why |
| --- | --- |
| `ModuleManifest.core` and `ModuleRegistry` support for core modules | Access control cannot be switched off, paywalled or depend on a module that can be. |
| `common/identity` with the `IdentityProvider` port, and an ArchUnit rule that only adapters import provider types | Section 3. |
| `EmployeeService` and `OrganizationService` call the port and `AccessRoleAssignments` instead of `KeycloakGroupService` | Section 3.2 and 3.3. |
| Converted controllers, starting with assets, swap `@orgSecurity` role checks for `@access.can` | Section 2.2. Each converted controller gets a guard test. |
| A boot-time scan of `@PreAuthorize` expressions | Section 2.3, enforced areas and literal validation. |
| Invitations by link replace `projectInviteCode` and `joinOrganization` | Section 3.4, the owner's decision on the ticket. |
| `user_identity` and `organization_identity` replace `users_table.keycloak_id` and the Keycloak group link | [Identity Provider Independence](2026-10-30-identity-provider-independence.md), section 3. |
| Platform administrators recorded in Echno instead of the Keycloak realm role behind `hasRole('platform-admin')` | The provider knows nothing about roles, and Keycloak does not continue. |

## 6. Delivery

Each step is one PR into `auth-workos`, merged bottom-up with every check green. `auth-workos` goes
to `development` in one piece once the migration is complete. The backend comes first, then core,
then web, then the mobile apps where sign-in changes. The developer guide changes each step needs are tracked in tornotron/echno-docs#20 and
made in a docs PR when the step merges.

1. Run the pull request checks and CodeRabbit on PRs into `auth-workos`.
2. This design note and the specs it cites.
3. Access module and decision API, with the core module flag.
4. Tables, without groups, with the organization checks, the change log and the project on each
   assignment.
5. Engine: per-request facts including roles held on each project, eviction after commit.
6. Role templates, provisioning and the backfill for existing organizations.
7. The `@access` guard, the enforced-permission scan and literal validation.
8. The identity-provider port with the Keycloak adapter and the legacy mirror; membership and org
   roles read from Echno's tables instead of the token (3.3).
9. Identity tables in place of `keycloak_id`, the `IdentityProviderContract` test and the in-memory
   fake adapter (phase 1 of the identity note).
10. Read and write endpoints for the screen, with the rules in 2.6.
11. Invitations by link with roles chosen up front (2.7, 3.4); invite codes removed.
12. One area converted per PR, each applying scope filtering in its list and get queries, starting
    with the areas behind `hasAuthority` guards (3.3).
13. Move the remaining role-name queries (managers, attendance geofence, `EmployeeDto.orgRoles`) and
    record platform administrators in Echno.
14. Delete `OrgRole`, `employee_org_roles`, the legacy mirror, the token's permission claim and the
    Keycloak role code.
15. Echno's own tokens, used first by web (phase 2).
16. Android and iOS sign in through Echno (phase 3).
17. The WorkOS adapter, then the move of every account from Keycloak (phase 4).

## 7. Waiting on the owner

Decided on the ticket: the nine construction roles (2.5), the levels (2.1), no groups (2.4), WorkOS
(3.1), the screen layout (2.3), invitations by link only (3.4) and roles held on a project (2.7).
Still open:

- The starting value of every cell for the nine roles (2.5).
- Whether Edit also covers creating records, since the owner's levels have no separate Create (2.1).
- What "the enterprise domain" the invitation link leads to means: a sign-in page per organization,
  or a web address per organization like Atlassian's (3.4).
- The administrator role: whether "System Admin" stays its name, whether anyone may start a new
  organization and so hold it (3.4), and keeping it apart from Echno's platform administrators.
