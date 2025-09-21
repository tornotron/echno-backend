package org.tornotron.echno_backend.projectInviteCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.InvalidInviteCodeException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeGenerationDto;
import org.tornotron.echno_backend.projectInviteCode.dto.InviteCodeValidationDto;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class ProjectInviteCodeService {

    private final ProjectInviteCodeRepository inviteCodeRepository;
    private final EmployeeService employeeService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final OrganizationRepository organizationRepository;

    public ProjectInviteCodeService(ProjectInviteCodeRepository inviteCodeRepository, EmployeeService employeeService, OrganizationRepository organizationRepository) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.employeeService = employeeService;
        this.organizationRepository = organizationRepository;
    }

    public int generateSecureFiveDigitNumber() {
        return 10000 + secureRandom.nextInt(90000);
    }

    public void generateInviteCode(InviteCodeGenerationDto inviteCodeGenerationDto) {
        Organization organization = organizationRepository.findOrganizationByOrganizationName(inviteCodeGenerationDto.getOrganizationName())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with name: " + inviteCodeGenerationDto.getOrganizationName()));
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
        employeeDetails.put("joiningDate", inviteCodeGenerationDto.getJoiningDate());
        employeeDetails.put("salary", inviteCodeGenerationDto.getSalary());
        employeeDetails.put("reportingManager", inviteCodeGenerationDto.getReportingManager());
        employeeDetails.put("shiftTiming", inviteCodeGenerationDto.getShiftTiming());
        employeeDetails.put("status", inviteCodeGenerationDto.getStatus());
        projectInviteCode.setEmployeeDetails(employeeDetails);
        ProjectInviteCode savedProjectInviteCode = inviteCodeRepository.save(projectInviteCode);
        if(savedProjectInviteCode.getId() == null) {
            throw new DatabaseOperationException("Invite code could not be created");
        }
    }

    public void validateAndUseInviteCode(InviteCodeValidationDto inviteCodeValidationDto) {
        Optional<ProjectInviteCode> inviteCodeOptional = inviteCodeRepository.findByCode(Integer.parseInt(inviteCodeValidationDto.getCode()));
        if (inviteCodeOptional.isPresent()) {
            ProjectInviteCode projectInviteCode = inviteCodeOptional.get();

            if (projectInviteCode.getExpiryDate().isBefore(LocalDateTime.now())) {
                throw new InvalidInviteCodeException("Invite code has expired");
            }
            if (projectInviteCode.getCurrentUses() >= projectInviteCode.getMaxUses()) {
                throw new InvalidInviteCodeException("Invite code has reached maximum usage limit");
            }
            if (!projectInviteCode.isActive()) {
                throw new InvalidInviteCodeException("Invite code is not active");
            }

            projectInviteCode.setCurrentUses(projectInviteCode.getCurrentUses() + 1);

            if (projectInviteCode.getCurrentUses() >= projectInviteCode.getMaxUses()) {
                projectInviteCode.setActive(false);
            }

            inviteCodeRepository.save(projectInviteCode);

            Organization organization = projectInviteCode.getOrganization();

            Map<String,Object> employeeDetails = projectInviteCode.getEmployeeDetails();
            EmployeeJoinOrgDto employeeJoinOrgDto = new EmployeeJoinOrgDto();
            employeeJoinOrgDto.setDesignation((String) employeeDetails.get("designation"));
            employeeJoinOrgDto.setDepartment((String) employeeDetails.get("department"));
            if (employeeDetails.get("joiningDate") != null) {
                employeeJoinOrgDto.setJoiningDate(LocalDateTime.parse(employeeDetails.get("joiningDate").toString()));
            }
            employeeJoinOrgDto.setSalary((Double) employeeDetails.get("salary"));
            employeeJoinOrgDto.setReportingManager((String) employeeDetails.get("reportingManager"));
            employeeJoinOrgDto.setShiftTiming((String) employeeDetails.get("shiftTiming"));
            employeeJoinOrgDto.setStatus((String) employeeDetails.get("status"));

            employeeService.joinOrganization(inviteCodeValidationDto.getUserId(), organization.getId(), employeeJoinOrgDto);

        } else {
            throw new InvalidInviteCodeException("Invite code not found");
        }
    }
}
