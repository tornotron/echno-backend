package org.tornotron.echno_backend.finance.construction.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
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

    /**
     * The order every page of the listing is read in.
     *
     * <p>A page with no order is a page in whatever order the storage engine happened to produce,
     * which on a distributed engine is not stable between two requests: the same voucher can
     * appear on page one and again on page two while another never appears at all. That is the
     * failure this listing exists to stop, so leaving the order out would reintroduce it one level
     * down. It matters especially here, because the endpoint used to take a {@code Pageable} that
     * could at least carry a caller's sort.
     *
     * <p>Newest payment first is what a payments screen wants. The payment number breaks the tie
     * between two vouchers dated the same day, and it is unique per tenant, so no two rows compare
     * equal and no page boundary can fall inside a run of ties. Same shape and same reason as the
     * AR invoice listing's order.
     *
     * <p>Lives here beside the filters because it is part of the same thing: the shape of the
     * query this register is read with. The test reads it from here rather than restating it, so a
     * test cannot pass against an order the production code does not use.
     */
    public static final Sort LIST_ORDER =
            Sort.by(Sort.Order.desc("paymentDate"), Sort.Order.desc("paymentNumber"));

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
