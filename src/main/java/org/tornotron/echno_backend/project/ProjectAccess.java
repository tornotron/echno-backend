package org.tornotron.echno_backend.project;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.api.ScopeAwareArea;

/**
 * Projects and their settings on the access matrix: what a project is linked to, and which projects
 * the caller may act on.
 *
 * <p>A project's facts are the project itself (so a grant held on that project, or one scoped to the
 * projects the person is assigned to, reaches it), its team (related employees) and the customer it
 * is built for (related through a client link). A project records its creator as a user, not an
 * employee, so a grant with scope {@code OWN} reaches no project.
 *
 * <p>The area's other records take a project's facts: a site structure node those of its project,
 * and a work category, which belongs to no project, only its organization's, so only a grant that
 * covers every record of the organization reaches one.
 *
 * <p>The project list and get queries narrow by these facts, so the area registers as scope aware
 * and a grant on it may carry any scope.
 */
@Component
public class ProjectAccess implements ScopeAwareArea {

    public static final String AREA = "projects";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public ProjectAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /**
     * The facts of a project as it stands, in the organization it was found in. Reads its team, so
     * call it inside the transaction that loaded the project.
     */
    public static RecordFacts facts(Long organizationId, Project project) {
        Set<Long> team = project.getEmployees() == null ? Set.of() : project.getEmployees().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet());
        return facts(organizationId, project.getId(), team, project.getCustomerId());
    }

    public static RecordFacts facts(Long organizationId, Long projectId, Set<Long> teamIds, UUID customerId) {
        return RecordFacts.of(organizationId, projectId)
                .withRelated(teamIds)
                .withCustomers(customerId == null ? Set.of() : Set.of(customerId));
    }

    /** The facts of a record that belongs to the organization and to no project, such as a work category. */
    public static RecordFacts organizationLevel(Long organizationId) {
        return RecordFacts.of(organizationId, null);
    }

    /** Which records of the area the caller may act on this way. Nothing, when there is no caller. */
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
     * Refuses unless the caller may act this way on a record with these facts. One they may not read
     * is reported missing, as one in another organization is, so its existence is not given away; one
     * they may read but not act on this way is refused.
     */
    public void requireOn(String action, RecordFacts record, Supplier<? extends RuntimeException> missing) {
        if (!allows(AccessPermission.READ, record)) {
            throw missing.get();
        }
        if (!AccessPermission.READ.equals(action)) {
            require(action, record);
        }
    }

    /** The projects a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<Project> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, query, cb, filter);
    }

    /**
     * The condition {@link AccessFilter#admits} states, on a project in a query. Takes any {@link From}
     * and the query it belongs to, so a subquery over projects can use it as well as a query of them.
     */
    public static Predicate admits(From<?, Project> project, AbstractQuery<?> query, CriteriaBuilder cb,
                                   AccessFilter filter) {
        Predicate inOrganization = cb.equal(project.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> id = project.get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(id.in(filter.projectIds()));
        }
        links(project, query, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(project, query, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(id, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** A project has no owner or vendor, so only its team and its customer can match. */
    private static Optional<Predicate> links(From<?, Project> project, AbstractQuery<?> query, CriteriaBuilder cb,
                                             AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.relatedIds().isEmpty()) {
            // A subquery over the team, so a project with several matching members is listed once.
            Subquery<Long> withMember = query.subquery(Long.class);
            Root<Project> member = withMember.from(Project.class);
            Join<Project, Employee> employee = member.join("employees");
            withMember.select(member.get("id")).where(employee.get("id").in(links.relatedIds()));
            anyOf.add(project.get("id").in(withMember));
        }
        if (!links.customerIds().isEmpty()) {
            anyOf.add(project.get("customerId").in(links.customerIds()));
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
