package org.tornotron.echno_backend.receipt;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * What a receipt is linked to, for the expenses area of the access matrix, and the receipts a filter
 * admits as a query condition. Whether the caller may act on one is {@code ExpenseAccess}'s to say,
 * since receipts sit in that area.
 *
 * <p>A receipt's facts are its project, the employee who issued it as its owner and the customer it
 * was received from.
 */
final class ReceiptAccess {

    private ReceiptAccess() {
    }

    /** The facts of a receipt as it stands. */
    static RecordFacts facts(Receipt receipt) {
        return facts(receipt.getOrganization().getId(), receipt.getProjectId(), receipt.getIssuedBy(),
                receipt.getCustomerId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long issuedBy, UUID customerId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(issuedBy == null ? Set.of() : Set.of(issuedBy))
                .withCustomers(customerId == null ? Set.of() : Set.of(customerId));
    }

    /** The receipts a filter admits, as a query condition, so paging and totals stay right. */
    static Specification<Receipt> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on a receipt in a query. */
    static Predicate admits(From<?, Receipt> receipt, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(receipt.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = receipt.get("projectId");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(receipt, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(receipt, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** A receipt names no assignee or vendor, so only the owner and customer links can match. */
    private static Optional<Predicate> links(From<?, Receipt> receipt, CriteriaBuilder cb, AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(receipt.get("issuedBy").in(links.ownerIds()));
        }
        if (!links.customerIds().isEmpty()) {
            anyOf.add(receipt.get("customerId").in(links.customerIds()));
        }
        return anyOf.isEmpty() ? Optional.empty() : Optional.of(cb.or(anyOf.toArray(Predicate[]::new)));
    }
}
