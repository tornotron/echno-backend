package org.tornotron.echno_backend.attendance;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/attendance-settings")
@Validated
public class AttendanceSettingsController {

    private final AttendanceSettingsService settingsService;

    public AttendanceSettingsController(AttendanceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PostMapping
    public ResponseEntity<AttendanceSettingsDto> create(@Valid @RequestBody AttendanceSettingsCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingsService.createSettings(dto));
    }

    @GetMapping
    public ResponseEntity<List<AttendanceSettingsDto>> getAll() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    @GetMapping("/org")
    public ResponseEntity<AttendanceSettingsDto> getOrgSettings() {
        return ResponseEntity.ok(settingsService.getOrgSettings());
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<AttendanceSettingsDto> getProjectSettings(@PathVariable Long projectId) {
        return ResponseEntity.ok(settingsService.getProjectSettings(projectId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AttendanceSettingsDto> update(@PathVariable Long id,
                                                         @RequestBody AttendanceSettingsPatchDto dto) {
        return ResponseEntity.ok(settingsService.updateSettings(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        settingsService.deactivateSettings(id);
        return ResponseEntity.noContent().build();
    }
}
