package org.tornotron.echno_backend.common.exception;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What is wrong with a request body that could not be read, in words a client can act on.
 *
 * <p>Reads the failure's structure (the field path, the target type, the position in the text) and
 * not its message, because Jackson's messages quote the rejected value.
 *
 * @param field  The path of the field at fault, such as {@code items[0].status}, or null when the
 *               failure is not about one field.
 * @param detail The sentence to answer with.
 */
record UnreadableBody(String field, String detail) {

    private static final String MISSING = "Required request body is missing";
    private static final String NO_CONTENT = "No content to map";
    private static final ObjectMapper ENUM_WRITER = new ObjectMapper();

    static UnreadableBody of(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof JsonParseException parse) {
            return new UnreadableBody(null, "The request body is not valid JSON" + position(parse.getLocation()));
        }
        if (cause instanceof JsonMappingException mapping) {
            return ofMapping(mapping);
        }
        String message = ex.getMessage();
        if (cause == null && message != null && message.startsWith(MISSING)) {
            return new UnreadableBody(null, MISSING);
        }
        return new UnreadableBody(null, "The request body could not be read");
    }

    private static UnreadableBody ofMapping(JsonMappingException mapping) {
        String original = mapping.getOriginalMessage();
        if (original != null && original.startsWith(NO_CONTENT)) {
            return new UnreadableBody(null, MISSING);
        }
        String field = fieldPath(mapping);
        if (mapping instanceof PropertyBindingException && field != null) {
            return new UnreadableBody(field, "Field '" + field + "' is not recognised");
        }
        String expected = expectation(mapping);
        if (field == null) {
            return new UnreadableBody(null, expected == null
                    ? "The request body could not be read"
                    : "The request body must be " + expected);
        }
        return new UnreadableBody(field, expected == null
                ? "Field '" + field + "' could not be read"
                : "Field '" + field + "' must be " + expected);
    }

    /** The path as a client wrote it: {@code items[0].status}. */
    private static String fieldPath(JsonMappingException mapping) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    private static String expectation(JsonMappingException mapping) {
        if (mapping instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null) {
            return describe(mismatch.getTargetType());
        }
        if (mapping instanceof ValueInstantiationException instantiation) {
            JavaType type = instantiation.getType();
            return type == null ? null : describe(type.getRawClass());
        }
        return null;
    }

    private static String describe(Class<?> type) {
        if (type.isEnum()) {
            return "one of: " + Arrays.stream(type.getEnumConstants())
                    .map(UnreadableBody::wireValue)
                    .collect(Collectors.joining(", "));
        }
        if (type == LocalDateTime.class) {
            return "a date and time such as 2026-01-31T10:15:30, with no time zone or offset";
        }
        if (type == LocalDate.class) {
            return "a date such as 2026-01-31";
        }
        if (type == LocalTime.class) {
            return "a time such as 10:15:30";
        }
        if (type == OffsetDateTime.class || type == ZonedDateTime.class || type == Instant.class) {
            return "a date and time with an offset, such as 2026-01-31T10:15:30Z";
        }
        if (type == UUID.class) {
            return "a UUID";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true or false";
        }
        if (type == BigDecimal.class || type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "a number";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return "a whole number";
        }
        if (type == String.class) {
            return "text";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "a list";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "an object";
        }
        return type.getName().startsWith("java.") ? null : "an object";
    }

    /** The constant as it appears in JSON, which an enum's {@code @JsonValue} can make differ from its name. */
    private static String wireValue(Object constant) {
        try {
            return ENUM_WRITER.convertValue(constant, String.class);
        } catch (IllegalArgumentException e) {
            return ((Enum<?>) constant).name();
        }
    }

    private static String position(JsonLocation location) {
        if (location == null || location.getLineNr() < 0) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }
}
