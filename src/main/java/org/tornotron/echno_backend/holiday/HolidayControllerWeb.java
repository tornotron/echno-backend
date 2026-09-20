package org.tornotron.echno_backend.holiday;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.holiday.dto.HolidayCreationDto;
import org.tornotron.echno_backend.holiday.dto.HolidayDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekUpdateDto;

import java.time.LocalDate;
import java.util.List;

/**
 * The web surface of the holiday calendar: the twin of {@link HolidayController} that addresses a
 * holiday by a {@code holidayId} query parameter. Reads are open to every member of the tenant,
 * because a holiday is something every employee plans leave around; writes are gated to the
 * roles that administer leave policies.
 */
@RestController
@RequestMapping("/api/v1/holidays/web")
@Validated
@Tag(
        name = "Holidays (Web)",
        description = "The organization's holiday calendar and working week. Reads for members, writes "
                + "for the system-admin and hr-admin roles that administer leave policies."
)
public class HolidayControllerWeb {

    private final HolidayService holidayService;

    public HolidayControllerWeb(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List the holidays of a year",
            description = "Every declared holiday of the current organization in the given calendar year, "
                    + "in date order. Defaults to the current year.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The holidays"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<HolidayDto>> listForYear(@RequestParam(required = false) Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        return ResponseEntity.ok(holidayService.listForYear(y));
    }

    @GetMapping("/holiday")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get a holiday")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The holiday"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No holiday with the given id")
    })
    public ResponseEntity<HolidayDto> get(@RequestParam Long holidayId) {
        return ResponseEntity.ok(holidayService.get(holidayId));
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(summary = "Declare a holiday",
            description = "Declares a holiday on a date for the current organization. One holiday per date.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Holiday declared"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "A holiday is already declared on that date")
    })
    public ResponseEntity<HolidayDto> create(@Valid @RequestBody HolidayCreationDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holidayService.create(dto));
    }

    @PutMapping("/update")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(summary = "Change a holiday", description = "Replaces the holiday's date, name and note.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holiday changed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No holiday with the given id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Another holiday is already declared on that date")
    })
    public ResponseEntity<HolidayDto> update(@RequestParam Long holidayId, @Valid @RequestBody HolidayCreationDto dto) {
        return ResponseEntity.ok(holidayService.update(holidayId, dto));
    }

    @DeleteMapping("/delete")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(summary = "Remove a holiday",
            description = "Removes the holiday. Leave requests already charged under it are not recalculated.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Holiday removed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No holiday with the given id")
    })
    public ResponseEntity<ApiResponse> delete(@RequestParam Long holidayId) {
        holidayService.delete(holidayId);
        return ResponseEntity.ok(new ApiResponse("Holiday removed successfully"));
    }

    @GetMapping("/working-week")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get the working week",
            description = "The days of the week the organization works. Monday to Friday until changed.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The working week"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<WorkingWeekDto> getWorkingWeek() {
        return ResponseEntity.ok(holidayService.getWorkingWeek());
    }

    @PutMapping("/working-week")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(summary = "Set the working week",
            description = "Sets the days of the week the organization works. Days outside it are not charged "
                    + "under the EXCLUDE_NON_WORKING_DAYS and SANDWICH leave treatments.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Working week set"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "No day named"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<WorkingWeekDto> updateWorkingWeek(@Valid @RequestBody WorkingWeekUpdateDto dto) {
        return ResponseEntity.ok(holidayService.updateWorkingWeek(dto));
    }
}
