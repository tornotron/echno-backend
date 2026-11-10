package org.tornotron.echno_backend.modules.access.matrix.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.access.api.AccessStartingRoles;

/**
 * Brings one organization's roles into line with the templates and its old role rows. Every write is
 * set-based, touches only what differs and relies on the tables' unique keys, so running it again
 * changes nothing and two running at once cannot double a row.
 *
 * <ul>
 *   <li>Each template becomes a role, unless the organization already has one with that slug or
 *       deleted it, which {@code access_role_removed} records.
 *   <li>A role nobody has changed follows its template's grants and grantable list, in both directions,
 *       so a change to the template's cells arrives at the next boot. A grantable row that points at a
 *       role the organization made is kept, since no template can list it.
 *   <li>A customized role made from a template takes each change to its template's grants once, and
 *       keeps the organization's own changes: {@code access_role_template_grant_applied} records which
 *       template grants it has been given, so a grant the template gains is added, a grant the template
 *       drops is taken away, and a grant the organization took off stays off. Without this a grant a
 *       template gained to keep today's access, when an area moved onto the matrix, never reached a
 *       customized role. Its grantable list is left as the organization set it. A role the organization
 *       made itself ({@code created_by} set) never follows a template, even one that later takes its slug.
 *   <li>Each {@code employee_org_roles} row becomes the matching assignment, with no project, because
 *       the old roles hold across the organization. Only ever adds: while the old table is the truth,
 *       the assignment writer removes the two together.
 * </ul>
 */
@Component
public class AccessRoleReconciler implements AccessStartingRoles {

    private final JdbcTemplate jdbc;

    public AccessRoleReconciler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureStartingRoles(Long organizationId) {
        ensureRoles(organizationId);
        alignGrants(organizationId);
        applyTemplateChangesToCustomizedRoles(organizationId);
        alignGrantables(organizationId);
    }

    /** {@link #ensureStartingRoles} and then the assignments from the old table. */
    public void reconcile(Long organizationId) {
        ensureStartingRoles(organizationId);
        ensureAssignments(organizationId);
    }

    private void ensureRoles(Long organizationId) {
        jdbc.update("""
                INSERT INTO access_role (id, organization_id, slug, name, subtitle, description, is_system,
                                         is_baseline, legacy_org_role, carried_over, created_at, updated_at)
                SELECT gen_random_uuid(), ?, t.slug, t.name, t.subtitle, t.description, t.is_system,
                       t.is_baseline, t.legacy_org_role, t.carried_over, now(), now()
                FROM access_role_template t
                WHERE NOT EXISTS (SELECT 1 FROM access_role_removed x
                                  WHERE x.organization_id = ? AND x.slug = t.slug)
                ON CONFLICT DO NOTHING
                """, organizationId, organizationId);
    }

    private void alignGrants(Long organizationId) {
        jdbc.update("""
                INSERT INTO access_role_grant (id, role_id, permission_key, scope)
                SELECT gen_random_uuid(), r.id, g.permission_key, g.scope
                FROM access_role r
                JOIN access_role_template t ON t.slug = r.slug
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE r.organization_id = ? AND NOT r.customized
                ON CONFLICT DO NOTHING
                """, organizationId);
        jdbc.update("""
                DELETE FROM access_role_grant
                WHERE role_id IN (SELECT r.id FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  WHERE r.organization_id = ? AND NOT r.customized)
                  AND NOT EXISTS (SELECT 1 FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  JOIN access_role_template_grant g ON g.template_id = t.id
                                  WHERE r.id = access_role_grant.role_id
                                    AND g.permission_key = access_role_grant.permission_key
                                    AND g.scope = access_role_grant.scope)
                """, organizationId);
    }

    private void applyTemplateChangesToCustomizedRoles(Long organizationId) {
        // What a template dropped since the role was last aligned leaves a customized role too.
        jdbc.update("""
                DELETE FROM access_role_grant
                WHERE role_id IN (SELECT r.id FROM access_role r
                                  WHERE r.organization_id = ? AND r.customized AND r.created_by IS NULL)
                  AND EXISTS (SELECT 1 FROM access_role_template_grant_applied a
                              WHERE a.role_id = access_role_grant.role_id
                                AND a.permission_key = access_role_grant.permission_key
                                AND a.scope = access_role_grant.scope)
                  AND NOT EXISTS (SELECT 1 FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  JOIN access_role_template_grant g ON g.template_id = t.id
                                  WHERE r.id = access_role_grant.role_id
                                    AND g.permission_key = access_role_grant.permission_key
                                    AND g.scope = access_role_grant.scope)
                """, organizationId);
        jdbc.update("""
                DELETE FROM access_role_template_grant_applied
                WHERE organization_id = ?
                  AND NOT EXISTS (SELECT 1 FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  JOIN access_role_template_grant g ON g.template_id = t.id
                                  WHERE r.id = access_role_template_grant_applied.role_id
                                    AND g.permission_key = access_role_template_grant_applied.permission_key
                                    AND g.scope = access_role_template_grant_applied.scope)
                """, organizationId);
        // What a template gained since reaches a customized role once; what the organization took off
        // stays off, since it is recorded as given.
        jdbc.update("""
                INSERT INTO access_role_grant (id, role_id, permission_key, scope)
                SELECT gen_random_uuid(), r.id, g.permission_key, g.scope
                FROM access_role r
                JOIN access_role_template t ON t.slug = r.slug
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE r.organization_id = ? AND r.customized AND r.created_by IS NULL
                  AND NOT EXISTS (SELECT 1 FROM access_role_template_grant_applied a
                                  WHERE a.role_id = r.id AND a.permission_key = g.permission_key
                                    AND a.scope = g.scope)
                ON CONFLICT DO NOTHING
                """, organizationId);
        jdbc.update("""
                INSERT INTO access_role_template_grant_applied (id, organization_id, role_id, permission_key, scope)
                SELECT gen_random_uuid(), r.organization_id, r.id, g.permission_key, g.scope
                FROM access_role r
                JOIN access_role_template t ON t.slug = r.slug
                JOIN access_role_template_grant g ON g.template_id = t.id
                WHERE r.organization_id = ? AND r.created_by IS NULL
                ON CONFLICT DO NOTHING
                """, organizationId);
    }

    private void alignGrantables(Long organizationId) {
        jdbc.update("""
                INSERT INTO access_role_grantable (id, organization_id, role_id, grantable_role_id)
                SELECT gen_random_uuid(), r.organization_id, r.id, target.id
                FROM access_role r
                JOIN access_role_template t ON t.slug = r.slug
                JOIN access_role_template_grantable tg ON tg.template_id = t.id
                JOIN access_role_template tt ON tt.id = tg.grantable_template_id
                JOIN access_role target ON target.organization_id = r.organization_id AND target.slug = tt.slug
                WHERE r.organization_id = ? AND NOT r.customized
                ON CONFLICT DO NOTHING
                """, organizationId);
        jdbc.update("""
                DELETE FROM access_role_grantable
                WHERE organization_id = ?
                  AND role_id IN (SELECT r.id FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  WHERE r.organization_id = access_role_grantable.organization_id
                                    AND NOT r.customized)
                  AND grantable_role_id IN (SELECT target.id FROM access_role target
                                            JOIN access_role_template tt ON tt.slug = target.slug
                                            WHERE target.organization_id = access_role_grantable.organization_id)
                  AND NOT EXISTS (SELECT 1 FROM access_role r
                                  JOIN access_role_template t ON t.slug = r.slug
                                  JOIN access_role_template_grantable tg ON tg.template_id = t.id
                                  JOIN access_role_template tt ON tt.id = tg.grantable_template_id
                                  JOIN access_role target ON target.slug = tt.slug
                                       AND target.organization_id = r.organization_id
                                  WHERE r.id = access_role_grantable.role_id
                                    AND target.id = access_role_grantable.grantable_role_id)
                """, organizationId);
    }

    private void ensureAssignments(Long organizationId) {
        jdbc.update("""
                INSERT INTO access_employee_role (id, organization_id, employee_id, role_id, project_id, created_at)
                SELECT gen_random_uuid(), e.organization_id, e.id, r.id, NULL, now()
                FROM employee e
                JOIN employee_org_roles old ON old.employee_id = e.id
                JOIN access_role r ON r.organization_id = e.organization_id
                     AND upper(replace(r.legacy_org_role, '-', '_')) = old.role
                WHERE e.organization_id = ?
                ON CONFLICT DO NOTHING
                """, organizationId);
    }
}
