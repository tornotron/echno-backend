# Work Progress Inspection

**Date:** 2026-09-28
**Status:** Design agreed with the product owner's answers of 26-27 Sep. Step 1 builds with this
note; Steps 2 to 5 are designed here and built later. ClickUp `14zdkkvrnhd`.

## 1. What it is

Work Progress Inspection (WPI) checks whether the construction work agreed for a date was done
on that date, and if it was not, records why and when it is now expected. It is regular (every
activity, every billing period) and it comes before payment. QA/QC stays separate: it looks for
defects and is invoked when one is suspected. WPI can cite QA/QC records, but does not depend on
them.

The product owner's answers fix the scope:

1. The schedule is loaded as WBS activities with planned start and finish, dependencies,
   milestones and a responsible party. The project team keeps the actuals.
2. A delay is recorded. Nothing downstream is shifted. Each activity carries a separate
   revised (forecast) finish date. Automatic rescheduling is a later feature.
3. The first billing chain is the subcontractor's: claim, measurement, certification,
   deductions, payment recommendation. Contractor-to-client billing follows later as another
   contract type.
4. The BOQ belongs to a contract, not to a project, because a project has several contracts.
5. Retention, advance recovery, penalties or liquidated damages, taxes and other deductions are
   calculated by one framework in which each deduction is configured per contract.
6. A new approver role signs the certificate. The project manager can hold it, or the
   organization assigns it to whoever its structure says.

## 2. The chain

```
Schedule (WBS activities, dependencies, milestones, responsible party)
   |
   v
Progress inspection per activity  : done / partly done (%) / not done,
   |                                  actual dates, forecast finish, delay reason, evidence
   v
Contract BOQ (per sub-contract)   : item, unit, contract quantity, rate, linked activity
   |
   v
Claim (RA period or milestone)    : the subcontractor's claimed quantity per BOQ line
   |
   v
Measurement                       : measured and accepted quantity per claim line,
   |                                  previous certified quantity carried forward
   v
Certificate + deductions          : this bill, previous, cumulative; configured deductions;
   |                                  signed by the approver role
   v
Payment recommendation            : handed to finance as a payable
```

## 3. Where it lives

The schedule stays in the core `wbs` package and WPI becomes a new module,
`modules/workprogress` (module id `work-progress`, feature `MODULE_WORK_PROGRESS`). It is not an
extension of the inspections module.

- **The schedule is core data.** `WbsElement` already carries planned and actual dates, weight
  and a progress roll-up, and tasks, purchase orders, indents, payables and material consumption
  already hang off it. Dependencies, the milestone flag, the responsible party and the forecast
  finish are properties of the schedule itself, so they go on the core entity and table.
- **WPI is its own commercial capability.** The chain ends in money: claims, certificates and
  deductions. An organization can buy QA/QC without progress billing, or the reverse, so the
  feature key and the kill switch must be separate. Putting WPI inside inspections would make
  every progress claim depend on the QA/QC subscription.
- **The shapes differ.** An `Inspection` is built around check points, defects, NCRs and
  compliance phases. A progress record is a dated statement about one activity with quantities
  and a delay. Folding it into `Inspection` would add a column set that is empty for every QA/QC
  row. The existing `InspectionType.PROGRESS` stays as it is, for a QA/QC-style progress check.
- **Reuse, through the rules the modular monolith already enforces.** Evidence goes into the
  shared attachment store (presign, upload, register), as toolbox talks and inspection evidence
  do. The location is the core spatial node. When a later step cites QA/QC inspections, it goes
  through the inspections module's `api` package, which is the only door the ArchUnit boundary
  rules allow. Tables live in `db/changelog/modules/work-progress/`; the core schedule columns go
  in a `v4.0` changeset because the core owns that table.
- **Background work.** Step 1 has none. Any later job (a reminder for activities due for
  inspection, a claim ageing sweep) runs through `callForTenantInTransaction`, as
  `TenantJobTransactionBoundaryTest` requires.

## 4. Step 1: schedule and progress inspection (this build)

### 4.1 Schedule on the WBS activity (core)

New columns on `wbs_element`:

| Column | Meaning |
|---|---|
| `is_milestone` | A zero-duration activity (planned start equals planned finish). A milestone is done or not done, never partly done. |
| `responsible_employee_id` | The person on the team who owns the activity. Optional. |
| `responsible_sub_contract_id` | The sub-contract whose contractor executes it. Optional. This is the join Step 2 uses to find the activity's claims. |
| `forecast_end_date` | The revised finish date. Set by a progress inspection or by the project manager. The planned `end_date` is never changed by it. |

New table `wbs_dependency`: `predecessor_id`, `successor_id`, `type` (`FS`, `SS`, `FF`, `SF`,
default `FS`), `lag_days`. Both ends are in the same project; a link that would close a loop is
refused; deleting an activity removes its links. A dependency is information for now: no date is
moved because of it.

Delay is derived and never stored on the activity: for a finished activity it is the actual
finish minus the planned finish; for an open one it is the later of the forecast finish and today
(once the planned finish has passed) minus the planned finish. Zero or less shows as on time.

Access: reading the schedule is for any member of the organization (the site team needs it to
record progress); changing it is for `system-admin` and `project-manager`. Today every WBS
endpoint is `system-admin` only, which would stop a project manager from maintaining the schedule
and a site engineer from seeing it.

### 4.2 Progress inspection (module)

Table `work_progress_inspection`, one row per inspection of one activity, append-only:

| Field | Rule |
|---|---|
| `wbs_element_id`, `project_id` | A leaf activity of a project in the caller's organization. A parent's progress is rolled up from its children, so it is never inspected directly. |
| `inspection_date` | Not in the future. |
| `outcome` | `DONE`, `PARTIAL`, `NOT_DONE`. |
| `percent_complete` | Cumulative for the activity. `DONE` is 100. `PARTIAL` is above 0 and below 100. `NOT_DONE` is 0 and is refused once earlier progress exists (record `PARTIAL` instead). A milestone takes only `DONE` or `NOT_DONE`. |
| `actual_start_date` | Needed for `DONE` and `PARTIAL` unless the activity already has one. |
| `actual_finish_date` | Needed for `DONE`, refused otherwise. Not after the inspection date. |
| `forecast_finish_date` | Optional for `PARTIAL` and `NOT_DONE`. |
| `planned_finish_date`, `delay_days` | Snapshot at recording time, so later schedule edits do not rewrite history. |
| `delay_reason`, `delay_notes` | Reason required when `delay_days` is above 0: `WEATHER`, `MATERIAL`, `LABOUR`, `EQUIPMENT`, `DESIGN_CHANGE`, `CLIENT`, `SUBCONTRACTOR`, `OTHER`, with free-text notes (required for `OTHER`). |
| `spatial_node_id` | Optional building, floor or zone where it was inspected. |
| `inspector_employee_id`, `recorded_by`, `remarks` | Who recorded it. |

Recording one applies it to the activity in the same transaction: progress (rolled up to the
parents as today), actual start, actual finish, forecast finish, and status (`DONE` makes it
`COMPLETED`, `PARTIAL` makes it `IN_PROGRESS`). A completed or cancelled activity takes no new
record. Planned dates and other activities are not touched. Evidence (photos, measurement
sheets) is attached through the shared attachment store under entity type
`PROGRESS_INSPECTION_EVIDENCE`, at any time after the record exists.

Access: reading is for any member; recording is for `system-admin`, `project-manager` and
`site-engineer`. Both surfaces are twins (`/api/v1/progress-inspections` and `.../web`) under
the same guards and `@RequireSubscription(MODULE_WORK_PROGRESS)`. The feature is granted on
every plan, as the other modules were, so no organization goes dark on release.

### 4.3 Web

The project page's WBS tab shows the WBS activities: planned, actual and forecast dates, progress,
delay and predecessors, with add, edit and dependency actions for the schedule roles. When the
module is enabled, each leaf activity has a "Record progress" action and a history of its
inspections with their evidence. The existing task tree grouped by work category stays below it.

## 5. Later steps (data model fixed now, built later)

**Step 2, contract BOQ.** `contract_boq_item`: `sub_contract_id`, `item_code`, `description`,
`unit`, `contract_quantity`, `rate`, `amount`, optional `wbs_element_id`, sort order. Loaded by
hand or from an Excel upload. `contract_milestone` gains an optional `wbs_element_id` so a
payment milestone points at its schedule milestone.

**Step 3, claim and measurement.** `progress_claim`: `sub_contract_id`, claim number,
`billing_model` (`RUNNING_ACCOUNT` or `MILESTONE`), period from and to or `contract_milestone_id`,
status (`DRAFT`, `SUBMITTED`, `MEASURED`, `CERTIFIED`, `REJECTED`), claimed amount.
`progress_claim_line`: BOQ item, claimed quantity. `claim_measurement`: measured and accepted
quantity per line, measured by, date, evidence, and the progress inspections it relies on.
Previous certified quantity is the sum over earlier certified claims of the same BOQ item.

**Step 4, certification, deductions, approver.** `contract_deduction_rule` per sub-contract:
kind (`RETENTION`, `ADVANCE_RECOVERY`, `PENALTY_LD`, `TDS`, `GST`, `OTHER`), basis (percent of
the bill or fixed amount), rate, cap, enabled. `payment_certificate`: this bill, previous,
cumulative, gross certified, the computed `certificate_deduction` lines, net payable, certified
by, approved by. The approver is a new organization role, `work-approver`, and the certificate
endpoints also admit `project-manager` where the organization chooses that.

**Step 5, payment recommendation.** An approved certificate creates a `Payable` for the
sub-contract's project and activity, so finance pays it through the existing payables and
payment screens. Bank or gateway execution stays out of scope.

**After that:** contractor-to-client billing as a second contract type on the same tables, and
automatic rescheduling from recorded delays.

## 6. Defaults chosen where the answers were silent

- Delay reason is a fixed list plus free text.
- Delay is measured on the finish date only; a late start shows through the actual start.
- A progress record is final once saved. A wrong record is corrected by the project manager
  editing the activity, and the next inspection records the true state.
- Dependencies are shown, never enforced.
- The Asset Homes schedule has not been received. Until it arrives, activities are entered on the
  WBS tab or through the existing bulk endpoint; an Excel import can follow once the file's
  layout is known.
