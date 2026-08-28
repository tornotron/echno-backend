package org.tornotron.echno_backend.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Date handling in the task partial-update switch.
 *
 * <p>Two things are pinned. {@code startDate} is applied at all: the client has always sent it and
 * the entity has always had the column, but the switch had no case for it, so a user setting a
 * task's start date got a 200 and no change. And both dates reject an offset-bearing value rather
 * than truncating it, which is what {@code ISO_DATE_TIME} was doing here.
 *
 * <p>Plain Mockito, no Spring context, so this adds nothing to the cached-context heap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskPartialUpdateDateTest {

    @Mock private TaskRepository taskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private TaskMapper taskMapper;

    @InjectMocks private TaskService service;

    private Task updateWith(Map<String, Object> updates) {
        // The task needs a project only because saving rolls task progress up to it; the
        // roll-up is not what this class is about.
        Project project = new Project();
        project.setId(3L);
        Task task = new Task();
        task.setProject(project);

        TenantContext.setCurrentOrgId(1L);
        try {
            when(taskRepository.findByIdAndOrganization_Id(any(), any())).thenReturn(Optional.of(task));
            when(taskRepository.save(any(Task.class))).thenAnswer(i -> i.getArgument(0));
            when(taskRepository.findByProject_Id(any())).thenReturn(java.util.List.of());
            service.partialUpdateATask(updates, 7L, null, "TASK");
        } finally {
            TenantContext.clear();
        }
        return task;
    }

    @Test
    @DisplayName("applies startDate, which the switch used to ignore entirely")
    void appliesStartDate() {
        Task task = updateWith(Map.of("startDate", "2026-08-27T09:00:00"));

        assertThat(task.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0));
    }

    @Test
    @DisplayName("applies endDate")
    void appliesEndDate() {
        Task task = updateWith(Map.of("endDate", "2026-09-30T18:00:00"));

        assertThat(task.getEndDate()).isEqualTo(LocalDateTime.of(2026, 9, 30, 18, 0));
    }

    @Test
    @DisplayName("applies both dates together")
    void appliesBothDates() {
        Task task = updateWith(Map.of(
                "startDate", "2026-08-27T09:00:00",
                "endDate", "2026-09-30T18:00:00"));

        assertThat(task.getStartDate()).isEqualTo(LocalDateTime.of(2026, 8, 27, 9, 0));
        assertThat(task.getEndDate()).isEqualTo(LocalDateTime.of(2026, 9, 30, 18, 0));
    }

    @Test
    @DisplayName("rejects a UTC startDate rather than storing a shifted time")
    void rejectsUtcStartDate() {
        assertThatThrownBy(() -> updateWith(Map.of("startDate", "2026-08-27T03:30:00.000Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no timezone offset");
    }

    @Test
    @DisplayName("rejects a UTC endDate rather than storing a shifted time")
    void rejectsUtcEndDate() {
        assertThatThrownBy(() -> updateWith(Map.of("endDate", "2026-09-30T12:30:00.000Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no timezone offset");
    }
}
