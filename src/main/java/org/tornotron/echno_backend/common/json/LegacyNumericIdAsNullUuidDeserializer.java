package org.tornotron.echno_backend.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.UUID;

/**
 * Reads a UUID reference that used to be declared as a number.
 *
 * <p>A client built against the old contract may still send a JSON number. A number cannot
 * name a row keyed by UUID, so it never referred to anything; it is read as "no reference"
 * instead of failing the whole request. A string must be a well-formed UUID.
 */
public class LegacyNumericIdAsNullUuidDeserializer extends JsonDeserializer<UUID> {

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return null;
        }
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException ex) {
            throw InvalidFormatException.from(parser, "Not a UUID: " + text, text, UUID.class);
        }
    }
}
