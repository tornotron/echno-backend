package org.tornotron.echno_backend.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
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
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing organizations.
 * Provides endpoints for creating, reading, updating, and deleting organizations.
 */
@RestController
@RequestMapping("/api/v1/organization")
@Validated
@Tag(
        name = "Organizations (Platform Admin)",
        description = "Platform-level organization management, used by platform administrators to look up "
                + "and manage organizations across tenants rather than within the caller's own tenant. "
                + "Companies such as Asset Homes are represented as an organization, each with its own "
                + "members, projects and subscription. Tenant-scoped organization access lives on the "
                + "sibling /organization/web endpoints."
)
public class OrganizationController {

    private final OrganizationService service;
    private final JsonPartBinder jsonPartBinder;

    /**
     * Constructs an OrganizationController with the given OrganizationService.
     *
     * @param service The service to handle organization-related business logic.
     */
    public OrganizationController(OrganizationService service,JsonPartBinder jsonPartBinder) {
        this.jsonPartBinder = jsonPartBinder;
        this.service = service;
    }

    /**
     * Creates a new organization.
     *
     * <p>The guard is authentication alone, and deliberately so. Creating an organization is how a
     * new account bootstraps itself: it starts a tenant of its own and touches no existing one, and
     * the creator becomes that tenant's system-admin. Requiring an organization authority first
     * meant a self-registered account could never obtain one, since the only endpoint that grants
     * it was the one being guarded. The billing side of the same rule lives in
     * {@code OrganizationService}, which exempts a user's first organization and charges the rest.
     *
     *  organizationCreationDto DTO containing the details for the new organization.
     * @return A {@link ResponseEntity} with the created organization's simple DTO and HTTP status 201 (Created).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Create an organization",
            description = "Creates an organization from a multipart request. The data part carries the "
                    + "organization details as JSON and the optional attachments part carries supporting "
                    + "files such as a logo. The caller becomes the organization's system admin. A "
                    + "user's first organization is part of signing up and needs no subscription; "
                    + "each one after it counts against the CREATE_ORGANIZATION subscription feature."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Organization created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid organization JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "402", description = "Caller already has an organization and their plan does not cover another"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not authenticated")
    })
    public ResponseEntity<OrganizationSimpleDto> createOrganization(
            @Parameter(schema = @Schema(implementation = OrganizationCreationDto.class))
            @RequestPart("data") String data,
            @RequestParam(value = "attachments",required = false)List<MultipartFile> attachments) throws JsonProcessingException {
        OrganizationCreationDto dto = jsonPartBinder.read(data, OrganizationCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addOrganization(dto,attachments));
    }
    /**
     * Retrieves a paginated list of all organizations.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of organizations per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of organization DTOs and HTTP status 200 (OK).
     */
//    @GetMapping
//    @PreAuthorize("hasAuthority('organization:read') or hasAuthority('organization:admin')")
//    public ResponseEntity<List<OrganizationDto>> readAllOrganizations(@RequestParam(defaultValue = "0") int pageNo,
//                                                                      @RequestParam(defaultValue = "10") int pageSize) {
//        Page<OrganizationDto> organizations = service.getAllOrganization(pageNo, pageSize);
//        return new ResponseEntity<>(organizations.getContent(), HttpStatus.OK);
//    }

    /**
     * Retrieves all organizations created by a specific user.
     *
     * @param creatorId The ID of the user who created the organizations.
     * @return A {@link ResponseEntity} containing a list of organization DTOs and HTTP status 200 (OK).
     */
    // Platform-admin only: this returns organizations created by an arbitrary
    // user id, with no tie between the caller and the returned records, so a
    // plain member must not be able to enumerate another user's organizations.
    // A member's own organizations come from the tenant-scoped /web listing,
    // which is filtered to their memberships.
    @GetMapping("/creator/{creatorId}")
    @PreAuthorize("hasAuthority('organization:admin')")
    @Operation(
            summary = "List organizations by creator",
            description = "Returns every organization created by the given user id. Platform-admin only, "
                    + "since it can enumerate another user's organizations regardless of the caller's own "
                    + "memberships."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of organizations created by the given user"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the organization admin authority")
    })
    public ResponseEntity<List<OrganizationDto>> readAllOrganizationsByCreatorId(@PathVariable Integer creatorId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getAllOrganizationsByCreatorId(creatorId));
    }

    /**
     * Retrieves a single organization by its ID.
     *
     * @param id The ID of the organization to retrieve.
     * @return A {@link ResponseEntity} containing the organization DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("(hasAuthority('organization:read') and @orgSecurity.isMember(#id)) or hasAuthority('organization:admin')")
    @Operation(
            summary = "Get an organization by id",
            description = "Returns a single organization by its numeric id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the organization read authority as a member of the organization, or the organization admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<?> readAnOrganization(@PathVariable Long id) {
        OrganizationDto organization = service.getAnOrganization(id);
        return new ResponseEntity<>(organization, HttpStatus.OK);
    }

    /**
     * Partially updates an existing organization.
     *
     * @param updates A map of fields to update.
     * @param id      The ID of the organization to update.
     * @return A {@link ResponseEntity} with the updated organization's simple DTO and HTTP status 200 (OK).
     */
//    @PatchMapping("{id}")
//    @PreAuthorize("hasAuthority('organization:update') or hasAuthority('organization:admin')")
//    public ResponseEntity<OrganizationSimpleDto> partialUpdateAnOrganization(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
//        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAnOrganization(updates, id));
//    }

    /**
     * Updates multiple organizations in a batch.
     *
     * @param updates A list of DTOs containing the updates for each organization.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('organization:update') or hasAuthority('organization:admin')")
    @Operation(
            summary = "Batch update organizations",
            description = "Applies partial updates to several organizations in one call. Each entry names "
                    + "an organization id and the fields to change on it."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch update applied"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update entries failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the organization update or admin authority")
    })
    public ResponseEntity<ApiResponse> batchUpdateOrganizations(@Valid @RequestBody List<OrganizationPatchDto> updates) {
        service.batchUpdateOrganization(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organizations updated successfully"));
    }

    /**
     * Deletes an organization by its ID.
     *
     * @param id The ID of the organization to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasOrgRole(#id, 'system-admin') or (hasAuthority('organization:delete') and @orgSecurity.isMember(#id)) or hasAuthority('organization:admin')")
    @Operation(
            summary = "Delete an organization",
            description = "Deletes the organization with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role in the organization, the organization delete authority as a member, or the organization admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<ApiResponse> deleteOrganization(@PathVariable Long id) {
        service.deleteAnOrganization(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organization with id: " + id + " deleted"));
    }
}