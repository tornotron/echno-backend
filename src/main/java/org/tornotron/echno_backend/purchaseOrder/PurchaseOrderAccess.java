package org.tornotron.echno_backend.purchaseOrder;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;

/**
 * Purchase orders on the access matrix: what an order is linked to, and which orders the caller may
 * act on.
 *
 * <p>An order's facts are the project it is charged to, the employee recorded as raising it (its
 * owner) and the vendor it is placed with (related through a vendor link). The indent it was raised
 * from is not a fact: an indent is its own area, and reaching one does not reach the orders raised
 * from it. A line item takes the facts of the order it sits on, so the lines of an order are visible
 * exactly when the order is.
 *
 * <p>Orders and their lines are narrowed by these facts in their list and get queries, so the area
 * registers as scope aware and a grant on it may carry any scope.
 */
@Component
public class PurchaseOrderAccess implements ScopeAwareArea {

    public static final String AREA = "purchase-orders";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public PurchaseOrderAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of an order as it stands, which are also the facts of each of its lines. */
    public static RecordFacts facts(PurchaseOrder order) {
        return facts(order.getOrganization().getId(),
                order.getProject() == null ? null : order.getProject().getId(),
                order.getCreatedBy() == null ? null : order.getCreatedBy().getId(),
                order.getVendor() == null ? null : order.getVendor().getId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long creatorId, Long vendorId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(creatorId == null ? Set.of() : Set.of(creatorId))
                .withVendors(vendorId == null ? Set.of() : Set.of(vendorId));
    }

    /** Which orders the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on an order, or a line of one, with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on the order. Reads are not checked here: an order
     * the caller may not read is reported missing before this is asked.
     */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this purchase order");
        }
    }

    /** The orders a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<PurchaseOrder> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The lines of the orders a filter admits, as a query condition on the lines. */
    public static Specification<PurchaseOrderItem> linesAdmittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root.join("purchaseOrder"), cb, filter);
    }

    /**
     * The condition {@link AccessFilter#admits} states, on an order in a query. Takes any
     * {@link From}, so a query of lines can apply it through their join to the order.
     */
    static Predicate admits(From<?, PurchaseOrder> order, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(order.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = order.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(order, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(order, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** An order has no related employee or customer, so only the owner and vendor links can match. */
    private static Optional<Predicate> links(From<?, PurchaseOrder> order, CriteriaBuilder cb,
                                             AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(order.get("createdBy").get("id").in(links.ownerIds()));
        }
        if (!links.vendorIds().isEmpty()) {
            anyOf.add(order.get("vendor").get("id").in(links.vendorIds()));
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
