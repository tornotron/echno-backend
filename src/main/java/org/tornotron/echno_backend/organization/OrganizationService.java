package org.tornotron.echno_backend.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;
import org.tornotron.echno_backend.billing.services.SubscriptionService;

import lombok.extern.slf4j.Slf4j;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserContextService;

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
@Slf4j
@Service
public class OrganizationService {

    private static final String ORGANIZATION_FOLDER = "organizations";

    private final OrganizationRepository repository;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;
    private final KeycloakGroupService keycloakGroupService;
    private final SubscriptionService subscriptionService;
    private final UserContextService userContextService;
    private final EmployeeService employeeService;

    /**
     * Constructs an {@code OrganizationService} with the necessary dependencies.
     * @param repository The repository for accessing organization data.
     * @param attachmentService The service for managing attachments.
     * @param fileStorageService The service for file storage operations.
     * @param keycloakGroupService The service for managing Keycloak groups.
     * @param subscriptionService The service for handling subscription billing.
     */
    public OrganizationService(OrganizationRepository repository, AttachmentService attachmentService, FileStorageService fileStorageService, KeycloakGroupService keycloakGroupService, SubscriptionService subscriptionService, UserContextService userContextService, EmployeeService employeeService) {
        this.repository = repository;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
        this.keycloakGroupService = keycloakGroupService;
        this.subscriptionService = subscriptionService;
        this.userContextService = userContextService;
        this.employeeService = employeeService;
    }

    /**
     * Creates and persists a new organization based on the provided data.
     * @param organizationCreationDto A DTO containing the details for the new organization.
     * @return An {@link OrganizationSimpleDto} representing the newly created organization.
     */
    @Transactional
    public OrganizationSimpleDto addOrganization(OrganizationCreationDto organizationCreationDto, List<MultipartFile> attachments) {
        if(repository.existsByOrganizationEmail(organizationCreationDto.getOrganizationEmail())){
            throw new DuplicateResourceException("Organization already exists");
        }
        User currentUser = userContextService.getCurrentUser();
        Organization organization = new Organization();
        organization.setOrganizationName(organizationCreationDto.getOrganizationName());
        organization.setOrganizationAddress(organizationCreationDto.getOrganizationAddress());
        organization.setCreatedAt(LocalDateTime.now());
        organization.setOrganizationEmail(organizationCreationDto.getOrganizationEmail());
        organization.setOrganizationPhone(organizationCreationDto.getOrganizationPhone());
        organization.setOrganizationWebsite(organizationCreationDto.getOrganizationWebsite());
        organization.setOrganizationLogo(organizationCreationDto.getOrganizationLogo());
        organization.setCreatorId(currentUser.getId().intValue());
        organization.setIsActive(true);
        Organization savedOrganization = repository.save(organization);

        // Create Keycloak group BEFORE joining the organization
        keycloakGroupService.createOrganizationGroup(
                savedOrganization.getId().toString(),
                savedOrganization.getOrganizationName()
        );

        EmployeeJoinOrgDto joinOrgDto = new EmployeeJoinOrgDto();
        EmployeeDto employeeDto = employeeService.joinOrganization(currentUser.getId(), savedOrganization.getId(), joinOrgDto);
        TenantContext.setCurrentOrgId(savedOrganization.getId());
        employeeService.assignOrgRole(employeeDto.getId(), OrgRole.SYSTEM_ADMIN);

        if (attachments != null && !attachments.isEmpty()) {
            attachmentService.uploadAttachments(attachments, "ORGANIZATION", savedOrganization.getId(), ORGANIZATION_FOLDER);
        }

        return OrganizationDtoConvertor.convertOrganizationToSimpleDto(savedOrganization);
    }

    /**
     * Retrieves a paginated list of all organizations, sorted by their ID in ascending order.
     * @return A {@link Page} of {@link OrganizationDto}s.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganization() {
        User user = userContextService.getCurrentUser();
        return repository.findAllByUserEmail(user.getEmail()).stream()
                .map(org -> OrganizationDtoConvertor.convertOrganizationToDto(org, fileStorageService))
                .collect(Collectors.toList());
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
                .map(org -> OrganizationDtoConvertor.convertOrganizationToDto(org, fileStorageService))
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
                .map(org -> OrganizationDtoConvertor.convertOrganizationToDto(org, fileStorageService))
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
    public OrganizationSimpleDto partialUpdateAnOrganization(Map<String, Object> updates, Long id,List<MultipartFile> attachments,String entityType) {
       Organization organization = repository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: "+id));
       partialUpdateAnOrganization(updates,organization);

       for (MultipartFile att:attachments) {
           Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,ORGANIZATION_FOLDER);
           organization.addAttachment(attachment);
       }
        return OrganizationDtoConvertor.convertOrganizationToSimpleDto(repository.save(organization));
    }

    private void partialUpdateAnOrganization(Map<String, Object> updates, Organization organization) {
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
    }

    /**
     * Performs a batch update on multiple organizations.
     * @param updates A list of {@link OrganizationPatchDto} objects, each containing the ID of the
     *                organization to update and a map of the updates to apply.
     */
    @Transactional
    public void batchUpdateOrganization(List<OrganizationPatchDto> updates) {
        List<Long> organizationIds = updates.stream().map(OrganizationPatchDto::getId).collect(Collectors.toList());
        List<Organization> organizations = repository.findAllById(organizationIds);

        Map<Long, Organization> organizationMap = organizations.stream().collect(Collectors.toMap(Organization::getId, organization -> organization));

        updates.forEach(update -> {
            Organization organization = organizationMap.get(update.getId());
            if (organization != null) {
                partialUpdateAnOrganization(update.getUpdates(), organization);
            }
        });

        repository.saveAll(organizations);
    }

    /**
     * Deletes an organization from the database.
     * @param id The ID of the organization to delete.
     * @throws ResourceNotFoundException if no organization with the given ID is found.
     */
    @Transactional
    public void deleteAnOrganization(Long id) {
        Organization organization = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found with id: "+id));

        try {
            keycloakGroupService.deleteOrganizationGroup(id.toString());
        } catch (Exception e) {
            log.error("Failed to delete Keycloak group for organization {}: {}",
                    id, e.getMessage());
        }

        Integer creatorId = organization.getCreatorId();
        repository.deleteById(id);

        if (creatorId != null) {
            try {
                subscriptionService.recordUsage(creatorId.longValue(), "CREATE_ORGANIZATION", -1L);
            } catch (Exception e) {
                log.warn("Failed to decrement organization usage for user {}", creatorId);
            }
        }
    }
}
