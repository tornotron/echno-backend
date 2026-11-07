package org.tornotron.echno_backend.modules.access.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.ALL;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.ASSIGNED_PROJECTS;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.OWN;
import static org.tornotron.echno_backend.modules.access.api.AccessScope.RELATED;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules every access engine keeps, run against each engine by extending this class.
 *
 * <p>Set-up goes through the abstract methods, so a test reads the same whatever the engine
 * stores: a subject is made a member, granted a permission with a scope, put on a project, or
 * linked to a customer or vendor. An engine passes when every test here does; that is what makes
 * one engine safe to swap for another.
 *
 * <p>The ids come from {@link #world()}, so an engine on a real database can supply rows it created.
 * In every world alice and bob start as people who are not active members.
 *
 * <p>{@code @Transactional} does nothing for an engine in maps. An engine on a database runs under
 * Spring's test transaction, and Spring looks for the annotation on the class that declares the test
 * method, which is this one.
 */
@Transactional
public abstract class AccessServiceContract {

    /** The organizations, people and records a test talks about. */
    public record World(
            long org,
            long otherOrg,
            long projectA,
            long projectB,
            UUID customer,
            UUID otherCustomer,
            long vendor,
            long otherVendor,
            AccessSubject alice,
            AccessSubject bob) {
    }

    protected static final AccessPermission READ_BILLS = AccessPermission.of("progress-bills:read");
    protected static final AccessPermission CERTIFY_BILLS = AccessPermission.of("progress-bills:certify");

    protected long org;
    protected long otherOrg;
    protected long projectA;
    protected long projectB;
    protected UUID customer;
    protected UUID otherCustomer;
    protected long vendor;
    protected long otherVendor;
    protected AccessSubject alice;
    protected AccessSubject bob;

    @BeforeEach
    void readTheWorld() {
        World world = world();
        org = world.org();
        otherOrg = world.otherOrg();
        projectA = world.projectA();
        projectB = world.projectB();
        customer = world.customer();
        otherCustomer = world.otherCustomer();
        vendor = world.vendor();
        otherVendor = world.otherVendor();
        alice = world.alice();
        bob = world.bob();
    }

    /** The ids this engine's tests use. */
    protected abstract World world();

    /** The engine under test, built over whatever {@code member}, {@code grant} and the rest set up. */
    protected abstract AccessService service();

    /** Makes the subject an active member of their organization. */
    protected abstract void member(AccessSubject subject);

    /** Makes the subject's membership inactive; they keep whatever was granted. */
    protected abstract void deactivate(AccessSubject subject);

    /** Gives the subject a grant that holds across the organization. */
    protected abstract void grant(AccessSubject subject, AccessPermission permission, AccessScope scope);

    /** Gives the subject a grant held only on one project. */
    protected abstract void grantOnProject(AccessSubject subject, AccessPermission permission, AccessScope scope, long projectId);

    protected abstract void assignToProject(AccessSubject subject, long projectId);

    protected abstract void linkCustomer(AccessSubject subject, UUID customerId);

    protected abstract void linkVendor(AccessSubject subject, long vendorId);

    @Test
    void aMemberWithNoGrantHoldsNothing() {
        member(alice);

        assertThat(service().check(alice, READ_BILLS)).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isFalse();
        assertThat(service().filter(alice, READ_BILLS).isEmpty()).isTrue();
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
        grant(alice, READ_BILLS, ALL);

        assertThat(service().check(alice, READ_BILLS)).isTrue();
        assertThat(service().check(alice, CERTIFY_BILLS)).isFalse();
    }

    @Test
    void allReachesEveryRecordInTheOrganization() {
        member(alice);
        grant(alice, READ_BILLS, ALL);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, null))).isTrue();
        assertThat(service().filter(alice, READ_BILLS).everything()).isTrue();
    }

    @Test
    void aRecordInAnotherOrganizationIsNeverAllowed() {
        member(alice);
        grant(alice, READ_BILLS, ALL);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(otherOrg, projectA))).isFalse();
        assertThat(service().filter(alice, READ_BILLS).admits(RecordFacts.of(otherOrg, projectA))).isFalse();
    }

    @Test
    void aRecordInAnotherOrganizationIsNotAllowedByAnyLinkEither() {
        member(alice);
        grant(alice, READ_BILLS, ASSIGNED_PROJECTS);
        grant(alice, READ_BILLS, RELATED);
        grant(alice, READ_BILLS, OWN);
        grantOnProject(alice, READ_BILLS, ALL, projectB);
        assignToProject(alice, projectA);
        linkCustomer(alice, customer);
        linkVendor(alice, vendor);
        RecordFacts strangerOnSameIds = new RecordFacts(otherOrg, projectA, Set.of(alice.employeeId()),
                Set.of(alice.employeeId()), Set.of(customer), Set.of(vendor));
        RecordFacts strangerOnGrantedProject = RecordFacts.of(otherOrg, projectB);

        assertThat(service().check(alice, READ_BILLS, strangerOnSameIds)).isFalse();
        assertThat(service().check(alice, READ_BILLS, strangerOnGrantedProject)).isFalse();
        AccessFilter filter = service().filter(alice, READ_BILLS);
        assertThat(filter.admits(strangerOnSameIds)).isFalse();
        assertThat(filter.admits(strangerOnGrantedProject)).isFalse();
    }

    @Test
    void anInactiveMemberHoldsNothingWhateverWasGranted() {
        member(alice);
        grant(alice, READ_BILLS, ALL);
        deactivate(alice);

        assertThat(service().check(alice, READ_BILLS)).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isFalse();
        assertThat(service().filter(alice, READ_BILLS).isEmpty()).isTrue();
        assertThat(service().grantsFor(alice).scopes()).isEmpty();
    }

    @Test
    void assignedProjectsReachesOnlyTheProjectsTheSubjectIsOn() {
        member(alice);
        grant(alice, READ_BILLS, ASSIGNED_PROJECTS);
        assignToProject(alice, projectA);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, null))).isFalse();
        assertThat(service().filter(alice, READ_BILLS).projectIds()).containsExactly(projectA);
    }

    @Test
    void relatedReachesRecordsAssignedToTheSubject() {
        member(alice);
        grant(alice, READ_BILLS, RELATED);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withRelated(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withRelated(Set.of(bob.employeeId())))).isFalse();
    }

    @Test
    void relatedNeverReachesARecordTheSubjectIsOnlyTheOwnerOf() {
        member(alice);
        grant(alice, READ_BILLS, RELATED);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withOwners(Set.of(alice.employeeId())))).isFalse();
    }

    @Test
    void relatedReachesRecordsCarryingTheSubjectsCustomer() {
        member(alice);
        grant(alice, READ_BILLS, RELATED);
        linkCustomer(alice, customer);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withCustomers(Set.of(customer)))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectB).withCustomers(Set.of(otherCustomer)))).isFalse();
    }

    @Test
    void relatedReachesRecordsCarryingTheSubjectsVendor() {
        member(alice);
        grant(alice, READ_BILLS, RELATED);
        linkVendor(alice, vendor);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withVendors(Set.of(vendor)))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withVendors(Set.of(otherVendor)))).isFalse();
    }

    @Test
    void aCustomerOrVendorLinkGivesNothingWithoutARelatedGrant() {
        member(alice);
        grant(alice, READ_BILLS, OWN);
        linkCustomer(alice, customer);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withCustomers(Set.of(customer)))).isFalse();
    }

    @Test
    void ownReachesTheSubjectsOwnRecordsAndNothingAssignedToThem() {
        member(alice);
        grant(alice, READ_BILLS, OWN);

        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, null).withOwners(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, null).withOwners(Set.of(bob.employeeId())))).isFalse();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, null).withRelated(Set.of(alice.employeeId())))).isFalse();
    }

    @Test
    void scopesAddTogether() {
        member(alice);
        grant(alice, READ_BILLS, ASSIGNED_PROJECTS);
        grant(alice, READ_BILLS, OWN);
        assignToProject(alice, projectA);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectB).withOwners(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();
        assertThat(service().grantsFor(alice).scopesFor(READ_BILLS))
                .containsExactlyInAnyOrder(ASSIGNED_PROJECTS, OWN);
    }

    @Test
    void theAreaCheckIsTrueWithAnyScope() {
        member(alice);
        grant(alice, READ_BILLS, OWN);

        assertThat(service().check(alice, READ_BILLS)).isTrue();
    }

    @Test
    void grantsToOneSubjectDoNotReachAnother() {
        member(alice);
        member(bob);
        grant(alice, READ_BILLS, ALL);

        assertThat(service().check(bob, READ_BILLS)).isFalse();
    }

    @Test
    void theFilterAdmitsExactlyTheRecordsTheCheckAllows() {
        member(alice);
        grant(alice, READ_BILLS, ASSIGNED_PROJECTS);
        grant(alice, READ_BILLS, RELATED);
        grant(alice, READ_BILLS, OWN);
        assignToProject(alice, projectA);
        linkCustomer(alice, customer);
        linkVendor(alice, vendor);

        List<RecordFacts> records = List.of(
                RecordFacts.of(org, projectA),
                RecordFacts.of(org, projectB),
                RecordFacts.of(org, null),
                RecordFacts.of(org, projectB).withOwners(Set.of(alice.employeeId())),
                RecordFacts.of(org, projectB).withOwners(Set.of(bob.employeeId())),
                RecordFacts.of(org, projectB).withRelated(Set.of(alice.employeeId())),
                RecordFacts.of(org, projectB).withCustomers(Set.of(customer)),
                RecordFacts.of(org, projectB).withCustomers(Set.of(otherCustomer)),
                RecordFacts.of(org, projectB).withVendors(Set.of(vendor)));
        AccessFilter filter = service().filter(alice, READ_BILLS);

        for (RecordFacts record : records) {
            assertThat(filter.admits(record))
                    .as("filter and check agree on %s", record)
                    .isEqualTo(service().check(alice, READ_BILLS, record));
        }
    }

    @Test
    void allOnOneProjectReachesEveryRecordOfThatProjectAndNothingElse() {
        member(alice);
        grantOnProject(alice, READ_BILLS, ALL, projectA);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, null))).isFalse();
        AccessFilter filter = service().filter(alice, READ_BILLS);
        assertThat(filter.everything()).isFalse();
        assertThat(filter.projectIds()).containsExactly(projectA);
    }

    @Test
    void ownOnOneProjectReachesTheSubjectsOwnRecordsOnThatProjectOnly() {
        member(alice);
        grantOnProject(alice, READ_BILLS, OWN, projectA);
        Set<Long> self = Set.of(alice.employeeId());

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA).withOwners(self))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB).withOwners(self))).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, null).withOwners(self))).isFalse();
    }

    @Test
    void relatedOnOneProjectReachesLinkedRecordsOnThatProjectOnly() {
        member(alice);
        grantOnProject(alice, READ_BILLS, RELATED, projectA);
        linkCustomer(alice, customer);
        linkVendor(alice, vendor);
        Set<Long> self = Set.of(alice.employeeId());

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA).withRelated(self))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withCustomers(Set.of(customer)))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectA).withVendors(Set.of(vendor)))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB).withRelated(self))).isFalse();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectB).withCustomers(Set.of(customer)))).isFalse();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectB).withVendors(Set.of(vendor)))).isFalse();
    }

    @Test
    void assignedProjectsOnOneProjectNeedsTheSubjectToBeOnThatProject() {
        member(alice);
        grantOnProject(alice, READ_BILLS, ASSIGNED_PROJECTS, projectA);
        assignToProject(alice, projectB);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();

        assignToProject(alice, projectA);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();
    }

    @Test
    void aProjectGrantIsHeldInTheAreaButIsNotAnOrganizationWideScope() {
        member(alice);
        grantOnProject(alice, READ_BILLS, ALL, projectA);

        assertThat(service().check(alice, READ_BILLS)).isTrue();
        AccessGrants grants = service().grantsFor(alice);
        assertThat(grants.holds(READ_BILLS)).isTrue();
        assertThat(grants.scopesFor(READ_BILLS)).isEmpty();
        assertThat(grants.scopesOn(projectA, READ_BILLS)).containsExactly(ALL);
        assertThat(grants.scopesOn(projectB, READ_BILLS)).isEmpty();
    }

    @Test
    void anInactiveMemberHoldsNothingOnAProjectEither() {
        member(alice);
        grantOnProject(alice, READ_BILLS, ALL, projectA);
        deactivate(alice);

        assertThat(service().check(alice, READ_BILLS)).isFalse();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isFalse();
        assertThat(service().filter(alice, READ_BILLS).isEmpty()).isTrue();
        assertThat(service().grantsFor(alice).projectScopes()).isEmpty();
    }

    @Test
    void anOrganizationWideGrantAndAProjectGrantAddTogether() {
        member(alice);
        grant(alice, READ_BILLS, OWN);
        grantOnProject(alice, READ_BILLS, ALL, projectA);

        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectA))).isTrue();
        assertThat(service().check(alice, READ_BILLS,
                RecordFacts.of(org, projectB).withOwners(Set.of(alice.employeeId())))).isTrue();
        assertThat(service().check(alice, READ_BILLS, RecordFacts.of(org, projectB))).isFalse();
    }

    @Test
    void theFilterAdmitsExactlyTheRecordsTheCheckAllowsOnAProjectGrant() {
        member(alice);
        grantOnProject(alice, READ_BILLS, OWN, projectA);
        grantOnProject(alice, READ_BILLS, RELATED, projectA);
        linkCustomer(alice, customer);
        linkVendor(alice, vendor);
        Set<Long> self = Set.of(alice.employeeId());

        List<RecordFacts> records = List.of(
                RecordFacts.of(org, projectA),
                RecordFacts.of(org, projectB),
                RecordFacts.of(org, null),
                RecordFacts.of(org, projectA).withOwners(self),
                RecordFacts.of(org, projectB).withOwners(self),
                RecordFacts.of(org, projectA).withRelated(self),
                RecordFacts.of(org, projectB).withRelated(self),
                RecordFacts.of(org, projectA).withCustomers(Set.of(customer)),
                RecordFacts.of(org, projectB).withCustomers(Set.of(customer)),
                RecordFacts.of(org, projectA).withVendors(Set.of(vendor)),
                RecordFacts.of(org, null).withOwners(self));
        AccessFilter filter = service().filter(alice, READ_BILLS);

        for (RecordFacts record : records) {
            assertThat(filter.admits(record))
                    .as("filter and check agree on %s", record)
                    .isEqualTo(service().check(alice, READ_BILLS, record));
        }
    }
}
