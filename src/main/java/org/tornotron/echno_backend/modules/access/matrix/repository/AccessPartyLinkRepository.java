package org.tornotron.echno_backend.modules.access.matrix.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessPartyLink;

@Repository
public interface AccessPartyLinkRepository extends JpaRepository<AccessPartyLink, UUID> {

    @Query("SELECT l FROM AccessPartyLink l WHERE l.employeeId = :employeeId")
    List<AccessPartyLink> findByEmployee(@Param("employeeId") Long employeeId);
}
