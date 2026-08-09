package org.tornotron.echno_backend.user;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing users.
 * Provides endpoints for creating, reading, updating, and deleting users.
 */
@RestController
@RequestMapping("/api/v1/user")
@Validated
public class UserController {

    private final UserService userService;

    /**
     * Constructs a UserController with the given UserService.
     *
     * @param userService The service for handling user-related business logic.
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }


    /**
     * Retrieves a paginated list of all users.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of users per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of user DTOs and HTTP status 200 (OK).
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'hr-admin')")
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
    @PreAuthorize("@orgSecurity.isSelfUser(#userId) or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/{userId}/organizations")
    public ResponseEntity<List<OrganizationDto>> readAllOrganizationsForCurrentUser(@PathVariable Long userId) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.getOrganizationsForCurrentUser(userId));
    }

    /**
     * Retrieves the currently authenticated user (resolved from the token subject).
     *
     * @return A {@link ResponseEntity} containing the user DTO and HTTP status 200 (OK).
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<UserDto> readAnUser(JwtAuthenticationToken authenticationToken) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.getAnUser(authenticationToken.getToken().getClaimAsString("sub")));
    }

    /**
     * Partially updates an existing user. A user may update their own record; org admins may update any.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the user to update.
     * @return A {@link ResponseEntity} with the updated user's DTO and HTTP status 200 (OK).
     */
    @PreAuthorize("@orgSecurity.isSelfUser(#id) or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PatchMapping("{id}")
    public ResponseEntity<UserDto> partialUpdateAUser(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.partialUpdateAnUser(updates, id));
    }

    /**
     * Updates multiple users in a batch.
     *
     * @param updates A list of DTOs containing the updates for each user.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
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
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteAnUser(@PathVariable Long id) {
        userService.deleteAnUser(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User with ID " + id + " deleted successfully"));
    }

}
