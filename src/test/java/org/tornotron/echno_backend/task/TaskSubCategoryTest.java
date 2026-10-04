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
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.util.HashMap;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;

/**
 * The task's sub-category in the partial-update switch: free text, trimmed, with null or blank
 * clearing it and anything longer than the column refused.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskSubCategoryTest {

    @Mock private TaskRepository taskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private TaskMapper taskMapper;

    @InjectMocks private TaskService service;

    private Task updateWith(String startingValue, Map<String, Object> updates) {
        Project project = new Project();
        project.setId(3L);
        Task task = new Task();
        task.setProject(project);
        task.setSubCategory(startingValue);

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
    @DisplayName("stores a typed-in sub-category trimmed")
    void storesFreeTextTrimmed() {
        assertThat(updateWith(null, Map.of("subCategory", "  Rock breaking by chiselling ")).getSubCategory())
                .isEqualTo("Rock breaking by chiselling");
    }

    @Test
    @DisplayName("a null or blank value clears it")
    void nullOrBlankClears() {
        Map<String, Object> clear = new HashMap<>();
        clear.put("subCategory", null);
        assertThat(updateWith("Excavation", clear).getSubCategory()).isNull();
        assertThat(updateWith("Excavation", Map.of("subCategory", "   ")).getSubCategory()).isNull();
    }

    @Test
    @DisplayName("refuses a value that is not text or is longer than the column")
    void refusesNonTextAndOverlong() {
        assertThatThrownBy(() -> updateWith(null, Map.of("subCategory", 5)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> updateWith(null, Map.of("subCategory", "x".repeat(256))))
                .isInstanceOf(InvalidRequestException.class);
    }
}
