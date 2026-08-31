package org.tornotron.echno_backend.finance.construction.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConstructionPaymentRepository
        extends JpaRepository<ConstructionPayment, UUID>, JpaSpecificationExecutor<ConstructionPayment> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT cp FROM ConstructionPayment cp WHERE cp.id = :id")
    Optional<ConstructionPayment> findByIdScoped(@Param("id") UUID id);

    /**
     * The same org-scoped lookup, holding the row until the transaction ends.
     *
     * <p>Every write path here reads the voucher's state, decides on it, and then changes it:
     * an update refuses a verified or cancelled voucher before replacing its fields, a
     * verification refuses one already verified before stamping it, and a cancellation refuses
     * one already cancelled. Two of those running at once would each read the voucher as it was
     * before the other, both pass the guard, and both act, so an edit could land on a voucher
     * that had just been verified, or a second verification could replace the first. With the
     * lock the second caller waits, reads what the first committed, and is refused. Reads that
     * only display a voucher keep the unlocked lookup above.
     *
     * <p>The same lock the stock-adjustment, leave-approval and payable paths take.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cp FROM ConstructionPayment cp WHERE cp.id = :id")
    Optional<ConstructionPayment> lockByIdScoped(@Param("id") UUID id);
}
