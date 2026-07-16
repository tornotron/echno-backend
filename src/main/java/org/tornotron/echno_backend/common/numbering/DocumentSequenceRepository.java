package org.tornotron.echno_backend.common.numbering;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface DocumentSequenceRepository extends JpaRepository<DocumentSequence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentSequence s WHERE s.organization.id = :orgId " +
            "AND s.docType = :type AND s.fiscalYear = :fy")
    Optional<DocumentSequence> findByOrgAndDocTypeAndFiscalYearForUpdate(Long orgId, String type, int fy);
}
