# Leave Management Module Documentation

A comprehensive leave management system with multi-level approval workflows, automatic balance calculations, half-day support, and real-time notifications.

---

## Table of Contents

1. [Overview](#overview)
2. [Core Concepts](#core-concepts)
3. [API Endpoints](#api-endpoints)
4. [Workflows](#workflows)
5. [DTOs Reference](#dtos-reference)
6. [Enums Reference](#enums-reference)
7. [Common Use Cases](#common-use-cases)
8. [Troubleshooting](#troubleshooting)

---

## Overview

The leave module consists of these main components:

| Component | Purpose |
|-----------|---------|
| **LeavePolicy** | Defines leave types (Casual, Sick, etc.) with quotas and rules |
| **LeaveBalance** | Tracks available/used/pending days per employee per policy per year |
| **LeaveRequest** | Employee's leave application |
| **LeaveApproval** | Approval workflow records |
| **LeaveTransaction** | Audit trail of all balance changes |
| **LeaveCalendar** | Daily entries for organization-wide visibility |
| **Notification** | In-app notifications for leave events |

---

## Core Concepts

### Leave Policy

A leave policy defines a type of leave (e.g., Casual Leave, Sick Leave) and its rules:

```
Organization
    └── LeavePolicy (Casual Leave)
            ├── annualQuota: 12 days
            ├── accrualRatePerMonth: 1.0
            ├── carryForwardLimit: 5 days
            ├── minDaysPerRequest: 0.5
            ├── maxDaysPerRequest: 5
            ├── advanceNoticeDays: 2
            └── allowHalfDay: true
```

### Leave Balance

Each employee gets a balance record per policy per year:

```
Available Balance = Opening Balance + Accrued - Used
Bookable Balance  = Available Balance - Pending
```

| Field | Description |
|-------|-------------|
| `openingBalance` | Initial balance at year start |
| `accrued` | Days earned through monthly accrual |
| `used` | Days actually taken (approved leaves) |
| `pending` | Days in pending approval (blocked but not deducted) |
| `carryForwardFromPrevious` | Days carried from last year |

### Leave Request Lifecycle

```
DRAFT → PENDING_APPROVAL → APPROVED → (CANCELLED)
                        ↘ REJECTED

PENDING_APPROVAL → WITHDRAWN (by employee)
```

### Approval Chain

Approvals follow the reporting manager hierarchy:

```
Employee → Manager → Manager's Manager → ... → Final Approver
   L1         L2            L3
```

---

## API Endpoints

### Leave Policies

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/leave-policies/web` | Create a new policy |
| GET | `/api/v1/leave-policies/web/policy?policyId=X` | Get policy by ID |
| GET | `/api/v1/leave-policies/web` | Get all policies |
| GET | `/api/v1/leave-policies/web/organization?organizationId=X` | Get policies for organization |
| GET | `/api/v1/leave-policies/web/employee?employeeId=X` | Get applicable policies for employee |
| PATCH | `/api/v1/leave-policies/web/update?policyId=X` | Update policy |
| DELETE | `/api/v1/leave-policies/web/deactivate?policyId=X` | Deactivate policy |
| POST | `/api/v1/leave-policies/web/activate?policyId=X` | Reactivate policy |
| POST | `/api/v1/leave-policies/web/duplicate?policyId=X&targetOrganizationId=Y` | Copy to another org |

### Leave Balances

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/leave-balances/web?employeeId=X&year=2024` | Get all balances |
| GET | `/api/v1/leave-balances/web/specific?employeeId=X&policyId=Y` | Get specific balance |
| GET | `/api/v1/leave-balances/web/summary?employeeId=X` | Get summary with totals |
| POST | `/api/v1/leave-balances/web/recalculate?employeeId=X` | Force recalculation |
| POST | `/api/v1/leave-balances/web/adjust` | Manual adjustment |
| GET | `/api/v1/leave-balances/web/transactions?employeeId=X` | Transaction history |
| GET | `/api/v1/leave-balances/web/transactions-by-balance?balanceId=X` | Transactions by balance |

### Leave Requests

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/leave-requests/web` | Create leave request |
| GET | `/api/v1/leave-requests/web/request?requestId=X` | Get request details |
| GET | `/api/v1/leave-requests/web/employee?employeeId=X` | Get employee's requests (paginated) |
| GET | `/api/v1/leave-requests/web/employee-by-status?employeeId=X&status=Y` | Filter by status |
| GET | `/api/v1/leave-requests/web/organization?organizationId=X` | Get org requests (paginated) |
| GET | `/api/v1/leave-requests/web/pending-approvals?approverId=X` | Requests awaiting approval |
| GET | `/api/v1/leave-requests/web/pending-approvals/count?approverId=X` | Count pending |
| PATCH | `/api/v1/leave-requests/web/update?requestId=X` | Update draft request |
| POST | `/api/v1/leave-requests/web/submit?requestId=X` | Submit for approval |
| POST | `/api/v1/leave-requests/web/cancel?requestId=X` | Cancel request |
| POST | `/api/v1/leave-requests/web/withdraw?requestId=X` | Withdraw pending request |
| GET | `/api/v1/leave-requests/web/conflicts?employeeId=X&startDate=Y&endDate=Z` | Check conflicts |
| POST | `/api/v1/leave-requests/web/calculate-days` | Calculate total days |

### Leave Approvals

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/leave-approvals/web/approve?requestId=X` | Approve request |
| POST | `/api/v1/leave-approvals/web/reject?requestId=X` | Reject request |
| POST | `/api/v1/leave-approvals/web/delegate?requestId=X` | Delegate to another |
| GET | `/api/v1/leave-approvals/web/history?requestId=X` | Approval history |
| GET | `/api/v1/leave-approvals/web/chain?requestId=X` | Full approval chain |
| GET | `/api/v1/leave-approvals/web/can-approve?requestId=X&employeeId=Y` | Check if can approve |

### Leave Calendar

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/leave-calendar/web/organization?organizationId=X&startDate=Y&endDate=Z` | Org calendar |
| GET | `/api/v1/leave-calendar/web/department?organizationId=X&department=Y&startDate=Z&endDate=W` | Department calendar |
| GET | `/api/v1/leave-calendar/web/employee?employeeId=X&startDate=Y&endDate=Z` | Employee calendar |
| GET | `/api/v1/leave-calendar/web/team?managerId=X&startDate=Y&endDate=Z` | Team calendar |
| GET | `/api/v1/leave-calendar/web/grouped?organizationId=X&startDate=Y&endDate=Z` | Grouped by date |
| GET | `/api/v1/leave-calendar/web/count?organizationId=X&date=Y` | Count on leave |

### Notifications

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/notifications/web?employeeId=X` | Get notifications (paginated) |
| GET | `/api/v1/notifications/web/unread?employeeId=X` | Unread only |
| GET | `/api/v1/notifications/web/unread-count?employeeId=X` | Unread count |
| PATCH | `/api/v1/notifications/web/read?notificationId=X` | Mark as read |
| POST | `/api/v1/notifications/web/mark-all-read?employeeId=X` | Mark all read |

---

## Workflows

### 1. Creating a Leave Policy (Admin)

```bash
POST /api/v1/leave-policies
Content-Type: application/json

{
  "organizationId": 1,
  "leaveTypeCode": "CL",
  "leaveTypeName": "Casual Leave",
  "description": "For personal matters",
  "annualQuota": 12,
  "accrualRatePerMonth": 1.0,
  "carryForwardLimit": 5,
  "carryForwardExpiryMonths": 3,
  "minDaysPerRequest": 0.5,
  "maxDaysPerRequest": 5,
  "advanceNoticeDays": 2,
  "allowHalfDay": true,
  "isPaid": true
}
```

### 2. Applying for Leave (Employee)

**Step 1: Check available balance**
```bash
GET /api/v1/leave-balances/web/summary?employeeId=123&year=2024
```

Response:
```json
{
  "employeeId": 123,
  "year": 2024,
  "balances": [
    {
      "policyId": 1,
      "leaveTypeName": "Casual Leave",
      "availableBalance": 8.0,
      "bookableBalance": 6.5,
      "used": 3.0,
      "pending": 1.5
    }
  ],
  "totalAvailable": 8.0,
  "totalUsed": 3.0,
  "totalPending": 1.5
}
```

**Step 2: Check for conflicts**
```bash
GET /api/v1/leave-requests/web/conflicts?employeeId=123&startDate=2024-03-10&endDate=2024-03-12
```

**Step 3: Create and submit leave request**
```bash
POST /api/v1/leave-requests/web
Content-Type: application/json

{
  "employeeId": 123,
  "leavePolicyId": 1,
  "startDate": "2024-03-10",
  "startHalfDayType": null,
  "endDate": "2024-03-12",
  "endHalfDayType": null,
  "reason": "Family function",
  "contactDuringLeave": "+91-9876543210",
  "handoverToId": 456,
  "handoverNotes": "Please handle client calls",
  "submitImmediately": true
}
```

### 3. Half-Day Leave Request

```bash
POST /api/v1/leave-requests/web

{
  "employeeId": 123,
  "leavePolicyId": 1,
  "startDate": "2024-03-10",
  "startHalfDayType": "FIRST_HALF",
  "endDate": "2024-03-10",
  "endHalfDayType": "FIRST_HALF",
  "reason": "Doctor appointment",
  "submitImmediately": true
}
```

This creates a 0.5-day leave request.

### 4. Multi-Day with Half-Day Boundaries

```bash
{
  "startDate": "2024-03-10",
  "startHalfDayType": "SECOND_HALF",
  "endDate": "2024-03-12",
  "endHalfDayType": "FIRST_HALF"
}
```

This calculates as: 0.5 (Mar 10) + 1.0 (Mar 11) + 0.5 (Mar 12) = 2.0 days

### 5. Approving Leave (Manager)

**Step 1: Get pending approvals**
```bash
GET /api/v1/leave-requests/web/pending-approvals?approverId=789
```

**Step 2: Review and approve**
```bash
POST /api/v1/leave-approvals/web/approve?requestId=100

{
  "approverId": 789,
  "comments": "Approved. Enjoy your time off!"
}
```

### 6. Rejecting Leave

```bash
POST /api/v1/leave-approvals/web/reject?requestId=100

{
  "approverId": 789,
  "comments": "Critical project deadline on these dates. Please reschedule."
}
```

### 7. Delegating Approval

```bash
POST /api/v1/leave-approvals/web/delegate?requestId=100

{
  "approverId": 789,
  "delegateToId": 999,
  "comments": "I'm on leave. Delegating to Team Lead."
}
```

### 8. Manual Balance Adjustment (Admin)

```bash
POST /api/v1/leave-balances/adjust

{
  "employeeId": 123,
  "leavePolicyId": 1,
  "days": 2.0,
  "reason": "Compensation for working on holiday",
  "adjustedById": 1
}
```

Use negative `days` for deductions:
```json
{
  "days": -1.0,
  "reason": "Correction for incorrectly approved leave"
}
```

---

## DTOs Reference

### LeaveRequestCreationDto

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `employeeId` | Long | Yes | Employee applying for leave |
| `leavePolicyId` | Long | Yes | Type of leave (Casual, Sick, etc.) |
| `startDate` | LocalDate | Yes | Leave start date |
| `startHalfDayType` | HalfDayType | No | FIRST_HALF, SECOND_HALF, or null for full day |
| `endDate` | LocalDate | Yes | Leave end date |
| `endHalfDayType` | HalfDayType | No | FIRST_HALF, SECOND_HALF, or null for full day |
| `reason` | String | Yes | Reason for leave (max 1000 chars) |
| `contactDuringLeave` | String | No | Emergency contact (max 100 chars) |
| `handoverToId` | Long | No | Employee ID to handle work |
| `handoverNotes` | String | No | Instructions for handover (max 500 chars) |
| `submitImmediately` | Boolean | No | `true` to submit, `false` for draft (default: false) |

### LeavePolicyCreationDto

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `organizationId` | Long | Yes | - | Organization this policy belongs to |
| `leaveTypeCode` | String | Yes | - | Unique code (e.g., "CL", "SL") |
| `leaveTypeName` | String | Yes | - | Display name |
| `description` | String | No | - | Policy description |
| `annualQuota` | Double | Yes | - | Total days per year |
| `accrualRatePerMonth` | Double | No | quota/12 | Days earned per month |
| `carryForwardLimit` | Double | No | 0 | Max days to carry forward |
| `carryForwardExpiryMonths` | Integer | No | null | Months until carry-forward expires |
| `minDaysPerRequest` | Double | No | 0.5 | Minimum days per request |
| `maxDaysPerRequest` | Double | No | null | Maximum days per request |
| `advanceNoticeDays` | Integer | No | 0 | Days in advance required |
| `requiresAttachment` | Boolean | No | false | Requires supporting document |
| `attachmentRequiredAfterDays` | Integer | No | null | Attachment needed if > X days |
| `applicableGenders` | String | No | "ALL" | "MALE", "FEMALE", or "ALL" |
| `minServiceMonths` | Integer | No | 0 | Minimum service required |
| `allowHalfDay` | Boolean | No | true | Allow half-day leaves |
| `isPaid` | Boolean | No | true | Paid or unpaid leave |
| `displayOrder` | Integer | No | 0 | Sort order in UI |

### LeaveApprovalActionDto

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `approverId` | Long | Yes | Employee performing the action |
| `comments` | String | No | Comments for the action |
| `delegateToId` | Long | No* | *Required for delegate action |

### LeaveBalanceAdjustmentDto

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `employeeId` | Long | Yes | Employee to adjust |
| `leavePolicyId` | Long | Yes | Policy to adjust |
| `days` | Double | Yes | Days to add (positive) or remove (negative) |
| `reason` | String | Yes | Reason for adjustment |
| `adjustedById` | Long | Yes | Admin performing adjustment |

---

## Enums Reference

### LeaveStatus
| Value | Description |
|-------|-------------|
| `DRAFT` | Created but not submitted |
| `PENDING_APPROVAL` | Awaiting approval |
| `APPROVED` | Approved and scheduled |
| `REJECTED` | Rejected by approver |
| `CANCELLED` | Cancelled after approval |
| `WITHDRAWN` | Withdrawn before approval |

### HalfDayType
| Value | Description |
|-------|-------------|
| `FULL_DAY` | Full day leave |
| `FIRST_HALF` | Morning half (typically until lunch) |
| `SECOND_HALF` | Afternoon half (typically after lunch) |

### ApprovalAction
| Value | Description |
|-------|-------------|
| `PENDING` | Awaiting action |
| `APPROVED` | Approved at this level |
| `REJECTED` | Rejected |
| `ESCALATED` | Escalated to higher level |
| `DELEGATED` | Delegated to another approver |

### TransactionType
| Value | Description |
|-------|-------------|
| `OPENING_BALANCE` | Initial balance at year start |
| `ACCRUAL` | Monthly accrual |
| `CARRY_FORWARD` | From previous year |
| `DEDUCTION` | Leave taken |
| `REVERSAL` | Undo previous transaction |
| `ADJUSTMENT` | Manual adjustment |
| `EXPIRY` | Expired carry-forward |

### NotificationType
| Value | Description |
|-------|-------------|
| `LEAVE_REQUEST_SUBMITTED` | Sent to employee on submit |
| `LEAVE_PENDING_APPROVAL` | Sent to approver |
| `LEAVE_APPROVED` | Sent to employee on approval |
| `LEAVE_REJECTED` | Sent to employee on rejection |
| `LEAVE_CANCELLED` | Sent on cancellation |
| `LEAVE_BALANCE_LOW` | Balance running low |
| `LEAVE_REMINDER` | Generic reminder |
| `APPROVAL_DELEGATED` | Sent to delegate |

---

## Common Use Cases

### How do I check my leave balance?

```bash
GET /api/v1/leave-balances/web/summary?employeeId={your-employee-id}
```

### How do I apply for leave?

1. Check balance (GET `/api/v1/leave-balances/web/summary?employeeId={id}`)
2. Check conflicts (GET `/api/v1/leave-requests/web/conflicts?employeeId={id}&startDate=X&endDate=Y`)
3. Submit request (POST `/api/v1/leave-requests/web` with `submitImmediately: true`)

### How do I cancel approved leave?

```bash
POST /api/v1/leave-requests/web/cancel?requestId={requestId}

{
  "reason": "Plans changed"
}
```

### How do I see who's on leave today?

```bash
GET /api/v1/leave-calendar/web/count?organizationId={orgId}&date=2024-03-10
```

Or get full details:
```bash
GET /api/v1/leave-calendar/web/organization?organizationId={orgId}&startDate=2024-03-10&endDate=2024-03-10
```

### How do I see my team's leave calendar?

```bash
GET /api/v1/leave-calendar/web/team?managerId={your-employee-id}&startDate=2024-03-01&endDate=2024-03-31
```

### How do I save a draft and submit later?

**Create draft:**
```bash
POST /api/v1/leave-requests/web

{
  "employeeId": 123,
  "leavePolicyId": 1,
  "startDate": "2024-03-10",
  "endDate": "2024-03-12",
  "reason": "Vacation",
  "submitImmediately": false
}
```

**Submit later:**
```bash
POST /api/v1/leave-requests/web/submit?requestId={requestId}
```

---

## Troubleshooting

### Error: "Insufficient leave balance"

**Cause:** Your available balance minus pending requests is less than requested days.

**Solution:**
1. Check your balance summary to see available vs pending
2. Reduce leave days
3. Use a different leave type
4. Contact HR for balance adjustment

### Error: "Leave request overlaps with existing request"

**Cause:** You already have leave (approved or pending) on one or more requested dates.

**Solution:**
1. Use `/api/v1/leave-requests/web/conflicts` endpoint to see conflicting dates
2. Withdraw or cancel the existing request
3. Choose different dates

### Error: "Advance notice required"

**Cause:** The leave policy requires X days notice, and your start date is too soon.

**Solution:**
1. Check the policy's `advanceNoticeDays` setting
2. Choose a start date at least that many days in the future

### Error: "Minimum days per request not met"

**Cause:** Policy requires minimum X days (often 0.5 for half-day support).

**Solution:** Request at least the minimum days specified in the policy.

### Error: "Maximum days per request exceeded"

**Cause:** Policy limits single requests to X days maximum.

**Solution:** Split into multiple requests if needed.

### Error: "Half-day leave not allowed"

**Cause:** The leave policy has `allowHalfDay: false`.

**Solution:** Request full days only, or use a different leave type.

### Balance not updating after approval

**Cause:** Balance calculation may be cached or needs recalculation.

**Solution:**
```bash
POST /api/v1/leave-balances/web/recalculate?employeeId={employeeId}
```

### Notifications not appearing

**Check:**
1. Verify the employee ID is correct
2. Check that approval workflow triggered (request must be submitted, not draft)
3. Query unread notifications:
```bash
GET /api/v1/notifications/web/unread?employeeId={id}
```

### Request stuck in PENDING_APPROVAL

**Possible causes:**
1. Current approver hasn't taken action
2. Approver may be on leave (consider delegation)

**To check:**
```bash
GET /api/v1/leave-approvals/web/chain?requestId={requestId}
```

This shows who needs to approve and at what level.

---

## Database Tables

| Table | Purpose |
|-------|---------|
| `leave_policy` | Leave type definitions |
| `leave_balance` | Employee balance per policy per year |
| `leave_request` | Leave applications |
| `leave_approval` | Approval records |
| `leave_transaction` | Balance change audit trail |
| `leave_calendar` | Daily calendar entries |
| `notification` | In-app notifications |
| `leave_request_sequence` | Request number generator |

---

## Request Number Format

Leave requests get unique numbers: `LR-YYYY-NNNNNN`

Example: `LR-2024-000042` (42nd request in 2024 for that organization)

This is auto-generated and thread-safe (uses pessimistic locking).
