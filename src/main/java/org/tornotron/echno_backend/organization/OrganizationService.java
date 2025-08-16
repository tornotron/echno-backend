package org.tornotron.echno_backend.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
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
    private final OrganizationDtoConvertor orgConvertor;

    public OrganizationService(OrganizationRepository repository, OrganizationDtoConvertor orgConvertor) {
        this.orgConvertor = orgConvertor;
        this.repository = repository;
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
        return orgConvertor.convertOrganizationToSimpleDto(repository.save(organization));
    }

    @Transactional(readOnly = true)
    public Page<OrganizationDto> getAllOrganization(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(OrganizationDtoConvertor::convertOrganizationToDto);
    }

    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizationsByCreatorId(Integer creatorId) {
        return repository.findOrganizationsByCreatorId(creatorId)
                .stream()
                .map(OrganizationDtoConvertor::convertOrganizationToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrganizationDto getAnOrganization(Long id) {
        OrganizationDto organizationDto = repository.findById(id)
                .map(OrganizationDtoConvertor::convertOrganizationToDto)
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
      return orgConvertor.convertOrganizationToSimpleDto(repository.save(organization));
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
