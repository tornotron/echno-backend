package org.tornotron.echno_backend.modules.workprogress.billing.service;

import java.util.Collection;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBillRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractBoqItemRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractDeductionRuleRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.repository.ContractMilestoneRequirementRepository;
import org.tornotron.echno_backend.subcontract.SubContractRecords;

/**
 * Tells the sub-contract that a contract with billing records is part of the record. A contract
 * with bills, a BOQ, deduction rules or milestone requirements cannot be deleted, and a milestone
 * that has been billed cannot be removed from it. A milestone's requirements alone do not hold
 * it: they go with the milestone.
 */
@Component
public class ContractBillingRecords implements SubContractRecords {

    private final ContractBillRepository bills;
    private final ContractBoqItemRepository boqItems;
    private final ContractDeductionRuleRepository rules;
    private final ContractMilestoneRequirementRepository requirements;

    public ContractBillingRecords(ContractBillRepository bills, ContractBoqItemRepository boqItems,
                                  ContractDeductionRuleRepository rules,
                                  ContractMilestoneRequirementRepository requirements) {
        this.bills = bills;
        this.boqItems = boqItems;
        this.rules = rules;
        this.requirements = requirements;
    }

    @Override
    public String recordsHeldAgainstContract(Long subContractId) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (bills.existsForContract(orgId, subContractId)) {
            return "bills";
        }
        if (boqItems.existsForContract(orgId, subContractId)) {
            return "a bill of quantities";
        }
        if (rules.existsForContract(orgId, subContractId)) {
            return "billing deduction rules";
        }
        if (requirements.existsForContract(orgId, subContractId)) {
            return "milestone requirements";
        }
        return null;
    }

    @Override
    public String recordsHeldAgainstMilestones(Collection<Long> milestoneIds) {
        if (milestoneIds.isEmpty()) {
            return null;
        }
        return bills.existsForMilestones(TenantContext.getCurrentOrgId(), milestoneIds) ? "bills" : null;
    }
}
