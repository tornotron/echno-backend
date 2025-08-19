package org.tornotron.echno_backend.pdfGeneration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/generate-report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ResponseEntity<byte[]> createPdfReport() {
        byte[] pdfReport = reportService.generatePdfReport();
        return  ResponseEntity.status(HttpStatus.OK).body(pdfReport);
    }
}
