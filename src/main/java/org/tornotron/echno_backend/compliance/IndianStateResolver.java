package org.tornotron.echno_backend.compliance;

import java.util.List;
import java.util.Locale;

/**
 * Derives the Indian state a project sits in from its free-text address. Compliance
 * rules are keyed by state, so generation needs a state name; projects only carry an
 * address string. The match is a case-insensitive substring scan against a fixed list
 * of states and union territories, returning the canonical name of the first hit.
 * Deliberately simple: a structured state field on the project can replace this later.
 */
final class IndianStateResolver {

    private static final List<String> STATES = List.of(
            "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh",
            "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka",
            "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram",
            "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu",
            "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
            "Andaman and Nicobar Islands", "Chandigarh",
            "Dadra and Nagar Haveli and Daman and Diu", "Delhi", "Jammu and Kashmir",
            "Ladakh", "Lakshadweep", "Puducherry");

    private IndianStateResolver() {}

    /** The canonical state name found in the address, or null if none matches. */
    static String resolve(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String haystack = address.toLowerCase(Locale.ROOT);
        for (String state : STATES) {
            if (haystack.contains(state.toLowerCase(Locale.ROOT))) {
                return state;
            }
        }
        return null;
    }
}
