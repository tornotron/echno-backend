package org.tornotron.echno_backend.siteTransfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SiteTransferRepository extends JpaRepository<SiteTransfer, Long> {

    Optional<SiteTransfer> findByTransferNumber(String transferNumber);

    List<SiteTransfer> findByStatus(SiteTransferStatus status);

    List<SiteTransfer> findBySendingProjectId(Long projectId);

    List<SiteTransfer> findByReceivingProjectId(Long projectId);

    List<SiteTransfer> findByIssueDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    Optional<SiteTransfer> findByIdAndOrganization_Id(Long id, Long organizationId);
}
