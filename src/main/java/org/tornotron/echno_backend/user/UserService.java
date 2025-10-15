package org.tornotron.echno_backend.user;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.OrganizationDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.UserDtoConvertor;
import org.tornotron.echno_backend.common.configuration.KeycloakInitializer;
import org.tornotron.echno_backend.common.exception.DatabaseOperationException;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;
import org.tornotron.echno_backend.user.dto.UserRegistrationDto;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service class for managing users.
 * Handles business logic related to user creation, retrieval, updates, and deletion.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final Keycloak keycloak;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;



    /**
     * Constructs a UserService with the given UserRepository.
     *
     * @param userRepository The repository for user data access.
     */
    public UserService(UserRepository userRepository, Keycloak keycloak) {
        this.userRepository = userRepository;
        this.keycloak = keycloak;
    }


    /**
     * Creates a new user.
     *
     * @param userCreationDto DTO containing the details for the new user.
     * @return The DTO of the newly created user.
     */
    @Transactional
    public UserDto addUser(UserCreationDto userCreationDto) {

        UsersResource usersResource = keycloak.realm(realm).users();

        String keycloakId;

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(userCreationDto.getName());
        userRepresentation.setEmail(userCreationDto.getEmail());
        userRepresentation.setEnabled(true);

        try(Response response = usersResource.create(userRepresentation)) {
            if(response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user, status: "+response.getStatus());
            }
            if(response.getStatus() == 409) {
                throw new RuntimeException("Keycloak User already exists");
            }
            String location = response.getLocation().toString();
            keycloakId = location.substring(location.lastIndexOf('/')+1);
        }

        CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
        credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        credentialRepresentation.setValue(userCreationDto.getPassword());
        credentialRepresentation.setTemporary(false);

        usersResource.get(keycloakId).resetPassword(credentialRepresentation);



        User user = new User();
        user.setName(userCreationDto.getName());
        user.setGender(userCreationDto.getGender());
        user.setAddress(userCreationDto.getAddress());
        user.setBloodGroup(userCreationDto.getBloodGroup());
        user.setEmail(userCreationDto.getEmail());
        user.setPhone(userCreationDto.getPhone());
        user.setDateOfBirth(userCreationDto.getDateOfBirth());
        user.setQualification(userCreationDto.getQualification());
        user.setSkills(userCreationDto.getSkills());
        user.setExperience(userCreationDto.getExperience());
        user.setCvUrl(userCreationDto.getCvUrl());
        user.setEmergencyContact(userCreationDto.getEmergencyContact());
        user.setRole(UserRole.valueOf(userCreationDto.getRole()));
        user.setProfilePictureUrl(userCreationDto.getProfilePictureUrl());
        user.setKeycloakId(keycloakId);
        return UserDtoConvertor.convertUserToDto(userRepository.save(user));
    }

    /**
     * Registers a new user.
     *
     * @param userRegistrationDto DTO containing the details for the new user.
     * @return The DTO of the newly created user.
     */
    @Transactional
    public UserDto registerUser(UserRegistrationDto userRegistrationDto) {
        UsersResource usersResource = keycloak.realm(realm).users();

        String keycloakId;

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(userRegistrationDto.getName());
        userRepresentation.setEmail(userRegistrationDto.getEmail());
        userRepresentation.setEnabled(true);
        userRepresentation.setEmailVerified(true);

        if (userRepository.existsUserByEmail(userRegistrationDto.getEmail()) || keycloakUserExists(userRegistrationDto.getEmail())) {
            throw new DuplicateResourceException();
        }

        try(Response response = usersResource.create(userRepresentation)) {
            if(response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user, status: "+response.getStatus());
            }
            String location = response.getLocation().toString();
            keycloakId = location.substring(location.lastIndexOf('/')+1);
        }

        CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
        credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        credentialRepresentation.setValue(userRegistrationDto.getPassword());
        credentialRepresentation.setTemporary(false);

        usersResource.get(keycloakId).resetPassword(credentialRepresentation);
        // usersResource.get(keycloakId).sendVerifyEmail();

        User user = new User();
        user.setName(userRegistrationDto.getName());
        user.setEmail(userRegistrationDto.getEmail());
        user.setRole(UserRole.client);
        user.setKeycloakId(keycloakId);
        return UserDtoConvertor.convertUserToDto(userRepository.save(user));
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
                .map(OrganizationDtoConvertor::convertOrganizationToDto)
                .collect(Collectors.toList());

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
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return userRepository.findAll(pageable)
                .map(UserDtoConvertor::convertUserToDto);
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
                .map(UserDtoConvertor::convertUserToDto)
                .orElseThrow(() -> new ResourceNotFoundException("No user found for the provided subject identifier"));
    }

    /**
     * Partially updates an existing user.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the user to update.
     * @return The DTO of the updated user.
     * @throws ResourceNotFoundException if no user with the given ID is found.
     */
    @Transactional
    public UserDto partialUpdateAnUser(Map<String, Object> updates, Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        updates.forEach((key, value) -> {
            switch (key) {
                case "name":
                    user.setName((String) value);
                    break;
                case "gender":
                    user.setGender((String) value);
                    break;
                case "bloodGroup":
                    user.setBloodGroup((String) value);
                    break;
                case "email":
                    user.setEmail((String) value);
                    break;
                case "phone":
                    user.setPhone((String) value);
                    break;
                case "dateOfBirth":
                    user.setDateOfBirth((LocalDateTime) value);
                    break;
                case "qualification":
                    user.setQualification((String) value);
                    break;
//                case "skills":
//                    user.setSkills((List<String>) value);
//                    break;
                case "experience":
                    user.setExperience((Integer) value);
                    break;
                case "cvUrl":
                    user.setCvUrl((String) value);
                    break;
                case "emergencyContact":
                    user.setEmergencyContact((String) value);
                    break;
                case "role":
                    user.setRole(UserRole.valueOf((String) value));
                    break;
                case "profilePictureUrl":
                    user.setProfilePictureUrl((String) value);
                    break;
            }
        });
        return UserDtoConvertor.convertUserToDto(userRepository.save(user));
    }

    /**
     * Updates multiple users in a batch.
     *
     * @param updates A list of DTOs containing the updates for each user.
     */
    @Transactional
    public void batchUpdateUser(List<UserPatchDto> updates) {
        updates.forEach(update -> partialUpdateAnUser(update.getUpdates(), update.getId()));
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
            throw new ResourceNotFoundException("User not found with id: " + id);
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
            throw new DatabaseOperationException("Keycloak database user check failed");
        }
    }

}