package org.tornotron.echno_backend.modules.workprogress.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractDeductionRule;

@Repository
public interface ContractDeductionRuleRepository extends JpaRepository<ContractDeductionRule, UUID> {

    @Query("SELECT r FROM ContractDeductionRule r WHERE r.id = :id AND r.organization.id = :orgId")
    Optional<ContractDeductionRule> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // A contract's rules in their order. Bounded by the contract: a handful of commercial terms.
    @Query("SELECT r FROM ContractDeductionRule r WHERE r.organization.id = :orgId AND r.subContractId = :contractId "
            + "ORDER BY r.sortOrder ASC, r.createdAt ASC")
    List<ContractDeductionRule> findForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);

    @Query("SELECT COUNT(r) > 0 FROM ContractDeductionRule r WHERE r.organization.id = :orgId AND r.subContractId = :contractId")
    boolean existsForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);
}
