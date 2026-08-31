package org.tornotron.echno_backend.siteTransferItem;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SiteTransferItemRepository extends JpaRepository<SiteTransferItem, Long> {

    List<SiteTransferItem> findBySiteTransferId(Long siteTransferId);

    List<SiteTransferItem> findByMaterialId(Long materialId);

    /**
     * Loads a transfer's lines for a read-modify-write on {@code receivedQuantity} under a
     * pessimistic write lock, ordered by id so two callers take the rows in the same sequence
     * and cannot deadlock against each other.
     *
     * <p>Recording a receipt reads how much of a line has already arrived, decides whether the
     * new quantity over-receives it, and writes the sum back. Two people confirming the same
     * lorry at once would each read the figure as it stood before the other, so both would pass
     * the over-receipt check and the second write would erase the first, leaving a transfer that
     * reads as under-received while the ledger holds both inbound legs. On CockroachDB
     * {@code SELECT … FOR UPDATE} queues the second caller rather than aborting it, so it reads
     * the committed figure and is judged against the truth. The same lock is what stops a
     * cancellation from reversing a transfer that is being received in the next connection.
     *
     * <p>Reads that only display a transfer keep {@link #findBySiteTransferId}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sti FROM SiteTransferItem sti WHERE sti.siteTransfer.id = :siteTransferId "
            + "AND sti.organization.id = :orgId ORDER BY sti.id")
    List<SiteTransferItem> lockBySiteTransferIdAndOrganizationId(
            @Param("siteTransferId") Long siteTransferId, @Param("orgId") Long orgId);
}
