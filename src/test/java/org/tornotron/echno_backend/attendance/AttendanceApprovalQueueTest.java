package org.tornotron.echno_backend.attendance;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.service.AttendanceCalculationService;
import org.tornotron.echno_backend.attendance.service.AttendanceGeofenceService;
import org.tornotron.echno_backend.attendance.service.AttendanceSettingsService;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The attendance approval queue is the caller's own, and it asks for the same records the approve
 * endpoint would accept.
 *
 * <p>Issue #692. The geofence routing built in #681 names an approver on the record and the
 * decision has worked since; what did not exist was any way to find a record. The two listings are
 * a project on one required date and one employee over a range, so a supervisor with people on
 * several sites had to guess a site and a day, and a day held last week was invisible to anyone not
 * already looking for it.
 *
 * <p>Two things are pinned here, and both are ways the same feature has gone wrong before.
 *
 * <p>The first is where the caller comes from. The leave queue took an {@code approverId} query
 * parameter under a guard that only asked for a role, so an administrator read any colleague's
 * queue while the line managers an approval chain is actually built from could read none of their
 * own. That is the shape closed in #589, #599, #607, #631, #635 and #683, and again across the
 * sweep findings #687 to #691. This pair takes no parameter at all: the id comes from the session,
 * and the same resolution serves both the list and the count, so the badge cannot be counted for
 * one person and the list served for another.
 *
 * <p>The second is what the predicate says, asserted against the criteria calls the specification
 * actually makes rather than by reading it. A queue that offered a record the approve endpoint
 * refuses draws a button the server answers with a 403, and the one refusal that is narrower than
 * the role model is the self-approval rule: {@code requireActorMayApprove} puts it ahead of the
 * role check, so a project manager's own day off site is not theirs to decide either.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceApprovalQueueTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_USER_ID = 9100L;
    private static final Long CALLER_EMPLOYEE_ID = 55L;

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private ShiftTimingRepository shiftTimingRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private ClockEventSequenceValidator sequenceValidator;
    @Mock private AttendanceMapper attendanceMapper;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserContextService userContextService;
    @Mock private AttendanceSecurityService attendanceSecurity;
    @Mock private AttendanceGeofenceService geofenceService;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity, geofenceService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void signedInAsTheNamedApprover() {
        Employee caller = new Employee();
        caller.setId(CALLER_EMPLOYEE_ID);
        when(userContextService.getCurrentUserId()).thenReturn(CALLER_USER_ID);
        when(employeeRepository.findByUserIdAndOrganizationId(CALLER_USER_ID, ORG_ID))
                .thenReturn(Optional.of(caller));
    }

    private void theCallerHasNoEmployeeRecordHere() {
        when(userContextService.getCurrentUserId()).thenReturn(CALLER_USER_ID);
        when(employeeRepository.findByUserIdAndOrganizationId(CALLER_USER_ID, ORG_ID))
                .thenReturn(Optional.empty());
    }

    private void theQueueComesBackEmpty() {
        when(attendanceRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    // ── Where the caller comes from ──────────────────────────────────────────────────────────

    @Test
    void theQueueIsReadForTheSignedInCallerAndTakesNoApproverArgument() {
        signedInAsTheNamedApprover();
        theQueueComesBackEmpty();

        assertThat(service.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS).getContent()).isEmpty();

        assertThat(capturedPredicates())
                .as("the approver the queue is narrowed to is the caller's own employee id")
                .containsEntry("geofenceApproverId", CALLER_EMPLOYEE_ID);
    }

    @Test
    void theCountIsCountedForTheSameCallerTheListIsServedFor() {
        signedInAsTheNamedApprover();
        when(attendanceRepository.count(any(Specification.class))).thenReturn(7L);

        assertThat(service.getPendingApprovalCount()).isEqualTo(7L);

        ArgumentCaptor<Specification<Attendance>> captor = specificationCaptor();
        verify(attendanceRepository).count(captor.capture());
        assertThat(predicatesOf(captor.getValue()))
                .containsEntry("geofenceApproverId", CALLER_EMPLOYEE_ID);
    }

    /**
     * The badge is a count query, not the length of a page.
     *
     * <p>A page total cannot answer this once the queue is longer than a page, and Spring's
     * {@code PageableExecutionUtils} does not even run the count query when the first page comes
     * back short, so a badge derived from a listing would be right only while it did not matter.
     * Seven waiting while the listing would have returned nothing is the shape that separates the
     * two.
     */
    @Test
    void theCountDoesNotComeFromTheListing() {
        signedInAsTheNamedApprover();
        when(attendanceRepository.count(any(Specification.class))).thenReturn(7L);
        theQueueComesBackEmpty();

        assertThat(service.getPendingApprovalCount()).isEqualTo(7L);

        verify(attendanceRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefusedAQueueRatherThanServedSomebodyElses() {
        theCallerHasNoEmployeeRecordHere();

        assertThatThrownBy(() -> service.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS))
                .isInstanceOf(AccessDeniedException.class);

        verify(attendanceRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefusedTheCount() {
        theCallerHasNoEmployeeRecordHere();

        assertThatThrownBy(service::getPendingApprovalCount)
                .isInstanceOf(AccessDeniedException.class);

        verify(attendanceRepository, never()).count(any(Specification.class));
    }

    // ── What the predicate says ──────────────────────────────────────────────────────────────

    /**
     * The queue is the pending geofence exceptions, never the caller's own.
     *
     * <p>Pending on its own is not a queue: every record is created {@code PENDING} and stays
     * there until somebody decides it, so a listing of everything pending a record manager may
     * decide would be the tenant's entire attendance history. The flag is what separates a day
     * held for a decision from a day nobody has looked at.
     */
    @Test
    void theQueueIsTheHeldDaysThatArePendingAndNeverTheCallersOwn() {
        signedInAsTheNamedApprover();
        theQueueComesBackEmpty();

        service.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS);
        Map<String, Object> predicates = capturedPredicates();

        assertThat(predicates).containsEntry("approvalStatus", ApprovalStatus.PENDING);
        assertThat(predicates)
                .as("held for a geofence decision, which is what the flag was added to say")
                .containsEntry("requiresGeofenceApproval", Boolean.TRUE);
        assertThat(predicates)
                .as("a manager who marked from off site does not decide their own day, and the "
                        + "approve endpoint refuses it ahead of any role check")
                .containsEntry("employeeId!", CALLER_EMPLOYEE_ID);
    }

    /**
     * A record manager's queue is not narrowed to the records naming them.
     *
     * <p>{@code resolveApprover} names nobody when the employee has no reporting manager and no
     * project manager is assigned to the site, and the reporting-manager field is set on a small
     * minority of employees, so that is the ordinary case rather than the edge. Those records fall
     * to the record-management roles, and a queue keyed only on the id would drop them: they would
     * sit pending with nobody able to see them.
     */
    @Test
    void aRecordManagerSeesTheHeldDaysThatNameNobody() {
        signedInAsTheNamedApprover();
        when(attendanceSecurity.canManageRecords()).thenReturn(true);
        theQueueComesBackEmpty();

        service.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS);
        Map<String, Object> predicates = capturedPredicates();

        assertThat(predicates)
                .as("no approver predicate, so a record naming nobody is still in the queue")
                .doesNotContainKey("geofenceApproverId");
        assertThat(predicates)
                .as("their own held day is still not theirs to decide")
                .containsEntry("employeeId!", CALLER_EMPLOYEE_ID);
    }

    @Test
    void theQueueIsOrderedAndCapped() {
        signedInAsTheNamedApprover();
        theQueueComesBackEmpty();

        service.getPendingApprovals(0, UnpagedResultCap.MAX_ROWS);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(attendanceRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .as("an unordered page walk can show one record twice and never reach another")
                .isEqualTo(AttendanceApprovalQueueSpecifications.QUEUE_ORDER);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    // ── Reading the specification ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Specification<Attendance>> specificationCaptor() {
        return ArgumentCaptor.forClass(Specification.class);
    }

    private Map<String, Object> capturedPredicates() {
        ArgumentCaptor<Specification<Attendance>> captor = specificationCaptor();
        verify(attendanceRepository).findAll(captor.capture(), any(Pageable.class));
        return predicatesOf(captor.getValue());
    }

    /**
     * Runs the specification against a recording criteria builder and returns what it asked for.
     *
     * <p>Keys are attribute names, with a trailing {@code !} for an inequality. Read this way the
     * predicate set is asserted rather than described, so deleting one of the three conditions is
     * a failure rather than a documentation drift.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> predicatesOf(Specification<Attendance> specification) {
        Map<String, Object> asked = new HashMap<>();
        Map<String, Path> paths = new HashMap<>();

        Root<Attendance> root = mock(Root.class);
        when(root.get(anyString())).thenAnswer(invocation -> {
            String attribute = invocation.getArgument(0);
            return paths.computeIfAbsent(attribute, name -> mock(Path.class));
        });

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate predicate = mock(Predicate.class);
        when(cb.equal(any(), any(Object.class))).thenAnswer(invocation -> {
            asked.put(nameOf(paths, invocation.getArgument(0)), invocation.getArgument(1));
            return predicate;
        });
        when(cb.notEqual(any(), any(Object.class))).thenAnswer(invocation -> {
            asked.put(nameOf(paths, invocation.getArgument(0)) + "!", invocation.getArgument(1));
            return predicate;
        });
        when(cb.isTrue(any())).thenAnswer(invocation -> {
            asked.put(nameOf(paths, invocation.getArgument(0)), Boolean.TRUE);
            return predicate;
        });
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        specification.toPredicate(root, mock(CriteriaQuery.class), cb);
        return asked;
    }

    @SuppressWarnings("rawtypes")
    private String nameOf(Map<String, Path> paths, Object path) {
        return paths.entrySet().stream()
                .filter(entry -> entry.getValue() == path)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown");
    }
}
