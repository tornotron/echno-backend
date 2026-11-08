package org.tornotron.echno_backend.asset;

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

/**
 * The asset register on the access matrix: what an asset is linked to, and which assets the caller
 * may act on.
 *
 * <p>An asset's facts are the project it is deployed on, its custodian (a related employee) and the
 * vendor that supplied it (related through a vendor link). The register records nobody as an asset's
 * creator, so a grant with scope {@code OWN} reaches no asset.
 *
 * <p>The register narrows its list and get queries by these facts, so it registers as scope aware and
 * a grant on it may carry any scope.
 */
@Component
public class AssetAccess implements ScopeAwareArea {

    public static final String AREA = "assets";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public AssetAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of an asset as it stands. */
    static RecordFacts facts(Asset asset) {
        return facts(asset.getOrganization().getId(),
                asset.getAssignedProject() == null ? null : asset.getAssignedProject().getId(),
                asset.getAssignedToId(),
                asset.getVendor() == null ? null : asset.getVendor().getId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long custodianId, Long vendorId) {
        return RecordFacts.of(organizationId, projectId)
                .withRelated(custodianId == null ? Set.of() : Set.of(custodianId))
                .withVendors(vendorId == null ? Set.of() : Set.of(vendorId));
    }

    /** Which assets the caller may act on this way. Nothing, when there is no caller. */
    AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on an asset with these facts. */
    boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on the asset. Reads are not checked here: an asset the
     * caller may not read is reported missing before this is asked.
     */
    void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this asset");
        }
    }

    /** The assets a filter admits, as a query condition, so paging and totals stay right. */
    static Specification<Asset> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /**
     * The condition {@link AccessFilter#admits} states, on an asset in a query. Takes any {@link From},
     * so a subquery over assets can use it as well as a query of them.
     */
    static Predicate admits(From<?, Asset> asset, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(asset.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = asset.get("assignedProject").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(asset, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(asset, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** An asset has no owner or customer, so only the custodian and vendor links can match. */
    private static Optional<Predicate> links(From<?, Asset> asset, CriteriaBuilder cb, AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.relatedIds().isEmpty()) {
            anyOf.add(asset.get("assignedToId").in(links.relatedIds()));
        }
        if (!links.vendorIds().isEmpty()) {
            anyOf.add(asset.get("vendor").get("id").in(links.vendorIds()));
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
