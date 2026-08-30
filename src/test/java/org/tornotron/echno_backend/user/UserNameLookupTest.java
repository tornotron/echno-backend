package org.tornotron.echno_backend.user;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a name means when a document says who approved it.
 *
 * <p>The answer has to hold in three awkward cases and not just the ordinary one: an account with
 * no employee record behind it, an account whose name column is empty, and an account that has
 * since been deleted while the document it stamped is still on the screen. None of them may
 * produce a blank cell, because a blank against an approval reads as nobody having approved it.
 */
class UserNameLookupTest {

    @Test
    void resolvesAnIdToTheUsersName() {
        UserNameLookup names = UserNameLookup.of(
                List.of(new UserDisplayName(7L, "Aneesh Johny", "aneesh@echno.test")));

        assertThat(names.nameOf(7L)).isEqualTo("Aneesh Johny");
    }

    @Test
    void fallsBackToTheEmailWhenTheNameIsEmpty() {
        // name is non-null in the schema but not guaranteed non-empty, and email is the only
        // other thing every account has.
        UserNameLookup names = UserNameLookup.of(
                List.of(new UserDisplayName(7L, "   ", "aneesh@echno.test")));

        assertThat(names.nameOf(7L)).isEqualTo("aneesh@echno.test");
    }

    @Test
    void readsAsAPlaceholderWhenTheAccountIsGone() {
        // A deleted user still has to render on the historical document it stamped. The
        // placeholder says the account no longer exists; a blank would say nobody approved it.
        UserNameLookup names = UserNameLookup.of(
                List.of(new UserDisplayName(7L, "Aneesh Johny", "aneesh@echno.test")));

        assertThat(names.nameOf(404L)).isEqualTo("User #404");
    }

    @Test
    void readsAsAPlaceholderWhenNothingWasResolvedAtAll() {
        assertThat(UserNameLookup.none().nameOf(9L)).isEqualTo("User #9");
    }

    @Test
    void isNullOnlyWhenTheStampItselfIsNull() {
        // An unapproved document has no approver, which is a different thing from an approver
        // whose account is gone, and the two must not render the same.
        UserNameLookup names = UserNameLookup.of(
                List.of(new UserDisplayName(7L, "Aneesh Johny", "aneesh@echno.test")));

        assertThat(names.nameOf(null)).isNull();
    }

    @Test
    void aRowWithNeitherNameNorEmailReadsAsThePlaceholder() {
        UserNameLookup names = UserNameLookup.of(List.of(new UserDisplayName(7L, "", null)));

        assertThat(names.nameOf(7L)).isEqualTo("User #7");
    }

    @Test
    void toleratesDuplicateRowsForTheSameId() {
        UserNameLookup names = UserNameLookup.of(List.of(
                new UserDisplayName(7L, "First", null),
                new UserDisplayName(7L, "Second", null)));

        assertThat(names.nameOf(7L)).isEqualTo("First");
    }
}
