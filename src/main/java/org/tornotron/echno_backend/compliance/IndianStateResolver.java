package org.tornotron.echno_backend.compliance;

import java.util.List;
import java.util.Locale;

/**
 * The Indian states and union territories compliance rules are keyed by, and the two ways a
 * project's state is arrived at.
 *
 * <p>{@link #canonicalise(String)} is the preferred one: a project that states its own state
 * gives an exact answer. {@link #resolve(String)} is the fallback for the projects that predate
 * the field, scanning the free-text address for a state name and returning the first hit. That
 * scan cannot find what the address does not say, so an address of "Chennai" resolves to
 * nothing, which is why the field exists.
 */
public final class IndianStateResolver {

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

    /** The states and union territories rules may be keyed by, in canonical spelling. */
    public static List<String> states() {
        return STATES;
    }

    /**
     * The canonical spelling of a state named in full, ignoring case and surrounding space, or
     * null for a blank input. Storing the canonical spelling is what lets the rule lookup match
     * a state the user typed in their own casing.
     *
     * @param state The state name as supplied.
     * @return The canonical name, or null when the input is blank.
     * @throws IllegalArgumentException if the name is not a state or union territory.
     */
    public static String canonicalise(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String trimmed = state.trim();
        for (String known : STATES) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }
        throw new IllegalArgumentException(
                "'" + trimmed + "' is not an Indian state or union territory");
    }

    /**
     * The state a project's compliance rules are keyed by: its own state field where it has one,
     * and otherwise whatever its free-text address happens to name.
     *
     * <p>Both the approval gate that refuses a project with no determinable state and the
     * generation that runs straight afterwards resolve it through here, so the gate cannot admit
     * a project that generation would then turn away, and cannot refuse one that generation
     * would have handled.
     *
     * @param projectState The project's own state field, canonical or blank.
     * @param projectAddress The project's free-text address, scanned only when the field is blank.
     * @return The canonical state name, or null when neither route yields one.
     */
    public static String forProject(String projectState, String projectAddress) {
        if (projectState != null && !projectState.isBlank()) {
            return projectState;
        }
        return resolve(projectAddress);
    }

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
