# RA and Milestone Billing

**Date:** 2026-10-04
**Status:** v1 design. Builds Steps 2 to 5 of `2026-09-28-work-progress-inspection.md` for the
pilot. ClickUp `14zdkkvrpb4` (Running Account) and `14zdkkvrpbq` (Milestone), both children of
the WPI ticket `14zdkkvrnhd`.

## 1. Direction

Both tickets describe the same direction as the WPI note: the contractor prepares the bill, the
client's engineer inspects and measures it, the engineer or QS certifies it, the client approves
it, and payment follows. The organization using Echno is the client side (for the pilot, Asset
Homes) and the contractor is the party on a `sub_contract`. That is the "subcontractor claim"
chain the WPI note chose first, so the tickets do not change its shape. Contractor-to-client
billing stays a later contract type on the same tables.

Two points refine the WPI note without changing it:

- The tickets put the inspection, joint measurement and certification inside the bill. A bill
  therefore carries its own workflow states rather than living in separate claim, measurement
  and certificate tables (see section 3).
- Payment execution is out of scope for v1 (product owner, 28 Sep). The chain stops at final
  approval, which hands the net amount to finance as a `Payable`, exactly as Step 5 planned.

## 2. One model for both billing types

RA and Milestone bills share one header table, one adjustment table, one event log, the
supporting documents and one approval workflow. They differ only in where the gross amount
comes from:

| | Running Account | Milestone |
|---|---|---|
| Billed against | BOQ items of the contract | One `contract_milestone` of the contract |
| Period | Billing period from and to | The milestone's target date |
| Gross this bill | Sum of accepted quantity x rate per line | Milestone value x certified percent |
| Cumulative | Previous certified quantity per BOQ item | Percent of the milestone billed so far |
| Gate before certification | Every claimed line has an accepted quantity | Every mandatory requirement is completed or not applicable |

The first bill on a contract fixes its billing model; a later bill of the other model is
refused. This is the "user chooses RA or Milestone" decision from the 28 Sep comment, stored by
the bills themselves, so the core `sub_contract` table does not change.

## 3. Tables (module `work-progress`)

All tables carry `organization_id` behind `orgFilter`, live in
`db/changelog/modules/work-progress/`, and reference the core by id with foreign keys.

- **`contract_boq_item`** (WPI Step 2): sub-contract, item code (unique per contract),
  description, unit, contract quantity, rate, amount, optional schedule activity, sort order.
  Entered by hand. An item used on a bill cannot be deleted.
- **`contract_deduction_rule`** (WPI Step 4): per sub-contract, kind (`RETENTION`,
  `ADVANCE_RECOVERY`, `PENALTY_LD`, `TDS`, `GST`, `OTHER`), label, effect (`DEDUCT` or `ADD`;
  GST adds, the rest deduct by default), basis (`PERCENT` or `FIXED`), rate or amount, optional
  cumulative cap, enabled.
- **`contract_milestone_requirement`**: per contract milestone, title, type (`SCOPE`,
  `QUALITY_TEST`, `QA_QC`, `DOCUMENT`, `INSPECTION`), description, mandatory, due date, status
  (`PENDING`, `UNDER_REVIEW`, `COMPLETED`, `NOT_APPLICABLE`), remarks. This is the Requirements
  tab of the milestone mockup and the "are all requirements satisfied" decision.
- **`contract_bill`**: the header for both models. Billing model, bill number per contract
  (`RA-01`, `MB-01`), status, period or milestone, milestone value and claimed and certified
  percent, contractor's reference, location, joint measurement date, engineer and client
  representative, frozen totals (gross claimed, gross certified, previous certified, additions,
  deductions, net payable), who submitted, verified, certified and approved and when, and the
  `payable_id` created on approval. This replaces the WPI note's `progress_claim` and
  `payment_certificate`, which would have held the same totals twice.
- **`contract_bill_line`**: RA only, one per BOQ item. Snapshot of the item's code, unit,
  contract quantity and rate; previous certified quantity; claimed, measured and accepted
  quantity; remarks. This replaces `progress_claim_line` and `claim_measurement`: v1 has one
  joint measurement per bill, so a separate measurement row would always be one-to-one.
- **`contract_bill_adjustment`**: the commercial adjustments of one bill. Lines computed from
  the rules at certification, plus manual lines added before it (approved variations, extra
  items, escalation, other deductions). This is the WPI note's `certificate_deduction`.
- **`contract_bill_event`**: the bill's timeline (created, claim updated, submitted,
  measurement saved, verified, returned, certified, approved, cancelled, notes, documents added),
  with the actor and an optional note.

Supporting documents use the shared attachment store under entity type
`CONTRACT_BILL_DOCUMENT`, through the same presign, upload and register path as progress
inspection evidence.

## 4. Workflow

```
DRAFT -> SUBMITTED -> VERIFIED -> CERTIFIED -> APPROVED  (creates the Payable)
            |            |            |
            +------------+------------+--> RETURNED (correction) -> SUBMITTED again
DRAFT or RETURNED -> CANCELLED
```

- **Draft and returned**: the claim is edited (claimed quantities, or claimed percent for a
  milestone). Submitting needs at least one claimed quantity above zero, or a claimed percent.
- **Submitted**: the joint measurement is recorded (measured and accepted quantity per line, or
  the certified percent for a milestone), and can be saved more than once. Verifying needs an
  accepted quantity on every claimed line.
- **Verified**: manual adjustments can be added. Certifying applies the enabled rules, freezes
  every figure on the bill and refuses a negative net. A milestone bill also needs its mandatory
  requirements completed or not applicable.
- **Certified**: final approval. The approver must be someone other than the certifier; a
  system admin may approve their own certification and it is recorded as such
  (`SelfApprovalPolicy`). Approval creates the `Payable` for the contract's project and
  contractor with the net amount.

A certified bill that is returned loses its frozen figures and rule lines, and must be verified
and certified again. The timeline entry for the return records the certified gross, deductions
and net it had, so the superseded certification can still be read.

Only one bill per contract can be open (not approved or cancelled) at a time. That keeps the
running account consistent: the previous certified quantity of a line cannot change under an
open bill.

## 5. Money

All amounts are `BigDecimal`, in INR, scale 2, rounded half up at each stated step; quantities
are scale 3.

- Line amount = accepted quantity x rate, rounded to 2 places. Gross certified is the sum of
  the rounded line amounts.
- Milestone value = the milestone's amount, or the contract value x payment percentage when the
  amount is not set. Milestone gross = value x certified percent / 100, rounded.
- Adjustment base = gross certified + manual additions.
- A percent rule = base x rate / 100, rounded. A fixed rule is its amount. A cap limits the sum
  of that rule over the contract's certified and approved bills.
- Net payable = base + additions from rules - all deductions.

A claimed cumulative quantity above the contract quantity is refused, and the accepted quantity
cannot exceed the claimed one, so the certified cumulative quantity never passes the contract
quantity either. Excess work goes on the bill as a manual extra-item addition until variations
exist. A milestone cannot be billed past
100 percent across its bills.

## 6. Access

| Action | Roles |
|---|---|
| Read anything | Any member of the organization |
| BOQ and deduction rules | system-admin, project-manager |
| Prepare, edit, submit, cancel a bill; documents and notes | system-admin, project-manager, site-engineer |
| Measurement, verification, return; milestone requirements | system-admin, project-manager, site-engineer |
| Manual adjustments, certification | system-admin, project-manager |
| Final approval | system-admin, project-manager (not the certifier) |

The WPI note's separate `work-approver` role is deferred: the pilot organizations approve
through the project manager or admin, and a new org role also needs the role picker and the
Keycloak groups. It can be added later without a data change. Every endpoint is a twin
(`/api/v1/contract-billing` and `.../web`) under `@RequireSubscription(MODULE_WORK_PROGRESS)`:
billing is the later half of the WPI chain already designed into this module, and a second
feature key now would only make a pilot grant two things.

## 7. Web

One billing home page (product owner, 28 Sep) under Finance: every contract of every project,
with its billing model, value, certified to date, open bill and status, filterable by project.
A contract that has no bills offers "Start billing" with the choice of Running Account or
Milestone; one that has offers "New RA bill" or "New milestone bill". The contract page holds
the BOQ, the deduction rules, the milestones with their requirements, and the bills. The bill
page follows the mockups: Overview with the bill summary and status stepper, Claimed Quantities
(RA) or Milestone (requirements and percent), Supporting Documents, Timeline, and a PDF of the
bill.

## 8. The v1 cut

**Ships for the pilot:** manual BOQ per contract, deduction rules, milestone requirements, RA
and Milestone bills through approval, the common home page, supporting documents, timeline,
PDF, and the `Payable` on approval. Retention is seeded from the contract's retention
percentage when billing starts on a contract with no rules. A new RA bill lists every BOQ item
with its previous certified quantity and, where the item is linked to a schedule activity, a
suggested quantity from the activity's inspected progress; the claimed quantity starts at zero.

**Not in v1, noted for the product owner:**

- Excel import of the BOQ: no sample layout yet.
- Filling a new bill automatically from progress, measurements and QA/QC records (the "Initiate
  Billing" and "Create Next RA" request of 28 Sep). v1 only suggests quantities.
- Linking a milestone requirement to QA/QC inspections and NCRs, and the schedule milestone
  behind a contract milestone.
- Payment through Echno, partial payments against a bill, and retention release.
- A cross-project billing dashboard with charts; the home page is a table with totals.
