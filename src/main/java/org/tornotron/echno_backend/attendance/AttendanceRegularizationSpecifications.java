package org.tornotron.echno_backend.attendance;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the optional register filters as a JPA {@link Specification}. Only non-null arguments
 * become predicates, so an omitted parameter leaves that dimension unfiltered rather than binding
 * a null-typed parameter on the Postgres wire protocol.
 *
 * <p>Organization scoping is deliberately absent. It comes from the Hibernate {@code orgFilter}
 * enabled per request against {@link AttendanceRegularization}, which since issue #507 is applied
 * fail-closed: an unset tenant is an error rather than an unfiltered read. Repeating the predicate
 * here would suggest the filter were optional, and the two could drift. It applies to the count
 * query behind {@code getTotalElements} for the same reason it applies to the row query, because
 * both are criteria queries over the same filtered entity.
 *
 * <p>Every id here is supplied by the caller, so none of them may be allowed to widen the read.
 * They narrow within the tenant and never across it.
 *
 * <p>Written for issue #637. The register's only listing was pending-only, and a pending request
 * has no approver by construction, so the approver the web client already shows on a decided
 * request could not be filtered on at all.
 */
public final class AttendanceRegularizationSpecifications {

    /**
     * The order every page of the register is read in.
     *
     * <p>A page with no order is a page in whatever order the storage engine happened to produce,
     * which on a distributed engine is not stable between two requests: the same request can
     * appear on page one and again on page two while another never appears at all. A register that
     * silently drops a row from a traversal is the failure this listing was added to stop, so it
     * cannot be left unordered.
     *
     * <p>Newest request first is what an approver's queue wants. The id breaks the tie between two
     * raised in the same instant, and being the primary key it is unique, so no two rows compare
     * equal and no page boundary can fall inside a run of ties.
     *
     * <p>Lives here beside the filters rather than in the service because it is part of the same
     * thing: the shape of the query this register is read with. Both paged reads share it, and the
     * test reads it from here rather than restating it, so a test cannot pass against an order the
     * production code does not use.
     */
    public static final Sort LIST_ORDER =
            Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));

    private AttendanceRegularizationSpecifications() {}

    /**
     * The register filters, as a specification that adds a predicate only for the arguments given.
     *
     * <p>{@code approvedById} answers "requests this person decided". It cannot on its own
     * distinguish an approval from a rejection, because {@code processRegularization} stamps the
     * same column pair on both outcomes. Pairing it with {@code status} is what separates the two:
     * {@code approvedById=X&status=APPROVED} is the approvals, {@code &status=REJECTED} the
     * rejections. That is why the two live on one listing rather than on an endpoint each.
     *
     * @param status       Lifecycle status of the request.
     * @param approvedById Employee id of whoever approved or rejected the request. An employee id,
     *                     and null on a request decided by a caller with no employee record in the
     *                     tenant.
     * @param requestedById Employee id of whoever raised the request. An employee id, not the
     *                     platform user id also stored on the row.
     * @return A specification matching every filter that was supplied.
     */
    public static Specification<AttendanceRegularization> withFilters(RegularizationStatus status,
                                                                     Long approvedById,
                                                                     Long requestedById) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (approvedById != null) {
                predicates.add(cb.equal(root.get("approvedById"), approvedById));
            }
            if (requestedById != null) {
                predicates.add(cb.equal(root.get("requestedById"), requestedById));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
