package org.tornotron.echno_backend.modules.workprogress.billing.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBillEvent;

@Repository
public interface ContractBillEventRepository extends JpaRepository<ContractBillEvent, UUID> {

    // One bill's timeline, newest first. Bounded by the bill: its handful of steps and notes.
    @Query("SELECT e FROM ContractBillEvent e WHERE e.organization.id = :orgId AND e.billId = :billId "
            + "ORDER BY e.createdAt DESC")
    List<ContractBillEvent> findForBill(@Param("orgId") Long orgId, @Param("billId") UUID billId);
}
