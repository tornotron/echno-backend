package org.tornotron.echno_backend.finance.construction.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the optional list filters as a JPA {@link Specification}. Only non-null
 * arguments become predicates, which avoids binding null-typed parameters on the
 * Postgres wire protocol. Organization scoping is handled separately by the
 * Hibernate {@code orgFilter} enabled per request.
 *
 * <p>Every dimension the payments screen narrows by belongs here rather than in the browser. A
 * filter applied to the array a page came back in narrows that page and not the register, so it
 * answers a different question from the one the chip above it claims, and answers it without
 * saying so. The three person filters were added for that reason under issue #638: the web list
 * was narrowing a page of twenty by verifier client-side and reading the result as the whole
 * answer.
 *
 * <p>The three carry ids from two different sequences and are not interchangeable.
 * {@code employeeId} is the payee on a salary or advance voucher and is an employee id;
 * {@code verifiedBy} and {@code raisedBy} are platform user ids stamped from the session.
 * Resolving one through the other is the wrong-person bug echno-web#346 removed, so a caller
 * passing an employee id as {@code verifiedBy} gets an empty page rather than somebody else's
 * vouchers.
 */
public final class ConstructionPaymentSpecifications {

    private ConstructionPaymentSpecifications() {}

    /**
     * The list filters, as a specification that adds a predicate only for the arguments given.
     *
     * @param projectId  Project the voucher is charged to.
     * @param vendorId   Vendor being paid.
     * @param status     Lifecycle status of the voucher.
     * @param type       Kind of payment.
     * @param payeeType  Category of party being paid.
     * @param employeeId Employee being paid. An employee id, not a user id.
     * @param verifiedBy User id that verified the voucher. A platform user id, not an employee id.
     * @param raisedBy   User id that raised the voucher. A platform user id, not an employee id.
     * @return A specification matching every filter that was supplied.
     */
    public static Specification<ConstructionPayment> withFilters(Long projectId,
                                                                 Long vendorId,
                                                                 ConstructionPaymentVoucherStatus status,
                                                                 ConstructionPaymentType type,
                                                                 ConstructionPayeeType payeeType,
                                                                 Long employeeId,
                                                                 Long verifiedBy,
                                                                 Long raisedBy) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (vendorId != null) {
                predicates.add(cb.equal(root.get("vendorId"), vendorId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (payeeType != null) {
                predicates.add(cb.equal(root.get("payeeType"), payeeType));
            }
            if (employeeId != null) {
                predicates.add(cb.equal(root.get("employeeId"), employeeId));
            }
            if (verifiedBy != null) {
                predicates.add(cb.equal(root.get("verifiedBy"), verifiedBy));
            }
            if (raisedBy != null) {
                predicates.add(cb.equal(root.get("raisedBy"), raisedBy));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
