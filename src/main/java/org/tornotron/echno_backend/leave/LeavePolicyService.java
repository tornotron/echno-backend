package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CRUD and lifecycle for leave policies within an organization.
 *
 * <p>Enforces one policy per leave-type code per organization, supports partial updates and
 * activation toggles (policies are deactivated rather than deleted), and can copy a policy into
 * another organization the caller owns. Eligibility queries filter policies by an employee's
 * gender and accrued service months.
 */
@Service
@Validated
public class LeavePolicyService {

    private final LeavePolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;
    private final UserContextService userContextService;
    private final LeavePolicyMapper leavePolicyMapper;

    public LeavePolicyService(
            LeavePolicyRepository policyRepository,
            OrganizationRepository organizationRepository,
            EmployeeRepository employeeRepository, UserContextService userContextService,
            LeavePolicyMapper leavePolicyMapper) {
        this.policyRepository = policyRepository;
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
        this.userContextService = userContextService;
        this.leavePolicyMapper = leavePolicyMapper;
    }

    /**
     * Creates a leave policy for the current organization.
     *
     * <p>The leave-type code is stored uppercased and must be unique within the organization; the
     * policy is created active.
     *
     * @param dto The policy attributes.
     * @return The created policy.
     * @throws ResourceNotFoundException if the current organization is not found for this user.
     * @throws DuplicateResourceException if a policy with the same leave-type code already exists.
     */
    @Transactional
    public LeavePolicyDto createPolicy(LeavePolicyCreationDto dto) {
        Organization organization = organizationRepository.findByIdAndUserEmail(TenantContext.getCurrentOrgId(), userContextService.getCurrentUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization with ID " + dto.getOrganizationId() + " was not found"));

        if (policyRepository.existsByOrganizationIdAndLeaveTypeCode(
                dto.getOrganizationId(), dto.getLeaveTypeCode())) {
            throw new DuplicateResourceException(
                    "Leave policy with code '" + dto.getLeaveTypeCode() +
                    "' already exists for this organization");
        }

        LeavePolicy policy = new LeavePolicy();
        policy.setOrganization(organization);
        policy.setLeaveTypeCode(dto.getLeaveTypeCode().toUpperCase());
        policy.setLeaveTypeName(dto.getLeaveTypeName());
        policy.setDescription(dto.getDescription());
        policy.setAnnualQuota(dto.getAnnualQuota());
        policy.setAccrualRatePerMonth(dto.getAccrualRatePerMonth());
        policy.setCarryForwardLimit(dto.getCarryForwardLimit());
        policy.setCarryForwardExpiryMonths(dto.getCarryForwardExpiryMonths());
        policy.setMinDaysPerRequest(dto.getMinDaysPerRequest());
        policy.setMaxDaysPerRequest(dto.getMaxDaysPerRequest());
        policy.setAdvanceNoticeDays(dto.getAdvanceNoticeDays());
        policy.setRequiresAttachment(dto.getRequiresAttachment());
        policy.setAttachmentRequiredAfterDays(dto.getAttachmentRequiredAfterDays());
        policy.setApplicableGenders(dto.getApplicableGenders());
        policy.setMinServiceMonths(dto.getMinServiceMonths());
        policy.setAllowHalfDay(dto.getAllowHalfDay());
        policy.setIsPaid(dto.getIsPaid());
        policy.setIsActive(true);
        policy.setDisplayOrder(dto.getDisplayOrder());
        if (dto.getMultiLevelApprovalEnabled() != null) {
            policy.setMultiLevelApprovalEnabled(dto.getMultiLevelApprovalEnabled());
        }

        LeavePolicy saved = policyRepository.save(policy);
        return leavePolicyMapper.toDto(saved);
    }

    /**
     * Retrieves a single leave policy by its ID.
     *
     * @param policyId The ID of the policy to retrieve.
     * @return The policy.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional(readOnly = true)
    public LeavePolicyDto getPolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));
        return leavePolicyMapper.toDto(policy);
    }

    /**
     * Lists all leave policies visible to the current tenant.
     *
     * @return Every policy for the current organization.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPolicies() {
        return policyRepository.findAll().stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the active policies for an organization, ordered by display order.
     *
     * @param organizationId The organization's ID.
     * @return The active policies in display order.
     * @throws ResourceNotFoundException if the organization is not found.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization with ID " + organizationId + " was not found");
        }

        return policyRepository.findByOrganizationIdAndIsActiveTrueOrderByDisplayOrderAsc(organizationId)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists all policies for an organization, including inactive ones.
     *
     * @param organizationId The organization's ID.
     * @return Every policy for the organization.
     * @throws ResourceNotFoundException if the organization is not found.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization with ID " + organizationId + " was not found");
        }

        return policyRepository.findByOrganizationId(organizationId)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the policies an employee is currently eligible for.
     *
     * <p>Filters by the employee's gender and their service months computed from the joining date.
     *
     * @param employeeId The employee's ID.
     * @return The policies applicable to the employee.
     * @throws ResourceNotFoundException if the employee is not found in this organization.
     */
    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getApplicablePoliciesForEmployee(Long employeeId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LocalDateTime joiningDate = employee.getJoiningDate();
        int serviceMonths = 0;
        if (joiningDate != null) {
            serviceMonths = (int) ChronoUnit.MONTHS.between(joiningDate, LocalDateTime.now());
        }

        return policyRepository.findApplicablePolicies(
                        employee.getOrganization().getId(),
                        employee.getGender(),
                        serviceMonths)
                .stream()
                .map(leavePolicyMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Applies a partial update to a policy.
     *
     * <p>Only the supplied keys are changed; the leave-type code and organization are not editable
     * here, and unrecognized keys are ignored.
     *
     * @param policyId The ID of the policy to update.
     * @param updates A map of field names to new values.
     * @return The updated policy.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public LeavePolicyDto updatePolicy(Long policyId, Map<String, Object> updates) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        updates.forEach((key, value) -> {
            switch (key) {
                case "leaveTypeName" -> policy.setLeaveTypeName((String) value);
                case "description" -> policy.setDescription((String) value);
                case "annualQuota" -> policy.setAnnualQuota(((Number) value).doubleValue());
                case "accrualRatePerMonth" -> policy.setAccrualRatePerMonth(
                        value != null ? ((Number) value).doubleValue() : null);
                case "carryForwardLimit" -> policy.setCarryForwardLimit(
                        value != null ? ((Number) value).doubleValue() : null);
                case "carryForwardExpiryMonths" -> policy.setCarryForwardExpiryMonths(
                        value != null ? ((Number) value).intValue() : null);
                case "minDaysPerRequest" -> policy.setMinDaysPerRequest(
                        value != null ? ((Number) value).doubleValue() : null);
                case "maxDaysPerRequest" -> policy.setMaxDaysPerRequest(
                        value != null ? ((Number) value).doubleValue() : null);
                case "advanceNoticeDays" -> policy.setAdvanceNoticeDays(
                        value != null ? ((Number) value).intValue() : null);
                case "requiresAttachment" -> policy.setRequiresAttachment((Boolean) value);
                case "attachmentRequiredAfterDays" -> policy.setAttachmentRequiredAfterDays(
                        value != null ? ((Number) value).intValue() : null);
                case "applicableGenders" -> policy.setApplicableGenders((String) value);
                case "minServiceMonths" -> policy.setMinServiceMonths(
                        value != null ? ((Number) value).intValue() : null);
                case "allowHalfDay" -> policy.setAllowHalfDay((Boolean) value);
                case "isPaid" -> policy.setIsPaid((Boolean) value);
                case "isActive" -> policy.setIsActive((Boolean) value);
                case "multiLevelApprovalEnabled" -> policy.setMultiLevelApprovalEnabled((Boolean) value);
                case "displayOrder" -> policy.setDisplayOrder(
                        value != null ? ((Number) value).intValue() : null);
            }
        });

        LeavePolicy saved = policyRepository.save(policy);
        return leavePolicyMapper.toDto(saved);
    }

    /**
     * Marks a policy inactive, keeping it for historical reference.
     *
     * @param policyId The ID of the policy to deactivate.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public void deactivatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        policy.setIsActive(false);
        policyRepository.save(policy);
    }

    /**
     * Marks a previously deactivated policy active again.
     *
     * @param policyId The ID of the policy to activate.
     * @throws ResourceNotFoundException if no policy with the given ID exists in this organization.
     */
    @Transactional
    public void activatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        policy.setIsActive(true);
        policyRepository.save(policy);
    }

    /**
     * Copies a policy's attributes into another organization the caller owns.
     *
     * <p>The copy is created active and keeps the source's leave-type code, which must not already
     * exist in the target organization.
     *
     * @param policyId The ID of the source policy.
     * @param targetOrganizationId The ID of the organization to copy into.
     * @return The newly created policy in the target organization.
     * @throws ResourceNotFoundException if the source policy or target organization is not found.
     * @throws DuplicateResourceException if the target already has a policy with the same leave-type code.
     */
    @Transactional
    public LeavePolicyDto duplicatePolicy(Long policyId, Long targetOrganizationId) {
        LeavePolicy source = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        Organization targetOrg = organizationRepository.findByIdAndUserEmail(targetOrganizationId,userContextService.getCurrentUserEmail())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target organization with ID " + targetOrganizationId + " was not found"));

        if (policyRepository.existsByOrganizationIdAndLeaveTypeCode(
                targetOrganizationId, source.getLeaveTypeCode())) {
            throw new DuplicateResourceException(
                    "Leave policy with code '" + source.getLeaveTypeCode() +
                    "' already exists in target organization");
        }

        LeavePolicy duplicate = new LeavePolicy();
        duplicate.setOrganization(targetOrg);
        duplicate.setLeaveTypeCode(source.getLeaveTypeCode());
        duplicate.setLeaveTypeName(source.getLeaveTypeName());
        duplicate.setDescription(source.getDescription());
        duplicate.setAnnualQuota(source.getAnnualQuota());
        duplicate.setAccrualRatePerMonth(source.getAccrualRatePerMonth());
        duplicate.setCarryForwardLimit(source.getCarryForwardLimit());
        duplicate.setCarryForwardExpiryMonths(source.getCarryForwardExpiryMonths());
        duplicate.setMinDaysPerRequest(source.getMinDaysPerRequest());
        duplicate.setMaxDaysPerRequest(source.getMaxDaysPerRequest());
        duplicate.setAdvanceNoticeDays(source.getAdvanceNoticeDays());
        duplicate.setRequiresAttachment(source.getRequiresAttachment());
        duplicate.setAttachmentRequiredAfterDays(source.getAttachmentRequiredAfterDays());
        duplicate.setApplicableGenders(source.getApplicableGenders());
        duplicate.setMinServiceMonths(source.getMinServiceMonths());
        duplicate.setAllowHalfDay(source.getAllowHalfDay());
        duplicate.setIsPaid(source.getIsPaid());
        duplicate.setIsActive(true);
        duplicate.setDisplayOrder(source.getDisplayOrder());
        duplicate.setMultiLevelApprovalEnabled(source.getMultiLevelApprovalEnabled());

        LeavePolicy saved = policyRepository.save(duplicate);
        return leavePolicyMapper.toDto(saved);
    }
}
