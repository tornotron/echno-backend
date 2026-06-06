# Attendance Module

This document explains the Attendance module — what it does, how each sub-system works, and how to call every API endpoint.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Data Model](#data-model)
4. [Enums Reference](#enums-reference)
5. [Sub-systems](#sub-systems)
   - [Shift Timings](#1-shift-timings)
   - [Attendance Settings](#2-attendance-settings)
   - [Attendance (Core)](#3-attendance-core)
   - [Regularization](#4-regularization)
   - [Movement Records](#5-movement-records)
6. [Status Calculation Logic](#status-calculation-logic)
7. [Validators](#validators)
8. [API Reference](#api-reference)
9. [Typical Flows](#typical-flows)

---

## Overview

The Attendance module tracks daily employee attendance for a **project-scoped, multi-tenant** system. Each attendance record belongs to a specific employee, project, and organization (tenant).

The lifecycle of a single working day looks like this:

```
Check-In (MORNING_CLOCK_IN)
  → optional: Lunch Out (LUNCH_BREAK_START)
  → optional: Lunch In  (LUNCH_BREAK_END)
  → Check-Out (EVENING_CLOCK_OUT)
```

After clock events are recorded, the system automatically calculates:
- Total work minutes, morning/afternoon sessions, break duration, overtime
- Whether the employee was late, left early, or worked overtime
- The final `AttendanceStatus` (PRESENT, HALF_DAY, LATE, OVERTIME, etc.)

---

## Architecture

```
AttendanceController / AttendanceControllerWeb
        │
        ▼
   AttendanceService
        │
        ├─► AttendanceCalculationService   (recalculates time totals & status)
        ├─► AttendanceSettingsService       (resolves effective settings per project)
        └─► ClockEventSequenceValidator     (enforces event ordering)

AttendanceRegularizationController
        │
        ▼
   AttendanceRegularizationService

AttendanceSettingsController
        │
        ▼
   AttendanceSettingsService

ShiftTimingController
        │
        ▼
   ShiftTimingService

MovementRecordController
        │
        ▼
   MovementRecordService
        └─► AttendanceSettingsService  (checks if movement tracking is enabled)
```

All controllers have a **mobile** variant (`/api/v1/...`) and a **web** variant (`/api/v1/.../web/...`) that call the same underlying service. This separation allows different auth middleware to be applied per client type.

Multi-tenancy is enforced via a Hibernate `orgFilter` that automatically scopes all queries to the current organization (`TenantContext.getCurrentOrgId()`).

---

## Data Model

### Attendance (core record)

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `employeeId` | Long | FK to Employee |
| `employeeName` | String | Denormalized name |
| `attendanceDate` | LocalDate | The working date |
| `projectId` | Long | FK to Project |
| `projectName` | String | Denormalized name |
| `status` | AttendanceStatus | Auto-derived status |
| `shiftTiming` | ShiftTiming | The assigned shift |
| `clockEvents` | List\<ClockEvent\> | Ordered list of clock in/out events |
| `totalWorkMinutes` | Integer | Net work time (morning + afternoon) |
| `morningSessionMinutes` | Integer | Clock-in → Lunch-out duration |
| `afternoonSessionMinutes` | Integer | Lunch-in → Clock-out duration |
| `overtimeMinutes` | Integer | Minutes worked beyond overtime threshold |
| `breakDurationMinutes` | Integer | Lunch break duration |
| `isLateArrival` | Boolean | Arrived after shift start + grace period |
| `isEarlyCheckout` | Boolean | Left 30+ minutes before shift end |
| `isOvertime` | Boolean | Worked beyond overtime threshold |
| `leaveId` | Long | Reference to leave record (if on leave) |
| `leaveType` | String | Type of leave |
| `regularizations` | List\<AttendanceRegularization\> | Regularization requests |
| `movements` | List\<MovementRecord\> | Off-site movement records |
| `approvalStatus` | ApprovalStatus | PENDING / APPROVED / REJECTED |
| `approvedBy` | String | Who approved the attendance |
| `approvedAt` | LocalDateTime | When it was approved |
| `remarks` | String | Optional manager remarks |

**Unique constraint:** `(employeeId, attendanceDate, projectId)` — one attendance record per employee per day per project.

---

### ClockEvent

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `attendance` | Attendance | Parent attendance record |
| `eventType` | ClockEventType | MORNING_CLOCK_IN / LUNCH_BREAK_START / LUNCH_BREAK_END / EVENING_CLOCK_OUT |
| `eventTimestamp` | LocalDateTime | Exact time of the event |
| `latitude` | Double | GPS latitude (optional) |
| `longitude` | Double | GPS longitude (optional) |
| `gpsAccuracy` | Double | GPS accuracy in meters |
| `altitude` | Double | GPS altitude |
| `photoUrl` | String | Photo taken at clock event |
| `devicePlatform` | String | "android" / "ios" / "web" |
| `deviceId` | String | Device identifier |
| `ipAddress` | String | Client IP address |
| `isWithinGeofence` | Boolean | Whether location is within project geofence |
| `distanceFromProject` | Double | Distance from project location in meters |
| `verifiedBy` | String | Supervisor who verified this event |
| `isRegularized` | Boolean | Whether this event was added via regularization |
| `regularizationReason` | String | Reason for regularization |

---

### ShiftTiming

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `shiftName` | String | e.g., "Morning Shift", "General Shift" |
| `startTime` | LocalTime | Official shift start time |
| `endTime` | LocalTime | Official shift end time |
| `lunchBreakStart` | LocalTime | Expected lunch break start |
| `lunchBreakEnd` | LocalTime | Expected lunch break end |
| `gracePeriodMinutes` | Integer | Late arrival tolerance (default: 15 min) |
| `minimumWorkHours` | BigDecimal | Hours required for PRESENT status (default: 8.0) |
| `halfDayWorkHours` | BigDecimal | Hours required for HALF_DAY status (default: 4.0) |
| `overtimeThreshold` | BigDecimal | Hours beyond which OVERTIME is flagged (default: 9.0) |

---

### AttendanceSettings

Settings are scoped to an organization or to a specific project. When a project-specific setting exists, it takes precedence over the org-wide setting.

| Field | Type | Default | Description |
|---|---|---|---|
| `projectId` | Long | null | null = org-wide default |
| `settingName` | String | — | Human-readable label |
| `checkInOutCycles` | Integer | 2 | 1 = no lunch break; 2 = with lunch break |
| `photoRequiredOnCheckIn` | Boolean | true | Require photo at morning clock-in |
| `photoRequiredOnCheckOut` | Boolean | false | Require photo at evening clock-out |
| `geolocationRequired` | Boolean | true | Require GPS coordinates on every clock event |
| `geofenceRadiusMeters` | Integer | 100 | Allowed distance from project location |
| `movementTrackingEnabled` | Boolean | true | Allow movement records to be submitted |
| `movementPhotoRequired` | Boolean | false | Require photo on movement records |
| `movementGeolocationRequired` | Boolean | false | Require GPS on movement records |
| `autoMarkAbsentAfterHours` | Integer | 4 | Hours past shift start before auto-marking absent |
| `allowSelfRegularization` | Boolean | true | Employees can raise their own regularization requests |
| `regularizationApprovalRequired` | Boolean | true | Regularizations need manager approval |
| `maxRegularizationDaysPerMonth` | Integer | 3 | Monthly cap on approved regularizations per employee |
| `defaultShiftTimingId` | Long | null | Default shift assigned on check-in |

---

### AttendanceRegularization

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `attendance` | Attendance | The attendance record being regularized |
| `reason` | String | Employee's explanation |
| `requestedBy` | String | Employee identifier |
| `requestedAt` | LocalDateTime | Auto-set on creation |
| `approvedBy` | String | Manager identifier |
| `approvedAt` | LocalDateTime | Approval timestamp |
| `status` | RegularizationStatus | PENDING / APPROVED / REJECTED |
| `rejectionReason` | String | Why it was rejected |
| `missingEvents` | String (JSON) | Serialized list of missing clock event types |

---

### MovementRecord

| Field | Type | Description |
|---|---|---|
| `id` | Long | Primary key |
| `attendance` | Attendance | Parent attendance record |
| `employeeId` | Long | Employee who moved |
| `movementType` | MovementType | Category of movement (see enums) |
| `fromLocation` | String | Starting location name |
| `toLocation` | String | Destination location name |
| `startTime` | LocalDateTime | When movement started |
| `endTime` | LocalDateTime | When movement ended |
| `durationMinutes` | Integer | Auto-calculated from start/end |
| `distanceKm` | Double | Distance traveled |
| `purpose` | String | Reason for the movement |
| `startLatitude/Longitude` | Double | GPS at departure |
| `endLatitude/Longitude` | Double | GPS at arrival |
| `attachments` | String (JSON) | Serialized list of file URLs |
| `isVerified` | Boolean | Whether a manager has verified it |

---

## Enums Reference

### AttendanceStatus

| Value | Meaning |
|---|---|
| `PRESENT` | Full day, on time, meets minimum work hours |
| `HALF_DAY` | Worked between halfDayWorkHours and minimumWorkHours |
| `ABSENT` | No clock-in, or clocked out with insufficient hours |
| `LEAVE` | Covered by an approved leave record |
| `WEEKLY_OFF` | Day off as per work schedule |
| `HOLIDAY` | Public/company holiday |
| `LATE` | Full hours worked but arrived after grace period |
| `EARLY_CHECKOUT` | Full hours worked but left 30+ min before shift end |
| `OVERTIME` | Worked beyond the overtime threshold |
| `PENDING_REGULARIZATION` | Clocked in but hasn't clocked out yet |

### ClockEventType (sequence enforced)

```
MORNING_CLOCK_IN → LUNCH_BREAK_START → LUNCH_BREAK_END → EVENING_CLOCK_OUT
```

When `checkInOutCycles = 1`, only `MORNING_CLOCK_IN` and `EVENING_CLOCK_OUT` are accepted.

### ApprovalStatus

`PENDING` | `APPROVED` | `REJECTED`

### RegularizationStatus

`PENDING` | `APPROVED` | `REJECTED`

### MovementType

`SITE_TRAVEL` | `CLIENT_MEETING` | `VENDOR_MEETING` | `WORK_FROM_HOME` | `ON_FIELD_WORK` | `TRAINING` | `OFFICE_WORK` | `INSPECTION` | `MATERIAL_PROCUREMENT` | `SUPERVISORY_VISIT` | `OTHER`

---

## Sub-systems

### 1. Shift Timings

A **ShiftTiming** defines the time boundaries of a working day and the thresholds used to classify attendance. Every attendance record must be linked to a shift (provided during check-in).

**Setup:** Create a shift first before creating attendance settings or recording attendance.

---

### 2. Attendance Settings

Settings control validation rules enforced during check-in, clock events, movement records, and regularizations. They are resolved in this order:

```
Project-specific settings (projectId = X)
  ↓ not found?
Org-wide settings (projectId = null)
  ↓ not found?
Throw ResourceNotFoundException
```

At least one org-wide settings record **must** exist before any attendance can be recorded.

**Key behaviors driven by settings:**
- Geolocation required → check-in/clock events without lat/lng are rejected
- Photo required on check-in → `photoUrl` must not be blank
- `checkInOutCycles = 1` → lunch break events are blocked
- `allowSelfRegularization = false` → employees cannot raise regularization requests
- `regularizationApprovalRequired = false` → regularizations are auto-approved and corrected events are applied immediately

---

### 3. Attendance (Core)

**Check-in** creates a new attendance record with the first clock event (`MORNING_CLOCK_IN`). Subsequent clock events (lunch break, clock-out) are recorded via the clock-event endpoint against the existing attendance record.

After every clock event, `AttendanceCalculationService.recalculate()` is called automatically to update all duration fields and re-derive the status.

**Mark Absent:** Creates or updates an attendance record directly to `ABSENT` status with `APPROVED` approval, bypassing clock events.

**Mark Leave:** Same as mark absent, but sets `LEAVE` status and links the `leaveId` and `leaveType`.

**Approval:** A manager approves or rejects the attendance record using the approve endpoint. This updates `approvalStatus`, `approvedBy`, and `approvedAt`.

---

### 4. Regularization

If an employee missed a clock event (e.g., forgot to clock out), they raise a regularization request explaining what was missed and optionally supplying corrected clock events.

**Flow:**
1. Employee calls `POST /api/v1/attendance-regularizations/request`
2. If `regularizationApprovalRequired = true` → status is `PENDING`; corrected events are NOT applied yet
3. Manager calls `POST /api/v1/attendance-regularizations/{id}/process` with APPROVED or REJECTED
4. On approval, `recalculate()` is called to update duration and status

**Limits enforced:**
- `allowSelfRegularization` must be `true`
- Monthly approved regularization count must be below `maxRegularizationDaysPerMonth`
- Only one `PENDING` regularization per attendance record is allowed at a time

---

### 5. Movement Records

Movement records log off-site trips or activities during a work day (e.g., a site visit or client meeting). They are attached to an existing attendance record.

Movement tracking must be enabled in settings (`movementTrackingEnabled = true`). If `movementGeolocationRequired = true`, start coordinates are required.

Duration is automatically calculated from `startTime` and `endTime` when both are provided.

Managers can verify movement records via `POST /api/v1/movement-records/{id}/verify`.

---

## Status Calculation Logic

The `AttendanceCalculationService` derives status using this priority order:

```
1. Has leaveId?                    → LEAVE
2. No MORNING_CLOCK_IN event?      → ABSENT
3. totalHours >= overtimeThreshold → OVERTIME
4. totalHours >= minimumWorkHours?
     └─ isLateArrival?             → LATE
     └─ isEarlyCheckout?           → EARLY_CHECKOUT
     └─ otherwise                  → PRESENT
5. totalHours >= halfDayWorkHours? → HALF_DAY
6. Has EVENING_CLOCK_OUT?          → ABSENT
7. Otherwise                       → PENDING_REGULARIZATION
```

**Late arrival** — clock-in time is after `shiftStart + gracePeriodMinutes`.

**Early checkout** — clock-out time is before `shiftEnd - 30 minutes`.

---

## Validators

### ClockEventSequenceValidator

Enforces that clock events are recorded in the correct order and that no event type is duplicated:

```
MORNING_CLOCK_IN (order 0)
LUNCH_BREAK_START (order 1)
LUNCH_BREAK_END   (order 2)
EVENING_CLOCK_OUT (order 3)
```

Rejects any incoming event if:
- An event with equal or higher order already exists on the record
- The event type is a duplicate
- Lunch events are submitted when `checkInOutCycles = 1`

### GeofenceValidator

Uses the **Haversine formula** to calculate the great-circle distance between two GPS coordinates. The `isWithinGeofence()` method compares the result against `geofenceRadiusMeters` from the settings. This validator is available as a Spring component and is used in any part of the code that needs geofence checking.

---

## API Reference

All endpoints listed below have an identical `/web` variant under the same path with `/web` appended (e.g., `POST /api/v1/attendance/web/check-in`). Both variants call the same service logic.

---

### Shift Timings — `/api/v1/shift-timings`

#### `POST /api/v1/shift-timings`
Create a new shift timing.

**Request body:**
```json
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

**Response:** `201 Created` — ShiftTimingDto

---

#### `GET /api/v1/shift-timings`
List all shift timings for the current organization.

**Response:** `200 OK` — `List<ShiftTimingDto>`

---

#### `GET /api/v1/shift-timings/{id}`
Get a shift timing by ID.

**Response:** `200 OK` — ShiftTimingDto

---

#### `PATCH /api/v1/shift-timings/{id}`
Update one or more fields of a shift timing (all fields optional).

**Request body:** Any subset of ShiftTimingCreationDto fields.

**Response:** `200 OK` — ShiftTimingDto

---

#### `DELETE /api/v1/shift-timings/{id}`
Delete a shift timing.

**Response:** `204 No Content`

---

### Attendance Settings — `/api/v1/attendance-settings`

#### `POST /api/v1/attendance-settings`
Create attendance settings (org-wide if `projectId` is omitted, project-specific otherwise).

**Request body:**
```json
{
  "settingName": "Default Org Settings",
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

Constraints: `checkInOutCycles` 1–4, `geofenceRadiusMeters` 50–5000, `autoMarkAbsentAfterHours` 1–24, `maxRegularizationDaysPerMonth` 0–31.

**Response:** `201 Created` — AttendanceSettingsDto

---

#### `GET /api/v1/attendance-settings`
List all active settings for the current organization.

**Response:** `200 OK` — `List<AttendanceSettingsDto>`

---

#### `GET /api/v1/attendance-settings/org`
Get the org-wide (default) settings.

**Response:** `200 OK` — AttendanceSettingsDto

---

#### `GET /api/v1/attendance-settings/project/{projectId}`
Get effective settings for a project (project-specific or falls back to org-wide).

**Response:** `200 OK` — AttendanceSettingsDto

---

#### `PATCH /api/v1/attendance-settings/{id}`
Update one or more fields of a settings record.

**Response:** `200 OK` — AttendanceSettingsDto

---

#### `DELETE /api/v1/attendance-settings/{id}`
Deactivate (soft-delete) a settings record.

**Response:** `204 No Content`

---

### Attendance — `/api/v1/attendance`

#### `POST /api/v1/attendance/check-in`
Record the morning clock-in for an employee. Creates a new attendance record.

**Request body:**
```json
{
  "employeeId": 42,
  "projectId": 7,
  "shiftTimingId": 1,
  "eventTimestamp": "2026-03-04T08:55:00",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "gpsAccuracy": 5.0,
  "altitude": 920.0,
  "photoUrl": "https://cdn.example.com/photos/abc.jpg",
  "devicePlatform": "android",
  "deviceId": "device-uuid-123",
  "ipAddress": "192.168.1.10",
  "remarks": "On site"
}
```

If `geolocationRequired = true` in settings, `latitude` and `longitude` are mandatory.
If `photoRequiredOnCheckIn = true`, `photoUrl` must not be blank.

**Response:** `201 Created` — AttendanceResponseDto

---

#### `POST /api/v1/attendance/clock-event`
Record any subsequent clock event (lunch out, lunch in, clock-out) on an existing attendance record.

**Request body:**
```json
{
  "attendanceId": 100,
  "eventType": "LUNCH_BREAK_START",
  "eventTimestamp": "2026-03-04T13:02:00",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "gpsAccuracy": 5.0,
  "altitude": 920.0,
  "photoUrl": null,
  "devicePlatform": "android",
  "deviceId": "device-uuid-123",
  "ipAddress": "192.168.1.10",
  "remarks": null
}
```

Valid `eventType` values: `LUNCH_BREAK_START`, `LUNCH_BREAK_END`, `EVENING_CLOCK_OUT`.
The sequence validator will reject out-of-order or duplicate events.

**Response:** `200 OK` — AttendanceResponseDto (recalculated)

---

#### `GET /api/v1/attendance/{id}`
Get a single attendance record by ID.

**Response:** `200 OK` — AttendanceResponseDto

---

#### `GET /api/v1/attendance/employee/{employeeId}?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD`
Get all attendance records for an employee within a date range.

**Response:** `200 OK` — `List<AttendanceResponseDto>`

---

#### `GET /api/v1/attendance/project/{projectId}?date=YYYY-MM-DD&status=PRESENT&search=John&page=0&size=20`
Get paginated attendance for a project on a specific date with optional filters.

| Query param | Required | Description |
|---|---|---|
| `date` | Yes | The attendance date (ISO format) |
| `status` | No | Filter by AttendanceStatus |
| `search` | No | Search by employee name (case-insensitive) |
| `page` | No | Page number (default 0) |
| `size` | No | Page size (default 20) |

Results are sorted by `employeeName`.

**Response:** `200 OK` — `Page<AttendanceResponseDto>`

---

#### `POST /api/v1/attendance/{id}/approve`
Approve or reject an attendance record.

**Request body:**
```json
{
  "approvalStatus": "APPROVED",
  "remarks": "Verified on site"
}
```

**Response:** `200 OK` — AttendanceResponseDto

---

#### `POST /api/v1/attendance/mark-absent?employeeId=42&projectId=7&date=2026-03-04`
Mark an employee as absent for a date. Creates the record if it doesn't exist; updates it if it does.

**Response:** `200 OK` — AttendanceResponseDto

---

#### `GET /api/v1/attendance/summary/{employeeId}?month=3&year=2026`
Get the monthly attendance summary for an employee.

**Response:** `200 OK` — AttendanceSummaryDto

```json
{
  "employeeId": 42,
  "employeeName": "John Doe",
  "month": 3,
  "year": 2026,
  "totalWorkingDays": 22,
  "presentDays": 18,
  "halfDays": 1,
  "absentDays": 2,
  "leaveDays": 1,
  "weeklyOffs": 0,
  "holidays": 0,
  "lateDays": 2,
  "overtimeDays": 3,
  "totalHoursWorked": 162.5,
  "totalOvertimeHours": 4.5,
  "averageWorkHours": 7.84,
  "effectiveWorkDays": 20.3,
  "attendancePercentage": 92.27
}
```

---

#### `DELETE /api/v1/attendance/{id}`
Delete an attendance record and all associated clock events, regularizations, and movements.

**Response:** `200 OK` — `{ "message": "Attendance record deleted successfully" }`

---

### Regularization — `/api/v1/attendance-regularizations`

#### `POST /api/v1/attendance-regularizations/request?requestedBy=john.doe`
Submit a regularization request for an attendance record with missing or incorrect clock events.

**Request body:**
```json
{
  "attendanceId": 100,
  "reason": "Forgot to clock out due to emergency",
  "missingEvents": ["EVENING_CLOCK_OUT"],
  "correctedEvents": [
    {
      "eventType": "EVENING_CLOCK_OUT",
      "eventTimestamp": "2026-03-04T18:05:00",
      "latitude": 12.9716,
      "longitude": 77.5946,
      "photoUrl": null
    }
  ]
}
```

- `missingEvents` lists what was missed (used for manager review).
- `correctedEvents` are only applied immediately if `regularizationApprovalRequired = false`.

**Response:** `201 Created` — AttendanceRegularizationDto

---

#### `POST /api/v1/attendance-regularizations/{id}/process?approvedBy=manager.name`
Approve or reject a pending regularization request.

**Request body:**
```json
{
  "status": "APPROVED",
  "rejectionReason": null
}
```

On approval, attendance is recalculated.

**Response:** `200 OK` — AttendanceRegularizationDto

---

#### `GET /api/v1/attendance-regularizations/pending`
List all pending regularization requests for the current organization.

**Response:** `200 OK` — `List<AttendanceRegularizationDto>`

---

#### `GET /api/v1/attendance-regularizations/{id}`
Get a single regularization request by ID.

**Response:** `200 OK` — AttendanceRegularizationDto

---

### Movement Records — `/api/v1/movement-records`

#### `POST /api/v1/movement-records?employeeId=42`
Log a movement record for an existing attendance record. Movement tracking must be enabled in settings.

**Request body:**
```json
{
  "attendanceId": 100,
  "movementType": "SITE_TRAVEL",
  "fromLocation": "Head Office",
  "toLocation": "Project Site B",
  "startTime": "2026-03-04T10:30:00",
  "endTime": "2026-03-04T11:45:00",
  "purpose": "Inspection of foundation work",
  "remarks": "Travelled with project engineer",
  "startLatitude": 12.9716,
  "startLongitude": 77.5946,
  "endLatitude": 12.9352,
  "endLongitude": 77.6245,
  "distanceKm": 8.3,
  "attachments": ["https://cdn.example.com/doc1.pdf"]
}
```

Duration is auto-calculated when `endTime` is provided.

**Response:** `201 Created` — MovementRecordDto

---

#### `GET /api/v1/movement-records/{id}`
Get a movement record by ID.

**Response:** `200 OK` — MovementRecordDto

---

#### `GET /api/v1/movement-records/attendance/{attendanceId}`
Get all movement records for an attendance record, ordered by `startTime`.

**Response:** `200 OK` — `List<MovementRecordDto>`

---

#### `POST /api/v1/movement-records/{id}/verify?verifiedBy=manager.name`
Mark a movement record as verified by a manager.

**Response:** `200 OK` — MovementRecordDto

---

## Typical Flows

### Flow 1: Standard Full-Day Attendance (with lunch break)

```
1. POST /api/v1/attendance/check-in
   Body: { employeeId, projectId, shiftTimingId, eventTimestamp: "09:00", ... }
   → Creates attendance record (status: PENDING_REGULARIZATION)

2. POST /api/v1/attendance/clock-event
   Body: { attendanceId, eventType: "LUNCH_BREAK_START", eventTimestamp: "13:05", ... }
   → Recalculates morning session

3. POST /api/v1/attendance/clock-event
   Body: { attendanceId, eventType: "LUNCH_BREAK_END", eventTimestamp: "14:00", ... }
   → Records break duration

4. POST /api/v1/attendance/clock-event
   Body: { attendanceId, eventType: "EVENING_CLOCK_OUT", eventTimestamp: "18:10", ... }
   → Recalculates all fields, status becomes PRESENT (or LATE if arrived past grace)

5. POST /api/v1/attendance/{id}/approve
   Body: { approvalStatus: "APPROVED" }
   → Attendance is fully approved
```

### Flow 2: Single-Cycle Attendance (no lunch break, `checkInOutCycles = 1`)

```
1. POST /api/v1/attendance/check-in
   Body: { ..., eventTimestamp: "08:00" }

2. POST /api/v1/attendance/clock-event
   Body: { attendanceId, eventType: "EVENING_CLOCK_OUT", eventTimestamp: "17:00" }
   → Total work = 9 hrs → status OVERTIME (if threshold = 9.0)
```

### Flow 3: Missed Clock-Out Regularization

```
1. Employee clocked in but forgot to clock out.
   Attendance status = PENDING_REGULARIZATION

2. POST /api/v1/attendance-regularizations/request?requestedBy=john.doe
   Body: {
     attendanceId: 100,
     reason: "Phone died, couldn't clock out",
     missingEvents: ["EVENING_CLOCK_OUT"],
     correctedEvents: [{ eventType: "EVENING_CLOCK_OUT", eventTimestamp: "17:45:00" }]
   }
   → Status = PENDING (awaiting manager)

3. POST /api/v1/attendance-regularizations/100/process?approvedBy=manager
   Body: { status: "APPROVED" }
   → Attendance recalculated → status becomes PRESENT
```

### Flow 4: Setting Up a New Organization

```
1. POST /api/v1/shift-timings
   → Create "General Shift" (09:00–18:00, lunch 13:00–14:00)

2. POST /api/v1/attendance-settings
   Body: { settingName: "Org Default", projectId: null, defaultShiftTimingId: 1, ... }
   → Org-wide settings established

3. (Optional) POST /api/v1/attendance-settings
   Body: { settingName: "Project X Strict", projectId: 7, geofenceRadiusMeters: 50, ... }
   → Project-specific override

4. Employees can now check in.
```

### Flow 5: Monthly Summary Report

```
GET /api/v1/attendance/summary/42?month=3&year=2026
→ Returns totals: present, absent, late, overtime, half-days, total hours, attendance %
```
