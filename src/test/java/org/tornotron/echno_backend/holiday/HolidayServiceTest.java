package org.tornotron.echno_backend.holiday;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.holiday.dto.HolidayCreationDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekUpdateDto;
import org.tornotron.echno_backend.holiday.mapper.HolidayMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HolidayServiceTest {

    @Mock private HolidayRepository holidayRepository;
    @Mock private WorkingWeekRepository workingWeekRepository;
    @Mock private TenantEntityHelper tenantEntityHelper;
    @Mock private HolidayMapper holidayMapper;

    private HolidayService service;
    private Organization org;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(1L);
        org = new Organization();
        org.setId(1L);
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(org);
        when(holidayRepository.save(any(Holiday.class))).thenAnswer(i -> i.getArgument(0));
        when(workingWeekRepository.save(any(WorkingWeek.class))).thenAnswer(i -> i.getArgument(0));
        service = new HolidayService(holidayRepository, workingWeekRepository, tenantEntityHelper, holidayMapper);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private HolidayCreationDto dto(LocalDate date, String name) {
        HolidayCreationDto dto = new HolidayCreationDto();
        dto.setHolidayDate(date);
        dto.setName(name);
        dto.setDescription(" ");
        return dto;
    }

    @Test
    void create_refusesASecondHolidayOnTheSameDate() {
        when(holidayRepository.existsByOrganization_IdAndHolidayDate(1L, LocalDate.of(2026, 10, 2))).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto(LocalDate.of(2026, 10, 2), "Gandhi Jayanti")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void create_writesTheRowIntoTheCurrentTenant_andBlanksAnEmptyNote() {
        when(holidayRepository.existsByOrganization_IdAndHolidayDate(any(), any())).thenReturn(false);

        service.create(dto(LocalDate.of(2026, 10, 2), " Gandhi Jayanti "));

        ArgumentCaptor<Holiday> saved = ArgumentCaptor.forClass(Holiday.class);
        verify(holidayRepository).save(saved.capture());
        assertThat(saved.getValue().getOrganization()).isSameAs(org);
        assertThat(saved.getValue().getName()).isEqualTo("Gandhi Jayanti");
        assertThat(saved.getValue().getDescription()).isNull();
    }

    @Test
    void update_refusesMovingOntoAnotherHolidaysDate() {
        Holiday existing = new Holiday();
        existing.setId(7L);
        existing.setOrganization(org);
        when(holidayRepository.findByIdAndOrganization_Id(7L, 1L)).thenReturn(Optional.of(existing));
        when(holidayRepository.existsByOrganization_IdAndHolidayDateAndIdNot(1L, LocalDate.of(2026, 1, 26), 7L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, dto(LocalDate.of(2026, 1, 26), "Republic Day")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void delete_ofAHolidayOutsideTheTenant_isNotFound() {
        when(holidayRepository.findByIdAndOrganization_Id(9L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void workingWeek_isCreatedMondayToFridayOnFirstRead() {
        when(workingWeekRepository.findByOrganization_Id(1L)).thenReturn(Optional.empty());

        WorkingWeekDto week = service.getWorkingWeek();

        assertThat(week.getOrganizationId()).isEqualTo(1L);
        assertThat(week.getWorkingDays()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        verify(workingWeekRepository).save(any(WorkingWeek.class));
    }

    @Test
    void updateWorkingWeek_dedupesAndSorts_andRefusesAnEmptyWeek() {
        WorkingWeek row = new WorkingWeek();
        row.setOrganization(org);
        when(workingWeekRepository.findByOrganization_Id(1L)).thenReturn(Optional.of(row));
        WorkingWeekUpdateDto update = new WorkingWeekUpdateDto();
        update.setWorkingDays(List.of(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.MONDAY));

        assertThat(service.updateWorkingWeek(update).getWorkingDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);

        update.setWorkingDays(List.of());
        assertThatThrownBy(() -> service.updateWorkingWeek(update)).isInstanceOf(InvalidRequestException.class);
    }
}
