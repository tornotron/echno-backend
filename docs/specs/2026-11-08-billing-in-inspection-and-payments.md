# Billing in Inspection, by Project, with Payments

**Date:** 2026-11-08
**Status:** Design for the next build. Revises `2026-10-04-ra-milestone-billing.md`: its section 7
(home page under Finance) and its "no payment through Echno" cut no longer stand. Issues:
echno-backend #955, echno-core #164, echno-web #549. ClickUp `14zdkkvrpb4`, `14zdkkvrpbq`,
`14zdkkvrnhd`.

## 1. What changed

- CTO decision, 8 Nov: RA billing leaves Finance for Inspection, and it processes payments. When an
  old answer and a newer ticket disagree, the latest ticket wins. The 28 Sep answer that payment
  stays out of the first version is therefore superseded.
- Product owner, 31 Oct, on the RA ticket: move billing to the project/inspections module; the home
  page is the last UI reference he attached, a project portfolio page. On the same day in #management:
  billing should be grouped on a project basis.
- Product owner, 31 Oct, on the Milestone ticket: the values an inspection measures should reach the
  contract items and make a draft bill.

The bill workflow, the money rules and the tables of the 4 Oct note are unchanged. A bill still ends
its own workflow at `APPROVED`, which raises the `Payable`. Everything here is either a new way to
reach that workflow or what follows it.

## 2. Nav, routes and old links

Billing becomes a child of Inspections in the sidebar, after Reports, and is hidden for an
organization without `MODULE_WORK_PROGRESS`. Finance keeps Payables and everything else it has.

| New route (under `/users/dashboard`) | Page |
|---|---|
| `/inspections/billing` | The project-grouped home |
| `/inspections/billing/projects/[id]` | One project: its contracts and bills |
| `/inspections/billing/contracts/[id]` | One contract: BOQ, rules, milestones, bills |
| `/inspections/billing/bills/[id]` | One bill, with its payments |

`/users/dashboard/finance/billing` and everything below it answer a permanent redirect to the same
path under `/inspections/billing`, in `next.config.ts` as the earlier leave move did. A bookmark or
a link already sent out keeps working, and the old pages are removed only with the redirect in place.

The route sits under the Inspections layout, so it passes the `inspections` module guard as well as
its own `work-progress` one. `MODULE_INSPECTIONS` is granted on every plan, so this hides nothing
today. A later plan that sold Work Progress without Inspections would have to move the route.

## 3. The home page, grouped by project

The reference is the project portfolio page: four figures across the top, a card or table view of
projects, recent activity and a status summary beside them. It carries over as follows.

- **Figures:** projects with billing, bills in progress, pending actions, amount due.
- **Each project:** name and location, contract value, percent certified against that value, a
  billing state, open bills, bills awaiting a signature and the amount due, and "Open project".
- **Recent activity:** the latest bill events across the organization.
- **Status summary:** projects by billing state, one of `NO_CONTRACTS`, `NOT_STARTED`, `IN_PROGRESS`,
  `PAYMENT_DUE`, `SETTLED`. The reference's on-track and at-risk figures are schedule status, which
  the billing tables do not hold, so this page shows billing state instead.
- The reference shows a project photograph. Projects have attachments but no chosen image, so a
  project shows an icon until one exists.

`GET /contract-billing/web/projects` returns a page of `ProjectBillingSummaryDto`, every project of
the organization by name, with figures from group-by queries over that page's project ids. Projects
with no contract are listed too, since they are where a user starting out will look; the page says
what to do next. `GET /contract-billing/web/activity?limit=` returns the recent events, optionally
for one project. `overview` gains the project and payment figures. All of this is additive to the
contract, so existing callers of `overview` and `contracts` are unaffected.

## 4. From inspection to draft bill (WPI Step 2)

The tickets ask for measured values to reach contract items. Echno has no measurement sheet today:
a progress inspection records a percent complete against a schedule activity. The link that exists
is WPI Step 2, already shipped: each BOQ item of a contract may name its activity. So the draft comes
from there, and Step 2 needs nothing new.

Opening a running account bill with `draftFromInspections` sets each line's claimed quantity to what
the linked activity's inspected progress implies: contract quantity x progress, less what is already
certified, never below zero. Lines with no linked activity stay at zero. This is the same figure the
bill page already offers as a suggestion. The bill is still a draft, and the joint measurement still
decides what is accepted. A milestone bill has no quantities and is unchanged.

## 5. Paying a bill

### 5.1 The flow

```
APPROVED bill with a Payable and an amount due
   |
   v
REQUESTED    an amount up to the amount due; part payment is allowed
   |  \
   |   +--> REJECTED   declined by the authorizer, with a reason
   v
AUTHORIZED   the second signature
   |
   +-- manual provider --> a person pays from the bank and records the reference --> SETTLED
   |
   +-- gateway ---------> sent with the payment id as idempotency key
                             |
                             v
                          PROCESSING --> SETTLED | FAILED
                                            |
                                            v
                                         REVERSED   the bank returned it later
```

- `contract_bill_payment` holds one row per attempt: bill, payable, amount, status, provider, the
  provider's id, the bank reference, who asked and who authorized, and the beneficiary as paid (name,
  IFSC, last four digits of the account). Its events go on the bill's timeline.
- `SETTLED` calls `PayableService.recordPayment`, the same call Finance > Payables makes. `Payable`
  stays the single record of what was paid. A bill's paid and due figures are read from it, so a
  payment recorded directly in Finance still shows on the bill.
- `REVERSED` gives the amount back through a new `PayableService.reversePayment`. `FAILED` and
  `REJECTED` never touched the payable, so another payment can be raised.
- At most one payment per bill is in flight, enforced under the payable's row lock.
- The bill has a derived payment state: `NOTHING_PAYABLE`, `UNPAID`, `PART_PAID`, `PAID`. `BillStatus`
  does not change, so a part-paid bill does not hold up the next RA bill, as the RA ticket's cycle
  expects.

### 5.2 Failure handling

- The provider is called outside any database transaction: commit `AUTHORIZED`, call, commit the
  result. A call that fails or times out leaves the payment `AUTHORIZED` with the error on it.
  Retrying sends the same payment id as the idempotency key, so a request that actually got through
  cannot pay twice.
- `POST .../sync` retries a send, or asks the provider where a payment stands if a webhook was missed.
- If money has gone out but the payable no longer has that much due (someone recorded the same
  payment in Finance meanwhile), the payment still settles, because the provider's answer is a fact.
  Only what is due is applied, and the timeline says finance has to reconcile the difference.

### 5.3 Endpoints

All twinned under `/contract-billing` and `/contract-billing/web`, under
`@RequireSubscription(MODULE_WORK_PROGRESS)`.

| Call | Does |
|---|---|
| `GET /bills/{id}/payments` | Mode, paid, due, payments |
| `POST /bills/{id}/payments` | Raise a payment |
| `POST /bill-payments/{id}/authorize` | Second signature; sends it when a gateway is wired |
| `POST /bill-payments/{id}/reject` | Decline, with a reason |
| `POST /bill-payments/{id}/record` | Manual mode: bank reference and date, settles it |
| `POST /bill-payments/{id}/sync` | Retry a send, or ask the provider |

## 6. Relation to the subscription gateway

`BillingGateway` and `echno.billing.*` stay as they are, and are not reused for the money movement.
They collect a subscription from an organization into the platform's own Razorpay account. A bill
payment goes the other way, from the organization to its contractor. Sent through the subscription
keys, a contractor's money would land in the platform's account, so sharing the port or the keys would
be wrong even though the provider has the same name.

Paying a third party from one's own bank account is Razorpay's payout product (RazorpayX), with its
own account number, keys and webhooks. So there is a separate small port in the module,
`BillPaymentGateway`, under `echno.contract-payments.*`. What is shared is plumbing:
`RazorpayRestClient`, `RazorpayWebhookSignature` and the `Razorpay` properties shape.

| `echno.contract-payments.provider` | Behaviour |
|---|---|
| `none` (default) | Manual. The workflow runs in full; a person pays from the bank and records the reference. No provider call. |
| `fake` | Test mode. Authorizing "sends" to an in-process fake and `sync` completes it; an amount ending in `.13` fails. Every such payment is labelled as a test on the page and timeline. Never set on a live deployment. |
| `razorpay` | Live, or Razorpay's test mode with test keys. Falls back to manual with a warning if a key, the account number or the webhook secret is missing, or for an organization not listed. |

Going live is configuration only: `ECHNO_CONTRACT_PAYMENTS_PROVIDER=razorpay`, the key pair, the
RazorpayX account number, the webhook secret, and `organization-ids`. That list exists because the keys
and the account are per deployment while organizations are not: an organization not on it stays
manual and can never draw on another's account. The webhook is
`POST /api/v1/billing/webhooks/razorpay-payouts`, under the prefix the security layer already opens,
signed with its own secret and checked over the raw bytes before parsing. It carries the payment id
as `reference_id`, is applied pinned to the payment's organization, and only moves a payment forward,
so a replay or a late event changes nothing.

The beneficiary is the sub-contract's existing bank name, account number and IFSC. With a gateway
wired, raising a payment on a contract without them is refused and says what to fill in. The transfer
mode is RTGS from 2 lakh and NEFT below. These calls are tested against a stub; they have not been run
against Razorpay because no keys exist yet, and the first run in its test mode should be watched.

## 7. Who may do what

Existing guards and nav access roles only. No new role, so this does not touch the access rework.

| Action | Guard | Web access |
|---|---|---|
| Read billing, projects, activity, payments | Any member | Any member |
| Open a draft from inspections | system-admin, project-manager, site-engineer, as any bill | `BILL_PREPARE_ACCESS` |
| Raise a payment | system-admin, project-manager | `BILLING_SIGN_ACCESS` |
| Authorize, reject, record, sync | system-admin | `PAYABLES_ACCESS` |

Authorizing follows Finance > Payables, which is system-admin today. The authorizer must be someone
other than the requester. A system admin may authorize their own request, and it is recorded as such,
as with a bill's final approval, because a small organization may have only one.

## 8. Defaults chosen

The tickets leave these open. Each can change without a data change unless noted.

1. Part payment is allowed, one payment in flight per bill.
2. Two signatures: request and authorize, with the system-admin exception above.
3. Payment does not hold up the next bill.
4. Retention is not released through this flow. It stays deducted, and its release is a later feature.
5. Milestone bills pay the same way as RA bills, and a milestone's own status is not closed by payment.
6. A draft from inspections uses the activity's latest progress, not progress as at the period end.
7. Project status on the home page is billing state, not schedule status.
8. A missed webhook is caught by `sync` on the page, not by a background sweep. A sweep can follow if
   real use shows it is needed.

## 9. Build order

Backend: this note; project reads and the draft from inspections; payments with manual and fake;
the Razorpay payout adapter and webhook. Core: one release with types, services and hooks, version
and changelog in the PR. Web: the move and redirects against the current core; then the project home
and the payment panel, which need that core release.
