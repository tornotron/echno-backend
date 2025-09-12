package org.tornotron.echno_backend.organization;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationDto;
import org.tornotron.echno_backend.organization.dto.OrganizationPatchDto;
import org.tornotron.echno_backend.organization.dto.OrganizationSimpleDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/organization")
@Validated
public class OrganizationController {

    private final OrganizationService service;


    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

//    @PostMapping("/onboard")
//    public ResponseEntity<ApiResponse> onboardTenant(@RequestParam String tenantId,
//                                                                      @RequestParam String dbUrl,
//                                                                      @RequestParam String username,
//                                                                      @RequestParam String password) {
//        tenantService.onboardNewTenant(tenantId, dbUrl, username, password);
//        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse("Tenant "+tenantId+" onboarded successfully"));
//    }

//    @PostMapping("/migrate")
//    public ResponseEntity<ApiResponse> migrateAllTenants() {
//        tenantService.migrateAllTenants();
//        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("All tenants migrated successfully"));
//    }

    @PostMapping
    public ResponseEntity<OrganizationSimpleDto> createOrganization(@Valid @RequestBody OrganizationCreationDto organizationCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addOrganization(organizationCreationDto));
    }

    @GetMapping
    public ResponseEntity<List<OrganizationDto>> readAllOrganizations(@RequestParam(defaultValue = "0") int pageNo,
                                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<OrganizationDto> organizations = service.getAllOrganization(pageNo, pageSize);
        return new ResponseEntity<>(organizations.getContent(), HttpStatus.OK);
    }

    @GetMapping("/creator/{creatorId}")
    public ResponseEntity<List<OrganizationDto>> readAllOrganizationsByCreatorId(@PathVariable Integer creatorId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getAllOrganizationsByCreatorId(creatorId));
    }

    @GetMapping("{id}")
    public ResponseEntity<?> readAnOrganization(@PathVariable Long id) {
        OrganizationDto organization = service.getAnOrganization(id);
        return new ResponseEntity<>(organization, HttpStatus.OK);
    }

    @PatchMapping("{id}")
    public ResponseEntity<OrganizationSimpleDto> partialUpdateAnOrganization(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(service.partialUpdateAnOrganization(updates, id));
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse> batchUpdateOrganizations(@Valid @RequestBody List<OrganizationPatchDto> updates) {
        service.batchUpdateOrganization(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organizations updated successfully"));
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ApiResponse> deleteOrganization(@PathVariable Long id) {
        service.deleteAnOrganization(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Organization with id: " + id + " deleted"));
    }
}
