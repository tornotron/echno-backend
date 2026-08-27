package org.tornotron.echno_backend.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceClockEventDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire contract for the attendance clock timestamps.
 *
 * <p>{@code eventTimestamp} is a site-local wall-clock time. It is declared as a
 * {@link LocalDateTime}, which carries no offset, so an offset-bearing value has no
 * meaning here. Jackson's default leniency accepts one anyway and silently discards
 * the offset, which shifts the stored time by the caller's offset and, near midnight,
 * moves the derived attendance date onto the wrong day. These tests assert the payload
 * is rejected instead.
 *
 * <p>Plain Jackson, no Spring context: the behaviour under test comes from the field
 * annotations, not from the container.
 */
class AttendanceTimestampContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    @DisplayName("check-in accepts a local wall-clock timestamp verbatim")
    void checkInAcceptsLocalWallClock() throws Exception {
        String json = """
                {"employeeId":42,"projectId":12,"eventTimestamp":"2026-08-27T09:00:00"}""";

        AttendanceCheckInDto dto = objectMapper.readValue(json, AttendanceCheckInDto.class);

        assertThat(dto.getEventTimestamp()).isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0, 0));
    }

    @Test
    @DisplayName("check-in rejects a UTC timestamp instead of silently dropping the Z")
    void checkInRejectsOffsetBearingTimestamp() {
        String json = """
                {"employeeId":42,"projectId":12,"eventTimestamp":"2026-08-27T03:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, AttendanceCheckInDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("clock event rejects a UTC timestamp")
    void clockEventRejectsOffsetBearingTimestamp() {
        String json = """
                {"attendanceId":7,"eventType":"EVENING_CLOCK_OUT","eventTimestamp":"2026-08-27T12:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, AttendanceClockEventDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("regularization clock event rejects a UTC timestamp")
    void regularizationClockEventRejectsOffsetBearingTimestamp() {
        String json = """
                {"eventType":"EVENING_CLOCK_OUT","projectId":12,"eventTimestamp":"2026-08-27T12:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, ClockEventCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("a midnight-adjacent local timestamp keeps its own calendar date")
    void midnightAdjacentTimestampKeepsItsDate() throws Exception {
        String json = """
                {"employeeId":42,"projectId":12,"eventTimestamp":"2026-08-27T00:30:00"}""";

        AttendanceCheckInDto dto = objectMapper.readValue(json, AttendanceCheckInDto.class);

        assertThat(dto.getEventTimestamp().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 27));
    }
}
