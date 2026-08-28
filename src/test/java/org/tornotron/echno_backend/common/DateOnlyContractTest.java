package org.tornotron.echno_backend.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;
import org.tornotron.echno_backend.project.dto.ProjectCreationDto;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.user.dto.UserRegistrationDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire contract for the date-only fields, the same way
 * {@code AttendanceTimestampContractTest} does for a clock punch.
 *
 * <p>These fields are calendar dates held in a {@link LocalDateTime}: a task's start and end, a
 * project's start and end, a date of birth, a joining date. Each is entered through an
 * {@code <input type="date">} and carries no time of day, and the column has no offset. Jackson's
 * default leniency accepted an offset-bearing value and discarded the offset, which for a date at
 * local midnight moves it to the neighbouring day.
 *
 * <p>Plain Jackson, no Spring context: the behaviour under test comes from the field annotations,
 * not the container.
 */
class DateOnlyContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    @DisplayName("task create accepts local calendar dates verbatim")
    void taskAcceptsLocalDates() throws Exception {
        String json = """
                {"title":"Column casting","startDate":"2026-08-27T00:00:00","endDate":"2026-09-30T00:00:00"}""";

        TaskCreationDto dto = objectMapper.readValue(json, TaskCreationDto.class);

        assertThat(dto.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 27, 0, 0));
        assertThat(dto.getEndDate()).isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 0));
    }

    @Test
    @DisplayName("task create rejects a UTC startDate rather than moving the day")
    void taskRejectsUtcStartDate() {
        String json = """
                {"title":"Column casting","startDate":"2026-08-26T18:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, TaskCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("project create rejects a UTC startDate")
    void projectRejectsUtcStartDate() {
        String json = """
                {"projectName":"Marina Towers","startDate":"2026-08-26T18:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, ProjectCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("employee create rejects a UTC dateOfBirth")
    void employeeRejectsUtcDateOfBirth() {
        String json = """
                {"employeeName":"A Worker","dateOfBirth":"1990-08-21T18:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, EmployeeCreationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("registration rejects a UTC dateOfBirth")
    void registrationRejectsUtcDateOfBirth() {
        String json = """
                {"userName":"aworker","dateOfBirth":"1990-08-21T18:30:00.000Z"}""";

        assertThatThrownBy(() -> objectMapper.readValue(json, UserRegistrationDto.class))
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("employee create keeps a date of birth on its own day")
    void employeeKeepsTheDate() throws Exception {
        String json = """
                {"employeeName":"A Worker","dateOfBirth":"1990-08-22T00:00:00"}""";

        EmployeeCreationDto dto = objectMapper.readValue(json, EmployeeCreationDto.class);

        assertThat(dto.getDateOfBirth().toLocalDate()).isEqualTo(java.time.LocalDate.of(1990, 8, 22));
    }
}
