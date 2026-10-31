# Module tiers and independent modules

Status: proposed, for review. Target repos: `echno-backend`, `echno-core`, `echno-web`.
Scope: how every module declares whether it is core, optional and free, or optional and paid; how an
organization switches optional modules on and off; one gate for module endpoints; and how the 40
packages outside `modules/` become independent modules. Business rules marked **owner sign-off** are
proposals until the product owner approves them on the ticket.

It builds on [Modular plug-and-play module architecture](2026-08-26-modular-plugin-architecture.md),
which set up the manifest, the registry, per-module changelogs and the entitlement link, and keeps all
of that. It replaces two of that note's choices, where they differ:

- its non-goal "not a rewrite of the forty existing domain packages" and its section 10.3 (convert
  domains only when needed): this note moves every package that can be a module into one (section 6);
- its section 7 endpoint layer (`@RequireSubscription` as the module gate): this note gates module
  endpoints through the registry (section 4).

Tracked in #926 and its sub-issues, milestone **Backend Architecture Redesign**.

## 1. What changes and why

The platform cannot tell core features from optional ones, and cannot turn an optional one off
reliably:

- **Only 4 of about 45 areas are modules**: `inspections`, `bim`, `work-progress` and
  `toolbox-talks`. The other 40 packages (914 files) have no manifest. They are always on, have no
  kill switch, and billing cannot gate them.
- **A module's tier is implied, not declared.** A module with an `entitlementFeatureKey` is paid; one
  without a key and with `enabledByDefault` is free; one without a key and without
  `enabledByDefault` is off with no way to turn it on. There is no core tier.
- **In practice nothing is paid today.** All four modules have a feature key, but every seeded plan
  (`FREE`, `STARTER`, `PRO`, `ENTERPRISE`) grants it, so nobody went dark when they were introduced.
- **Two gates decide different things.** Endpoints are gated by `@RequireSubscription`, which asks
  billing only. Background jobs and the nav ask `ModuleRegistry.isEnabledForOrg`, which also checks the
  kill switch `echno.modules.<id>.enabled` and `dependsOn`. The kill switch is on the jobs only
  (`<Pascal>ModuleEnabled`), never on a controller. So a module switched off still answers its
  endpoints, and BIM's endpoints answer when inspections, which BIM depends on, is off.
- **Core reaches into optional code**, so no optional package can be switched off or carved out
  cleanly. Section 5 lists where.

The target:

- Every module, core included, has a manifest that **declares its tier**.
- An organization **chooses** which optional modules it uses, within what its plan allows.
- **One gate**, the registry, decides for endpoints, jobs and the nav alike.
- **Core never depends on an optional module**, so turning one off cannot break core.

## 2. Vocabulary

| Term | Meaning |
| --- | --- |
| Module | A package under `modules/<id>/` with exactly one `EchnoModule` manifest. Other code reaches it only through `modules/<id>/api`. |
| Platform | `common/`: tenancy, the module registry, storage, notifications, events, the access engine. Not a module. |
| Core module | A module the product cannot run without. On for every organization, never paid, no kill switch. |
| Optional module, free | Off or on per organization, by the organization's choice. No billing feature. |
| Optional module, paid | As free, but only an organization whose plan includes the module's billing feature may turn it on. |
| Entitled | The organization's plan includes the module's feature (paid), or the module is free or core. |
| Chosen | The organization has the optional module switched on. |

## 3. Tiers and per-organization choice

### 3.1 The manifest declares the tier

`ModuleManifest` gets one field, `tier`, with three values: `CORE`, `FREE`, `PAID`. It replaces the
meaning that `entitlementFeatureKey` and `enabledByDefault` carry together today. The boot fails when:

- a `CORE` module has a feature key, or depends on a module that is not `CORE`;
- a `PAID` module has no feature key, or a `FREE` module has one;
- a module depends on a module of a lower tier: `CORE` before `FREE` and `PAID`, and a `FREE` module
  may not depend on a `PAID` one, since it would then be paid in effect.

`enabledByDefault` changes meaning: for an optional module it is whether an organization has it
chosen until the organization says otherwise. It is ignored for `CORE`. The four current modules
change it to `true` (today it is `false`, as the module contract requires for a paid module), and
so does every module carved out in section 6, so every organization keeps what it has (3.2). The first attempt (#904) added a `core` boolean beside the
other two fields; this replaces it, so step 3 of the access plan declares the access module as
`CORE`.

### 3.2 An organization chooses its optional modules

A new platform table records the choice:

```
organization_module(organization_id, module_id, enabled, changed_by, changed_at)
```

- A missing row means the module's `enabledByDefault`. A row is written only when an organization
  changes its choice. So adding a module, or this table, needs no backfill: with `enabledByDefault`
  `true` on the four current modules and on every carved-out one (3.1), every existing and new
  organization has them chosen, and nobody loses anything when the tiers land.
- Without that change, a missing row would mean "not chosen" for the four current modules, and every
  organization would lose them the day M1 merges. `ModuleRegistryTest` pins it: an organization with
  no rows has every optional module with `enabledByDefault` chosen.
- Only a person with `org-admin` permission to manage modules may change it (an action in the access
  catalog, once the access matrix lands; `system-admin` until then).
- Each change is recorded with who and when.

### 3.3 When a module is on

`ModuleRegistry.isEnabledForOrg(id, organizationId)` is true when all of these hold:

1. the module is installed (compiled in);
2. it is `CORE`, or its kill switch is not `false`;
3. it is `CORE`, or the organization is entitled to it and has it chosen;
4. every module in its `dependsOn` is on for the same organization.

Turning a module off keeps its data. Turning it back on shows the same records.

Entitlement keeps following `echno.entitlement.mode`. In `advisory`, the default, a billing refusal
is only logged: `BillingModuleEntitlementResolver` asks `EntitlementPolicy.permits`, which allows it.
So condition 3 refuses for entitlement only in `enforce` mode, as `@RequireSubscription` does today.
The kill switch, the organization's choice and `dependsOn` are not billing and always apply.

Nothing changes for any organization when this lands: every kill switch is on, every module is
chosen (3.2), and BIM's dependency, inspections, is on every plan.

### 3.4 Lifecycle hooks

`EchnoModule.onEnable` and `onDisable` exist and are reserved; nothing calls them. They are called:

- `onEnable(organization)` when an organization chooses the module, and for every enabled module when
  an organization is created. A module seeds its starting data here (section 5.3), idempotently.
- `onDisable(organization)` when an organization switches it off. A module stops anything it runs for
  that organization; it does not delete data.

A plan change that removes a paid module's feature does not call `onDisable`; the module is simply
not entitled, and comes back with its data if the plan returns.

### 3.5 What clients see

- `GET /api/v1/modules/web` and `/enabled` carry `tier`, and for optional modules `entitled` and
  `chosen`, so web can show a module the organization could turn on.
- `PUT /api/v1/modules/web/{id}/choice` with `{ "enabled": true | false }` switches an optional module
  for the current organization. It refuses a `CORE` module, and turning on a paid module the plan
  does not include.
- Turning a module on that depends on one that is off is refused with the missing dependency named.
  Turning one off that another chosen module depends on is refused the same way.

## 4. One gate for module endpoints

The platform gate replaces the class-level `@RequireSubscription` on module controllers:

- A handler belongs to the module whose package (`modules/<id>/`) holds its controller. The gate
  resolves this once at boot into a map from handler to module id, so a request costs one lookup.
- For a handler in an optional module, the gate asks `isEnabledForOrg` for the request's organization.
  When the module is off it refuses with 403 and a stable code, `MODULE_DISABLED`, naming the module
  and why (not entitled, not chosen, switched off, or a dependency off). Core modules pass without a
  check.
- The gate runs before `@PreAuthorize`, so a person never learns what they could do in a module the
  organization does not have.
- `@RequireSubscription` stays for what only it does: quota features and `recordUsage = true` on
  metered endpoints. The module contract in the developer guide changes to match (section 9).
- The registry's answer is memoized per organization for the request. Today each
  `isEnabledForOrg` call, and each dependency it checks, can ask billing (`checkFeatureAccess`); the
  memo makes that once per module per request, and the organization's choices are read in one query.
  A choice or plan change evicts that organization's entry after commit.
- Entitlement follows `echno.entitlement.mode` (3.3), so in `advisory` the gate refuses only for the
  kill switch, the choice or a dependency.
- The gate is a `HandlerInterceptor` registered in the platform's MVC configuration, not in
  `SecurityConfig`, which the module contract keeps modules and their wiring out of.
  `GlobalExceptionHandler` maps its `ModuleDisabledException` to the 403.

Tests:

- every handler under `modules/<id>/` is covered by the gate (an architecture test like
  `EndpointAuthorizationTest`);
- for one module of each optional tier, the endpoints refuse when switched off, not chosen, not
  entitled, or when a dependency is off;
- switching inspections off makes BIM refuse.

## 5. What has to change for modules to be independent

### 5.1 The dependency rules

- A module may use the platform and core modules through their `api` package, and another optional
  module only through its `api` and when it is in `dependsOn`.
- **Core never depends on an optional module**, not even through its `api`. Today
  `ModuleBoundaryRuleTest.coreReachesModulesOnlyThroughApi` allows that; it becomes "core reaches no
  optional module". Where core needs something an optional module provides, core declares an
  interface and the module implements it (section 5.3).
- The platform (`common/`) depends on no module.
- Each rule is an ArchUnit rule in `ModuleBoundaryRuleTest`, with an allow list that only shrinks,
  so the rules can land first and the code catch up module by module.

### 5.2 Where the code breaks the rules today

Counted on `development` at `1845e1e5`:

| Problem | Where | Fix (sub-issue) |
| --- | --- | --- |
| The platform imports 17 domain packages | `common/entity/Attachment`, `common/events/*`, `common/customAnnotation/OpeningStockValidator`, `SiteTransferReceiptReconciler` use | Move to platform types or to the owning module's `api` (#922) |
| Notifications live in a domain package | `leave.NotificationService`, `NotificationDraft`, `NotificationType`, used by `material` and `documentReversal` | Move to `common/notification` (#922) |
| Core entities hold collections of optional records | `Employee` has 12 `@OneToMany`/`@ManyToMany` collections (purchase orders, site transfers, issues, materials, inventory transactions…); `User` 2, `Organization` 3, `Project` 4 | Remove; the owning module queries (#924) |
| Organization setup calls modules directly | `OrganizationOnboardingSeeder` calls the finance (`ChartOfAccountsSeeder`, `CostCategorySeeder`, `FinanceSettingsService`), leave and category seeders | Each module seeds itself in `onEnable` (#923) |
| Core uses an optional module's `api` | `project` uses `modules.inspections.api.IndianStateResolver` and `ElementTypeValidator` | Move the state resolver to the platform; inspections validates element types itself (#923) |
| Core computes from optional records | `ProjectProgressCalculator` reads tasks and WBS elements | A progress interface in core that modules implement (#923) |
| One feature spans 15 packages | `documentReversal` | A `ReversibleDocument` interface each module implements (#923) |
| Packages depend on each other in loops | employee ↔ material, project ↔ task, purchase order ↔ GRN ↔ payable ↔ vendor | Resolved by the fixes above and the module grouping in section 6 |

### 5.3 Extension points instead of direct calls

Where core needs an optional module to take part, core declares the interface in its `api` and each
module provides a bean. Core collects whatever beans exist, and calls only those of modules enabled
for the organization. No core code names a module. The three needed now:

- **Organization setup**: replaced by `onEnable` (3.4). `OrganizationOnboardingSeeder` keeps core's
  own seeding only.
- **Project progress**: `ProjectProgressContributor` returns a module's share of a project's
  progress; `project` combines them.
- **Document reversal**: `ReversibleDocument` per document type, so `documentReversal` holds the
  workflow and each module the reversal of its own records.

Events stay the way for a module to react to another's change: an `ApplicationEvent` record in the
publisher's `api` package, published after commit, as the earlier note set out.

### 5.4 What does not change

- Tables, columns and data. Moving a package does not touch the schema.
- Existing Liquibase changesets stay where they are, because Liquibase keys a changeset on its path. A
  carved-out module's new changesets go in `db/changelog/modules/<id>/`, and may continue the old
  numbering, as the module contract allows.
- API paths. A module keeps its paths when it moves; path changes are #916's.

## 6. Modules and their tiers

**Owner sign-off** for the tiers. The grouping follows the matrix areas in the access work, so a module
owns whole areas:

| Module | Packages | Matrix areas | Proposed tier |
| --- | --- | --- | --- |
| organization | `organization`, `projectInviteCode` (until invitations by link) | `org-admin` | CORE |
| people | `user`, `auth`, `employee` | `team` | CORE |
| projects | `project`, `category`, `wbs` | `projects`, `wbs` | CORE |
| billing | `billing` | `org-admin` | CORE |
| access | new, from the access work | `org-admin` | CORE |
| documents | attachments (from `common`), `pdfGeneration` | `documents` | CORE |
| search | `search` | none | CORE |
| tasks | `task`, `issue`, `IssueComment` | `tasks`, `issues` | FREE |
| chat | `chat` | `chat` | FREE |
| hr | `attendance`, `leave`, `holiday` | `team` (attendance), `leave`, `leave-regularisation` | FREE or PAID |
| inventory | `material`, `storageLocation`, `inventoryTransaction`, `stockAdjustment`, `siteTransfer`, `siteTransferItem`, `materialConsumption` | `inventory`, `storage-locations`, `material-consumption` | PAID |
| procurement | `indent`, `indentItem`, `purchaseOrder`, `purchaseOrderItem`, `vendor`, `goodsReceivedNote`, `grnItem`; depends on inventory | `indents`, `purchase-orders`, `vendors`, `goods-receipts` | PAID |
| finance | `finance`, `expense`, `payable`, `receipt` | `finance` | PAID |
| assets | `asset` | `assets` | PAID |
| construction | `subcontract`, `labour` | `construction` | PAID |
| risk | `risk` | `compliance` | PAID |
| inspections, bim, work-progress, toolbox-talks | already modules | `inspections`, `bim`, `progress`, `progress-bills`, `toolbox-talks` | PAID |

Notes:

- `keycloak` is not a module; it is the identity-provider adapter, which the access work moves behind
  the `IdentityProvider` port in the platform.
- `documentReversal` becomes part of the platform's workflow (5.3), not a module.
- A core module is still a module: it has a manifest, an `api` package and the boundary rules. It
  simply cannot be switched off.
- Every paid module's feature is seeded on every current plan, as the four are today, so nobody loses
  access when the tiers land. Taking a module out of a plan is a billing data change afterwards.

## 7. How this meets the access work

- The access module is `CORE`. Step 3 of the access plan (the access module and decision API) needs
  the tier, so #920 comes before it, in milestone M1 (section 8).
- The matrix follows the modules: an area belongs to the module that owns it, and the matrix screen
  and the access check treat the areas of a module that is off for the organization as unavailable.
- Access step 12 converts one area at a time. An area is converted after its module is carved out
  (#925), so each controller changes once. The order of #925 follows the order of step 12 where
  possible.

## 8. Delivery

The Backend Architecture Redesign (#926) has two streams of work: this note's (#919 to #925, with
#916 and #918) and the access matrix and identity provider (#914). Both go through `auth-workos`:

- **Each step is one PR into `auth-workos`**, merged bottom-up with every check green.
- **Each milestone is one PR from `auth-workos` into `development`.** A GitHub milestone of the
  same name collects the step PRs it carries. After it merges, `auth-workos` is reset to
  `development`.
- **Every milestone PR stays under 100 commits and 100 files**, the limits for a GitHub rebase merge
  and a CodeRabbit review (files counted after its path filters). A budget check on PRs into
  `auth-workos` warns at 80 and fails at 100, so a milestone closes before it reaches a limit.
- **Each milestone leaves `development` working**: nothing an organization uses changes until the
  step that changes it, and every module and permission stays as it is until its own step.

| Milestone | Steps it carries | Why it is safe to deploy on its own |
| --- | --- | --- |
| M1 Foundations | Access steps 1 and 2 (already on `auth-workos`); this note (#919); tiers and choice (#920); the module gate (#921); hyphenated paths (#916) | Every organization's modules are seeded as chosen; old paths stay as aliases |
| M2 Access engine | Access steps 3 to 5: access module (`CORE`), tables, engine | Nothing enforces with it yet |
| M3 Roles and extension points | Access steps 6 and 7: templates, backfill, the `@access` guard; platform pieces (#922); extension points (#923) | The guard guards nothing yet; seeding moves into `onEnable` with the same result |
| M4 Identity | Access steps 8 and 9: provider port, mirror, identity tables; core entities lose optional collections (#924) | The mirror keeps old and new checks in step |
| M5 Roles screen and invitations | Access steps 10 and 11 | Needs core and web released with it |
| M6 onward: modules and areas | One or a few modules from #925, each carved and then converted to the matrix (access step 12), with its lists moved to the paged shape (#918) | One module changes at a time; a large module (finance, hr) is a milestone of its own, or two |
| Then | Access steps 13 and 14 (removal), 15 and 16 (Echno tokens), 17 (WorkOS) | One milestone each |

Within M6 onward, the order is the least tangled module first: chat, risk, assets, construction,
tasks; then hr, inventory, procurement, finance; then the core modules. The boundary rules in 5.1
land with #921, with allow lists that each later PR shrinks.


### 8.1 What M1 changes in the registry

**#920, tiers and choice:**

| Where | Change |
| --- | --- |
| `ModuleManifest` | `tier` with the boot rules in 3.1; factory methods `core(...)`, `free(...)` and `paid(...)` replace the 8-argument constructor |
| `ModuleRegistry` | `isSwitchedOn` is always true for `CORE`; `isEnabledForOrg` adds "core, or entitled and chosen" (3.3); the boot rejects a core module depending on an optional one; the per-organization memo (section 4) |
| `organization_module` | Table, entity and repository, as platform code: its changeset in `db/changelog/`, not under `modules/` |
| `ModuleChoiceService` | Writes a choice, records who and when, calls `onEnable`/`onDisable`, evicts the memo after commit. Calling `onEnable` when an organization is created comes with #923 (M3) |
| `ModuleControllerWeb`, `ModuleDescriptorDto` | `tier` and `chosen` added; `PUT /api/v1/modules/web/{id}/choice` (3.5), guarded by `system-admin` until the access module exists; OpenAPI snapshot regenerated |
| The four manifests | Move to `paid(...)` with `enabledByDefault` `true` (3.1) |
| Test fixtures (`echno_modulefixtures`), `ModuleRegistryTest`, each `<Pascal>ModuleTest` | Move to the factory methods; one test per tier rule; the no-rows test in 3.2 |
| Scaffold templates (`scripts/scaffold/templates/backend/`) | Generate `paid(...)`, so the `scaffold-check` job keeps passing |

**#921, the gate:**

| Where | Change |
| --- | --- |
| `ModuleGateInterceptor` in `common/module/web` | Handler-to-module map at boot; refuses with 403 `MODULE_DISABLED` before `@PreAuthorize` (section 4) |
| `GlobalExceptionHandler` | Maps `ModuleDisabledException` |
| Module controllers | Class-level `@RequireSubscription` removed (18 uses in the four modules); kept only with `recordUsage`. The scaffold's controller templates too |
| `ModuleBoundaryRuleTest` | "Core reaches no optional module" and "the platform reaches no module" (5.1), with allow lists that only shrink; the first lists `project` to `inspections.api` |
| A new architecture test | Every handler under `modules/<id>/` is covered by the gate |

Not in M1: `NavRegistry` and `PermissionRegistry` are unchanged. The 40 legacy packages have no
manifest yet, so the gate does not touch them and they stay on until they are carved out (#925).
No core manifest exists until the access module in M2, so the core tier is tested through fixtures.

## 9. Other repos

- **echno-core**: `module-service.ts` parses `tier`, `entitled` and `chosen`; a service call and hook
  for the choice endpoint. Additive, so a minor version.
- **echno-web**: a settings screen under organization settings listing the optional modules, with a
  switch for each and an upgrade prompt for a paid module the plan lacks (**owner sign-off**);
  `ModuleGuard` and the nav follow `/enabled`, as today. The `MODULE_DISABLED` error shows a clear
  message rather than a generic 403.
- **echno-android** and **echno-ios**: no change now. They do not read the module list; when they do,
  they read `/enabled`.
- **echno-docs**: the module contract page changes: the manifest's `tier` field, the gate replacing
  class-level `@RequireSubscription`, and the core-never-depends-on-optional rule. Tracked in
  tornotron/echno-docs#21 and made in a docs PR when #920 and #921 merge.

## 10. Waiting on the owner

1. Which modules are core, optional free and optional paid (section 6).
2. May an organization switch off a free module, or are free modules always on?
3. Is a paid module an organization's plan lacks hidden, or shown with an upgrade prompt?
4. Do all current plans keep the four current modules, and every carved-out paid module, as proposed
   in section 6?
