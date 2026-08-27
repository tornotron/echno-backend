package org.tornotron.echno_backend.compliance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compliance rules are looked up by state name, so what gets stored on a project has to be the
 * canonical spelling rather than whatever casing the user typed. These pin that, and pin the
 * gap the stored field exists to close: scanning a free-text address only works when the
 * address happens to name a state.
 */
class IndianStateResolverTest {

    @Test
    void canonicalise_returnsTheCanonicalSpellingWhateverTheCasing() {
        assertThat(IndianStateResolver.canonicalise("tamil nadu")).isEqualTo("Tamil Nadu");
        assertThat(IndianStateResolver.canonicalise("  KERALA  ")).isEqualTo("Kerala");
        assertThat(IndianStateResolver.canonicalise("Puducherry")).isEqualTo("Puducherry");
    }

    @Test
    void canonicalise_treatsBlankAsNotStated() {
        assertThat(IndianStateResolver.canonicalise(null)).isNull();
        assertThat(IndianStateResolver.canonicalise("   ")).isNull();
    }

    @Test
    void canonicalise_refusesSomethingThatIsNotAState() {
        // Refused at entry, because a state that matches no rule would otherwise fail much
        // later, at generation time, with nothing pointing at the typo that caused it.
        assertThatThrownBy(() -> IndianStateResolver.canonicalise("Chennai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not an Indian state");
        assertThatThrownBy(() -> IndianStateResolver.canonicalise("Tamilnadu"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_findsAStateNamedInTheAddress() {
        assertThat(IndianStateResolver.resolve("12 Mount Road, Chennai, Tamil Nadu"))
                .isEqualTo("Tamil Nadu");
    }

    @Test
    void resolve_findsNothingInAnAddressThatNamesOnlyACity() {
        // This is the case that made the stored field necessary. "Chennai" is a complete,
        // ordinary address to type, and the scan can make nothing of it.
        assertThat(IndianStateResolver.resolve("Chennai")).isNull();
        assertThat(IndianStateResolver.resolve("")).isNull();
        assertThat(IndianStateResolver.resolve(null)).isNull();
    }

    @Test
    void states_coversTheTwentyEightStatesAndEightUnionTerritories() {
        assertThat(IndianStateResolver.states()).hasSize(36).contains("Ladakh", "West Bengal");
    }
}
