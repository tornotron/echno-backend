package org.tornotron.echno_backend.payable;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayableRepository extends JpaRepository<Payable, Long> {

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

    @Query("SELECT p FROM Payable p WHERE p.amountRecorded > p.amountPaid")
    List<Payable> findOutstandingPayables();

    boolean existsByPayableNumberAndOrganization_Id(String payableNumber, Long organizationId);
}
