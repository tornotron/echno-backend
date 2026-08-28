package org.tornotron.echno_backend.common.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.util.Map;

/**
 * Reads the JSON {@code data} part of a multipart request into a payload, and validates it.
 *
 * <p>A multipart create endpoint cannot take its payload as a {@code @RequestBody}, because the
 * body is the multipart envelope, so the payload travels as a JSON string part and the endpoint
 * deserializes it. Doing that by hand is what let every constraint on those payloads go unenforced
 * for as long as the endpoints existed: {@code @Valid} was written on the {@code String} part,
 * a {@code String} declares no constraints, and the bean that came out of {@code readValue} was
 * never offered to a validator. Issue #490 counted 32 declared constraints across 11 endpoints
 * that had never once run.
 *
 * <p>Parsing and validating are therefore one call here rather than two steps at a call site.
 * A caller cannot obtain a payload from this class without its constraints having been checked,
 * which is the property that survives the next endpoint being written by somebody who has not
 * read this file. {@code MultipartPayloadValidationTest} keeps controllers from going back to
 * parsing a part themselves.
 *
 * <p>{@link #readUpdates} is the partial-update counterpart. Those endpoints read the part into a
 * {@code Map} of the fields the caller actually sent, so there is no bean and nothing to validate;
 * it is here so that reading a part is one mechanism rather than two, and so the rule above needs
 * no exceptions.
 */
@Component
public class JsonPartBinder {

    private static final TypeReference<Map<String, Object>> UPDATES = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final PayloadValidator payloadValidator;

    public JsonPartBinder(ObjectMapper objectMapper, PayloadValidator payloadValidator) {
        this.objectMapper = objectMapper;
        this.payloadValidator = payloadValidator;
    }

    /**
     * Reads a required JSON part into a payload and checks its constraints.
     *
     * @param json The raw JSON carried by the part.
     * @param type The payload type to read it into.
     * @param <T> The payload type.
     * @return The deserialized payload, already validated.
     * @throws InvalidRequestException if the part is absent or carries nothing.
     * @throws JsonProcessingException if the part is not JSON, or does not fit the payload.
     * @throws ConstraintViolationException if any constraint on the payload fails.
     */
    public <T> T read(String json, Class<T> type) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            throw new InvalidRequestException("The data part is required and must carry the payload as JSON");
        }
        return payloadValidator.requireValid(objectMapper.readValue(json, type));
    }

    /**
     * Reads an optional JSON part into the map of fields a partial update is changing.
     *
     * <p>An absent part means "change nothing", which is how these endpoints have always treated
     * it, so it answers with an empty map rather than refusing the request.
     *
     * @param json The raw JSON carried by the part, or {@code null} when the part is absent.
     * @return The fields to change, empty when the part is absent.
     * @throws JsonProcessingException if the part is not a JSON object.
     */
    public Map<String, Object> readUpdates(String json) throws JsonProcessingException {
        return json != null ? objectMapper.readValue(json, UPDATES) : Map.of();
    }
}
