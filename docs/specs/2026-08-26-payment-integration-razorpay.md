# Echno payment-gateway and paywall integration design (2026-08-26)

Status: draft for review. Target repos: `echno-backend`, `echno-core`, `echno-web`. Companion to the
subscription-gateway research (Razorpay-first) and the modular-plugin architecture draft. This is a
design, not an implementation; it is meant to be PR'd into `echno-backend/docs/specs/` once agreed.

## 1. Summary of decisions

1. **Razorpay only for launch, behind a provider-neutral `BillingGateway` port.** The official
   `com.razorpay:razorpay-java` SDK is wrapped inside one adapter and never imported by core or by any
   domain service. A Stripe adapter is additive later for international orgs and is not built now.
2. **The gateway never touches the paywall directly. Webhook events drive a single internal entitlement
   projection, and the existing `checkFeatureAccess` / `@RequireSubscription` gate reads only that
   projection.** The gate machinery (aspect, annotation, `FeatureAccessResultDto`, `SubscriptionCache`)
   is kept exactly as it is. What changes is where the `Subscription` state comes from and the scope it
   is keyed on.
3. **The entitlement projection is the existing `Subscription` row, promoted from per-user to
   per-organization.** `Subscription` already carries `plan`, `status`, `currentPeriodStart/End`,
   `cancelAtPeriodEnd` and, importantly, an unused `externalSubscriptionId`. The `SubscriptionStatus`
   enum already has `INCOMPLETE`, `PAST_DUE`, `UNPAID`, `PAUSED`, which map cleanly onto gateway states.
   The billing catalog (`Plan` / `Feature` / `PlanFeature` / `UsageRecord`) is reused unchanged.
4. **Webhook ingestion is verified against the raw request body, deduped on the provider event id,
   written to an inbox table first, and processed asynchronously.** Return 2xx fast, tolerate retries,
   tolerate out-of-order delivery. The projection is idempotent and order-tolerant.
5. **A paid module (plugin architecture) is entitled through the same `Feature` row.** Enabling a paid
   module for an org is a `PlanFeature` change plus, when it is a paid tier, a gateway subscription that
   grants the plan. No new entitlement concept is invented for modules.

## 2. What exists today, and the one gap this design must close

The billing package (`org.tornotron.echno_backend.billing`) already implements a working SaaS gate:

- `SubscriptionService.checkFeatureAccess(userId, featureCode)` returns a structured
  `FeatureAccessResultDto` (`noSubscription`, `featureNotInPlan`, `allowed`, `featureDisabled`,
  `quotaExceeded`). Quota features are summed from `UsageRecord` over a `QuotaPeriod` window.
- `@RequireSubscription(feature, recordUsage, usageAmount, errorMessage)` (targets `METHOD` and `TYPE`)
  is enforced by `SubscriptionAspect` at `HIGHEST_PRECEDENCE + 10`, throwing
  `SubscriptionAccessDeniedException` carrying the `FeatureAccessResultDto`.
- `SubscriptionCache` is a Caffeine cache keyed by `userId` with a 5 minute write TTL, evicted on every
  create / change / cancel / usage write.
- `Subscription` is keyed by `userId`, holds `plan`, `status` (`SubscriptionStatus`), period bounds,
  trial window, `cancelAtPeriodEnd`, and an already-present but unused `externalSubscriptionId`.
- Catalog: `Plan` (code, `monthlyPrice`/`annualPrice` as `BigDecimal(10,2)`, `currency` default `INR`,
  `trialDays`, `maxUsers`, `planFeatures`), `Feature` (unique `code`, `FeatureType` of
  `BOOLEAN`/`QUOTA`/`RATE_LIMIT`, `category`), `PlanFeature` (`enabled`, `quotaLimit`, `quotaPeriod`).
- Management endpoints live under `/api/v1/billing/subscriptions/web`, guarded by
  `@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')`; the admin section uses
  `hasAuthority('billing:admin')`.

The gap. **Entitlement is keyed on `userId`, but payment and billing are naturally per-organization
(the tenant), and RBI mandates are per payer.** Today `checkFeatureAccess(userId, ...)` looks up
`findActiveSubscriptionByUserId(userId)`, so a plain org member calling a `@RequireSubscription`
endpoint resolves `noSubscription` unless that individual user happens to hold a subscription row. The
management endpoints are already guarded at the org level (only a `system-admin` of the tenant can
create or change the subscription), which shows the intent is org-level; the storage and the read path
have simply not caught up. This design closes that gap as the foundation for gateway billing, because a
gateway subscription belongs to an org, not to a person.

## 3. The provider-neutral port

### 3.1 `BillingGateway` interface

Defined in a new `billing.gateway` package, provider-agnostic, using only internal DTOs. No Razorpay
type crosses this boundary.

```java
public interface BillingGateway {

    ProviderId providerId();                       // RAZORPAY, later STRIPE

    // Customer <-> Organization
    GatewayCustomer ensureCustomer(OrgBillingProfile org);

    // Catalog: map an internal Plan (+ interval) to a provider plan/price id, creating it if absent
    GatewayPlanRef ensurePlan(Plan plan, BillingPeriod interval);

    // Subscription lifecycle
    GatewaySubscription createSubscription(CreateSubscriptionCommand cmd);   // returns hosted auth URL
    GatewaySubscription fetchSubscription(String providerSubscriptionId);
    void pauseSubscription(String providerSubscriptionId);
    void resumeSubscription(String providerSubscriptionId);
    void cancelSubscription(String providerSubscriptionId, boolean atCycleEnd);
    GatewaySubscription changePlan(ChangePlanCommand cmd);                   // upgrade / downgrade

    // Mandate / authorization: returns the hosted UPI-Autopay / e-mandate / card AFA URL
    MandateAuthorization initiateMandate(InitiateMandateCommand cmd);

    // Webhook ingestion
    boolean verifySignature(byte[] rawBody, String signatureHeader);
    List<NormalizedBillingEvent> parseEvents(byte[] rawBody);               // 0..n normalized events
}
```

Provider-neutral DTOs (in `billing.gateway.dto`):

- `OrgBillingProfile` (org id, legal name, GST number, email, phone, currency).
- `GatewayCustomer` (provider customer id).
- `GatewayPlanRef` (internal plan code + interval to provider plan/price id).
- `CreateSubscriptionCommand` / `ChangePlanCommand` (org id, internal plan code, interval, quantity,
  trial days, notify-info for the RBI pre-debit notice).
- `GatewaySubscription` (provider subscription id, `NormalizedSubscriptionStatus`, current period bounds,
  next-charge time, mandate reference, short URL for authorization when pending).
- `MandateAuthorization` (hosted auth URL, mandate reference, `NormalizedMandateStatus`).
- `NormalizedBillingEvent` (provider event id, `NormalizedEventType`, org id or provider subscription
  id, occurred-at, provider status snapshot).

### 3.2 Normalized enums (do not leak provider vocabulary)

`NormalizedSubscriptionStatus` maps onto the existing `SubscriptionStatus` so the projection can reuse
it directly:

| Normalized | Existing `SubscriptionStatus` |
|-----------|-------------------------------|
| CREATED / PENDING_AUTH | `INCOMPLETE` |
| AUTH_FAILED / EXPIRED_BEFORE_AUTH | `INCOMPLETE_EXPIRED` |
| TRIAL | `TRIALING` |
| ACTIVE / AUTHENTICATED | `ACTIVE` |
| PAYMENT_FAILED_RETRYING | `PAST_DUE` |
| HALTED / DUNNING_EXHAUSTED | `UNPAID` |
| PAUSED | `PAUSED` |
| CANCELLED / COMPLETED | `CANCELED` |

`NormalizedEventType`: `SUBSCRIPTION_AUTHENTICATED`, `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_CHARGED`,
`SUBSCRIPTION_PENDING`, `SUBSCRIPTION_HALTED`, `SUBSCRIPTION_PAUSED`, `SUBSCRIPTION_RESUMED`,
`SUBSCRIPTION_CANCELLED`, `SUBSCRIPTION_COMPLETED`, `MANDATE_AUTHORIZED`, `MANDATE_REVOKED`,
`PAYMENT_FAILED`, `INVOICE_PAID`.

`NormalizedMandateStatus`: `CREATED`, `PENDING`, `AUTHORIZED`, `PAUSED`, `REVOKED`, `EXPIRED`.

The gate never sees these enums. They exist only between the adapter and the entitlement projector.

## 4. The Razorpay adapter

`RazorpayBillingGateway implements BillingGateway`, in `billing.gateway.razorpay`, the only place
`com.razorpay:razorpay-java` is imported.

- **Customer.** `ensureCustomer` maps `organization.id` to a Razorpay customer. Store the mapping in
  `billing_customer` (Section 7) so it is created once per org and reused. Razorpay customer notes carry
  the org id for traceability.
- **Plan.** `ensurePlan` maps an internal `Plan.code` + interval to a Razorpay Plan id. Razorpay Plans
  are immutable once created, so a price change means a new Razorpay Plan id bound to the same internal
  `Plan.code`; the mapping table records which Razorpay Plan id is current. Amounts are sent in paise
  (`BigDecimal` rupees times 100).
- **Subscription.** `createSubscription` creates a Razorpay Subscription (`total_count`, `plan_id`,
  `customer_notify`, optional `start_at` for the trial, `notes` with org id). Razorpay returns a
  `short_url` that is the hosted authorization page; this is surfaced as `GatewaySubscription.authUrl`.
  Cancel supports `cancel_at_cycle_end`. Pause/resume map to the Razorpay pause/resume endpoints.
- **Plan change.** Razorpay proration is weak, so `changePlan` follows the documented pattern: for an
  upgrade take effect at cycle end or cancel-and-recreate, rather than pretending Stripe-style
  proration exists. The port exposes the intent; the adapter chooses the mechanism. The projection is
  updated only from the resulting webhook, never optimistically.
- **Mandate.** `initiateMandate` returns the hosted UPI-Autopay / e-NACH / card-AFA URL. For a
  subscription the mandate is registered as part of the first authorization transaction, so in practice
  `createSubscription` and mandate registration are one hosted flow; `initiateMandate` exists as a
  separate port method so a Stripe adapter (SetupIntent-style) can implement it independently.
- **Webhooks.** `verifySignature` calls `Utils.verifyWebhookSignature(rawBodyString, signatureHeader,
  webhookSecret)` (HMAC-SHA256 hex over the raw body). `parseEvents` maps the Razorpay event envelope
  to `NormalizedBillingEvent`, resolving the org id from `notes` or from the `billing_customer` mapping.

## 5. The entitlement projection

This is the heart of the design and the contract with the existing gate.

### 5.1 Single projection, org-scoped, gate reads only this

Rule: **the paywall reads the projection, the projection is written only by the webhook projector, and
nothing in the request path calls the gateway.** The projection answers exactly one question: "for
organization X, which plan is active and until when."

The projection is the existing `Subscription` row, promoted to org scope:

- Add `organization_id` to `subscription` (see Section 8). The row now means "this org's current
  entitlement," resolved through its `Plan` and `PlanFeature` set exactly as today.
- The webhook projector is the only writer of `status`, `currentPeriodStart/End`, `plan`, and
  `externalSubscriptionId` for gateway-backed subscriptions. The self-service create/change/cancel
  methods remain for manually-provisioned or trial subscriptions, and for admin overrides, but a
  gateway-backed subscription's authoritative state transitions come from webhooks.

### 5.2 Making the gate org-aware without changing the aspect or the annotation

`SubscriptionAspect` and `@RequireSubscription` stay byte-for-byte the same. They call
`checkFeatureAccess(userId, feature)`. The change is internal to `SubscriptionService`:

- Add `resolveOrgId(userId)` (the user's organization, from `User` / `TenantContext`).
- `getActiveSubscription` gains an org-scoped path: resolve the org, then
  `findActiveSubscriptionByOrgId(orgId)`. Keep the per-user method as a fallback during migration so
  no existing behaviour breaks on cutover.
- `checkFeatureAccess(userId, feature)` therefore resolves the caller's org entitlement, which is the
  correct semantics: any member of an entitled org passes the gate; a member of an org whose plan omits
  the feature is denied with the existing `featureNotInPlan` result.
- The `SubscriptionCache` is re-keyed (or a parallel org-keyed cache is added) so a webhook that changes
  an org's entitlement evicts the org, not one user. Cache eviction on a webhook event is mandatory,
  otherwise entitlement changes lag by up to the 5 minute TTL.

This keeps the promise: build on the gate, do not replace it. The aspect, the annotation, the
`FeatureAccessResultDto`, quota, and usage recording are all untouched. Only the storage scope and the
resolution helper change, plus the cache key.

### 5.3 How it maps onto Feature / Plan / PlanFeature (extend vs new tables)

- **`Feature`, `Plan`, `PlanFeature`, `UsageRecord`: unchanged.** A tier is a `Plan`; a capability is a
  `Feature`; a tier granting a capability is a `PlanFeature`; a metered capability still uses
  `FeatureType.QUOTA` with `quotaLimit` / `quotaPeriod` and `UsageRecord`. The gateway integration adds
  no column to any of these four.
- **`Subscription`: extended**, not replaced. Add `organization_id` (the projection scope) and reuse the
  existing `externalSubscriptionId` for the provider subscription id. Add a small `provider` column so
  the row records which gateway backs it (or `MANUAL` for admin-provisioned).
- **New auxiliary tables** (Section 8) hold the parts that do not belong on the projection: the
  org-to-customer mapping, the provider-plan mapping, the mandate, and the webhook inbox. These are
  gateway plumbing, deliberately separate from the catalog and the projection so the catalog stays
  provider-neutral.

### 5.4 Reconciliation fallback

Webhooks are the source of truth but can be missed. A scheduled reconciler (mirroring the existing
`findExpiredSubscriptions` sweep and the compliance background job pattern) periodically fetches the
provider state for subscriptions that look stale (past `currentPeriodEnd`, or in `INCOMPLETE`/`PAST_DUE`
for too long) via `fetchSubscription`, and repairs the projection. This makes a missed webhook a
self-healing delay, not a permanent wrong entitlement.

## 6. Webhook ingestion (Razorpay + Spring specifics)

### 6.1 Raw body capture before Jackson

The signature is HMAC-SHA256 over the unparsed body, so the bytes must be captured before any converter
touches them. Two acceptable mechanisms, pick one and apply it only to the webhook path:

- A dedicated controller method `@PostMapping(consumes = MediaType.ALL) public ResponseEntity<Void>
  handle(@RequestBody byte[] rawBody, @RequestHeader("X-Razorpay-Signature") String sig)`. `byte[]`
  binding gives the untouched body, and the endpoint is excluded from any global JSON assumptions.
- Or a `ContentCachingRequestWrapper` via a narrow filter mapped only to
  `/api/v1/billing/webhooks/**`, reading `getContentAsByteArray()` in the controller.

Recommend the `byte[]` handler: it is the simplest and cannot be defeated by a re-serialization. Parsing
to a DTO happens only after `verifySignature(rawBody, sig)` returns true, and only from the same bytes.

### 6.2 The webhook endpoint

- Path: `POST /api/v1/billing/webhooks/razorpay`. Public (no Keycloak session), because Razorpay calls
  it; it is authenticated solely by the HMAC signature.
- It must be reachable through the edge. On DO staging that is the Cloudflare origin with the webhook
  path allowed through WAF and rate-limited; on IITM it is the Cloudflare Tunnel. The endpoint bypasses
  the tenant filter (there is no session org), and it must set no `TenantContext` from a session; the
  org is resolved from the event payload / customer mapping instead.

### 6.3 Idempotent, order-tolerant, async

- **Inbox first.** On receipt, after signature verification, insert a `billing_event` row keyed unique
  on `(provider, provider_event_id)`. A duplicate insert (unique-violation) means an already-seen event;
  ack 2xx and stop. This is the dedupe.
- **Return 2xx fast.** The HTTP handler does signature verify plus inbox insert, then returns 200. The
  projection update runs asynchronously (a `@Async` worker or a short poll of `RECEIVED` inbox rows),
  so a slow projection never causes Razorpay to time out and retry.
- **Order tolerance.** Events can arrive out of order (`charged` before `activated`). The projector is a
  state function, not a step sequence: it computes the target projection from the event plus the current
  provider-truth fields, and applies a transition only if it does not move the projection backwards in
  time (guard on the event `occurred_at` versus the row's last-applied timestamp). A late `activated`
  after a `charged` therefore does not un-activate.
- **Cache eviction.** Every applied transition evicts the org's subscription cache entry so the next
  `checkFeatureAccess` reads fresh state.
- **Retry and dead-letter.** A projection failure marks the `billing_event` `FAILED` with an attempt
  count; a bounded retry re-runs it; exhausted events are visible for manual inspection. Razorpay's own
  retries also re-deliver, and the unique inbox key makes that safe.

## 7. RBI, mandate lifecycle, and pricing constraints

The e-mandate framework binds any gateway, so these are product rules, not Razorpay quirks.

- **AFA cap of 15,000 rupees per debit.** Recurring debits at or below 15,000 run without per-charge
  OTP once the mandate is authorized with AFA up front. Above 15,000, every charge needs AFA (OTP),
  which is friction each cycle. **Design rule: keep monthly plan prices at or below 15,000 rupees per
  cycle where possible.** For higher-value plans, prefer an annual cycle priced above the cap only if
  the customer accepts per-charge AFA, or split into an at-or-below-cap monthly. The plan catalog should
  carry a flag or be validated so a plan priced above the cap is a deliberate choice, surfaced in the
  billing UI as "requires approval on each payment."
- **Pre-debit notification, at least 24 hours before each debit.** Razorpay sends this as part of its
  subscription product, but the billing UX and any dunning copy must assume the charge is not immediate:
  a renewal is notified at least a day ahead, with an opt-out. Do not build flows that assume same-second
  capture on renewal.
- **One-time mandate registration always needs AFA** (3DS, UPI approval, or netbanking) at setup. The
  first authorization is a hosted flow; entitlement is granted only on `MANDATE_AUTHORIZED` +
  `SUBSCRIPTION_ACTIVATED`, never at `createSubscription` time.
- **Mandate lifecycle to project.** `created` -> hosted auth -> `authorized`/`activated` (grant
  entitlement) -> `charged` per cycle (extend `currentPeriodEnd`) -> `pending`/`payment_failed`
  (`PAST_DUE`, keep access during the grace/dunning window) -> `halted` (`UNPAID`, revoke) or
  `paused`/`resumed` or `cancelled`/`completed` (`CANCELED`). Revocation of the mandate
  (`MANDATE_REVOKED`) cancels entitlement.
- **Secrets.** `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` are injected as env
  vars at deploy time, following the existing compliance-AI config pattern in `application.yml`
  (`${VAR:default}` with an `enabled` flag that no-ops the integration when the key is empty). They live
  in the Ansible vault in the private `echno-deployment` repo, never in the public `echno-backend` repo.
  Test-mode keys back staging on `echno.in`; live keys back production per-client DO instances.

Config sketch (matches the existing style):

```yaml
razorpay:
  enabled: ${RAZORPAY_ENABLED:false}
  key-id: ${RAZORPAY_KEY_ID:}
  key-secret: ${RAZORPAY_KEY_SECRET:}
  webhook-secret: ${RAZORPAY_WEBHOOK_SECRET:}
  currency: INR
  afa-cap-paise: 1500000   # 15,000 rupees, the no-OTP ceiling
```

When `enabled` is false or the key is empty the gateway bean is a no-op and the app runs on
manually-provisioned subscriptions only, exactly as it does today.

## 8. Data model and migrations

New Liquibase changesets under `db/changelog/v4.1/` (next version context after the current `v4.0` tip,
changeset 052), each `<include>`d in `db.changelog-master.xml` in the existing style. All new tables are
org-scoped and carry `organization_id` so the tenant filter applies, except the webhook inbox which is
cross-org by nature and is guarded by service-layer org resolution instead.

1. **Alter `subscription`**: add `organization_id BIGINT` (nullable at first for backfill, then
   not-null), add `provider VARCHAR(20)` default `MANUAL`, index `idx_subscription_org` on
   `organization_id`. `external_subscription_id` already exists and is reused. Backfill
   `organization_id` from each subscription's user's org.
2. **`billing_customer`**: `id`, `organization_id` (unique per provider), `provider`,
   `provider_customer_id`, `created_at`. One row per org per provider.
3. **`gateway_plan_ref`**: `id`, `plan_code`, `provider`, `interval`, `provider_plan_id`, `is_current`,
   `created_at`. Maps an internal `Plan.code` + interval to the current provider plan id; supports the
   immutable-plan-means-new-id rule.
4. **`payment_mandate`**: `id`, `organization_id`, `provider`, `provider_mandate_ref`, `method`
   (UPI_AUTOPAY / ENACH / CARD), `status` (`NormalizedMandateStatus`), `max_amount_paise`,
   `authorized_at`, `revoked_at`. The AFA-cap and method live here.
5. **`billing_event`** (webhook inbox): `id`, `provider`, `provider_event_id` (unique with `provider`),
   `event_type`, `organization_id` (nullable until resolved), `payload` (JSON/TEXT), `signature_verified`,
   `status` (`RECEIVED` / `PROCESSED` / `FAILED` / `SKIPPED`), `attempt_count`, `received_at`,
   `processed_at`, `last_applied_at`. The unique key is the idempotency guard; `last_applied_at` supports
   order tolerance.

`Feature`, `Plan`, `PlanFeature`, `UsageRecord`, `SubscriptionItem` get no schema change. Following the
plugin design, when a paid module ships, its `Feature` row (for example `MODULE_INSPECTIONS`) is added
by an idempotent seed fragment, not by a gateway migration.

## 9. echno-web and echno-core

There is no billing feature folder in `echno-web` or billing subpath in `echno-core` today, so the UI
and the shared contract are greenfield and follow the existing conventions.

### 9.1 echno-core

Add a `billing` domain subpath, published by the existing wildcard subpath exports the same way every
core domain is:

- `@tornotron/echno-core/billing/types`: `PlanDto`, `FeatureDto`, `PlanFeatureDto`, `SubscriptionDto`,
  `FeatureAccessResultDto`, `MandateStatus`, `CheckoutSession`, matching the backend DTOs so the web app
  cannot drift from the contract.
- `@tornotron/echno-core/billing/services`: typed clients for the subscription, plan, feature-access,
  checkout, and mandate endpoints.
- `@tornotron/echno-core/billing/hooks`: TanStack Query hooks and keys, including a
  `useFeatureAccess(featureCode)` hook and a `useEntitlements()` bootstrap hook. These are the
  entitlement hooks the plugin design's module loader consumes.

### 9.2 echno-web

Two-tier BFF as elsewhere: browser calls Next `/api/v1/billing/*`, the server proxy forwards to
`backend.echno.xyz/api/v1/billing/*` with the Keycloak session token. New `features/billing/`:

- **Paywall surface.** A `FeatureGate` component and a `withEntitlement(featureCode)` guard that read
  `useFeatureAccess` / the enabled-set and render either the feature or a paywall panel (plan name,
  what the feature unlocks, an upgrade call to action). Gating is presentation only; the server-side
  `@RequireSubscription` backstop is the real control.
- **Checkout / mandate flow.** The billing settings page starts a subscription: it calls the backend to
  create the gateway subscription, receives the hosted authorization URL (Razorpay `short_url` or
  Razorpay Checkout via `checkout.js`), and sends the org admin through the UPI-Autopay / e-mandate /
  card-AFA authorization. The page then shows a "authorization pending" state and flips to active only
  when the backend projection reports `ACTIVE` (driven by the webhook), never optimistically on redirect
  return. A 24 hour pre-debit notice notice is shown for renewals to set expectations.
- **Billing settings.** Current plan, status, next charge date, mandate method and status, cancel /
  change-plan actions, and invoice history. Guarded to the org `system-admin` role, matching the
  backend endpoint guards.
- **Plan and usage display.** The plan catalog with prices (rupees), a usage meter for quota features
  from `checkFeatureAccess` remaining counts, and a clear label on any plan priced above the 15,000
  rupee cap that each payment will need approval.

## 10. Tie-in with the plugin module registry

The plugin design already routes a module's on/off through the entitlement gate: a module's manifest
declares an `entitlementFeatureKey` (for example `MODULE_INSPECTIONS`), which is a `Feature` row, and
`ModuleRegistry.isEnabledForOrg` is true only when the org is entitled per `checkFeatureAccess`. This
design supplies the paid path behind that key:

- **Enabling a paid module for an org is a billing operation, not a deploy.** A plan that includes the
  module has a `PlanFeature` for the module's feature key. An org on that plan is entitled; the registry
  reports the module in `/api/v1/modules/enabled`; the web loader renders its nav; the module's
  `@RequireSubscription(feature = "MODULE_...")` backstop enforces it server-side.
- **The paywall for a module is the same paywall as for any feature.** When an org is not entitled, the
  module is absent from the enabled set (no nav), its routes render the standard access-denied surface,
  and its endpoints deny with `FeatureAccessResultDto`. Upgrading the org's plan (a `PlanFeature`
  change, backed by a gateway subscription that grants the plan) turns the module on with no redeploy.
- **Metered modules** (for example N AI reports per month) keep using `FeatureType.QUOTA` +
  `@RequireSubscription(recordUsage = true)` and `UsageRecord`, unchanged. The gateway does not meter;
  it only decides which plan an org holds. Metering stays internal.

So the gateway grants plans, plans grant features, features gate modules. The payment layer stops at
"which plan is the org on," and everything above it is the existing gate and the module registry.

## 11. Phased plan

- **Phase 0, foundation (no gateway).** Add `organization_id` to `subscription`, the org-scoped read
  path in `SubscriptionService`, the org-keyed cache, and `findActiveSubscriptionByOrgId`. Backfill.
  This closes the per-user gap and is independently valuable: the paywall becomes correct for all org
  members. No gateway involved, fully testable.
- **Phase 1, port and adapter, test mode.** Add the `BillingGateway` port, DTOs, normalized enums, the
  `RazorpayBillingGateway` adapter, the auxiliary tables, and the config with the `enabled` no-op guard.
  Wire the webhook endpoint (raw body, HMAC verify, inbox, async projector). Run against Razorpay test
  mode on `echno.in`. Entitlement is now gateway-driven for a test org.
- **Phase 2, web checkout and billing UI.** Add the echno-core billing subpath and the echno-web
  `features/billing` folder: paywall, checkout / mandate flow, billing settings, plan and usage display.
  Exercise the full authorize-and-charge lifecycle in test mode.
- **Phase 3, reconciler and hardening.** Add the scheduled reconciliation sweep, dead-letter visibility
  for failed events, rate-limit and WAF rules on the webhook path, and the mandate lifecycle dashboards.
- **Phase 4, go-live per client.** Provision live keys in the Ansible vault for the per-client
  production DO instance, seed the real plans (respecting the 15,000 rupee rule), and cut a pilot org
  over. Stripe remains a future additive adapter, not part of this plan.

## 12. Security

- **Webhook authenticity is the HMAC, nothing else.** The endpoint is unauthenticated by session and
  must reject any request whose signature does not verify against the raw body. Never parse or act on an
  unverified payload. The webhook secret is per environment and vault-held.
- **No provider secret in the public repo.** Key id, key secret, and webhook secret are env-injected
  from the private `echno-deployment` vault, matching the DO Spaces and compliance-AI keys.
- **The paywall is enforced server-side.** UI gating is cosmetic; `@RequireSubscription` on the endpoint
  is the control, unchanged from today.
- **Tenant isolation.** The projection and all auxiliary org-scoped tables carry `organization_id` and
  inherit the `orgFilter`. The webhook path is the one place with no session org; it resolves the org
  from the event and must never trust a client-supplied org id.
- **Least privilege on management endpoints.** Create / change / cancel stay guarded by the org
  `system-admin` role and the `billing:admin` authority, as today.
- **Idempotency prevents double-grant.** The unique inbox key means a replayed or duplicated webhook
  cannot double-extend a period or double-charge internally.

## 13. Testing

- **Signature verification and replay.** Unit tests for `verifySignature` against known good and
  tampered bodies; an integration test that posts the same event twice and asserts one projection change
  (dedupe) and a 2xx both times.
- **Out-of-order delivery.** Post `charged` before `activated` and assert the projection ends `ACTIVE`
  with the correct period, not un-activated by the late event.
- **Mandate lifecycle in Razorpay test mode.** Drive create -> authorize -> charge -> pause -> resume ->
  cancel and assert each maps to the expected `SubscriptionStatus` and entitlement outcome, using the
  Testcontainers + CockroachDB harness already in the repo.
- **Gate correctness.** A member of an entitled org passes `@RequireSubscription`; a member of an org
  whose plan omits the feature is denied with `featureNotInPlan`; a quota feature blocks past the limit
  and records usage. These reuse the existing gate tests, extended to the org scope.
- **Cache eviction.** A webhook transition evicts the org cache and the next access reflects the new
  entitlement within the request, not after the TTL.
- **RBI cap validation.** A plan priced above the cap is flagged; the checkout copy warns of per-charge
  AFA.
- **Reconciler.** A subscription with a missed activation webhook is repaired by the scheduled fetch.

## 14. Open questions

- **User-to-org resolution for the gate.** Phase 0 assumes a clean "user's organization" lookup. Confirm
  the canonical source (the `User` entity's org, or `TenantContext` at request time) and whether a user
  can belong to more than one org; if so, the gate must resolve the org of the current tenant context,
  not a single home org.
- **Trial and manual coexistence.** Some orgs will be on manually-provisioned or trial subscriptions
  (`provider = MANUAL`). Confirm the rule when a manual subscription and a gateway subscription would
  both apply to an org (recommend: a gateway subscription supersedes a manual one on activation).
- **Plan change semantics.** Decide the product rule for upgrade and downgrade given Razorpay's weak
  proration: at-cycle-end change, or cancel-and-recreate with a credit. This is a business decision that
  shapes the `changePlan` implementation.
- **Above-cap plans.** Decide whether Echno will offer any plan above 15,000 rupees per cycle at launch
  (accepting per-charge AFA), or cap all launch plans at or below it and use annual billing for larger
  commitments.
- **Invoicing and GST.** Razorpay does not produce GST-compliant invoices for you. Decide whether Echno
  generates its own tax invoice (it already renders PDFs) or uses Razorpay Invoices, and where the org
  GSTIN is captured.
- **Webhook exposure on IITM.** The Cloudflare Tunnel path must expose the webhook endpoint publicly
  while the rest of staging stays behind auth; confirm the tunnel and WAF config for
  `/api/v1/billing/webhooks/razorpay`.
- **Per-client production keys.** Each per-client DO instance implies its own Razorpay account or its own
  sub-keys and webhook secret. Confirm whether clients pay Fereydon (one Razorpay account, orgs as
  customers) or each client has its own merchant account (one account per deployment). This changes the
  customer-to-org mapping fundamentally and should be settled before go-live.
```
