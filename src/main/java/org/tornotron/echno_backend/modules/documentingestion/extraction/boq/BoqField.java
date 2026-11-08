package org.tornotron.echno_backend.modules.documentingestion.extraction.boq;

import java.util.Locale;
import java.util.Optional;

/** The columns of a BOQ sheet a person maps. The key is the payload field and the API name. */
public enum BoqField {
    ITEM_CODE("itemCode"),
    DESCRIPTION("description"),
    UNIT("unit"),
    QUANTITY("quantity"),
    RATE("rate"),
    AMOUNT("amount");

    private final String key;

    BoqField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<BoqField> ofKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (BoqField f : values()) {
            if (f.key.equals(key) || f.name().equals(key.toUpperCase(Locale.ROOT))) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
}
