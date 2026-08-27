package org.tornotron.echno_backend.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire contract for a movement record's start and end times, the same way
 * {@link AttendanceTimestampContractTest} does for a clock punch.
 *
 * <p>Both fields are {@link LocalDateTime} and carry no offset, and the web form feeds
 * them from a {@code datetime-local} input, which is a local wall clock by definition.
 * Jackson's default leniency accepted an offset-bearing value and discarded the offset,
 * shifting the recorded time. These tests assert it is rejected instead.
 *
 * <p>Plain Jackson, no Spring context: the behaviour under test comes from the field
 * annotations, not the container.
 */
class MovementTimestampContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    @DisplayName("accepts local wall-clock start and end times verbatim")
    void acceptsLocalWallClock() throws Exception {
        String json = """
                {"attendanceId":5,"movementType":"SITE_TRAVEL","fromLocation":"Site","purpose":"Vendor",\
                "startTime":"2026-08-27T11:00:00","endTime":"2026-08-27T12:30:00"}""";

        MovementRecordCreationDto dto = objectMapper.readValue(json, MovementRecordCreationDto.class);

        assertThat(dto.getStartTime()).isEqualTo(LocalDateTime.of(2026, 8, 27, 11, 0, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalDateTime.of(2026, 8, 27, 12, 30, 0));
    }

    @Test
    @DisplayName("rejects a UTC startTime instead of silently dropping the Z")
    void rejectsOffsetBearingStartTime() {
        String json = """
                {"attendanceId":5,"movementType":"SITE_TRAVEL","fromLocation":"Site","purpose":"Vendor",\
                "startTime":"2026-08-27T05:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, MovementRecordCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("rejects a UTC endTime instead of silently dropping the Z")
    void rejectsOffsetBearingEndTime() {
        String json = """
                {"attendanceId":5,"movementType":"SITE_TRAVEL","fromLocation":"Site","purpose":"Vendor",\
                "startTime":"2026-08-27T11:00:00","endTime":"2026-08-27T07:00:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, MovementRecordCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("a null endTime is still allowed, since a movement can be open")
    void allowsNullEndTime() throws Exception {
        String json = """
                {"attendanceId":5,"movementType":"SITE_TRAVEL","fromLocation":"Site","purpose":"Vendor",\
                "startTime":"2026-08-27T11:00:00"}""";

        MovementRecordCreationDto dto = objectMapper.readValue(json, MovementRecordCreationDto.class);

        assertThat(dto.getEndTime()).isNull();
    }
}
