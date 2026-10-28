package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.ALL;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.ASSIGNED_PROJECTS;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.OWN;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.RELATED;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules every access engine keeps, run against each engine by extending this class.
 *
 * <p>Set-up goes through the abstract methods, so a test reads the same whatever the engine
 * stores: a subject is made a member, granted a permission with a scope, put on a project, or
 * linked to a customer or vendor. An engine passes when every test here does; that is what makes
 * one engine safe to swap for another.
 */
public abstract class AccessServiceContract {

    protected static final long ORG = 7L;
    protected static final long OTHER_ORG = 8L;
    protected static final long PROJECT_A = 100L;
    protected static final long PROJECT_B = 200L;
    protected static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    protected static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    protected static final long VENDOR = 900L;

    protected static final AccessPermission VIEW_BILLS = AccessPermission.of("progress-bills:view");
    protected static final AccessPermission CERTIFY_BILLS = AccessPermission.of("progress-bills:certify");

    protected final AccessSubject alice = new AccessSubject(ORG, 1L, 11L);
    protected final AccessSubject bob = new AccessSubject(ORG, 2L, 12L);

    /** The engine under test, built over whatever {@code member}, {@code grant} and the rest set up. */
    protected abstract AccessService service();

    /** Makes the subject an active member of their organization. */
    protected abstract void member(AccessSubject subject);

    /** Makes the subject's membership inactive; they keep whatever was granted. */
    protected abstract void deactivate(AccessSubject subject);

    protected abstract void grant(AccessSubject subject, AccessPermission permission, AccessScope scope);

    protected abstract void assignToProject(AccessSubject subject, long projectId);

    protected abstract void linkCustomer(AccessSubject subject, UUID customerId);

    protected abstract void linkVendor(AccessSubject subject, long vendorId);

    @Test
    void aMemberWithNoGrantHoldsNothing() {
        member(alice);

        assertThat(service().check(alice, VIEW_BILLS)).isFalse();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_A))).isFalse();
        assertThat(service().filter(alice, VIEW_BILLS).isEmpty()).isTrue();
        assertThat(service().grantsFor(alice).scopes()).isEmpty();
    }

    @Test
    void membershipFollowsTheMembersStatus() {
        member(alice);

        assertThat(service().isActiveMember(alice)).isTrue();
        assertThat(service().isActiveMember(bob)).isFalse();

        deactivate(alice);

        assertThat(service().isActiveMember(alice)).isFalse();
    }

    @Test
    void oneActionDoesNotGrantAnother() {
        member(alice);
        grant(alice, VIEW_BILLS, ALL);

        assertThat(service().check(alice, VIEW_BILLS)).isTrue();
        assertThat(service().check(alice, CERTIFY_BILLS)).isFalse();
    }

    @Test
    void allReachesEveryRecordInTheOrganization() {
        member(alice);
        grant(alice, VIEW_BILLS, ALL);

        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_A))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, null))).isTrue();
        assertThat(service().filter(alice, VIEW_BILLS).everything()).isTrue();
    }

    @Test
    void aRecordInAnotherOrganizationIsNeverAllowed() {
        member(alice);
        grant(alice, VIEW_BILLS, ALL);

        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(OTHER_ORG, PROJECT_A))).isFalse();
    }

    @Test
    void anInactiveMemberHoldsNothingWhateverWasGranted() {
        member(alice);
        grant(alice, VIEW_BILLS, ALL);
        deactivate(alice);

        assertThat(service().check(alice, VIEW_BILLS)).isFalse();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_A))).isFalse();
        assertThat(service().filter(alice, VIEW_BILLS).isEmpty()).isTrue();
        assertThat(service().grantsFor(alice).scopes()).isEmpty();
    }

    @Test
    void assignedProjectsReachesOnlyTheProjectsTheSubjectIsOn() {
        member(alice);
        grant(alice, VIEW_BILLS, ASSIGNED_PROJECTS);
        assignToProject(alice, PROJECT_A);

        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_A))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_B))).isFalse();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, null))).isFalse();
        assertThat(service().filter(alice, VIEW_BILLS).projectIds()).containsExactly(PROJECT_A);
    }

    @Test
    void relatedReachesRecordsAssignedToTheSubject() {
        member(alice);
        grant(alice, VIEW_BILLS, RELATED);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withRelated(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withRelated(Set.of(bob.employeeId())))).isFalse();
    }

    @Test
    void relatedNeverReachesARecordTheSubjectIsOnlyTheOwnerOf() {
        member(alice);
        grant(alice, VIEW_BILLS, RELATED);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withOwners(Set.of(alice.employeeId())))).isFalse();
    }

    @Test
    void relatedReachesRecordsCarryingTheSubjectsCustomer() {
        member(alice);
        grant(alice, VIEW_BILLS, RELATED);
        linkCustomer(alice, CUSTOMER);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withCustomers(Set.of(CUSTOMER)))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_B).withCustomers(Set.of(OTHER_CUSTOMER)))).isFalse();
    }

    @Test
    void relatedReachesRecordsCarryingTheSubjectsVendor() {
        member(alice);
        grant(alice, VIEW_BILLS, RELATED);
        linkVendor(alice, VENDOR);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withVendors(Set.of(VENDOR)))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withVendors(Set.of(VENDOR + 1)))).isFalse();
    }

    @Test
    void aCustomerOrVendorLinkGivesNothingWithoutARelatedGrant() {
        member(alice);
        grant(alice, VIEW_BILLS, OWN);
        linkCustomer(alice, CUSTOMER);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_A).withCustomers(Set.of(CUSTOMER)))).isFalse();
    }

    @Test
    void ownReachesRecordsAboutTheSubjectAndNothingAssignedToThem() {
        member(alice);
        grant(alice, VIEW_BILLS, OWN);

        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, null).withOwners(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, null).withOwners(Set.of(bob.employeeId())))).isFalse();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, null).withRelated(Set.of(alice.employeeId())))).isFalse();
    }

    @Test
    void scopesAddTogether() {
        member(alice);
        grant(alice, VIEW_BILLS, ASSIGNED_PROJECTS);
        grant(alice, VIEW_BILLS, OWN);
        assignToProject(alice, PROJECT_A);

        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_A))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS,
                RecordFacts.of(ORG, PROJECT_B).withOwners(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, VIEW_BILLS, RecordFacts.of(ORG, PROJECT_B))).isFalse();
        assertThat(service().grantsFor(alice).scopesFor(VIEW_BILLS))
                .containsExactlyInAnyOrder(ASSIGNED_PROJECTS, OWN);
    }

    @Test
    void theAreaCheckIsTrueWithAnyScope() {
        member(alice);
        grant(alice, VIEW_BILLS, OWN);

        assertThat(service().check(alice, VIEW_BILLS)).isTrue();
    }

    @Test
    void grantsToOneSubjectDoNotReachAnother() {
        member(alice);
        member(bob);
        grant(alice, VIEW_BILLS, ALL);

        assertThat(service().check(bob, VIEW_BILLS)).isFalse();
    }

    @Test
    void theFilterAdmitsExactlyTheRecordsTheCheckAllows() {
        member(alice);
        grant(alice, VIEW_BILLS, ASSIGNED_PROJECTS);
        grant(alice, VIEW_BILLS, RELATED);
        grant(alice, VIEW_BILLS, OWN);
        assignToProject(alice, PROJECT_A);
        linkCustomer(alice, CUSTOMER);
        linkVendor(alice, VENDOR);

        List<RecordFacts> records = List.of(
                RecordFacts.of(ORG, PROJECT_A),
                RecordFacts.of(ORG, PROJECT_B),
                RecordFacts.of(ORG, null),
                RecordFacts.of(ORG, PROJECT_B).withOwners(Set.of(alice.employeeId())),
                RecordFacts.of(ORG, PROJECT_B).withOwners(Set.of(bob.employeeId())),
                RecordFacts.of(ORG, PROJECT_B).withRelated(Set.of(alice.employeeId())),
                RecordFacts.of(ORG, PROJECT_B).withCustomers(Set.of(CUSTOMER)),
                RecordFacts.of(ORG, PROJECT_B).withCustomers(Set.of(OTHER_CUSTOMER)),
                RecordFacts.of(ORG, PROJECT_B).withVendors(Set.of(VENDOR)));
        AccessFilter filter = service().filter(alice, VIEW_BILLS);

        for (RecordFacts record : records) {
            assertThat(filter.admits(record))
                    .as("filter and check agree on %s", record)
                    .isEqualTo(service().check(alice, VIEW_BILLS, record));
        }
    }
}
