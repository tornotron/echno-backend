package org.tornotron.echno_backend.holiday;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkingCalendarServiceTest {

    @Mock private HolidayRepository holidayRepository;
    @Mock private HolidayService holidayService;

    private WorkingCalendarService service;

    // 2026-08-24 is a Monday.
    private static final LocalDate MON = LocalDate.of(2026, 8, 24);
    private static final LocalDate WED = LocalDate.of(2026, 8, 26);
    private static final LocalDate SAT = LocalDate.of(2026, 8, 29);
    private static final LocalDate SUN = LocalDate.of(2026, 8, 30);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(1L);
        service = new WorkingCalendarService(holidayRepository, holidayService);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void weekendAndDeclaredHolidayAreBothNonWorking() {
        when(holidayService.workingDays()).thenReturn(WorkingWeek.DEFAULT_WORKING_DAYS);
        Holiday holiday = new Holiday();
        holiday.setHolidayDate(WED);
        when(holidayRepository.findByOrganization_IdAndHolidayDateBetweenOrderByHolidayDateAsc(eq(1L), any(), any()))
                .thenReturn(List.of(holiday));

        assertThat(service.nonWorkingDays(MON, NEXT_MON)).containsExactly(WED, SAT, SUN);
    }

    @Test
    void aSixDayWeek_leavesSaturdayWorking() {
        when(holidayService.workingDays()).thenReturn(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY));
        when(holidayRepository.findByOrganization_IdAndHolidayDateBetweenOrderByHolidayDateAsc(eq(1L), any(), any()))
                .thenReturn(List.of());

        assertThat(service.nonWorkingDays(MON, NEXT_MON)).containsExactly(SUN);
    }
}
