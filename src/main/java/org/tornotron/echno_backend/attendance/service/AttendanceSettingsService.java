package org.tornotron.echno_backend.attendance.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.AttendanceSettingsRepository;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsCreationDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsPatchDto;
import org.tornotron.echno_backend.attendance.mapper.AttendanceSettingsMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AttendanceSettingsService {

    private final AttendanceSettingsRepository settingsRepository;
    private final ShiftTimingRepository shiftTimingRepository;
    private final OrganizationRepository organizationRepository;
    private final AttendanceSettingsMapper settingsMapper;

    public AttendanceSettingsService(AttendanceSettingsRepository settingsRepository,
                                     ShiftTimingRepository shiftTimingRepository,
                                     OrganizationRepository organizationRepository,
                                     AttendanceSettingsMapper settingsMapper) {
        this.settingsRepository = settingsRepository;
        this.shiftTimingRepository = shiftTimingRepository;
        this.organizationRepository = organizationRepository;
        this.settingsMapper = settingsMapper;
    }

    /**
     * Resolves the effective settings for a given project.
     * Falls back to org-wide defaults if no project-specific config exists.
     */
    public AttendanceSettings resolveEffectiveSettings(Long orgId, Long projectId) {
        return settingsRepository
                .findByOrganizationIdAndProjectIdAndIsActiveTrue(orgId, projectId)
                .orElseGet(() -> settingsRepository
                        .findByOrganizationIdAndProjectIdIsNullAndIsActiveTrue(orgId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No attendance settings are configured for organization " + orgId
                                        + " and no org-wide default exists")));
    }

    @Transactional
    public AttendanceSettingsDto createSettings(AttendanceSettingsCreationDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));

        ShiftTiming shift = null;
        if (dto.getDefaultShiftTimingId() != null) {
            shift = shiftTimingRepository.findByIdAndOrganization_Id(dto.getDefaultShiftTimingId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift timing with ID " + dto.getDefaultShiftTimingId() + " was not found in this organization"));
        }

        AttendanceSettings settings = AttendanceSettings.builder()
                .organization(org)
                .projectId(dto.getProjectId())
                .settingName(dto.getSettingName())
                .checkInOutCycles(dto.getCheckInOutCycles())
                .photoRequiredOnCheckIn(dto.getPhotoRequiredOnCheckIn())
                .photoRequiredOnCheckOut(dto.getPhotoRequiredOnCheckOut())
                .geolocationRequired(dto.getGeolocationRequired())
                .geofenceRadiusMeters(dto.getGeofenceRadiusMeters())
                .movementTrackingEnabled(dto.getMovementTrackingEnabled())
                .movementPhotoRequired(dto.getMovementPhotoRequired())
                .movementGeolocationRequired(dto.getMovementGeolocationRequired())
                .autoMarkAbsentAfterHours(dto.getAutoMarkAbsentAfterHours())
                .allowSelfRegularization(dto.getAllowSelfRegularization())
                .regularizationApprovalRequired(dto.getRegularizationApprovalRequired())
                .maxRegularizationDaysPerMonth(dto.getMaxRegularizationDaysPerMonth())
                .defaultShiftTiming(shift)
                .isActive(true)
                .build();

        return settingsMapper.toDto(settingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public AttendanceSettingsDto getOrgSettings() {
        AttendanceSettings settings = settingsRepository
                .findByOrganizationIdAndProjectIdIsNullAndIsActiveTrue(TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No org-wide attendance settings are configured for this organization"));
        return settingsMapper.toDto(settings);
    }

    @Transactional(readOnly = true)
    public AttendanceSettingsDto getProjectSettings(Long projectId) {
        AttendanceSettings settings = resolveEffectiveSettings(TenantContext.getCurrentOrgId(), projectId);
        return settingsMapper.toDto(settings);
    }

    @Transactional(readOnly = true)
    public List<AttendanceSettingsDto> getAllSettings() {
        return settingsRepository.findByOrganizationIdAndIsActiveTrue(TenantContext.getCurrentOrgId())
                .stream()
                .map(settingsMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public AttendanceSettingsDto updateSettings(Long id, AttendanceSettingsPatchDto dto) {
        AttendanceSettings settings = settingsRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance settings with ID " + id + " were not found"));

        if (dto.getSettingName() != null) settings.setSettingName(dto.getSettingName());
        if (dto.getCheckInOutCycles() != null) settings.setCheckInOutCycles(dto.getCheckInOutCycles());
        if (dto.getPhotoRequiredOnCheckIn() != null) settings.setPhotoRequiredOnCheckIn(dto.getPhotoRequiredOnCheckIn());
        if (dto.getPhotoRequiredOnCheckOut() != null) settings.setPhotoRequiredOnCheckOut(dto.getPhotoRequiredOnCheckOut());
        if (dto.getGeolocationRequired() != null) settings.setGeolocationRequired(dto.getGeolocationRequired());
        if (dto.getGeofenceRadiusMeters() != null) settings.setGeofenceRadiusMeters(dto.getGeofenceRadiusMeters());
        if (dto.getMovementTrackingEnabled() != null) settings.setMovementTrackingEnabled(dto.getMovementTrackingEnabled());
        if (dto.getMovementPhotoRequired() != null) settings.setMovementPhotoRequired(dto.getMovementPhotoRequired());
        if (dto.getMovementGeolocationRequired() != null) settings.setMovementGeolocationRequired(dto.getMovementGeolocationRequired());
        if (dto.getAutoMarkAbsentAfterHours() != null) settings.setAutoMarkAbsentAfterHours(dto.getAutoMarkAbsentAfterHours());
        if (dto.getAllowSelfRegularization() != null) settings.setAllowSelfRegularization(dto.getAllowSelfRegularization());
        if (dto.getRegularizationApprovalRequired() != null) settings.setRegularizationApprovalRequired(dto.getRegularizationApprovalRequired());
        if (dto.getMaxRegularizationDaysPerMonth() != null) settings.setMaxRegularizationDaysPerMonth(dto.getMaxRegularizationDaysPerMonth());

        if (dto.getDefaultShiftTimingId() != null) {
            ShiftTiming shift = shiftTimingRepository.findByIdAndOrganization_Id(dto.getDefaultShiftTimingId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Shift timing with ID " + dto.getDefaultShiftTimingId() + " was not found in this organization"));
            settings.setDefaultShiftTiming(shift);
        }

        return settingsMapper.toDto(settingsRepository.save(settings));
    }

    @Transactional
    public void deactivateSettings(Long id) {
        AttendanceSettings settings = settingsRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Attendance settings with ID " + id + " were not found"));
        settings.setIsActive(false);
        settingsRepository.save(settings);
    }
}
