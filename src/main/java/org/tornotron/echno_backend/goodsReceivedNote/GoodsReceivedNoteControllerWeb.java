package org.tornotron.echno_backend.goodsReceivedNote;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteCreationDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteUpdateDto;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/grns/web")
@Validated
public class GoodsReceivedNoteControllerWeb {

    private final GoodsReceivedNoteService goodsReceivedNoteService;

    public GoodsReceivedNoteControllerWeb(GoodsReceivedNoteService goodsReceivedNoteService) {
        this.goodsReceivedNoteService = goodsReceivedNoteService;
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<GoodsReceivedNoteDto> createGrn(@Valid @RequestBody GoodsReceivedNoteCreationDto creationDto) {
        GoodsReceivedNoteDto created = goodsReceivedNoteService.createGoodsReceivedNote(creationDto);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<GoodsReceivedNoteDto> getGrnById(@PathVariable Long id) {
        GoodsReceivedNoteDto grn = goodsReceivedNoteService.getGrnById(id);
        return ResponseEntity.ok(grn);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<GoodsReceivedNoteDto>> getAllGrns() {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getAllGrns();
        return ResponseEntity.ok(grns);
    }

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<Page<GoodsReceivedNoteDto>> getAllGrnsPaginated(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        Page<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getAllGrns(pageNo, pageSize);
        return ResponseEntity.ok(grns);
    }

    @GetMapping("/vendor/{vendorId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByVendor(@PathVariable Long vendorId) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByVendor(vendorId);
        return ResponseEntity.ok(grns);
    }

    @PatchMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<GoodsReceivedNoteDto> updateGrn(@Valid @RequestBody GoodsReceivedNoteUpdateDto updateDto) {
        GoodsReceivedNoteDto updated = goodsReceivedNoteService.updateGoodsReceivedNote(updateDto);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/date-range")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<List<GoodsReceivedNoteDto>> getGrnsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        List<GoodsReceivedNoteDto> grns = goodsReceivedNoteService.getGrnsByDateRange(startDate, endDate);
        return ResponseEntity.ok(grns);
    }

}
