package org.tornotron.echno_backend.organization;

import jakarta.validation.ConstraintViolationException;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.payload.PartialUpdateKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.exception.SubscriptionAccessDeniedException;
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
 * the organization mapper to map entities to DTOs.
 */
@Slf4j
@Service
public class OrganizationService {

    private static final String ORGANIZATION_FOLDER = "organizations";

    /** The billing feature that covers creating an organization. */
    private static final String CREATE_ORGANIZATION_FEATURE = "CREATE_ORGANIZATION";

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
    private final PayloadValidator payloadValidator;

    /**
     * Constructs an {@code OrganizationService} with the necessary dependencies.
     * @param repository The repository for accessing organization data.
     * @param attachmentService The service for managing attachments.
     * @param fileStorageService The service for file storage operations.
     * @param keycloakGroupService The service for managing Keycloak groups.
     * @param subscriptionService The service for handling subscription billing.
     * @param onboardingSeeder Seeds the per-organization finance defaults for a new organization.
     * @param payloadValidator Runs the create payload's own constraints.
     */
    public OrganizationService(OrganizationRepository repository, AttachmentService attachmentService, FileStorageService fileStorageService, KeycloakGroupService keycloakGroupService, SubscriptionService subscriptionService, UserContextService userContextService, EmployeeService employeeService, org.tornotron.echno_backend.organization.mapper.OrganizationMapper organizationMapper, org.tornotron.echno_backend.common.service.OrganizationSecurityService orgSecurity, OrganizationOnboardingSeeder onboardingSeeder, PayloadValidator payloadValidator) {
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
        this.payloadValidator = payloadValidator;
    }

    /**
     * Applies the CREATE_ORGANIZATION entitlement, exempting the caller's very first organization.
     *
     * <p>Creating an organization is how a new account bootstraps itself: a self-registered user
     * holds no subscription and belongs to nothing, so billing the first creation leaves the
     * account with no action it can take and no way to buy one. The first organization is therefore
     * part of signing up and is not gated; every organization after it goes through the normal
     * feature check.
     *
     * <p>This lives in the service rather than on the controllers because both entry points, web
     * and mobile, create through here, and the fact the rule turns on (whether this user has
     * created an organization before) is only known at this level.
     *
     * <p>The read and the insert it guards run in one transaction against CockroachDB, whose
     * isolation is SERIALIZABLE, so two concurrent first creations by the same user cannot both
     * pass: the second insert invalidates the other's read and that transaction retries, by which
     * point the first organization exists and the check applies normally.
     *
     * <p>Deleting an organization does restore the exemption, and that is intended rather than a
     * gap. Deletion already refunds the CREATE_ORGANIZATION usage it recorded, so a create followed
     * by a delete leaves the account exactly where it started. Making the exemption permanent
     * instead would mean a user who deletes the organization they made by mistake can never create
     * another without buying a plan, which is a worse answer than the one it replaces.
     *
     * @param currentUser The user creating the organization.
     * @throws SubscriptionAccessDeniedException if this is not their first and their plan does not
     *         cover it.
     */
    private void requireCreateOrganizationEntitlement(User currentUser) {
        if (!repository.existsByCreatorId(currentUser.getId().intValue())) {
            log.info("User {} is creating their first organization; no subscription required", currentUser.getId());
            return;
        }

        FeatureAccessResultDto access = subscriptionService.checkFeatureAccess(currentUser.getId(), CREATE_ORGANIZATION_FEATURE);
        if (!access.isAllowed()) {
            String message = access.getMessage() != null ? access.getMessage() : access.getReason();
            throw new SubscriptionAccessDeniedException(message, CREATE_ORGANIZATION_FEATURE, access);
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
        payloadValidator.requireValid(organizationCreationDto);
        if(repository.existsByOrganizationEmail(organizationCreationDto.getOrganizationEmail())){
            throw new DuplicateResourceException("Organization with email '" + organizationCreationDto.getOrganizationEmail() + "' already exists");
        }
        User currentUser = userContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException(
                    "Your account is not provisioned yet, so an organization cannot be created for it");
        }
        requireCreateOrganizationEntitlement(currentUser);
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

        // Count the creation against the user's CREATE_ORGANIZATION quota, after commit for the
        // same reason as the seeding: a usage-recording failure must not lose the organization.
        // The first organization is exempt from the check above but still counted, so the quota
        // stays an honest record of what was created.
        afterCommit(() -> recordOrganizationCreated(currentUser.getId()));

        return organizationMapper.toSimpleDto(savedOrganization);
    }

    /**
     * Records one CREATE_ORGANIZATION usage, swallowing any failure.
     *
     * <p>Usage accounting is bookkeeping, not part of the creation: an organization that exists
     * must not be reported as failed because its meter could not be written.
     */
    private void recordOrganizationCreated(Long userId) {
        try {
            subscriptionService.recordUsage(userId, CREATE_ORGANIZATION_FEATURE, 1L);
        } catch (Exception e) {
            log.error("Failed to record organization creation usage for user {}", userId, e);
        }
    }

    /**
     * Runs the per-org finance seeding after the current transaction commits when one is active, so a
     * seed failure cannot roll back the newly created organization. Falls back to running inline when
     * there is no active transaction (the seeding is idempotent and independently guarded either way).
     */
    private void scheduleFinanceSeeding(Long organizationId) {
        afterCommit(() -> onboardingSeeder.seedFinanceDefaults(organizationId));
    }

    /**
     * Defers work until the current transaction commits, or runs it inline when there is no
     * transaction to wait for (which is the case in unit tests and in any direct call).
     */
    private void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
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
     * @param attachments Files to attach, or null when the request carried no attachments part.
     * @param entityType What the uploaded files are, for example ORGANIZATION_LOGO.
     * @return An {@link OrganizationSimpleDto} representing the updated organization.
     * @throws ResourceNotFoundException if no organization with the given ID is found.
     */
    @Transactional
    public OrganizationSimpleDto partialUpdateAnOrganization(Map<String, Object> updates, Long id,List<MultipartFile> attachments,String entityType) {
       Organization organization = repository.findById(id)
               .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + id + " was not found"));
       partialUpdateAnOrganization(updates,organization);

       // The attachments part is optional, and Spring hands back null rather than an empty list
       // when a multipart request omits it. Every organization edit that does not also replace
       // the logo arrives that way, which is most of them.
       if (attachments != null) {
           for (MultipartFile att : attachments) {
               Attachment attachment = attachmentService.uploadAttachment(att,entityType,id,ORGANIZATION_FOLDER);
               organization.addAttachment(attachment);
           }
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
                default:
                    // Nothing is dropped on purpose here. The one key echno-core sends that this
                    // endpoint has no field for is isActive, behind an "Active Organization"
                    // checkbox that no route can currently honour, and a warning naming it is the
                    // point. See echno-core#57.
                    PartialUpdateKeys.reportUnknown(log, "organization", organization.getId(), key);
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
                subscriptionService.recordUsage(creatorId.longValue(), CREATE_ORGANIZATION_FEATURE, -1L);
            } catch (Exception e) {
                log.warn("Failed to decrement organization usage for user {}", creatorId);
            }
        }
    }
}
