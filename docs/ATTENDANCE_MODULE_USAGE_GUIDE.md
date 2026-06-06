# Attendance Module — Usage Guide

A practical, scenario-driven guide to **using** the Attendance module. If you need a reference of every field, enum and endpoint, read [`ATTENDANCE_MODULE.md`](./ATTENDANCE_MODULE.md) alongside this doc. This guide answers the question: *"Given X, which endpoints do I call, in what order, with what payload?"*

---

## Table of Contents

1. [Who This Guide Is For](#who-this-guide-is-for)
2. [Before You Start](#before-you-start)
3. [The Mental Model](#the-mental-model)
4. [Choosing Between Mobile and Web Endpoints](#choosing-between-mobile-and-web-endpoints)
5. [First-Time Organization Setup](#first-time-organization-setup)
6. [Configuring Attendance per Use Case](#configuring-attendance-per-use-case)
7. [Daily Attendance Patterns — Scenarios](#daily-attendance-patterns--scenarios)
8. [Manager Workflows](#manager-workflows)
9. [Regularization Workflows](#regularization-workflows)
10. [Movement Tracking Workflows](#movement-tracking-workflows)
11. [Reporting and Analytics](#reporting-and-analytics)
12. [Decision Matrix — Which Endpoint?](#decision-matrix--which-endpoint)
13. [Error Handling Cookbook](#error-handling-cookbook)
14. [Best Practices](#best-practices)
15. [FAQ](#faq)
16. [End-to-End Cookbook](#end-to-end-cookbook)

---

## Who This Guide Is For

- **Mobile / web client developers** integrating the attendance APIs into an employee or supervisor app.
- **HR / operations admins** building admin dashboards on top of the module.
- **Backend engineers** wiring custom flows (automation, payroll export, integrations).

If you're looking for how every field is computed, go to the reference doc. If you're trying to build a feature and want to know *which order to call things in*, you're in the right place.

---

## Before You Start

Every call into the Attendance module assumes the following context is already set up:

| Requirement | Why it matters |
|---|---|
| **Tenant context is resolved** (`TenantContext.getCurrentOrgId()`) | All repositories enforce the `orgFilter`. A missing or wrong org id will produce empty results or "not found" errors. |
| **An `Organization` exists** for that id | Used as a foreign key on every row created. |
| **At least one `Employee` exists and is linked to the org** | Attendance check-in resolves the employee by `(id, orgId)`. |
| **At least one `Project` exists under the org** | Attendance is project-scoped; no project ⇒ no check-in. |
| **At least one `ShiftTiming` exists** | Check-in requires `shiftTimingId`. |
| **At least one org-wide `AttendanceSettings` record exists with `projectId = null`** | All validation paths resolve settings; none configured ⇒ every call blows up with `ResourceNotFoundException`. |

If any of these are missing, seed them once before shipping the feature — see [First-Time Organization Setup](#first-time-organization-setup).

---

## The Mental Model

Think of the module as **four concentric layers**:

```
┌─────────────────────────────────────────────────┐
│  Settings (policy)                              │
│    • What do we require? (photo, GPS, geofence) │
│    • How many cycles? (with/without lunch)      │
│    • Who can regularize? How often?             │
└─────────────────────────────────────────────────┘
           │ resolved per project
           ▼
┌─────────────────────────────────────────────────┐
│  ShiftTiming (schedule)                         │
│    • Start/end, grace, min hours, OT threshold  │
└─────────────────────────────────────────────────┘
           │ referenced by
           ▼
┌─────────────────────────────────────────────────┐
│  Attendance (day record)                        │
│    • 1 per (employee, date, project)            │
│    • Holds clock events, movements, regularizations │
│    • Status auto-derived on every clock event   │
└─────────────────────────────────────────────────┘
           │ accumulates
           ▼
┌─────────────────────────────────────────────────┐
│  ClockEvents / MovementRecords / Regularizations│
│    • Append-only children that drive status     │
└─────────────────────────────────────────────────┘
```

**Golden rule:** you never write `status` directly from the outside. The calculation service re-derives it after every clock event and every approved regularization.

---

## Choosing Between Mobile and Web Endpoints

Every endpoint is mirrored at two paths:

| Client | Base path | Example |
|---|---|---|
| Mobile app | `/api/v1/<resource>` | `/api/v1/attendance/check-in` |
| Web / admin dashboard | `/api/v1/<resource>/web` | `/api/v1/attendance/web/check-in` |

Both variants **call the same service** — behavior, payloads, and responses are identical. The separation exists so infrastructure (auth middleware, rate limiting, CORS, IP allow lists) can differ per client type.

**Rule of thumb:**
- Employee-facing mobile/React Native app → `/api/v1/...`
- Internal admin panel, HR dashboard, supervisor UI → `/api/v1/.../web`
- Server-to-server cron jobs / integrations → either, depending on which middleware stack applies.

The rest of this guide drops the `/web` suffix for brevity. Substitute as needed for your client.

---

## First-Time Organization Setup

When a brand-new organization signs up, run this one-time bootstrap in order. Each step depends on the previous one.

### 1. Create a shift

```http
POST /api/v1/shift-timings
Content-Type: application/json

{
  "shiftName": "General Shift",
  "startTime": "09:00:00",
  "endTime": "18:00:00",
  "lunchBreakStart": "13:00:00",
  "lunchBreakEnd": "14:00:00",
  "gracePeriodMinutes": 15,
  "minimumWorkHours": 8.0,
  "halfDayWorkHours": 4.0,
  "overtimeThreshold": 9.0
}
```
Save the returned `id` — you'll plug it into attendance settings.

### 2. Create org-wide attendance settings

```http
POST /api/v1/attendance-settings
Content-Type: application/json

{
  "settingName": "Org Default",
  "projectId": null,
  "checkInOutCycles": 2,
  "photoRequiredOnCheckIn": true,
  "photoRequiredOnCheckOut": false,
  "geolocationRequired": true,
  "geofenceRadiusMeters": 100,
  "movementTrackingEnabled": true,
  "movementPhotoRequired": false,
  "movementGeolocationRequired": false,
  "autoMarkAbsentAfterHours": 4,
  "allowSelfRegularization": true,
  "regularizationApprovalRequired": true,
  "maxRegularizationDaysPerMonth": 3,
  "defaultShiftTimingId": 1
}
```

**Must have `projectId: null`** — this is the fallback every project without its own override will use.

### 3. (Optional) Create project overrides

```http
POST /api/v1/attendance-settings
{
  "settingName": "Site X - Strict",
  "projectId": 7,
  "checkInOutCycles": 1,
  "photoRequiredOnCheckIn": true,
  "photoRequiredOnCheckOut": true,
  "geolocationRequired": true,
  "geofenceRadiusMeters": 50,
  ...
}
```

A row with `projectId = 7` takes precedence over the org default **only** for project 7.

### 4. Verify

```http
GET /api/v1/attendance-settings/org           # org-wide record
GET /api/v1/attendance-settings/project/7     # project 7's effective (override)
GET /api/v1/attendance-settings/project/99    # project 99's effective (falls back to org)
```

After this, employees in the org can check in.

---

## Configuring Attendance per Use Case

The same module supports very different workforces by swapping a handful of settings. Below are common presets — copy and adjust.

### Preset A — Corporate office, 9-to-6 with lunch break

```json
{
  "checkInOutCycles": 2,
  "photoRequiredOnCheckIn": false,
  "photoRequiredOnCheckOut": false,
  "geolocationRequired": false,
  "geofenceRadiusMeters": 5000,
  "movementTrackingEnabled": false,
  "allowSelfRegularization": true,
  "regularizationApprovalRequired": true,
  "maxRegularizationDaysPerMonth": 3
}
```
Employees clock in, take lunch, clock out. No photo / GPS because it's a trusted office environment.

### Preset B — Field / construction site, single cycle

```json
{
  "checkInOutCycles": 1,
  "photoRequiredOnCheckIn": true,
  "photoRequiredOnCheckOut": true,
  "geolocationRequired": true,
  "geofenceRadiusMeters": 100,
  "movementTrackingEnabled": true,
  "movementPhotoRequired": true,
  "movementGeolocationRequired": true,
  "allowSelfRegularization": false,
  "regularizationApprovalRequired": true
}
```
No lunch cycle. Photo + GPS for accountability. Movement records enforced when they visit other sites. Only supervisors regularize.

### Preset C — Remote / work-from-home

```json
{
  "checkInOutCycles": 2,
  "photoRequiredOnCheckIn": false,
  "geolocationRequired": false,
  "geofenceRadiusMeters": 5000,
  "movementTrackingEnabled": true,
  "allowSelfRegularization": true,
  "regularizationApprovalRequired": false,
  "maxRegularizationDaysPerMonth": 10
}
```
Lightweight. Self-approved regularization (because nobody's watching clock-outs remotely). Movement records let people log WFH days / client meetings.

### Preset D — Strict audit mode (legal / finance)

```json
{
  "checkInOutCycles": 2,
  "photoRequiredOnCheckIn": true,
  "photoRequiredOnCheckOut": true,
  "geolocationRequired": true,
  "geofenceRadiusMeters": 50,
  "movementTrackingEnabled": true,
  "movementPhotoRequired": true,
  "movementGeolocationRequired": true,
  "allowSelfRegularization": false,
  "regularizationApprovalRequired": true,
  "maxRegularizationDaysPerMonth": 1
}
```
Everything on, everything small. Use sparingly — high friction for employees.

### Tuning tips

| Want to… | Change |
|---|---|
| Let everyone skip lunch break clock events | `checkInOutCycles: 1` |
| Block check-in without a photo | `photoRequiredOnCheckIn: true` |
| Allow flexible arrival | Increase `gracePeriodMinutes` in the shift |
| Change what counts as half-day | Adjust `halfDayWorkHours` in the shift |
| Turn overtime off | Set `overtimeThreshold` ≥ some large value (e.g. 24.0) |
| Stop employees from raising regularizations | `allowSelfRegularization: false` |
| Auto-accept all regularizations | `regularizationApprovalRequired: false` (corrected events apply immediately) |
| Cap regularization abuse | Lower `maxRegularizationDaysPerMonth` |

---

## Daily Attendance Patterns — Scenarios

All scenarios assume settings + shift + employee + project already exist.

### Scenario 1 — Standard office day (with lunch)

The most common path. Four clock events.

```http
# 1. Morning check-in
POST /api/v1/attendance/check-in
{
  "employeeId": 42,
  "projectId": 7,
  "shiftTimingId": 1,
  "eventTimestamp": "2026-04-17T09:02:00",
  "latitude": 12.9716, "longitude": 77.5946,
  "photoUrl": "https://cdn.example.com/selfies/abc.jpg"
}
# → Attendance created, status = PENDING_REGULARIZATION
# → Response contains attendanceId (save it!)

# 2. Lunch out
POST /api/v1/attendance/clock-event
{ "attendanceId": 100, "eventType": "LUNCH_BREAK_START",
  "eventTimestamp": "2026-04-17T13:03:00", "latitude": ..., "longitude": ... }

# 3. Lunch in
POST /api/v1/attendance/clock-event
{ "attendanceId": 100, "eventType": "LUNCH_BREAK_END",
  "eventTimestamp": "2026-04-17T14:00:00", "latitude": ..., "longitude": ... }

# 4. Evening clock-out
POST /api/v1/attendance/clock-event
{ "attendanceId": 100, "eventType": "EVENING_CLOCK_OUT",
  "eventTimestamp": "2026-04-17T18:10:00", "latitude": ..., "longitude": ... }
# → Status recalculated to PRESENT (if on-time) or LATE (if past grace)
```

**Tips:**
- The `attendanceId` returned from check-in must be used for every subsequent clock event that day.
- Clock events are stored in `eventTimestamp` order regardless of insertion order, but **sequence validation is strict** — you cannot submit `EVENING_CLOCK_OUT` before `LUNCH_BREAK_END` because of the ordering rule.
- The field app should persist the `attendanceId` locally so subsequent events still reach the right record even if the app is killed.

### Scenario 2 — Single-cycle field employee (no lunch)

Setting: `checkInOutCycles = 1`.

```http
# 1. Clock in
POST /api/v1/attendance/check-in
{ "employeeId": 42, "projectId": 7, "shiftTimingId": 1,
  "eventTimestamp": "2026-04-17T08:00:00", ... }

# 2. Clock out (skip lunch events entirely)
POST /api/v1/attendance/clock-event
{ "attendanceId": 100, "eventType": "EVENING_CLOCK_OUT",
  "eventTimestamp": "2026-04-17T17:00:00", ... }
# → 9 hrs worked → status = OVERTIME (if threshold 9.0)
```

Trying to submit `LUNCH_BREAK_START` in this mode yields `ValidationException: Lunch break events are not configured for this project`.

### Scenario 3 — Employee arrives late

Nothing different in the call pattern. The calculation service compares clock-in time to `shiftStart + gracePeriodMinutes`. If after:

- `isLateArrival = true`
- After the full day, if total hours ≥ `minimumWorkHours`, status becomes `LATE` (not `PRESENT`).
- If total hours < `minimumWorkHours`, normal half-day / absent logic still applies.

Managers see `LATE` on the list view and can still approve the day.

### Scenario 4 — Employee leaves early

Same as above but on the clock-out side. Clock-out before `shiftEnd - 30 min`:

- `isEarlyCheckout = true`
- If total hours ≥ `minimumWorkHours` → status `EARLY_CHECKOUT`.

### Scenario 5 — Overtime day

Any day where `totalHours >= overtimeThreshold` becomes `OVERTIME`. `overtimeMinutes` captures how much beyond the threshold.

### Scenario 6 — Employee forgets to clock out (stuck as PENDING_REGULARIZATION)

An attendance with a clock-in but no clock-out sits as `PENDING_REGULARIZATION` forever unless the employee or manager resolves it. See the [Regularization Workflows](#regularization-workflows).

### Scenario 7 — Unplanned absence (manager marks absent)

```http
POST /api/v1/attendance/mark-absent?employeeId=42&projectId=7&date=2026-04-17
```
- If no attendance record exists for that day → creates one with `status=ABSENT`, `approvalStatus=APPROVED`.
- If one already exists (e.g. the employee was mid-day) → overrides it to `ABSENT` + `APPROVED`.

**Warning:** this is destructive — any clock events on that record are left behind but the status is forced. Use only for confirmed no-shows.

### Scenario 8 — Employee is on leave

Leave is not exposed through a REST endpoint on the attendance controllers; it's driven by the `markLeave(...)` service method. Integration code that ties a leave-management system to attendance should:

1. Resolve the leave record (from your leave module).
2. Call `AttendanceService.markLeave(employeeId, projectId, date, leaveId, leaveType)` directly from a service / scheduler.

This sets `status=LEAVE`, `approvalStatus=APPROVED` and stamps `leaveId` + `leaveType` on the record. The status-derivation short-circuits to `LEAVE` whenever `leaveId != null`.

### Scenario 9 — Multi-project day (same employee, two projects)

The unique key is `(employeeId, attendanceDate, projectId)`, so the same employee **can** have two attendance rows on the same day for different projects. Handled out of the box — check in to project A in the morning, project B in the afternoon. Each is a separate lifecycle.

---

## Manager Workflows

### List today's attendance for a project

```http
GET /api/v1/attendance/project/7?date=2026-04-17
GET /api/v1/attendance/project/7?date=2026-04-17&status=LATE
GET /api/v1/attendance/project/7?date=2026-04-17&search=john&page=0&size=20
```

Paginated, sorted by employee name. Use `status` to pull a dashboard tile ("8 late today"), `search` to resolve a specific employee by partial name.

### Approve / reject a day

```http
POST /api/v1/attendance/100/approve
{ "approvalStatus": "APPROVED", "remarks": "Verified on site" }
```

**Caveat:** the mobile/web controllers currently hardcode `approvedBy = "system"`. If you need to record *which* manager approved, call the service method directly from a custom endpoint that carries the authenticated user. On the data model side, `approvedBy` and `approvedAt` are first-class fields — just wire them up on your side.

### Bulk-mark absent at end of day

Iterate over rosters and call `POST /api/v1/attendance/mark-absent` per missing employee. Typically run from a nightly job.

### Export a month

Use the summary endpoint per employee, or fan out employee queries and stitch together:

```http
GET /api/v1/attendance/employee/42?startDate=2026-04-01&endDate=2026-04-30
GET /api/v1/attendance/summary/42?month=4&year=2026
```

The raw list returns every record (with clock events + movements). The summary returns aggregated counts suitable for payroll.

---

## Regularization Workflows

### When to use regularization

- Employee forgot to clock out (phone died, emergency).
- Employee was on-site but forgot to clock in.
- Manager corrected a clock-in time.

### Flow A — Self-regularization with approval (the default)

Settings: `allowSelfRegularization: true`, `regularizationApprovalRequired: true`.

```http
# Employee submits
POST /api/v1/attendance-regularizations/request?requestedBy=john.doe
{
  "attendanceId": 100,
  "reason": "Phone died, couldn't clock out",
  "missingEvents": ["EVENING_CLOCK_OUT"],
  "correctedEvents": [
    { "eventType": "EVENING_CLOCK_OUT",
      "eventTimestamp": "2026-04-17T17:45:00",
      "projectId": 7 }
  ]
}
# → status: PENDING, correctedEvents NOT applied yet

# Manager reviews the queue
GET /api/v1/attendance-regularizations/pending

# Manager approves
POST /api/v1/attendance-regularizations/1/process?approvedBy=manager.jane
{ "status": "APPROVED" }
# → Attendance recalculated, status becomes PRESENT/LATE/etc.

# Manager rejects
POST /api/v1/attendance-regularizations/1/process?approvedBy=manager.jane
{ "status": "REJECTED", "rejectionReason": "No proof of on-site work" }
```

### Flow B — Auto-approved regularization

Settings: `regularizationApprovalRequired: false`.

```http
POST /api/v1/attendance-regularizations/request?requestedBy=john.doe
{ "attendanceId": 100, "reason": "...", "missingEvents": ["EVENING_CLOCK_OUT"],
  "correctedEvents": [ ... ] }
# → status: APPROVED immediately
# → correctedEvents are added to the attendance record right away
# NOTE: after corrected events are written, you should call recalculate on the attendance —
# the auto-approve path doesn't currently call it. Verify by re-reading the attendance and
# checking totalWorkMinutes / status.
```

### Flow C — Regularization is disabled entirely

Settings: `allowSelfRegularization: false`.

Employees calling `/request` get `ValidationException: Self regularization is not allowed for this project`. Regularizations must be done by managers through a custom admin endpoint that wraps `AttendanceService` / `AttendanceRegularizationService` directly.

### Monthly quota enforcement

When `maxRegularizationDaysPerMonth = 3`, the fourth *approved* request in the same calendar month (counted by `requestedBy`) throws `Monthly regularization limit of 3 has been reached`.

To display remaining quota in your UI, count approved regularizations for the user in the current month — there's no dedicated "quota status" endpoint, but the repo method is available if you expose one.

### One pending at a time

Only one `PENDING` regularization is allowed per attendance record. Submitting a second before the first is processed throws `A regularization request is already pending for this attendance`. Tell employees to wait for manager action.

---

## Movement Tracking Workflows

Movement records capture off-site trips during a working day.

### Preconditions

- `movementTrackingEnabled: true` on the effective settings.
- An active attendance record already exists for that day (the employee has checked in).

### Logging a movement

```http
POST /api/v1/movement-records?employeeId=42
{
  "attendanceId": 100,
  "movementType": "SITE_TRAVEL",
  "fromLocation": "Head Office",
  "toLocation": "Project Site B",
  "startTime": "2026-04-17T10:30:00",
  "endTime":   "2026-04-17T11:45:00",
  "purpose": "Inspection of foundation work",
  "startLatitude": 12.9716, "startLongitude": 77.5946,
  "endLatitude": 12.9352,   "endLongitude": 77.6245,
  "distanceKm": 8.3,
  "attachments": ["https://cdn.example.com/doc1.pdf"]
}
```

- `durationMinutes` is auto-calculated when `endTime` is provided.
- If `movementGeolocationRequired: true`, `startLatitude` and `startLongitude` are mandatory.
- `endTime` can be left null when logging an in-progress movement. Update the record later via a PATCH endpoint (not yet exposed — extend if needed).

### Verifying a movement

```http
POST /api/v1/movement-records/100/verify?verifiedBy=manager.jane
# → isVerified = true, verifiedBy/At stamped
```

### Viewing all movements of a day

```http
GET /api/v1/movement-records/attendance/100
# → sorted by startTime ASC
```

### Movement type picker

Use the `MovementType` enum in the UI dropdown:

| Enum | Suggested label |
|---|---|
| `SITE_TRAVEL` | Site travel |
| `CLIENT_MEETING` | Client meeting |
| `VENDOR_MEETING` | Vendor meeting |
| `WORK_FROM_HOME` | Work from home |
| `ON_FIELD_WORK` | Field work |
| `TRAINING` | Training |
| `OFFICE_WORK` | Office work |
| `INSPECTION` | Inspection |
| `MATERIAL_PROCUREMENT` | Material procurement |
| `SUPERVISORY_VISIT` | Supervisory visit |
| `OTHER` | Other |

---

## Reporting and Analytics

### One employee, date range

```http
GET /api/v1/attendance/employee/42?startDate=2026-04-01&endDate=2026-04-17
```
Returns **every** attendance record (with nested clock events, movements, regularizations). Best for a timeline view or per-employee details page.

### Project dashboard (single day)

```http
GET /api/v1/attendance/project/7?date=2026-04-17&status=LATE&page=0&size=50
```
Best for a daily roll call screen. Supports pagination and server-side filtering — push filters down, don't paginate on the client.

### Monthly summary

```http
GET /api/v1/attendance/summary/42?month=4&year=2026
```

Returns aggregated counts and an `attendancePercentage`. The effective-days formula used internally:

```
effectiveWorkDays = presentDays
                  + halfDays * 0.5
                  + leaveDays
                  + weeklyOffs
                  + holidays
                  + lateDays * 0.9
                  + overtimeDays
attendancePercentage = effectiveWorkDays / totalWorkingDays * 100
```

**Caveat about counts:** `totalWorkingDays` in the summary only counts `PRESENT + HALF_DAY + ABSENT + LEAVE + LATE + OVERTIME`. Statuses like `PENDING_REGULARIZATION`, `EARLY_CHECKOUT`, `WEEKLY_OFF`, `HOLIDAY` are tracked separately or not included in the divisor. Double-check formulas against your payroll rules before using this number for payouts.

### Payroll export pattern

```
for each employee in org:
    GET /api/v1/attendance/summary/{employeeId}?month=M&year=Y
    GET /api/v1/attendance/employee/{employeeId}?startDate=...&endDate=...
    Join summary + raw events and push to downstream payroll system.
```

Don't try to run payroll directly from the summary — always cross-check with the raw list during the first few months of integration.

---

## Decision Matrix — Which Endpoint?

| I want to… | Call |
|---|---|
| Create a shift | `POST /api/v1/shift-timings` |
| Change grace period or overtime threshold | `PATCH /api/v1/shift-timings/{id}` |
| Enable GPS / photo for a new project | `POST /api/v1/attendance-settings` with `projectId = X` |
| Temporarily disable movement tracking org-wide | `PATCH /api/v1/attendance-settings/{orgSettingsId}` with `movementTrackingEnabled: false` |
| Employee starts their day | `POST /api/v1/attendance/check-in` |
| Employee takes lunch | `POST /api/v1/attendance/clock-event` with `LUNCH_BREAK_START` |
| Employee returns from lunch | `POST /api/v1/attendance/clock-event` with `LUNCH_BREAK_END` |
| Employee ends their day | `POST /api/v1/attendance/clock-event` with `EVENING_CLOCK_OUT` |
| Employee didn't show up | `POST /api/v1/attendance/mark-absent` |
| Show today's team on a supervisor screen | `GET /api/v1/attendance/project/{projectId}?date=...` |
| Show this month's stats for an employee | `GET /api/v1/attendance/summary/{employeeId}?month=...&year=...` |
| Supervisor accepts the day | `POST /api/v1/attendance/{id}/approve` |
| Fix a missed clock-out | `POST /api/v1/attendance-regularizations/request` → `/process` |
| Log a client visit mid-day | `POST /api/v1/movement-records` |
| Confirm a movement really happened | `POST /api/v1/movement-records/{id}/verify` |

---

## Error Handling Cookbook

| Error message | What it means | How to recover |
|---|---|---|
| `No attendance settings configured for organization: {orgId}` | Org-wide settings row missing | Create one via `POST /api/v1/attendance-settings` with `projectId: null` |
| `Geolocation is required for attendance in this project` | `geolocationRequired=true` but the request has no lat/lng | Enable GPS on the client, or toggle the setting off for that project |
| `Photo is required for check-in` / `for this clock event` | Photo required by settings, `photoUrl` missing | Upload photo → get a URL → retry |
| `Attendance record already exists for this employee/date/project` | Employee already checked in today for this project | Use `GET /api/v1/attendance/employee/{id}` to fetch, then continue with `clock-event` on the existing record |
| `Clock event {X} has already been recorded for this attendance` | Duplicate event | Don't re-send the same type; inspect `clockEvents` on the response |
| `Clock event {X} is out of sequence` | Tried to post an event after a later-ordered event | Follow the strict order: `MORNING_CLOCK_IN → LUNCH_BREAK_START → LUNCH_BREAK_END → EVENING_CLOCK_OUT` |
| `Lunch break events are not configured for this project (checkInOutCycles=1)` | Single-cycle project got a lunch event | Skip lunch events; go straight to clock-out |
| `Self regularization is not allowed for this project. Contact your manager.` | `allowSelfRegularization=false` | Route the employee to the manager UI instead |
| `Monthly regularization limit of {N} has been reached` | Quota exceeded | Wait for next month, or bump `maxRegularizationDaysPerMonth` in settings |
| `A regularization request is already pending for this attendance` | One is already open | Wait for manager action on the existing one |
| `Regularization is not in pending state` | Trying to re-process an already-approved/rejected record | Nothing to do; fetch the current state |
| `Movement tracking is not enabled for this project` | Settings disabled it | Enable `movementTrackingEnabled` on the project or the org default |
| `Geolocation is required for movement records in this project` | `movementGeolocationRequired=true` with no start lat/lng | Provide `startLatitude` + `startLongitude` |
| `{Employee|Project|Shift timing|Attendance|Regularization|Movement record|Organization} not found` | Entity doesn't exist under the current tenant | Verify the id belongs to the same org context, and that the tenant filter is set |

Most errors surface as Spring's `ValidationException` (400) or `ResourceNotFoundException` (404). Wrap both in your client's error handler with the message shown to the end user.

---

## Best Practices

### Client-side

1. **Persist `attendanceId` immediately after check-in**, ideally in local storage. A lost id means the app can't post subsequent events.
2. **Send `eventTimestamp` from the device clock, not the server clock.** The server stores exactly what you send — this captures the *actual* event time even when the network is slow.
3. **Batch uploads gracefully.** If the phone was offline, replay events in order (`MORNING_CLOCK_IN` first, then others). The sequence validator enforces order, so don't optimize by sending them in parallel.
4. **Always capture GPS on check-in**, even when not required — you can decide later to tighten geofence policy without losing historical data.
5. **Make photo upload a prerequisite** before the check-in call; don't leave the user stuck with a pending HTTP request waiting on an S3 upload.

### Server / integration side

1. **Never write `status` directly.** It is authoritatively derived by `AttendanceCalculationService`.
2. **When building custom flows that add clock events**, call `calculationService.recalculate(attendance, shift)` before saving. (The built-in auto-approval path for regularizations does not currently do this — double-check.)
3. **Always scope lookups by org id.** All repository methods have an `...AndOrganization_Id(...)` variant — use them.
4. **Before deleting a shift**, check that no active attendance is referencing it. The cascade is not defined to protect historical data.
5. **For payroll**, reconcile `summary` counts against raw records for the first month before trusting them.

### Settings

1. **Create the org-wide default first, and never delete it.** Every fallback depends on it.
2. **Roll out policy changes via project overrides**, not by mutating the org default. Overrides are easier to revert.
3. **Keep one settings record per (org, project).** Creating a second one with the same `projectId` is not prevented by the code — either enforce uniqueness at the application layer or only `PATCH` existing records.

---

## FAQ

**Q: Can an employee have two attendance records on the same day?**
Yes — one per project. Unique key is `(employeeId, attendanceDate, projectId)`.

**Q: What happens if I don't create any `AttendanceSettings`?**
Every call into the module that resolves settings (check-in, clock-event, regularization, movement) throws `ResourceNotFoundException`. Create at least one org-wide row first.

**Q: Why is the status `PENDING_REGULARIZATION` right after check-in?**
Because there's no clock-out yet and no sufficient hours. It's the "day is incomplete" state. Once a clock-out lands, the status re-derives to `PRESENT`, `LATE`, `OVERTIME`, `HALF_DAY` or `ABSENT`.

**Q: What if my employees are supposed to skip lunch?**
Set `checkInOutCycles: 1` in the relevant settings. Lunch events will be rejected, and total work = `clockOut - clockIn`.

**Q: How do I mark a public holiday?**
Bulk-insert attendance records with `status=HOLIDAY` and `approvalStatus=APPROVED` (via a service-layer job or DB seed). There's no REST endpoint for bulk holiday marking; wire one up on top of your holiday calendar source.

**Q: Can I delete a wrong clock event?**
Not directly via the API. Delete the attendance record (`DELETE /api/v1/attendance/{id}`) and have the employee check in again, or go through regularization with corrected events.

**Q: Is there audit logging for approvals?**
`approvedBy` + `approvedAt` are written to the attendance record and regularization record. No separate audit table — wire up a CDC or `@EntityListeners` if you need one.

**Q: How do I know if an employee is currently inside the geofence?**
Call `GeofenceValidator.isWithinGeofence(...)` in your own endpoint — it's a Spring component. The module stores `isWithinGeofence` on each ClockEvent but currently always defaults it to `false`; wire a project-location lookup into the service if you need the real value.

**Q: How are timezones handled?**
`eventTimestamp` is a `LocalDateTime` — it carries no zone info. Agree on a zone per org (typically the org's primary operating zone) and render consistently on both client and reports.

---

## End-to-End Cookbook

### Recipe 1 — A normal employee day (happy path)

```http
POST /api/v1/attendance/check-in                     → attendanceId=100 (PENDING_REGULARIZATION)
POST /api/v1/attendance/clock-event (LUNCH_BREAK_START)
POST /api/v1/attendance/clock-event (LUNCH_BREAK_END)
POST /api/v1/attendance/clock-event (EVENING_CLOCK_OUT) → PRESENT
POST /api/v1/attendance/100/approve                  → Approved by supervisor
```

### Recipe 2 — Missed clock-out with self-regularization

```http
POST /api/v1/attendance/check-in                     → attendanceId=101
# (no clock-out submitted, day ends as PENDING_REGULARIZATION)

POST /api/v1/attendance-regularizations/request?requestedBy=alice
     { attendanceId:101, reason:"...", missingEvents:["EVENING_CLOCK_OUT"],
       correctedEvents:[{ eventType:"EVENING_CLOCK_OUT", eventTimestamp:"...", projectId:7 }] }
                                                     → regularizationId=9 (PENDING)
POST /api/v1/attendance-regularizations/9/process?approvedBy=bob
     { status: "APPROVED" }                          → Attendance recalculated → PRESENT
```

### Recipe 3 — Site supervisor day (single cycle + movement + photo)

```http
POST /api/v1/attendance/check-in
  (photoUrl + GPS, shiftTimingId for single-cycle shift)  → attendanceId=102
POST /api/v1/movement-records?employeeId=42
  (movementType=SITE_TRAVEL, start/end GPS, photo in attachments)
POST /api/v1/movement-records/5/verify?verifiedBy=jane    → verified
POST /api/v1/attendance/clock-event (EVENING_CLOCK_OUT)   → OVERTIME
POST /api/v1/attendance/102/approve
```

### Recipe 4 — Team lead's morning dashboard

```http
GET /api/v1/attendance/project/7?date=2026-04-17                → full roll call
GET /api/v1/attendance/project/7?date=2026-04-17&status=LATE    → who arrived late
GET /api/v1/attendance-regularizations/pending                  → queue to process
```

### Recipe 5 — Payroll run for the month

```http
for employee in roster:
  GET /api/v1/attendance/summary/{id}?month=4&year=2026
  GET /api/v1/attendance/employee/{id}?startDate=2026-04-01&endDate=2026-04-30
→ feed both into payroll calculation
```

### Recipe 6 — Onboarding a new project with stricter rules

```http
POST /api/v1/attendance-settings
  { settingName:"Plant Site A", projectId:12, geolocationRequired:true,
    geofenceRadiusMeters:75, photoRequiredOnCheckIn:true,
    photoRequiredOnCheckOut:true, checkInOutCycles:1,
    movementTrackingEnabled:true, movementGeolocationRequired:true,
    allowSelfRegularization:false, regularizationApprovalRequired:true,
    maxRegularizationDaysPerMonth:1, autoMarkAbsentAfterHours:2,
    defaultShiftTimingId:1 }
GET /api/v1/attendance-settings/project/12     → verify override resolves
```

---

## Where to go next

- For full field definitions and raw enum lists, see [`ATTENDANCE_MODULE.md`](./ATTENDANCE_MODULE.md).
- For the calculation logic in detail (status derivation, formulas), see the *Status Calculation Logic* section of the reference doc.
- For custom extensions (e.g., plugging in a real geofence check or exposing `markLeave` via REST), read `AttendanceService` and `AttendanceRegularizationService` — both are small and straightforward to extend.
