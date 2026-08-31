package org.tornotron.echno_backend.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;

import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.user.dto.UserUpdateFieldsDto;

/**
 * REST controller for managing users.
 * Provides endpoints for creating, reading, updating, and deleting users.
 */
@RestController
@RequestMapping("/api/v1/user")
@Validated
@Tag(
        name = "Users",
        description = "The platform account behind an employee, keyed to a Keycloak identity. Endpoints "
                + "cover reading the caller's own profile, listing users and their organizations, "
                + "batch and partial updates, and deletion. Most operations are restricted to a "
                + "system or HR admin, except reads of one's own record."
)
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
     * @param pageQuery Page index and page size, bounded by {@link PageQuery}.
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
    public ResponseEntity<List<UserDto>> readAllUsers(@Valid @ParameterObject PageQuery pageQuery) {
        Page<UserDto> users = userService.getAllUsers(pageQuery.getPageNo(),pageQuery.getPageSize());
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
     * Retrieves the currently authenticated user (resolved from the token subject).
     *
     * @return A {@link ResponseEntity} containing the user DTO and HTTP status 200 (OK).
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    @Operation(
            summary = "Get the current user",
            description = "Resolves the caller's user record from the subject claim of their access token."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Caller is not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user matches the token subject")
    })
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
    @Operation(
            summary = "Partially update a user",
            description = "Applies the given field updates to the user with the given id. Callable by the "
                    + "user themselves or by a system admin."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the updated fields failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the user nor a system admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
    public ResponseEntity<UserDto> partialUpdateAUser(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = UserUpdateFieldsDto.class)))
            @RequestBody Map<String, Object> updates,
            @PathVariable Long id) {
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
