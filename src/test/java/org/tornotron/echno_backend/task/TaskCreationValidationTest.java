package org.tornotron.echno_backend.task;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.dto.TaskCreationDto;
import org.tornotron.echno_backend.task.mapper.TaskMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Both task controllers take the create payload as the JSON string part of a multipart request
 * and deserialize it by hand, so Spring never binds a bean and never validates one. The
 * constraints on {@link TaskCreationDto} were therefore decorative, while the endpoints
 * documented a 400 for a field that failed validation. These pin that the service runs them
 * itself, and that a rejected payload never reaches the repository.
 *
 * <p>A real Hibernate Validator with mocked collaborators: whether the constraints fire needs a
 * genuine validator, but no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class TaskCreationValidationTest {

    private static ValidatorFactory factory;
    private Validator validator;

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private TaskMapper taskMapper;

    private TaskService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        service = new TaskService(taskRepository, employeeRepository, projectRepository,
                categoryRepository, attachmentService, taskMapper, validator);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private TaskCreationDto validDto() {
        TaskCreationDto dto = new TaskCreationDto();
        dto.setTitle("Pour foundation slab, block A");
        dto.setStatus("upcoming");
        dto.setCreatorId(5L);
        dto.setProjectId(42L);
        dto.setCategoryId(3L);
        dto.setProgress(0.0);
        dto.setAssigneeIds(List.of(7L));
        dto.setTags(List.of("concrete"));
        return dto;
    }

    @Test
    void addTask_rejectsABlankTitle_beforeTouchingTheRepository() {
        TaskCreationDto dto = validDto();
        dto.setTitle("  ");

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(taskRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addTask_rejectsATitleUnderThreeCharacters() {
        TaskCreationDto dto = validDto();
        dto.setTitle("ab");

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_rejectsATitleOverTheColumnWidth() {
        TaskCreationDto dto = validDto();
        dto.setTitle("x".repeat(256));

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_rejectsAMissingCreatorId() {
        TaskCreationDto dto = validDto();
        dto.setCreatorId(null);

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(taskRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addTask_rejectsAMissingProjectId() {
        TaskCreationDto dto = validDto();
        dto.setProjectId(null);

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_rejectsAMissingCategoryId() {
        TaskCreationDto dto = validDto();
        dto.setCategoryId(null);

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_rejectsAMissingStatus() {
        // Without this the status went straight into TaskStatus.valueOf and raised a
        // NullPointerException, which the handler reports as a 500.
        TaskCreationDto dto = validDto();
        dto.setStatus(null);

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_acceptsATaskWithNoAssigneesAndNoTags() {
        // Both were @NotNull, which contradicted the service's own handling: it has always
        // skipped an absent or empty list. Failing past validation means it reached the tenant
        // lookup, which is mocked empty here; what matters is that it is not a constraint failure.
        TaskCreationDto dto = validDto();
        dto.setAssigneeIds(null);
        dto.setTags(null);

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addTask_acceptsATitleOfTwoHundredAndFiftyFiveCharacters() {
        // The old cap was 50 while the column has always been VARCHAR(255), and the cap never
        // ran, so titles longer than 50 have been accepted for as long as the endpoint existed.
        TaskCreationDto dto = validDto();
        dto.setTitle("x".repeat(255));

        assertThatThrownBy(() -> service.addTask(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }
}
