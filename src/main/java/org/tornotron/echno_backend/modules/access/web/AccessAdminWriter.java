package org.tornotron.echno_backend.modules.access.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.modules.access.AccessControlModule;
import org.tornotron.echno_backend.modules.access.api.AccessChanges;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.guard.EnforcedPermissions;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;
import org.tornotron.echno_backend.modules.access.matrix.catalog.ScopeAwareAreas;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleCreatedDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRoleRequest;
import org.tornotron.echno_backend.organization.Organization;

/**
 * What the Roles &amp; Permissions screen changes, under the write rules of design note 2.6. Every
 * query names the caller's organization, so a role or person of another organization reads as
 * absent (rule 7), and every change appends to the access history (rule 6).
 *
 * <p>Holding {@code access:edit} is not enough on its own (rule 1): a caller changes only roles
 * their own roles may assign, lists as grantable only roles they may assign, and gives or takes only
 * grants they hold, with that scope or with all. Beyond that:
 * <ul>
 *   <li>System Admin is fixed: it is never renamed, deleted or given other grants or grantable
 *       roles (rule 2). The baseline role is fixed too, except its grants, which only a holder of
 *       {@code access:edit-employee-role} changes, and never to include a grant on the access area
 *       itself (rule 3).
 *   <li>Nobody changes a role they hold, and a person's own roles are refused by the assignment
 *       service (rule 4), which also keeps the last administrator (rule 5).
 *   <li>A grant is added only on an action some guard enforces, and with a scope other than all
 *       records only on an area that filters by scope, since anywhere else it would mean all
 *       records.
 *   <li>Role names are unique in the organization ignoring case (rule 8).
 * </ul>
 * A person's roles change only through {@link AccessRoleAssignments}, the one writer of
 * assignments, so the old role table stays in step.
 */
@Service
public class AccessAdminWriter {

    static final String ADMINISTRATOR = "system-admin";

    private static final String TARGET_ROLE = "role";
    private static final AccessPermission EDIT_EMPLOYEE_ROLE =
            AccessPermission.of(AccessControlModule.PERMISSION_EDIT_EMPLOYEE_ROLE);

    /** One role a person holds, on a project or, with none, across the organization. */
    private record Holding(UUID roleId, @Nullable Long projectId) {
    }

    @PersistenceContext
    private EntityManager em;

    private final JdbcTemplate jdbc;
    private final CurrentAccessSubject current;
    private final AccessService access;
    private final AccessRoleAssignments assignments;
    private final AccessChanges changes;
    private final CachedAccessFactsSource facts;
    private final AccessCatalogReader catalog;
    private final EnforcedPermissions enforced;
    private final ScopeAwareAreas scopeAware;

    public AccessAdminWriter(JdbcTemplate jdbc, CurrentAccessSubject current, AccessService access,
                             AccessRoleAssignments assignments, AccessChanges changes, CachedAccessFactsSource facts,
                             AccessCatalogReader catalog, EnforcedPermissions enforced, ScopeAwareAreas scopeAware) {
        this.jdbc = jdbc;
        this.current = current;
        this.access = access;
        this.assignments = assignments;
        this.changes = changes;
        this.facts = facts;
        this.catalog = catalog;
        this.enforced = enforced;
        this.scopeAware = scopeAware;
    }

    /**
     * Creates a role with no grants. The System Admin may always assign it, and so may each role
     * the caller holds that already assigns others, so whoever makes a role can hand it out.
     */
    @Transactional
    public AccessRoleCreatedDto createRole(String name, @Nullable String subtitle, @Nullable String description) {
        AccessSubject caller = caller();
        Long organization = caller.organizationId();
        String cleanName = name.strip();
        requireNameFree(organization, cleanName, null);
        AccessRole role = new AccessRole();
        role.setOrganization(em.getReference(Organization.class, organization));
        role.setSlug(freeSlug(organization, cleanName));
        role.setName(cleanName);
        role.setSubtitle(blankToNull(subtitle));
        role.setDescription(blankToNull(description));
        // Made here, so no template ever aligns it.
        role.setCustomized(true);
        role.setCreatedBy(caller.userId());
        role.setUpdatedBy(caller.userId());
        em.persist(role);
        em.flush();

        Set<UUID> assigners = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM access_role WHERE organization_id = ? AND slug = ?", UUID.class, organization, ADMINISTRATOR));
        assigners.addAll(jdbc.queryForList("""
                SELECT DISTINCT a.role_id FROM access_employee_role a
                WHERE a.organization_id = ? AND a.employee_id = ?
                  AND EXISTS (SELECT 1 FROM access_role_grantable g
                              WHERE g.organization_id = a.organization_id AND g.role_id = a.role_id)
                """, UUID.class, organization, caller.employeeId()));
        assigners.forEach(assigner -> addGrantable(organization, assigner, role.getId()));
        record(caller, "role.created", role.getId(), null, describe(role));
        return new AccessRoleCreatedDto(role.getId(), role.getSlug());
    }

    /** Renames a role or changes what it says about itself; its slug never changes. */
    @Transactional
    public void updateRole(UUID roleId, String name, @Nullable String subtitle, @Nullable String description) {
        AccessSubject caller = caller();
        AccessRole role = role(caller.organizationId(), roleId);
        refuseIfSystem(role, "renamed");
        requireAssignable(caller, role);
        String cleanName = name.strip();
        requireNameFree(caller.organizationId(), cleanName, roleId);
        Map<String, Object> before = describe(role);
        role.setName(cleanName);
        role.setSubtitle(blankToNull(subtitle));
        role.setDescription(blankToNull(description));
        role.setUpdatedBy(caller.userId());
        em.flush();
        record(caller, "role.updated", roleId, before, describe(role));
    }

    /**
     * Replaces a role's grants. A grant the role holds may be sent back unchanged on any action;
     * one being added must be on an enforced action, and on an area that does not filter by scope it
     * must cover all records.
     */
    @Transactional
    public void replaceRoleGrants(UUID roleId, List<AccessGrantRequest> requested) {
        AccessSubject caller = caller();
        AccessRole role = role(caller.organizationId(), roleId);
        if (role.isBaseline()) {
            if (access.grantsFor(caller).scopesFor(EDIT_EMPLOYEE_ROLE).isEmpty()) {
                throw new AccessDeniedException("Only a holder of " + EDIT_EMPLOYEE_ROLE + " changes what '"
                        + role.getName() + "' grants");
            }
        } else {
            refuseIfSystem(role, "given other grants");
            requireAssignable(caller, role);
            refuseIfHeldBy(caller, role);
        }
        Set<Grant> asked = RequestedGrants.asked(requested, catalog::declares);
        Set<Grant> wanted = RequestedGrants.withRead(asked);
        Set<Grant> held = role.getGrants().stream()
                .map(grant -> Grant.of(grant.getPermissionKey(), grant.getScope()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Grant> added = difference(wanted, held);
        Set<Grant> removed = difference(held, wanted);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        for (Grant grant : added) {
            refuseOnTheBaseline(role, grant);
            if (grant.scope() != AccessScope.ALL && !scopeAware.isScopeAware(grant.permission().area())) {
                throw new InvalidRequestException("'" + grant.permission().area() + "' does not filter its records "
                        + "by scope, so a grant on it covers all records; give " + grant.key() + " with ALL");
            }
            if (asked.contains(grant) && !enforced.isEnforced(grant.permission())) {
                throw new InvalidRequestException("'" + grant.key() + "' is not yet controlled here: no endpoint "
                        + "checks it, so granting it would do nothing");
            }
        }
        requireHeld(caller, added, removed);

        removed.forEach(grant -> role.revoke(grant.permission(), grant.scope()));
        added.forEach(grant -> role.grant(grant.permission(), grant.scope()));
        role.setCustomized(true);
        role.setUpdatedBy(caller.userId());
        em.flush();
        record(caller, "role.grants-changed", roleId, Map.of("grants", state(held)), Map.of("grants", state(wanted)));
        facts.evictAllAfterCommit();
    }

    /**
     * Replaces the roles a role's holders may assign. The caller may list, or take off the list,
     * only roles they may assign themselves. The baseline is never listed, since everyone holds it.
     */
    @Transactional
    public void replaceGrantableRoles(UUID roleId, List<UUID> grantableRoleIds) {
        AccessSubject caller = caller();
        Long organization = caller.organizationId();
        AccessRole role = role(organization, roleId);
        refuseIfSystem(role, "given other grantable roles");
        requireAssignable(caller, role);
        refuseIfHeldBy(caller, role);
        Set<UUID> wanted = new LinkedHashSet<>();
        for (UUID id : grantableRoleIds) {
            AccessRole target = role(organization, id);
            if (target.isBaseline()) {
                throw new InvalidRequestException("Everyone holds '" + target.getName() + "' already; it is never assigned");
            }
            wanted.add(id);
        }
        Set<UUID> held = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT grantable_role_id FROM access_role_grantable WHERE organization_id = ? AND role_id = ?
                """, UUID.class, organization, roleId));
        Set<UUID> added = difference(wanted, held);
        Set<UUID> removed = difference(held, wanted);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        Set<UUID> assignable = assignableBy(caller);
        for (UUID changed : union(added, removed)) {
            if (!assignable.contains(changed)) {
                throw new AccessDeniedException("Your roles may not assign '" + role(organization, changed).getName()
                        + "', so you may not list it as grantable");
            }
        }

        removed.forEach(target -> jdbc.update(
                "DELETE FROM access_role_grantable WHERE organization_id = ? AND role_id = ? AND grantable_role_id = ?",
                organization, roleId, target));
        added.forEach(target -> addGrantable(organization, roleId, target));
        role.setCustomized(true);
        role.setUpdatedBy(caller.userId());
        em.flush();
        record(caller, "role.grantables-changed", roleId,
                Map.of("grantable", slugs(organization, held)), Map.of("grantable", slugs(organization, wanted)));
    }

    /**
     * Deletes a role nobody holds. A starting role stays deleted: the reconciler does not copy it
     * again. A role that stands in for an old role stays while the old checks read it.
     */
    @Transactional
    public void deleteRole(UUID roleId) {
        AccessSubject caller = caller();
        Long organization = caller.organizationId();
        AccessRole role = role(organization, roleId);
        refuseIfSystem(role, "deleted");
        requireAssignable(caller, role);
        if (role.getLegacyOrgRole() != null) {
            throw new UnprocessableRequestException("'" + role.getName() + "' stands in for an old role the old checks "
                    + "still read, so it stays until they are gone");
        }
        Integer holders = jdbc.queryForObject(
                "SELECT count(DISTINCT employee_id) FROM access_employee_role WHERE organization_id = ? AND role_id = ?",
                Integer.class, organization, roleId);
        if (holders != null && holders > 0) {
            throw new UnprocessableRequestException("'" + role.getName() + "' is held by " + holders
                    + (holders == 1 ? " person" : " people") + "; take it from them first");
        }
        Map<String, Object> before = describe(role);
        before.put("grants", state(role.getGrants().stream()
                .map(grant -> Grant.of(grant.getPermissionKey(), grant.getScope())).collect(Collectors.toSet())));
        jdbc.update("""
                INSERT INTO access_role_removed (organization_id, slug, removed_at, removed_by)
                SELECT ?, t.slug, now(), ? FROM access_role_template t WHERE t.slug = ?
                ON CONFLICT DO NOTHING
                """, organization, caller.userId(), role.getSlug());
        jdbc.update("DELETE FROM access_role_grantable WHERE organization_id = ? AND (role_id = ? OR grantable_role_id = ?)",
                organization, roleId, roleId);
        em.remove(role);
        em.flush();
        record(caller, "role.deleted", roleId, before, null);
    }

    /**
     * Replaces the roles a person holds. Each role added or taken goes through the assignment
     * service, which refuses a change to the caller's own roles, a role the caller may not assign, a
     * project on a role that stands in for an old one, and taking the last administrator's role.
     * Additions go first, so moving someone between roles never leaves a moment without one.
     */
    @Transactional
    public void replaceUserRoles(Long employeeId, List<AccessUserRoleRequest> requested) {
        Long organization = caller().organizationId();
        Integer found = jdbc.queryForObject("SELECT count(*) FROM employee WHERE id = ? AND organization_id = ?",
                Integer.class, employeeId, organization);
        if (found == null || found == 0) {
            throw new ResourceNotFoundException("Employee with ID " + employeeId + " was not found in this organization");
        }
        Set<UUID> baseline = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM access_role WHERE organization_id = ? AND is_baseline", UUID.class, organization));
        Set<Holding> wanted = new LinkedHashSet<>();
        for (AccessUserRoleRequest request : requested) {
            if (baseline.contains(request.roleId())) {
                throw new InvalidRequestException("Every active employee holds the baseline role; it is never assigned");
            }
            wanted.add(new Holding(request.roleId(), request.projectId()));
        }
        Set<Holding> held = new LinkedHashSet<>(jdbc.query("""
                SELECT role_id, project_id FROM access_employee_role WHERE organization_id = ? AND employee_id = ?
                """, (row, index) -> new Holding(row.getObject(1, UUID.class), row.getObject(2, Long.class)),
                organization, employeeId));
        difference(wanted, held).forEach(holding -> assignments.assign(employeeId, holding.roleId(), holding.projectId()));
        difference(held, wanted).forEach(holding -> assignments.remove(employeeId, holding.roleId(), holding.projectId()));
    }

    // ---- what the caller may change ----

    private AccessSubject caller() {
        return current.resolve()
                .orElseThrow(() -> new AccessDeniedException("Only an employee of this organization changes its roles"));
    }

    private static void refuseIfSystem(AccessRole role, String change) {
        if (role.isSystem()) {
            throw new UnprocessableRequestException("'" + role.getName() + "' is a system role and is never " + change);
        }
    }

    private static void refuseOnTheBaseline(AccessRole role, Grant grant) {
        if (role.isBaseline() && grant.permission().area().equals(AccessControlModule.ID)) {
            throw new InvalidRequestException("'" + role.getName() + "' never grants anything on "
                    + AccessControlModule.ID + ", since every employee holds it");
        }
    }

    /** Refuses unless one of the caller's roles may assign this one. */
    private void requireAssignable(AccessSubject caller, AccessRole role) {
        if (!assignableBy(caller).contains(role.getId())) {
            throw new AccessDeniedException("Your roles may not assign '" + role.getName() + "', so you may not change it");
        }
    }

    /** Nobody changes their own grants by changing a role they hold. */
    private void refuseIfHeldBy(AccessSubject caller, AccessRole role) {
        Integer held = jdbc.queryForObject("""
                SELECT count(*) FROM access_employee_role WHERE organization_id = ? AND employee_id = ? AND role_id = ?
                """, Integer.class, caller.organizationId(), caller.employeeId(), role.getId());
        if (held != null && held > 0) {
            throw new AccessDeniedException("You hold '" + role.getName() + "', and nobody changes their own grants");
        }
    }

    /** Refuses unless the caller holds every grant being given or taken, with its scope or with all. */
    @SafeVarargs
    private void requireHeld(AccessSubject caller, Set<Grant>... changes) {
        AccessGrants held = access.grantsFor(caller);
        for (Set<Grant> change : changes) {
            for (Grant grant : change) {
                Set<AccessScope> scopes = held.scopesFor(grant.permission());
                if (!scopes.contains(AccessScope.ALL) && !scopes.contains(grant.scope())) {
                    throw new AccessDeniedException("You do not hold " + grant.key() + " for " + grant.scope()
                            + ", so you may not give or take it");
                }
            }
        }
    }

    /** The roles any role the caller holds lists as grantable. */
    private Set<UUID> assignableBy(AccessSubject caller) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT g.grantable_role_id FROM access_role_grantable g
                JOIN access_employee_role a ON a.role_id = g.role_id AND a.organization_id = g.organization_id
                WHERE g.organization_id = ? AND a.employee_id = ?
                """, UUID.class, caller.organizationId(), caller.employeeId()));
    }

    // ---- loading and writing ----

    private AccessRole role(Long organization, UUID roleId) {
        return em.createQuery("SELECT r FROM AccessRole r WHERE r.id = :id AND r.organization.id = :organizationId",
                        AccessRole.class)
                .setParameter("id", roleId)
                .setParameter("organizationId", organization)
                .getResultStream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Role " + roleId + " was not found in this organization"));
    }

    private void requireNameFree(Long organization, String name, @Nullable UUID except) {
        Integer taken = jdbc.queryForObject("""
                SELECT count(*) FROM access_role
                WHERE organization_id = ? AND lower(name) = lower(?) AND id IS DISTINCT FROM CAST(? AS UUID)
                """, Integer.class, organization, name, except);
        if (taken != null && taken > 0) {
            throw new DuplicateResourceException("A role named '" + name + "' already exists in this organization");
        }
    }

    /**
     * A slug made from the name that no role of the organization, no starting role and no deleted
     * starting role uses, so the reconciler never takes a role the organization made for a template's.
     */
    private String freeSlug(Long organization, String name) {
        String base = slug(name);
        Set<String> taken = new HashSet<>(jdbc.queryForList("""
                SELECT slug FROM access_role WHERE organization_id = ?
                UNION SELECT slug FROM access_role_template
                UNION SELECT slug FROM access_role_removed WHERE organization_id = ?
                """, String.class, organization, organization));
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            String suffix = "-" + n;
            candidate = base.substring(0, Math.min(base.length(), 80 - suffix.length())) + suffix;
        }
        return candidate;
    }

    static String slug(String name) {
        String slug = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            throw new InvalidRequestException("A role's name needs at least one letter or digit");
        }
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-+$", "") : slug;
    }

    private void addGrantable(Long organization, UUID roleId, UUID grantableRoleId) {
        jdbc.update("""
                INSERT INTO access_role_grantable (id, organization_id, role_id, grantable_role_id)
                VALUES (gen_random_uuid(), ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, organization, roleId, grantableRoleId);
    }

    private List<String> slugs(Long organization, Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<String> slugs = new ArrayList<>(em.createQuery("SELECT r.slug FROM AccessRole r "
                        + "WHERE r.organization.id = :organizationId AND r.id IN :ids", String.class)
                .setParameter("organizationId", organization)
                .setParameter("ids", roleIds)
                .getResultList());
        slugs.sort(Comparator.naturalOrder());
        return slugs;
    }

    private void record(AccessSubject caller, String action, UUID roleId,
                        @Nullable Map<String, Object> before, @Nullable Map<String, Object> after) {
        changes.record(caller.organizationId(), caller.userId(), action, TARGET_ROLE, roleId.toString(), before, after);
    }

    private static Map<String, Object> describe(AccessRole role) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("slug", role.getSlug());
        state.put("name", role.getName());
        state.put("subtitle", role.getSubtitle());
        state.put("description", role.getDescription());
        return state;
    }

    private static List<Map<String, String>> state(Set<Grant> grants) {
        return grants.stream()
                .sorted(Comparator.comparing(Grant::key).thenComparing(grant -> grant.scope().name()))
                .map(grant -> Map.of("permission", grant.key(), "scope", grant.scope().name()))
                .toList();
    }

    private static <T> Set<T> difference(Set<T> from, Set<T> minus) {
        Set<T> result = new LinkedHashSet<>(from);
        result.removeAll(minus);
        return result;
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return result;
    }

    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
