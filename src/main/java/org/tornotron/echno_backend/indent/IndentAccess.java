package org.tornotron.echno_backend.indent;

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
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.modules.access.api.ScopeAwareArea;

/**
 * Indents on the access matrix: what an indent is linked to, and which indents the caller may act on.
 *
 * <p>An indent's facts are the project it requests materials for and the employee recorded as having
 * raised it, who is its owner. It names no vendor, customer or other related person. A line of an
 * indent has no facts of its own: it takes its indent's, so whoever may act on an indent may act on its
 * lines and nobody else may.
 *
 * <p>Indents and their lines are narrowed by these facts in every list and get query, so the area
 * registers as scope aware and a grant on it may carry any scope.
 */
@Component
public class IndentAccess implements ScopeAwareArea {

    public static final String AREA = "indents";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public IndentAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    @Override
    public String area() {
        return AREA;
    }

    /** The facts of an indent as it stands. */
    public static RecordFacts facts(Indent indent) {
        return facts(indent.getOrganization().getId(),
                indent.getProject() == null ? null : indent.getProject().getId(),
                indent.getCreatedBy() == null ? null : indent.getCreatedBy().getId());
    }

    /** The facts of a line, which are its indent's. */
    public static RecordFacts facts(IndentItem line) {
        if (line.getIndent() != null) {
            return facts(line.getIndent());
        }
        return RecordFacts.of(line.getOrganization().getId(), null);
    }

    public static RecordFacts facts(Long organizationId, Long projectId, Long raisedById) {
        return RecordFacts.of(organizationId, projectId)
                .withOwners(raisedById == null ? Set.of() : Set.of(raisedById));
    }

    /** Which indents the caller may act on this way. Nothing, when there is no caller. */
    public AccessFilter filter(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        return subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)))
                .orElseGet(() -> AccessFilter.none(organizationId));
    }

    /** Whether the caller may act this way on an indent, or a line of one, with these facts. */
    public boolean allows(String action, RecordFacts record) {
        return subject(record.organizationId())
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on the indent. Reads are not checked here: an indent
     * the caller may not read is reported missing before this is asked.
     */
    public void require(String action, RecordFacts record) {
        if (!allows(action, record)) {
            throw new AccessDeniedException("You may not " + action + " this indent");
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

    /** The indents a filter admits, as a query condition, so paging and totals stay right. */
    public static Specification<Indent> admittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root, cb, filter);
    }

    /** The lines of the indents a filter admits, as a query condition. */
    public static Specification<IndentItem> linesAdmittedBy(AccessFilter filter) {
        return (root, query, cb) -> admits(root.join("indent"), cb, filter);
    }

    /**
     * The condition {@link AccessFilter#admits} states, on an indent in a query. Takes any
     * {@link From}, so a query of lines can apply it to their indent through a join.
     */
    public static Predicate admits(From<?, Indent> indent, CriteriaBuilder cb, AccessFilter filter) {
        Predicate inOrganization = cb.equal(indent.get("organization").get("id"), filter.organizationId());
        if (filter.everything()) {
            return inOrganization;
        }
        Path<Long> project = indent.get("project").get("id");
        List<Predicate> anyOf = new ArrayList<>();
        if (!filter.projectIds().isEmpty()) {
            anyOf.add(project.in(filter.projectIds()));
        }
        links(indent, filter.links()).ifPresent(anyOf::add);
        filter.withinProject().forEach((projectId, links) -> links(indent, links)
                .ifPresent(match -> anyOf.add(cb.and(cb.equal(project, projectId), match))));
        if (anyOf.isEmpty()) {
            return cb.disjunction();
        }
        return cb.and(inOrganization, cb.or(anyOf.toArray(Predicate[]::new)));
    }

    /** An indent's only link is the employee who raised it, so only an owner link can match. */
    private static Optional<Predicate> links(From<?, Indent> indent, AccessFilter.Links links) {
        if (links.ownerIds().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(indent.get("createdBy").get("id").in(links.ownerIds()));
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
