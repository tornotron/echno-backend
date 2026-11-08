package org.tornotron.echno_backend.payable;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.api.ScopeAwareArea;

/**
 * Payables on the access matrix: what a payable is linked to, and which payables the caller may act on.
 *
 * <p>A payable's facts are the project it is charged to, the employee recorded as raising it (its
 * owner) and the vendor it is owed to (related through a vendor link). The goods received note it
 * stands against is not a fact: reaching a receipt does not reach the payables raised on it.
 *
 * <p>Every payable list and get is narrowed by these facts in its query, so the area registers as
 * scope aware and a grant on it may carry any scope.
 */
@Component
public class PayableAccess implements ScopeAwareArea {

    public static final String AREA = "payables";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public PayableAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of a payable as it stands. */
    static RecordFacts facts(Payable payable) {
        return facts(payable.getOrganization().getId(),
                payable.getProject() == null ? null : payable.getProject().getId(),
                payable.getCreatedBy() == null ? null : payable.getCreatedBy().getId(),
                payable.getVendor() == null ? null : payable.getVendor().getId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long creatorId, Long vendorId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(creatorId == null ? Set.of() : Set.of(creatorId))
                .withVendors(vendorId == null ? Set.of() : Set.of(vendorId));
    }

    /** Which payables the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on a payable with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /** Refuses unless the caller may act this way on a payable with these facts. */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this payable");
        }
    }

    /**
     * Passes a payable the caller may act on this way. One they may not read is reported missing with
     * {@code missing}, as one in another organization is, so its existence is not given away; one they
     * may read but not act on this way is refused.
     */
    public void check(String action, RecordFacts record, Supplier<? extends RuntimeException> missing) {
        if (!allows(AccessPermission.READ, record)) {
            throw missing.get();
        }
        if (!AccessPermission.READ.equals(action)) {
            require(action, record);
        }
    }

    /** The payables a filter admits, as a query condition, so paging and totals stay right. */
    static Specification<Payable> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on a payable in a query. */
    static Predicate admits(From<?, Payable> payable, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(payable.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = payable.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(payable, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(payable, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** A payable names no assignee or customer, so only the owner and vendor links can match. */
    private static Optional<Predicate> links(From<?, Payable> payable, CriteriaBuilder cb, AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(payable.get("createdBy").get("id").in(links.ownerIds()));
        }
        if (!links.vendorIds().isEmpty()) {
            anyOf.add(payable.get("vendor").get("id").in(links.vendorIds()));
        }
        return anyOf.isEmpty() ? Optional.empty() : Optional.of(cb.or(anyOf.toArray(Predicate[]::new)));
    }

    private Optional<AccessSubject> subject(Long organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return currentEmployee.currentEmployee().map(employee -> new AccessSubject(
                organizationId,
                employee.getId(),
                employee.getUser() == null ? null : employee.getUser().getId()));
    }
}
