package org.tornotron.echno_backend.attendance;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The attendance days waiting on one caller's decision, as a JPA {@link Specification}.
 *
 * <p>Written for issue #692. Since #681 a punch taken outside the project's geofence is held for a
 * named person, and the decision worked from the first day: {@code AttendanceService.approve}
 * accepts it, {@code AttendanceSecurityService.canDecideApproval} says who may take it. What was
 * missing was any way to find the record. The two listings are a project on one required date and
 * one employee over a range, so an approver could only reach a held day by already knowing it was
 * there.
 *
 * <p>The predicate is written once and used by both the listing and its count, the way the
 * regularization register's is, so the badge and the list it labels cannot come to disagree. The
 * count is a real count query rather than a page total, so it stays right past the first page.
 *
 * <h2>Why the queue is the geofence exceptions rather than everything pending</h2>
 *
 * <p>{@code approvalStatus} starts at {@code PENDING} on every record ever created:
 * {@code checkIn} builds it that way and nothing moves it until somebody decides the day. Pending
 * is therefore the resting state of an ordinary attendance record, not a request for attention, and
 * a queue of every pending record a caller may decide would hand an HR admin every attendance row
 * in the organization. {@code requiresGeofenceApproval} is the flag that separates a day held for a
 * decision from a day nobody has looked at, which is what the field was added for and what the
 * entity documents it as.
 *
 * <p>So the queue is narrower than the set {@code canDecideApproval} accepts, and deliberately: it
 * only ever offers records the approve endpoint would take, never one it would refuse.
 *
 * <h2>How it agrees with the two rules on the write path</h2>
 *
 * <p>{@code AttendanceService.requireActorMayApprove} refuses a self-approval on a flagged record
 * before it looks at any role, then defers to {@code canDecideApproval}, which is the
 * record-management roles plus the approver the record names. Read in that order:
 *
 * <ul>
 *   <li>The employee's own held days are excluded outright, for every caller. A project manager
 *       who marked from off site is refused their own record by the approve endpoint whatever
 *       roles they hold, so offering it in their queue would draw a button the server answers
 *       with a 403.</li>
 *   <li>A caller holding the record-management roles sees every other held day in the tenant,
 *       which is what carries the records {@code resolveApprover} could name nobody for. A null
 *       {@code geofenceApproverId} widens who decides rather than stranding the record, and the
 *       reporting-manager field is set on a small minority of employees today, so that is the
 *       common case rather than the edge.</li>
 *   <li>Any other caller sees the days that name them. The id is the one the server resolved and
 *       stored at the time of the punch; the chain behind it is not walked again here, so the
 *       queue cannot drift from the record.</li>
 * </ul>
 *
 * <p>Organization scoping is deliberately absent, as it is on the regularization register: it
 * comes from the Hibernate {@code orgFilter} enabled per request against {@link Attendance}, which
 * is fail-closed since issue #507. It applies to the count query for the same reason it applies to
 * the row query, both being criteria queries over the same filtered entity.
 */
public final class AttendanceApprovalQueueSpecifications {

    /**
     * The order the queue is read in.
     *
     * <p>Most recent day first, because that is the one an approver is most likely to be asked
     * about, and the same order the regularization register settled on. The id breaks the tie
     * between two days that fall on the same date, and being the primary key it is unique, so no
     * two rows compare equal and no page boundary can fall inside a run of ties. Without a total
     * order a page walk can hand back one record twice and never reach another, which on a queue
     * means a held day nobody sees.
     */
    public static final Sort QUEUE_ORDER =
            Sort.by(Sort.Order.desc("attendanceDate"), Sort.Order.desc("id"));

    private AttendanceApprovalQueueSpecifications() {
    }

    /**
     * The days waiting on this caller.
     *
     * @param callerEmployeeId The signed-in caller's employee id, resolved from the session and
     *     never from the request. An id off the request would let any caller ask for a colleague's
     *     queue, which is the defect #683 closed on the leave queue.
     * @param decidesEveryRecord Whether the caller holds the attendance record-management roles,
     *     the answer {@code AttendanceSecurityService.canManageRecords()} gives.
     * @return A specification matching the pending geofence exceptions this caller may decide.
     */
    public static Specification<Attendance> waitingOn(Long callerEmployeeId,
                                                      boolean decidesEveryRecord) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("approvalStatus"), ApprovalStatus.PENDING));
            predicates.add(cb.isTrue(root.<Boolean>get("requiresGeofenceApproval")));
            // Nobody decides their own absence from the site, whatever roles they hold.
            predicates.add(cb.notEqual(root.get("employeeId"), callerEmployeeId));
            if (!decidesEveryRecord) {
                // A record naming nobody does not match here, and does not need to: it is the
                // record managers' by the branch above.
                predicates.add(cb.equal(root.get("geofenceApproverId"), callerEmployeeId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
