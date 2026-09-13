# Payments: Razorpay keys, webhook, and what to check

Phase 1 of the payment integration (`docs/specs/2026-08-26-payment-integration-razorpay.md`,
issue #794) ships the gateway port, the Razorpay adapter, the webhook inbox and the entitlement
projection. Nothing is live until the keys below exist. Without them the backend wires a no-op
gateway and runs on manually provisioned subscriptions, exactly as before.

## Configuration keys

All under `echno.billing` in `application.yml`, each backed by an environment variable.

| Property | Env var | Default | Meaning |
|----------|---------|---------|---------|
| `echno.billing.provider` | `ECHNO_BILLING_PROVIDER` | `none` | `none` or `razorpay`. |
| `echno.billing.razorpay.key-id` | `RAZORPAY_KEY_ID` | empty | Razorpay API key id (`rzp_test_...` on staging, `rzp_live_...` in production). |
| `echno.billing.razorpay.key-secret` | `RAZORPAY_KEY_SECRET` | empty | The matching API key secret. |
| `echno.billing.razorpay.webhook-secret` | `RAZORPAY_WEBHOOK_SECRET` | empty | The secret entered when the webhook is created in the Razorpay dashboard. |
| `echno.billing.razorpay.base-url` | `RAZORPAY_BASE_URL` | `https://api.razorpay.com/v1` | Leave alone. |
| `echno.billing.razorpay.proxy-host` / `proxy-port` | `RAZORPAY_PROXY_HOST` / `RAZORPAY_PROXY_PORT` | empty / `3128` | Forward proxy for egress where the host has none (the IITM lab proxy), as for `compliance.ai`. |
| `echno.billing.afa-cap-paise` | (none) | `1500000` | The RBI per-debit ceiling, 15,000 rupees. Do not change without a product decision. |
| `echno.entitlement.mode` | `ECHNO_ENTITLEMENT_MODE` | `advisory` | Already present. `enforce` turns a refused entitlement into a 402. |

Behaviour of the guard:

- `provider=none`: no-op gateway. Every webhook delivery is rejected with 401, no provider call
  is ever made, `BillingGateway.isEnabled()` is false.
- `provider=razorpay` with `key-id` or `key-secret` empty: a warning at startup and the same
  no-op gateway. The application boots.
- `provider=razorpay` with both keys but no `webhook-secret`: the adapter is wired for outbound
  calls, and every webhook is rejected until the secret is set.

## Where the keys live

They are deploy-time secrets and never enter this repository. Add them to the Ansible vault in
the private `echno-deployment` repository and template them into the backend environment the
same way `COMPLIANCE_AI_API_KEY` reaches it. Suggested vault variable names, one set per
environment:

```
vault_razorpay_key_id
vault_razorpay_key_secret
vault_razorpay_webhook_secret
```

mapped in the backend environment as `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`,
`RAZORPAY_WEBHOOK_SECRET`, plus `ECHNO_BILLING_PROVIDER=razorpay`. Test-mode keys back the
IITM staging (`echno.in`); live keys back each per-client production instance, each with its own
webhook secret.

## Registering the webhook

In the Razorpay dashboard (Settings, Webhooks) create one webhook per environment:

- URL: `https://<backend host>/api/v1/billing/webhooks/razorpay`
  (staging: `https://backend.echno.in/api/v1/billing/webhooks/razorpay`).
- Secret: generate one, put it in the vault as above.
- Events: `subscription.authenticated`, `subscription.activated`, `subscription.charged`,
  `subscription.pending`, `subscription.halted`, `subscription.paused`, `subscription.resumed`,
  `subscription.cancelled`, `subscription.completed`, `payment.failed`, `invoice.paid`,
  `token.confirmed`, `token.rejected`, `token.cancelled`, `token.paused`.

The path is public at the security layer; the HMAC in `X-Razorpay-Signature` over the raw body
is the only gate. It must be reachable from Razorpay through the edge: on the IITM staging that
means the Cloudflare Tunnel must expose it, and the WAF must let `POST` bodies through on that
path. That edge configuration is still to be done and is tracked on #794.

## How a delivery flows

1. `BillingWebhookController` binds the body as raw bytes and hands them, the signature header
   and `X-Razorpay-Event-Id` to `BillingWebhookService`.
2. The signature is verified over the untouched bytes. Failure: 401, nothing parsed, nothing stored.
3. The body is parsed by the adapter and one `billing_event` row is inserted, keyed on the
   header event id (or a body digest when the header is absent). A duplicate is acknowledged
   with 200 and dropped. Razorpay retries on anything but 2xx, so 200 is returned as soon as
   the row commits.
4. After commit, `BillingEventListener` runs `BillingEventProjector.process` on another thread:
   resolve the organization (from the `notes.organization_id` the adapter stamps on every
   subscription, else the `billing_customer` mapping, else the projected subscription), drop
   the event as `SKIPPED` if a later event for the same provider subscription was already
   applied, otherwise `EntitlementProjection.apply` writes the `subscription` row and evicts the
   organization's cache entry.
5. A failure marks the row `FAILED` with `last_error` and `attempt_count`;
   `BillingReconciliationService.retryPending` retries up to five attempts. Scheduling that
   retry, and the stale-subscription sweep over `reconcile(providerSubscriptionId)`, is Phase 3.

## What each event does to the entitlement

| Event | `subscription.status` | Period |
|-------|----------------------|--------|
| `subscription.authenticated` | `ACTIVE` | Until the first charge (`charge_at`). |
| `subscription.activated` / `charged` | `ACTIVE` | `current_start` to `current_end`. |
| `subscription.pending`, `payment.failed` | `PAST_DUE` | Unchanged. The gate treats `PAST_DUE` as not active (see below). |
| `subscription.halted` | `UNPAID` | Unchanged. |
| `subscription.paused` / `resumed` | `PAUSED` / `ACTIVE` | Unchanged / as sent. |
| `subscription.cancelled` / `completed`, `token.cancelled` / `rejected` | `CANCELED` | Unchanged. |
| `token.confirmed` | (mandate row `AUTHORIZED`) | |

A gateway subscription that becomes live cancels any other live row of the same organization
(a manual or trial one) with the reason "Superseded by RAZORPAY subscription ...", so the
organization has one live row and it is the paid one.

Note on `PAST_DUE`: the design (section 7) keeps access during the dunning window, but the gate
as it exists (`SubscriptionRepository.findActiveSubscription`, `Subscription.isActive()`) treats
only `ACTIVE` and `TRIALING` as live. Phase 1 projects the state and leaves the gate unchanged;
whether `PAST_DUE` should keep access, and for how long, is a product decision recorded on #794.

## Checking an environment

- Startup log: `Billing gateway: Razorpay at https://api.razorpay.com/v1` (or `none configured`).
- Sign a fixture and post it:

  ```sh
  BODY=$(cat src/test/resources/fixtures/razorpay/subscription.activated.json)
  SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$RAZORPAY_WEBHOOK_SECRET" | awk '{print $2}')
  curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "X-Razorpay-Signature: $SIG" -H "X-Razorpay-Event-Id: evt_smoke_1" \
    -H 'Content-Type: application/json' --data-binary "$BODY" \
    https://backend.echno.in/api/v1/billing/webhooks/razorpay
  ```

  200 the first time, 200 again (duplicate), 401 with the signature altered. The fixture names
  organization 4242 and plan `fixture-pro`, so on a real environment the row lands as `SKIPPED`
  with "Organization could not be resolved" unless those exist; that is the expected outcome of
  a smoke check.
- Inbox: `SELECT id, provider_event_id, event_type, organization_id, status, attempt_count,
  last_error FROM billing_event ORDER BY received_at DESC LIMIT 20;`
- Projection: `SELECT id, organization_id, provider, external_subscription_id, status,
  current_period_end FROM subscription WHERE provider = 'RAZORPAY';`

## RBI rules the adapter enforces

- A cycle amount (plan price times quantity) above the AFA cap needs the buyer to have
  accepted per-charge authentication (`CreateSubscriptionCommand.acceptPerChargeAfa`);
  otherwise `MandatePolicyViolationException` before any provider call. Keep monthly plans at or
  below 15,000 rupees where possible.
- Every subscription is created with `customer_notify=1`, so Razorpay sends the pre-debit
  notification, and the buyer must have an email or phone to receive it.
- Entitlement is granted only from the provider's own state (`activated`, `charged`), never at
  `createSubscription` time.
