package org.tornotron.echno_backend.labour;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.labour.dto.LabourCreationDto;
import org.tornotron.echno_backend.labour.dto.LabourDto;
import org.tornotron.echno_backend.labour.dto.LabourSimpleDto;
import org.tornotron.echno_backend.labour.dto.LabourUpdateDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/labour/web")
@Validated
public class LabourControllerWeb {

    private final LabourService labourService;

    public LabourControllerWeb(LabourService labourService) {
        this.labourService = labourService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LabourSimpleDto> createLabour(@Valid @RequestBody LabourCreationDto labourCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(labourService.createLabour(labourCreationDto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LabourDto>> getAllLabours(@RequestParam(defaultValue = "0") int pageNo,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.status(HttpStatus.OK).body(labourService.getAllLabours(pageNo, pageSize).getContent());
    }

    @GetMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LabourDto> getALabour(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(labourService.getALabour(id));
    }

    @PatchMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<ApiResponse> partialUpdateALabour(@Valid @RequestBody LabourUpdateDto updates, @PathVariable Long id) {
        labourService.partialUpdateALabour(updates, id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Labour with id: " + id + " updated"));
    }

    @DeleteMapping("{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<ApiResponse> deleteALabour(@PathVariable Long id) {
        labourService.deleteALabour(id);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Labour with id: " + id + " has been deleted"));
    }
}
