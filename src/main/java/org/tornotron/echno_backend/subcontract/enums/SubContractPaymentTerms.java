package org.tornotron.echno_backend.subcontract.enums;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.util.Arrays;
import java.util.List;

/**
 * How a subcontract is paid.
 *
 * <p>The wire value is the camelCase string the web app's enum of the same meaning uses, so a
 * stored value and the badge that renders it agree. Stored as that string.
 */
public enum SubContractPaymentTerms {
    MILESTONE("milestone"),
    MONTHLY("monthly"),
    COMPLETION("completion"),
    CUSTOM("custom");

    private final String value;

    SubContractPaymentTerms(String value) {
        this.value = value;
    }

    /** The string stored in the column and sent on the wire. */
    public String value() {
        return value;
    }

    /** Every accepted wire value, in declaration order. */
    public static List<String> wireValues() {
        return Arrays.stream(values()).map(SubContractPaymentTerms::value).toList();
    }

    /**
     * Checks a value from a request against this vocabulary.
     *
     * @param field the request field, named in the error
     * @param raw   the value sent; null or blank means not set
     * @return the value unchanged, or null when not set
     * @throws InvalidRequestException if the value is set and not one of the accepted values
     */
    public static String requireValid(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (SubContractPaymentTerms candidate : values()) {
            if (candidate.value.equals(raw)) {
                return raw;
            }
        }
        throw new InvalidRequestException(
                field + " must be one of " + wireValues() + ", but was '" + raw + "'");
    }
}
