package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleTemplate;

@Repository
public interface AccessRoleTemplateRepository extends JpaRepository<AccessRoleTemplate, UUID> {

    // The starting roles with their grants in one query, for copying into an organization.
    @Query("SELECT DISTINCT t FROM AccessRoleTemplate t LEFT JOIN FETCH t.grants ORDER BY t.slug")
    List<AccessRoleTemplate> findAllWithGrants();
}
