package org.tornotron.echno_backend.modules.inspections;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One reviewer operation on an observation's regions during a MODIFY. A replace and a remove
 * retire the row they name; nothing is updated in place.
 * The slug is the wire value; the kind is not stored, the rows it produces are.
 */
public enum RegionEditKind {
    REPLACE("replace"), REMOVE("remove"), ADD("add");

    private final String value;

    RegionEditKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RegionEditKind fromValue(String value) {
        for (RegionEditKind candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown RegionEditKind: " + value);
    }
}
