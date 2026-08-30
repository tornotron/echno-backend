package org.tornotron.echno_backend.pdfGeneration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.thymeleaf.context.IContext;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.category.dto.CategoryDto;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.common.exception.ReportRenderingException;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.organization.OrganizationService;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.task.TaskService;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The consolidated PDF report, rendered through the real Thymeleaf template and the
 * real openhtmltopdf pipeline.
 *
 * <p>The indent rows are the point of this file. {@code report/report} asked each
 * indent for {@code createdBy.name}, a property {@code EmployeeDto} does not have,
 * so the render aborted on the first indent it reached and the endpoint answered 500
 * for every tenant holding one. It went unnoticed because a tenant with no indents
 * never enters that loop, and on staging only one organization had any. A test that
 * renders the report with an empty indent list therefore proves nothing: every case
 * here carries indents.
 *
 * <p>The populated indent mirrors the staging rows that failed: an indent numbered in
 * the {@code IND-2026-nnnnnn} series, raised against a project by an employee. The
 * second one carries no creator at all, which the schema allows
 * ({@code indent.created_by_id} is nullable) and which would abort the same document
 * the same way if the expression were merely renamed rather than guarded.
 *
 * <p>No Spring context, following the sibling PDF tests: the template engine comes
 * from its configuration class and every service is a mock, so the suite JVM gains no
 * cached context from this file.
 */
class ReportPdfRenderTest {

    private final TaskService taskService = mock(TaskService.class);
    private final PdfReportService pdfReportService = mock(PdfReportService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final IndentService indentService = mock(IndentService.class);

    private final ReportController controller = new ReportController(
            taskService, pdfReportService, new DateConversion(),
            new ThymeleafConfig().pdfTemplateEngine(), new PdfRenderer(),
            projectService, organizationService, indentService);

    @Test
    void reportRenders_whenTheTenantHasIndents() throws Exception {
        given(List.of(task(1L, "Slab shuttering, block C", category("Formwork"))),
                List.of(indent(1L, "IND-2026-000001", IndentStatus.ON_SITE, employee("Echno Admin"))));

        ResponseEntity<byte[]> response = controller.pdfReport();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), 0, 5)).isEqualTo("%PDF-");

        String text = textOf(response.getBody());
        assertThat(text).contains("IND-2026-000001");
        assertThat(text).contains("Echno Admin");
        assertThat(text).contains("Formwork");
    }

    @Test
    void reportRenders_whenAnIndentHasNoCreatorAndATaskHasNoCategory() throws Exception {
        given(List.of(task(1L, "Rebar tying, block C", null)),
                List.of(indent(2L, "IND-2026-000002", IndentStatus.ORDERED, null)));

        ResponseEntity<byte[]> response = controller.pdfReport();

        String text = textOf(response.getBody());
        assertThat(text).contains("IND-2026-000002");
        assertThat(text).contains("Rebar tying, block C");
    }

    /**
     * The diagnosability half. A template that cannot evaluate used to escape as an
     * unhandled exception and reach the caller as "An unexpected error occurred",
     * which named neither the document nor the expression. The failure now says which
     * template it was and carries the engine's own reason.
     */
    @Test
    void aTemplateFailure_namesTheTemplateRatherThanArrivingAsAnUnknownError() {
        SpringTemplateEngine broken = mock(SpringTemplateEngine.class);
        when(broken.process(eq("report/report"), any(IContext.class)))
                .thenThrow(new TemplateProcessingException(
                        "Exception evaluating SpringEL expression: \"indent.createdBy.name\""));
        ReportController withBrokenTemplate = new ReportController(
                taskService, pdfReportService, new DateConversion(),
                broken, new PdfRenderer(), projectService, organizationService, indentService);
        given(List.of(), List.of(indent(1L, "IND-2026-000001", IndentStatus.ON_SITE, employee("Echno Admin"))));

        assertThatThrownBy(withBrokenTemplate::pdfReport)
                .isInstanceOf(ReportRenderingException.class)
                .hasMessageContaining("report/report")
                .hasMessageContaining("indent.createdBy.name");
    }

    private void given(List<TaskDto> tasks, List<IndentDto> indents) {
        Page<TaskDto> taskPage = page(tasks);
        Page<IndentDto> indentPage = page(indents);
        when(taskService.getAllTasks(0, UnpagedResultCap.MAX_ROWS)).thenReturn(taskPage);
        when(indentService.getAllIndents(0, UnpagedResultCap.MAX_ROWS)).thenReturn(indentPage);
        when(pdfReportService.statusCount())
                .thenReturn(Map.of("COMPLETED", 2L, "IN_PROGRESS", 5L, "NOT_STARTED", 1L));
    }

    private <T> Page<T> page(List<T> content) {
        return new PageImpl<>(content, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), content.size());
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private EmployeeDto employee(String name) {
        EmployeeDto employee = new EmployeeDto();
        employee.setId(4L);
        employee.setEmployeeName(name);
        return employee;
    }

    private CategoryDto category(String name) {
        CategoryDto category = new CategoryDto();
        category.setId(3L);
        category.setName(name);
        return category;
    }

    private TaskDto task(Long id, String title, CategoryDto category) {
        TaskDto task = new TaskDto();
        task.setId(id);
        task.setTitle(title);
        task.setProgress(45.0);
        task.setCategory(category);
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 20, 9, 30));
        return task;
    }

    private IndentDto indent(Long id, String number, IndentStatus status, EmployeeDto createdBy) {
        IndentDto indent = new IndentDto();
        indent.setId(id);
        indent.setIndentNumber(number);
        indent.setStatus(status);
        indent.setCreatedBy(createdBy);
        indent.setProjectId(5L);
        indent.setCreatedAt(LocalDateTime.of(2026, 8, 18, 11, 0));
        return indent;
    }
}
