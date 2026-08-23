package org.tornotron.echno_backend.attendance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/attendance-settings")
@Validated
@Tag(
        name = "Attendance Settings",
        description = "Organization- and project-level configuration for how attendance is captured: "
                + "number of check-in/out cycles, whether a photo or geolocation is required, geofence "
                + "radius, movement tracking, and regularization rules. A project without its own settings "
                + "falls back to the organization's. Reading settings is tenant scoped; creating, updating "
                + "and deactivating them is limited to callers who can configure attendance."
)
public class AttendanceSettingsController {

    private final AttendanceSettingsService settingsService;

    public AttendanceSettingsController(AttendanceSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PostMapping
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Create attendance settings",
            description = "Creates an attendance settings profile for the organization, or for a single "
                    + "project when projectId is set."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attendance settings created"),
            @ApiResponse(responseCode = "400", description = "A required field is missing or out of range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings")
    })
    public ResponseEntity<AttendanceSettingsDto> create(@Valid @RequestBody AttendanceSettingsCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settingsService.createSettings(dto));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List attendance settings",
            description = "Returns every attendance settings profile in the current tenant, organization "
                    + "level and per project."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance settings returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<AttendanceSettingsDto>> getAll() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    @GetMapping("/org")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get organization-level attendance settings",
            description = "Returns the attendance settings that apply organization-wide, used as the "
                    + "default for projects without their own settings."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Organization attendance settings returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No organization-level attendance settings configured")
    })
    public ResponseEntity<AttendanceSettingsDto> getOrgSettings() {
        return ResponseEntity.ok(settingsService.getOrgSettings());
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "Get a project's attendance settings",
            description = "Returns the attendance settings for a project, falling back to the "
                    + "organization-level settings if the project has none of its own."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance settings returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id, or no settings configured for it")
    })
    public ResponseEntity<AttendanceSettingsDto> getProjectSettings(@PathVariable Long projectId) {
        return ResponseEntity.ok(settingsService.getProjectSettings(projectId));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Update attendance settings",
            description = "Applies a partial update to an attendance settings profile. Only the fields "
                    + "present in the request body are changed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attendance settings updated"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings"),
            @ApiResponse(responseCode = "404", description = "No attendance settings with the given id")
    })
    public ResponseEntity<AttendanceSettingsDto> update(@PathVariable Long id,
                                                         @RequestBody AttendanceSettingsPatchDto dto) {
        return ResponseEntity.ok(settingsService.updateSettings(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@attendanceSecurity.canConfigureAttendance()")
    @Operation(
            summary = "Deactivate attendance settings",
            description = "Deactivates the attendance settings profile with the given id. Deactivated "
                    + "settings no longer apply and are excluded from lookups."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Attendance settings deactivated"),
            @ApiResponse(responseCode = "403", description = "Caller lacks permission to configure attendance settings"),
            @ApiResponse(responseCode = "404", description = "No attendance settings with the given id")
    })
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        settingsService.deactivateSettings(id);
        return ResponseEntity.noContent().build();
    }
}
