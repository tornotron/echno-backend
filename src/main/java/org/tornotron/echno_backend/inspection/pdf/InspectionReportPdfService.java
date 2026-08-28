package org.tornotron.echno_backend.inspection.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.inspection.dtos.DefectPhotoAnnotationDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionCheckItemDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDefectDto;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.service.DefectAnnotationService;
import org.tornotron.echno_backend.inspection.service.InspectionService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.pdfGeneration.ReportText;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectDto;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The QA/QC inspection report (FR-REP-01 to FR-REP-03): one inspection printed in
 * full, with its check points and the criterion each was judged against, the
 * defects it raised, the summary counts, and the marked-up photographs of those
 * defects.
 *
 * <p>Built the way the construction invoice is: the data is turned into
 * display-ready strings here so the template only places them, and the document
 * goes out through the shared {@link PdfRenderer}. Nothing about PDF generation is
 * reimplemented.
 *
 * <h2>What bounds this</h2>
 *
 * <p>A report is a read, and this one reads an aggregate rather than a table, so
 * the ArchUnit ratchet on {@code findAll()} does not see it. The rows are still
 * unbounded in principle: an inspection may carry any number of check points and
 * defects. Each section is therefore capped at {@link UnpagedResultCap#MAX_ROWS},
 * the true count is printed whether it fits or not, and the document says so when
 * it is showing only part. This is the pattern the site progress report already
 * uses.
 *
 * <p>The photographs are capped separately and much lower, by
 * {@link AnnotatedPhotoLoader}, because embedding an image costs bytes rather than
 * rows and one photo can outweigh every table on the page.
 */
@Slf4j
@Service
public class InspectionReportPdfService {

    private static final String TEMPLATE = "inspection/qa-qc-report";
    private final SpringTemplateEngine pdfTemplateEngine;
    private final PdfRenderer pdfRenderer;
    private final InspectionService inspectionService;
    private final DefectAnnotationService annotationService;
    private final AnnotatedPhotoLoader photoLoader;
    private final ProjectService projectService;

    public InspectionReportPdfService(
            @Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
            PdfRenderer pdfRenderer,
            InspectionService inspectionService,
            DefectAnnotationService annotationService,
            AnnotatedPhotoLoader photoLoader,
            ProjectService projectService) {
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.pdfRenderer = pdfRenderer;
        this.inspectionService = inspectionService;
        this.annotationService = annotationService;
        this.photoLoader = photoLoader;
        this.projectService = projectService;
    }

    /**
     * Renders the report for one inspection.
     *
     * <p>Returns the download name alongside the bytes so the controller does not
     * have to read the inspection a second time to find out what to call the file.
     *
     * @param inspectionId The inspection to print. Resolved through
     *                     {@code InspectionService}, so a report can only be
     *                     produced for an inspection in the caller's tenant.
     * @return The document name and the PDF bytes.
     * @throws IOException if the document cannot be rendered.
     */
    public RenderedReport render(UUID inspectionId) throws IOException {
        InspectionDto inspection = inspectionService.findById(inspectionId);
        Context ctx = populateContext(inspection);
        byte[] pdf = pdfRenderer.render(pdfTemplateEngine.process(TEMPLATE, ctx));

        String number = inspection.inspectionNumber();
        return new RenderedReport(
                number == null || number.isBlank() ? "inspection-report" : number, pdf);
    }

    private Context populateContext(InspectionDto inspection) {
        Context ctx = new Context();

        ctx.setVariable("inspectionNumber", ReportText.orDash(inspection.inspectionNumber()));
        ctx.setVariable("title", ReportText.orDash(inspection.title()));
        ctx.setVariable("categoryLabel", ReportText.humanise(inspection.category()));
        ctx.setVariable("tradeLabel", ReportText.humanise(inspection.trade()));
        ctx.setVariable("typeLabel", ReportText.humanise(inspection.type()));
        ctx.setVariable("statusLabel", ReportText.humanise(inspection.status()));
        ctx.setVariable("resultLabel", ReportText.humanise(inspection.result()));
        ctx.setVariable("projectName", resolveProjectName(inspection.projectId()));
        ctx.setVariable("location", ReportText.orDash(inspection.location()));
        ctx.setVariable("areaInspected", ReportText.orDash(inspection.areaInspected()));
        ctx.setVariable("drawingReference", ReportText.orDash(inspection.drawingReference()));
        ctx.setVariable("scheduledDate", ReportText.date(inspection.scheduledDate()));
        ctx.setVariable("startedAt", ReportText.stamp(inspection.actualStartTime()));
        ctx.setVariable("endedAt", ReportText.stamp(inspection.actualEndTime()));
        ctx.setVariable("clientRepresentative", ReportText.orDash(inspection.clientRepresentative()));
        ctx.setVariable("weather", ReportText.orDash(inspection.weatherConditions()));
        ctx.setVariable("attendees", inspection.attendees() == null || inspection.attendees().isEmpty()
                ? ReportText.DASH
                : String.join(", ", inspection.attendees()));

        ctx.setVariable("totalCheckPoints", inspection.totalCheckPoints());
        ctx.setVariable("passedCheckPoints", inspection.passedCheckPoints());
        ctx.setVariable("failedCheckPoints", inspection.failedCheckPoints());
        ctx.setVariable("defectsFound", inspection.defectsFound());

        List<InspectionCheckItemDto> checkItems = orEmpty(inspection.checkItems());
        List<InspectionDefectDto> defects = orEmpty(inspection.defects());

        ctx.setVariable("checkRows", toCheckRows(capped(checkItems)));
        ctx.setVariable("defectRows", toDefectRows(capped(defects)));
        ctx.setVariable("checkItemTotal", checkItems.size());
        ctx.setVariable("defectTotal", defects.size());
        ctx.setVariable("rowCap", UnpagedResultCap.MAX_ROWS);
        ctx.setVariable("truncated",
                checkItems.size() > UnpagedResultCap.MAX_ROWS
                        || defects.size() > UnpagedResultCap.MAX_ROWS);

        addPlates(ctx, inspection, defects);

        ctx.setVariable("generatedOn", ReportText.generatedNow());
        return ctx;
    }

    /**
     * Builds the marked-up photograph plates.
     *
     * <p>Only photographs that carry a mark are printed. An inspection's photos are
     * already in the app; what a report adds is the annotated evidence, and
     * printing every unmarked photo would bury it while multiplying the size of the
     * document.
     */
    private void addPlates(Context ctx, InspectionDto inspection, List<InspectionDefectDto> defects) {
        Page<DefectPhotoAnnotationDto> annotations = annotationService.findByInspection(
                inspection.id(), PageRequest.of(0, UnpagedResultCap.MAX_ROWS));

        Map<String, List<DefectPhotoAnnotationDto>> byPhoto = new LinkedHashMap<>();
        for (DefectPhotoAnnotationDto mark : annotations.getContent()) {
            byPhoto.computeIfAbsent(mark.photo(), photo -> new ArrayList<>()).add(mark);
        }

        Map<String, String> captions = captionsByPhoto(defects);

        List<PhotoPlate> plates = new ArrayList<>();
        int embedded = 0;
        for (Map.Entry<String, List<DefectPhotoAnnotationDto>> entry : byPhoto.entrySet()) {
            if (embedded >= photoLoader.maxPhotos()) {
                break;
            }
            embedded++;

            String photo = entry.getKey();
            String image = photoLoader.dataUri(photo).orElse(null);
            plates.add(new PhotoPlate(
                    "Plate " + embedded,
                    captions.getOrDefault(photo, "Defect photograph"),
                    image,
                    image == null ? "This photograph could not be read from storage." : null,
                    AnnotationGeometry.place(entry.getValue())));
        }

        ctx.setVariable("plates", plates);
        ctx.setVariable("annotatedPhotoTotal", byPhoto.size());
        ctx.setVariable("photoCap", photoLoader.maxPhotos());
        ctx.setVariable("platesTruncated", byPhoto.size() > embedded);
        ctx.setVariable("annotationTotal", annotations.getTotalElements());
        ctx.setVariable("annotationsTruncated",
                annotations.getTotalElements() > annotations.getNumberOfElements());
    }

    /** The defect description to caption each photograph with. */
    private static Map<String, String> captionsByPhoto(List<InspectionDefectDto> defects) {
        Map<String, String> captions = new LinkedHashMap<>();
        for (InspectionDefectDto defect : defects) {
            if (defect.photos() == null) {
                continue;
            }
            for (String photo : defect.photos()) {
                captions.putIfAbsent(photo, ReportText.orDash(defect.description()));
            }
        }
        return captions;
    }

    private static List<CheckRow> toCheckRows(List<InspectionCheckItemDto> items) {
        List<CheckRow> rows = new ArrayList<>(items.size());
        for (InspectionCheckItemDto item : items) {
            rows.add(new CheckRow(
                    ReportText.orDash(item.category()),
                    ReportText.orDash(item.checkPoint()),
                    ReportText.orDash(item.specification()),
                    ReportText.orDash(item.acceptanceCriterion()),
                    ReportText.orDash(item.tolerance()),
                    ReportText.orDash(item.expectedValue()),
                    ReportText.orDash(item.measurement()),
                    item.deviation() == null
                            ? ReportText.DASH
                            : item.deviation().stripTrailingZeros().toPlainString(),
                    ReportText.humanise(item.status()),
                    ReportText.orDash(item.remarks())));
        }
        return rows;
    }

    private static List<DefectRow> toDefectRows(List<InspectionDefectDto> defects) {
        List<DefectRow> rows = new ArrayList<>(defects.size());
        int index = 0;
        for (InspectionDefectDto defect : defects) {
            index++;
            rows.add(new DefectRow(
                    index,
                    ReportText.orDash(defect.description()),
                    ReportText.humanise(defect.severity()),
                    ReportText.orDash(defect.location()),
                    ReportText.orDash(defect.correctiveAction()),
                    ReportText.orDash(defect.responsibleParty()),
                    ReportText.date(defect.targetDate()),
                    ReportText.humanise(defect.status()),
                    ReportText.date(defect.resolvedDate()),
                    defect.photos() == null ? 0 : defect.photos().size()));
        }
        return rows;
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> List<T> capped(List<T> values) {
        return values.size() <= UnpagedResultCap.MAX_ROWS
                ? values
                : values.subList(0, UnpagedResultCap.MAX_ROWS);
    }

    /**
     * The project's name, falling back to its id. A lookup that fails must not fail
     * the report, following the invoice service.
     */
    private String resolveProjectName(Long projectId) {
        if (projectId == null) {
            return ReportText.DASH;
        }
        try {
            ProjectDto project = projectService.getAProject(projectId);
            if (project != null && project.getProjectName() != null
                    && !project.getProjectName().isBlank()) {
                return project.getProjectName();
            }
        } catch (RuntimeException e) {
            log.debug("Could not resolve project {} for an inspection report", projectId);
        }
        return "Project #" + projectId;
    }

    /** Display-ready projection of one check point. */
    public record CheckRow(String category,
                           String checkPoint,
                           String specification,
                           String acceptanceCriterion,
                           String tolerance,
                           String expectedValue,
                           String measurement,
                           String deviation,
                           String status,
                           String remarks) {
    }

    /** Display-ready projection of one defect. */
    public record DefectRow(int index,
                            String description,
                            String severity,
                            String location,
                            String correctiveAction,
                            String responsibleParty,
                            String targetDate,
                            String status,
                            String resolvedDate,
                            int photoCount) {
    }

    /** One marked-up photograph, with the image inlined and its marks placed over it. */
    public record PhotoPlate(String plateNumber,
                             String caption,
                             String image,
                             String missingNote,
                             List<AnnotationGeometry.PlacedMark> marks) {
    }

}
