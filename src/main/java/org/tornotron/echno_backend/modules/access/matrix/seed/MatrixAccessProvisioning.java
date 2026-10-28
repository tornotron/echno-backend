package org.tornotron.echno_backend.modules.access.matrix.seed;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.access.api.AccessProvisioning;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;

/**
 * Provisions a new organization on the matrix: the standard roles, and the owner as company
 * administrator.
 */
@Service
public class MatrixAccessProvisioning implements AccessProvisioning {

    private final StandardRoleSeeder seeder;
    private final AccessRoleAssignments assignments;

    public MatrixAccessProvisioning(StandardRoleSeeder seeder, AccessRoleAssignments assignments) {
        this.seeder = seeder;
        this.assignments = assignments;
    }

    @Override
    @Transactional
    public void provisionOrganization(Long organizationId, Long ownerEmployeeId) {
        seeder.seed(organizationId);
        assignments.assign(organizationId, ownerEmployeeId, StandardRoles.SYSTEM_ADMIN);
    }
}
