package org.tornotron.echno_backend.pdfGeneration;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.tornotron.echno_backend.category.CategoryService;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.task.TaskService;

import java.io.ByteArrayOutputStream;
import java.net.URL;

@RestController
@RequestMapping("/api/v1/generate-report")
public class ReportController {

    private final TaskService taskService;
    private final ReportService reportService;
    private final SpringTemplateEngine pdfTemplateEngine;
    private final CategoryService categoryService;
    private final DateConversion dateConversion;

    public ReportController(TaskService taskService,ReportService reportService,CategoryService categoryService, DateConversion dateConversion) {
        this.dateConversion = dateConversion;
        this.reportService = reportService;
        this.categoryService = categoryService;
        this.taskService = taskService;
        this.pdfTemplateEngine = pdfTemplateEngine();
    }

    private SpringTemplateEngine pdfTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        engine.setTemplateResolver(resolver);;
        return engine;
    }

    @GetMapping("/report")
    public String htmlReport(Model model) {
        model.addAttribute("tasks", taskService.getAllTasks());
        model.addAttribute("counts", reportService.statusCount());
        model.addAttribute("category", categoryService);
        model.addAttribute("dateConverter", dateConversion);
        return "report/report";
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdfReport() throws Exception{
        Context ctx = new Context();
        ctx.setVariable("tasks", taskService.getAllTasks());
        ctx.setVariable("counts", reportService.statusCount());
        ctx.setVariable("delayedCounts", reportService.statusCount());
        ctx.setVariable("category", categoryService);
        ctx.setVariable("dateConverter", dateConversion);

        String html = pdfTemplateEngine.process("report/report", ctx);

        URL resource = getClass().getClassLoader().getResource("templates/");
        String baseUrl = resource.toExternalForm();

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.withHtmlContent(html, baseUrl);
        builder.toStream(os);

        builder.run();

        byte[] pdf = os.toByteArray();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}