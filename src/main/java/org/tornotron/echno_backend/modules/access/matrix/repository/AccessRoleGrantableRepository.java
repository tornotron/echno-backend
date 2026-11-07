package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRoleGrantable;

@Repository
public interface AccessRoleGrantableRepository extends JpaRepository<AccessRoleGrantable, UUID> {

    // The roles a role's holders may assign.
    @Query("SELECT g FROM AccessRoleGrantable g JOIN FETCH g.grantableRole WHERE g.role.id = :roleId")
    List<AccessRoleGrantable> findByRole(@Param("roleId") UUID roleId);
}
