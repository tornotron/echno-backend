package org.tornotron.echno_backend.payable;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PayableRepository extends JpaRepository<Payable, Long>, JpaSpecificationExecutor<Payable> {

    Optional<Payable> findByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Loads a payable for a read-modify-write on {@code amountPaid} under a
     * pessimistic write lock, so concurrent {@code recordPayment} calls serialize
     * instead of losing updates. On CockroachDB {@code SELECT … FOR UPDATE} queues
     * writers rather than aborting them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payable p WHERE p.id = :id AND p.organization.id = :orgId")
    Optional<Payable> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    Optional<Payable> findByPayableNumber(String payableNumber);

    List<Payable> findByVendorIdAndOrganization_id(Long vendorId, Long organizationId);

    Page<Payable> findByVendorIdAndOrganization_id(Long vendorId, Long organizationId, Pageable pageable);

    /** Whether any payable stands against the goods receipt, which blocks reversing the receipt. */
    boolean existsByGoodsReceivedNote_IdAndOrganization_Id(Long goodsReceivedNoteId, Long organizationId);

    @Query("SELECT p FROM Payable p WHERE p.amountRecorded > p.amountPaid")
    List<Payable> findOutstandingPayables();

    @Query("SELECT p FROM Payable p WHERE p.amountRecorded > p.amountPaid")
    Page<Payable> findOutstandingPayables(Pageable pageable);

    boolean existsByPayableNumberAndOrganization_Id(String payableNumber, Long organizationId);

    /**
     * The condition of {@link #findByVendorIdAndOrganization_id} as a specification, for a listing that
     * is also narrowed to the payables a caller may see.
     */
    static Specification<Payable> owedTo(Long vendorId) {
        return (root, query, cb) -> cb.equal(root.get("vendor").get("id"), vendorId);
    }

    /**
     * The condition of {@link #findOutstandingPayables} as a specification, for a listing that is also
     * narrowed to the payables a caller may see.
     */
    static Specification<Payable> outstanding() {
        return (root, query, cb) -> cb.greaterThan(root.get("amountRecorded"), root.get("amountPaid"));
    }
}
