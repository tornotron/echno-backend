package org.tornotron.echno_backend.goodsReceivedNote;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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

/**
 * Goods receipts on the access matrix: what a receipt is linked to, and which receipts the caller
 * may act on.
 *
 * <p>A receipt's facts are the project the goods were received at, the employee recorded as
 * receiving them (its owner, since a receipt records no creator of its own) and the vendor who
 * delivered (related through a vendor link). The purchase order it is received against is not a
 * fact: an order is its own area, and reaching one does not reach the receipts against it.
 *
 * <p>Receipts are narrowed by these facts in their list and get queries, so the area registers as
 * scope aware and a grant on it may carry any scope.
 */
@Component
public class GoodsReceivedNoteAccess implements ScopeAwareArea {

    public static final String AREA = "goods-receipts";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public GoodsReceivedNoteAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of a receipt as it stands. */
    public static RecordFacts facts(GoodsReceivedNote grn) {
        return facts(grn.getOrganization().getId(),
                grn.getProject() == null ? null : grn.getProject().getId(),
                grn.getReceivedBy() == null ? null : grn.getReceivedBy().getId(),
                grn.getVendor() == null ? null : grn.getVendor().getId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long receiverId, Long vendorId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(receiverId == null ? Set.of() : Set.of(receiverId))
                .withVendors(vendorId == null ? Set.of() : Set.of(vendorId));
    }

    /** Which receipts the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on a receipt with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on the receipt. Reads are not checked here: a
     * receipt the caller may not read is reported missing before this is asked.
     */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this goods receipt");
        }
    }

    /** The receipts a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<GoodsReceivedNote> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on a receipt in a query. */
    static Predicate admits(Root<GoodsReceivedNote> grn, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(grn.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = grn.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(grn, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(grn, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** A receipt has no related employee or customer, so only the owner and vendor links can match. */
    private static Optional<Predicate> links(Root<GoodsReceivedNote> grn, CriteriaBuilder cb,
                                             AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(grn.get("receivedBy").get("id").in(links.ownerIds()));
        }
        if (!links.vendorIds().isEmpty()) {
            anyOf.add(grn.get("vendor").get("id").in(links.vendorIds()));
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
