package org.tornotron.echno_backend.holiday;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.holiday.dto.HolidayDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekDto;

import java.util.List;

/**
 * The mobile surface of the holiday calendar: the year's holidays and the working week, read
 * only. Declaring and changing holidays is an office job and lives on the web twin,
 * {@link HolidayControllerWeb}, under the leave-policy administration guard.
 */
@RestController
@RequestMapping("/api/v1/holidays")
@Validated
@Tag(
        name = "Holidays",
        description = "The organization's holiday calendar and working week, read by every member. "
                + "Writes are on the web twin."
)
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    @GetMapping("/year/{year}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List the holidays of a year",
            description = "Every declared holiday of the current organization in the given calendar year, "
                    + "in date order.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The holidays"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<HolidayDto>> listForYear(@PathVariable int year) {
        return ResponseEntity.ok(holidayService.listForYear(year));
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
}
