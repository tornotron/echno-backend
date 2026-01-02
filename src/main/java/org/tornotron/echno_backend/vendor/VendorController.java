package org.tornotron.echno_backend.vendor;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.vendor.dto.VendorCreationDto;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vendors")
@Validated
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('vendor:create') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> createVendor(@Valid @RequestBody VendorCreationDto creationDto) {
        VendorDto created = vendorService.createVendor(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> getVendorById(@PathVariable Long id) {
        VendorDto vendor = vendorService.getVendorById(id);
        return ResponseEntity.ok(vendor);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorDto>> getAllVendors() {
        List<VendorDto> vendors = vendorService.getAllVendors();
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<Page<VendorDto>> getAllVendorsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<VendorDto> vendors = vendorService.getAllVendors(pageNo, pageSize);
        return ResponseEntity.ok(vendors);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('vendor:read') or hasAuthority('vendor:admin')")
    public ResponseEntity<List<VendorDto>> searchVendors(@RequestParam String name) {
        List<VendorDto> vendors = vendorService.searchVendorsByName(name);
        return ResponseEntity.ok(vendors);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:update') or hasAuthority('vendor:admin')")
    public ResponseEntity<VendorDto> updateVendor(
            @PathVariable Long id,
            @Valid @RequestBody VendorCreationDto updateDto
    ) {
        VendorDto updated = vendorService.updateVendor(id, updateDto);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:delete') or hasAuthority('vendor:admin')")
    public ResponseEntity<ApiResponse> deleteVendor(@PathVariable Long id) {
        vendorService.deleteVendor(id);
        return ResponseEntity.ok(new ApiResponse("Vendor with id: " + id + " deleted successfully"));
    }
}
