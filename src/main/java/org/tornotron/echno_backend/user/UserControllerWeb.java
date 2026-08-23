package org.tornotron.echno_backend.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user/web")
@Validated
@Tag(
        name = "Users",
        description = "Web-client twin of the user endpoints. Adds the current-user shortcut, the "
                + "employee memberships of the caller, and a multipart profile-update path for the "
                + "profile picture and CV, alongside the same listing, batch update and delete "
                + "operations as the base user API."
)
public class UserControllerWeb {

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final UserContextService userContextService;

    /**
     * Constructs a UserController with the given UserService.
     *
     * @param userService The service for handling user-related business logic.
     * @param objectMapper The ObjectMapper for JSON processing.
     */
    public UserControllerWeb(UserService userService, ObjectMapper objectMapper, UserContextService userContextService) {
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.userContextService = userContextService;
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
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
    @GetMapping("/all")
    @Operation(
            summary = "List users",
            description = "Returns a single page of user accounts. The pageNo and pageSize parameters "
                    + "control paging; only the page content is returned, without paging metadata."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of users returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
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
    @PreAuthorize("@orgSecurity.isSelfUser(#userId) or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/{userId}/organizations")
    @Operation(
            summary = "List a user's organizations",
            description = "Returns every organization the given user belongs to. Callable by the user "
                    + "themselves or by a system admin."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organizations returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the user nor a system admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
    public ResponseEntity<List<OrganizationDto>> readAllOrganizationsForCurrentUser(@PathVariable Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.getOrganizationsForCurrentUser(userId));
    }

    /**
     * Retrieves a single user by their ID.
     *
     * @return A {@link ResponseEntity} containing the user DTO and HTTP status 200 (OK).
     */
//    @GetMapping
//    public ResponseEntity<UserDto> readAnUser(JwtAuthenticationToken authenticationToken) {
//        return ResponseEntity.status(HttpStatus.OK).body(userService.getAnUser(authenticationToken.getToken().getClaimAsString("sub")));
//    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    @Operation(
            summary = "Get the current user",
            description = "Resolves the caller's user record from their Keycloak identity."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Caller is not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user matches the caller's Keycloak identity")
    })
    public ResponseEntity<UserDto> getCurrentUser() {
        String keycloakId = userContextService.getCurrentKeycloakId();
        return ResponseEntity.ok(userService.getCurrentUserDto(keycloakId));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/employees")
    @Operation(
            summary = "List the current user's employee records",
            description = "Returns every employee record linked to the caller's user account, across "
                    + "the organizations they belong to."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Employee records returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    })
    public ResponseEntity<List<EmployeeDto>> getEmployeesForCurrentUser() {
        String keycloakId = userContextService.getCurrentKeycloakId();
        List<EmployeeDto> employees = userService.getEmployeesForUser(keycloakId);
        return ResponseEntity.ok(employees);
    }

    /**
     * Partially updates an existing user. A user may update their own record; org admins may update any.
     *
     * @param data    JSON string containing the fields to update.
     * @param id      The ID of the user to update.
     * @param profilePicture Optional profile picture file.
     * @param cv      Optional CV/resume file.
     * @return A {@link ResponseEntity} with the updated user's DTO and HTTP status 200 (OK).
     */
    @PreAuthorize("@orgSecurity.isSelfUser(#id) or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PatchMapping(value = "{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Partially update a user",
            description = "Applies field updates from a multipart request. The data part carries the "
                    + "changed fields as JSON, and the optional profilePicture and cv parts replace those "
                    + "files. Callable by the user themselves or by a system admin."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the user nor a system admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
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
    /**
     * Updates multiple users in a batch.
     *
     * @param updates A list of DTOs containing the updates for each user.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PatchMapping("/batch")
    @Operation(
            summary = "Batch update users",
            description = "Applies partial updates to several users in one call. Each entry names a user "
                    + "id and the map of fields to change on that user."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
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
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @DeleteMapping("{id}")
    @Operation(
            summary = "Delete a user",
            description = "Deletes the user account with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
    public ResponseEntity<ApiResponse> deleteAnUser(@PathVariable Long id) {
        userService.deleteAnUser(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User with ID " + id + " deleted successfully"));
    }

}
