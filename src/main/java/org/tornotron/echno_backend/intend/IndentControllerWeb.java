package org.tornotron.echno_backend.intend;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.intend.dto.IntendCreationDto;
import org.tornotron.echno_backend.intend.dto.IntendDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/indents/web")
public class IndentControllerWeb {

    private final IntendService intendService;

    public IndentControllerWeb(IntendService intendService) {
        this.intendService = intendService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IntendDto> createIntend(@Valid @RequestBody IntendCreationDto intendCreationDto) {
        return new ResponseEntity<>(intendService.addIntend(intendCreationDto), HttpStatus.CREATED);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<IntendDto>> getAllIntends(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return new ResponseEntity<>(intendService.getAllIntends(pageNo, pageSize).getContent(), HttpStatus.OK);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<IntendDto>> getAllIntends() {
        return new ResponseEntity<>(intendService.getAllIntends(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<IntendDto> getAnIntend(@PathVariable Long id) {
        return new ResponseEntity<>(intendService.getAnIntend(id), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<ApiResponse> deleteIntend(@PathVariable Long id) {
        intendService.deleteIntend(id);
        return ResponseEntity.ok(new ApiResponse("Intend with id: " + id + " deleted successfully"));
    }

}
