package org.tornotron.echno_backend.modules.workprogress.billing.service;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;
import org.tornotron.echno_backend.subcontract.ContractMilestone;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractRepository;
import org.tornotron.echno_backend.user.UserRepository;

/**
 * Lookups the billing services share: the contract and milestone in the caller's organization,
 * and a readable name for a user id.
 */
@Component
public class BillingSupport {

    /** The statuses whose figures count on the running account. */
    public static final Set<BillStatus> CERTIFIED = EnumSet.of(BillStatus.CERTIFIED, BillStatus.APPROVED);

    private final SubContractRepository subContracts;
    private final EmployeeRepository employees;
    private final UserRepository users;

    public BillingSupport(SubContractRepository subContracts, EmployeeRepository employees, UserRepository users) {
        this.subContracts = subContracts;
        this.employees = employees;
        this.users = users;
    }

    public SubContract requireContract(Long subContractId, Long orgId) {
        return subContracts.findByIdAndOrganization_Id(subContractId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Subcontract not found: " + subContractId));
    }

    public static ContractMilestone requireMilestone(SubContract contract, Long milestoneId) {
        return contract.getMilestones().stream()
                .filter(milestone -> milestone.getId().equals(milestoneId))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("Milestone " + milestoneId
                        + " is not part of subcontract " + contract.getId()));
    }

    /** The contract's own reference, or one made from its id when it has none. */
    public static String contractRef(SubContract contract) {
        String ref = contract.getContractId();
        return ref == null || ref.isBlank() ? "SC-" + contract.getId() : ref.trim();
    }

    /** The employee name of a user in this organization, else the user's own name, else null. */
    public String nameOf(Long userId, Long orgId) {
        if (userId == null) {
            return null;
        }
        return employees.findByUserIdAndOrganizationId(userId, orgId)
                .map(employee -> employee.getEmployeeName())
                .or(() -> users.findById(userId).map(user -> user.getName()))
                .orElse(null);
    }

    static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
