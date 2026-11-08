package org.tornotron.echno_backend.user.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration is refused unless the terms were accepted (#997). The form already checked this in
 * the browser; a direct call to the endpoint did not.
 */
class UserRegistrationTermsTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static UserRegistrationDto registration(Boolean acceptTerms) {
        UserRegistrationDto dto = new UserRegistrationDto();
        dto.setUserName("site.engineer");
        dto.setName("Site Engineer");
        dto.setPhone("9000000000");
        dto.setGender("Female");
        dto.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        dto.setEmail("site.engineer@example.com");
        dto.setPassword("a-long-password");
        dto.setAcceptTerms(acceptTerms);
        return dto;
    }

    private static Set<String> failingFields(UserRegistrationDto dto) {
        return validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void acceptedTermsPass() {
        assertThat(failingFields(registration(true))).isEmpty();
    }

    @Test
    void declinedTermsAreRefusedAgainstTheField() {
        Set<ConstraintViolation<UserRegistrationDto>> violations = validator.validate(registration(false));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("acceptTerms");
            assertThat(v.getMessage()).isEqualTo("acceptTerms must be true");
        });
    }

    @Test
    void missingTermsAreRefusedRatherThanPassing() {
        Set<ConstraintViolation<UserRegistrationDto>> violations = validator.validate(registration(null));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("acceptTerms");
            assertThat(v.getMessage()).isEqualTo("acceptTerms is required");
        });
    }
}
