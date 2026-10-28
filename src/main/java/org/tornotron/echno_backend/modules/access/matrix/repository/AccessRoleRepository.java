package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;

@Repository
public interface AccessRoleRepository extends JpaRepository<AccessRole, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT r FROM AccessRole r WHERE r.id = :id")
    Optional<AccessRole> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT r FROM AccessRole r WHERE r.slug = :slug")
    Optional<AccessRole> findBySlugScoped(@Param("slug") String slug);

    // An organization's roles named explicitly, for work that runs outside a tenant-scoped
    // request, such as seeding a new organization.
    @Query("SELECT r FROM AccessRole r WHERE r.organization.id = :organizationId")
    List<AccessRole> findAllInOrganization(@Param("organizationId") Long organizationId);

    // Roles with their grants in one query, for the matrix and the resolver.
    @Query("SELECT DISTINCT r FROM AccessRole r LEFT JOIN FETCH r.grants ORDER BY r.name")
    List<AccessRole> findAllWithGrants();
}
