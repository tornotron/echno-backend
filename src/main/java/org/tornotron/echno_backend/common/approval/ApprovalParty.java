package org.tornotron.echno_backend.common.approval;

/**
 * One side of a self-approval comparison: whoever raised a document, or whoever is approving it.
 *
 * <p>The modules that raise approvable documents do not all record their people the same way.
 * Stock adjustments and construction invoices stamp the platform user id, because that is the
 * only identity every authenticated caller has. Attendance regularizations stamp the employee id,
 * because that is what the attendance record and the web client link a person by, and it is null
 * for a caller who holds a role in the tenant but has no employee record in it yet.
 *
 * <p>Comparing across those two spaces is what this type exists for. It carries both ids, and a
 * comparison succeeds on whichever the two sides have in common. Both ids are unique per person
 * within their own space, so a match on either is conclusive and a mismatch on either is too.
 * The alternative that was considered, comparing the display names the documents already carry,
 * was rejected: {@code requestedBy} holds an employee name where there is an employee and a
 * username where there is not, two people can share a name, and a person can change theirs.
 *
 * @param userId     the platform user id, null when the caller resolves to no user row.
 * @param employeeId the employee id in the current tenant, null when the caller has no employee
 *                   record there.
 */
public record ApprovalParty(Long userId, Long employeeId) {

    /** A party known only by their platform user id, which is how the finance modules record one. */
    public static ApprovalParty ofUser(Long userId) {
        return new ApprovalParty(userId, null);
    }

    /** True when nothing at all names this party, so no comparison can involve them. */
    public boolean isUnidentified() {
        return userId == null && employeeId == null;
    }

    /**
     * True when the two parties share at least one identity space, so a comparison between them
     * means something. Two parties can both be identified and still not be comparable, for
     * instance a regularization raised before the user id was stamped, whose raiser is known only
     * as an employee, being decided by an approver who has no employee record in the tenant.
     */
    public boolean isComparableWith(ApprovalParty other) {
        return (userId != null && other.userId != null)
                || (employeeId != null && other.employeeId != null);
    }

    /** True when a shared identity space says these two are the same person. */
    public boolean isSamePersonAs(ApprovalParty other) {
        return (userId != null && userId.equals(other.userId))
                || (employeeId != null && employeeId.equals(other.employeeId));
    }
}
