package org.tornotron.echno_backend.modules.workprogress.billing.repository;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.ContractBill;
import org.tornotron.echno_backend.subcontract.SubContract;

@Repository
public interface ContractBillRepository extends JpaRepository<ContractBill, UUID>, JpaSpecificationExecutor<ContractBill> {

    @Query("SELECT b FROM ContractBill b WHERE b.id = :id AND b.organization.id = :orgId")
    Optional<ContractBill> findScoped(@Param("id") UUID id, @Param("orgId") Long orgId);

    // A contract's bills, newest first. Bounded by the contract: one a month over its life.
    @Query("SELECT b FROM ContractBill b WHERE b.organization.id = :orgId AND b.subContractId = :contractId "
            + "ORDER BY b.sequenceNo DESC")
    List<ContractBill> findForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);

    // The bills of one page of contracts, for the billing home page. Bounded by the page.
    @Query("SELECT b FROM ContractBill b WHERE b.organization.id = :orgId AND b.subContractId IN :contractIds "
            + "ORDER BY b.sequenceNo DESC")
    List<ContractBill> findForContracts(@Param("orgId") Long orgId, @Param("contractIds") Collection<Long> contractIds);

    @Query("SELECT COALESCE(MAX(b.sequenceNo), 0) FROM ContractBill b WHERE b.organization.id = :orgId "
            + "AND b.subContractId = :contractId")
    int maxSequence(@Param("orgId") Long orgId, @Param("contractId") Long contractId);

    @Query("SELECT COUNT(b) > 0 FROM ContractBill b WHERE b.organization.id = :orgId AND b.subContractId = :contractId")
    boolean existsForContract(@Param("orgId") Long orgId, @Param("contractId") Long contractId);

    @Query("SELECT COUNT(b) > 0 FROM ContractBill b WHERE b.organization.id = :orgId "
            + "AND b.contractMilestoneId IN :milestoneIds")
    boolean existsForMilestones(@Param("orgId") Long orgId, @Param("milestoneIds") Collection<Long> milestoneIds);

    // Certified quantity to date per BOQ item of a contract: the accepted quantities of its
    // certified and approved bills.
    @Query("SELECT l.boqItemId, SUM(l.acceptedQuantity) FROM ContractBill b JOIN b.lines l "
            + "WHERE b.organization.id = :orgId AND b.subContractId = :contractId AND b.status IN :statuses "
            + "GROUP BY l.boqItemId")
    List<Object[]> certifiedQuantities(@Param("orgId") Long orgId, @Param("contractId") Long contractId,
                                       @Param("statuses") Collection<BillStatus> statuses);

    @Query("SELECT COUNT(l) > 0 FROM ContractBill b JOIN b.lines l WHERE b.organization.id = :orgId "
            + "AND l.boqItemId = :itemId")
    boolean boqItemInUse(@Param("orgId") Long orgId, @Param("itemId") UUID itemId);

    // What each rule of a contract has taken so far, over its certified and approved bills.
    @Query("SELECT a.ruleId, SUM(a.amount) FROM ContractBill b JOIN b.adjustments a "
            + "WHERE b.organization.id = :orgId AND b.subContractId = :contractId AND b.status IN :statuses "
            + "AND a.ruleId IS NOT NULL GROUP BY a.ruleId")
    List<Object[]> appliedByRule(@Param("orgId") Long orgId, @Param("contractId") Long contractId,
                                 @Param("statuses") Collection<BillStatus> statuses);

    @Query("SELECT COUNT(b) > 0 FROM ContractBill b JOIN b.adjustments a WHERE b.organization.id = :orgId "
            + "AND a.ruleId = :ruleId")
    boolean ruleInUse(@Param("orgId") Long orgId, @Param("ruleId") UUID ruleId);

    @Query("SELECT COALESCE(SUM(b.grossCertified), 0) FROM ContractBill b WHERE b.organization.id = :orgId "
            + "AND b.subContractId = :contractId AND b.status IN :statuses")
    BigDecimal certifiedGross(@Param("orgId") Long orgId, @Param("contractId") Long contractId,
                              @Param("statuses") Collection<BillStatus> statuses);

    // Percent of each milestone certified so far, over certified and approved bills.
    @Query("SELECT b.contractMilestoneId, SUM(b.certifiedPercent) FROM ContractBill b WHERE b.organization.id = :orgId "
            + "AND b.subContractId = :contractId AND b.status IN :statuses AND b.contractMilestoneId IS NOT NULL "
            + "GROUP BY b.contractMilestoneId")
    List<Object[]> certifiedPercentByMilestone(@Param("orgId") Long orgId, @Param("contractId") Long contractId,
                                               @Param("statuses") Collection<BillStatus> statuses);

    // Organization-wide counts by status, for the home page cards.
    @Query("SELECT b.status, COUNT(b), COALESCE(SUM(b.grossCertified), 0), COALESCE(SUM(b.netPayable), 0) "
            + "FROM ContractBill b WHERE b.organization.id = :orgId GROUP BY b.status")
    List<Object[]> statusTotals(@Param("orgId") Long orgId);

    @Query(value = "SELECT s FROM SubContract s WHERE s.organization.id = :orgId ORDER BY s.createdAt DESC",
            countQuery = "SELECT COUNT(s) FROM SubContract s WHERE s.organization.id = :orgId")
    Page<SubContract> contracts(@Param("orgId") Long orgId, Pageable pageable);

    @Query(value = "SELECT s FROM SubContract s WHERE s.organization.id = :orgId AND s.projectId = :projectId "
            + "ORDER BY s.createdAt DESC",
            countQuery = "SELECT COUNT(s) FROM SubContract s WHERE s.organization.id = :orgId AND s.projectId = :projectId")
    Page<SubContract> contractsOfProject(@Param("orgId") Long orgId, @Param("projectId") Long projectId,
                                         Pageable pageable);

    // Paged, newest first. A Specification rather than "(:x IS NULL OR ...)" in JPQL, which
    // CockroachDB cannot type.
    default Page<ContractBill> findPage(Long orgId, Long projectId, Long contractId, BillStatus status,
                                        BillingModel model, Pageable pageable) {
        Specification<ContractBill> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("organization").get("id"), orgId));
            if (projectId != null) {
                where.add(cb.equal(root.get("projectId"), projectId));
            }
            if (contractId != null) {
                where.add(cb.equal(root.get("subContractId"), contractId));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (model != null) {
                where.add(cb.equal(root.get("billingModel"), model));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt")));
        return findAll(spec, sorted);
    }
}
