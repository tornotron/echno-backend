package org.tornotron.echno_backend.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every attendance and movement handler, on the tenant API and its {@code /web} twin, asks the access
 * matrix for the action it performs. Where the old guard was the record-management roles, it asks for
 * the action across the organization ({@code @teamAccess.acrossOrganization}); where it was the
 * employee named or those roles, it asks about that employee ({@code @teamAccess.allowsFor}); where it
 * was any member, the area alone, and the service goes on deciding whose record the call touches.
 * Deleting a record is the named {@code delete-attendance}. A new handler fails here until it is given
 * its row.
 */
class AttendanceGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by the whole guard. */
    static final Map<String, String> GUARD_BY_HANDLER = Map.ofEntries(
            Map.entry("AttendanceController#checkIn", can("create")),
            Map.entry("AttendanceController#recordClockEvent", can("create")),
            Map.entry("AttendanceController#getById", can("read")),
            Map.entry("AttendanceController#getByEmployee", forEmployee("read", "employeeId")),
            Map.entry("AttendanceController#getByProject", acrossOrganization("read")),
            Map.entry("AttendanceController#approve", can("approve")),
            Map.entry("AttendanceController#getPendingApprovals", can("read")),
            Map.entry("AttendanceController#getPendingApprovalCount", can("read")),
            Map.entry("AttendanceController#markAbsent", acrossOrganization("create")),
            Map.entry("AttendanceController#getMonthlySummary", forEmployee("read", "employeeId")),
            Map.entry("AttendanceController#delete", acrossOrganization("delete-attendance")),
            Map.entry("AttendanceControllerWeb#checkIn", can("create")),
            Map.entry("AttendanceControllerWeb#recordClockEvent", can("create")),
            Map.entry("AttendanceControllerWeb#getById", can("read")),
            Map.entry("AttendanceControllerWeb#getByEmployee", forEmployee("read", "employeeId")),
            Map.entry("AttendanceControllerWeb#getByProject", acrossOrganization("read")),
            Map.entry("AttendanceControllerWeb#approve", can("approve")),
            Map.entry("AttendanceControllerWeb#getPendingApprovals", can("read")),
            Map.entry("AttendanceControllerWeb#getPendingApprovalCount", can("read")),
            Map.entry("AttendanceControllerWeb#markAbsent", acrossOrganization("create")),
            Map.entry("AttendanceControllerWeb#getMonthlySummary", forEmployee("read", "employeeId")),
            Map.entry("AttendanceControllerWeb#delete", acrossOrganization("delete-attendance")),
            Map.entry("MovementRecordController#create", can("create")),
            Map.entry("MovementRecordController#getById", can("read")),
            Map.entry("MovementRecordController#getByAttendance", can("read")),
            Map.entry("MovementRecordController#verify", acrossOrganization("approve")),
            Map.entry("MovementRecordControllerWeb#create", can("create")),
            Map.entry("MovementRecordControllerWeb#getById", can("read")),
            Map.entry("MovementRecordControllerWeb#getByAttendance", can("read")),
            Map.entry("MovementRecordControllerWeb#verify", acrossOrganization("approve")));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(AttendanceGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards).containsExactlyInAnyOrderEntriesOf(GUARD_BY_HANDLER);
    }

    static String can(String action) {
        return "@access.can('team', '" + action + "')";
    }

    static String forEmployee(String action, String parameter) {
        return can(action) + " and @teamAccess.allowsFor('" + action + "', #" + parameter + ")";
    }

    static String acrossOrganization(String action) {
        return can(action) + " and @teamAccess.acrossOrganization('" + action + "')";
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(AttendanceController.class, AttendanceControllerWeb.class,
                        MovementRecordController.class, MovementRecordControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
