package org.tornotron.echno_backend.common.conversions;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
public class DateConversion {

    public static String convertDateToString(LocalDateTime javaDate) {
        if (javaDate == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
        return javaDate.format(formatter);
    }

    /**
     * Reads a value from a partial-update map into a {@link LocalDateTime}.
     *
     * <p>The partial-update endpoints take a {@code Map<String, Object>} rather than a typed DTO,
     * so the field annotations that pin the wire contract elsewhere do not apply and the parsing
     * has to be done here. This keeps the two routes saying the same thing: the value is a local
     * wall-clock time with no offset, and a value carrying one is rejected rather than truncated.
     *
     * <p>The truncation is the reason this exists. {@code DateTimeFormatter.ISO_DATE_TIME}, which
     * these switches used, happily parses {@code "2026-08-27T03:30:00Z"} and then discards the
     * offset on the way into a {@code LocalDateTime}, so the stored time is silently shifted by
     * the caller's offset. {@code ISO_LOCAL_DATE_TIME} rejects it instead, while still accepting
     * optional seconds and fractional seconds.
     *
     * @param value The raw map value: an ISO local date-time string, an already-typed
     *              {@link LocalDateTime}, or null.
     * @return The parsed value, or null when the caller is clearing the field.
     * @throws IllegalArgumentException if the value is not a string, or carries a timezone offset,
     *                                  or is not a valid ISO local date-time.
     */
    public static LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "Expected a date-time string but got " + value.getClass().getSimpleName());
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Expected a local date-time with no timezone offset, for example "
                            + "2026-08-27T09:00:00, but got \"" + text + "\". A value carrying an "
                            + "offset cannot be stored here without silently shifting the time.", e);
        }
    }
}
