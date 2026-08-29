package org.tornotron.echno_backend.finance.invoice.repositories;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the optional list filters for customer invoices as a JPA {@link Specification}.
 *
 * <p>Only a non-null argument becomes a predicate, so an omitted parameter leaves that dimension
 * unfiltered rather than binding a null-typed parameter on the Postgres wire protocol. Organization
 * scoping is deliberately absent here: it comes from the Hibernate {@code orgFilter} enabled on the
 * session for the request, which applies to this criteria query and to its count query alike.
 * Restating it as a predicate would give a reader two places to check and one of them could drift.
 */
public final class InvoiceSpecifications {

    /**
     * The statuses that make an invoice a live receivable: issued to the customer and not yet
     * settled in full. A draft is not owed yet, a paid invoice is no longer owed, and a cancelled
     * one never will be.
     */
    public static final Set<InvoiceStatus> OPEN_STATUSES =
            Set.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID);

    private InvoiceSpecifications() {
    }

    /**
     * The list filters, combined with AND.
     *
     * <p>{@code openOnly} and {@code status} are independent and both are applied when both are
     * given, so asking for open invoices with a status of PAID is a legal request that matches
     * nothing. That is the honest answer to a contradictory filter, and it keeps the two parameters
     * from having to know about each other.
     *
     * @param customerId Restrict to one customer, or null for every customer.
     * @param status     Restrict to one lifecycle status, or null for every status.
     * @param openOnly   Restrict to the {@link #OPEN_STATUSES} when true; no restriction when false.
     * @return A specification matching invoices that satisfy every filter given.
     */
    public static Specification<Invoice> withFilters(UUID customerId,
                                                     InvoiceStatus status,
                                                     boolean openOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (openOnly) {
                predicates.add(root.get("status").in(OPEN_STATUSES));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
