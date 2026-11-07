# Access matrix and identity provider

Status: proposed, for review. Target repos: `echno-backend`, `echno-core`, `echno-web`, and for
invitations and sign-in also `echno-android` and `echno-ios`.
Scope: the design the access matrix work is rebuilt on after the review of the first attempt
(#904 to #913). It replaces those PRs' design where they differ. Business rules marked
**owner sign-off** are proposals until the product owner approves them on the ticket. The ordered
PRs are in the [WorkOS build plan](2026-11-07-workos-build-plan.md).

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
Approve are the actions of the same names, and Edit also covers `create`: there is no separate Create
level on the screen.

Which records a level covers is a separate, second choice, offered only on areas that filter by
scope (2.3): "All records", "Projects they are on", "Records linked to them" or "Their own
records". It defaults to all records. Own and Limited from the proposed roles guide are not levels;
"their own records" is this second choice. Every starting role, the Employee role included, shows
and edits its grants this way, so an administrator sees, for example, that Employee may view only
their own leave, and can change it (Employee only with `access:edit-employee-role`, rule 3 in 2.6).

### 2.2 Decision API

`modules/access/api` is the only way the rest of the code asks about access. It speaks in
decisions:

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

Today's `OrgRole` set and the ticket's nine do not line up, so the starting point is:

| Role | Counterpart in `OrgRole` | Starting grants until sign-off |
| --- | --- | --- |
| System Admin | `system-admin` | Today's access, plus `access:*` |
| Project Manager | `project-manager` | Today's access |
| Site Engineer | `site-engineer` | Today's access |
| QA/QC Engineer | `qa-engineer` | Today's access |
| Store Keeper | `store-keeper` | Today's access |
| Management Director, Procurement Manager, Finance Manager, Client / Owner | none | The baseline only. No access exists today to mirror, and guessing would grant access nobody approved. |
| HR Admin, Safety Officer, Org Manager, Observation Producer (carried over) | `hr-admin`, `safety-officer`, `org-manager`, `observation-producer` | Today's access. These exist so the backfill leaves nobody short. A new organization's screen does not offer them, and the owner's sign-off says where each one goes. |

Seeded roles are not all `is_system`. Only System Admin (the administrator of one organization, held by
its first administrator) and the baseline role are system roles. Every other starting role can be renamed, edited and deleted by the organization.

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

A person gets their roles at the moment they are added. The form that adds an employee or
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

**During the migration, a role with an `OrgRole` counterpart stays organization-wide.** The old checks
have no idea of a project (audit G-02). Mirroring a project assignment into `employee_org_roles` would
grant that role everywhere, and leaving it out would make the screen and the old checks disagree. So
`AccessRoleAssignments` refuses a project on such a role, and the form says why. The column and the
engine carry the project from the first PR, and a role without a counterpart takes one as soon as its
areas are converted. Every role takes one once `OrgRole` is deleted.

### 2.8 Engine

- Facts (a person's roles, grants and project memberships in one organization) load once per
  request and are memoized for its length, so a per-record check in a list does not query per row.
- Across requests they are cached for five minutes in Redis, with Caffeine in front.
- Eviction runs in `TransactionSynchronization.afterCommit`, never before commit, and project membership
  changes evict too. A read that loaded the old rows just before a commit and writes the cache just after
  the eviction can still keep the old holdings for up to the five minutes (#958). Membership is read
  fresh on every request, so removing a person is never delayed this way.
- Employees with status `active`, `onLeave` or `probation` hold their access. Any other status
  (`inactive`, `terminated`, `resigned`, `suspended`), and null, holds nothing. Today no check reads
  this field (audit G-18), so the backfill first sets null to `active` (section 4). A person invited
  but not yet accepted has no employee record at all (3.4).
- `AccessFilter.admits` checks the record's organization as well as its scope.

## 3. The identity-provider port

### 3.1 The port

One interface in `common/identity`, `IdentityProvider`, with an adapter per provider, and one setting,
`echno.identity.provider`, that picks the adapter per environment. The owner decided on the ticket
that Echno moves to WorkOS. The first production deployment runs on `WorkOsIdentityProvider` with no
Keycloak in its stack. `KeycloakIdentityProvider` wraps today's calls and keeps both stagings running
until a later migration. Apps sign in with the provider, behind one small module per app (option A in
[Identity Provider Independence](2026-10-30-identity-provider-independence.md), where option C is
deferred). Nothing outside an adapter imports a Keycloak or WorkOS type, and an ArchUnit rule says so.

| Concern | Operations |
| --- | --- |
| Users and credentials | `profile(user) -> (email, emailVerified, name)`, `sendPasswordReset`, `endSessions(user)`. Creating an account with a password is an optional extra only the Keycloak adapter has, for its self-registration; under WorkOS an account comes from accepting an invitation. |
| Organizations | `createOrganization(Organization) -> ExternalOrgId`, `deleteOrganization` |
| Membership | `addMember(user, org)`, `removeMember(user, org)`. Echno reads membership from `Employee`, so the port has no query for it. |
| Invitations | `invite(email, org, inviter, expiry) -> ExternalInvitationId`, `resendInvitation`, `revokeInvitation`, `invitationState(id) -> (state, acceptedUser)`. Section 3.4 has the flow. WorkOS sends its own invitation; the Keycloak adapter creates the account and uses Keycloak's built-in email that asks the person to fill in their profile and set a password, with no pages of our own, so little is thrown away when Keycloak goes. That email needs the realm's SMTP settings, which the stagings do not have yet, so they need them before they use invitations. |
| Token to principal | `IdentityTokenMapper`: maps a validated provider token to our `Principal(issuer, subject, email, authentication)`. `email` is optional, since a WorkOS token has none. `authentication` records how the person signed in (password, MFA, their company's single sign-on), mapped per adapter; a missing or unreadable value counts as the weakest, so a rule that requires MFA fails closed. A WorkOS token has no such value, so MFA is set in WorkOS and Echno has no rule on it yet (build plan, section 3). The JWT decoder is configured per provider. |

Roles are not on the port. The provider never stores or returns them.

The active organization is never taken from the token. The `X-Organization-Id` header names it, as
today, and every request checks it against the person's active `Employee` record. A provider's own
organization, role and permission claims are ignored.

### 3.2 Who calls the provider today, and where each call goes

| Today | Calls | Through the port |
| --- | --- | --- |
| `UserService` registration | `KeycloakUserProvisioningService.createUser`, `deleteUser` | The Keycloak adapter's self-registration extra; absent under WorkOS |
| `OrganizationService` | `KeycloakGroupService.createOrganizationGroup`, `deleteOrganizationGroup`, `addUserToOrganization` | `createOrganization`, `deleteOrganization`, `addMember` |
| `EmployeeService` | `addUserToOrganization`, `removeUserFromOrganization`, `assignOrgRole`, `removeOrgRole` | `addMember`, `removeMember`; the role calls go, replaced by the legacy mirror (3.3) |
| `DevFixtureProvisioner` | group and role writes | the port and the legacy mirror |
| `JwtAuthConverter` | reads `/org-{id}/{role}` from the `groups` claim, and permissions from the Keycloak authorization claim | `IdentityTokenMapper`, then Echno's tables mint the same authorities (3.3). The permission claim goes with the old guards (build plan steps 11 and 32) |
| `RPTExchangeFilter`, `KeycloakAuthorizationService`, `KeycloakManagementService` | Keycloak Authorization Services | conditional on the Keycloak adapter, absent under WorkOS, and deleted with the role-name checks |

The port lands before anything else calls Keycloak. The first attempt added direct Keycloak writes
in the assignment step; the rework does not.

### 3.3 Keeping old and new checks in step

Until every guard is converted, two systems decide access: the matrix for converted areas, and
`@orgSecurity` role-name checks for the rest. They must never disagree about the same person.

- Each starting role that has a counterpart in `OrgRole` records it (`legacy_org_role` on the
  template and the copied role).
- `AccessRoleAssignments` is the only writer of role assignments. It writes each change of a role
  with a counterpart into `employee_org_roles` **in the same transaction**, so the two tables never
  disagree. This write is the `LegacyRoleMirror`, deleted in the last step.
- Today three things read roles and memberships from the token's Keycloak groups:
  `@orgSecurity`, `OrgRoleAuthority` (used for salary visibility) and `TenantFilter` (which
  organizations a person may name). All three read authorities. So `JwtAuthConverter` keeps minting
  `ORG_MEMBER_{id}` and `ORG_{id}_ROLE_{role}`, from `Employee` and `employee_org_roles` by way of
  `user_identity`, and those three do not change. The read is cached and evicted after commit like the
  matrix facts, so a change takes effect on the next request under either provider. Under Keycloak
  this also ends the wait for a new token.
- A machine login has no `Employee`. Under Keycloak, a service account's token keeps its groups from
  the token, so `observation-producer` keeps working. The WorkOS adapter has no such path yet (build
  plan, section 3).
- The guards written as `hasAuthority('<area>:<action>')` (141 in the mapping table) are held by nobody
  today: the audit's G-03 and `docs/org-scoped-roles.md` both show that no such authority is ever
  minted. They answer no under either provider, as they do now, until their area is converted. The
  mapping note's check of one token from each staging must confirm this before `auth-workos` reaches
  `development`.
- Under Keycloak the role subgroup still follows each change, after commit, through a repeat-safe
  queue. No guard reads it any more. `KeycloakInitializer.ensureAdminMfa` does: it requires MFA for
  members of the `system-admin` subgroups, and the stagings keep that rule. Under WorkOS there is no
  subgroup.
- A custom role has no counterpart. It affects only converted areas, which is the intended
  behavior.
- `EmployeeService` role writes go through `AccessRoleAssignments`, so the old screens and the new
  one write the same rows.

### 3.4 Invitations by link

The owner decided on the ticket that a person joins an organization only through an invitation
link, never an invite code, and never by registering themselves first. Invite codes go completely.
Today's codes give the person no role, which is why this work started. The owner points to
Atlassian's invitations as the model. The flow:

1. An administrator enters the person's email, the roles they get with the project for each (2.7),
   and the employee details the form collects today, including the manager. Echno stores an invitation
   with its roles. No employee record exists yet.
2. Echno sends the link through the port (`invite`). The link expires and can be revoked. WorkOS sends
   the email, so the backend needs no mail sender.
3. The link opens the provider's sign-in page, where the person enters their details (name, and a
   password unless their company signs in with its own directory). No password is ever sent or stored
   by Echno.
4. On first sign-in, Echno asks the provider for the invitation's state. It links the account only when
   the invitation is accepted, by this person, with a verified email equal to the invited one. It then
   creates the employee, active, with the roles from step 1, in one transaction. Links are never made
   on email alone.
5. Administrators see each invitation's state (sent, accepted, accepted by another address, expired,
   revoked) and can resend or revoke it.

**Why no pending employee record.** An earlier version of this note created one at step 1.
`employee.user_id` is required, and a WorkOS account does not exist until the person accepts. Every
employee list, attendance query and manager lookup would also have to learn to skip pending rows. An
invitation row avoids both, and the invitation screen is where a pending person belongs.

**The invited email is Echno's check.** WorkOS lets an organization invitation on a corporate domain be
accepted with any address on that domain ([Invitations](https://workos.com/docs/authkit/invitations)).
Step 4 refuses that case and shows it to the administrator.

**Starting a new organization** is the one case with no invitation from inside it. In the first
production deployment nobody may do it for themselves: sign-up is off, the organization and its first
System Admin's invitation come from a one-time runner (build plan, section 7), and any further
organization is created by Echno's own staff. The stagings keep self-service until they migrate.

Today a person joins by redeeming an invite code (`projectInviteCode`, `/api/v1/invitation/web`,
and `joinOrganization` on the employee controllers). Those codes and that route are removed when
this lands.

## 4. Existing organizations

Every organization on both stagings has employees and `employee_org_roles` rows but no matrix rows.
A dev reset is not an option there. A brand-new organization has nothing to backfill and gets its roles
from the templates. Two preparations run first, because `Employee` and `employee_org_roles` become the
truth: a null `employee.status` becomes `active`, and under Keycloak each employee's role subgroups are
imported into `employee_org_roles`, logging every row added. The backfill:

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
| Platform administrators recorded in Echno instead of the Keycloak role behind `hasRole('platform-admin')` | The provider knows nothing about roles. |
| Keycloak beans and settings conditional on `echno.identity.provider` | A stack with WorkOS selected must boot with no Keycloak settings at all. |
| `/auth/register` and self-service organization creation depend on the provider | Sign-up is off under WorkOS (3.4). |

## 6. Delivery

Each step is one PR into `auth-workos` in its repo, merged bottom-up with every check green and one
human approval. The backend comes first, then core, then web, then the mobile apps. The developer
guide changes each step needs are tracked in tornotron/echno-docs#20 and made in a docs PR when the
step merges. The [build plan](2026-11-07-workos-build-plan.md) has the contents and tests of each. In
short:

1. Prove the WorkOS facts the plan relies on against a sandbox.
2. Access module, tables with the project on each assignment, engine, guard.
3. The port with a fake, then the Keycloak adapter, then a backend that boots with no Keycloak.
4. Identity tables in place of `keycloak_id`.
5. Membership and org roles read from Echno (3.3), templates and backfill, one assignment writer with
   the mirror.
6. The WorkOS adapter, invitations by link, invite codes removed, the first-organization runner, the
   minimum app version check.
7. Core, then web's sign-in module, then web on WorkOS and its invitation screens. **The first production
   deployment can run from here.**
8. The screen's endpoints and the screen, then one area per PR with scope filtering, then the remaining
   role-name queries, then removal of the old role code.
9. Android and iOS behind their sign-in modules, then on WorkOS.

Both stagings stay on the Keycloak adapter throughout. `auth-workos` goes to `development` in one piece
once the migration is complete. Moving the stagings to WorkOS is a later project.

## 7. Waiting on the owner

Decided on the ticket: the nine construction roles (2.5), the levels (2.1), no groups (2.4), WorkOS
(3.1), the screen layout (2.3), invitations by link only (3.4) and roles held on a project (2.7).

Decided for the build: five working roles at go-live and the other four on the baseline only (2.5); Edit
covers Create (2.1); the administrator role is System Admin, held by the organization's first
administrator; no MFA rule at launch; the starting cells come from the permission mapping as corrected in
2.5, and stay template data so changing them needs no release.

Still open:

- The starting value of every cell for the nine roles (2.5).
- What "the enterprise domain" the invitation link leads to means: a sign-in page per organization,
  or a web address per organization like Atlassian's (3.4). WorkOS's docs show one sign-in address per
  environment and no per-organization page ([custom domains](https://workos.com/docs/custom-domains/authkit)),
  so the proposal is one address per client deployment.
- The starting grants for Management Director, Procurement Manager, Finance Manager and Client / Owner,
  which hold only the baseline until then (2.5), and where the four carried-over roles go.
