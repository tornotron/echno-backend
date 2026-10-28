package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessEmployeeGrant;

@Repository
public interface AccessEmployeeGrantRepository extends JpaRepository<AccessEmployeeGrant, UUID> {

    @Query("SELECT g FROM AccessEmployeeGrant g WHERE g.employeeId = :employeeId")
    List<AccessEmployeeGrant> findByEmployee(@Param("employeeId") Long employeeId);
}
