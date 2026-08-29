package org.tornotron.echno_backend.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.tornotron.echno_backend.employee.dto.EmployeeCreationDto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The create payload carried a required {@code status} that the mapper building the employee
 * then threw away, so a caller was made to send a value nothing read and a caller that left it
 * out was refused for it. It is gone. An employee's status is written through the employee
 * update endpoint, which is the one place that sets it.
 */
class EmployeeCreationDtoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private EmployeeCreationDto validDto() {
        EmployeeCreationDto dto = new EmployeeCreationDto();
        dto.setDesignation("Site Engineer");
        dto.setDepartment("Civil");
        dto.setEmployeeName("Ravi Kumar");
        dto.setGender("male");
        dto.setPhoneNumber("9847012345");
        dto.setEmailAddress("ravi.kumar@example.com");
        dto.setDateOfBirth(LocalDateTime.of(1994, 3, 22, 0, 0));
        return dto;
    }

    @Test
    void aPayloadThatNamesNoStatusIsValid() {
        assertThat(validator.validate(validDto())).isEmpty();
    }

    @Test
    void aPayloadThatStillSendsAStatusIsStillAccepted() {
        // Nothing here refuses a client that has not caught up: the application's mapper is the
        // one Spring Boot builds, which ignores a property the DTO no longer declares. So an old
        // caller keeps working and this change needs no deploy coordinated with the clients.
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .build();
        String json = """
                {
                  "designation": "Site Engineer",
                  "department": "Civil",
                  "employeeName": "Ravi Kumar",
                  "gender": "male",
                  "phoneNumber": "9847012345",
                  "emailAddress": "ravi.kumar@example.com",
                  "dateOfBirth": "1994-03-22T00:00:00",
                  "status": "active"
                }
                """;

        assertThatCode(() -> {
            EmployeeCreationDto dto = objectMapper.readValue(json, EmployeeCreationDto.class);
            assertThat(validator.validate(dto)).isEmpty();
        }).doesNotThrowAnyException();
    }
}
