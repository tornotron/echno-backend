package org.tornotron.echno_backend.siteTransfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SiteTransferRepository extends JpaRepository<SiteTransfer, Long> {

    Optional<SiteTransfer> findByTransferNumber(String transferNumber);

    List<SiteTransfer> findByStatus(SiteTransferStatus status);

    Page<SiteTransfer> findByStatus(SiteTransferStatus status, Pageable pageable);

    List<SiteTransfer> findBySendingProjectId(Long projectId);

    Page<SiteTransfer> findBySendingProjectId(Long projectId, Pageable pageable);

    List<SiteTransfer> findByReceivingProjectId(Long projectId);

    Page<SiteTransfer> findByReceivingProjectId(Long projectId, Pageable pageable);

    List<SiteTransfer> findByIssueDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    Optional<SiteTransfer> findByIdAndOrganization_Id(Long id, Long organizationId);
}
