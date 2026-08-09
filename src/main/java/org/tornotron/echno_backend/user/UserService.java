package org.tornotron.echno_backend.user;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.DtoConversions.EmployeeDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.UserDtoConvertor;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;
import org.tornotron.echno_backend.user.dto.UserRegistrationDto;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service class for managing users.
 * Handles business logic related to user creation, retrieval, updates, and deletion.
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final String USERS_FOLDER = "users";
    private static final String ATTACHMENT_TYPE_PROFILE_PICTURE = "USER_PROFILE_PICTURE";
    private static final String ATTACHMENT_TYPE_CV = "USER_CV";

    private final UserRepository userRepository;
    private final Keycloak keycloak;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;

    /**
     * Constructs a UserService with the given UserRepository.
     *
     * @param userRepository The repository for user data access.
     * @param keycloak       The Keycloak client.
     * @param attachmentService The service for attachment operations.
     */
    public UserService(UserRepository userRepository, Keycloak keycloak, AttachmentService attachmentService,FileStorageService fileStorageService) {
        this.userRepository = userRepository;
        this.keycloak = keycloak;
        this.attachmentService = attachmentService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Registers a new user.
     *
     * @param userRegistrationDto DTO containing the details for the new user.
     * @return The DTO of the newly created user.
     */
    @Transactional
    public UserDto registerUser(UserRegistrationDto userRegistrationDto) {
        if (userRepository.existsUserByEmail(userRegistrationDto.getEmail())) {
            throw new DuplicateResourceException("User with email '" + userRegistrationDto.getEmail() + "' already exists");
        }

        String keycloakId = createKeycloakUser(userRegistrationDto.getUserName(), userRegistrationDto.getEmail(), userRegistrationDto.getPassword());

        try {
            User user = new User();
            user.setName(userRegistrationDto.getName());
            user.setPhone(userRegistrationDto.getPhone());
            user.setGender(userRegistrationDto.getGender());
            user.setDateOfBirth(userRegistrationDto.getDateOfBirth());
            user.setEmail(userRegistrationDto.getEmail());
            try {
                user.setRole(UserRole.valueOf(userRegistrationDto.getRole()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("'" + userRegistrationDto.getRole() + "' is not a valid user role");
            }
            user.setKeycloakId(keycloakId);
            return UserDtoConvertor.convertUserToDto(userRepository.save(user), fileStorageService);
        } catch (Exception e) {
            deleteKeycloakUser(keycloakId);
            throw e;
        }
    }

    private String createKeycloakUser(String username, String email, String password) {
        if (keycloakUserExists(email)) {
            throw new DuplicateResourceException("User with email '" + email + "' already exists in Keycloak");
        }

        UsersResource usersResource = keycloak.realm(realm).users();

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(username);
        userRepresentation.setEmail(email);
        userRepresentation.setEnabled(true);
        userRepresentation.setEmailVerified(true);

        String keycloakId;
        try (Response response = usersResource.create(userRepresentation)) {
            if (response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user '" + username + "' in Keycloak (status " + response.getStatus() + ")");
            }
            String location = response.getLocation().toString();
            keycloakId = location.substring(location.lastIndexOf('/') + 1);
        }

        CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
        credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        credentialRepresentation.setValue(password);
        credentialRepresentation.setTemporary(false);

        usersResource.get(keycloakId).resetPassword(credentialRepresentation);

        try {
            var userRole = keycloak.realm(realm).roles().get("user").toRepresentation();
            usersResource.get(keycloakId).roles().realmLevel().add(Collections.singletonList(userRole));
        } catch (Exception e) {
            logger.warn("Failed to assign 'user' realm role to new user {}: {}", keycloakId, e.getMessage());
        }

        return keycloakId;
    }

    private void deleteKeycloakUser(String keycloakId) {
        try {
            if (keycloakId != null) {
                keycloak.realm(realm).users().get(keycloakId).remove();
                logger.info("Rolled back Keycloak user creation for ID: {}", keycloakId);
            }
        } catch (Exception e) {
            logger.error("Failed to rollback Keycloak user creation for ID: {}. Manual intervention required.", keycloakId, e);
        }
    }

    /**
     * Retrieves all organizations associated with a specific user.
     *
     * @param userId The ID of the user.
     * @return A list of organization DTOs.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDto> getOrganizationsForCurrentUser(Long userId) {
        return userRepository.findOrganizationsByUserId(userId)
                .stream()
                .map(org ->
                    OrganizationDtoConvertor.convertOrganizationToDto(org,fileStorageService)
                )
                .collect(Collectors.toList());

    }

    /**
     * Retrieves all employees associated with a user by their Keycloak ID.
     *
     * @param keycloakId The Keycloak ID of the user.
     * @return A list of employee DTOs.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesForUser(String keycloakId) {
        return userRepository.findUserWithEmployeesByKeycloakId(keycloakId)
                .map(user -> user.getEmployees().stream()
                        .map(emp -> EmployeeDtoConvertor.convertEmployeeToDto(emp, fileStorageService))
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * Retrieves the current user by their Keycloak ID with attachments eagerly loaded.
     *
     * @param keycloakId The Keycloak ID of the user.
     * @return The user DTO.
     * @throws ResourceNotFoundException if no user with the given Keycloak ID is found.
     */
    @Transactional(readOnly = true)
    public UserDto getCurrentUserDto(String keycloakId) {
        return userRepository.findUserWithAttachmentsByKeycloakId(keycloakId)
                .map(user -> UserDtoConvertor.convertUserToDto(user, fileStorageService))
                .orElseThrow(() -> new ResourceNotFoundException("User with Keycloak ID '" + keycloakId + "' was not found"));
    }

    /**
     * Retrieves a paginated list of all users.
     *
     * @param pageNo   The page number to retrieve.
     * @param pageSize The number of users per page.
     * @return A {@link Page} of user DTOs.
     */
    @Transactional(readOnly = true)
    public Page<UserDto> getAllUsers(int pageNo, int pageSize) {
        // Scope the directory to the caller's tenant. User is not a tenant-scoped
        // entity, so the Hibernate org filter does not apply here; without this a
        // findAll returns every tenant's users. Fail closed if there is no org
        // context (the controller guard should already prevent that).
        Long organizationId = TenantContext.getCurrentOrgId();
        if (organizationId == null) {
            throw new AccessDeniedException("No organization context; cannot list users.");
        }
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return userRepository.findUsersByOrganizationId(organizationId, pageable)
                .map(usr -> UserDtoConvertor.convertUserToDto(usr,fileStorageService));
    }

    /**
     * Retrieves a single user by their ID.
     *
     * @return The user DTO.
     * @throws ResourceNotFoundException if no user with the given ID is found.
     */
    @Transactional(readOnly = true)
    public UserDto getAnUser(String sub) {
        return userRepository.findUserByKeycloakId(sub)
                .map(usr -> UserDtoConvertor.convertUserToDto(usr,fileStorageService))
                .orElseThrow(() -> new ResourceNotFoundException("User with subject identifier '" + sub + "' was not found"));
    }

    /**
     * Partially updates an existing user (without file uploads).
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the user to update.
     * @return The DTO of the updated user.
     * @throws ResourceNotFoundException if no user with the given ID is found.
     */
    @Transactional
    public UserDto partialUpdateAnUser(Map<String, Object> updates, Long id) {
        return partialUpdateAnUser(updates, id, null, null);
    }

    /**
     * Partially updates an existing user with optional file uploads.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the user to update.
     * @param profilePicture Optional profile picture file.
     * @param cv Optional CV/resume file.
     * @return The DTO of the updated user.
     * @throws ResourceNotFoundException if no user with the given ID is found.
     */
    @Transactional
    public UserDto partialUpdateAnUser(Map<String, Object> updates, Long id, MultipartFile profilePicture, MultipartFile cv) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User with ID " + id + " was not found"));
        applyUpdates(updates, user);

        handleProfilePictureUpload(user, profilePicture, id);
        handleCvUpload(user, cv, id);

        return UserDtoConvertor.convertUserToDto(userRepository.save(user), fileStorageService);
    }

    private void applyUpdates(Map<String, Object> updates, User user) {
        updates.forEach((key, value) -> {
            switch (key) {
                case "name" -> user.setName((String) value);
                case "defaultOrganizationId" -> user.setDefaultOrganizationId(parseDefaultOrganizationId(value));
                case "gender" -> user.setGender((String) value);
                case "bloodGroup" -> user.setBloodGroup((String) value);
                case "email" -> user.setEmail((String) value);
                case "phone" -> user.setPhone((String) value);
                case "dateOfBirth" -> user.setDateOfBirth(parseDateOfBirth(value));
                case "qualification" -> user.setQualification((String) value);
                case "address" -> user.setAddress((String) value);
                case "experience" -> user.setExperience((Integer) value);
                case "cvUrl" -> user.setCvUrl((String) value);
                case "emergencyContact" -> user.setEmergencyContact((String) value);
                case "role" -> user.setRole(UserRole.valueOf((String) value));
                case "profilePictureUrl" -> user.setProfilePictureUrl((String) value);
                case "skills" -> user.setSkills(parseSkills(value));
                case "certifications" -> user.setCertifications(parseCertifications(value));
                default -> { }
            }
        });
    }

    private Long parseDefaultOrganizationId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("defaultOrganizationId must be a number");
    }

    private LocalDateTime parseDateOfBirth(Object value) {
        if (value instanceof String) {
            return LocalDateTime.parse((String) value);
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return null;
    }

    private List<String> parseSkills(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> rawList)) {
            throw new IllegalArgumentException("Skills must be a list");
        }
        return rawList.stream()
                .map(this::validateSkill)
                .toList();
    }

    private String validateSkill(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Skill cannot be null");
        }
        if (!(item instanceof String)) {
            throw new IllegalArgumentException("Each skill must be a string");
        }
        String skill = ((String) item).trim();
        if (skill.isBlank()) {
            throw new IllegalArgumentException("Skill cannot be blank");
        }
        if (skill.length() > 100) {
            throw new IllegalArgumentException("Skill cannot exceed 100 characters");
        }
        return skill;
    }

    private List<String> parseCertifications(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> rawList)) {
            throw new IllegalArgumentException("Certification must be a list");
        }
        return rawList.stream()
                .map(this::validateCertification)
                .toList();
    }

    private String validateCertification(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Certification cannot be null");
        }
        if (!(item instanceof String)) {
            throw new IllegalArgumentException("Each certification must be a string");
        }
        String certification = ((String) item).trim();
        if (certification.isBlank()) {
            throw new IllegalArgumentException("Certification cannot be blank");
        }
        if (certification.length() > 100) {
            throw new IllegalArgumentException("Certification cannot exceed 100 characters");
        }
        return certification;
    }

    private void handleProfilePictureUpload(User user, MultipartFile profilePicture, Long userId) {
        if (profilePicture != null && !profilePicture.isEmpty()) {
            Attachment attachment = attachmentService.uploadAttachment(
                    profilePicture, ATTACHMENT_TYPE_PROFILE_PICTURE, userId, USERS_FOLDER);
            user.setProfilePictureUrl(attachment.getUrl());
            user.addAttachment(attachment);
        }
    }

    private void handleCvUpload(User user, MultipartFile cv, Long userId) {
        if (cv != null && !cv.isEmpty()) {
            Attachment attachment = attachmentService.uploadAttachment(
                    cv, ATTACHMENT_TYPE_CV, userId, USERS_FOLDER);
            user.setCvUrl(attachment.getUrl());
            user.addAttachment(attachment);
        }
    }

    /**
     * Updates multiple users in a batch.
     *
     * @param updates A list of DTOs containing the updates for each user.
     */
    @Transactional
    public void batchUpdateUser(List<UserPatchDto> updates) {
        List<Long> userIds = updates.stream().map(UserPatchDto::getId).collect(Collectors.toList());
        List<User> users = userRepository.findAllById(userIds);

        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));

        updates.forEach(update -> {
            User user = userMap.get(update.getId());
            if (user != null) {
                applyUpdates(update.getUpdates(), user);
            }
        });

        userRepository.saveAll(users);
    }

    /**
     * Deletes a user by their ID.
     *
     * @param id The ID of the user to delete.
     * @throws ResourceNotFoundException if no user with the given ID is found.
     */
    @Transactional
    public void deleteAnUser(Long id) {
        if(!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User with ID " + id + " was not found");
        } else {
            userRepository.deleteById(id);
        }
    }

    private boolean keycloakUserExists(String email) {
        try {
            UsersResource usersResource = keycloak.realm(realm).users();
            List<UserRepresentation> users = usersResource.search(email, true);
            return !users.isEmpty();
        } catch (Exception ex) {
            throw new DatabaseOperationException("Failed to check whether user with email '" + email + "' exists in Keycloak: " + ex.getMessage());
        }
    }

}