package org.tornotron.echno_backend.finance.ledger;

import jakarta.persistence.Column;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.finance.ledger.domain.JournalEntry;
import org.tornotron.echno_backend.finance.ledger.dtos.ReverseJournalRequest;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet on the arithmetic behind the reversal reason bound.
 *
 * <p>{@code REVERSAL_REASON_MAX_LENGTH} has to be written as a literal, because bean validation
 * reads it from an annotation and an annotation argument must be a compile-time constant. That
 * makes it the one number in the chain that a widened column or a reworded prefix cannot update
 * on its own, which is exactly how the web app's client-side cap and the server drifted apart in
 * the first place. These tests recompute it from the pieces it is derived from, and check the
 * pieces themselves still match the columns they describe.
 */
class JournalLimitsTest {

    @Test
    void reasonBoundIsTheDescriptionColumnLessEverythingElseTheLedgerPutsInIt() {
        int prefixCost = JournalLimits.REVERSAL_DESCRIPTION_PREFIX.length()
                + JournalLimits.ENTRY_NUMBER_MAX_LENGTH
                + JournalLimits.REVERSAL_DESCRIPTION_SEPARATOR.length();

        assertThat(JournalLimits.REVERSAL_REASON_MAX_LENGTH)
                .as("a reason of the maximum length must exactly fill what the prefix leaves")
                .isEqualTo(JournalLimits.DESCRIPTION_MAX_LENGTH - prefixCost);
    }

    @Test
    void descriptionOfALongestReasonAgainstALongestEntryNumberStillFitsTheColumn() {
        String description = JournalLimits.reversalDescription(
                "E".repeat(JournalLimits.ENTRY_NUMBER_MAX_LENGTH),
                "r".repeat(JournalLimits.REVERSAL_REASON_MAX_LENGTH));

        assertThat(description).hasSize(JournalLimits.DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void theColumnLengthsMatchTheEntityTheyAreCopiedFrom() throws NoSuchFieldException {
        assertThat(columnLength("description")).isEqualTo(JournalLimits.DESCRIPTION_MAX_LENGTH);
        assertThat(columnLength("entryNumber")).isEqualTo(JournalLimits.ENTRY_NUMBER_MAX_LENGTH);
    }

    @Test
    void theRequestRecordDeclaresTheSameBound() throws NoSuchFieldException {
        Size size = ReverseJournalRequest.class.getDeclaredField("reason").getAnnotation(Size.class);

        assertThat(size).isNotNull();
        assertThat(size.max()).isEqualTo(JournalLimits.REVERSAL_REASON_MAX_LENGTH);
    }

    private static int columnLength(String fieldName) throws NoSuchFieldException {
        Field field = JournalEntry.class.getDeclaredField(fieldName);
        return field.getAnnotation(Column.class).length();
    }
}
