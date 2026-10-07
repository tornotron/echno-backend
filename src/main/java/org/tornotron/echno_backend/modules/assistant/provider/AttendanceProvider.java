package org.tornotron.echno_backend.modules.assistant.provider;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.modules.assistant.api.AssistantProvider;
import org.tornotron.echno_backend.modules.assistant.api.CostProfile;
import org.tornotron.echno_backend.modules.assistant.api.EvidenceUnit;
import org.tornotron.echno_backend.modules.assistant.api.FieldSpec;
import org.tornotron.echno_backend.modules.assistant.api.ProviderDescriptor;
import org.tornotron.echno_backend.modules.assistant.api.ProviderResult;
import org.tornotron.echno_backend.modules.assistant.api.Question;
import org.tornotron.echno_backend.modules.assistant.api.Scope;
import org.tornotron.echno_backend.modules.assistant.api.SourceRef;
import org.tornotron.echno_backend.modules.assistant.api.Subject;

/**
 * Attendance for a project over a period, one summary per day: how many employees have a record and
 * how many sit in each status, with the minutes worked and the overtime.
 *
 * <p>Reads through {@link AttendanceService#getAttendanceByProject}, the service behind the project
 * attendance read on both attendance controllers, so the tenant filter applies by construction.
 * That read is guarded on the controllers, not on the service, so {@link #retrieve} carries the
 * same guard, {@link #READ_GUARD}, and {@code ProviderGuardParityTest} fails if the two ever
 * differ. It means only the roles that may read a project's attendance (by default the system
 * admin, HR admin and project manager) get attendance evidence through the assistant.
 *
 * <p>The counts are per status, with the status names the attendance module uses. Nothing here
 * decides which statuses "count as present": that is a definition, and the answer should be able to
 * quote the source's own words rather than this class's reading of them. Named rows are not
 * returned; a day is an aggregate.
 */
@Slf4j
@Component
public class AttendanceProvider implements AssistantProvider {

    public static final String ID = "attendance";

    /** The guard on {@code GET /project/{projectId}} of both attendance controllers. */
    public static final String READ_GUARD = "@attendanceSecurity.canManageRecords()";

    // A week is the default period and a month is the most anyone should ask for in one answer;
    // past it the evidence would not fit the model's context anyway.
    static final int MAX_DAYS = 31;

    // The read returns one page at a time. A provider that took only the first would silently
    // under-count a big site's day, so it walks the pages; the ceiling turns a runaway into an
    // Unavailable instead of an unbounded loop.
    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES_PER_DAY = 50;

    private static final String STATUSES = Arrays.stream(AttendanceStatus.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor(
            ID,
            "Who was on site and how each day went for one project: per day, how many employees have an "
                    + "attendance record and how many sit in each attendance status, with the minutes worked "
                    + "and the overtime. It needs a project and a date range. It does not name individual "
                    + "employees and cannot break the count down by trade.",
            List.of(
                    new FieldSpec("date", "ISO-8601 date", "The calendar day the summary covers."),
                    new FieldSpec("projectId", "id", "The project the attendance records belong to."),
                    new FieldSpec("recordCount", "records",
                            "Employees with an attendance record that day, whatever its status."),
                    new FieldSpec("statusCounts", "records per status",
                            "Records per attendance status, named as the attendance module names them ("
                                    + STATUSES + "); a status with no records is left out."),
                    new FieldSpec("totalWorkMinutes", "minutes",
                            "Minutes worked, summed over the day's records; a record with no figure adds nothing."),
                    new FieldSpec("overtimeMinutes", "minutes",
                            "Minutes worked beyond the shift's overtime threshold, summed the same way.")),
            Set.of(Subject.ATTENDANCE),
            true,
            true,
            // An estimate, not a measurement: one indexed read per day of the period.
            new CostProfile(500, 0));

    private final AttendanceService attendanceService;

    public AttendanceProvider(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Override
    public ProviderDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    @PreAuthorize(READ_GUARD)
    public ProviderResult retrieve(Question question, Scope scope) {
        if (scope.projectId() == null) {
            return new ProviderResult.Unavailable(
                    "Attendance is read for one project, and no project was resolved for this question.");
        }
        if (scope.from() == null) {
            return new ProviderResult.Unavailable(
                    "Attendance is read for a period, and no period was resolved for this question.");
        }
        long days = ChronoUnit.DAYS.between(scope.from(), scope.to()) + 1;
        if (days > MAX_DAYS) {
            return new ProviderResult.Unavailable("Attendance is read for at most " + MAX_DAYS
                    + " days at a time, and " + days + " were asked for.");
        }

        List<EvidenceUnit> units = new ArrayList<>();
        try {
            for (LocalDate day = scope.from(); !day.isAfter(scope.to()); day = day.plusDays(1)) {
                DayTotals totals = readDay(scope.projectId(), day);
                if (totals.records > 0) {
                    units.add(summarize(scope.projectId(), day, totals));
                }
            }
        } catch (AccessDeniedException e) {
            // A refusal is a refusal, never a quiet "could not be read".
            throw e;
        } catch (RuntimeException e) {
            // The reason shown to the user names nothing internal; the log has the cause.
            log.warn("Attendance could not be read for project {} from {} to {}",
                    scope.projectId(), scope.from(), scope.to(), e);
            return new ProviderResult.Unavailable(
                    "Attendance for project " + scope.projectId() + " could not be read.");
        }

        if (units.isEmpty()) {
            return new ProviderResult.Empty("attendance records for project " + scope.projectId()
                    + " from " + scope.from() + " to " + scope.to());
        }
        return new ProviderResult.Evidence(units);
    }

    // Aggregated page by page, so a large day is never held in memory as full records.
    private DayTotals readDay(Long projectId, LocalDate day) {
        DayTotals totals = new DayTotals();
        for (int page = 0; page < MAX_PAGES_PER_DAY; page++) {
            // Sorted by name then id, so a row cannot land on two pages or none between calls.
            List<AttendanceResponseDto> rows = attendanceService.getAttendanceByProject(projectId, day,
                    null, null, null, null, PageRequest.of(page, PAGE_SIZE, Sort.by("employeeName", "id")));
            rows.forEach(totals::add);
            if (rows.size() < PAGE_SIZE) {
                return totals;
            }
        }
        throw new IllegalStateException("More than " + MAX_PAGES_PER_DAY * PAGE_SIZE
                + " attendance records on " + day + " for project " + projectId);
    }

    private static EvidenceUnit summarize(Long projectId, LocalDate day, DayTotals totals) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", day.toString());
        values.put("projectId", projectId);
        values.put("recordCount", totals.records);
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        totals.byStatus.forEach((status, count) -> statusCounts.put(status.name(), count));
        values.put("statusCounts", Collections.unmodifiableMap(statusCounts));
        values.put("totalWorkMinutes", totals.workMinutes);
        values.put("overtimeMinutes", totals.overtimeMinutes);
        return new EvidenceUnit(
                "attendance:" + day + ":project-" + projectId,
                ID,
                "attendance-day-summary",
                values,
                SourceRef.entity("project-attendance", projectId + "/" + day),
                1.0);
    }

    private static final class DayTotals {
        int records;
        long workMinutes;
        long overtimeMinutes;
        // Enum order, so the counts read the same way every time.
        final Map<AttendanceStatus, Integer> byStatus = new EnumMap<>(AttendanceStatus.class);

        void add(AttendanceResponseDto record) {
            records++;
            if (record.getStatus() != null) {
                byStatus.merge(record.getStatus(), 1, Integer::sum);
            }
            if (record.getTotalWorkMinutes() != null) {
                workMinutes += record.getTotalWorkMinutes();
            }
            if (record.getOvertimeMinutes() != null) {
                overtimeMinutes += record.getOvertimeMinutes();
            }
        }
    }
}
