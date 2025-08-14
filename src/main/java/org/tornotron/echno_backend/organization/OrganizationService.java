package org.tornotron.echno_backend.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.teamMember.TeamMember;
import org.tornotron.echno_backend.teamMember.dto.TeamMemberDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrganizationService {

    private final OrganizationRepository repository;

    public OrganizationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    private TeamMemberDto convertTeamMemberToTeamMemberDTO(TeamMember teamMember) {
        TeamMemberDto teamMemberDto = new TeamMemberDto();
        teamMemberDto.setId(teamMember.getId());
        teamMemberDto.setMemberName(teamMember.getMemberName());
        teamMemberDto.setMemberEmail(teamMember.getMemberEmail());
        teamMemberDto.setMemberPhone(teamMember.getMemberPhone());
        teamMemberDto.setMemberRole(teamMember.getMemberRole());
        teamMemberDto.setMemberImage(teamMember.getMemberImage());
        return teamMemberDto;
    }

    private ProjectDto convertProjectToProjectDto(Project project) {
        ProjectDto projectDto = new ProjectDto();
        projectDto.setId(project.getId());
        projectDto.setProjectName(project.getProjectName());
        projectDto.setProjectAddress(project.getProjectAddress());
        projectDto.setStatus(project.getStatus());
        projectDto.setCreatedAt(project.getCreatedAt());
        projectDto.setTeamMembers(project.getTeamMembers().stream()
                .map(this::convertTeamMemberToTeamMemberDTO)
                .collect(Collectors.toList()));
        return projectDto;
    }

    private EmployeeDto convertEmployeeToEmployeeDto(Employee employee) {
        EmployeeDto employeeDto = new EmployeeDto();
        employeeDto.setId(employee.getId());
        employeeDto.setEmployeeName(employee.getEmployeeName());
        employeeDto.setGender(employee.getGender());
        employeeDto.setPhoneNumber(employee.getPhoneNumber());
        employeeDto.setEmailAddress(employee.getEmailAddress());
        employeeDto.setDateOfBirth(employee.getDateOfBirth());
        return employeeDto;
    }

    private OrganizationSimpleDto convertToSimpleDto(Organization organization) {
        OrganizationSimpleDto dto = new OrganizationSimpleDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setIsActive(organization.getIsActive());
        dto.setCreatorId(organization.getCreatorId());
        return dto;
    }


    private OrganizationDto convertToDto(Organization organization) {
        OrganizationDto dto = new OrganizationDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setOrganizationEmail(organization.getOrganizationEmail());
        dto.setOrganizationPhone(organization.getOrganizationPhone());
        dto.setOrganizationWebsite(organization.getOrganizationWebsite());
        dto.setOrganizationLogo(organization.getOrganizationLogo());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setEmployees(organization.getEmployees().stream()
                .map(this::convertEmployeeToEmployeeDto)
                .collect(Collectors.toList()));
        dto.setProjects(organization.getProjects().stream()
                .map(this::convertProjectToProjectDto)
                .collect(Collectors.toList()));
        dto.setIsActive(organization.getIsActive());
        return dto;
    }

    public OrganizationSimpleDto addOrganization(OrganizationCreationDto organizationCreationDto) {
        Organization organization = new Organization();
        organization.setOrganizationName(organizationCreationDto.getOrganizationName());
        organization.setOrganizationAddress(organizationCreationDto.getOrganizationAddress());
        organization.setCreatedAt(LocalDateTime.now());
        organization.setOrganizationEmail(organizationCreationDto.getOrganizationEmail());
        organization.setOrganizationPhone(organizationCreationDto.getOrganizationPhone());
        organization.setOrganizationWebsite(organizationCreationDto.getOrganizationWebsite());
        organization.setOrganizationLogo(organizationCreationDto.getOrganizationLogo());
        organization.setCreatorId(organizationCreationDto.getCreatorId());
        organization.setIsActive(true);
        return convertToSimpleDto(repository.save(organization));
    }

    @Transactional(readOnly = true)
    public Page<OrganizationDto> getAllOrganization(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(this::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizationsByCreatorId(Integer creatorId) {
        return repository.findOrganizationsByCreatorId(creatorId)
                .stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrganizationDto getAnOrganization(Long id) {
        OrganizationDto organizationDto = repository.findById(id)
                .map(this::convertToDto)
                .orElse(null);
        if(organizationDto == null) {
            throw new ResourceNotFoundException("Organization not found with id: "+id);
        } else {
            return organizationDto;
        }
    }

    public OrganizationSimpleDto partialUpdateAnOrganization(Map<String, Object> updates, Long id) {
       Organization organization = repository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: "+id));
       updates.forEach((key, value) -> {
           switch (key) {
               case "organizationName":
                     organization.setOrganizationName((String) value);
                     break;
               case "organizationAddress":
                     organization.setOrganizationAddress((String) value);
                     break;
               case "organizationEmail":
                     organization.setOrganizationEmail((String) value);
                     break;
               case "organizationPhone":
                     organization.setOrganizationPhone((String) value);
                     break;
               case "organizationWebsite":
                     organization.setOrganizationWebsite((String) value);
                     break;
               case "organizationLogo":
                     organization.setOrganizationLogo((String) value);
                     break;
           }
       });
      return convertToSimpleDto(repository.save(organization));
    }

    public void batchUpdateOrganization(List<OrganizationPatchDto> updates) {
        updates.forEach(update -> partialUpdateAnOrganization(update.getUpdates(),update.getId()));
    }

    public void deleteAnOrganization(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Organization not found with id: "+id);
        } else {
            repository.deleteById(id);
        }
    }
}
