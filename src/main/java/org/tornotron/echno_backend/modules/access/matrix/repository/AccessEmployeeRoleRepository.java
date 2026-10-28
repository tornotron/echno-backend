package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeRole;

@Repository
public interface AccessEmployeeRoleRepository extends JpaRepository<AccessEmployeeRole, UUID> {

    // The roles one person holds directly, with each role's grants, for working out what they hold.
    @Query("SELECT DISTINCT a FROM AccessEmployeeRole a JOIN FETCH a.role r LEFT JOIN FETCH r.grants "
            + "WHERE a.employeeId = :employeeId")
    List<AccessEmployeeRole> findByEmployeeWithGrants(@Param("employeeId") Long employeeId);

    // Who holds a role directly, for the matrix's member chips.
    @Query("SELECT a FROM AccessEmployeeRole a WHERE a.role.id = :roleId")
    List<AccessEmployeeRole> findByRole(@Param("roleId") UUID roleId);
}
