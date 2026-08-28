package org.tornotron.echno_backend.common.conversions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DateConversion#parseLocalDateTime} is the partial-update route's equivalent of the
 * {@code @JsonFormat(lenient = FALSE)} that pins the typed DTOs. A partial update arrives as a
 * {@code Map<String, Object>}, so no field annotation applies and the strictness has to live here.
 *
 * <p>No timezone pinning in this class, deliberately. The method converts a string to a
 * {@link LocalDateTime} without consulting a clock or a zone, so there is nothing for a zone to
 * change and pinning one would be ritual rather than coverage. The zone matters on the client,
 * where the string is produced, and those tests pin it.
 */
class DateConversionTest {

    @Test
    @DisplayName("parses a local date-time")
    void parsesLocalDateTime() {
        assertThat(DateConversion.parseLocalDateTime("2026-08-27T09:00:00"))
                .isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0, 0));
    }

    @Test
    @DisplayName("accepts optional seconds and fractional seconds")
    void acceptsOptionalPrecision() {
        assertThat(DateConversion.parseLocalDateTime("2026-08-27T09:00"))
                .isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0));
        assertThat(DateConversion.parseLocalDateTime("2026-08-27T09:00:00.123"))
                .isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0, 0, 123_000_000));
    }

    @Test
    @DisplayName("rejects a UTC value rather than discarding the offset")
    void rejectsUtcValue() {
        assertThatThrownBy(() -> DateConversion.parseLocalDateTime("2026-08-27T03:30:00.000Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no timezone offset");
    }

    @Test
    @DisplayName("rejects an explicit offset rather than discarding it")
    void rejectsExplicitOffset() {
        assertThatThrownBy(() -> DateConversion.parseLocalDateTime("2026-08-27T09:00:00+05:30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no timezone offset");
    }

    @Test
    @DisplayName("passes a null through so a field can be cleared")
    void passesNullThrough() {
        assertThat(DateConversion.parseLocalDateTime(null)).isNull();
    }

    @Test
    @DisplayName("passes an already-typed value through")
    void passesTypedValueThrough() {
        LocalDateTime value = LocalDateTime.of(2026, 8, 27, 9, 0);
        assertThat(DateConversion.parseLocalDateTime(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("rejects a value that is not a date-time string")
    void rejectsNonString() {
        assertThatThrownBy(() -> DateConversion.parseLocalDateTime(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a date-time string");
    }
}
