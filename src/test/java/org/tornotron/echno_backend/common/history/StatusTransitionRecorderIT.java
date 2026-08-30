package org.tornotron.echno_backend.common.history;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared status trail against a real CockroachDB, on the schema Liquibase builds (see
 * {@link AbstractIntegrationTest}). Hibernate runs with {@code ddl-auto: validate}, so the
 * context only starts if the entity and the migrated table agree.
 *
 * <p>What is pinned here is the trail's own behaviour rather than any one module's use of it:
 * the opening entry, the no-op that must not become an entry, the snapshotted actor, and the
 * organization scope, which is the whole of the tenant boundary on a table that carries no
 * foreign key to the records it describes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StatusTransitionRecorder.class)
class StatusTransitionRecorderIT extends AbstractIntegrationTest {

    private static final String PROJECT = "PROJECT";

    @Autowired
    private StatusTransitionRecorder recorder;

    @Autowired
    private StatusTransitionRepository repository;

    @Autowired
    private TestEntityManager em;

    /**
     * The opening entry names no earlier status and is marked as a creation, which is what makes
     * "created in this state" tellable from "moved into this state" afterwards.
     */
    @Test
    void recordCreation_writesAnEntryWithNoEarlierStatus() {
        Organization org = persistOrganization("Creation Org");

        recorder.recordCreation(PROJECT, 101L, org, "upcoming", persistUser("Aneesh Johny", "kc-aneesh"));
        em.flush();
        em.clear();

        StatusTransition entry = onlyEntry(PROJECT, 101L, org);
        assertThat(entry.getFromStatus()).isNull();
        assertThat(entry.getToStatus()).isEqualTo("upcoming");
        assertThat(entry.getSource()).isEqualTo(StatusTransitionSource.CREATION);
        assertThat(entry.getOccurredAt()).isNotNull();
    }

    /**
     * The actor's name is stored beside the id at the time of writing, so renaming or removing the
     * user later does not rewrite what the trail says happened.
     */
    @Test
    void recordChange_snapshotsTheActorsNameBesideTheirId() {
        Organization org = persistOrganization("Actor Org");
        User actor = persistUser("Anand Rajashekar", "kc-anand");

        recorder.recordChange(PROJECT, 102L, org, "upcoming", "approved", actor, "Signed off");
        em.flush();
        em.clear();

        StatusTransition entry = onlyEntry(PROJECT, 102L, org);
        assertThat(entry.getChangedBy()).isEqualTo(actor.getId());
        assertThat(entry.getChangedByName()).isEqualTo("Anand Rajashekar");
        assertThat(entry.getSource()).isEqualTo(StatusTransitionSource.UPDATE);
        assertThat(entry.getNote()).isEqualTo("Signed off");
    }

    /**
     * A save that left the status where it was is not a transition. Dropping it here rather than
     * at each caller is what lets a caller record unconditionally on every write without filling
     * the trail with entries whose two ends are the same.
     */
    @Test
    void recordChange_writesNothingWhenTheStatusDidNotMove() {
        Organization org = persistOrganization("No-op Org");

        StatusTransition written = recorder.recordChange(
                PROJECT, 103L, org, "open", "open", null, null);
        em.flush();

        assertThat(written).isNull();
        assertThat(repository.countByEntityTypeAndEntityIdAndOrganization_Id(PROJECT, 103L, org.getId()))
                .isZero();
    }

    /**
     * The trail carries no foreign key to the record it describes, so the organization column is
     * the whole of its tenant scope. Two organizations that happen to number a project the same
     * must not see each other's history, and the finder is organization-explicit for that reason.
     */
    @Test
    void aTrailIsReadOnlyWithinItsOwnOrganization() {
        Organization mine = persistOrganization("Mine");
        Organization theirs = persistOrganization("Theirs");

        recorder.recordChange(PROJECT, 27L, mine, "upcoming", "approved", null, null);
        recorder.recordChange(PROJECT, 27L, theirs, "upcoming", "cancelled", null, null);
        em.flush();
        em.clear();

        assertThat(onlyEntry(PROJECT, 27L, mine).getToStatus()).isEqualTo("approved");
        assertThat(onlyEntry(PROJECT, 27L, theirs).getToStatus()).isEqualTo("cancelled");
    }

    /**
     * Newest first, because the head of the trail is the entry that explains the status the record
     * is in now, and a capped read from the other end would leave it off the page.
     */
    @Test
    void aTrailIsReadNewestFirst() {
        Organization org = persistOrganization("Ordering Org");

        recorder.recordCreation(PROJECT, 104L, org, "upcoming", null);
        recorder.recordChange(PROJECT, 104L, org, "upcoming", "open", null, null);
        recorder.recordChange(PROJECT, 104L, org, "open", "approved", null, null);
        em.flush();
        em.clear();

        Page<StatusTransition> page = repository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        PROJECT, 104L, org.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(StatusTransition::getToStatus)
                .containsExactly("approved", "open", "upcoming");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    /** Entries for one record do not leak into another record's trail. */
    @Test
    void aTrailHoldsOnlyItsOwnRecordsEntries() {
        Organization org = persistOrganization("Separation Org");

        recorder.recordChange(PROJECT, 105L, org, "upcoming", "approved", null, null);
        recorder.recordChange(PROJECT, 106L, org, "upcoming", "cancelled", null, null);
        recorder.recordChange("SITE_TRANSFER", 105L, org, "PENDING", "COMPLETED", null, null);
        em.flush();
        em.clear();

        assertThat(onlyEntry(PROJECT, 105L, org).getToStatus()).isEqualTo("approved");
        assertThat(onlyEntry("SITE_TRANSFER", 105L, org).getToStatus()).isEqualTo("COMPLETED");
    }

    private StatusTransition onlyEntry(String entityType, Long entityId, Organization org) {
        Page<StatusTransition> page = repository
                .findByEntityTypeAndEntityIdAndOrganization_IdOrderByOccurredAtDescIdDesc(
                        entityType, entityId, org.getId(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);
        return page.getContent().get(0);
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").replace("-", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private User persistUser(String name, String keycloakId) {
        User user = new User();
        user.setKeycloakId(keycloakId);
        user.setName(name);
        em.persist(user);
        return user;
    }
}
