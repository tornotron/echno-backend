package org.tornotron.echno_backend.pdfGeneration;

import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.common.exception.ReportRenderingException;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.organization.OrganizationService;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.task.TaskService;

import java.io.IOException;
import org.springframework.data.domain.Page;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.task.dto.TaskDto;

@RestController
@RequestMapping("/api/v1/generate-report")
@Tag(
        name = "PDF Reports",
        description = "Generates a consolidated PDF report covering tasks, task status counts, projects "
                + "and indents. The report is rendered from a Thymeleaf template and returned as a "
                + "downloadable file."
)
public class ReportController {

    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);

    private static final String TEMPLATE = "report/report";

    private final OrganizationService organizationService;
    private final TaskService taskService;
    private final PdfReportService pdfReportService;
    private final SpringTemplateEngine pdfTemplateEngine;
    private final PdfRenderer pdfRenderer;
    private final DateConversion dateConversion;
    private final ProjectService projectService;
    private final IndentService indentService;

    public ReportController(TaskService taskService,
                            PdfReportService pdfReportService,
                            DateConversion dateConversion,
                            @Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
                            PdfRenderer pdfRenderer,
                            ProjectService projectService,
                            OrganizationService organizationService,
                            IndentService indentService) {
        this.organizationService = organizationService;
        this.projectService = projectService;
        this.dateConversion = dateConversion;
        this.pdfReportService = pdfReportService;
        this.taskService = taskService;
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.pdfRenderer = pdfRenderer;
        this.indentService = indentService;
    }

    /**
     * Assembles the template context.
     *
     * <p>Tasks and indents are read one capped page at a time rather than whole. Both tables grow
     * with everything a tenant ever records, so an uncapped read would load, map and render every
     * row a client has accumulated. The true totals go into the context alongside the capped
     * lists, so the report states its real counts and says when it is showing only part of them.
     */
    private Context populateContext() {
        Page<TaskDto> tasks = taskService.getAllTasks(0, UnpagedResultCap.MAX_ROWS);
        Page<IndentDto> indents = indentService.getAllIndents(0, UnpagedResultCap.MAX_ROWS);

        Context ctx = new Context();
        ctx.setVariable("tasks", tasks.getContent());
        ctx.setVariable("taskTotal", tasks.getTotalElements());
        ctx.setVariable("counts", pdfReportService.statusCount());
        ctx.setVariable("delayedCounts", pdfReportService.statusCount());
        ctx.setVariable("dateConverter", dateConversion);
        ctx.setVariable("project",projectService);
        ctx.setVariable("organization",organizationService);
        ctx.setVariable("indents", indents.getContent());
        ctx.setVariable("indentTotal", indents.getTotalElements());
        ctx.setVariable("truncated",
                tasks.getTotalElements() > tasks.getNumberOfElements()
                        || indents.getTotalElements() > indents.getNumberOfElements());
        ctx.setVariable("rowCap", UnpagedResultCap.MAX_ROWS);
        return ctx;
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin','project-manager')")
    @GetMapping("/pdf")
    @Operation(
            summary = "Generate a PDF report",
            description = "Renders and returns a PDF covering the current tenant's tasks, their status "
                    + "breakdown, projects and indents. The task and indent sections show at most "
                    + "500 rows each; the report states the true totals and flags itself when it "
                    + "is showing only part of them."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF report generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "500", description = "PDF rendering failed")
    })
    public ResponseEntity<byte[]> pdfReport() {
        byte[] pdf = render(populateContext());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Renders the report and says what went wrong when it cannot.
     *
     * <p>Each half used to lose the one fact worth keeping. An expression the template
     * cannot evaluate escaped unhandled and arrived as "An unexpected error occurred",
     * logged as "Unknown error occurred", with nothing naming the document. The write
     * half was worse: caught into a bare 500 with an empty body and a
     * {@code // Log the exception} comment standing in for the logging. That is how a
     * report which has never rendered for any tenant holding an indent went unnoticed.
     * Both now name the template, in the log line and in the response.
     *
     * <p>The bytes come from the shared {@link PdfRenderer} rather than a
     * {@code PdfRendererBuilder} assembled here, which is what every other report in
     * the codebase already does. The private copy this replaces had drifted: it never
     * called {@code useFastMode()}, so this one endpoint ran on a different renderer
     * path from the rest for no reason anyone chose.
     */
    private byte[] render(Context ctx) {
        String html;
        try {
            html = pdfTemplateEngine.process(TEMPLATE, ctx);
        } catch (RuntimeException e) {
            logger.error("Template {} failed to evaluate", TEMPLATE, e);
            throw new ReportRenderingException(
                    "The " + TEMPLATE + " template could not be evaluated: " + e.getMessage(), e);
        }

        try {
            return pdfRenderer.render(html);
        } catch (IOException | RuntimeException e) {
            logger.error("Template {} evaluated but could not be written as a PDF", TEMPLATE, e);
            throw new ReportRenderingException(
                    "The " + TEMPLATE + " document could not be written as a PDF: " + e.getMessage(), e);
        }
    }
}
