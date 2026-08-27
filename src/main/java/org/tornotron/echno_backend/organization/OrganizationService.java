package org.tornotron.echno_backend.organization;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service layer for managing organizations. This class encapsulates the business logic
 * for creating, retrieving, updating, and deleting organizations. It interacts with the
 * {@link OrganizationRepository} to perform database operations and uses
 * the organization mapper to map entities to DTOs.
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
    private final org.tornotron.echno_backend.organization.mapper.OrganizationMapper organizationMapper;
    private final org.tornotron.echno_backend.common.service.OrganizationSecurityService orgSecurity;
    private final OrganizationOnboardingSeeder onboardingSeeder;
    private final Validator validator;

    /**
     * Constructs an {@code OrganizationService} with the necessary dependencies.
     * @param repository The repository for accessing organization data.
     * @param attachmentService The service for managing attachments.
     * @param fileStorageService The service for file storage operations.
     * @param keycloakGroupService The service for managing Keycloak groups.
     * @param subscriptionService The service for handling subscription billing.
     * @param onboardingSeeder Seeds the per-organization finance defaults for a new organization.
     * @param validator Bean validator applied to the create payload.
     */
    public OrganizationService(OrganizationRepository repository, AttachmentService attachmentService, FileStorageService fileStorageService, KeycloakGroupService keycloakGroupService, SubscriptionService subscriptionService, UserContextService userContextService, EmployeeService employeeService, org.tornotron.echno_backend.organization.mapper.OrganizationMapper organizationMapper, org.tornotron.echno_backend.common.service.OrganizationSecurityService orgSecurity, OrganizationOnboardingSeeder onboardingSeeder, Validator validator) {
        this.repository = repository;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
        this.keycloakGroupService = keycloakGroupService;
        this.subscriptionService = subscriptionService;
        this.userContextService = userContextService;
        this.employeeService = employeeService;
        this.organizationMapper = organizationMapper;
        this.orgSecurity = orgSecurity;
        this.onboardingSeeder = onboardingSeeder;
        this.validator = validator;
    }

    /**
     * Runs bean validation over the create payload.
     *
     * <p>Both organization controllers take the payload as the JSON string part of a multipart
     * request and deserialize it by hand, so Spring never binds a bean and never validates one,
     * and the constraints on {@link OrganizationCreationDto} would otherwise never fire. Doing it
     * here covers both entry points at once, and covers any later caller by construction.
     *
     * @param organizationCreationDto The payload as deserialized from the request.
     * @throws ConstraintViolationException if any constraint on the payload fails.
     */
    private void requireValid(OrganizationCreationDto organizationCreationDto) {
        Set<ConstraintViolation<OrganizationCreationDto>> violations =
                validator.validate(organizationCreationDto);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /**
     * Creates and persists a new organization based on the provided data.
     * @param organizationCreationDto A DTO containing the details for the new organization.
     * @return An {@link OrganizationSimpleDto} representing the newly created organization.
     * @throws ConstraintViolationException if the payload fails its own constraints.
     */
    @Transactional
    public OrganizationSimpleDto addOrganization(OrganizationCreationDto organizationCreationDto, List<MultipartFile> attachments) {
        requireValid(organizationCreationDto);
        if(repository.existsByOrganizationEmail(organizationCreationDto.getOrganizationEmail())){
            throw new DuplicateResourceException("Organization with email '" + organizationCreationDto.getOrganizationEmail() + "' already exists");
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

        // Make the creator the system-admin of the organization they just created. This is the
        // linchpin of self-service onboarding: assignOrgRole adds them to the '/org-{id}/system-admin'
        // Keycloak subgroup (idempotent), so their next token carries ORG_{id}_ROLE_system-admin and
        // they can administer their own org. It is critical: assignOrgRole rethrows on a Keycloak
        // failure, which rolls this creation back so the caller sees the error rather than an
        // org whose creator cannot manage it.
        employeeService.assignOrgRole(employeeDto.getId(), OrgRole.SYSTEM_ADMIN);

        if (attachments != null && !attachments.isEmpty()) {
            attachmentService.uploadAttachments(attachments, "ORGANIZATION", savedOrganization.getId(), ORGANIZATION_FOLDER);
        }

        // Seed the org's finance defaults (chart of accounts, cost categories, finance settings)
        // AFTER this creating transaction commits, so a seed failure is logged and skipped rather
        // than rolling back the created org. Running them post-commit is also what lets each
        // idempotent seeder see the committed org and run in its own transaction.
        scheduleFinanceSeeding(savedOrganization.getId());

        return organizationMapper.toSimpleDto(savedOrganization);
    }

    /**
     * Runs the per-org finance seeding after the current transaction commits when one is active, so a
     * seed failure cannot roll back the newly created organization. Falls back to running inline when
     * there is no active transaction (the seeding is idempotent and independently guarded either way).
     */
    private void scheduleFinanceSeeding(Long organizationId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            onboardingSeeder.seedFinanceDefaults(organizationId);
                        }
                    });
        } else {
            onboardingSeeder.seedFinanceDefaults(organizationId);
        }
    }

    /**
     * Retrieves the organizations the currently authenticated user belongs to.
     *
     * <p>This method is the row filter for the organization picker, not the endpoint guard.
     * It resolves the caller from the security context and returns only organizations that
     * have an employee record for that user, so the caller's identity decides the result set
     * and a relaxed guard cannot widen it.
     *
     * @return A {@link List} of {@link OrganizationDto}s the caller is a member of, empty if none.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganization() {
        User user = userContextService.getCurrentUser();
        if (user == null) {
            // Authenticated against Keycloak but with no local user record yet, which happens
            // between first login and the user being provisioned. They belong to nothing yet.
            return List.of();
        }
        return repository.findAllByUserEmail(user.getEmail()).stream()
                .map(org -> organizationMapper.toDto(org))
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
                .map(org -> organizationMapper.toDto(org))
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
                .map(org -> organizationMapper.toDto(org))
                .orElse(null);
        if(organizationDto == null) {
            throw new ResourceNotFoundException("Organization with ID " + id + " was not found");
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
               .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + id + " was not found"));
       partialUpdateAnOrganization(updates,organization);

       for (MultipartFile att:attachments) {
           Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,ORGANIZATION_FOLDER);
           organization.addAttachment(attachment);
       }
        return organizationMapper.toSimpleDto(repository.save(organization));
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
        // Organization is the tenant root, not a TenantScopedEntity, so neither the
        // org filter nor the fail-closed load listener guards it. Authorize each id
        // here: the caller must be a member of that organization (or a platform
        // admin), mirroring the single-organization DELETE endpoint. Without this a
        // member of one organization could patch another organization by id.
        updates.forEach(update -> {
            if (!orgSecurity.isMemberOrAdmin(update.getId())) {
                throw new org.tornotron.echno_backend.common.exception.TenantAccessDeniedException(
                        "Not permitted to update organization " + update.getId());
            }
        });

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
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + id + " was not found"));

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
