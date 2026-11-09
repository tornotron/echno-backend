package org.tornotron.echno_backend.materialConsumption;

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
 * Material consumption on the access matrix: what a consumption is linked to, and which
 * consumptions the caller may act on.
 *
 * <p>A consumption's facts are the project the material was used at, which every consumption names,
 * and the employee recorded as its creator (its owner). The material, storage location, task and WBS
 * element it names are not facts: each is its own area or a catalogue entry, and reaching one does not
 * reach the consumption booked against it.
 *
 * <p>Consumptions are narrowed by these facts in their list and get queries, so the area registers as
 * scope aware and a grant on it may carry any scope.
 */
@Component
public class MaterialConsumptionAccess implements ScopeAwareArea {

    public static final String AREA = "material-consumption";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public MaterialConsumptionAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of a consumption as it stands. */
    public static RecordFacts facts(MaterialConsumption consumption) {
        return facts(consumption.getOrganization().getId(),
                consumption.getProject() == null ? null : consumption.getProject().getId(),
                consumption.getCreatedBy() == null ? null : consumption.getCreatedBy().getId());
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long creatorId) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(creatorId == null ? Set.of() : Set.of(creatorId));
    }

    /** Which consumptions the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on a consumption with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /** Refuses unless the caller may act this way on a consumption with these facts. */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this material consumption");
        }
    }

    /** The consumptions a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<MaterialConsumption> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on a consumption in a query. */
    static Predicate admits(Root<MaterialConsumption> consumption, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(consumption.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = consumption.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(consumption, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(consumption, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** A consumption has no related employee, customer or vendor, so only the owner link can match. */
    private static Optional<Predicate> links(Root<MaterialConsumption> consumption, AccessFilter.Links links) {
        if (links.ownerIds().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(consumption.get("createdBy").get("id").in(links.ownerIds()));
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
