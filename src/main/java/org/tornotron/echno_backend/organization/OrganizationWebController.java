package org.tornotron.echno_backend.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.common.payload.JsonPartBinder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.organization.dto.OrganizationUpdateFieldsDto;

@RestController
@RequestMapping("/api/v1/organization/web")
@Validated
@Tag(
        name = "Organizations",
        description = "Tenant-scoped organization access for the current user: creating an organization, "
                + "listing and reading the organizations the caller is a member of, and updating or "
                + "deleting an organization the caller administers. Companies such as Asset Homes are "
                + "represented as an organization."
)
public class OrganizationWebController {

    private final OrganizationService service;
    private final JsonPartBinder jsonPartBinder;

    public OrganizationWebController(OrganizationService service, JsonPartBinder jsonPartBinder) {
        this.jsonPartBinder = jsonPartBinder;
        this.service = service;
    }
    /**
     * Creates a new organization.
     *
     * <p>This is the endpoint a freshly registered account uses to bootstrap itself, so the guard
     * is authentication alone: the caller starts a tenant of their own, touches no existing one,
     * and becomes its system-admin. The billing rule lives in {@code OrganizationService}, which
     * exempts a user's first organization from the CREATE_ORGANIZATION entitlement and charges
     * every one after it. Gating the first creation on a subscription left a self-registered
     * account with nothing it could do, which is what the onboarding screen walked into.
     *
     * organizationCreationDto DTO containing the details for the new organization.
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
     * Retrieves the organizations that the authenticated user is a member of.
     *
     * <p>This is the organization picker: it is called before a tenant has been chosen, so the
     * guard is authentication alone and deliberately not tenant-scoped. Any check reading
     * {@link org.tornotron.echno_backend.common.multitenancy.TenantContext} would be circular
     * here, because the caller is asking which tenants they may select. The result is narrowed
     * to the caller's own organizations by {@code OrganizationService.getAllOrganization},
     * which queries by the authenticated user's identity rather than trusting this guard.
     *
     * @return A {@link ResponseEntity} containing the list of organization DTOs and HTTP status 200 (OK).
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List the current user's organizations",
            description = "Returns the organizations the caller is a member of. Any authenticated "
                    + "caller may request it and receives only their own organizations, those where "
                    + "they hold an employee record. No tenant needs to be selected first, since this "
                    + "is the endpoint that populates the organization picker."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of the caller's organizations, empty if they belong to none"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    })
    public ResponseEntity<List<OrganizationDto>> readAllOrganizations() {
        return ResponseEntity.status(HttpStatus.OK).body(service.getAllOrganization());
    }

    /**
     * Retrieves a single organization by its ID.
     * User must be a member of the organization or a platform admin.
     *
     * @param id The ID of the organization to retrieve.
     * @return A {@link ResponseEntity} containing the organization DTO and HTTP status 200 (OK).
     */
    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.isMemberOrAdmin(#id)")
    @Operation(
            summary = "Get an organization by id",
            description = "Returns a single organization by its numeric id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither a member of the organization nor a platform admin"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<?> readAnOrganization(@PathVariable Long id) {
        OrganizationDto organization = service.getAnOrganization(id);
        return new ResponseEntity<>(organization, HttpStatus.OK);
    }

    /**
     * Partially updates an existing organization.
     * Requires system-admin or hr-admin role in the organization, or platform admin access.
     *
     * @param id      The ID of the organization to update.
     * @return A {@link ResponseEntity} with the updated organization's simple DTO and HTTP status 200 (OK).
     */
    @PatchMapping(value = "{id}",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.hasAnyOrgRole(#id, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Partially update an organization",
            description = "Applies the given field updates to the organization, from a multipart request. "
                    + "The optional data part carries the fields to change as JSON; the optional attachments "
                    + "part carries a replacement file for the given entityType, such as the organization "
                    + "logo."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The data part is not valid JSON, or a field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin or hr-admin role in the organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<OrganizationSimpleDto> partialUpdateAnOrganization(
            @Parameter(schema = @Schema(implementation = OrganizationUpdateFieldsDto.class))
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType",required = false,defaultValue = "ORGANIZATION_LOGO") String entityType) throws JsonProcessingException{
        Map<String, Object> updates = jsonPartBinder.readUpdates(data);
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAnOrganization(updates,id,attachments,entityType));
    }

    /**
     * Deletes an organization by its ID.
     * Requires system-admin role in the organization or platform admin access.
     *
     * @param id The ID of the organization to delete.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasOrgRole(#id, 'system-admin')")
    @Operation(
            summary = "Delete an organization",
            description = "Deletes the organization with the given id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Organization deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role in the organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<ApiResponse> deleteOrganization(@PathVariable Long id) {
        service.deleteAnOrganization(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organization with id: " + id + " deleted"));
    }
}

