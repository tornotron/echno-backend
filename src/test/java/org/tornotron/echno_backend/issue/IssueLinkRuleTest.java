package org.tornotron.echno_backend.issue;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.category.Category;
import org.tornotron.echno_backend.category.CategoryRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.issue.dto.IssueCreationDto;
import org.tornotron.echno_backend.issue.mapper.IssueMapper;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.task.Task;
import org.tornotron.echno_backend.task.TaskRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An issue can be raised without a task, but it has to be connected to a project or a domain (a
 * work category), ClickUp 14zdkkvzx60. A task supplies its own project and, by default, its
 * category.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueLinkRuleTest {

    private static final Long ORG_ID = 1L;
    private static ValidatorFactory factory;

    @Mock private IssueRepository issueRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private IssueMapper issueMapper;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private ProjectRepository projectRepository;
    @Mock private CategoryRepository categoryRepository;

    private IssueService service;
    private Organization organization;
    private Project tower;
    private Project annex;
    private Category concrete;
    private Category masonry;
    private Task pour;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        service = new IssueService(issueRepository, taskRepository, attachmentService, issueMapper,
                employeeRepository, currentEmployeeService, new PayloadValidator(factory.getValidator()),
                projectRepository, categoryRepository);
        TenantContext.setCurrentOrgId(ORG_ID);

        organization = new Organization();
        organization.setId(ORG_ID);
        tower = project(7L);
        annex = project(8L);
        concrete = category(3L);
        masonry = category(4L);
        pour = new Task();
        pour.setId(11L);
        pour.setOrganization(organization);
        pour.setProject(tower);
        pour.setCategory(concrete);

        when(taskRepository.findByIdAndOrganization_Id(eq(11L), eq(ORG_ID))).thenReturn(Optional.of(pour));
        when(projectRepository.findByIdAndOrganization_Id(eq(7L), eq(ORG_ID))).thenReturn(Optional.of(tower));
        when(projectRepository.findByIdAndOrganization_Id(eq(8L), eq(ORG_ID))).thenReturn(Optional.of(annex));
        when(categoryRepository.findByIdAndOrganization_Id(eq(3L), eq(ORG_ID))).thenReturn(Optional.of(concrete));
        when(categoryRepository.findByIdAndOrganization_Id(eq(4L), eq(ORG_ID))).thenReturn(Optional.of(masonry));
        Employee creator = new Employee();
        creator.setOrganization(organization);
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(creator);
        when(issueRepository.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setOrganization(organization);
        return project;
    }

    private Category category(Long id) {
        Category category = new Category();
        category.setId(id);
        category.setOrganization(organization);
        return category;
    }

    private static IssueCreationDto dto(Long taskId, Long projectId, Long categoryId) {
        IssueCreationDto dto = new IssueCreationDto();
        dto.setTitle("Cracks along the parapet");
        dto.setDescription("Hairline cracks every metre along the east parapet.");
        dto.setType("quality");
        dto.setTaskId(taskId);
        dto.setProjectId(projectId);
        dto.setCategoryId(categoryId);
        return dto;
    }

    private Issue saved() {
        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void anIssueOnATaskTakesTheTasksProjectAndCategory() {
        service.addIssue(dto(11L, null, null), null);

        Issue issue = saved();
        assertThat(issue.getTask()).isEqualTo(pour);
        assertThat(issue.getProject()).isEqualTo(tower);
        assertThat(issue.getCategory()).isEqualTo(concrete);
        assertThat(issue.getOrganization()).isEqualTo(organization);
    }

    @Test
    void anIssueWithoutATaskCanBelongToAProjectOrADomain() {
        service.addIssue(dto(null, 7L, null), null);
        assertThat(saved().getProject()).isEqualTo(tower);
    }

    @Test
    void anIssueWithOnlyADomainIsAccepted() {
        service.addIssue(dto(null, null, 4L), null);

        Issue issue = saved();
        assertThat(issue.getTask()).isNull();
        assertThat(issue.getProject()).isNull();
        assertThat(issue.getCategory()).isEqualTo(masonry);
    }

    @Test
    void anIssueWithNeitherTaskProjectNorDomainIsRefused() {
        assertThatThrownBy(() -> service.addIssue(dto(null, null, null), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("project or a domain");
        verify(issueRepository, never()).save(any());
    }

    @Test
    void aProjectThatIsNotTheTasksIsRefused() {
        assertThatThrownBy(() -> service.addIssue(dto(11L, 8L, null), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("belongs to project 7");
    }

    @Test
    void unlinkingTheTaskKeepsTheProjectAndClearingEverythingIsRefused() {
        Issue issue = new Issue();
        issue.setId(5L);
        issue.setTask(pour);
        issue.setProject(tower);
        when(issueRepository.findByIdAndOrganization_Id(eq(5L), eq(ORG_ID))).thenReturn(Optional.of(issue));

        Map<String, Object> unlinkTask = new HashMap<>();
        unlinkTask.put("taskId", null);
        service.partialUpdateAnIssue(unlinkTask, 5L, null, "ISSUE");
        assertThat(issue.getTask()).isNull();
        assertThat(issue.getProject()).isEqualTo(tower);

        Map<String, Object> clearProject = new HashMap<>();
        clearProject.put("projectId", null);
        assertThatThrownBy(() -> service.partialUpdateAnIssue(clearProject, 5L, null, "ISSUE"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aGrandfatheredIssueWithNoLinksCanStillBeEdited() {
        Issue issue = new Issue();
        issue.setId(6L);
        when(issueRepository.findByIdAndOrganization_Id(eq(6L), eq(ORG_ID))).thenReturn(Optional.of(issue));

        service.partialUpdateAnIssue(Map.of("title", "Renamed"), 6L, null, "ISSUE");

        assertThat(issue.getTitle()).isEqualTo("Renamed");
    }
}
