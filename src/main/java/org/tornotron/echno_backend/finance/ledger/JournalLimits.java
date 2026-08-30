package org.tornotron.echno_backend.finance.ledger;

/**
 * The lengths the ledger's own columns impose on the text callers hand it.
 *
 * <p>These live in one place because the reversal description is not written by the caller: the
 * ledger composes it, as {@code "Reversal of " + entryNumber + " - " + reason}, and stores the
 * result in a 500-character column. A reason therefore has less room than the column suggests,
 * and the amount less is arithmetic over three constants that used to be spread across an entity,
 * a request record and a string literal inside a service. A caller that guessed 500 from the
 * column produced a database error instead of a refusal, and the web app compensated with a
 * client-side cap of its own that nothing tied back here.
 *
 * <p>{@link #REVERSAL_REASON_MAX_LENGTH} has to be a compile-time constant because bean
 * validation reads it in an annotation, so it cannot be written as the expression that derives
 * it. {@code JournalLimitsTest} recomputes that expression and fails if the two drift, which is
 * what a widened column or a reworded prefix would otherwise do silently.
 */
public final class JournalLimits {

    /** Length of {@code journal_entry.description}, the column a composed description lands in. */
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    /** Length of {@code journal_entry.entry_number}, worst case for the number inside the prefix. */
    public static final int ENTRY_NUMBER_MAX_LENGTH = 30;

    /** Opening of a reversal's description, before the reversed entry's number. */
    public static final String REVERSAL_DESCRIPTION_PREFIX = "Reversal of ";

    /** What separates the reversed entry's number from the caller's reason. */
    public static final String REVERSAL_DESCRIPTION_SEPARATOR = " - ";

    /**
     * How much of a reversal's description is left for the caller's reason: 500 less the
     * 12-character prefix, the 30-character entry number and the 3-character separator.
     */
    public static final int REVERSAL_REASON_MAX_LENGTH = 455;

    private JournalLimits() {
    }

    /**
     * Composes the description a reversing entry carries.
     *
     * @param reversedEntryNumber The number of the entry being reversed.
     * @param reason The caller's reason, already bounded by {@link #REVERSAL_REASON_MAX_LENGTH}.
     * @return The description, which fits {@link #DESCRIPTION_MAX_LENGTH}.
     */
    public static String reversalDescription(String reversedEntryNumber, String reason) {
        return REVERSAL_DESCRIPTION_PREFIX + reversedEntryNumber + REVERSAL_DESCRIPTION_SEPARATOR + reason;
    }
}
