package org.tornotron.echno_backend.projectInviteCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.ProjectInviteCodeDtoConvertor;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidInviteCodeException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodePatchDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.ProjectInviteCodeDto;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service class for managing project invite codes.
 * Handles the business logic for generating, validating, and using invite codes
 * for employees to join an organization.
 */
@Service
public class ProjectInviteCodeService {

    private final ProjectInviteCodeRepository inviteCodeRepository;
    private final EmployeeService employeeService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final OrganizationRepository organizationRepository;
    private final FileStorageService fileStorageService;
    private final EmployeeRepository employeeRepository;

    /**
     * Constructs a ProjectInviteCodeService with the necessary repositories and services.
     *
     * @param inviteCodeRepository   The repository for invite code data access.
     * @param employeeService        The service for employee-related operations.
     * @param organizationRepository The repository for organization data access.
     */
    public ProjectInviteCodeService(ProjectInviteCodeRepository inviteCodeRepository, EmployeeService employeeService, OrganizationRepository organizationRepository, FileStorageService fileStorageService, EmployeeRepository employeeRepository) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.employeeService = employeeService;
        this.organizationRepository = organizationRepository;
        this.fileStorageService = fileStorageService;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Generates a secure, random five-digit number.
     *
     * @return A random integer between 10000 and 99999.
     */
    public int generateSecureFiveDigitNumber() {
        return 10000 + secureRandom.nextInt(90000);
    }

    /**
     * Generates a new invite code for an organization.
     * The code is created with specified validity, usage limits, and default employee details.
     *
     * @param inviteCodeGenerationDto DTO containing the details for generating the invite code.
     * @return A DTO of the newly created invite code.
     * @throws ResourceNotFoundException if the organization is not found.
     * @throws DatabaseOperationException if the invite code cannot be saved.
     */
    @Transactional
    public ProjectInviteCodeDto generateInviteCode(InviteCodeGenerationDto inviteCodeGenerationDto,Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + organizationId + " was not found"));
        if (inviteCodeGenerationDto.getManagerId() != null) {
            if (!employeeRepository.existsByIdAndOrgRolesIn(inviteCodeGenerationDto.getManagerId(), OrgRole.getManagerRoles())) {
                throw new ResourceNotFoundException("Manager with ID " + inviteCodeGenerationDto.getManagerId() + " was not found with a manager role in this organization");
            }
        }
        int inviteCode = generateSecureFiveDigitNumber();
        ProjectInviteCode projectInviteCode = new ProjectInviteCode();
        projectInviteCode.setCode(inviteCode);
        projectInviteCode.setOrganization(organization);
        projectInviteCode.setExpiryDate(LocalDateTime.now().plusDays(inviteCodeGenerationDto.getValidityDays()));
        projectInviteCode.setActive(true);
        projectInviteCode.setMaxUses(inviteCodeGenerationDto.getMaxUses());
        projectInviteCode.setCurrentUses(0);
        Map<String, Object> employeeDetails = new HashMap<>();
        employeeDetails.put("designation", inviteCodeGenerationDto.getDesignation());
        employeeDetails.put("department", inviteCodeGenerationDto.getDepartment());
        employeeDetails.put("joiningDate", LocalDateTime.now().toString());
        employeeDetails.put("salary", inviteCodeGenerationDto.getSalary());
        employeeDetails.put("managerId", inviteCodeGenerationDto.getManagerId());
        employeeDetails.put("shiftTiming", inviteCodeGenerationDto.getShiftTiming());
        employeeDetails.put("status", inviteCodeGenerationDto.getStatus());
        employeeDetails.put("employeeId", inviteCodeGenerationDto.getEmployeeId());
        employeeDetails.put("employeeName", inviteCodeGenerationDto.getEmployeeName());
        employeeDetails.put("email", inviteCodeGenerationDto.getEmail());
        employeeDetails.put("phone", inviteCodeGenerationDto.getPhone());
        projectInviteCode.setEmployeeDetails(employeeDetails);
        ProjectInviteCode savedProjectInviteCode = inviteCodeRepository.save(projectInviteCode);
        if(savedProjectInviteCode.getId() == null) {
            throw new DatabaseOperationException("Invite code for organization " + organizationId + " could not be persisted");
        }
        return ProjectInviteCodeDtoConvertor.convertToDto(savedProjectInviteCode);
    }

    /**
     * Validates an invite code and, if successful, adds the user to the associated organization as an employee.
     *
     * @param inviteCodeValidationDto DTO containing the user ID and the invite code to validate.
     * @return A DTO of the organization the user has joined.
     * @throws ResourceNotFoundException if the invite code is not found.
     * @throws InvalidInviteCodeException if the code is expired, inactive, or has reached its usage limit.
     */
    @Transactional
    public OrganizationDto validateAndUseInviteCode(InviteCodeValidationDto inviteCodeValidationDto,Long userId) {
        ProjectInviteCode inviteCode = inviteCodeRepository.findByCode(Integer.parseInt(inviteCodeValidationDto.getCode()))
                .orElseThrow(() -> new ResourceNotFoundException("Invite code '" + inviteCodeValidationDto.getCode() + "' was not found"));
        if(inviteCode.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new InvalidInviteCodeException("Invite code '" + inviteCodeValidationDto.getCode() + "' expired on " + inviteCode.getExpiryDate());
        }
        if(!inviteCode.isActive()) {
            throw new InvalidInviteCodeException("Invite code '" + inviteCodeValidationDto.getCode() + "' is no longer active");
        }
        if(inviteCode.getCurrentUses() >= inviteCode.getMaxUses()) {
            throw new InvalidInviteCodeException("Invite code '" + inviteCodeValidationDto.getCode() + "' has reached its maximum usage limit of " + inviteCode.getMaxUses());
        }
        inviteCodeRepository.save(inviteCode);
        Organization organization = inviteCode.getOrganization();
        Map<String,Object> employeeDetails = inviteCode.getEmployeeDetails();
        EmployeeJoinOrgDto employeeJoinOrgDto = new EmployeeJoinOrgDto();
        employeeJoinOrgDto.setDesignation((String) employeeDetails.get("designation"));
        employeeJoinOrgDto.setDepartment((String) employeeDetails.get("department"));
        if (employeeDetails.get("joiningDate") != null) {
            employeeJoinOrgDto.setJoiningDate(LocalDateTime.parse(employeeDetails.get("joiningDate").toString()));
        }
        employeeJoinOrgDto.setSalary((Double) employeeDetails.get("salary"));
        if (employeeDetails.get("managerId") != null) {
            employeeJoinOrgDto.setManagerId(((Number) employeeDetails.get("managerId")).longValue());
        }
        employeeJoinOrgDto.setShiftTiming((String) employeeDetails.get("shiftTiming"));
        employeeJoinOrgDto.setStatus((String) employeeDetails.get("status"));
        employeeJoinOrgDto.setEmployeeId((String) employeeDetails.get("employeeId"));
        employeeJoinOrgDto.setEmployeeName((String) employeeDetails.get("employeeName"));
        employeeJoinOrgDto.setEmail((String) employeeDetails.get("email"));
        employeeJoinOrgDto.setPhone((String) employeeDetails.get("phone"));
        employeeService.joinOrganization(userId, organization.getId(), employeeJoinOrgDto);
        inviteCode.setCurrentUses(inviteCode.getCurrentUses() + 1);
        return OrganizationDtoConvertor.convertOrganizationToDto(organization,fileStorageService);
    }

    /**
     * Partially updates an invite code's properties.
     * Only non-null fields in the DTO will be updated.
     *
     * @param inviteCodeId The ID of the invite code to update.
     * @param patchDto     DTO containing the fields to update.
     * @return A DTO of the updated invite code.
     * @throws ResourceNotFoundException if the invite code is not found.
     */
    @Transactional
    public ProjectInviteCodeDto patchInviteCode(Long inviteCodeId, InviteCodePatchDto patchDto) {
        ProjectInviteCode inviteCode = inviteCodeRepository.findByIdAndOrganization_Id(inviteCodeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Invite code with ID " + inviteCodeId + " was not found in this organization"));

        if (patchDto.getMaxUses() != null) {
            inviteCode.setMaxUses(patchDto.getMaxUses());
        }
        if (patchDto.getCurrentUses() != null) {
            inviteCode.setCurrentUses(patchDto.getCurrentUses());
        }
        if (patchDto.getIsActive() != null) {
            inviteCode.setActive(patchDto.getIsActive());
        }

        ProjectInviteCode updatedInviteCode = inviteCodeRepository.save(inviteCode);
        return ProjectInviteCodeDtoConvertor.convertToDto(updatedInviteCode);
    }

    @Transactional(readOnly = true)
    public List<ProjectInviteCodeDto> readAllProjectInviteCodes(Long organizationId) {
        return inviteCodeRepository.findByOrganization_Id(organizationId)
                .stream()
                .map(ProjectInviteCodeDtoConvertor::convertToDto)
                .toList();
    }
}