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
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval filters on the project attendance listing, against a real CockroachDB.
 *
 * <p>Issue #712. The listing is server-paged, so the narrowing has to happen in the query or the
 * page count lies: a client-side filter over the current page shows three rows under a footer
 * reading "1-20 of 137".
 *
 * <p>Two filters rather than one, because "pending" means two different things here and only one
 * of them is small. {@code checkIn} creates every record at {@code approvalStatus = PENDING} and
 * nothing moves it until somebody decides, so the pending set on a normal day is the site's whole
 * roll. The set a person means by "what still needs looking at" is the days held for a punch
 * outside the geofence, which is the {@code requiresGeofenceApproval} flag.
 *
 * <p>The seed makes that difference measurable rather than assertable by luck. Eight of the nine
 * rows on the day are pending, and only two of those are held, so a filter that had quietly
 * stopped narrowing returns eight where the test wants two.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AttendanceProjectApprovalFilterIT extends AbstractIntegrationTest {

    @Autowired
    private AttendanceRepository attendanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final LocalDate THE_DAY = LocalDate.of(2026, 9, 3);
    private static final LocalDate ANOTHER_DAY = THE_DAY.plusDays(1);
    private static final long SITE = 8100L;
    private static final long ANOTHER_SITE = 8200L;

    private Long orgId;
    private long nextEmployeeId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        disableOrgFilter();
        nextEmployeeId = 9000L;

        Organization org = persistOrganization("Filter Org");
        entityManager.flush();
        orgId = org.getId();

        // Six ordinary days: present, pending because nobody has decided anything, not held.
        // These are the rows that make a bare pending filter useless on its own.
        for (int i = 0; i < 6; i++) {
            persist(org, SITE, THE_DAY, AttendanceStatus.PRESENT, ApprovalStatus.PENDING, false);
        }
        // Two held days: a punch fell outside the site and somebody has to decide.
        persist(org, SITE, THE_DAY, AttendanceStatus.PRESENT, ApprovalStatus.PENDING, true);
        persist(org, SITE, THE_DAY, AttendanceStatus.LATE, ApprovalStatus.PENDING, true);
        // One already decided, so it is out of every pending answer.
        persist(org, SITE, THE_DAY, AttendanceStatus.PRESENT, ApprovalStatus.APPROVED, true);

        // Neighbours that must never appear: the same shape on the next day, and on another site.
        persist(org, SITE, ANOTHER_DAY, AttendanceStatus.PRESENT, ApprovalStatus.PENDING, true);
        persist(org, ANOTHER_SITE, THE_DAY, AttendanceStatus.PRESENT, ApprovalStatus.PENDING, true);

        entityManager.flush();
        entityManager.clear();
        enableOrgFilter(orgId);
    }

    @AfterEach
    void cleanup() {
        disableOrgFilter();
        TenantContext.clear();
    }

    @Test
    void thePlainListingIsTheWholeDayOnTheSite() {
        assertThat(page(null, null))
                .as("nine rows on this site on this day; the next day and the other site are not in it")
                .hasSize(9);
    }

    @Test
    void filteringOnAnApprovalStatusNarrowsToThatDecision() {
        assertThat(page(ApprovalStatus.APPROVED, null))
                .as("one day has been decided")
                .hasSize(1)
                .allSatisfy(row ->
                        assertThat(row.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED));
    }

    @Test
    void filteringOnPendingIsAlmostTheWholeDay() {
        // Not a defect, and the reason the flag below exists. A record is born pending, so this
        // filter separates the decided from the undecided and nothing else. It is worth having
        // for APPROVED and REJECTED; it is not the "what needs looking at" question.
        assertThat(page(ApprovalStatus.PENDING, null))
                .as("eight of the nine rows, which is what a pending filter honestly returns")
                .hasSize(8);
    }

    @Test
    void filteringOnTheHeldFlagIsTheSmallSet() {
        assertThat(page(null, true))
                .as("three days were held, one of which has since been decided")
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.getRequiresGeofenceApproval()).isTrue());
    }

    @Test
    void theTwoFiltersTogetherAreTheSitesOutstandingWork() {
        assertThat(page(ApprovalStatus.PENDING, true))
                .as("held and still undecided: the two days somebody has to look at")
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.getRequiresGeofenceApproval()).isTrue();
                    assertThat(row.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
                });
    }

    @Test
    void askingForTheDaysNotHeldExcludesTheHeldOnes() {
        assertThat(page(null, false))
                .as("false narrows as well as true; it is not read as absent")
                .hasSize(6)
                .allSatisfy(row -> assertThat(row.getRequiresGeofenceApproval()).isFalse());
    }

    @Test
    void theNarrowingHappensInTheQuerySoTheTotalIsHonest() {
        // The listing is paged. If the filter were applied after the page was cut, the page would
        // hold the right rows and the total behind it would still be the unfiltered nine.
        Page<Attendance> firstPage = attendanceRepository.findWithFilters(
                SITE, THE_DAY, null, ApprovalStatus.PENDING, true, null,
                PageRequest.of(0, 1, Sort.by("employeeName")));

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalElements())
                .as("two held pending days, not the nine on the site")
                .isEqualTo(2);
    }

    @Test
    void theOtherFiltersStillCompose() {
        assertThat(page(AttendanceStatus.LATE, ApprovalStatus.PENDING, true, null))
                .as("one of the two held days is a late arrival")
                .hasSize(1);
    }

    private List<Attendance> page(ApprovalStatus approvalStatus, Boolean requiresApproval) {
        return page(null, approvalStatus, requiresApproval, null);
    }

    private List<Attendance> page(AttendanceStatus status, ApprovalStatus approvalStatus,
                                            Boolean requiresApproval, String search) {
        return attendanceRepository.findWithFilters(
                        SITE, THE_DAY, status, approvalStatus, requiresApproval, search,
                        PageRequest.of(0, 50, Sort.by("employeeName")))
                .getContent();
    }

    private void persist(Organization org, long projectId, LocalDate date, AttendanceStatus status,
                         ApprovalStatus approvalStatus, boolean held) {
        long employeeId = nextEmployeeId++;
        Attendance attendance = Attendance.builder()
                .employeeId(employeeId)
                .employeeName("Employee " + employeeId)
                .attendanceDate(date)
                .projectId(projectId)
                .projectName("Site " + projectId)
                .status(status)
                // Set explicitly: the entity's builder carries no @Builder.Default for this, so
                // the field initializer is bypassed and the column is NOT NULL.
                .approvalStatus(approvalStatus)
                .requiresGeofenceApproval(held)
                .organization(org)
                .build();
        entityManager.persist(attendance);
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

    private void enableOrgFilter(Long organizationId) {
        entityManager.unwrap(Session.class)
                .enableFilter("orgFilter")
                .setParameter("organizationId", organizationId);
    }

    private void disableOrgFilter() {
        entityManager.unwrap(Session.class).disableFilter("orgFilter");
    }
}
