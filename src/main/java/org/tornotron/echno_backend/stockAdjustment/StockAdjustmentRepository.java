package org.tornotron.echno_backend.stockAdjustment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    Optional<StockAdjustment> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Loads a document under a pessimistic write lock, so the transitions that read its state
     * and then decide on it serialize against each other.
     *
     * <p>Every write path on this document is a read-decide-write: approve refuses a document
     * already posted or rejected and then posts it, reject refuses one already posted or
     * rejected and then stamps it, and update and delete both refuse a decided document before
     * changing it. Two of those running at once on the same row would each read the state as it
     * was before the other, both pass the guard, and both act. Concurrent approvals would post
     * the movement to the stock ledger twice; an approval racing a rejection would post the
     * movement and then record the document as refused; two rejections would overwrite the first
     * reason with the second. With the lock the second caller waits, then reads the decision the
     * first committed and is refused by the guard, which is the outcome the guards are there for.
     *
     * <p>The same lock the leave-approval and payable paths take for the same reason. Reads that
     * only display the document keep the unlocked lookup above.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockAdjustment s WHERE s.id = :id AND s.organization.id = :orgId")
    Optional<StockAdjustment> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    boolean existsByAdjustmentNumberAndOrganization_Id(String adjustmentNumber, Long organizationId);
}
