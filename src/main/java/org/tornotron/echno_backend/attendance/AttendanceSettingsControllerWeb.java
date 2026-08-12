package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsCreationDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsPatchDto;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance-settings/web")
@Validated
public class AttendanceSettingsControllerWeb {

    private final AttendanceSettingsService settingsService;

    public AttendanceSettingsControllerWeb(AttendanceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PostMapping
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<AttendanceSettingsDto> create(@Valid @RequestBody AttendanceSettingsCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingsService.createSettings(dto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<List<AttendanceSettingsDto>> getAll() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    @GetMapping("/org")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<AttendanceSettingsDto> getOrgSettings() {
        return ResponseEntity.ok(settingsService.getOrgSettings());
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    public ResponseEntity<AttendanceSettingsDto> getProjectSettings(@PathVariable Long projectId) {
        return ResponseEntity.ok(settingsService.getProjectSettings(projectId));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<AttendanceSettingsDto> update(@PathVariable Long id,
                                                         @RequestBody AttendanceSettingsPatchDto dto) {
        return ResponseEntity.ok(settingsService.updateSettings(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        settingsService.deactivateSettings(id);
        return ResponseEntity.noContent().build();
    }
}
