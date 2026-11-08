package org.tornotron.echno_backend.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.common.enums.OrgRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleDefinitionRepository extends JpaRepository<RoleDefinition, UUID> {

    /**
     * Every override of a built-in role, in every organization the user with this subject belongs
     * to. The caller keeps the ones for roles the user actually holds.
     *
     * <p>Runs on every request, before a tenant is chosen, so it selects plain values and never
     * loads a tenant-scoped entity.
     */
    @Query("SELECT r.organization.id AS organizationId, r.systemRole AS systemRole, "
            + "KEY(p) AS permission, VALUE(p) AS granted "
            + "FROM RoleDefinition r JOIN r.permissions p "
            + "WHERE r.systemRole IS NOT NULL AND r.organization.id IN "
            + "(SELECT e.organization.id FROM Employee e WHERE e.user.authSubject = :authSubject)")
    List<RolePermissionRow> findBuiltInOverridesForSubject(@Param("authSubject") String authSubject);

    /**
     * Every permission granted by a custom role the user with this subject holds, in any
     * organization. Same constraints as {@link #findBuiltInOverridesForSubject}.
     */
    @Query("SELECT r.organization.id AS organizationId, r.systemRole AS systemRole, "
            + "KEY(p) AS permission, VALUE(p) AS granted "
            + "FROM RoleDefinition r JOIN r.members m JOIN r.permissions p "
            + "WHERE r.systemRole IS NULL AND m.user.authSubject = :authSubject AND VALUE(p) = true")
    List<RolePermissionRow> findCustomGrantsForSubject(@Param("authSubject") String authSubject);

    List<RoleDefinition> findByOrganization_IdAndSystemRoleIsNullOrderByNameAsc(Long organizationId);

    List<RoleDefinition> findByOrganization_IdAndSystemRoleIsNotNull(Long organizationId);

    Optional<RoleDefinition> findByOrganization_IdAndSystemRole(Long organizationId, OrgRole systemRole);

    Optional<RoleDefinition> findByIdAndOrganization_IdAndSystemRoleIsNull(UUID id, Long organizationId);

    boolean existsByOrganization_IdAndNameIgnoreCase(Long organizationId, String name);

    boolean existsByOrganization_IdAndNameIgnoreCaseAndIdNot(Long organizationId, String name, UUID id);

    /** Custom roles held by one employee, for showing them next to the built-in ones. */
    @Query("SELECT r FROM RoleDefinition r JOIN r.members m "
            + "WHERE r.organization.id = :organizationId AND m.id = :employeeId ORDER BY r.name")
    List<RoleDefinition> findCustomRolesOfEmployee(@Param("organizationId") Long organizationId,
                                                   @Param("employeeId") Long employeeId);
}
