package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractBillPaymentRepository extends JpaRepository<ContractBillPayment, UUID> {

    @Query("SELECT p FROM ContractBillPayment p WHERE p.id = :id AND p.organization.id = :orgId")
    Optional<ContractBillPayment> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ContractBillPayment p WHERE p.id = :id AND p.organization.id = :orgId")
    Optional<ContractBillPayment> lockScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // One bill's payments, newest first. Bounded by the bill: a handful of attempts.
    @Query("SELECT p FROM ContractBillPayment p WHERE p.organization.id = :orgId AND p.billId = :billId "
            + "ORDER BY p.createdAt DESC")
    List<ContractBillPayment> findForBill(@Param("orgId") Long orgId, @Param("billId") UUID billId);

    @Query("SELECT COUNT(p) > 0 FROM ContractBillPayment p WHERE p.organization.id = :orgId AND p.billId = :billId "
            + "AND p.status IN :statuses")
    boolean existsForBillInStatus(@Param("orgId") Long orgId, @Param("billId") UUID billId,
                                  @Param("statuses") Collection<BillPaymentStatus> statuses);

    @Query("SELECT COUNT(p) FROM ContractBillPayment p WHERE p.organization.id = :orgId AND p.status IN :statuses")
    long countInStatus(@Param("orgId") Long orgId, @Param("statuses") Collection<BillPaymentStatus> statuses);

    // The organization a payment belongs to, for an event that arrives with no session. The id comes
    // from a signed provider event and the caller pins the tenant to the answer.
    @Query("SELECT p.organization.id FROM ContractBillPayment p WHERE p.id = :id")
    Optional<Long> findOrganizationId(@Param("id") UUID id);
}
