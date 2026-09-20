package org.tornotron.echno_backend.leave.enums;

public enum NotificationType {
    LEAVE_REQUEST_SUBMITTED,
    LEAVE_PENDING_APPROVAL,
    LEAVE_APPROVED,
    LEAVE_REJECTED,
    LEAVE_CANCELLED,
    LEAVE_BALANCE_LOW,
    LEAVE_REMINDER,
    APPROVAL_DELEGATED,

    /**
     * A material has reached its reorder level on a project, and somebody who can act on it is
     * being told once.
     *
     * <p>The first constant here that is not about leave. The enum is named for the subsystem
     * rather than for leave, the column is {@code VARCHAR(50)}, and nothing reads the constants
     * as a closed leave-shaped set, so this costs a line rather than a migration.
     */
    MATERIAL_LOW_STOCK,

    /**
     * A reversal of a site transfer, purchase order or goods receipt was approved. Sent to the
     * store keepers of every store that has stock to put back, and to the requester.
     */
    DOCUMENT_REVERSAL_APPROVED,

    /** A reversal request was refused. Sent to the requester with the approver's reason. */
    DOCUMENT_REVERSAL_REJECTED
}
