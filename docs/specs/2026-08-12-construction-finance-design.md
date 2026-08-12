# Construction finance: two-model design

Status: approved (Abhijith, 2026-08-12). Echno supports **both** a construction operational finance document
and the existing accounts-receivable (AR) accounting record, with the construction document posting into the
shared general ledger. Build owned in-house (not delegated to the feature team). Section 4 fork resolved to
**A**; section 7 defaults chosen (below).

## 1. Why two models

The web app already defines a rich **construction** invoice/payment model, and the backend already ships a
narrow **AR accounting** model. They are different artifacts, not two versions of one:

| | Construction document (web-defined, currently mock) | AR record (backend/core, real) |
|---|---|---|
| Purpose | Operational: what was billed/paid on a project | Accounting: customer receivable + ledger posting |
| Invoice keyed on | `projectId` (required), `vendorId?` | `customer` (required) |
| `type` dimension | purchase / sales / expense / service | none (always sales: DR AR / CR income) |
| Line items | qty, unit, unitPrice, tax, discount, refs to inventory/asset/task | description, qty, unitPrice, tax, **revenue account (INCOME)** |
| Status | 8 states + separate paymentStatus | 5 states (DRAFT/ISSUED/PARTIALLY_PAID/PAID/CANCELLED) |
| Payment | broad disbursement voucher (vendor/labour/salary/utility payouts) | customer receipt allocated across AR invoices |
| Id | numeric (web) | UUID |

The AR record is deliberately narrow and ledger-coupled (revenue-account-per-line, immediate journal entry on
issue, fixed DR-AR / CR-income posting). Extending it with project/vendor/type/discount/PO/GRN and three extra
states would break those invariants: a **purchase or expense invoice inverts the posting** (DR expense / CR
accounts-payable), which the AR invoice cannot express. So the construction document is a **new entity/table**,
and it **posts into** the AR/GL rather than replacing it.

## 2. The reconciliation seam (already built for this)

The ledger already accepts arbitrary document sources:

- `JournalPostingService.postInternal(PostJournalRequest, sourceType, sourceId)` posts a balanced set of
  DR/CR lines and returns the journal entry id.
- `JournalEntry.sourceType` / `sourceId` columns already carry the existing `INVOICE`, `PAYMENT`, `MANUAL`,
  `REVERSAL` sources and are built to take more.
- `postInternal` enforces the invariants (>= 2 lines, DR = CR, non-future date, no posting to inactive or
  non-leaf accounts).

The construction module posts through this same method with new source types `CONSTRUCTION_INVOICE` /
`CONSTRUCTION_PAYMENT`, stores the returned journal-entry id on the construction document, and reverses via
`postingService.reverse(...)` on cancel. This gives full drill-back from ledger to source document with no new
plumbing.

## 3. Posting logic by invoice type

| `type` | Debit | Credit |
|---|---|---|
| sales / service | Accounts Receivable (control acct 1200) | revenue account per line (INCOME) + GST output (2210) |
| purchase / expense | expense/inventory account per line | Accounts Payable (new control acct) + GST input |

Sales/service reuses the exact posting `InvoiceService.issue` already performs (lines 146-182). Purchase/expense
needs a **new posting-properties bean** carrying the AP control-account code and GST-input code, mirroring
`InvoicePostingProperties` (AR=1200, GST-out=2210).

## 4. Sales/service ledger path — RESOLVED: option A

For **sales/service** construction invoices, two ways to reach the ledger were considered; **A is chosen**:

- **(A, recommended) Materialize a real AR `Invoice`.** On issue, the construction service creates the
  corresponding AR `Invoice` row (customer = the project's client) and calls the existing `InvoiceService.issue`.
  AR aging, customer statements, and the receipts/allocation flow then pick up construction sales invoices for
  free, and we reuse the already-tested posting + payment-allocation path.
- **(B) Post its own journal entry directly** via `postInternal("CONSTRUCTION_INVOICE", id)`, without an AR row.
  Simpler to build, but construction sales invoices then never appear in AR aging or customer statements, and
  payments against them need a parallel allocation path.

**Purchase/expense** invoices have no AR analog (they are payables), so they always post their own AP journal
entry directly (option B mechanics) regardless of the fork.

Recommendation: **A for sales/service, direct AP posting for purchase/expense.** This keeps one source of truth
for customer receivables and reuses the hardened path, at the cost of writing the construction to AR mapping.

## 5. Work breakdown

Owned in-house across all three repos (not delegated to the feature team).

**Backend:**
1. New module `finance/construction/`: `ConstructionInvoice` + `ConstructionInvoiceLine` + `ConstructionPayment`
   entities matching the web `types/finance/{invoice,payment}.ts` shape (project/vendor/type/PO/GRN/discount/
   8-state), tenant-scoped, UUID ids.
2. `ConstructionInvoiceService` / `ConstructionPaymentService` with the posting logic in section 3 and the
   section-4 fork (default A for sales/service).
3. New AP posting-properties bean (AP control account + GST-input codes).
4. `ConstructionInvoiceControllerWeb` / `ConstructionPaymentControllerWeb` under `/api/v1/finance/...web`:
   list (filter by project/vendor/status/type + pagination), get-by-id, create, update, issue, cancel/record.
5. **Add the missing list endpoints** `GET /finance/invoices/web` and `GET /finance/payments/web` on the
   existing AR controllers too (needed independently; core already flags these as a pending backend request).

**Core:**
6. Add construction finance types + parsers (zod, following the boundary pattern already in `types/finance/`),
   and `getAll` on `financeInvoiceService` / `financePaymentService` / the new construction services.
7. Publish core >= 0.27.1; bump `echno-web` off `0.26.1`.

**Web:**
8. Replace `services/{invoices,payments}-service.ts` mock bodies with real calls through core; keep hook
   signatures identical so pages need no change. Delete the mock fixtures once no consumer remains.

## 6. Sequencing

Backend module + list endpoints first (unblocks everything), then core types + publish, then web migration.
The AR list endpoints (step 5) can ship immediately and independently. Web migration is last and mechanical.

## 7. Domain defaults (chosen; confirm actual code values before posting real data)

- **AP + GST account codes**: implement the AP posting-properties bean **config-driven**, mirroring
  `InvoicePostingProperties`. Ship with placeholder codes (AP control + GST-input) that MUST be set to the real
  chart-of-accounts values before any real posting. The posting code reads them from config, so setting the
  real values is a config change, not a code change. Flagged as the single hard prerequisite before the posting
  path goes live.
- **Construction Payment breadth**: **phased.** Phase 1 covers vendor / accounts-payable payouts (the ledger
  path with a real AP analog). Payroll / labour / utility / other voucher payees come in a later phase, since
  their ledger treatment differs and several have no counterparty entity yet.
- **GST treatment**: straight output (sales) / input (purchase) split for now; input-credit netting and
  edge cases deferred to a finance-accounting review.

## 8. Build sequence (in-house, incremental)

Executed by us, not the feature team. Each increment is its own tested PR.

1. **Construction invoice: entity + migration + DTOs + service (CRUD + list, NO ledger posting) + controller**
   (list/get/create/update, tenant-guarded). Mirrors the AR invoice module structure. Lets the web pages read
   and write real construction invoices immediately. Lowest risk (no financial posting).
2. **AR list endpoints** (`GET /finance/{invoices,payments}/web`) on the existing controllers, plus construction
   payment entity + CRUD + list. Independent, read-only.
3. **Ledger posting** (the financially sensitive part): sales/service materialize an AR invoice via
   `InvoiceService.issue`; purchase/expense post AP journal entries via a new config-driven posting bean. Done
   carefully with the section-7 code values confirmed first.
4. **Core**: construction finance types + parsers + `getAll`; publish >= 0.27.1.
5. **Web**: migrate `{invoices,payments}-service.ts` off the mock fixtures; bump the core dependency.
