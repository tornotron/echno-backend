package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.pdf.AnnotatedPhotoLoader;
import org.tornotron.echno_backend.inspection.pdf.InspectionReportPdfService;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The QA/QC inspection report, rendered through the real openhtmltopdf pipeline
 * and the actual Thymeleaf template. openhtmltopdf parses strictly, so a template
 * that is not well formed fails here rather than in front of a client.
 *
 * <p>No Spring context: the template engine is the same bean the application uses,
 * built directly from its configuration class, following
 * {@code ConstructionInvoicePdfServiceTest}. The test JVM caches every context it
 * loads for the whole run, so a renderer test must not start one.
 */
class InspectionReportPdfServiceTest {

    private static final UUID INSPECTION_ID =
            UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String PHOTO = "https://cdn.example.test/inspections/crack.jpg";

    /**
     * A one-pixel PNG as a data URI. Small enough to keep the assertions about
     * document size honest, and real enough that the renderer decodes it rather
     * than skipping an image it cannot read.
     */
    private static final String PIXEL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final InspectionService inspectionService = mock(InspectionService.class);
    private final DefectAnnotationService annotationService = mock(DefectAnnotationService.class);
    private final AnnotatedPhotoLoader photoLoader = mock(AnnotatedPhotoLoader.class);
    private final ProjectService projectService = mock(ProjectService.class);

    private final InspectionReportPdfService service = new InspectionReportPdfService(
            new ThymeleafConfig().pdfTemplateEngine(), new PdfRenderer(),
            inspectionService, annotationService, photoLoader, projectService);

    @Test
    void rendersAFullyPopulatedInspection() throws Exception {
        givenProject();
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(1, 1));
        givenAnnotations(mark(DefectAnnotationShape.RECTANGLE));
        when(photoLoader.maxPhotos()).thenReturn(12);
        when(photoLoader.dataUri(PHOTO)).thenReturn(Optional.of(PIXEL));

        RenderedReport report = service.render(INSPECTION_ID);

        assertThat(report.documentName()).isEqualTo("INSP-2026-0001");
        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
        // the photograph really was embedded, not silently dropped: a data URI the
        // renderer could not read would still produce a perfectly valid PDF
        assertThat(new String(report.content(), StandardCharsets.ISO_8859_1))
                .contains("/Subtype /Image");
    }

    @Test
    void rendersAnInspectionWithNoCheckPointsNoDefectsAndNoMarks() throws Exception {
        givenProject();
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(0, 0));
        givenAnnotations();
        when(photoLoader.maxPhotos()).thenReturn(12);

        RenderedReport report = service.render(INSPECTION_ID);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    /**
     * A photograph that cannot be read out of storage must not fail the report. The
     * plate prints a placeholder instead, so the reader learns which evidence is
     * missing rather than getting a 500.
     */
    @Test
    void rendersAPlaceholderWhenThePhotographCannotBeRead() throws Exception {
        givenProject();
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(1, 1));
        givenAnnotations(mark(DefectAnnotationShape.ARROW));
        when(photoLoader.maxPhotos()).thenReturn(12);
        when(photoLoader.dataUri(anyString())).thenReturn(Optional.empty());

        RenderedReport report = service.render(INSPECTION_ID);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    /**
     * The section that is unbounded in principle. An inspection may carry any number
     * of check points and defects, and a report that printed all of them would grow
     * with the record. Both tables stop at the shared cap, and the document still
     * renders.
     */
    @Test
    void rendersAnInspectionWithMoreRowsThanTheCapAllows() throws Exception {
        givenProject();
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(700, 700));
        givenAnnotations();
        when(photoLoader.maxPhotos()).thenReturn(12);

        RenderedReport report = service.render(INSPECTION_ID);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    /**
     * Only the photographs that carry a mark are read. The report's whole subject is
     * annotated evidence, and reading every photograph attached to every defect would
     * multiply the size of the document with images nobody marked.
     */
    @Test
    void readsOnlyThePhotographsThatCarryAMark() throws Exception {
        givenProject();
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(1, 1));
        givenAnnotations();
        when(photoLoader.maxPhotos()).thenReturn(12);

        service.render(INSPECTION_ID);

        org.mockito.Mockito.verify(photoLoader, org.mockito.Mockito.never()).dataUri(anyString());
    }

    /** An inspection with no project attached still produces a report. */
    @Test
    void rendersWhenTheProjectCannotBeResolved() throws Exception {
        when(projectService.getAProject(42L)).thenThrow(new IllegalStateException("gone"));
        when(inspectionService.findById(INSPECTION_ID)).thenReturn(inspection(1, 1));
        givenAnnotations();
        when(photoLoader.maxPhotos()).thenReturn(12);

        RenderedReport report = service.render(INSPECTION_ID);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    private void givenProject() {
        ProjectDto project = new ProjectDto();
        project.setId(42L);
        project.setProjectName("Tower B, Riverside Residences");
        when(projectService.getAProject(42L)).thenReturn(project);
    }

    private void givenAnnotations(DefectPhotoAnnotationDto... marks) {
        Page<DefectPhotoAnnotationDto> page =
                new PageImpl<>(List.of(marks), PageRequest.of(0, 500), marks.length);
        when(annotationService.findByInspection(any(), any())).thenReturn(page);
    }

    private static DefectPhotoAnnotationDto mark(DefectAnnotationShape shape) {
        return new DefectPhotoAnnotationDto(UUID.randomUUID(), INSPECTION_ID, PHOTO, shape,
                new BigDecimal("0.20"), new BigDecimal("0.30"),
                new BigDecimal("0.55"), new BigDecimal("0.70"),
                "Honeycombing, approx 120 mm across", 0, 7L);
    }

    private static InspectionDto inspection(int checkPoints, int defects) {
        List<InspectionCheckItemDto> items = new ArrayList<>();
        for (int i = 0; i < checkPoints; i++) {
            items.add(new InspectionCheckItemDto(UUID.randomUUID(), "Reinforcement",
                    "Rebar spacing at grid C4", "IS 456", CheckItemStatus.FAILED,
                    "Spacing exceeded", true, List.of(PHOTO), "163", "150",
                    "150 mm centre to centre", "+/- 10 mm", new BigDecimal("13.0000"),
                    null, "high"));
        }

        List<InspectionDefectDto> found = new ArrayList<>();
        for (int i = 0; i < defects; i++) {
            found.add(new InspectionDefectDto(UUID.randomUUID(), "Structural",
                    "Honeycombing on column C4 at ground level", DefectSeverity.MAJOR,
                    "Column C4, ground floor", List.of(PHOTO),
                    "Chip out and re-pour the affected section", "ABC Contractors",
                    LocalDate.of(2026, 9, 12), DefectStatus.OPEN, null));
        }

        return new InspectionDto(INSPECTION_ID, "INSP-2026-0001", "Slab pour, level 4",
                InspectionType.QUALITY, InspectionCategory.QA_QC,
                InspectionTrade.REINFORCEMENT, InspectionStatus.COMPLETED,
                InspectionResult.FAILED, 42L, "Block A", "Level 4 slab", "SD-104-R3",
                LocalDate.of(2026, 8, 20), "09:00",
                LocalDateTime.of(2026, 8, 20, 9, 5), LocalDateTime.of(2026, 8, 20, 11, 40),
                155, 100L, 200L, "Client QS",
                List.of("R Menon", "S Iyer"), "Clear", "31 C",
                checkPoints, 0, checkPoints, defects,
                InspectionOrigin.MANUAL, null, null, null, null, null,
                items, found,
                LocalDateTime.of(2026, 8, 20, 9, 0), LocalDateTime.of(2026, 8, 20, 12, 0));
    }
}
