package org.tornotron.echno_backend.subcontract;

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
public class SubContractControllerWeb {

    private final SubContractService subContractService;

    public SubContractControllerWeb(SubContractService subContractService) {
        this.subContractService = subContractService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<SubContractDto>> readAllSubContracts() {
        return new ResponseEntity<>(subContractService.getAll(), HttpStatus.OK);
    }

    @GetMapping("/paginated")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<Page<SubContractDto>> readAllSubContractsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return new ResponseEntity<>(subContractService.getPaginated(pageNo, pageSize, search, status, type), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<SubContractDto> readASubContract(@PathVariable Long id) {
        return new ResponseEntity<>(subContractService.getById(id), HttpStatus.OK);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<SubContractDto> createSubContract(@Valid @RequestBody SubContractCreationDto creationDto) {
        return new ResponseEntity<>(subContractService.create(creationDto), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<SubContractDto> updateSubContract(@PathVariable Long id,
                                                            @Valid @RequestBody SubContractCreationDto creationDto) {
        return new ResponseEntity<>(subContractService.update(id, creationDto), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    public ResponseEntity<ApiResponse> deleteSubContract(@PathVariable Long id) {
        subContractService.delete(id);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Subcontract with id: " + id + " has been deleted"));
    }
}
