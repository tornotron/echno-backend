package org.tornotron.echno_backend.pdfGeneration;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.category.CategoryService;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.organization.OrganizationService;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.task.TaskService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/generate-report")
public class ReportController {

    private final OrganizationService organizationService;
    private final TaskService taskService;
    private final ReportService reportService;
    private final SpringTemplateEngine pdfTemplateEngine;
    private final CategoryService categoryService;
    private final DateConversion dateConversion;
    private final ProjectService projectService;

    public ReportController(TaskService taskService,
                            ReportService reportService,
                            CategoryService categoryService,
                            DateConversion dateConversion,
                            @Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
                            ProjectService projectService,
                            OrganizationService organizationService) {
        this.organizationService = organizationService;
        this.projectService = projectService;
        this.dateConversion = dateConversion;
        this.reportService = reportService;
        this.categoryService = categoryService;
        this.taskService = taskService;
        this.pdfTemplateEngine = pdfTemplateEngine;
    }

    private Context populateContext() {
        Context ctx = new Context();
        ctx.setVariable("tasks", taskService.getAllTasks());
        ctx.setVariable("counts", reportService.statusCount());
        ctx.setVariable("delayedCounts", reportService.statusCount());
        ctx.setVariable("category", categoryService);
        ctx.setVariable("dateConverter", dateConversion);
        ctx.setVariable("project",projectService);
        ctx.setVariable("organization",organizationService);
        return ctx;
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdfReport() {
        Context ctx = populateContext();

        String html = pdfTemplateEngine.process("report/report", ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, "classpath:/templates/");
            builder.toStream(os);
            builder.run();

            byte[] pdf = os.toByteArray();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (IOException e) {
            // Log the exception
            return ResponseEntity.internalServerError().build();
        }
    }
}