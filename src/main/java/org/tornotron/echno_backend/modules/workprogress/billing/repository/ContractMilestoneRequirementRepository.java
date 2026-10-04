package org.tornotron.echno_backend.modules.workprogress.billing.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractMilestoneRequirement;

@Repository
public interface ContractMilestoneRequirementRepository extends JpaRepository<ContractMilestoneRequirement, UUID> {

    @Query("SELECT r FROM ContractMilestoneRequirement r WHERE r.id = :id AND r.organization.id = :orgId")
    Optional<ContractMilestoneRequirement> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // The requirements of some milestones of one contract, in their order. Bounded by the contract.
    @Query("SELECT r FROM ContractMilestoneRequirement r WHERE r.organization.id = :orgId "
            + "AND r.contractMilestoneId IN :milestoneIds ORDER BY r.sortOrder ASC, r.createdAt ASC")
    List<ContractMilestoneRequirement> findForMilestones(@Param("orgId") Long orgId,
                                                         @Param("milestoneIds") Collection<Long> milestoneIds);

    @Query("SELECT COUNT(r) > 0 FROM ContractMilestoneRequirement r WHERE r.organization.id = :orgId AND r.subContractId = :contractId")
    boolean existsForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);
}
