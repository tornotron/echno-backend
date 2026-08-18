# Construction finance: ledger codes, approval workflow, and budgeting

Status: proposed (2026-08-19). Addendum to `2026-08-12-construction-finance-design.md`. Captures the business
input gathered on issue tornotron/echno-backend#341 (Anand, Abin) and turns it into a build plan. The base spec's
increments 1, 2, 4 and 5 are done (construction invoice #339, payment #340, core 0.28.0, web read #190); its
increment 3 (ledger posting) was held for the account codes, which #341 now resolves. Anand's feedback also adds
two items the base spec did not cover: an approval workflow and project budgeting. This document settles the
codes, folds the approval step into the posting design, and scopes budgeting as its own phase.

## 1. Ledger account codes (resolves base spec section 7)

Ledger posting for construction purchase and expense invoices needs two chart-of-accounts codes that were left
as placeholders in the base spec. The receivable side is already live: Accounts Receivable `1200`, GST Output
`2210` (`InvoicePostingProperties`, `finance.invoice.*` in `application.yml`). The payable side needs:

| Account | Code | Basis |
|---|---|---|
| Accounts Payable (control) | `2100` | Matches Abin's chart-of-accounts convention on #341 (liabilities `2000-2999`, Accounts Payable `2100`). |
| GST Input Credit | `1210` | Assets range (`1000-1999`) per the same convention; sits with the other receivable/asset control accounts. `1200` is already AR, so `1210` is the next free asset-control slot. |

These are defaults, not hard-coded values. Implement a `ConstructionPostingProperties` bean mirroring
`InvoicePostingProperties`, bound to `finance.construction.*`, so the real values are a config change and never a
code change. Abin confirmed Accounts Payable `2100` directly; GST Input Credit `1210` is a reasonable default in
his structure but was not called out by code, so treat it as provisional and get one explicit confirmation before
the first real posting. Nothing else in the codes is open.

## 2. Approval workflow (new, from Anand's #341 input)

The base spec posts on issue. Anand's feedback introduces an approval step ahead of payment, with these statuses:

    Pending Approval -> Approved -> Partially Paid -> Paid -> Overdue      (plus Cancelled)

This changes two things in the base design:

- **Posting trigger moves from "issue" to "Approved".** A construction invoice posts its journal entry (section 3
  of the base spec: DR expense / CR Accounts Payable + GST Input for purchase and expense; materialize an AR
  invoice for sales and service) at the moment it is approved, not when it is drafted or sent. Cancel reverses the
  entry via `postingService.reverse(...)` as before. This keeps unapproved documents out of the ledger, which is
  the point of an approval gate.
- **An audit trail is required.** Add, on the construction invoice and payment: `submitted_by` / `submitted_at`,
  `approved_by` / `approved_at`, and `payment_recorded_by`. These map directly onto Anand's "Approval / Audit
  Trail" detail-page section.

Who may approve: reuse the existing org-role model rather than inventing a new authority. Recommended gate is
`system-admin` or `project-manager` (the roles that already own project financial actions); `hr-admin` does not
approve construction invoices. The transition endpoints (`submit`, `approve`, `record-payment`, `cancel`) carry
`@PreAuthorize` on those roles, consistent with the endpoint-authorization ratchet.

Payment status stays a separate dimension from invoice status, as it already is in the model, so an approved
invoice moves Partially Paid then Paid as payments are recorded against it, and Overdue is derived from the due
date when unpaid.

## 3. Budgeting and project cost control (new, larger phase)

Anand asked for the finance module to become "an actual project cost control tool":

    Budget allocated -> Invoice amount -> Amount already spent -> Remaining budget

This is a feature in its own right, not a field, and it should be its own phase after posting and approvals are
in. The minimal shape:

- A **project budget** with **cost categories** (budget heads): a per-project allocation broken down by category
  (materials, labour, plant, overheads, and so on).
- Construction invoice lines carry a **cost category / budget head** (Anand lists this under Line Items), so each
  posted line consumes a specific budget head.
- **Spent** rolls up from approved (posted) invoice lines by budget head; **remaining** is allocated minus spent.
- A project cost-control view shows allocated, committed (approved-not-paid), spent, and remaining per head, with
  over-budget flagged.

Design questions to settle before building this phase: whether "spent" counts on approval or on payment; whether
budget heads are a fixed org-level list or per-project free text; and how change orders adjust an allocation.
These are business decisions, so this phase starts with a short design round with Anand, not code.

## 4. List and detail view (Anand's spec, web)

Anand's `invoice-payment-view.md` on #341 specifies the pages. This part is a web-only change on the existing
finance pages and needs no backend work beyond the fields above. Applying now (separate web change):

- **List columns:** number (primary identifier), project, vendor/payee, invoice date, due date, amount, payment
  status, payment method, actions (view / edit / approve / record payment).
- **Label:** rename "Total" to "Amount".
- **Status:** render as a coloured badge (green paid, yellow pending approval, and so on) so it reads at a glance.
- **Emphasis:** due date belongs in the list (what needs attention now); payment date moves to the detail page.
- **Detail page sections:** Header (number, vendor/payee, project, status, total), Invoice Information (dates,
  PO number, payment terms, tax/GST, discount, notes/attachments), Line Items (description, qty, unit, unit
  price, tax, line total, cost category/budget head), Payment Information (amount paid, balance due, payment
  dates, method, reference number), Approval / Audit Trail (created by, submitted, approved by, approval date,
  payment recorded by, activity history).

Two of these fields need backend support that the current model lacks: a **due date** and **payment terms** on
the invoice, and the audit-trail fields from section 2. The rest are already present or presentational.

## 5. Sequencing and what needs a decision

1. **Web view refinements (section 4)**: safe, no financial logic. Ship first; the audit-trail and due-date
   columns render once the backend fields below exist.
2. **Backend fields**: add `due_date`, `payment_terms`, and the section-2 audit-trail columns to the construction
   invoice/payment (migration + DTOs + core types). Mechanical, low risk.
3. **Approval workflow + ledger posting (sections 1 and 2)**: the financially sensitive increment 3 of the base
   spec, now gated on Approved. Build carefully, with the `1210` GST-input code confirmed first, and a
   concurrency-safe posting path (mirror the tested `JournalPostingService` and `InvoicePostingProperties`
   patterns). This is the item that should not ship without a review.
4. **Budgeting (section 3)**: its own phase, starts with a design round with Anand.

Items 1 and 2 are buildable now. Item 3 is a money path and should be greenlit and reviewed, not merged blind.
Item 4 is a product design round first. The one open confirmation is the GST Input Credit code (`1210` proposed).
