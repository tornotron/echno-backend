package org.tornotron.echno_backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessGrants;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectCompletion;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.dto.TaskPatchDto;
import org.tornotron.echno_backend.task.enums.TaskStatus;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

/**
 * Tasks ask the access matrix about the task itself, not only about the area: a caller whose grants
 * reach one project sees, creates, changes and deletes only that project's tasks.
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceAccessTest {

    private static final Long ORG = 100L;
    private static final Long ON_MY_PROJECT = 1L;
    private static final Long ELSEWHERE = 2L;
    private static final Long MY_PROJECT = 10L;
    private static final Long OTHER_PROJECT = 20L;

    @Mock private TaskRepository taskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private CurrentEmployeeService currentEmployee;
    @Mock private TaskMapper taskMapper;
    @Mock private PayloadValidator payloadValidator;
    @Mock private ProjectCompletion projectCompletion;

    private final OneProjectAccess access = new OneProjectAccess();
    private TaskService tasks;
    private Organization organization;
    private Employee caller;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        organization = new Organization();
        organization.setId(ORG);
        caller = new Employee();
        caller.setId(7L);
        lenient().when(currentEmployee.currentEmployee()).thenReturn(Optional.of(caller));
        tasks = new TaskService(taskRepository, employeeRepository, projectRepository, categoryRepository,
                attachmentService, currentEmployee, taskMapper, payloadValidator, projectCompletion,
                new TaskAccess(access, currentEmployee));
        lenient().when(taskRepository.findByIdAndOrganization_Id(ON_MY_PROJECT, ORG))
                .thenReturn(Optional.of(task(ON_MY_PROJECT, MY_PROJECT)));
        lenient().when(taskRepository.findByIdAndOrganization_Id(ELSEWHERE, ORG))
                .thenReturn(Optional.of(task(ELSEWHERE, OTHER_PROJECT)));
        lenient().when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aTaskTheCallerMayNotSeeReadsAsMissing() {
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> tasks.getATask(ELSEWHERE));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> tasks.partialUpdateATask(Map.of("title", "Renamed"), ELSEWHERE, null, "TASK"));
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> tasks.deleteATask(ELSEWHERE));
        verify(taskRepository, never()).save(any());
        verify(taskRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    void aTaskTheCallerMaySeeButNotChangeIsRefusedAndKept() {
        access.mayChange = false;

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> tasks.partialUpdateATask(Map.of("title", "Renamed"), ON_MY_PROJECT, null, "TASK"));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> tasks.deleteATask(ON_MY_PROJECT));
        verify(taskRepository, never()).save(any());
        verify(taskRepository, never()).deleteByIdAndOrganization_Id(anyLong(), anyLong());
    }

    @Test
    void aTaskOnTheCallersProjectIsChanged() {
        tasks.partialUpdateATask(Map.of("title", "Renamed"), ON_MY_PROJECT, null, "TASK");

        verify(taskRepository).save(any(Task.class));
    }

    @Test
    void aTaskCannotBeCreatedOnAProjectTheCallerMayNotCreateTasksOn() {
        when(currentEmployee.requireCurrentEmployee(any())).thenReturn(caller);
        Project other = project(OTHER_PROJECT);
        when(projectRepository.findByIdAndOrganization_Id(OTHER_PROJECT, ORG)).thenReturn(Optional.of(other));
        when(categoryRepository.findByIdAndOrganization_Id(3L, ORG)).thenReturn(Optional.of(new Category()));
        TaskCreationDto task = new TaskCreationDto();
        task.setTitle("Pour the slab");
        task.setStartDate(LocalDateTime.of(2026, 11, 1, 9, 0));
        task.setStatus(TaskStatus.upcoming.name());
        task.setProjectId(OTHER_PROJECT);
        task.setCategoryId(3L);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> tasks.addTask(task, null));
        verify(taskRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchSkipsTasksTheCallerMayNotSeeAndChangesTheRest() {
        when(taskRepository.findAllById(any())).thenReturn(List.of(
                task(ON_MY_PROJECT, MY_PROJECT), task(ELSEWHERE, OTHER_PROJECT)));

        tasks.batchUpdateTasks(List.of(patch(ON_MY_PROJECT), patch(ELSEWHERE)));

        ArgumentCaptor<Iterable<Task>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(taskRepository).saveAll(saved.capture());
        List<Long> savedIds = new ArrayList<>();
        saved.getValue().forEach(task -> savedIds.add(task.getId()));
        assertThat(savedIds).containsExactly(ON_MY_PROJECT);
    }

    @Test
    void aBatchWithATaskTheCallerMaySeeButNotChangeChangesNothing() {
        access.mayChange = false;
        when(taskRepository.findAllById(any())).thenReturn(List.of(task(ON_MY_PROJECT, MY_PROJECT)));

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> tasks.batchUpdateTasks(List.of(patch(ON_MY_PROJECT))));
        verify(taskRepository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListsAreNarrowedInTheQuery() {
        when(taskRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(taskRepository.findAll(any(Specification.class))).thenReturn(List.of());

        tasks.getAllTasks(0, 10);
        tasks.getTasksPaginated(0, 10, MY_PROJECT, "slab");
        tasks.getTasksByProjectId(MY_PROJECT);

        verify(taskRepository, never()).findAll(any(Pageable.class));
        verify(taskRepository, never()).search(any(), any(), any());
        verify(taskRepository, never()).findAllByProject_IdAndOrganization_Id(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeEveryTaskGetsTheUnnarrowedLists() {
        access.everything = true;
        when(taskRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(taskRepository.search(any(), any(), any())).thenReturn(Page.empty());

        tasks.getAllTasks(0, 10);
        tasks.getTasksPaginated(0, 10, null, null);
        tasks.getTasksByProjectId(MY_PROJECT);

        verify(taskRepository).findAllByProject_IdAndOrganization_Id(MY_PROJECT, ORG);
        verify(taskRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(taskRepository, never()).findAll(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerWhoMaySeeNoTaskIsNotQueriedFor() {
        access.nothing = true;

        assertThat(tasks.getAllTasks(0, 10)).isEmpty();
        assertThat(tasks.getTasksPaginated(0, 10, null, "slab")).isEmpty();
        assertThat(tasks.getTasksByProjectId(MY_PROJECT)).isEmpty();
        verify(taskRepository, never()).findAll(any(Pageable.class));
        verify(taskRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(taskRepository, never()).search(any(), any(), any());
        verify(taskRepository, never()).findAllByProject_IdAndOrganization_Id(any(), any());
    }

    private static TaskPatchDto patch(Long id) {
        TaskPatchDto patch = new TaskPatchDto();
        patch.setId(id);
        patch.setUpdates(Map.of("title", "Renamed"));
        return patch;
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setOrganization(organization);
        return project;
    }

    private Task task(Long id, Long projectId) {
        Task task = new Task();
        task.setId(id);
        task.setTitle("Task " + id);
        task.setStatus(TaskStatus.upcoming);
        task.setOrganization(organization);
        task.setProject(project(projectId));
        return task;
    }

    /** Grants read, and create, edit and delete when it may change, on one project's tasks, and nothing else. */
    private static final class OneProjectAccess implements AccessService {

        boolean everything;
        boolean nothing;
        boolean mayChange = true;

        private Set<String> held() {
            return mayChange
                    ? Set.of(AccessPermission.READ, AccessPermission.CREATE, AccessPermission.EDIT,
                            AccessPermission.DELETE)
                    : Set.of(AccessPermission.READ);
        }

        @Override
        public boolean isActiveMember(AccessSubject subject) {
            return true;
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission) {
            return !nothing && held().contains(permission.action());
        }

        @Override
        public boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record) {
            return filter(subject, permission).admits(record);
        }

        @Override
        public AccessFilter filter(AccessSubject subject, AccessPermission permission) {
            if (nothing || !held().contains(permission.action())) {
                return AccessFilter.none(subject.organizationId());
            }
            if (everything) {
                return AccessFilter.all(subject.organizationId());
            }
            return new AccessFilter(subject.organizationId(), false, Set.of(MY_PROJECT), null, null);
        }

        @Override
        public AccessGrants grantsFor(AccessSubject subject) {
            return AccessGrants.none();
        }
    }
}
