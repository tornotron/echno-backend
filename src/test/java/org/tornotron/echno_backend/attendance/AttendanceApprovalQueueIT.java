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
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attendance approval queue against a real CockroachDB: who sees which held day, and what the
 * count says once the queue is longer than a page.
 *
 * <p>Issue #692. Exercised at the repository and specification rather than through the service,
 * because that is where the two things at risk live: the {@code orgFilter} the criteria query
 * inherits, and the count query, which is a separate statement from the one that returns rows.
 * Who the caller is and how the two branches are chosen belong to the service and are pinned by
 * {@link AttendanceApprovalQueueTest}.
 *
 * <p>The seed is built so that no assertion can pass by accident: every excluded row is excluded
 * for exactly one reason, and each reason has a row of its own. A queue that dropped its status
 * filter, its flag filter, its self-exclusion, its approver filter or its tenant scope fails a
 * different test here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AttendanceApprovalQueueIT extends AbstractIntegrationTest {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** The employee a held day names as its approver. Holds no record-management role. */
    private static final long APPROVER = 4100L;

    /** A second named approver, so a filter that had stopped narrowing would be caught. */
    private static final long OTHER_APPROVER = 4200L;

    /** A caller who holds the record-management roles, so every held day is theirs to decide. */
    private static final long RECORD_MANAGER = 4300L;

    /** Ordinary employees whose days are the ones being held. */
    private static final long WORKER_ONE = 7100L;
    private static final long WORKER_TWO = 7200L;

    private Long orgAId;
    private Long orgBId;

    /** Attendance is unique per employee, date and project, so each seeded row needs its own day. */
    private int seededDay;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        disableOrgFilter();
        seededDay = 0;

        Organization orgA = persistOrganization("Queue Org A");
        Organization orgB = persistOrganization("Queue Org B");
        entityManager.flush();
        orgAId = orgA.getId();
        orgBId = orgB.getId();

        // Waiting on APPROVER by name.
        persistAttendance(orgA, WORKER_ONE, true, ApprovalStatus.PENDING, APPROVER);
        // Waiting on nobody by name: resolveApprover found neither a reporting manager nor a
        // project manager on the site. These fall to the record managers, and a queue keyed only
        // on the id would leave them where nobody can see them.
        persistAttendance(orgA, WORKER_TWO, true, ApprovalStatus.PENDING, null);
        // Waiting on somebody else.
        persistAttendance(orgA, WORKER_ONE, true, ApprovalStatus.PENDING, OTHER_APPROVER);
        // Already decided: the decision is made, so it is nobody's queue any more.
        persistAttendance(orgA, WORKER_ONE, true, ApprovalStatus.APPROVED, APPROVER);
        // Pending but never held: this is the resting state of every attendance record ever
        // created, and the row that makes "everything pending" the wrong query.
        persistAttendance(orgA, WORKER_TWO, false, ApprovalStatus.PENDING, null);
        // The record manager's own day off site, naming APPROVER. Nobody signs off their own
        // absence from the site, whatever roles they hold.
        persistAttendance(orgA, RECORD_MANAGER, true, ApprovalStatus.PENDING, APPROVER);
        // The named approver's own day. Held, pending, and not theirs either.
        persistAttendance(orgA, APPROVER, true, ApprovalStatus.PENDING, OTHER_APPROVER);

        // The other tenant's held days carry the same approver id, which is the point: an id must
        // narrow within a tenant and never across one.
        persistAttendance(orgB, WORKER_ONE, true, ApprovalStatus.PENDING, APPROVER);
        persistAttendance(orgB, WORKER_TWO, true, ApprovalStatus.PENDING, APPROVER);
        persistAttendance(orgB, WORKER_ONE, true, ApprovalStatus.PENDING, OTHER_APPROVER);

        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void cleanup() {
        disableOrgFilter();
        TenantContext.clear();
    }

    /**
     * The named approver's queue is the days that name them, and nothing else.
     *
     * <p>Two of the seeded rows name APPROVER and are still pending and still held. The third row
     * naming them has been decided, and the fourth is in the other tenant.
     */
    @Test
    void theNamedApproverSeesTheHeldDaysThatNameThemAndNothingElse() {
        enableOrgFilter(orgAId);

        List<Attendance> queue = queueFor(APPROVER, false);

        assertThat(queue)
                .as("the worker's held day and the record manager's own, both naming this approver")
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getGeofenceApproverId()).isEqualTo(APPROVER);
                    assertThat(row.getRequiresGeofenceApproval()).isTrue();
                    assertThat(row.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
                })
                .extracting(Attendance::getEmployeeId)
                .containsExactlyInAnyOrder(WORKER_ONE, RECORD_MANAGER);
    }

    /**
     * A record manager sees the held days that name nobody, which a queue keyed on the id drops.
     *
     * <p>{@code resolveApprover} returns null when the employee has no reporting manager and no
     * project manager is assigned to the site. The reporting-manager field is set on a small
     * minority of employees on staging, so this is the ordinary case rather than the edge, and a
     * record nobody can find is the failure this endpoint exists to fix.
     */
    @Test
    void aRecordManagerSeesEveryHeldDayIncludingTheOnesNamingNobody() {
        enableOrgFilter(orgAId);

        List<Attendance> queue = queueFor(RECORD_MANAGER, true);

        assertThat(queue)
                .extracting(Attendance::getGeofenceApproverId)
                .as("the record naming nobody is in the queue, alongside the ones naming others")
                .containsExactlyInAnyOrder(APPROVER, null, OTHER_APPROVER, OTHER_APPROVER);
    }

    /**
     * Nobody's own held day is in their queue, whichever branch put them there.
     *
     * <p>{@code requireActorMayApprove} refuses a self-approval on a held record ahead of the role
     * check, so a record manager is refused their own day exactly as an ordinary employee is.
     * Offering it in the queue would draw a button the server answers with a 403.
     */
    @Test
    void nobodySeesTheirOwnHeldDay() {
        enableOrgFilter(orgAId);

        assertThat(queueFor(RECORD_MANAGER, true))
                .as("the manager's own day off site is held and pending, and still not theirs")
                .extracting(Attendance::getEmployeeId)
                .doesNotContain(RECORD_MANAGER);

        assertThat(queueFor(APPROVER, false))
                .extracting(Attendance::getEmployeeId)
                .doesNotContain(APPROVER);
    }

    /**
     * The count is a count, not the length of the page beside it.
     *
     * <p>Spring's {@code PageableExecutionUtils} skips the count query when the first page comes
     * back short and reports the row-list length as the total, so an assertion made on a short page
     * proves nothing about counting. This asks for two of the record manager's four and reads the
     * count separately: a badge built from the page would say two.
     */
    @Test
    void theCountIsTheWholeQueueAndNotTheFirstPage() {
        enableOrgFilter(orgAId);

        Page<Attendance> firstPage = attendanceRepository.findAll(
                AttendanceApprovalQueueSpecifications.waitingOn(RECORD_MANAGER, true),
                PageRequest.of(0, 2, AttendanceApprovalQueueSpecifications.QUEUE_ORDER));

        assertThat(firstPage.getContent())
                .as("the page is deliberately smaller than the queue")
                .hasSize(2);
        assertThat(attendanceRepository.count(
                AttendanceApprovalQueueSpecifications.waitingOn(RECORD_MANAGER, true)))
                .as("four held days are waiting on this caller; a count that read the page would "
                        + "say two, and one that ignored the tenant would say seven")
                .isEqualTo(4);
    }

    /** The other tenant's queue holds its own rows only, read with the same approver id. */
    @Test
    void theQueueIsScopedToTheTenant() {
        enableOrgFilter(orgBId);

        assertThat(queueFor(APPROVER, false))
                .as("two held days name this approver here, and the other tenant's two are not in it")
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getOrganization().getId()).isEqualTo(orgBId));
    }

    /** Newest day first, so a page walk visits every held day exactly once. */
    @Test
    void theQueueIsOrderedNewestDayFirst() {
        enableOrgFilter(orgAId);

        List<Attendance> queue = queueFor(RECORD_MANAGER, true);

        assertThat(queue)
                .extracting(Attendance::getAttendanceDate)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    private List<Attendance> queueFor(long callerEmployeeId, boolean decidesEveryRecord) {
        return attendanceRepository.findAll(
                        AttendanceApprovalQueueSpecifications
                                .waitingOn(callerEmployeeId, decidesEveryRecord),
                        PageRequest.of(0, 50, AttendanceApprovalQueueSpecifications.QUEUE_ORDER))
                .getContent();
    }

    private void persistAttendance(Organization org, long employeeId, boolean held,
                                   ApprovalStatus approvalStatus, Long geofenceApproverId) {
        Attendance attendance = Attendance.builder()
                .employeeId(employeeId)
                .employeeName("Employee " + employeeId)
                .attendanceDate(LocalDate.of(2026, 9, 1).plusDays(seededDay))
                .projectId(1L)
                .projectName("Tower A")
                .status(AttendanceStatus.PRESENT)
                // Set explicitly: the entity's builder carries no @Builder.Default for this, so
                // the field initializer is bypassed and the column is NOT NULL.
                .approvalStatus(approvalStatus)
                .requiresGeofenceApproval(held)
                .geofenceApproverId(geofenceApproverId)
                .organization(org)
                .build();
        entityManager.persist(attendance);
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
