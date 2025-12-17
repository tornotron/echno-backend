package org.tornotron.echno_backend.siteTransfer;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferCreationDto;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.util.List;

@RestController
@RequestMapping("/api/v1/site-transfers")
@Validated
public class SiteTransferController {

    private final SiteTransferService siteTransferService;

    public SiteTransferController(SiteTransferService siteTransferService) {
        this.siteTransferService = siteTransferService;
    }

    @PostMapping
    public ResponseEntity<SiteTransferDto> createSiteTransfer(@Valid @RequestBody SiteTransferCreationDto creationDto) {
        SiteTransferDto created = siteTransferService.createSiteTransfer(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SiteTransferDto> getSiteTransferById(@PathVariable Long id) {
        SiteTransferDto transfer = siteTransferService.getSiteTransferById(id);
        return ResponseEntity.ok(transfer);
    }

    @GetMapping
    public ResponseEntity<List<SiteTransferDto>> getAllSiteTransfers() {
        List<SiteTransferDto> transfers = siteTransferService.getAllSiteTransfers();
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/all")
    public ResponseEntity<Page<SiteTransferDto>> getAllSiteTransfersPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<SiteTransferDto> transfers = siteTransferService.getAllSiteTransfers(pageNo, pageSize);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByStatus(@PathVariable SiteTransferStatus status) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByStatus(status);
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/receiving-site/{receivingSite}")
    public ResponseEntity<List<SiteTransferDto>> getSiteTransfersByReceivingSite(@PathVariable String receivingSite) {
        List<SiteTransferDto> transfers = siteTransferService.getSiteTransfersByReceivingSite(receivingSite);
        return ResponseEntity.ok(transfers);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse> updateSiteTransferStatus(
            @PathVariable Long id,
            @RequestParam SiteTransferStatus status
    ) {
        siteTransferService.updateSiteTransferStatus(id, status);
        return ResponseEntity.ok(new ApiResponse("Site transfer status updated successfully"));
    }
}
