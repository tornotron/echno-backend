package org.tornotron.echno_backend.expense;

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
 * The expenses area on the access matrix, which holds expenses and receipts: what the caller may do
 * to a record of either, and what an expense is linked to. A receipt's facts and query condition are
 * in {@code ReceiptAccess}; the caller's side is this bean for both.
 *
 * <p>An expense's facts are its project, the employee who incurred it as its owner and the vendor it
 * was paid to. Expenses and receipts both narrow their list and get queries by their facts, so the
 * area registers as scope aware and a grant on it may carry any scope.
 */
@Component
public class ExpenseAccess implements ScopeAwareArea {

    public static final String AREA = "expenses";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public ExpenseAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of an expense as it stands. */
    static RecordFacts facts(Expense expense) {
        return facts(expense.getOrganization().getId(), expense.getProjectId(), expense.getEmployeeId(),
                expense.getVendorId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long employeeId, Long vendorId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(employeeId == null ? Set.of() : Set.of(employeeId))
                .withVendors(vendorId == null ? Set.of() : Set.of(vendorId));
    }

    /** Which expenses and receipts the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on a record with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /** Refuses unless the caller may act this way on a record with these facts. */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this record");
        }
    }

    /**
     * Passes a record the caller may act on this way. One they may not read is reported missing with
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

    /** The expenses a filter admits, as a query condition, so paging and totals stay right. */
    static Specification<Expense> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on an expense in a query. */
    static Predicate admits(From<?, Expense> expense, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(expense.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = expense.get("projectId");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(expense, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(expense, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** An expense names no assignee or customer, so only the owner and vendor links can match. */
    private static Optional<Predicate> links(From<?, Expense> expense, CriteriaBuilder cb, AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(expense.get("employeeId").in(links.ownerIds()));
        }
        if (!links.vendorIds().isEmpty()) {
            anyOf.add(expense.get("vendorId").in(links.vendorIds()));
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
