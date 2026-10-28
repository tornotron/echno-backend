package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessGroup;

@Repository
public interface AccessGroupRepository extends JpaRepository<AccessGroup, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT g FROM AccessGroup g WHERE g.id = :id")
    Optional<AccessGroup> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT g FROM AccessGroup g ORDER BY g.name")
    List<AccessGroup> findAllScoped();

    // The groups one employee belongs to, for working out what they hold.
    @Query("SELECT g FROM AccessGroup g WHERE :employeeId MEMBER OF g.memberIds")
    List<AccessGroup> findByMember(@Param("employeeId") Long employeeId);
}
