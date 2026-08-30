package org.tornotron.echno_backend.attendance.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.MovementRecord;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceSettingsDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordDto;
import org.tornotron.echno_backend.attendance.dto.ShiftTimingDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.MovementType;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins what the attendance conversions produce, field by field.
 *
 * <p>Six of the seven hand-written mappers issue #522 counted were in this package: 356 lines of
 * builder chains doing what the MapStruct processor writes. This test is the safety net the
 * conversion was done under. It asserts every field of every one of the six DTOs, so it is a
 * statement of what the response carries rather than of how the mapper is written, and it passes
 * on either implementation. Written against the hand-written mappers first, it is what says the
 * generated ones produce the same objects; if a conversion had quietly dropped a field, this is
 * where it would have shown.
 *
 * <p>The one deliberate change it records is on a clock event's attachments. The hand-written
 * mapper carried a second copy of the attachment conversion, taking {@code FileStorageService} as
 * a parameter and filling seven of the DTO's twelve fields; the shared {@code AttachmentMapper}
 * fills all of them. The same file therefore used to describe itself one way hanging off a clock
 * event and another way everywhere else, and now does not.
 *
 * <p>No Spring context: the generated implementations are built directly and their collaborators
 * set on them, which is what the container does.
 */
class AttendanceMapperConversionTest {

    private static final String SIGNED_URL = "https://store.example/echno/signed?sig=abc";

    private ShiftTimingMapper shiftTimingMapper;
    private AttendanceSettingsMapper attendanceSettingsMapper;
    private ClockEventMapper clockEventMapper;
    private MovementRecordMapper movementRecordMapper;
    private AttendanceRegularizationMapper regularizationMapper;
    private AttendanceMapper attendanceMapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .thenReturn(SIGNED_URL);
        AttachmentMapper attachmentMapper = new AttachmentMapperImpl();
        ReflectionTestUtils.setField(attachmentMapper, "fileStorageService", fileStorageService);

        shiftTimingMapper = new ShiftTimingMapperImpl();

        attendanceSettingsMapper = new AttendanceSettingsMapperImpl();
        ReflectionTestUtils.setField(attendanceSettingsMapper, "shiftTimingMapper", shiftTimingMapper);

        clockEventMapper = new ClockEventMapperImpl();
        ReflectionTestUtils.setField(clockEventMapper, "attachmentMapper", attachmentMapper);

        movementRecordMapper = new MovementRecordMapperImpl();
        ReflectionTestUtils.setField(movementRecordMapper, "objectMapper", objectMapper);

        regularizationMapper = new AttendanceRegularizationMapperImpl();
        ReflectionTestUtils.setField(regularizationMapper, "objectMapper", objectMapper);

        attendanceMapper = new AttendanceMapperImpl();
        ReflectionTestUtils.setField(attendanceMapper, "shiftTimingMapper", shiftTimingMapper);
        ReflectionTestUtils.setField(attendanceMapper, "clockEventMapper", clockEventMapper);
        ReflectionTestUtils.setField(attendanceMapper, "attendanceRegularizationMapper", regularizationMapper);
        ReflectionTestUtils.setField(attendanceMapper, "movementRecordMapper", movementRecordMapper);
    }

    // ---------------------------------------------------------------- shift timing

    @Test
    void shiftTiming_mapsEveryField() {
        ShiftTimingDto dto = shiftTimingMapper.toDto(shiftTiming());

        assertThat(dto.getId()).isEqualTo(4L);
        assertThat(dto.getShiftName()).isEqualTo("General");
        assertThat(dto.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(dto.getLunchBreakStart()).isEqualTo(LocalTime.of(13, 0));
        assertThat(dto.getLunchBreakEnd()).isEqualTo(LocalTime.of(13, 45));
        assertThat(dto.getGracePeriodMinutes()).isEqualTo(10);
        assertThat(dto.getMinimumWorkHours()).isEqualByComparingTo("8.00");
        assertThat(dto.getHalfDayWorkHours()).isEqualByComparingTo("4.00");
        assertThat(dto.getOvertimeThreshold()).isEqualByComparingTo("9.00");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 8, 0));
        assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 2, 1, 8, 0));
    }

    @Test
    void shiftTiming_isNullForANullEntity() {
        assertThat(shiftTimingMapper.toDto(null)).isNull();
    }

    // ---------------------------------------------------------------- settings

    @Test
    void settings_mapsEveryFieldAndFlattensTheOrganization() {
        AttendanceSettingsDto dto = attendanceSettingsMapper.toDto(settings());

        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getOrganizationId()).isEqualTo(7L);
        assertThat(dto.getProjectId()).isEqualTo(3L);
        assertThat(dto.getSettingName()).isEqualTo("Site default");
        assertThat(dto.getCheckInOutCycles()).isEqualTo(2);
        assertThat(dto.getPhotoRequiredOnCheckIn()).isTrue();
        assertThat(dto.getPhotoRequiredOnCheckOut()).isFalse();
        assertThat(dto.getGeolocationRequired()).isTrue();
        assertThat(dto.getGeofenceRadiusMeters()).isEqualTo(150);
        assertThat(dto.getMovementTrackingEnabled()).isTrue();
        assertThat(dto.getMovementPhotoRequired()).isFalse();
        assertThat(dto.getMovementGeolocationRequired()).isTrue();
        assertThat(dto.getAutoMarkAbsentAfterHours()).isEqualTo(5);
        assertThat(dto.getAllowSelfRegularization()).isTrue();
        assertThat(dto.getRegularizationApprovalRequired()).isFalse();
        assertThat(dto.getMaxRegularizationDaysPerMonth()).isEqualTo(4);
        assertThat(dto.getDefaultShiftTiming()).isNotNull();
        assertThat(dto.getDefaultShiftTiming().getShiftName()).isEqualTo("General");
        assertThat(dto.getIsActive()).isTrue();
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 8, 0));
        assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 2, 2, 8, 0));
    }

    @Test
    void settings_organizationIdIsNullWhenTheSettingHasNoOrganization() {
        AttendanceSettings settings = settings();
        settings.setOrganization(null);

        assertThat(attendanceSettingsMapper.toDto(settings).getOrganizationId()).isNull();
    }

    @Test
    void settings_isNullForANullEntity() {
        assertThat(attendanceSettingsMapper.toDto(null)).isNull();
    }

    // ---------------------------------------------------------------- clock event

    @Test
    void clockEvent_mapsEveryFieldAndSignsItsAttachments() {
        ClockEventDto dto = clockEventMapper.toDto(clockEvent());

        assertThat(dto.getId()).isEqualTo(21L);
        assertThat(dto.getEventType()).isEqualTo(ClockEventType.MORNING_CLOCK_IN);
        assertThat(dto.getEventTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 5));
        assertThat(dto.getLatitude()).isEqualTo(13.0827);
        assertThat(dto.getLongitude()).isEqualTo(80.2707);
        assertThat(dto.getGpsAccuracy()).isEqualTo(6.5);
        assertThat(dto.getProjectId()).isEqualTo(3L);
        assertThat(dto.getProjectName()).isEqualTo("Tower B");
        assertThat(dto.getDevicePlatform()).isEqualTo("android");
        assertThat(dto.getIsWithinGeofence()).isTrue();
        assertThat(dto.getDistanceFromProject()).isEqualTo(12.0);
        assertThat(dto.getRemarks()).isEqualTo("on time");
        assertThat(dto.getVerifiedBy()).isEqualTo("Supervisor");
        assertThat(dto.getVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 10, 0));
        assertThat(dto.getIsRegularized()).isFalse();
        assertThat(dto.getRegularizationReason()).isNull();

        // The entity has no photo URL of its own; the photos are the attachments.
        assertThat(dto.getPhotoUrl()).isNull();

        assertThat(dto.getAttachments()).hasSize(1);
        assertThat(dto.getAttachments().get(0).getId()).isEqualTo(88L);
        assertThat(dto.getAttachments().get(0).getUrl()).isEqualTo(SIGNED_URL);
        assertThat(dto.getAttachments().get(0).getEntityType()).isEqualTo("CLOCK_EVENT");
        assertThat(dto.getAttachments().get(0).getContentType()).isEqualTo("image/jpeg");
        assertThat(dto.getAttachments().get(0).getFileSize()).isEqualTo(2048L);
        assertThat(dto.getAttachments().get(0).getFileName()).isEqualTo("checkin.jpg");
        assertThat(dto.getAttachments().get(0).getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 5).toString());
        assertThat(dto.getAttachments().get(0).getUpdatedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 6).toString());
    }

    @Test
    void clockEvent_attachmentsNowCarryTheDocumentFieldsEveryOtherAttachmentCarries() {
        // The hand-written copy of the attachment conversion filled seven of the twelve fields, so
        // a document hanging off a clock event described itself differently from the same document
        // hanging off anything else. Delegating to the shared mapper settles that.
        ClockEventDto dto = clockEventMapper.toDto(clockEvent());

        assertThat(dto.getAttachments().get(0).getDocumentType()).isEqualTo("site-photo");
        assertThat(dto.getAttachments().get(0).getIssuedOn()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(dto.getAttachments().get(0).getExpiresOn()).isEqualTo(LocalDate.of(2030, 3, 1));
        assertThat(dto.getAttachments().get(0).getExpired()).isFalse();
        assertThat(dto.getAttachments().get(0).getDaysUntilExpiry()).isPositive();
    }

    @Test
    void clockEvent_isNullForANullEntity() {
        assertThat(clockEventMapper.toDto(null)).isNull();
    }

    // ---------------------------------------------------------------- movement record

    @Test
    void movementRecord_mapsEveryFieldAndReadsItsAttachmentsBackOutOfJson() {
        MovementRecordDto dto = movementRecordMapper.toDto(movementRecord());

        assertThat(dto.getId()).isEqualTo(31L);
        assertThat(dto.getAttendanceId()).isEqualTo(51L);
        assertThat(dto.getEmployeeId()).isEqualTo(18L);
        assertThat(dto.getEmployeeName()).isEqualTo("Ramesh Kumar");
        assertThat(dto.getMovementType()).isEqualTo(MovementType.SITE_TRAVEL);
        assertThat(dto.getFromLocation()).isEqualTo("Site A");
        assertThat(dto.getToLocation()).isEqualTo("Site B");
        assertThat(dto.getStartTime()).isEqualTo(LocalDateTime.of(2026, 3, 4, 11, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalDateTime.of(2026, 3, 4, 12, 30));
        assertThat(dto.getDurationMinutes()).isEqualTo(90);
        assertThat(dto.getDistanceKm()).isEqualTo(14.2);
        assertThat(dto.getPurpose()).isEqualTo("material inspection");
        assertThat(dto.getRemarks()).isEqualTo("took the van");
        assertThat(dto.getStartLatitude()).isEqualTo(13.01);
        assertThat(dto.getStartLongitude()).isEqualTo(80.21);
        assertThat(dto.getEndLatitude()).isEqualTo(13.09);
        assertThat(dto.getEndLongitude()).isEqualTo(80.29);
        assertThat(dto.getAttachments()).containsExactly("first.jpg", "second.jpg");
        assertThat(dto.getVerifiedBy()).isEqualTo("Supervisor");
        assertThat(dto.getVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 13, 0));
        assertThat(dto.getIsVerified()).isTrue();
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 11, 0));
        assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 13, 0));
    }

    @Test
    void movementRecord_readsUnusableAttachmentJsonAsNoAttachments() {
        // Stored JSON that cannot be read is not worth a 500 on a listing: the movement itself is
        // still a fact, and this is what the hand-written mapper did.
        MovementRecord record = movementRecord();

        record.setAttachments(null);
        assertThat(movementRecordMapper.toDto(record).getAttachments()).isEmpty();

        record.setAttachments("   ");
        assertThat(movementRecordMapper.toDto(record).getAttachments()).isEmpty();

        record.setAttachments("{not json");
        assertThat(movementRecordMapper.toDto(record).getAttachments()).isEmpty();
    }

    @Test
    void movementRecord_attendanceIdIsNullWhenTheRecordHasNoAttendance() {
        MovementRecord record = movementRecord();
        record.setAttendance(null);

        assertThat(movementRecordMapper.toDto(record).getAttendanceId()).isNull();
    }

    @Test
    void movementRecord_roundTripsItsAttachmentsThroughJson() {
        assertThat(movementRecordMapper.serializeAttachments(List.of("a.jpg", "b.jpg")))
                .isEqualTo("[\"a.jpg\",\"b.jpg\"]");
        assertThat(movementRecordMapper.serializeAttachments(List.of())).isNull();
        assertThat(movementRecordMapper.serializeAttachments(null)).isNull();
        assertThat(movementRecordMapper.deserializeAttachments("[\"a.jpg\"]")).containsExactly("a.jpg");
    }

    @Test
    void movementRecord_isNullForANullEntity() {
        assertThat(movementRecordMapper.toDto(null)).isNull();
    }

    // ---------------------------------------------------------------- regularization

    @Test
    void regularization_mapsEveryFieldAndReadsItsMissingEventsBackOutOfJson() {
        AttendanceRegularizationDto dto = regularizationMapper.toDto(regularization());

        assertThat(dto.getId()).isEqualTo(41L);
        assertThat(dto.getAttendanceId()).isEqualTo(51L);
        assertThat(dto.getReason()).isEqualTo("phone battery died");
        assertThat(dto.getRequestedBy()).isEqualTo("Ramesh Kumar");
        assertThat(dto.getRequestedById()).isEqualTo(18L);
        assertThat(dto.getRequestedAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 9, 0));
        assertThat(dto.getApprovedBy()).isEqualTo("Supervisor");
        assertThat(dto.getApprovedById()).isEqualTo(19L);
        assertThat(dto.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 10, 0));
        assertThat(dto.getStatus()).isEqualTo(RegularizationStatus.APPROVED);
        assertThat(dto.getRejectionReason()).isNull();
        assertThat(dto.getMissingEvents()).containsExactly("EVENING_CLOCK_OUT");
    }

    @Test
    void regularization_readsUnusableMissingEventJsonAsNone() {
        AttendanceRegularization entity = regularization();

        entity.setMissingEvents(null);
        assertThat(regularizationMapper.toDto(entity).getMissingEvents()).isEmpty();

        entity.setMissingEvents("{not json");
        assertThat(regularizationMapper.toDto(entity).getMissingEvents()).isEmpty();
    }

    @Test
    void regularization_attendanceIdIsNullWhenTheRequestHasNoAttendance() {
        AttendanceRegularization entity = regularization();
        entity.setAttendance(null);

        assertThat(regularizationMapper.toDto(entity).getAttendanceId()).isNull();
    }

    @Test
    void regularization_roundTripsTheCorrectedEventsItStoresUntilApproval() {
        ClockEventCreationDto correction = new ClockEventCreationDto();
        correction.setEventType(ClockEventType.EVENING_CLOCK_OUT);

        String stored = regularizationMapper.serializeRequestedEvents(List.of(correction));
        assertThat(stored).isNotNull();
        assertThat(regularizationMapper.deserializeRequestedEvents(stored))
                .singleElement()
                .satisfies(read -> assertThat(read.getEventType()).isEqualTo(ClockEventType.EVENING_CLOCK_OUT));

        assertThat(regularizationMapper.serializeRequestedEvents(List.of())).isNull();
        assertThat(regularizationMapper.serializeRequestedEvents(null)).isNull();
        assertThat(regularizationMapper.deserializeRequestedEvents("{not json")).isEmpty();
    }

    @Test
    void regularization_isNullForANullEntity() {
        assertThat(regularizationMapper.toDto(null)).isNull();
    }

    // ---------------------------------------------------------------- attendance

    @Test
    void attendance_mapsEveryFieldAndEveryNestedCollection() {
        AttendanceResponseDto dto = attendanceMapper.toResponseDto(attendance());

        assertThat(dto.getId()).isEqualTo(51L);
        assertThat(dto.getEmployeeId()).isEqualTo(18L);
        assertThat(dto.getEmployeeName()).isEqualTo("Ramesh Kumar");
        assertThat(dto.getAttendanceDate()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(dto.getProjectId()).isEqualTo(3L);
        assertThat(dto.getProjectName()).isEqualTo("Tower B");
        assertThat(dto.getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dto.getShiftTiming()).isNotNull();
        assertThat(dto.getShiftTiming().getShiftName()).isEqualTo("General");
        assertThat(dto.getClockEvents()).hasSize(1);
        assertThat(dto.getClockEvents().get(0).getId()).isEqualTo(21L);
        assertThat(dto.getClockEvents().get(0).getAttachments()).hasSize(1);
        assertThat(dto.getTotalWorkMinutes()).isEqualTo(480);
        assertThat(dto.getMorningSessionMinutes()).isEqualTo(240);
        assertThat(dto.getAfternoonSessionMinutes()).isEqualTo(240);
        assertThat(dto.getOvertimeMinutes()).isEqualTo(30);
        assertThat(dto.getBreakDurationMinutes()).isEqualTo(45);
        assertThat(dto.getIsLateArrival()).isFalse();
        assertThat(dto.getIsEarlyCheckout()).isFalse();
        assertThat(dto.getIsOvertime()).isTrue();
        assertThat(dto.getLeaveId()).isEqualTo(61L);
        assertThat(dto.getLeaveType()).isEqualTo("CASUAL");
        assertThat(dto.getRegularizations()).hasSize(1);
        assertThat(dto.getRegularizations().get(0).getId()).isEqualTo(41L);
        assertThat(dto.getMovements()).hasSize(1);
        assertThat(dto.getMovements().get(0).getId()).isEqualTo(31L);
        assertThat(dto.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(dto.getApprovedBy()).isEqualTo("Supervisor");
        assertThat(dto.getApprovedById()).isEqualTo(19L);
        assertThat(dto.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 19, 0));
        assertThat(dto.getRemarks()).isEqualTo("full day");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 0));
        assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 19, 0));
    }

    @Test
    void attendance_leavesANullCollectionNullRatherThanEmpty() {
        // The hand-written mapper distinguished the two, and a client reading "no clock events"
        // differently from "clock events not loaded" would notice the change.
        Attendance attendance = attendance();
        attendance.setClockEvents(null);
        attendance.setRegularizations(null);
        attendance.setMovements(null);

        AttendanceResponseDto dto = attendanceMapper.toResponseDto(attendance);

        assertThat(dto.getClockEvents()).isNull();
        assertThat(dto.getRegularizations()).isNull();
        assertThat(dto.getMovements()).isNull();
    }

    @Test
    void attendance_isNullForANullEntity() {
        assertThat(attendanceMapper.toResponseDto(null)).isNull();
    }

    // ---------------------------------------------------------------- fixtures

    private static ShiftTiming shiftTiming() {
        ShiftTiming shift = new ShiftTiming();
        shift.setId(4L);
        shift.setShiftName("General");
        shift.setStartTime(LocalTime.of(9, 0));
        shift.setEndTime(LocalTime.of(18, 0));
        shift.setLunchBreakStart(LocalTime.of(13, 0));
        shift.setLunchBreakEnd(LocalTime.of(13, 45));
        shift.setGracePeriodMinutes(10);
        shift.setMinimumWorkHours(new BigDecimal("8.00"));
        shift.setHalfDayWorkHours(new BigDecimal("4.00"));
        shift.setOvertimeThreshold(new BigDecimal("9.00"));
        shift.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        shift.setUpdatedAt(LocalDateTime.of(2026, 2, 1, 8, 0));
        return shift;
    }

    private static AttendanceSettings settings() {
        Organization organization = new Organization();
        organization.setId(7L);

        AttendanceSettings settings = new AttendanceSettings();
        settings.setId(11L);
        settings.setOrganization(organization);
        settings.setProjectId(3L);
        settings.setSettingName("Site default");
        settings.setCheckInOutCycles(2);
        settings.setPhotoRequiredOnCheckIn(true);
        settings.setPhotoRequiredOnCheckOut(false);
        settings.setGeolocationRequired(true);
        settings.setGeofenceRadiusMeters(150);
        settings.setMovementTrackingEnabled(true);
        settings.setMovementPhotoRequired(false);
        settings.setMovementGeolocationRequired(true);
        settings.setAutoMarkAbsentAfterHours(5);
        settings.setAllowSelfRegularization(true);
        settings.setRegularizationApprovalRequired(false);
        settings.setMaxRegularizationDaysPerMonth(4);
        settings.setDefaultShiftTiming(shiftTiming());
        settings.setIsActive(true);
        settings.setCreatedAt(LocalDateTime.of(2026, 1, 2, 8, 0));
        settings.setUpdatedAt(LocalDateTime.of(2026, 2, 2, 8, 0));
        return settings;
    }

    private static Attachment attachment() {
        Attachment attachment = new Attachment();
        attachment.setId(88L);
        attachment.setStorageKey("clock-events/88/checkin.jpg");
        attachment.setEntityType("CLOCK_EVENT");
        attachment.setContentType("image/jpeg");
        attachment.setFileSize(2048L);
        attachment.setOriginalFilename("checkin.jpg");
        attachment.setCreatedAt(LocalDateTime.of(2026, 3, 4, 9, 5));
        attachment.setUpdatedAt(LocalDateTime.of(2026, 3, 4, 9, 6));
        attachment.setDocumentType("site-photo");
        attachment.setIssuedOn(LocalDate.of(2026, 3, 1));
        attachment.setExpiresOn(LocalDate.of(2030, 3, 1));
        return attachment;
    }

    private static ClockEvent clockEvent() {
        ClockEvent event = new ClockEvent();
        event.setId(21L);
        event.setEventType(ClockEventType.MORNING_CLOCK_IN);
        event.setEventTimestamp(LocalDateTime.of(2026, 3, 4, 9, 5));
        event.setLatitude(13.0827);
        event.setLongitude(80.2707);
        event.setGpsAccuracy(6.5);
        event.setProjectId(3L);
        event.setProjectName("Tower B");
        event.setDevicePlatform("android");
        event.setIsWithinGeofence(true);
        event.setDistanceFromProject(12.0);
        event.setRemarks("on time");
        event.setVerifiedBy("Supervisor");
        event.setVerifiedAt(LocalDateTime.of(2026, 3, 4, 10, 0));
        event.setIsRegularized(false);
        event.setAttachments(List.of(attachment()));
        return event;
    }

    private static MovementRecord movementRecord() {
        MovementRecord record = new MovementRecord();
        record.setId(31L);
        record.setAttendance(bareAttendance());
        record.setEmployeeId(18L);
        record.setEmployeeName("Ramesh Kumar");
        record.setMovementType(MovementType.SITE_TRAVEL);
        record.setFromLocation("Site A");
        record.setToLocation("Site B");
        record.setStartTime(LocalDateTime.of(2026, 3, 4, 11, 0));
        record.setEndTime(LocalDateTime.of(2026, 3, 4, 12, 30));
        record.setDurationMinutes(90);
        record.setDistanceKm(14.2);
        record.setPurpose("material inspection");
        record.setRemarks("took the van");
        record.setStartLatitude(13.01);
        record.setStartLongitude(80.21);
        record.setEndLatitude(13.09);
        record.setEndLongitude(80.29);
        record.setAttachments("[\"first.jpg\",\"second.jpg\"]");
        record.setVerifiedBy("Supervisor");
        record.setVerifiedAt(LocalDateTime.of(2026, 3, 4, 13, 0));
        record.setIsVerified(true);
        record.setCreatedAt(LocalDateTime.of(2026, 3, 4, 11, 0));
        record.setUpdatedAt(LocalDateTime.of(2026, 3, 4, 13, 0));
        return record;
    }

    private static AttendanceRegularization regularization() {
        AttendanceRegularization entity = new AttendanceRegularization();
        entity.setId(41L);
        entity.setAttendance(bareAttendance());
        entity.setReason("phone battery died");
        entity.setRequestedBy("Ramesh Kumar");
        entity.setRequestedById(18L);
        entity.setRequestedAt(LocalDateTime.of(2026, 3, 5, 9, 0));
        entity.setApprovedBy("Supervisor");
        entity.setApprovedById(19L);
        entity.setApprovedAt(LocalDateTime.of(2026, 3, 5, 10, 0));
        entity.setStatus(RegularizationStatus.APPROVED);
        entity.setMissingEvents("[\"EVENING_CLOCK_OUT\"]");
        return entity;
    }

    private static Attendance bareAttendance() {
        Attendance attendance = new Attendance();
        attendance.setId(51L);
        return attendance;
    }

    private static Attendance attendance() {
        Attendance attendance = bareAttendance();
        attendance.setEmployeeId(18L);
        attendance.setEmployeeName("Ramesh Kumar");
        attendance.setAttendanceDate(LocalDate.of(2026, 3, 4));
        attendance.setProjectId(3L);
        attendance.setProjectName("Tower B");
        attendance.setStatus(AttendanceStatus.PRESENT);
        attendance.setShiftTiming(shiftTiming());
        attendance.setClockEvents(List.of(clockEvent()));
        attendance.setTotalWorkMinutes(480);
        attendance.setMorningSessionMinutes(240);
        attendance.setAfternoonSessionMinutes(240);
        attendance.setOvertimeMinutes(30);
        attendance.setBreakDurationMinutes(45);
        attendance.setIsLateArrival(false);
        attendance.setIsEarlyCheckout(false);
        attendance.setIsOvertime(true);
        attendance.setLeaveId(61L);
        attendance.setLeaveType("CASUAL");
        attendance.setRegularizations(List.of(regularization()));
        attendance.setMovements(List.of(movementRecord()));
        attendance.setApprovalStatus(ApprovalStatus.APPROVED);
        attendance.setApprovedBy("Supervisor");
        attendance.setApprovedById(19L);
        attendance.setApprovedAt(LocalDateTime.of(2026, 3, 4, 19, 0));
        attendance.setRemarks("full day");
        attendance.setCreatedAt(LocalDateTime.of(2026, 3, 4, 9, 0));
        attendance.setUpdatedAt(LocalDateTime.of(2026, 3, 4, 19, 0));
        return attendance;
    }
}
