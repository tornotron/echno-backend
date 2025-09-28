package org.tornotron.echno_backend.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service layer for managing organizations. This class encapsulates the business logic
 * for creating, retrieving, updating, and deleting organizations. It interacts with the
 * {@link OrganizationRepository} to perform database operations and uses
 * {@link OrganizationDtoConvertor} to map entities to DTOs.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository repository;
    private final OrganizationDtoConvertor orgConvertor;

    /**
     * Constructs an {@code OrganizationService} with the necessary dependencies.
     * @param repository The repository for accessing organization data.
     * @param orgConvertor The converter for mapping between Organization entities and DTOs.
     */
    public OrganizationService(OrganizationRepository repository, OrganizationDtoConvertor orgConvertor) {
        this.orgConvertor = orgConvertor;
        this.repository = repository;
    }

    /**
     * Creates and persists a new organization based on the provided data.
     * @param organizationCreationDto A DTO containing the details for the new organization.
     * @return An {@link OrganizationSimpleDto} representing the newly created organization.
     */
    @Transactional
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

    /**
     * Retrieves a paginated list of all organizations, sorted by their ID in ascending order.
     * @param pageNo The page number to retrieve (0-indexed).
     * @param pageSize The number of organizations per page.
     * @return A {@link Page} of {@link OrganizationDto}s.
     */
    @Transactional(readOnly = true)
    public Page<OrganizationDto> getAllOrganization(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return repository.findAll(pageable)
                .map(OrganizationDtoConvertor::convertOrganizationToDto);
    }

    /**
     * Retrieves a list of all organizations created by a specific user.
     * @param creatorId The ID of the user who created the organizations.
     * @return A {@link List} of {@link OrganizationDto}s.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizationsByCreatorId(Integer creatorId) {
        return repository.findOrganizationsByCreatorId(creatorId)
                .stream()
                .map(OrganizationDtoConvertor::convertOrganizationToDto)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a single organization by its unique identifier.
     * @param id The ID of the organization to retrieve.
     * @return An {@link OrganizationDto} containing the organization's details.
     * @throws ResourceNotFoundException if no organization with the given ID is found.
     */
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

    /**
     * Partially updates an existing organization. This method applies updates to specific fields
     * of an organization as provided in the updates map.
     * @param updates A {@link Map} where keys are the field names to update and values are the new values.
     * @param id The ID of the organization to update.
     * @return An {@link OrganizationSimpleDto} representing the updated organization.
     * @throws ResourceNotFoundException if no organization with the given ID is found.
     */
    @Transactional
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

    /**
     * Performs a batch update on multiple organizations.
     * @param updates A list of {@link OrganizationPatchDto} objects, each containing the ID of the
     *                organization to update and a map of the updates to apply.
     */
    @Transactional
    public void batchUpdateOrganization(List<OrganizationPatchDto> updates) {
        updates.forEach(update -> partialUpdateAnOrganization(update.getUpdates(),update.getId()));
    }

    /**
     * Deletes an organization from the database.
     * @param id The ID of the organization to delete.
     * @throws ResourceNotFoundException if no organization with the given ID is found.
     */
    @Transactional
    public void deleteAnOrganization(Long id) {
        if(!repository.existsById(id)) {
            throw new ResourceNotFoundException("Organization not found with id: "+id);
        } else {
            repository.deleteById(id);
        }
    }
}
