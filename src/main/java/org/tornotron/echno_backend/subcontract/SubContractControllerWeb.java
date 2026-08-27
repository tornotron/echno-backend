package org.tornotron.echno_backend.subcontract;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.subcontract.dto.SubContractCreationDto;
import org.tornotron.echno_backend.subcontract.dto.SubContractDto;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/sub-contracts/web")
@Tag(
        name = "Subcontracts",
        description = "Contracts placed with subcontractors for labor, material supply, equipment rental "
                + "or services on a project, including payment milestones, ratings and completion "
                + "tracking. Read access requires tenant membership; creating, updating and deleting are "
                + "restricted to system-admin or project-manager."
)
public class SubContractControllerWeb {

    private final SubContractService subContractService;

    public SubContractControllerWeb(SubContractService subContractService) {
        this.subContractService = subContractService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List all subcontracts",
            description = "Returns every subcontract for the current tenant, unpaginated."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subcontracts returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SubContractDto>> readAllSubContracts() {
        return new ResponseEntity<>(subContractService.getAll(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "List subcontracts, paginated and filterable",
            description = "Returns a single page of subcontracts. Supports a free-text search over "
                    + "contract and contractor name, and filtering by status or contract type."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Page of subcontracts returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<Page<SubContractDto>> readAllSubContractsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return new ResponseEntity<>(subContractService.getPaginated(pageNo, pageSize, search, status, type), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Get a subcontract by id",
            description = "Returns a single subcontract, including its milestones, ratings and payment totals."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subcontract found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No subcontract with the given id")
    })
    public ResponseEntity<SubContractDto> readASubContract(@PathVariable Long id) {
        return new ResponseEntity<>(subContractService.getById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Create a subcontract",
            description = "Creates a subcontract with a contractor, its scope of work, contract value, "
                    + "payment terms and optional milestones."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subcontract created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "contractName or contractorName is missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<SubContractDto> createSubContract(@Valid @RequestBody SubContractCreationDto creationDto) {
        return new ResponseEntity<>(subContractService.create(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Update a subcontract",
            description = "Replaces the subcontract's details, including its milestone list, with the given payload."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subcontract updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "contractName or contractorName is missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No subcontract with the given id")
    })
    public ResponseEntity<SubContractDto> updateSubContract(@PathVariable Long id,
                                                            @Valid @RequestBody SubContractCreationDto creationDto) {
        return new ResponseEntity<>(subContractService.update(id, creationDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Delete a subcontract",
            description = "Deletes the subcontract with the given id, along with its milestones."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subcontract deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No subcontract with the given id")
    })
    public ResponseEntity<ApiResponse> deleteSubContract(@PathVariable Long id) {
        subContractService.delete(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Subcontract with id: " + id + " has been deleted"));
    }
}
