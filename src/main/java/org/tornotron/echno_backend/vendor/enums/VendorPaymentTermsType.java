package org.tornotron.echno_backend.vendor.enums;

import org.tornotron.echno_backend.common.exception.InvalidRequestException;

import java.util.Arrays;
import java.util.List;

/**
 * The credit period agreed with a vendor. The values are those of echno-core's
 * {@code PaymentTerms} enum: {@code IMMEDIATE} is cash on delivery and {@code NET<n>} is n days
 * from the invoice date. Stored as the constant's name.
 */
public enum VendorPaymentTermsType {
    IMMEDIATE,
    NET15,
    NET20,
    NET30,
    NET60,
    NET90;

    /** Every accepted value, in declaration order. */
    public static List<String> wireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * Checks the payment terms a request sent (#863): the column took any string, so values such
     * as {@code NET45} that no client can display were stored.
     *
     * @param raw the value sent
     * @return the value unchanged
     * @throws InvalidRequestException if it is not one of the accepted values
     */
    public static String requireValid(String raw) {
        if (raw != null) {
            for (VendorPaymentTermsType candidate : values()) {
                if (candidate.name().equals(raw)) {
                    return raw;
                }
            }
        }
        throw new InvalidRequestException(
                "paymentTerms must be one of " + wireValues() + ", but was '" + raw + "'");
    }
}
