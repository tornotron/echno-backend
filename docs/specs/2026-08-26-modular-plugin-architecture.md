# Modular plug-and-play module architecture for Echno

Status: draft for review. Target repos: `echno-backend`, `echno-core`, `echno-web`.
Scope: a design, not an implementation. No code has changed. This document is meant to be PR'd into
`echno-backend/docs/specs/` once agreed, and its frontend half cross-referenced from `echno-web/docs`.

## 1. Motivation

Everything Echno does today ships as one deployable: a Spring Boot backend with roughly forty domain
packages, one Next.js app with one feature folder per domain, and the `@tornotron/echno-core` shared
library that binds them. Every new capability lands by editing that shared codebase. Three costs follow
from this:

1. A new enhancement cannot be built, reviewed, released, or priced on its own. It is entangled with
   the core at compile time and at deploy time.
2. Delegating a whole capability to one developer is hard, because the work touches shared wiring
   (route registration, navigation, the security config, the Liquibase master changelog) that other
   people are also editing.
3. There is already a subscription and feature-entitlement gate in the backend
   (`SubscriptionService.checkFeatureAccess`, the `@RequireSubscription` aspect, `Feature` / `Plan` /
   `PlanFeature` / `Subscription`), but nothing above the level of a single endpoint annotation ties a
   whole capability to an entitlement. There is no notion of a "module" that is on or off for an org.

The vision (Abhijith, 2026-08-10) is to freeze the current capabilities as the **core** of Echno and
add a **skeleton** so that new enhancements arrive as modular, plug-and-play, on/off features that:

- are added without editing the core codebase,
- are each entitlement and paywall gateable through the existing subscription gate,
- are easy to hand to one developer end to end.

This document defines that skeleton concretely for the Echno stack.

## 2. Goals and non-goals

### Goals

- A **module contract** on the backend: a module can register REST routes, JPA entities, Liquibase
  migrations, domain services, permissions, navigation, and scheduled jobs without any core file being
  edited to name that module.
- A **module manifest and runtime registry**: each module declares stable metadata (id, version,
  entitlement key, dependencies, extension points); the registry exposes the set of installed and
  enabled modules to the API and, through it, to the web app.
- **Runtime on/off** wired to the existing entitlement gate, per organization, so a module can be dark
  for one tenant and live for another on the same running instance.
- A **frontend module loader** in `echno-web` that renders a module's routes, navigation, and pages
  only when the module is enabled and the current org is entitled, and that fits the existing
  `features/` folders and the `eslint-plugin-boundaries` dependency graph.
- A **published contract in `echno-core`** so a module's types, DTOs, API client, and hooks are
  consumed by the web app the same way core domains are consumed today.
- An **evolutionary migration path**: carve one existing capability out as the reference module, keep
  everything else as-is, and convert further capabilities one at a time when there is a reason to.

### Non-goals

- **Not** dynamic classloading, OSGi, a hot-deploy plugin jar dropped into a running JVM, or a
  marketplace of third-party binaries. Section 4 argues why.
- **Not** a microservice split. Modules stay inside the one Spring Boot process and the one Next.js
  app. This is a modular monolith, not a distributed system.
- **Not** a rewrite of the forty existing domain packages. The core stays exactly where it is; the
  skeleton is added around it, and migration is opt-in per capability.
- **Not** a change to the tenancy model, the auth model, or the deployment topology. Modules must
  respect the org-filter and Keycloak/JWT that already exist; they do not get to invent their own.

## 3. What "module" and "plug-and-play" mean here

A **module** is a self-contained vertical slice of product capability that owns its own routes,
persistence, services, permissions, navigation, and jobs, and that is described by a manifest. It is a
unit of delegation (one developer can own it), a unit of entitlement (one `Feature` code gates it), and
a unit of on/off (the registry can enable or disable it). Concretely a backend module is a Java package
(or a Gradle subproject) plus a manifest bean; a frontend module is a `features/<id>` folder plus a
`module.config.ts`; a shared module is a subpath of `@tornotron/echno-core`.

**Plug-and-play** means two distinct things that must not be conflated:

- **Build-time pluggable**: adding a module means adding a package or subproject and its manifest. No
  core file is edited to *name* the module. The core discovers it. This is what we build.
- **Runtime on/off**: a module that is present in the build can be turned on or off per organization at
  runtime, gated on entitlement, with no redeploy. This is also what we build.

Plug-and-play does **not** mean a module can be installed into a running server without a build. That is
dynamic classloading, and Section 4 rejects it for now.

## 4. The core tradeoff: compile-time modular monolith with runtime on/off

The central decision is whether a "module" is a compile-time artifact (a package or Gradle subproject
built into the one deployable) or a runtime artifact (a jar loaded into a live JVM through a custom
classloader, OSGi, or the Java module layer API).

**Recommendation: compile-time modular monolith, with runtime on/off gating.** A module is compiled
into the single Echno deployable. What flips at runtime is whether the module's routes, jobs, and
navigation are *active for a given org*, not whether its code is *present in the JVM*.

Reasons, specific to this stack:

- **Multi-tenancy is enforced by a Hibernate filter that depends on entity mappings being known to the
  one `EntityManagerFactory`.** `HibernateFilterConfig` enables `orgFilter` inside every
  `@Transactional` method within `org.tornotron.echno_backend..*`. A dynamically loaded entity would
  either miss this aspect or need its own session factory, which breaks the single-filter guarantee and
  reopens the exact tenant-isolation hazard that #330 closed. A compiled-in module is scanned into the
  same `EntityManagerFactory` and inherits the filter for free.
- **Liquibase runs once at boot against one schema.** A runtime-installed module would need online
  schema change against CockroachDB from a live process, with no coordinated rollback. Compile-time
  modules contribute changelog fragments that run in the normal boot migration, which is already how
  the version-context strategy in `db.changelog-master.xml` works.
- **The entitlement gate already gives us runtime on/off without classloading.** `checkFeatureAccess`
  returns no-sub / not-in-plan / on-off / quota per org. That is the runtime lever. We do not need a
  classloader to make a module dark for a tenant; we need the registry to consult the gate.
- **Security review and operational simplicity.** A custom classloader loading third-party jars into a
  process that holds the CockroachDB credentials, the Keycloak admin client, and the DO Spaces keys is
  a large attack surface for no current benefit. All module authors are our own developers (Abin,
  Hrishi, and whoever a module is delegated to). We are optimising for internal delegation and
  per-tenant pricing, not a third-party plugin market.
- **Deployment is already image-per-commit on GHCR.** Rebuilding and redeploying to add a *new* module
  is cheap and is already the normal path. We do not gain much from avoiding a rebuild, and we lose the
  build-time safety net (compilation, boundary lint, tests) if we allow runtime install.

**When to revisit.** If Echno ever sells a third-party plugin marketplace, or needs a partner to ship a
closed module we cannot compile, revisit with the Java module layer (`ModuleLayer` / `ServiceLoader`)
or a sanctioned OSGi runtime, and pay the tenancy and migration costs deliberately. Until then, the
compile-time model gives every plug-and-play property the vision asks for at a fraction of the risk.

**Packaging within the compile-time model: package vs Gradle subproject.** Two sub-options:

- *Same-jar packages* (recommended for phase 1): a module is a package under
  `org.tornotron.echno_backend.modules.<id>`, compiled into the one jar. Simplest, zero build-system
  change, and enough to get isolation through Spring Modulith's `ApplicationModules` verification.
- *Gradle subprojects* (recommended once there are several modules): each module is its own Gradle
  subproject (`:modules:inspections`) that depends on a `:module-api` project but not on core internals,
  with core depending on the module projects at assembly time only. This makes "cannot import core
  internals" a compile error, not a lint rule, and lets a module be built and tested in isolation. It
  is a natural phase-2 hardening once the same-jar version proves the contract.

Adopt Spring Modulith (`spring-modulith-core`, `spring-modulith-starter-test`) from day one regardless
of the packaging sub-option. Its `ApplicationModules.of(...).verify()` test fails the build when a
module reaches into another module's internals, which is the guard rail that keeps modules honest while
they still live in one jar.

## 5. Backend module contract

A module is a Spring component set discovered by the core, described by one manifest bean, and wired
into five extension surfaces: routes, persistence + migrations, services, permissions + navigation, and
scheduled jobs. Nothing in core is edited to name the module.

### 5.1 Discovery: the module lives in its own package and is component-scanned

Modules live under a dedicated base package, `org.tornotron.echno_backend.modules`, which is already
inside the `EchnoBackendApplication` component-scan root and inside the `within(...echno_backend..*)`
pointcut that enables the org-filter. A module contributes:

- `@RestController` classes, discovered by the existing MVC mapping. No central route table is edited.
  The twin-controller convention (`XxxController` for the mobile `/api/v1` surface, `XxxControllerWeb`
  for the web BFF surface) is preserved by modules, so nothing about how the two clients call the
  backend changes.
- `@Entity` classes and Spring Data repositories, discovered by the same entity scan and Spring Data
  scan that finds core entities.
- `@Service` beans, discovered by component scan.

The one thing a module *must* declare that a plain package would not is its **manifest**, so the
registry knows the module exists as a first-class thing and can gate it.

### 5.2 The module SPI: `EchnoModule`

Each module provides exactly one `@Component` implementing the module SPI. This is the single required
piece of boilerplate.

```java
public interface EchnoModule {
    ModuleManifest manifest();

    // Optional lifecycle hooks, default no-op:
    default void onEnable(Organization org) {}
    default void onDisable(Organization org) {}
    default void registerNavigation(NavRegistry nav) {}
    default void registerPermissions(PermissionRegistry perms) {}
}
```

`ModuleManifest` is the metadata described in Section 6. Core defines `EchnoModule`, `ModuleManifest`,
`NavRegistry`, and `PermissionRegistry` in a small `common/module` package (or, in the subproject
variant, in a `:module-api` project). A module depends only on this SPI and on domain-neutral shared
code, never on another module's internals. Spring auto-collects every `EchnoModule` bean by injecting
`List<EchnoModule>` into the registry, so adding a module is: create the package, write the manifest
bean. No registration list is edited.

### 5.3 The registry bean

```java
@Component
public class ModuleRegistry {
    public ModuleRegistry(List<EchnoModule> modules) { ... }   // auto-collected

    Collection<ModuleManifest> installed();                    // present in the build
    boolean isEnabledForOrg(String moduleId, Long orgId);      // installed AND entitled
    Collection<ModuleManifest> enabledForOrg(Long orgId);
}
```

`installed()` is fixed at boot (what was compiled in). `isEnabledForOrg` is the runtime lever: it
returns true only when the module is installed, its `enabledByDefault`/ops flag is on, and the org is
entitled per the entitlement gate (Section 7). The registry validates dependencies at boot (Section 6)
and fails fast if a module declares a dependency that is not installed.

### 5.4 Persistence and per-module migrations

Modules own their tables and their migrations. Two rules keep this clean:

- **Every module entity is tenant-scoped the same way core is.** It implements `TenantScopedEntity`,
  carries `@Filter(name = "orgFilter", condition = "organization_id = :organizationId")`, and maps an
  `organization_id` column. Because the module package is inside the filter pointcut, `orgFilter` is
  enabled on its queries automatically. This is non-negotiable: a module that skips it is a
  cross-tenant leak. The Modulith verification test plus a custom ArchUnit rule ("every `@Entity` under
  `modules..` implements `TenantScopedEntity`") enforce it mechanically.
- **Each module ships its own Liquibase changelog fragment**, and the master includes module
  changelogs by convention, not by hand-editing an include per changeset. Add one line per module to
  `db.changelog-master.xml`:

  ```xml
  <includeAll path="db/changelog/modules/inspections/" />
  ```

  or, better, a single `<includeAll path="db/changelog/modules/"/>` that recursively pulls every
  module's changelog directory. `includeAll` runs files in a deterministic order, so a module author
  adds `db/changelog/modules/<id>/001-*.xml` and it runs at the next boot with no master edit. Module
  changesets carry **no `context`** so they run regardless of which baseline context
  (`v1`..`v4`) provisioned the core schema, exactly as the master's "future migrations" note already
  prescribes. Module changeset ids are namespaced (`inspections-001`) to avoid collisions in
  `DATABASECHANGELOG`.

  Note on disabled modules: a module's tables are created by migration at boot whether or not any org
  has it enabled. On/off is a data-visibility and routing concern, not a schema concern. Creating empty
  tables for a globally unused module is harmless and keeps enable/disable instant.

### 5.5 Routes, services, and the tenancy invariant

Nothing special is needed for routes and services beyond component scan. The important invariant is
that a module's controllers and services run inside the same request pipeline as core: the same
`JwtAuthConverter` authorities, the same `TenantContext` org resolution, the same `@Transactional`
org-filter activation. A module does not build its own security or its own tenant resolution. It
consumes `UserContextService` / `TenantContext` like core does.

### 5.6 Permissions and navigation registration

A module declares its permissions and nav entries through the SPI hooks rather than by editing core
enums or the web nav tree from the backend:

- `registerPermissions(PermissionRegistry)` contributes permission keys (for example
  `inspections:view`, `inspections:manage`) that the module's own `@PreAuthorize` /
  `@orgSecurity.hasAnyOrgRoleForCurrentTenant(...)` checks reference. These keys are surfaced in the
  module descriptor the API returns (Section 6), so the web app and the entitlement UI can see them
  without hard-coding.
- `registerNavigation(NavRegistry)` contributes the module's nav intent (label, section, icon key,
  route path, required permission). The backend does not render nav; it publishes nav *descriptors*.
  The web loader (Section 9) is the authority on rendering, and it maps a descriptor onto the existing
  `nav/` platform. This keeps the source of truth for "what nav a module wants" next to the module,
  while leaving the rendering to the existing filesystem-driven nav system.

### 5.7 Scheduled jobs

Modules may contribute `@Scheduled` methods, but an always-firing `@Scheduled` ignores on/off and
tenancy. Two rules:

- A module's scheduled bean is guarded by `@ConditionalOnProperty("echno.modules.<id>.enabled")` at the
  installed level, so an operator can hard-disable the whole module's jobs from config without a code
  change.
- Inside the job, work is done per entitled org: the job asks the registry for
  `enabledForOrg`-holding organizations and runs its unit of work inside a tenant-bound context per
  org (setting `TenantContext` so the org-filter applies), rather than running one global unbound pass.
  This mirrors how any multi-tenant batch must behave and prevents a module job from operating on orgs
  that are not entitled. The compliance background job is the existing precedent to follow.

### 5.8 Runtime on/off wiring

There are two distinct switches, and both must exist:

1. **Installed on/off (operator, coarse)**: `echno.modules.<id>.enabled` config property, read by
   `@ConditionalOnProperty` on the module's controllers/jobs where a hard kill switch is wanted. This
   is for incident response and for builds that intentionally exclude a module. Default true when the
   module is compiled in.
2. **Entitlement on/off (per org, fine)**: the registry's `isEnabledForOrg` consults the entitlement
   gate. This is the normal, per-tenant, paywall-driven switch, and it is the one the product uses.

The clean separation matters: operators kill a misbehaving module globally with config; product and
billing turn a healthy module on and off per customer through entitlement, with no redeploy.

## 6. Module manifest and registry

### 6.1 Manifest fields

The manifest is the module's identity and its contract with the registry and the billing system.

| Field | Meaning |
|-------|---------|
| `id` | Stable machine id, e.g. `inspections`. Namespaces packages, nav, permissions, changelog, entitlement key. Never changes. |
| `name` | Human label, e.g. "Site Inspections". |
| `version` | Module semver, independent of the app version. Surfaced for support and for the descriptor. |
| `entitlementFeatureKey` | The `Feature` code in the billing tables that gates this module (e.g. `MODULE_INSPECTIONS`). The single link between a module and the paywall. |
| `dependsOn` | Other module ids that must be installed and enabled for this one to function. Validated at boot and at enable time. |
| `extensionPoints` | Named hooks the module *exposes* for others to extend, and hooks it *consumes*. Phase 1 keeps this to declared Spring `ApplicationEvent`s it publishes and listens to (the codebase already uses domain events + listeners), so inter-module contact is event-based and does not create hard code coupling. |
| `permissions` | The permission keys the module defines (mirrors `registerPermissions`). |
| `navDescriptors` | The nav intents (mirrors `registerNavigation`). |
| `enabledByDefault` | Whether a fresh install treats the module as on before any explicit entitlement decision. Usually false for paid modules. |

### 6.2 The relationship to the existing billing tables

No new billing concept is invented. A module's `entitlementFeatureKey` is a row in the existing
`Feature` table. Whether an org has it is exactly `checkFeatureAccess(userId, key)`, which already
returns no-sub / not-in-plan / on-off / quota. A `Plan` includes the module by having a `PlanFeature`
for that feature; a paywall is a plan that omits it; a quota-limited module (for example, N AI reports
per month) uses the `quota` feature type and `recordUsage`, which `@RequireSubscription(recordUsage =
true)` already supports. The module skeleton adds the *concept of a module* on top of features; it does
not replace the feature machinery.

Seeding: each module contributes a small idempotent seed fragment that ensures its `Feature` row exists
(id, key, type). This fits the existing Ansible `seed` role model, so enabling a module for a plan is a
billing-data operation, not a schema migration.

### 6.3 The runtime descriptor endpoint

The registry is exposed to clients through one endpoint, for example:

```
GET /api/v1/modules            -> installed modules + version + declared permissions/nav
GET /api/v1/modules/enabled    -> modules enabled AND entitled for the caller's org
```

`/enabled` is the contract the web app consumes on session bootstrap. It returns, per enabled module,
the id, the nav descriptors, and the permission keys the current user actually holds for that module.
The web loader turns that into rendered nav and route guards. Because the backend is the authority on
both entitlement and the user's org authorities, the frontend never has to guess whether a module is
live; it asks.

## 7. Entitlement integration in detail

The module is gated at three layers, defence in depth:

1. **Registry layer**: `isEnabledForOrg` is false unless entitled, so `/api/v1/modules/enabled` omits
   the module. The UI never offers it.
2. **Endpoint layer**: every module controller method that must be paid for carries
   `@RequireSubscription(feature = "MODULE_INSPECTIONS")`. This is the existing aspect, unchanged. Even
   if a client calls a module URL directly, the aspect denies it with the standard
   `SubscriptionAccessDeniedException` and the structured `FeatureAccessResultDto` reason. This is the
   backstop that makes the paywall real rather than cosmetic.
3. **Data layer**: the org-filter guarantees a module can only ever read and write the caller's org's
   rows, so even a bug in the first two layers cannot cross tenants.

Recommendation: provide a class-level convenience so a module does not annotate every method.
`@RequireSubscription` already targets `TYPE` as well as `METHOD`; a module's controllers can carry it
at class level with the module's feature key, and the aspect applies it to every handler. Method-level
annotations remain available for quota-metered endpoints that also want `recordUsage`.

## 8. echno-core contract

A module's shared surface is packaged as a subpath of `@tornotron/echno-core`, exactly like a core
domain. The package already uses conditional subpath exports keyed by domain:

```
@tornotron/echno-core/<domain>/types
@tornotron/echno-core/<domain>/services
@tornotron/echno-core/<domain>/hooks
@tornotron/echno-core/<domain>/hooks/keys
```

A module named `inspections` publishes `inspections/types` (domain types, DTOs, Zod schemas),
`inspections/services` (the typed API client for the module's endpoints), and `inspections/hooks` (the
TanStack Query hooks and query keys). Nothing new is needed in the export machinery; the module's files
live under `src/types/inspections`, `src/services/inspections-service.ts`, `src/hooks/inspections`, and
they are picked up by the existing wildcard exports.

Two additions specific to modules:

- **A module-descriptor type** in core (`ModuleDescriptor`, `ModuleId`, `ModuleNavDescriptor`) shared
  by backend-shaped responses and the web loader, so `/api/v1/modules/enabled` has a typed client.
- **The core version is the module's compatibility handle.** A module's backend, its core subpath, and
  its web feature are released together against a core version. `echno-core` stays the single source of
  DTO truth so a module cannot drift from the backend contract. Publishing remains the existing
  release-triggered `publish.yml` to GitHub Packages.

Because everything a module needs to be consumed is already expressible as core subpaths, a module does
**not** become its own npm package in phase 1. Keeping module types inside `echno-core` avoids a second
publishing pipeline and keeps the one-version-binds-everything guarantee. Splitting a very large module
into its own package is a later option, not a starting requirement.

## 9. Frontend module loader

The web app already has the pieces a module loader needs: a `features/<id>` folder per domain, an
enforced `app -> features -> shared -> lib -> types` boundary via `eslint-plugin-boundaries`, and a
filesystem-driven `nav/` platform with per-route `AccessConfig`, sections, and a `canAccess` evaluator
that filters the nav tree by role and permission. The loader extends this, it does not replace it.

### 9.1 A module is a feature folder plus a config

A frontend module is `features/<id>/` (its components, exactly as today) plus a `features/<id>/module.config.ts`:

```ts
export const inspectionsModule: ModuleConfig = {
  id: 'inspections',
  entitlementFeatureKey: 'MODULE_INSPECTIONS',
  // nav + routes this module contributes, referencing the module's own pages
  nav: [{ label: 'Inspections', section: 'inspections', path: '/users/dashboard/inspections',
          access: { permissions: ['inspections:view'] }, icon: 'ClipboardCheck' }],
};
```

This config sits *inside* the feature folder, so it obeys the existing boundary rule: `app` may import
from `features`, but a module's config imports only `shared`, `lib`, `types`. The module does not reach
into the core nav internals; it declares intent and the loader composes it.

### 9.2 Enabled-set gating, from the backend descriptor

On session bootstrap the app calls `/api/v1/modules/enabled` (typed via the core `ModuleDescriptor`)
and holds the enabled-and-entitled module id set. The loader composes the nav from two sources: the
existing core nav for core domains, and each enabled module's `nav` descriptors. A module whose id is
not in the enabled set contributes nothing: its nav entries are absent and its routes are guarded.

This is deliberately backend-driven. The frontend does not decide entitlement; the backend descriptor
is the truth, and the frontend renders it. The existing `canAccess` role/permission evaluator still runs
on top, so a module can be entitled for the org yet hidden for a user who lacks its permission. The two
gates compose: entitlement (org bought it) AND access (this user may use it).

### 9.3 Routes

Module pages live under the Next.js `app/` router as they do now (for example
`app/users/dashboard/inspections/...`). Because on/off is data-driven rather than a build artifact, a
route that exists in the bundle but is not enabled for the org must guard itself: a small server-side
check in the route segment (or a shared `ModuleGuard` layout) reads the enabled set and renders the
standard access-denied surface for a disabled or unentitled module, rather than a broken page. This
mirrors the backend's endpoint backstop: the nav hides it, and the route refuses it.

### 9.4 Fit with boundaries

No boundary rule changes. A module's `features/<id>` folder may import `shadcn`, `shared`, `lib`,
`types`, and its own feature subtree, exactly as the existing `from: features` rules allow. The loader
and the module-config type live in `shared`/`lib`, which every feature may already import. This means a
new module introduces zero eslint-boundaries exceptions, which is the sign it fits the existing
architecture rather than fighting it.

## 10. Migration path: carve the first module, keep everything else

This is evolutionary. The forty core domains are not touched. We add the skeleton, then move exactly
one existing capability onto it as the reference implementation, then convert further capabilities only
when there is a reason (a new paid feature, a delegation, a rewrite that was going to happen anyway).

### 10.1 Reference-module candidate: Inspections

Recommended first module: **Inspections** (with Compliance as the closely related second, since
Compliance already extends Inspection and is already an entitlement-style add-on).

Why Inspections rather than Chat:

- It is already a bounded vertical (`inspection` backend package, `features/inspections` web folder,
  inspection types in core) with relatively few inbound dependencies from other domains, so extracting
  it does not ripple through finance, projects, or procurement.
- The **Compliance module (#36)** is already built as an add-on on top of Inspection, already uses the
  Anthropic API and a background job, and is already conceptually "a feature you activate." It is the
  natural proof that the module + entitlement + scheduled-job contract works end to end, because it
  exercises every extension surface: entities, migrations, a paid feature key, a background job, and
  curated seed data.
- Chat is a worse first candidate: it holds a realtime SSE surface, a heartbeat scheduled component,
  and cross-cutting presence concerns, so it stresses the transport layer more than the module contract.
  It is a good *second or third* conversion once the contract is proven, not the reference.

### 10.2 Steps for the reference module (no behaviour change)

1. Add the `common/module` SPI (`EchnoModule`, `ModuleManifest`, registry, nav/permission registries)
   and the `/api/v1/modules` endpoints. Adopt Spring Modulith and add the verification test. This is
   pure addition; nothing existing changes.
2. Move `inspection` (and its `compliance` extension) under `modules/inspections`, keeping package
   moves mechanical. Add the `EchnoModule` manifest bean with `entitlementFeatureKey =
   MODULE_INSPECTIONS`. Relocate its changelog fragments under `db/changelog/modules/inspections/` and
   switch the master to `includeAll` for the modules directory. Verify `DATABASECHANGELOG` still
   matches on the moved changesets (use the original changeset `id`/`author` so Liquibase treats them as
   already-run, not new).
3. Add the `Feature` row (`MODULE_INSPECTIONS`) to the seed and to the plans that should include it.
   Everything that was previously always-on becomes gated; make the default plan include it so no
   existing tenant loses the capability on cutover.
4. On the web side, add `features/inspections/module.config.ts` and the enabled-set loader, and make
   the inspections nav come from the descriptor rather than being unconditionally present.
5. Verify with the Modulith test, the tenant-isolation tests, and a login smoke test on `echno.in`
   that inspections still works for an entitled org and is dark for an org whose plan omits the feature.

### 10.3 The incremental path afterwards

- New capabilities are born as modules. That is the payoff: the next enhancement is delegated to one
  developer as `modules/<id>` + core subpath + `features/<id>`, gated by one feature key, with no core
  edit.
- Existing domains are converted opportunistically, not in a big bang. A domain becomes a module when
  it is about to be monetised separately, handed to a new owner, or substantially reworked. Domains
  that are pure core (organizations, users, billing, the tenancy infrastructure itself) stay core
  forever; they are the skeleton, not modules on it.
- The Gradle-subproject hardening (Section 4) is adopted once there are three or four modules and the
  same-jar boundary lint is no longer felt to be enough.

## 11. Security

- **Tenancy is the top risk.** A module that forgets `TenantScopedEntity` + `@Filter`, or that runs a
  query outside a `@Transactional` boundary (so the filter aspect never fires), is a cross-tenant leak.
  Mitigations: the ArchUnit rule that every `modules..` `@Entity` implements `TenantScopedEntity`; the
  Modulith verification test; and a required tenant-isolation integration test per module (the same
  fail-closed pattern #330 established) as a merge gate.
- **The paywall must be enforced server-side, never only in nav.** The endpoint-level
  `@RequireSubscription` backstop (Section 7) is mandatory; hiding a module in the UI is not a security
  control.
- **Modules inherit auth, they do not define it.** A module uses the existing `JwtAuthConverter`
  authorities and `@orgSecurity` checks. A module may not add its own authentication, its own token
  handling, or its own CORS. Reviews reject any module that tries.
- **No secret sprawl.** A module that needs a secret (an API key, an object-store path) declares it in
  config and receives it through the existing config/secret mechanism; it does not read credentials
  directly or ship its own. The compliance module's Anthropic key is the pattern.
- **No runtime code loading** (Section 4) keeps the process attack surface unchanged from today.

## 12. Testing

- **Modulith boundary test**: `ApplicationModules.of(EchnoBackendApplication.class).verify()` fails the
  build on illegal inter-module access. One test, high leverage.
- **Per-module tenant-isolation test** (merge gate): assert a module's entities are org-filtered and
  that a cross-org read returns nothing, using the existing Testcontainers + CockroachDB harness.
- **Entitlement tests**: a module endpoint returns the standard denial for an unentitled org and
  succeeds for an entitled one; a quota-metered endpoint records usage and blocks past quota.
- **Registry tests**: dependency validation fails fast at boot on a missing `dependsOn`; `enabledForOrg`
  reflects entitlement.
- **Migration test**: booting against a fresh schema runs the module `includeAll` fragment; booting
  against an existing schema with the moved changesets does not re-run them.
- **Frontend**: the loader hides a module absent from the enabled set; a route guard denies a disabled
  module; `canAccess` still hides an entitled module from a user without its permission.

## 13. Versioning and release

- **App version** (the deployable) advances on every merge as today. It is the set of *installed*
  modules.
- **Module version** is semver in the manifest, independent of the app, for support and the descriptor.
- **`echno-core` version binds the contract**: a module's backend DTOs, its core subpath, and its web
  feature are released against one core version. This is the single compatibility handle and it already
  exists.
- **Enabling a module for a customer is a billing-data change** (a `PlanFeature` row), not a release.
  This is the operational win: pricing and packaging move without shipping code.

## 14. Developer workflow: adding a module

For a developer handed a new module end to end:

1. **Backend**: create `org.tornotron.echno_backend.modules.<id>`; add entities (each
   `TenantScopedEntity` + `@Filter`), repositories, services, and the twin controllers; add the
   `@RequireSubscription(feature = "MODULE_<ID>")` gate; write the `EchnoModule` manifest bean; add
   `db/changelog/modules/<id>/001-*.xml`; register nav and permissions through the SPI hooks; add the
   `Feature` seed row.
2. **Core**: add `src/types/<id>`, `src/services/<id>-service.ts`, `src/hooks/<id>` in `echno-core`;
   the existing wildcard exports publish them as `@tornotron/echno-core/<id>/{types,services,hooks}`.
3. **Web**: create `features/<id>/` with the module's components and `module.config.ts`; add the
   `app/users/dashboard/<id>/...` routes with the module guard; the loader picks the module up when the
   backend reports it enabled.
4. **Billing**: add the module's `Feature` to the plans that should include it.
5. **Verify**: Modulith test, tenant-isolation test, entitlement test, login smoke test on `echno.in`.

No step edits a shared registration list, a central route table, the security config, or another
module. That is the delegation property the vision asked for.

## 15. Open questions and risks

- **Cross-module dependencies beyond events.** Phase 1 keeps inter-module contact to Spring
  `ApplicationEvent`s (already used) plus declared `dependsOn`. If a module genuinely needs to *call*
  another module's service synchronously, we need a sanctioned exposed-service interface in the
  `dependsOn` target's public API package rather than an ad-hoc import. Decide the exposed-API
  convention before the second module needs it.
- **Enable/disable side effects.** Turning a module off leaves its rows in place (schema is not
  dropped). Is "disabled" purely hidden, or must some modules quiesce jobs and revoke tokens on
  disable? The `onDisable(Organization)` hook exists for this; per-module policy is a decision.
- **Migration ordering across modules.** `includeAll` orders files deterministically within a module,
  but two modules that both touch a shared core table in a migration could race. Rule of thumb: a
  module migration may only create or alter that module's own tables, never a core table. Enforce by
  review, and reconsider if a real cross-cutting migration appears.
- **Same-jar vs subproject timing.** Starting same-jar is right, but the boundary is only lint-enforced
  until the subproject split. Agree the trigger (module count, or the first time a boundary violation
  slips through review) so the split is not deferred indefinitely.
- **k8s staging.** The registry and descriptor endpoint behave identically on the compose monolith and
  the k8s staging, since nothing here is deployment-specific. Confirm the modules descriptor is not
  cached across orgs in any edge/proxy layer (it is per-org and must not be shared).
- **Entitlement source of truth for the frontend.** The design has the frontend trust
  `/api/v1/modules/enabled`. Confirm that endpoint is cheap enough to call on every session bootstrap,
  or cache it per session with an invalidation on plan change.

## 16. Summary of decisions

1. **Compile-time modular monolith with runtime, per-org on/off.** Not dynamic classloading. The
   tenancy filter, single-schema Liquibase, and existing entitlement gate all make the compile-time
   model the correct and lower-risk target.
2. **A module is a package (later a Gradle subproject) with one `EchnoModule` manifest bean**,
   discovered by component scan and auto-collected into a `ModuleRegistry`. No core registration list is
   edited to add a module.
3. **Spring Modulith from day one** for boundary verification, with an ArchUnit tenancy rule.
4. **Migrations per module via `includeAll` and context-free changesets**; module entities are
   mandatorily `TenantScopedEntity` + `orgFilter`.
5. **On/off is entitlement, not classloading.** The module's `entitlementFeatureKey` is a `Feature`
   row; `checkFeatureAccess` and `@RequireSubscription` are the runtime lever and the server-side
   backstop, unchanged. A coarse `@ConditionalOnProperty` kill switch sits above it for operators.
6. **`echno-core` stays the single contract**, with module types/services/hooks published as existing
   subpaths; one core version binds backend, core, and web.
7. **The web loader is backend-driven**: `/api/v1/modules/enabled` is the truth, composed with the
   existing `canAccess` role/permission gate and the existing `nav/` and boundaries setup, with zero new
   boundary exceptions.
8. **Inspections (with Compliance) is the reference module.** Evolutionary, not a rewrite: add the
   skeleton, move one bounded capability onto it, gate it so no existing tenant loses it, then convert
   further capabilities only on demand.
