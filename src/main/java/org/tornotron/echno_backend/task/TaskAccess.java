package org.tornotron.echno_backend.task;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Tasks on the access matrix: what a task is linked to, and which tasks the caller may act on.
 *
 * <p>A task's facts are the project it sits on, which every task names, the employee who created
 * it (its owner) and the employees it is assigned to (related to it). Its category is not a fact:
 * categories are a catalogue, and reaching one does not reach the tasks filed under it.
 *
 * <p>Tasks are narrowed by these facts in their list and get queries, so the area registers as
 * scope aware and a grant on it may carry any scope.
 */
@Component
public class TaskAccess implements ScopeAwareArea {

    public static final String AREA = "tasks";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public TaskAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /**
     * The facts of a task as it stands. A task loaded for the organization in force and not yet
     * given one of its own is taken to be in that organization.
     */
    public static RecordFacts facts(Task task) {
        Long organizationId = task.getOrganization() == null
                ? TenantContext.getCurrentOrgId()
                : task.getOrganization().getId();
        return facts(organizationId,
                task.getProject() == null ? null : task.getProject().getId(),
                task.getCreator() == null ? null : task.getCreator().getId(),
                task.getAssignees() == null ? Set.of() : task.getAssignees().stream()
                        .map(Employee::getId)
                        .collect(Collectors.toSet()));
    }

    static RecordFacts facts(Long organizationId, Long projectId, Long creatorId, Collection<Long> assigneeIds) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(creatorId == null ? Set.of() : Set.of(creatorId))
                .withRelated(assigneeIds == null ? Set.of() : Set.copyOf(assigneeIds));
    }

    /** Which tasks the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on a task with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on a task with these facts. Reads are not checked
     * here: a task the caller may not read is reported missing before this is asked.
     */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this task");
        }
    }

    /** The tasks a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<Task> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, query, cb, filter);
    }

    /** The condition {@link AccessFilter#admits} states, on a task in a query. */
    static Predicate admits(Root<Task> task, CriteriaQuery<?> query, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(task.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = task.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(task, query, cb, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(task, query, cb, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /**
     * A task has no customer or vendor, so only the owner and related links can match. The assignees
     * are matched in a subquery, so a task with several of them is still one row of the page.
     */
    private static Optional<Predicate> links(Root<Task> task, CriteriaQuery<?> query, CriteriaBuilder cb,
                                             AccessFilter.Links links) {
        List<Predicate> anyOf = new ArrayList<>();
        if (!links.ownerIds().isEmpty()) {
            anyOf.add(task.get("creator").get("id").in(links.ownerIds()));
        }
        if (!links.relatedIds().isEmpty()) {
            Subquery<Long> assigned = query.subquery(Long.class);
            Root<Task> same = assigned.from(Task.class);
            Join<Task, Employee> assignee = same.join("assignees");
            assigned.select(same.get("id"))
                    .where(cb.equal(same.get("id"), task.get("id")), assignee.get("id").in(links.relatedIds()));
            anyOf.add(cb.exists(assigned));
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
