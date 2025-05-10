package org.tornotron.echno_backend.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.ProjectDtoForOrg;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrganizationService {

    private final OrganizationRepository repository;

    public OrganizationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    private ProjectDtoForOrg convertProjectToProjectDtoForOrg(Project project) {
        ProjectDtoForOrg projectDtoForOrg = new ProjectDtoForOrg();
        projectDtoForOrg.setProjectName(project.getProjectName());
        projectDtoForOrg.setProjectAddress(project.getProjectAddress());
        return projectDtoForOrg;
    }

    private OrganizationDto convertToDto(Organization organization) {
        OrganizationDto dto = new OrganizationDto();
        dto.setId(organization.getId());
        dto.setOrganizationName(organization.getOrganizationName());
        dto.setOrganizationAddress(organization.getOrganizationAddress());
        dto.setCreatedAt(organization.getCreatedAt());
        dto.setProjects(organization.getProjects().stream()
                .map(this::convertProjectToProjectDtoForOrg)
                .collect(Collectors.toList()));
        return dto;
    }

    public void addOrganization(OrganizationCreationDto organizationCreationDto) {
        Organization organization = new Organization();
        organization.setOrganizationName(organizationCreationDto.getOrganizationName());
        organization.setOrganizationAddress(organizationCreationDto.getOrganizationAddress());
        organization.setCreatedAt(LocalDateTime.now());
        Organization savedOrganization = repository.save(organization);
        if(savedOrganization.getId() == null) {
            throw new DatabaseOperationException("Organization could not be created");
        }
    }

    public Page<OrganizationDto> getAllOrganization(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(this::convertToDto);
    }

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

    public void partialUpdateAProject(Map<String, Object> updates, Long id) {
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
           }
       });
       repository.save(organization);
    }

    public void batchUpdateOrganization(List<OrganizationPatchDto> updates) {
        updates.forEach(update -> partialUpdateAProject(update.getUpdates(),update.getId()));
    }

    public void deleteAProject(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Organization not found with id: "+id);
        } else {
            repository.deleteById(id);
        }
    }
}
