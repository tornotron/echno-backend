package org.tornotron.echno_backend.attendance.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingCreationDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingPatchDto;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ShiftTimingService {

    private final ShiftTimingRepository shiftTimingRepository;
    private final OrganizationRepository organizationRepository;
    private final ShiftTimingMapper shiftTimingMapper;

    public ShiftTimingService(ShiftTimingRepository shiftTimingRepository,
                              OrganizationRepository organizationRepository,
                              ShiftTimingMapper shiftTimingMapper) {
        this.shiftTimingRepository = shiftTimingRepository;
        this.organizationRepository = organizationRepository;
        this.shiftTimingMapper = shiftTimingMapper;
    }

    @Transactional
    public ShiftTimingDto createShiftTiming(ShiftTimingCreationDto dto) {
        Organization org = organizationRepository.findById(TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        ShiftTiming shiftTiming = ShiftTiming.builder()
                .shiftName(dto.getShiftName())
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .lunchBreakStart(dto.getLunchBreakStart())
                .lunchBreakEnd(dto.getLunchBreakEnd())
                .gracePeriodMinutes(dto.getGracePeriodMinutes())
                .minimumWorkHours(dto.getMinimumWorkHours())
                .halfDayWorkHours(dto.getHalfDayWorkHours())
                .overtimeThreshold(dto.getOvertimeThreshold())
                .organization(org)
                .build();

        return shiftTimingMapper.toDto(shiftTimingRepository.save(shiftTiming));
    }

    @Transactional(readOnly = true)
    public List<ShiftTimingDto> getAllShiftTimings() {
        return shiftTimingRepository.findByOrganizationId(TenantContext.getCurrentOrgId())
                .stream()
                .map(shiftTimingMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ShiftTimingDto getShiftTimingById(Long id) {
        ShiftTiming shiftTiming = shiftTimingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift timing not found"));
        return shiftTimingMapper.toDto(shiftTiming);
    }

    @Transactional
    public ShiftTimingDto updateShiftTiming(Long id, ShiftTimingPatchDto dto) {
        ShiftTiming shiftTiming = shiftTimingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift timing not found"));

        if (dto.getShiftName() != null) shiftTiming.setShiftName(dto.getShiftName());
        if (dto.getStartTime() != null) shiftTiming.setStartTime(dto.getStartTime());
        if (dto.getEndTime() != null) shiftTiming.setEndTime(dto.getEndTime());
        if (dto.getLunchBreakStart() != null) shiftTiming.setLunchBreakStart(dto.getLunchBreakStart());
        if (dto.getLunchBreakEnd() != null) shiftTiming.setLunchBreakEnd(dto.getLunchBreakEnd());
        if (dto.getGracePeriodMinutes() != null) shiftTiming.setGracePeriodMinutes(dto.getGracePeriodMinutes());
        if (dto.getMinimumWorkHours() != null) shiftTiming.setMinimumWorkHours(dto.getMinimumWorkHours());
        if (dto.getHalfDayWorkHours() != null) shiftTiming.setHalfDayWorkHours(dto.getHalfDayWorkHours());
        if (dto.getOvertimeThreshold() != null) shiftTiming.setOvertimeThreshold(dto.getOvertimeThreshold());

        return shiftTimingMapper.toDto(shiftTimingRepository.save(shiftTiming));
    }

    @Transactional
    public void deleteShiftTiming(Long id) {
        ShiftTiming shiftTiming = shiftTimingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift timing not found"));
        shiftTimingRepository.delete(shiftTiming);
    }
}
