package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A filter admits a record when any one of its conditions holds, the same rule a module's query
 * applies.
 */
class AccessFilterTest {

    private static final long ORG = 7L;
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");

    @Test
    void theEmptyFilterAdmitsNothingAndTheFullOneEverything() {
        RecordFacts record = RecordFacts.of(ORG, 100L);

        assertThat(AccessFilter.none().isEmpty()).isTrue();
        assertThat(AccessFilter.none().admits(record)).isFalse();
        assertThat(AccessFilter.all().isEmpty()).isFalse();
        assertThat(AccessFilter.all().admits(record)).isTrue();
    }

    @Test
    void admitsWhenAnyOneConditionHolds() {
        AccessFilter filter = new AccessFilter(false, Set.of(100L), Set.of(1L), Set.of(2L), Set.of(CUSTOMER), Set.of(9L));

        assertThat(filter.admits(RecordFacts.of(ORG, 100L))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withOwners(Set.of(1L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withRelated(Set.of(2L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withCustomers(Set.of(CUSTOMER)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withVendors(Set.of(9L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, 200L).withOwners(Set.of(2L)))).isFalse();
    }

    @Test
    void anOwnerIsNotARelatedPersonAndTheOtherWayRound() {
        AccessFilter ownOnly = new AccessFilter(false, null, Set.of(1L), null, null, null);

        assertThat(ownOnly.admits(RecordFacts.of(ORG, null).withRelated(Set.of(1L)))).isFalse();
    }
}
