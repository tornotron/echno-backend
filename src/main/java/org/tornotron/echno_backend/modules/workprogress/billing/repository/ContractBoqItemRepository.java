package org.tornotron.echno_backend.modules.workprogress.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBoqItem;

@Repository
public interface ContractBoqItemRepository extends JpaRepository<ContractBoqItem, UUID> {

    // JPQL rather than findById so the orgFilter applies, and the organization named so the read
    // holds where the filter is not enabled; a stranger's id reads as absent.
    @Query("SELECT i FROM ContractBoqItem i WHERE i.id = :id AND i.organization.id = :orgId")
    Optional<ContractBoqItem> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // A contract's BOQ, in its own order. Bounded by the contract: one row per priced item.
    @Query("SELECT i FROM ContractBoqItem i WHERE i.organization.id = :orgId AND i.subContractId = :contractId "
            + "ORDER BY i.sortOrder ASC, i.itemCode ASC")
    List<ContractBoqItem> findForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);

    @Query("SELECT COUNT(i) > 0 FROM ContractBoqItem i WHERE i.organization.id = :orgId "
            + "AND i.subContractId = :contractId AND i.itemCode = :code")
    boolean existsCode(@Param("orgId") Long orgId, @Param("contractId") Long contractId, @Param("code") String code);

    @Query("SELECT COUNT(i) > 0 FROM ContractBoqItem i WHERE i.organization.id = :orgId AND i.subContractId = :contractId")
    boolean existsForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);
}
