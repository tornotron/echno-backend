# Shift unification: employee and invite reference the structured ShiftTiming

## Problem

The backend carried two disconnected notions of a work shift.

1. A structured `ShiftTiming` entity (package `attendance`) with a real schedule:
   `shiftName`, `startTime`, `endTime`, `lunchBreakStart`/`lunchBreakEnd`,
   `gracePeriodMinutes`, `minimumWorkHours`, `halfDayWorkHours`,
   `overtimeThreshold`, and its owning `organization`. It is tenant scoped and has
   a full controller/service/repository/DTO stack. `Attendance.shiftTiming` is a
   `@ManyToOne` to it, and the attendance calculation runs against its windows and
   thresholds.

2. A free-text `String shiftTiming` on `Employee` (column `shift_timing`), also
   present on `EmployeeDto`, `EmployeeCreationDto`, `EmployeeJoinOrgDto` and on
   `InviteCodeGenerationDto`. This was a plain label such as `09:00-18:00`, never
   linked to the structured entity, so an employee's shift and the shift the
   attendance engine used were unrelated values.

The two never met: an employee's stored shift string could not drive attendance,
and attendance always required the shift id to be passed on every check-in.

## Target

Employee (and the invite that seeds a new employee) reference the structured
`ShiftTiming` by foreign key. Attendance derives an employee's shift from their
assigned `ShiftTiming` when they have one, and only falls back to a shift id
supplied on the request when they do not.

## Changes

### Employee entity

- New `shiftTiming` association: `@ManyToOne(fetch = LAZY)` to
  `attendance.ShiftTiming`, column `shift_timing_id`, nullable. This is the
  structured shift the employee works.
- The old `String shiftTiming` field is renamed to `legacyShiftTiming`
  (column `legacy_shift_timing`). It is kept, not dropped, so the original label
  survives for reference and for the name-match backfill below. It is removed from
  the response DTO.

### ProjectInviteCode entity

- New `shiftTiming` association: `@ManyToOne` to `ShiftTiming`, column
  `shift_timing_id`, nullable. An invite records the structured shift a joining
  employee should be given. The id also continues to travel inside the invite's
  `employeeDetails` JSON payload, which is what actually seeds the join.

### DTOs

- `EmployeeCreationDto`: `String shiftTiming` becomes `Long shiftTimingId`
  (optional, nullable).
- `EmployeeJoinOrgDto`: `String shiftTiming` becomes `Long shiftTimingId`
  (optional, nullable).
- `EmployeeDto` (response): `String shiftTiming` becomes `Long shiftTimingId` plus
  a nested read-only `ShiftTimingDto shiftTiming` holding the resolved shift, null
  when the employee has none.
- `InviteCodeGenerationDto`: `String shiftTiming` becomes `Long shiftTimingId`
  (nullable).

### Services and mappers

- `EmployeeService` gains a `resolveShiftTiming(shiftTimingId, organization)`
  helper: null id yields no shift, a non-null id is looked up scoped to that
  organization, and an id that does not belong to the organization is rejected with
  `ResourceNotFoundException`. Create and join both set the `shiftTiming` foreign
  key through it. The batch patch path accepts a `shiftTimingId` key.
- `EmployeeMapper` maps the association to `shiftTimingId` and to the nested
  `ShiftTimingDto` via the existing `ShiftTimingMapper`.
- `ProjectInviteCodeService` stores `shiftTimingId` in the invite payload and on
  the invite's own foreign key at generation, and reads it back into
  `EmployeeJoinOrgDto.shiftTimingId` when the code is used, so a user joining
  through an invite is given the invite's shift.

### Attendance integration

`AttendanceService.checkIn` now prefers the employee's assigned `shiftTiming` when
present, and only looks up `AttendanceCheckInDto.shiftTimingId` when the employee
has no assigned shift. The request field is now optional; a check-in is rejected
only when neither source yields a shift. The existing calculation and its null
guards are unchanged: a non-null `ShiftTiming` still reaches the builder.

## Backfill decision

Existing employees hold only the free-text label in `legacy_shift_timing`. The
migration backfills `shift_timing_id` by matching that label to a `ShiftTiming` in
the same organization on a case-insensitive, trimmed comparison of the shift name:

```
lower(trim(shift_timing.shift_name)) = lower(trim(employee.legacy_shift_timing))
```

Only a name match sets the foreign key. Labels that were free-form times such as
`09:00-18:00`, or that match no shift, leave `shift_timing_id` null; those
employees simply have no structured shift until one is assigned. The times inside a
label are deliberately not parsed into a shift, because a label carries no lunch
window, grace period or thresholds, so a parsed guess would be a worse record than
an honest null. The legacy column is retained so the match can be revisited later.
