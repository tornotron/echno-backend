package org.tornotron.echno_backend.issue;

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
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.task.TaskRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.tornotron.echno_backend.common.payload.PayloadValidator;

/**
 * Both issue controllers take the create payload as the JSON string part of a multipart request
 * and deserialize it by hand, so Spring never binds a bean and never validates one. The
 * constraints on {@link IssueCreationDto} were therefore decorative. These pin that the service
 * runs them itself, and that a rejected payload never reaches the repository.
 *
 * <p>A real Hibernate Validator with mocked collaborators: whether the constraints fire needs a
 * genuine validator, but no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class IssueCreationValidationTest {

    private static ValidatorFactory factory;
    private Validator validator;

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private EmployeeRepository employeeRepository;

    private IssueService service;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        service = new IssueService(issueRepository, taskRepository, attachmentService,
                issueMapper, employeeRepository, new PayloadValidator(validator));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private IssueCreationDto validDto() {
        IssueCreationDto dto = new IssueCreationDto();
        dto.setTitle("Honeycombing on the block A raft");
        dto.setDescription("Voids visible along the north edge of the pour after stripping.");
        dto.setType("quality");
        dto.setStatus("open");
        dto.setTaskId(11L);
        dto.setCreatedById(5L);
        return dto;
    }

    @Test
    void addIssue_rejectsABlankTitle_beforeTouchingTheRepository() {
        IssueCreationDto dto = validDto();
        dto.setTitle("  ");

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(issueRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addIssue_rejectsABlankDescription() {
        IssueCreationDto dto = validDto();
        dto.setDescription("   ");

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);

        verify(issueRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void addIssue_rejectsADescriptionUnderFiveCharacters() {
        // The message claimed a minimum of ten while the constraint said five. The constraint
        // never ran, so the two had never been compared against a real request.
        IssueCreationDto dto = validDto();
        dto.setDescription("wet");

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addIssue_rejectsAMissingType() {
        IssueCreationDto dto = validDto();
        dto.setType(null);

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addIssue_rejectsAMissingStatus() {
        IssueCreationDto dto = validDto();
        dto.setStatus(null);

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addIssue_rejectsATitleOverTheColumnWidth() {
        IssueCreationDto dto = validDto();
        dto.setTitle("x".repeat(256));

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addIssue_acceptsATitleOfTwoHundredAndFiftyFiveCharacters() {
        // The old cap was 50 while the column has always been VARCHAR(255), and the cap never
        // ran, so longer titles have been accepted for as long as the endpoint existed. Failing
        // past validation means it reached the task lookup, which is mocked empty here; what
        // matters is that it is not a constraint failure.
        IssueCreationDto dto = validDto();
        dto.setTitle("x".repeat(255));

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void addIssue_acceptsALongDescription() {
        // The column is TEXT and the 500 cap never ran, so descriptions past it have been
        // accepted all along; enforcing the old figure would start refusing them.
        IssueCreationDto dto = validDto();
        dto.setDescription("x".repeat(1500));

        assertThatThrownBy(() -> service.addIssue(dto, null))
                .isNotInstanceOf(ConstraintViolationException.class);
    }
}
