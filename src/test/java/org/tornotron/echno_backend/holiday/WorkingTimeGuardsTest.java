package org.tornotron.echno_backend.holiday;

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
import org.tornotron.echno_backend.attendance.AttendanceSettingsController;
import org.tornotron.echno_backend.attendance.AttendanceSettingsControllerWeb;
import org.tornotron.echno_backend.attendance.ShiftTimingController;
import org.tornotron.echno_backend.attendance.ShiftTimingControllerWeb;

/**
 * Every holiday, working week, attendance settings and shift timing handler, on the tenant API and its
 * {@code /web} twin, asks the access matrix for the one working-time action it performs. A new handler
 * fails here until it is given its row.
 */
class WorkingTimeGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by the working-time action. */
    static final Map<String, String> ACTION_BY_HANDLER = Map.ofEntries(
            Map.entry("HolidayController#listForYear", "read"),
            Map.entry("HolidayController#getWorkingWeek", "read"),
            Map.entry("HolidayControllerWeb#listForYear", "read"),
            Map.entry("HolidayControllerWeb#get", "read"),
            Map.entry("HolidayControllerWeb#create", "create"),
            Map.entry("HolidayControllerWeb#update", "edit"),
            Map.entry("HolidayControllerWeb#delete", "delete"),
            Map.entry("HolidayControllerWeb#getWorkingWeek", "read"),
            Map.entry("HolidayControllerWeb#updateWorkingWeek", "edit"),
            Map.entry("AttendanceSettingsController#create", "create"),
            Map.entry("AttendanceSettingsController#getAll", "read"),
            Map.entry("AttendanceSettingsController#getOrgSettings", "read"),
            Map.entry("AttendanceSettingsController#getProjectSettings", "read"),
            Map.entry("AttendanceSettingsController#update", "edit"),
            Map.entry("AttendanceSettingsController#deactivate", "delete"),
            Map.entry("AttendanceSettingsControllerWeb#create", "create"),
            Map.entry("AttendanceSettingsControllerWeb#getAll", "read"),
            Map.entry("AttendanceSettingsControllerWeb#getOrgSettings", "read"),
            Map.entry("AttendanceSettingsControllerWeb#getProjectSettings", "read"),
            Map.entry("AttendanceSettingsControllerWeb#update", "edit"),
            Map.entry("AttendanceSettingsControllerWeb#deactivate", "delete"),
            Map.entry("ShiftTimingController#create", "create"),
            Map.entry("ShiftTimingController#getAll", "read"),
            Map.entry("ShiftTimingController#getById", "read"),
            Map.entry("ShiftTimingController#update", "edit"),
            Map.entry("ShiftTimingController#delete", "delete"),
            Map.entry("ShiftTimingControllerWeb#create", "create"),
            Map.entry("ShiftTimingControllerWeb#getAll", "read"),
            Map.entry("ShiftTimingControllerWeb#getById", "read"),
            Map.entry("ShiftTimingControllerWeb#update", "edit"),
            Map.entry("ShiftTimingControllerWeb#delete", "delete"));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(WorkingTimeGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(ACTION_BY_HANDLER.keySet());
        ACTION_BY_HANDLER.forEach((handler, action) -> assertThat(guards.get(handler)).as(handler)
                .isEqualTo("@access.can('working-time', '" + action + "')"));
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(HolidayController.class, HolidayControllerWeb.class, AttendanceSettingsController.class, AttendanceSettingsControllerWeb.class, ShiftTimingController.class, ShiftTimingControllerWeb.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
