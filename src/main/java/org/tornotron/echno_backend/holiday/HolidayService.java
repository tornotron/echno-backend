package org.tornotron.echno_backend.holiday;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.holiday.dto.HolidayCreationDto;
import org.tornotron.echno_backend.holiday.dto.HolidayDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekDto;
import org.tornotron.echno_backend.holiday.dto.WorkingWeekUpdateDto;
import org.tornotron.echno_backend.holiday.mapper.HolidayMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The organization's holiday calendar and working week.
 *
 * <p>Holidays are organization-wide and one per date. The working week is a single row per
 * organization, written the first time it is set; until then every read answers Monday to
 * Friday, which is the week the leave accrual counted before the setting existed. Both are read by {@link WorkingCalendarService} when a
 * leave request is charged.
 */
@Service
@Validated
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final WorkingWeekRepository workingWeekRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final HolidayMapper holidayMapper;

    public HolidayService(HolidayRepository holidayRepository,
                          WorkingWeekRepository workingWeekRepository,
                          TenantEntityHelper tenantEntityHelper,
                          HolidayMapper holidayMapper) {
        this.holidayRepository = holidayRepository;
        this.workingWeekRepository = workingWeekRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.holidayMapper = holidayMapper;
    }

    /**
     * Declares a holiday for the current organization.
     *
     * @param dto The date, name and optional note.
     * @return The declared holiday.
     * @throws DuplicateResourceException if the organization already has a holiday on that date.
     */
    @Transactional
    public HolidayDto create(HolidayCreationDto dto) {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (holidayRepository.existsByOrganization_IdAndHolidayDate(organizationId, dto.getHolidayDate())) {
            throw new DuplicateResourceException(
                    "A holiday is already declared on " + dto.getHolidayDate() + " for this organization");
        }
        Holiday holiday = new Holiday();
        holiday.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        apply(holiday, dto);
        return holidayMapper.toDto(holidayRepository.save(holiday));
    }

    /**
     * Replaces a holiday's date, name and note.
     *
     * @param holidayId The holiday to change.
     * @param dto The new values.
     * @return The changed holiday.
     * @throws ResourceNotFoundException if no such holiday exists in this organization.
     * @throws DuplicateResourceException if another holiday already sits on the new date.
     */
    @Transactional
    public HolidayDto update(Long holidayId, HolidayCreationDto dto) {
        Holiday holiday = require(holidayId);
        if (holidayRepository.existsByOrganization_IdAndHolidayDateAndIdNot(
                TenantContext.getCurrentOrgId(), dto.getHolidayDate(), holidayId)) {
            throw new DuplicateResourceException(
                    "A holiday is already declared on " + dto.getHolidayDate() + " for this organization");
        }
        apply(holiday, dto);
        return holidayMapper.toDto(holidayRepository.save(holiday));
    }

    /**
     * Removes a holiday. Requests already charged under it keep their charged-day count; the
     * rule is applied when a request is raised or approved and never re-run on approved ones.
     *
     * @param holidayId The holiday to remove.
     * @throws ResourceNotFoundException if no such holiday exists in this organization.
     */
    @Transactional
    public void delete(Long holidayId) {
        holidayRepository.delete(require(holidayId));
    }

    /**
     * One holiday.
     *
     * @param holidayId The holiday.
     * @return The holiday.
     * @throws ResourceNotFoundException if no such holiday exists in this organization.
     */
    @Transactional(readOnly = true)
    public HolidayDto get(Long holidayId) {
        return holidayMapper.toDto(require(holidayId));
    }

    /**
     * Every holiday of the current organization in a calendar year, in date order.
     *
     * @param year The year.
     * @return The holidays.
     */
    @Transactional(readOnly = true)
    public List<HolidayDto> listForYear(int year) {
        return holidayRepository.findByOrganization_IdAndHolidayDateBetweenOrderByHolidayDateAsc(
                        TenantContext.getCurrentOrgId(), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .stream()
                .map(holidayMapper::toDto)
                .toList();
    }

    /**
     * The organization's working week: the stored row, or Monday to Friday when the
     * organization has never set one.
     *
     * <p>A read never writes the row. Two first reads racing to create it would collide on
     * {@code uk_working_week_org} and one member would see an error for asking a question; the
     * row is written only by {@link #updateWorkingWeek}, where a collision is a real conflict.
     *
     * @return The working week.
     */
    @Transactional(readOnly = true)
    public WorkingWeekDto getWorkingWeek() {
        Long organizationId = TenantContext.getCurrentOrgId();
        Set<DayOfWeek> days = workingWeekRepository.findByOrganization_Id(organizationId)
                .map(WorkingWeek::workingDaySet)
                .orElse(WorkingWeek.DEFAULT_WORKING_DAYS);
        WorkingWeekDto dto = new WorkingWeekDto();
        dto.setOrganizationId(organizationId);
        dto.setWorkingDays(days.stream().sorted().toList());
        return dto;
    }

    /**
     * Sets the organization's working days.
     *
     * @param dto The days worked; at least one, in any order, duplicates ignored.
     * @return The working week as stored.
     * @throws InvalidRequestException if no day is named.
     */
    @Transactional
    public WorkingWeekDto updateWorkingWeek(WorkingWeekUpdateDto dto) {
        if (dto.getWorkingDays() == null || dto.getWorkingDays().isEmpty()) {
            throw new InvalidRequestException("workingDays must name at least one day of the week");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        days.addAll(dto.getWorkingDays());
        WorkingWeek week = getOrCreateWorkingWeek();
        week.setWorkingDaySet(days);
        return toDto(workingWeekRepository.save(week));
    }

    /**
     * The working days of the current organization, for callers that need the set rather than
     * the DTO. Reads only, like {@link #getWorkingWeek}.
     *
     * @return The days worked, Monday to Friday when the organization has never set them.
     */
    @Transactional(readOnly = true)
    public Set<DayOfWeek> workingDays() {
        return workingWeekRepository.findByOrganization_Id(TenantContext.getCurrentOrgId())
                .map(WorkingWeek::workingDaySet)
                .orElse(WorkingWeek.DEFAULT_WORKING_DAYS);
    }

    /** The stored row, or a new unsaved one for the caller to fill and save. */
    private WorkingWeek getOrCreateWorkingWeek() {
        Long organizationId = TenantContext.getCurrentOrgId();
        return workingWeekRepository.findByOrganization_Id(organizationId).orElseGet(() -> {
            WorkingWeek week = new WorkingWeek();
            week.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
            return week;
        });
    }

    private WorkingWeekDto toDto(WorkingWeek week) {
        WorkingWeekDto dto = new WorkingWeekDto();
        dto.setOrganizationId(TenantContext.getCurrentOrgId());
        dto.setWorkingDays(week.workingDaySet().stream().sorted().toList());
        return dto;
    }

    private Holiday require(Long holidayId) {
        return holidayRepository.findByIdAndOrganization_Id(holidayId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Holiday with ID " + holidayId + " was not found in this organization"));
    }

    private static void apply(Holiday holiday, HolidayCreationDto dto) {
        holiday.setHolidayDate(dto.getHolidayDate());
        holiday.setName(dto.getName().trim());
        holiday.setDescription(dto.getDescription() == null || dto.getDescription().isBlank()
                ? null : dto.getDescription().trim());
    }
}
