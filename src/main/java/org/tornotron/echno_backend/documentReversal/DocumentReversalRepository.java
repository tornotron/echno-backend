package org.tornotron.echno_backend.documentReversal;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;

import java.util.List;
import java.util.Optional;

public interface DocumentReversalRepository extends JpaRepository<DocumentReversal, Long> {

    Optional<DocumentReversal> findByIdAndOrganization_Id(Long id, Long organizationId);

    /** The decision paths lock the row so two approvers cannot both post the correction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DocumentReversal r WHERE r.id = :id AND r.organization.id = :orgId")
    Optional<DocumentReversal> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    Page<DocumentReversal> findByOrganization_Id(Long organizationId, Pageable pageable);

    Page<DocumentReversal> findByOrganization_IdAndStatus(Long organizationId, DocumentReversalStatus status,
                                                          Pageable pageable);

    List<DocumentReversal> findByDocumentTypeAndDocumentIdAndOrganization_IdOrderByRequestedAtDesc(
            ReversibleDocumentType documentType, Long documentId, Long organizationId);

    Optional<DocumentReversal> findFirstByDocumentTypeAndDocumentIdAndOrganization_IdAndStatus(
            ReversibleDocumentType documentType, Long documentId, Long organizationId,
            DocumentReversalStatus status);
}
