package org.tornotron.echno_backend.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/organization/web")
@Validated
public class OrganizationWebController {

    private final OrganizationService service;
    private final ObjectMapper objectMapper;

    public OrganizationWebController(OrganizationService service, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.service = service;
    }
    /**
     * Creates a new organization.
     *
     * organizationCreationDto DTO containing the details for the new organization.
     * @return A {@link ResponseEntity} with the created organization's simple DTO and HTTP status 201 (Created).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @PreAuthorize("hasAuthority('organization:create') or hasAuthority('organization:admin')")
    @RequireSubscription(feature = "CREATE_ORGANIZATION",recordUsage = true)
    public ResponseEntity<OrganizationSimpleDto> createOrganization(@RequestPart("data") @Valid String data,
                                                                    @RequestParam(value = "attachments",required = false)List<MultipartFile> attachments) throws JsonProcessingException {
        OrganizationCreationDto dto = objectMapper.readValue(data, OrganizationCreationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addOrganization(dto,attachments));
    }

    /**
     * Retrieves a paginated list of all organizations.
     *
     * @param pageNo   The page number to retrieve (default is 0).
     * @param pageSize The number of organizations per page (default is 10).
     * @return A {@link ResponseEntity} containing the list of organization DTOs and HTTP status 200 (OK).
     */
    @GetMapping
//    @PreAuthorize("hasAuthority('organization:read') or hasAuthority('organization:admin')")
    public ResponseEntity<List<OrganizationDto>> readAllOrganizations(@RequestParam(defaultValue = "0") int pageNo,
                                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<OrganizationDto> organizations = service.getAllOrganization(pageNo, pageSize);
        return new ResponseEntity<>(organizations.getContent(), HttpStatus.OK);
    }

    /**
     * Retrieves all organizations created by a specific user.
     *
     * @param creatorId The ID of the user who created the organizations.
     * @return A {@link ResponseEntity} containing a list of organization DTOs and HTTP status 200 (OK).
     */
    @GetMapping("/creator/{creatorId}")
//    @PreAuthorize("hasAuthority('organization:read') or hasAuthority('organization:admin')")
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
    @PreAuthorize("@orgSecurity.hasOrgRole(#id, 'system-admin')")
    public ResponseEntity<?> readAnOrganization(@PathVariable Long id) {
        OrganizationDto organization = service.getAnOrganization(id);
        return new ResponseEntity<>(organization, HttpStatus.OK);
    }

    /**
     * Partially updates an existing organization.
     *
     * @param id      The ID of the organization to update.
     * @return A {@link ResponseEntity} with the updated organization's simple DTO and HTTP status 200 (OK).
     */
    @PatchMapping(value = "{id}",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@orgSecurity.isMemberOrAdmin(#id)")
    public ResponseEntity<OrganizationSimpleDto> partialUpdateAnOrganization(
            @RequestPart(value = "data", required = false) String data,
            @PathVariable Long id,
            @RequestParam(value = "attachments",required = false) List<MultipartFile> attachments,
            @RequestParam(value = "entityType",required = false,defaultValue = "ORGANIZATION_LOGO") String entityType) throws JsonProcessingException{
        Map<String ,Object> updates = data != null
                ? objectMapper.readValue(data, new TypeReference<>() {}) : Map.of();
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAnOrganization(updates,id,attachments,entityType));
    }

    /**
     * Updates multiple organizations in a batch.
     *
     * @param updates A list of DTOs containing the updates for each organization.
     * @return A {@link ResponseEntity} with a success message and HTTP status 200 (OK).
     */
    @PatchMapping("/batch")
//    @PreAuthorize("hasAuthority('organization:update') or hasAuthority('organization:admin')")
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
    @PreAuthorize("@orgSecurity.isMemberOrAdmin(#id)")
    public ResponseEntity<ApiResponse> deleteOrganization(@PathVariable Long id) {
        service.deleteAnOrganization(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organization with id: " + id + " deleted"));
    }
}

