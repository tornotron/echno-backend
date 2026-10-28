package org.tornotron.echno_backend.modules.access.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrant;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.web.RequestedGrants.Grant;
import org.tornotron.echno_backend.organization.Organization;

/**
 * What the Roles &amp; Permissions screen changes. Every query names the caller's organization,
 * and every change evicts the cached access it affects, so it applies on the next request.
 *
 * <p>Holding {@code access:edit} is not enough on its own; two rules keep a caller from giving
 * more than they have:
 * <ul>
 *   <li>a caller changes only the roles their own roles may assign, and the baseline role;
 *   <li>a caller gives or takes only a grant they hold themselves, with that scope or with all.
 * </ul>
 */
@Service
public class AccessAdminWriter {

    private final AccessService access;
    private final CachedAccessFactsSource facts;

    @PersistenceContext
    private EntityManager em;

    public AccessAdminWriter(AccessService access, CachedAccessFactsSource facts) {
        this.access = access;
        this.facts = facts;
    }

    /**
     * Creates a role with no grants. The caller's roles that already assign others may assign it
     * too, so whoever makes a role can hand it out.
     *
     * @return the new role's id
     */
    @Transactional
    public UUID createRole(AccessSubject caller, String name, String subtitle, String description) {
        Long organizationId = caller.organizationId();
        String slug = slug(name);
        boolean taken = !em.createQuery("SELECT r.id FROM AccessRole r WHERE r.organization.id = :organizationId "
                        + "AND r.slug = :slug", UUID.class)
                .setParameter("organizationId", organizationId)
                .setParameter("slug", slug)
                .getResultList().isEmpty();
        if (taken) {
            throw new DuplicateResourceException("A role named '" + name.strip() + "' already exists");
        }
        AccessRole role = new AccessRole();
        role.setOrganization(em.getReference(Organization.class, organizationId));
        role.setSlug(slug);
        role.setName(name.strip());
        role.setSubtitle(blankToNull(subtitle));
        role.setDescription(blankToNull(description));
        role.setCreatedBy(caller.employeeId());
        role.setUpdatedBy(caller.employeeId());
        em.persist(role);
        heldRoles(caller).stream()
                .filter(held -> !held.getGrantableRoles().isEmpty())
                .forEach(held -> held.getGrantableRoles().add(role));
        return role.getId();
    }

    /** Renames a role or changes what it says about itself; its slug never changes. */
    @Transactional
    public void updateRole(AccessSubject caller, UUID roleId, String name, String subtitle, String description) {
        AccessRole role = changeableRole(caller, roleId);
        role.setName(name.strip());
        role.setSubtitle(blankToNull(subtitle));
        role.setDescription(blankToNull(description));
        role.setUpdatedBy(caller.employeeId());
    }

    /**
     * Replaces a role's grants. Locked grants stay whether or not they are asked for, since a role
     * can never drop them.
     */
    @Transactional
    public void replaceRoleGrants(AccessSubject caller, UUID roleId, Set<Grant> wanted) {
        AccessRole role = changeableRole(caller, roleId);
        Set<Grant> current = role.getGrants().stream()
                .filter(g -> !g.isLocked())
                .map(g -> grant(g.getPermissionKey(), g.getScope()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Grant> locked = role.getGrants().stream()
                .filter(AccessRoleGrant::isLocked)
                .map(g -> grant(g.getPermissionKey(), g.getScope()))
                .collect(Collectors.toSet());
        Set<Grant> added = difference(difference(wanted, current), locked);
        Set<Grant> removed = difference(current, wanted);
        requireHeld(caller, added, removed);

        removed.forEach(g -> role.revoke(g.permission(), g.scope()));
        added.forEach(g -> role.grant(g.permission(), g.scope(), false));
        role.setUpdatedBy(caller.employeeId());
        if (!added.isEmpty() || !removed.isEmpty()) {
            facts.evictAll();
        }
    }

    /**
     * Replaces the roles a role's holders may assign. The caller may list, or take off the list,
     * only roles they may assign themselves; the baseline role is never listed, since everyone
     * holds it already.
     */
    @Transactional
    public void replaceGrantableRoles(AccessSubject caller, UUID roleId, Collection<UUID> grantableRoleIds) {
        AccessRole role = changeableRole(caller, roleId);
        Set<AccessRole> wanted = new HashSet<>(assignableRoles(caller.organizationId(), grantableRoleIds));
        Set<AccessRole> current = role.getGrantableRoles();
        Set<AccessRole> changed = difference(wanted, current);
        changed.addAll(difference(current, wanted));
        requireMayAssign(caller, changed);

        role.getGrantableRoles().retainAll(wanted);
        role.getGrantableRoles().addAll(wanted);
        role.setUpdatedBy(caller.employeeId());
    }

    /**
     * Deletes a role an organization made. Its holders lose it at once; the starting roles cannot
     * be deleted.
     */
    @Transactional
    public void deleteRole(AccessSubject caller, UUID roleId) {
        AccessRole role = changeableRole(caller, roleId);
        if (role.isSystem() || role.isBaseline()) {
            throw new UnprocessableRequestException("'" + role.getName() + "' is a starting role and cannot be deleted");
        }
        // Assignments, group links and other roles' grantable lists go with it by foreign-key cascade.
        em.remove(role);
        em.flush();
        facts.evictAll();
    }

    // ---- what the caller may change ----

    /**
     * A role of the caller's organization that the caller may change: the baseline role, or one
     * their roles may assign.
     */
    AccessRole changeableRole(AccessSubject caller, UUID roleId) {
        AccessRole role = role(caller.organizationId(), roleId);
        if (!role.isBaseline() && !assignableBy(caller).contains(role.getId())) {
            throw new AccessDeniedException("Your roles may not assign '" + role.getName() + "', so you may not change it");
        }
        return role;
    }

    /** Refuses unless the caller's roles may assign every one of the roles. */
    void requireMayAssign(AccessSubject caller, Collection<AccessRole> roles) {
        if (roles.isEmpty()) {
            return;
        }
        Set<UUID> assignable = assignableBy(caller);
        for (AccessRole role : roles) {
            if (!assignable.contains(role.getId())) {
                throw new AccessDeniedException("Your roles may not assign '" + role.getName() + "'");
            }
        }
    }

    /** Refuses unless the caller holds every grant being given or taken, with its scope or with all. */
    @SafeVarargs
    final void requireHeld(AccessSubject caller, Set<Grant>... changes) {
        AccessGrants held = null;
        for (Set<Grant> change : changes) {
            for (Grant grant : change) {
                if (held == null) {
                    held = access.grantsFor(caller);
                }
                Set<AccessScope> scopes = held.scopesFor(grant.permission());
                if (!scopes.contains(AccessScope.ALL) && !scopes.contains(grant.scope())) {
                    throw new AccessDeniedException("You do not hold " + grant.key() + " for " + grant.scope()
                            + ", so you may not give or take it");
                }
            }
        }
    }

    /** The ids of the roles the caller's roles may assign: their own, their groups' and the baseline's. */
    Set<UUID> assignableBy(AccessSubject caller) {
        return new HashSet<>(em.createQuery("SELECT DISTINCT g.id FROM AccessRole r JOIN r.grantableRoles g "
                        + "WHERE r.organization.id = :organizationId AND (r.baseline = true "
                        + "OR r.id IN (SELECT a.role.id FROM AccessEmployeeRole a WHERE a.employeeId = :employeeId "
                        + "AND a.organization.id = :organizationId) "
                        + "OR r.id IN (SELECT gr.id FROM AccessGroup grp JOIN grp.roles gr "
                        + "WHERE :employeeId MEMBER OF grp.memberIds AND grp.organization.id = :organizationId))",
                        UUID.class)
                .setParameter("organizationId", caller.organizationId())
                .setParameter("employeeId", caller.employeeId())
                .getResultList());
    }

    /** The roles the caller holds directly or through a group, not counting the baseline. */
    private List<AccessRole> heldRoles(AccessSubject caller) {
        return em.createQuery("SELECT DISTINCT r FROM AccessRole r WHERE r.organization.id = :organizationId "
                        + "AND (r.id IN (SELECT a.role.id FROM AccessEmployeeRole a WHERE a.employeeId = :employeeId "
                        + "AND a.organization.id = :organizationId) "
                        + "OR r.id IN (SELECT gr.id FROM AccessGroup grp JOIN grp.roles gr "
                        + "WHERE :employeeId MEMBER OF grp.memberIds AND grp.organization.id = :organizationId))",
                        AccessRole.class)
                .setParameter("organizationId", caller.organizationId())
                .setParameter("employeeId", caller.employeeId())
                .getResultList();
    }

    // ---- loading ----

    AccessRole role(Long organizationId, UUID roleId) {
        AccessRole role = em.find(AccessRole.class, roleId);
        if (role == null || !role.getOrganization().getId().equals(organizationId)) {
            throw new ResourceNotFoundException("No role " + roleId + " in this organization");
        }
        return role;
    }

    /** The roles named, all of the organization and none of them the baseline. */
    List<AccessRole> assignableRoles(Long organizationId, Collection<UUID> roleIds) {
        List<AccessRole> roles = new LinkedHashSet<>(roleIds).stream().map(id -> role(organizationId, id)).toList();
        roles.stream().filter(AccessRole::isBaseline).findFirst().ifPresent(baseline -> {
            throw new InvalidRequestException("Everyone holds '" + baseline.getName() + "' already; it is never assigned");
        });
        return roles;
    }

    // ---- helpers ----

    static String slug(String name) {
        String slug = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty()) {
            throw new InvalidRequestException("A role's name needs at least one letter or digit");
        }
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-+$", "") : slug;
    }

    static <T> Set<T> difference(Set<T> from, Set<T> minus) {
        Set<T> result = new LinkedHashSet<>(from);
        result.removeAll(minus);
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static Grant grant(String permissionKey, AccessScope scope) {
        return new Grant(AccessPermission.of(permissionKey), scope);
    }
}
