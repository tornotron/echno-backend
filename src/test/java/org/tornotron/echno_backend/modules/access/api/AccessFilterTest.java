package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.access.api.AccessFilter.Links;

/**
 * A filter admits a record of its own organization when any one of its conditions holds, the same
 * rule a module's query applies.
 */
class AccessFilterTest {

    private static final long ORG = 7L;
    private static final long OTHER_ORG = 8L;
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");

    private static Links ownerIs(long employee) {
        return new Links(Set.of(employee), null, null, null);
    }

    @Test
    void theEmptyFilterAdmitsNothingAndTheFullOneEverything() {
        RecordFacts record = RecordFacts.of(ORG, 100L);

        assertThat(AccessFilter.none(ORG).isEmpty()).isTrue();
        assertThat(AccessFilter.none(ORG).admits(record)).isFalse();
        assertThat(AccessFilter.all(ORG).isEmpty()).isFalse();
        assertThat(AccessFilter.all(ORG).admits(record)).isTrue();
    }

    @Test
    void admitsWhenAnyOneConditionHolds() {
        AccessFilter filter = new AccessFilter(ORG, false, Set.of(100L),
                new Links(Set.of(1L), Set.of(2L), Set.of(CUSTOMER), Set.of(9L)), null);

        assertThat(filter.admits(RecordFacts.of(ORG, 100L))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withOwners(Set.of(1L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withRelated(Set.of(2L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withCustomers(Set.of(CUSTOMER)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withVendors(Set.of(9L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, 200L).withOwners(Set.of(2L)))).isFalse();
    }

    @Test
    void anOwnerIsNotARelatedPersonAndTheOtherWayRound() {
        AccessFilter ownOnly = new AccessFilter(ORG, false, null, ownerIs(1L), null);

        assertThat(ownOnly.admits(RecordFacts.of(ORG, null).withRelated(Set.of(1L)))).isFalse();
    }

    @Test
    void aRecordOfAnotherOrganizationNeverPasses() {
        Links everyLink = new Links(Set.of(1L), Set.of(2L), Set.of(CUSTOMER), Set.of(9L));
        RecordFacts stranger = new RecordFacts(OTHER_ORG, 100L, Set.of(1L), Set.of(2L), Set.of(CUSTOMER), Set.of(9L));

        assertThat(AccessFilter.all(ORG).admits(stranger)).isFalse();
        assertThat(new AccessFilter(ORG, false, Set.of(100L), everyLink, Map.of(100L, everyLink)).admits(stranger))
                .isFalse();
    }

    @Test
    void linksHeldOnAProjectAdmitOnlyThatProjectsRecords() {
        AccessFilter filter = new AccessFilter(ORG, false, null, null, Map.of(100L, ownerIs(1L)));

        assertThat(filter.admits(RecordFacts.of(ORG, 100L).withOwners(Set.of(1L)))).isTrue();
        assertThat(filter.admits(RecordFacts.of(ORG, 100L).withOwners(Set.of(2L)))).isFalse();
        assertThat(filter.admits(RecordFacts.of(ORG, 200L).withOwners(Set.of(1L)))).isFalse();
        assertThat(filter.admits(RecordFacts.of(ORG, null).withOwners(Set.of(1L)))).isFalse();
    }

    @Test
    void aProjectWithNoLinksDoesNotMakeAFilterNonEmpty() {
        assertThat(new AccessFilter(ORG, false, null, null, Map.of(100L, Links.NONE)).isEmpty()).isTrue();
    }
}
