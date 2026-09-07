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
    MATERIAL_LOW_STOCK
}
