package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.LeavePolicyDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Validated
public class LeavePolicyService {

    private final LeavePolicyRepository policyRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeRepository employeeRepository;

    public LeavePolicyService(
            LeavePolicyRepository policyRepository,
            OrganizationRepository organizationRepository,
            EmployeeRepository employeeRepository) {
        this.policyRepository = policyRepository;
        this.organizationRepository = organizationRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public LeavePolicyDto createPolicy(LeavePolicyCreationDto dto) {
        Organization organization = organizationRepository.findById(dto.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization not found with id: " + dto.getOrganizationId()));

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

        LeavePolicy saved = policyRepository.save(policy);
        return LeavePolicyDtoConvertor.convertToDto(saved);
    }

    @Transactional(readOnly = true)
    public LeavePolicyDto getPolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));
        return LeavePolicyDtoConvertor.convertToDto(policy);
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPolicies() {
        return policyRepository.findAll().stream()
                .map(LeavePolicyDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization not found with id: " + organizationId);
        }

        return policyRepository.findByOrganizationIdAndIsActiveTrueOrderByDisplayOrderAsc(organizationId)
                .stream()
                .map(LeavePolicyDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getAllPoliciesByOrganization(Long organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException(
                    "Organization not found with id: " + organizationId);
        }

        return policyRepository.findByOrganizationId(organizationId)
                .stream()
                .map(LeavePolicyDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyDto> getApplicablePoliciesForEmployee(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + employeeId));

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
                .map(LeavePolicyDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeavePolicyDto updatePolicy(Long policyId, Map<String, Object> updates) {
        LeavePolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

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
                case "displayOrder" -> policy.setDisplayOrder(
                        value != null ? ((Number) value).intValue() : null);
            }
        });

        LeavePolicy saved = policyRepository.save(policy);
        return LeavePolicyDtoConvertor.convertToDto(saved);
    }

    @Transactional
    public void deactivatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

        policy.setIsActive(false);
        policyRepository.save(policy);
    }

    @Transactional
    public void activatePolicy(Long policyId) {
        LeavePolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

        policy.setIsActive(true);
        policyRepository.save(policy);
    }

    @Transactional
    public LeavePolicyDto duplicatePolicy(Long policyId, Long targetOrganizationId) {
        LeavePolicy source = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

        Organization targetOrg = organizationRepository.findById(targetOrganizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organization not found with id: " + targetOrganizationId));

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

        LeavePolicy saved = policyRepository.save(duplicate);
        return LeavePolicyDtoConvertor.convertToDto(saved);
    }
}
