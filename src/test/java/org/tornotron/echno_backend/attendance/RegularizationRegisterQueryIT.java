package org.tornotron.echno_backend.attendance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regularization register query against a real CockroachDB: a decided request can be found at
 * all, the approver narrows it, and neither the rows nor the count reach outside the tenant.
 *
 * <p>Issue #637. The register's only listing returned {@code PENDING} rows, and
 * {@code processRegularization} stamps the approver in the same call that moves a request off
 * {@code PENDING}. A row with an approver was therefore never in the list and a row in the list
 * never had one, so filtering by approver could not match anything, ever. Every test here fails
 * against that listing rather than merely describing it.
 *
 * <p>Exercised at the repository and specification rather than through the service, because that
 * is where the two things at risk actually live: the {@code orgFilter} the criteria query inherits,
 * and the separate count query behind {@code getTotalElements}. The service is a {@code map} over
 * this page and is pinned by {@code RegularizationListingTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RegularizationRegisterQueryIT extends AbstractIntegrationTest {

    @Autowired
    private AttendanceRegularizationRepository regularizationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** The approver whose decisions the register is asked for. An employee id. */
    private static final long APPROVER = 3100L;

    /** A second approver, so a filter that matched every decided row would be caught. */
    private static final long OTHER_APPROVER = 3200L;

    /** The requester. Deliberately far from every approver id above. */
    private static final long REQUESTER_EMPLOYEE = 7700L;

    /**
     * The requester's platform user id.
     *
     * <p>Held apart from {@link #REQUESTER_EMPLOYEE} on purpose. A request carries both
     * {@code requested_by_id}, which is an employee id, and {@code requested_by_user_id}, which is
     * not; on a fresh database the two sequences run in lockstep, so a query that read one for the
     * other would pass on the coincidence. Seeding them apart is what makes that a failure, and
     * {@link #theRegisterKeepsTheEmployeeIdAndTheUserIdApart} asserts the gap rather than assuming
     * it.
     */
    private static final long REQUESTER_USER = 91500L;

    private Long orgAId;
    private Long orgBId;

    /**
     * Distinguishes the seeded attendance rows.
     *
     * <p>Attendance is unique per employee, date and project, and every request here is raised by
     * the one employee on the one project, so each needs its own day.
     */
    private int seededDay;

    /**
     * Base timestamp the seeded requests are spaced out from.
     *
     * <p>They must not share one: rows with an identical {@code requestedAt} would satisfy a
     * descending-order assertion no matter what the query did, so the ordering test would pass
     * against an unordered query.
     */
    private static final LocalDateTime REGISTER_EPOCH = LocalDateTime.of(2026, 8, 12, 8, 30);

    @BeforeEach
    void seed() {
        TenantContext.clear();
        disableOrgFilter();
        seededDay = 0;

        Organization orgA = persistOrganization("Reg Org A");
        Organization orgB = persistOrganization("Reg Org B");
        entityManager.flush();
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        // Three requests this tenant's approver decided: two approved, one rejected. The register
        // has to separate those two outcomes, because both write the same column pair.
        persistRegularization(orgA, RegularizationStatus.APPROVED, APPROVER);
        persistRegularization(orgA, RegularizationStatus.APPROVED, APPROVER);
        persistRegularization(orgA, RegularizationStatus.REJECTED, APPROVER);
        // Decided by somebody else, and one still awaiting a decision.
        persistRegularization(orgA, RegularizationStatus.APPROVED, OTHER_APPROVER);
        persistRegularization(orgA, RegularizationStatus.PENDING, null);
        // The other tenant's rows carry the same approver id, which is the whole point: an id the
        // caller supplies must narrow within a tenant and never across one. A third row it did not
        // decide keeps the assertion honest: without it, "every row this tenant has" and "the rows
        // this approver decided" are the same two rows, and a filter that had stopped narrowing
        // would still count correctly.
        persistRegularization(orgB, RegularizationStatus.APPROVED, APPROVER);
        persistRegularization(orgB, RegularizationStatus.APPROVED, APPROVER);
        persistRegularization(orgB, RegularizationStatus.APPROVED, OTHER_APPROVER);

        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void cleanup() {
        disableOrgFilter();
        TenantContext.clear();
    }

    /**
     * A decided request is reachable, which the pending-only listing made impossible.
     *
     * <p>Delete the status filter's ability to take anything but {@code PENDING} and this fails
     * with an empty page: an approved request was simply not in the only collection the register
     * had.
     */
    @Test
    void anApprovedRequestIsReachable() {
        enableOrgFilter(orgAId);

        Page<AttendanceRegularization> approved = regularizationRepository.findAll(
                AttendanceRegularizationSpecifications
                        .withFilters(RegularizationStatus.APPROVED, null, null),
                PageRequest.of(0, 10));

        assertThat(approved.getTotalElements()).isEqualTo(3);
        assertThat(approved.getContent())
                .allSatisfy(row -> assertThat(row.getStatus())
                        .isEqualTo(RegularizationStatus.APPROVED));
    }

    /**
     * The approver narrows the register, and the status separates an approval from a rejection.
     *
     * <p>{@code processRegularization} writes {@code approvedBy} and {@code approvedById} on both
     * outcomes, so the approver id alone answers "requests this person decided" and nothing finer.
     * Pairing it with the status is what makes "approved by X" and "rejected by X" two separate,
     * answerable questions, and it is why this listing carries both rather than the schema growing
     * a second column pair.
     */
    @Test
    void theApproverAndTheStatusTogetherSeparateAnApprovalFromARejection() {
        enableOrgFilter(orgAId);

        assertThat(countMatching(null, APPROVER))
                .as("everything this person decided, either way")
                .isEqualTo(3);
        assertThat(countMatching(RegularizationStatus.APPROVED, APPROVER))
                .as("what this person approved")
                .isEqualTo(2);
        assertThat(countMatching(RegularizationStatus.REJECTED, APPROVER))
                .as("what this person rejected")
                .isEqualTo(1);
        assertThat(countMatching(null, OTHER_APPROVER))
                .as("the other approver's single decision, so the filter is not matching everything")
                .isEqualTo(1);
    }

    /**
     * The approver filter is scoped to the tenant, and so is the count behind it.
     *
     * <p>{@code approvedById} is an id the caller supplies, so the one thing it must never do is
     * widen the read. The {@code orgFilter} is fail-closed by construction since issue #507, but a
     * criteria query has two halves and only one of them returns rows: {@code getTotalElements}
     * comes from a separate count query, and a total that counted every tenant's requests would
     * report the size of another tenant's register through a page of the caller's own.
     *
     * <p>The page is deliberately smaller than the number of matching rows. Spring's
     * {@code PageableExecutionUtils} skips the count query when the first page comes back short
     * and reports the row-list length as the total, so a count assertion on a short page passes
     * whatever the count query would have done and proves nothing. Asking for two of the three
     * matching rows forces the count to run, and the assertion that the total is three while the
     * page holds two is what shows it ran: a skipped count could only have said two.
     *
     * <p>The other tenant holds two more requests with the same approver, so a count that ignored
     * the filter would read five rather than three.
     */
    @Test
    void theApproverFilterAndItsCountAreBothScopedToTheTenant() {
        enableOrgFilter(orgAId);

        Page<AttendanceRegularization> page = regularizationRepository.findAll(
                AttendanceRegularizationSpecifications.withFilters(null, APPROVER, null),
                PageRequest.of(0, 2));

        assertThat(page.getContent())
                .as("the page is smaller than the match count, which is what makes the count query run")
                .hasSize(2);
        assertThat(page.getTotalElements())
                .as("three matching requests in this tenant, and the other tenant's two are not "
                        + "counted; a total of two would mean the count query never ran, and five "
                        + "would mean it ran unscoped")
                .isEqualTo(3);
        assertThat(page.getContent())
                .allSatisfy(row -> assertThat(row.getOrganization().getId()).isEqualTo(orgAId));
    }

    /** The other tenant sees only its own two, by the same filter and the same approver id. */
    @Test
    void theOtherTenantSeesOnlyItsOwn() {
        enableOrgFilter(orgBId);

        Page<AttendanceRegularization> page = regularizationRepository.findAll(
                AttendanceRegularizationSpecifications.withFilters(null, APPROVER, null),
                PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements())
                .as("a short page would have skipped the count, so this asks for one of two")
                .isEqualTo(2);
        assertThat(page.getContent())
                .allSatisfy(row -> assertThat(row.getOrganization().getId()).isEqualTo(orgBId));
    }

    /**
     * The requester filter reads the employee id and not the user id beside it.
     *
     * <p>The two are separate columns holding separate sequences, and the inequality is asserted
     * rather than assumed: on a fresh database they run in lockstep, and a query reading the wrong
     * one would then return the right rows for the wrong reason.
     */
    @Test
    void theRegisterKeepsTheEmployeeIdAndTheUserIdApart() {
        enableOrgFilter(orgAId);

        assertThat(REQUESTER_EMPLOYEE)
                .as("the seed has to hold the two sequences apart, or this test proves nothing")
                .isNotEqualTo(REQUESTER_USER);

        assertThat(countMatchingRequester(REQUESTER_EMPLOYEE))
                .as("every seeded request in this tenant carries this requester employee id")
                .isEqualTo(5);
        assertThat(countMatchingRequester(REQUESTER_USER))
                .as("the requester filter must not match on requested_by_user_id")
                .isZero();
    }

    /**
     * Walking the register one row at a time visits every request exactly once.
     *
     * <p>This is the property paging is for, and an unordered page silently breaks it. Without an
     * {@code ORDER BY}, the engine may return rows in a different order for each {@code LIMIT ...
     * OFFSET ...}, so a traversal can hand back the same row twice and never reach another. A
     * register that quietly drops a request is precisely the failure this listing was added to
     * stop, so it would be the same defect one level down.
     *
     * <p>Asserted as a set over a full walk rather than as a fixed order, because that is the
     * guarantee callers actually depend on. {@link #theRegisterIsOrderedNewestFirst} pins the
     * order itself. Deleting {@code LIST_ORDER} does not always fail this on a small table, since
     * a scan of five rows tends to come back consistently; the ordering test is the one that
     * fails outright, and this one states the reason the ordering is there.
     */
    @Test
    void aFullWalkOfTheRegisterVisitsEveryRequestExactlyOnce() {
        enableOrgFilter(orgAId);

        List<Long> seen = new ArrayList<>();
        for (int pageNo = 0; pageNo < 5; pageNo++) {
            regularizationRepository.findAll(
                            AttendanceRegularizationSpecifications.withFilters(null, null, null),
                            PageRequest.of(pageNo, 1, AttendanceRegularizationSpecifications.LIST_ORDER))
                    .getContent()
                    .forEach(row -> seen.add(row.getId()));
        }

        assertThat(seen)
                .as("five requests in this tenant, each visited once and none twice")
                .hasSize(5)
                .doesNotHaveDuplicates();
    }

    /**
     * The register comes back newest first, with a unique tiebreaker underneath.
     *
     * <p>Newest first is what an approver's queue wants. The tiebreaker is what makes the order
     * total: two requests raised in the same instant would otherwise compare equal, and a page
     * boundary falling inside that run is where a traversal loses a row.
     */
    @Test
    void theRegisterIsOrderedNewestFirst() {
        enableOrgFilter(orgAId);

        List<AttendanceRegularization> rows = regularizationRepository.findAll(
                        AttendanceRegularizationSpecifications.withFilters(null, null, null),
                        PageRequest.of(0, 10, AttendanceRegularizationSpecifications.LIST_ORDER))
                .getContent();

        assertThat(rows).hasSize(5);
        assertThat(rows)
                .extracting(AttendanceRegularization::getRequestedAt)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    private long countMatching(RegularizationStatus status, Long approvedById) {
        return regularizationRepository.findAll(
                        AttendanceRegularizationSpecifications.withFilters(status, approvedById, null),
                        PageRequest.of(0, 100))
                .getTotalElements();
    }

    private long countMatchingRequester(Long requestedById) {
        return regularizationRepository.findAll(
                        AttendanceRegularizationSpecifications.withFilters(null, null, requestedById),
                        PageRequest.of(0, 100))
                .getTotalElements();
    }

    private void persistRegularization(Organization org, RegularizationStatus status, Long approverId) {
        Attendance attendance = Attendance.builder()
                .employeeId(REQUESTER_EMPLOYEE)
                .employeeName("Ravi Kumar")
                .attendanceDate(LocalDate.of(2026, 8, 1).plusDays(seededDay))
                .projectId(1L)
                .projectName("Tower A")
                .status(AttendanceStatus.PRESENT)
                // Set explicitly: the entity's builder carries no @Builder.Default for this, so
                // the field initializer is bypassed and the column is NOT NULL.
                .approvalStatus(ApprovalStatus.PENDING)
                .organization(org)
                .build();
        entityManager.persist(attendance);

        AttendanceRegularization regularization = AttendanceRegularization.builder()
                .attendance(attendance)
                .reason("Phone battery died before evening clock-out")
                .requestedBy("Ravi Kumar")
                .requestedById(REQUESTER_EMPLOYEE)
                .requestedByUserId(REQUESTER_USER)
                .requestedAt(REGISTER_EPOCH.plusHours(seededDay))
                .status(status)
                .organization(org)
                .build();
        if (approverId != null) {
            regularization.setApprovedBy("Anand Rajashekar");
            regularization.setApprovedById(approverId);
            regularization.setApprovedAt(LocalDateTime.of(2026, 8, 12, 11, 0));
        }
        entityManager.persist(regularization);
        seededDay++;
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }

    private void enableOrgFilter(Long orgId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", orgId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }
}
