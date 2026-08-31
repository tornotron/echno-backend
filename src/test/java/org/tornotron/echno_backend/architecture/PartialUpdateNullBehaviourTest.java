package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.tornotron.echno_backend.architecture.PartialUpdateSurfaces.UpdateSurface;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.StatusTransitionRepository;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.finance.ledger.repositories.CustomerRepository;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeHierarchyService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.issue.Issue;
import org.tornotron.echno_backend.issue.IssueRepository;
import org.tornotron.echno_backend.issue.IssueService;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.user.KeycloakUserProvisioningService;
import org.tornotron.echno_backend.leave.LeaveApprovalService;
import org.tornotron.echno_backend.leave.LeaveBalanceRepository;
import org.tornotron.echno_backend.leave.LeavePolicy;
import org.tornotron.echno_backend.leave.LeavePolicyRepository;
import org.tornotron.echno_backend.leave.LeavePolicyService;
import org.tornotron.echno_backend.leave.LeaveRequest;
import org.tornotron.echno_backend.leave.LeaveRequestRepository;
import org.tornotron.echno_backend.leave.LeaveRequestSequenceRepository;
import org.tornotron.echno_backend.leave.LeaveRequestService;
import org.tornotron.echno_backend.leave.LeaveRequestValidator;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeavePolicyMapper;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationOnboardingSeeder;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.organization.OrganizationService;
import org.tornotron.echno_backend.organization.mapper.OrganizationMapper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;
import org.tornotron.echno_backend.task.TaskService;
import org.tornotron.echno_backend.task.mapper.TaskMapper;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.user.UserService;
import org.tornotron.echno_backend.user.enums.UserRole;
import org.tornotron.echno_backend.user.mapper.UserMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What an explicit null actually does to every key of every partial-update surface.
 *
 * <p>This is the behavioural half of {@link PartialUpdateSurfaces#surfaces()}, and the reason that
 * list can be trusted by {@link PartialUpdateNullContractTest} and, through it, by the published
 * document. For each surface it sends {@code {key: null}} once per declared key and requires the
 * outcome to be the one the list claims: a key not listed as refusing must be applied, and a key
 * listed as refusing must come back as an {@code InvalidRequestException}, which the global handler
 * answers 400 for.
 *
 * <p>Run against the tree before #645 the sweep reports eleven keys, on seven of the eight
 * surfaces, that did neither: {@code TaskUpdateFieldsDto.status},
 * {@code IssueUpdateFieldsDto.assignedToId}, {@code ProjectUpdateFieldsDto.status},
 * {@code projectLatitude} and {@code projectLongitude}, {@code UserUpdateFieldsDto.role},
 * {@code EmployeeUpdateFieldsDto.status} and {@code managerId},
 * {@code LeavePolicyUpdateFieldsDto.annualQuota}, and {@code LeaveRequestUpdateFieldsDto.startDate}
 * and {@code endDate}. Each reached an unguarded {@code Enum.valueOf(null)},
 * {@code ((Number) null).longValue()} or {@code LocalDate.parse(null)} and threw a
 * {@code NullPointerException}, which no handler names, so the endpoint answered 500 to a request
 * whose shape the document said was fine.
 *
 * <p>The sweep is driven off the schema class rather than a written list of keys, so a field added
 * to one of these DTOs is covered here the day it appears.
 *
 * <p>Plain Mockito, no Spring context, so this adds nothing to the cached-context heap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartialUpdateNullBehaviourTest {

    private static final long ORG_ID = 1L;
    private static final long ENTITY_ID = 7L;

    /**
     * Sends a null for every key of one surface and checks each outcome against the list.
     *
     * @param schema The surface's schema class, whose declared fields are the keys to sweep.
     * @param apply Applies one single-key update, throwing whatever the service throws.
     */
    private static void sweep(Class<?> schema, Consumer<Map<String, Object>> apply) {
        UpdateSurface surface = PartialUpdateSurfaces.surfaces().stream()
                .filter(candidate -> candidate.schema().equals(schema))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        schema.getSimpleName() + " is not listed in PartialUpdateSurfaces"));

        List<String> keys = Arrays.stream(schema.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
        assertThat(keys).as("%s declares no fields, so this sweep checks nothing", surface).isNotEmpty();

        List<String> wrong = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> update = new HashMap<>();
            update.put(key, null);
            boolean refuses = surface.refusesNull().contains(key);
            try {
                apply.accept(update);
                if (refuses) {
                    wrong.add(key + ": listed as refusing a null, but the update was accepted");
                }
            } catch (InvalidRequestException e) {
                if (!refuses) {
                    wrong.add(key + ": refused the null with \"" + e.getMessage()
                            + "\", but is not listed as refusing one");
                }
            } catch (RuntimeException e) {
                wrong.add(key + ": threw " + e.getClass().getSimpleName() + " (\"" + e.getMessage()
                        + "\"), which is neither clearing the field nor a 400. An unguarded null "
                        + "reaching a cast or a parser answers 500");
            }
        }

        assertThat(wrong)
                .as("%s does not do to a null what PartialUpdateSurfaces says it does", surface)
                .isEmpty();
    }

    @Nested
    @DisplayName("task")
    class TaskSurface {

        @Mock private TaskRepository taskRepository;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private ProjectRepository projectRepository;
        @Mock private CategoryRepository categoryRepository;
        @Mock private AttachmentService attachmentService;
        @Mock private CurrentEmployeeService currentEmployeeService;
        @Mock private TaskMapper taskMapper;
        @Mock private PayloadValidator payloadValidator;

        @InjectMocks private TaskService service;

        private Task task;

        private void apply(Map<String, Object> updates) {
            Project project = new Project();
            project.setId(3L);
            task = new Task();
            task.setProject(project);
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(taskRepository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(task));
                when(taskRepository.save(any(Task.class))).thenAnswer(i -> i.getArgument(0));
                when(taskRepository.findByProject_Id(any())).thenReturn(List.of());
                service.partialUpdateATask(updates, ENTITY_ID, null, "TASK");
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("status refuses a null rather than answering 500, since the column is NOT NULL")
        void statusRefusesNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("status", null);

            assertThatThrownBy(() -> apply(update))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("status cannot be cleared");
        }
    }

    @Nested
    @DisplayName("issue")
    class IssueSurface {

        @Mock private IssueRepository issueRepository;
        @Mock private TaskRepository taskRepository;
        @Mock private AttachmentService attachmentService;
        @Mock private IssueMapper issueMapper;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private CurrentEmployeeService currentEmployeeService;
        @Mock private PayloadValidator payloadValidator;

        @InjectMocks private IssueService service;

        private Issue issue;

        private void apply(Map<String, Object> updates) {
            issue = new Issue();
            issue.setAssignedTo(new Employee());
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(issueRepository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(issue));
                when(issueRepository.save(any(Issue.class))).thenAnswer(i -> i.getArgument(0));
                service.partialUpdateAnIssue(updates, ENTITY_ID, null, "ISSUE");
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.issue.dto.IssueUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("assignedToId unassigns on a null rather than answering 500")
        void assignedToIdClearsOnNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("assignedToId", null);

            apply(update);

            assertThat(issue.getAssignedTo()).isNull();
        }
    }

    @Nested
    @DisplayName("project")
    class ProjectSurface {

        @Mock private ProjectRepository repository;
        @Mock private OrganizationRepository organizationRepository;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private AttachmentService attachmentService;
        @Mock private ProjectMapper projectMapper;
        @Mock private EmployeeMapper employeeMapper;
        @Mock private ApplicationEventPublisher eventPublisher;
        @Mock private CustomerRepository customerRepository;
        @Mock private PayloadValidator payloadValidator;
        @Mock private UserContextService userContextService;
        @Mock private StatusTransitionRecorder statusTransitionRecorder;
        @Mock private StatusTransitionRepository statusTransitionRepository;
        @Mock private StatusTransitionMapper statusTransitionMapper;

        @InjectMocks private ProjectService service;

        private Project project;

        private void apply(Map<String, Object> updates) {
            project = new Project();
            project.setId(ENTITY_ID);
            project.setStatus(ProjectCreationStatus.open);
            project.setProjectLatitude(12.9f);
            project.setProjectLongitude(80.2f);
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(repository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(project));
                when(repository.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
                when(userContextService.getCurrentUser()).thenReturn(new User());
                service.partialUpdateAProject(updates, ENTITY_ID, null, "PROJECT");
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.project.dto.ProjectUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("both coordinates clear on a null rather than answering 500")
        void coordinatesClearOnNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("projectLatitude", null);
            update.put("projectLongitude", null);

            apply(update);

            assertThat(project.getProjectLatitude()).isNull();
            assertThat(project.getProjectLongitude()).isNull();
        }

        @Test
        @DisplayName("a coordinate still refuses a value outside its range")
        void coordinateRangeIsStillEnforced() {
            Map<String, Object> update = new HashMap<>();
            update.put("projectLatitude", 91);

            assertThatThrownBy(() -> apply(update))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Latitude must be between -90 and 90");
        }
    }

    @Nested
    @DisplayName("organization")
    class OrganizationSurface {

        @Mock private OrganizationRepository repository;
        @Mock private AttachmentService attachmentService;
        @Mock private FileStorageService fileStorageService;
        @Mock private KeycloakGroupService keycloakGroupService;
        @Mock private SubscriptionService subscriptionService;
        @Mock private UserContextService userContextService;
        @Mock private EmployeeService employeeService;
        @Mock private OrganizationMapper organizationMapper;
        @Mock private OrganizationSecurityService orgSecurity;
        @Mock private OrganizationOnboardingSeeder onboardingSeeder;
        @Mock private PayloadValidator payloadValidator;

        @InjectMocks private OrganizationService service;

        private void apply(Map<String, Object> updates) {
            Organization organization = new Organization();
            organization.setId(ENTITY_ID);
            when(repository.findById(any())).thenReturn(Optional.of(organization));
            when(repository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
            service.partialUpdateAnOrganization(updates, ENTITY_ID, null, "ORGANIZATION");
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.organization.dto.OrganizationUpdateFieldsDto.class, this::apply);
        }
    }

    @Nested
    @DisplayName("user")
    class UserSurface {

        @Mock private UserRepository userRepository;
        @Mock private KeycloakUserProvisioningService keycloakUserProvisioningService;
        @Mock private AttachmentService attachmentService;
        @Mock private FileStorageService fileStorageService;
        @Mock private EmployeeMapper employeeMapper;
        @Mock private UserMapper userMapper;
        @Mock private OrganizationMapper organizationMapper;

        @InjectMocks private UserService service;

        private User user;

        private void apply(Map<String, Object> updates) {
            user = new User();
            user.setRole(UserRole.EMPLOYEE);
            user.setDefaultOrganizationId(ORG_ID);
            when(userRepository.findById(any())).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
            service.partialUpdateAnUser(updates, ENTITY_ID, null, null);
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.user.dto.UserUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("role clears on a null rather than answering 500")
        void roleClearsOnNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("role", null);

            apply(update);

            assertThat(user.getRole()).isNull();
        }

        @Test
        @DisplayName("defaultOrganizationId clears on a null rather than refusing it as a non-number")
        void defaultOrganizationIdClearsOnNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("defaultOrganizationId", null);

            apply(update);

            assertThat(user.getDefaultOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("employee")
    class EmployeeSurface {

        @Mock private EmployeeRepository employeeRepository;
        @Mock private OrganizationRepository organizationRepository;
        @Mock private UserRepository userRepository;
        @Mock private KeycloakGroupService keycloakGroupService;
        @Mock private EmployeeMapper employeeMapper;
        @Mock private EmployeeHierarchyService employeeHierarchyService;
        @Mock private ShiftTimingRepository shiftTimingRepository;

        @InjectMocks private EmployeeService service;

        private Employee employee;

        private void apply(Map<String, Object> updates) {
            employee = new Employee();
            employee.setManager(new Employee());
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(employeeRepository.findByIdAndOrganizationId(any(), any()))
                        .thenReturn(Optional.of(employee));
                when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
                service.partialUpdateAnEmployee(updates, ENTITY_ID);
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.employee.dto.EmployeeUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("managerId detaches the manager on a null rather than answering 500")
        void managerIdClearsOnNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("managerId", null);

            apply(update);

            assertThat(employee.getManager()).isNull();
        }
    }

    @Nested
    @DisplayName("leave policy")
    class LeavePolicySurface {

        @Mock private LeavePolicyRepository policyRepository;
        @Mock private OrganizationRepository organizationRepository;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private UserContextService userContextService;
        @Mock private LeavePolicyMapper leavePolicyMapper;

        @InjectMocks private LeavePolicyService service;

        private void apply(Map<String, Object> updates) {
            LeavePolicy policy = new LeavePolicy();
            policy.setAnnualQuota(12.0);
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(policyRepository.findByIdAndOrganization_Id(any(), any()))
                        .thenReturn(Optional.of(policy));
                when(policyRepository.save(any(LeavePolicy.class))).thenAnswer(i -> i.getArgument(0));
                service.updatePolicy(ENTITY_ID, updates);
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.leave.dto.LeavePolicyUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("annualQuota refuses a null rather than answering 500, since the column is NOT NULL")
        void annualQuotaRefusesNull() {
            Map<String, Object> update = new HashMap<>();
            update.put("annualQuota", null);

            assertThatThrownBy(() -> apply(update))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("annualQuota cannot be cleared");
        }
    }

    @Nested
    @DisplayName("leave request")
    class LeaveRequestSurface {

        @Mock private LeaveRequestRepository requestRepository;
        @Mock private LeaveRequestSequenceRepository sequenceRepository;
        @Mock private LeavePolicyRepository policyRepository;
        @Mock private LeaveBalanceRepository balanceRepository;
        @Mock private EmployeeRepository employeeRepository;
        @Mock private LeaveApprovalService approvalService;
        @Mock private LeaveRequestValidator leaveRequestValidator;
        @Mock private LeaveRequestMapper leaveRequestMapper;
        @Mock private OrganizationSecurityService orgSecurity;

        @InjectMocks private LeaveRequestService service;

        private void apply(Map<String, Object> updates) {
            Employee employee = new Employee();
            employee.setId(5L);
            LeaveRequest request = new LeaveRequest();
            request.setEmployee(employee);
            request.setStatus(LeaveStatus.DRAFT);
            request.setStartDate(LocalDate.of(2026, 9, 14));
            request.setEndDate(LocalDate.of(2026, 9, 18));
            TenantContext.setCurrentOrgId(ORG_ID);
            try {
                when(requestRepository.findByIdAndOrganization_Id(any(), any()))
                        .thenReturn(Optional.of(request));
                when(requestRepository.save(any(LeaveRequest.class))).thenAnswer(i -> i.getArgument(0));
                when(orgSecurity.isSelfInCurrentTenant(any())).thenReturn(true);
                service.updateRequest(ENTITY_ID, updates);
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("every key does to a null what PartialUpdateSurfaces says it does")
        void nullBehaviourMatchesTheTable() {
            sweep(org.tornotron.echno_backend.leave.dto.LeaveRequestUpdateFieldsDto.class, this::apply);
        }

        @Test
        @DisplayName("both dates refuse a null rather than answering 500, since both columns are NOT NULL")
        void datesRefuseNull() {
            for (String key : List.of("startDate", "endDate")) {
                Map<String, Object> update = new HashMap<>();
                update.put(key, null);

                assertThatThrownBy(() -> apply(update))
                        .isInstanceOf(InvalidRequestException.class)
                        .hasMessageContaining(key + " cannot be cleared");
            }
        }

        @Test
        @DisplayName("an unparseable date is a 400 rather than the 500 a DateTimeParseException gives")
        void unparseableDateIsRefused() {
            assertThatThrownBy(() -> apply(new HashMap<>(
                    Collections.singletonMap("startDate", "14-09-2026"))))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("must be an ISO date");
        }
    }
}
