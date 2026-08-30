package org.tornotron.echno_backend.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Reassigning a task, and moving it to another category, through the partial-update endpoint.
 *
 * <p>Neither used to work. Both are real relations on {@code Task}, both are accepted on create,
 * and the edit page submits both from prefilled controls, but the update switch named neither and
 * there is no assignee or category sub-resource to reach them another way. The call answered 200,
 * so the failure read as a rendering glitch: {@code use-task-mutations.ts} leaves
 * {@code assigneeIds} out of its optimistic patch and then invalidates, and the user watched the
 * assignee list snap back to what it was. Recorded as the highest-value entry on echno-core#57.
 *
 * <p>Every test here fails without the two cases: the assignee ones by the set staying as it was,
 * the category one by the category not moving.
 *
 * <p>Plain Mockito, no Spring context, so this adds nothing to the cached-context heap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskPartialUpdateAssignmentTest {

    private static final long ORG_ID = 1L;

    @Mock private TaskRepository taskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private TaskMapper taskMapper;

    @InjectMocks private TaskService service;

    private Task task;

    private Task updateWith(Map<String, Object> updates) {
        // The task needs a project only because saving rolls task progress up to it; the roll-up
        // is not what this class is about.
        Project project = new Project();
        project.setId(3L);
        task = new Task();
        task.setProject(project);
        task.setAssignees(new HashSet<>(Set.of(employee(11L))));
        task.setCategory(category(4L));

        TenantContext.setCurrentOrgId(ORG_ID);
        try {
            when(taskRepository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(task));
            when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
            when(taskRepository.findByProject_Id(any())).thenReturn(List.of());
            service.partialUpdateATask(updates, 7L, null, "TASK");
        } finally {
            TenantContext.clear();
        }
        return task;
    }

    private static Employee employee(long id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }

    private static Category category(long id) {
        Category category = new Category();
        category.setId(id);
        return category;
    }

    @Test
    @DisplayName("replaces the assignees with the list the client sent")
    void appliesAssigneeIds() {
        when(employeeRepository.findAllByIdInAndOrganizationId(any(), eq(ORG_ID)))
                .thenReturn(List.of(employee(21L), employee(22L)));

        Task updated = updateWith(Map.of("assigneeIds", List.of(21, 22)));

        assertThat(updated.getAssignees()).extracting(Employee::getId)
                .containsExactlyInAnyOrder(21L, 22L);
    }

    @Test
    @DisplayName("an empty list unassigns the task rather than leaving it as it was")
    void clearsAssigneesOnAnEmptyList() {
        Task updated = updateWith(Map.of("assigneeIds", List.of()));

        assertThat(updated.getAssignees()).isEmpty();
    }

    @Test
    @DisplayName("refuses an employee id from outside the caller's organization")
    void refusesAnAssigneeFromAnotherOrganization() {
        // The query is scoped to the tenant, so an id belonging elsewhere simply does not come
        // back. Silently assigning nobody would repeat the bug this endpoint just stopped having.
        when(employeeRepository.findAllByIdInAndOrganizationId(any(), eq(ORG_ID)))
                .thenReturn(List.of(employee(21L)));

        assertThatThrownBy(() -> updateWith(Map.of("assigneeIds", List.of(21, 99))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("moves the task to the category the client sent")
    void appliesCategoryId() {
        when(categoryRepository.findByIdAndOrganization_Id(eq(9L), anyLong()))
                .thenReturn(Optional.of(category(9L)));

        Task updated = updateWith(Map.of("categoryId", 9));

        assertThat(updated.getCategory().getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("refuses a category from outside the caller's organization")
    void refusesACategoryFromAnotherOrganization() {
        when(categoryRepository.findByIdAndOrganization_Id(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateWith(Map.of("categoryId", 9)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("refuses to clear the category, which creation insists on")
    void refusesToClearTheCategory() {
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("categoryId", null);

        assertThatThrownBy(() -> updateWith(updates))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be cleared");
    }

    @Test
    @DisplayName("still accepts the keys it has no field for rather than refusing the request")
    void acceptsTheKeysItHasNoFieldFor() {
        // echno-core puts attachments: [] in the JSON part of every task update, and sends a
        // creatorId, a priority and a projectId this endpoint has never applied. Refusing an
        // unrecognised key would turn every task edit the deployed web app makes into a 400.
        java.util.Map<String, Object> updates = new java.util.LinkedHashMap<>();
        updates.put("attachments", List.of());
        updates.put("creatorId", 5);
        updates.put("priority", "high");
        updates.put("projectId", 3);
        updates.put("title", "Pour the block A raft");

        assertThatCode(() -> updateWith(updates)).doesNotThrowAnyException();
        assertThat(task.getTitle()).isEqualTo("Pour the block A raft");
    }
}
