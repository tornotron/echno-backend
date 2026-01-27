package org.tornotron.echno_backend.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.entity.AttachmentDto;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user/web")
@Validated
public class UserControllerWeb {

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final AttachmentService attachmentService;

    /**
     * Constructs a UserController with the given UserService.
     *
     * @param userService The service for handling user-related business logic.
     * @param objectMapper The ObjectMapper for JSON processing.
     */
    public UserControllerWeb(UserService userService, ObjectMapper objectMapper,AttachmentService
                             attachmentService) {
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.attachmentService = attachmentService;
    }

    /**
     * Creates a new user.
     *
     * @param userCreationDto DTO containing the details for the new user.
     * @return A {@link ResponseEntity} with the created user's DTO and HTTP status 201 (Created).
     */
//    @PostMapping
//    public ResponseEntity<UserDto> createUser(@RequestBody @Valid UserCreationDto userCreationDto) {
//        return ResponseEntity.status(HttpStatus.CREATED).body(userService.addUser(userCreationDto));
//    }

    /**
     * Retrieves a paginated list of all users.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of users per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of user DTOs and HTTP status 200 (OK).
     */
    @GetMapping("/all")
    public ResponseEntity<List<UserDto>> readAllUsers(@RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<UserDto> users = userService.getAllUsers(pageNo,pageSize);
        return new ResponseEntity<>(users.getContent(), HttpStatus.OK);
    }

    /**
     * Retrieves all organizations associated with a specific user.
     *
     * @param userId The ID of the user.
     * @return A {@link ResponseEntity} containing a list of organization DTOs and HTTP status 200 (OK).
     */
    @GetMapping("/{userId}/organizations")
    public ResponseEntity<List<OrganizationDto>> readAllOrganizationsForCurrentUser(@PathVariable Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.getOrganizationsForCurrentUser(userId));
    }

    /**
     * Retrieves a single user by their ID.
     *
     * @return A {@link ResponseEntity} containing the user DTO and HTTP status 200 (OK).
     */
    @GetMapping
    public ResponseEntity<UserDto> readAnUser(JwtAuthenticationToken authenticationToken) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.getAnUser(authenticationToken.getToken().getClaimAsString("sub")));
    }

    /**
     * Partially updates an existing user.
     *
     * @param data    JSON string containing the fields to update.
     * @param id      The ID of the user to update.
     * @param profilePicture Optional profile picture file.
     * @param cv      Optional CV/resume file.
     * @return A {@link ResponseEntity} with the updated user's DTO and HTTP status 200 (OK).
     */
    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDto> partialUpdateAUser(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "profilePicture", required = false) MultipartFile profilePicture,
            @RequestParam(value = "cv", required = false) MultipartFile cv) throws JsonProcessingException {
        Map<String, Object> updates = data != null
                ? objectMapper.readValue(data, new TypeReference<>() {
        })
                : Map.of();
        return ResponseEntity.status(HttpStatus.OK).body(userService.partialUpdateAnUser(updates, id, profilePicture, cv));
    }

    @GetMapping("/userId/{userId}/attachmentType/{attachmentType}")
    public ResponseEntity<List<AttachmentDto>> readAttachments(@PathVariable String attachmentType,@PathVariable Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(attachmentService.getAttachments(attachmentType,userId));
    }

    @DeleteMapping("/attachmentId/{attachmentId}")
    public ResponseEntity<ApiResponse> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Attachment deleted"));
    }

    /**
     * Updates multiple users in a batch.
     *
     * @param updates A list of DTOs containing the updates for each user.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse> batchUpdateUsers(@Valid @RequestBody List<UserPatchDto> updates) {
        userService.batchUpdateUser(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

    /**
     * Deletes a user by their ID.
     *
     * @param id The ID of the user to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteAnUser(@PathVariable Long id) {
        userService.deleteAnUser(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User with ID " + id + " deleted successfully"));
    }

}