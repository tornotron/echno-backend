package org.tornotron.echno_backend.inspection.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inspection.NcrType;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;
import org.tornotron.echno_backend.pdfGeneration.ReportText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The non-conformance reports (FR-REP-10 to FR-REP-13): one report printed with
 * its full accountability trail, and the punch list of everything still
 * outstanding.
 *
 * <p>The trail is the point of the document. #498 put who raised the report, which
 * site engineer owns it, what corrective work was reported and when, who accepted
 * it and who closed it on the record precisely so a report could print them, and
 * this is where they are printed. Nothing is derived or inferred here: every date
 * and every name comes off the stored row.
 *
 * <p>The punch list is the {@code open=true} query the NCR listing already answers,
 * rendered rather than reimplemented. It goes through {@code NcrService.findAll}
 * with a {@link UnpagedResultCap#MAX_ROWS} page, so it is bounded by the same
 * ceiling as every other capped read, prints the true total, and says when it is
 * showing only part of it.
 */
@Slf4j
@Service
public class NcrReportPdfService {

    private static final String SINGLE_TEMPLATE = "inspection/ncr-report";
    private static final String PUNCH_LIST_TEMPLATE = "inspection/ncr-punch-list";

    private final SpringTemplateEngine pdfTemplateEngine;
    private final PdfRenderer pdfRenderer;
    private final NcrService ncrService;
    private final InspectionRepository inspectionRepo;
    private final EmployeeRepository employeeRepository;

    public NcrReportPdfService(
            @Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
            PdfRenderer pdfRenderer,
            NcrService ncrService,
            InspectionRepository inspectionRepo,
            EmployeeRepository employeeRepository) {
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.pdfRenderer = pdfRenderer;
        this.ncrService = ncrService;
        this.inspectionRepo = inspectionRepo;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Renders one non-conformance report with its trail.
     *
     * @param ncrId The report to print, resolved through {@code NcrService} so it
     *              can only be one in the caller's tenant.
     * @return The document name and the PDF bytes.
     * @throws IOException if the document cannot be rendered.
     */
    public RenderedReport render(UUID ncrId) throws IOException {
        NcrDto ncr = ncrService.findById(ncrId);
        Map<Long, String> names = namesFor(
                Arrays.asList(ncr.raisedById(), ncr.siteEngineerId(),
                        ncr.verifiedById(), ncr.closedById()));

        Context ctx = new Context();
        ctx.setVariable("ncrNumber", ReportText.orDash(ncr.ncrNumber()));
        ctx.setVariable("typeLabel", ReportText.humanise(ncr.type()));
        ctx.setVariable("title", ReportText.orDash(ncr.title()));
        ctx.setVariable("description", ReportText.orDash(ncr.description()));
        ctx.setVariable("severityLabel", ReportText.humanise(ncr.severity()));
        ctx.setVariable("statusLabel", ReportText.humanise(ncr.status()));
        ctx.setVariable("targetDate", ReportText.date(ncr.targetDate()));
        ctx.setVariable("raisedBy", nameOf(names, ncr.raisedById()));
        ctx.setVariable("raisedOn", ReportText.stamp(ncr.createdAt()));
        ctx.setVariable("siteEngineer", nameOf(names, ncr.siteEngineerId()));
        ctx.setVariable("correctiveActionRemarks", ReportText.orDash(ncr.correctiveActionRemarks()));
        ctx.setVariable("correctiveActionCompletedAt",
                ReportText.stamp(ncr.correctiveActionCompletedAt()));
        ctx.setVariable("verificationRemarks", ReportText.orDash(ncr.verificationRemarks()));
        ctx.setVariable("verifiedBy", nameOf(names, ncr.verifiedById()));
        ctx.setVariable("verifiedAt", ReportText.stamp(ncr.verifiedAt()));
        ctx.setVariable("closedBy", nameOf(names, ncr.closedById()));
        ctx.setVariable("closedAt", ReportText.stamp(ncr.closedAt()));
        ctx.setVariable("inspectionReference", inspectionReference(ncr.inspectionId()));
        ctx.setVariable("defectReference",
                ncr.defectId() == null ? "Raised against the inspection as a whole"
                        : ncr.defectId().toString());
        ctx.setVariable("generatedOn", ReportText.generatedNow());

        byte[] pdf = pdfRenderer.render(pdfTemplateEngine.process(SINGLE_TEMPLATE, ctx));
        String number = ncr.ncrNumber();
        return new RenderedReport(number == null || number.isBlank() ? "ncr" : number, pdf);
    }

    /**
     * Renders the punch list: every non-conformance not yet closed, newest first.
     *
     * @param inspectionId   Optional filter to one inspection.
     * @param type           Optional filter to the quality or the safety discipline.
     * @param siteEngineerId Optional filter to one assignee's outstanding work.
     * @return The document name and the PDF bytes.
     * @throws IOException if the document cannot be rendered.
     */
    public RenderedReport renderPunchList(UUID inspectionId, NcrType type, Long siteEngineerId)
            throws IOException {
        Page<NcrDto> page = ncrService.findAll(
                inspectionId, type, null, siteEngineerId, Boolean.TRUE,
                PageRequest.of(0, UnpagedResultCap.MAX_ROWS,
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        List<NcrDto> outstanding = page.getContent();
        Map<Long, String> names = namesFor(outstanding.stream()
                .map(NcrDto::siteEngineerId)
                .toList());

        List<PunchRow> rows = new ArrayList<>(outstanding.size());
        int index = 0;
        for (NcrDto ncr : outstanding) {
            index++;
            rows.add(new PunchRow(
                    index,
                    ReportText.orDash(ncr.ncrNumber()),
                    ReportText.humanise(ncr.type()),
                    ReportText.orDash(ncr.title()),
                    ReportText.humanise(ncr.severity()),
                    ReportText.humanise(ncr.status()),
                    nameOf(names, ncr.siteEngineerId()),
                    ReportText.date(ncr.targetDate()),
                    ReportText.stamp(ncr.createdAt())));
        }

        Context ctx = new Context();
        ctx.setVariable("rows", rows);
        ctx.setVariable("total", page.getTotalElements());
        ctx.setVariable("rowCap", UnpagedResultCap.MAX_ROWS);
        ctx.setVariable("truncated", page.getTotalElements() > page.getNumberOfElements());
        ctx.setVariable("typeLabel", type == null ? "Quality and safety" : ReportText.humanise(type));
        ctx.setVariable("generatedOn", ReportText.generatedNow());

        byte[] pdf = pdfRenderer.render(pdfTemplateEngine.process(PUNCH_LIST_TEMPLATE, ctx));
        return new RenderedReport("punch-list", pdf);
    }

    /**
     * Names for the employee ids a document is about to print, in one query.
     *
     * <p>Resolved as a batch rather than per row. On the punch list that is the
     * difference between one query and one per outstanding report, and the punch
     * list is exactly the document a busy site produces with hundreds of rows on
     * it.
     */
    private Map<Long, String> namesFor(List<Long> employeeIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : employeeIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> names = new HashMap<>();
        employeeRepository.findNamesByIds(TenantContext.getCurrentOrgId(), ids)
                .forEach(row -> names.put(row.getId(), row.getEmployeeName()));
        return names;
    }

    /** An employee's name, falling back to the id so the trail never prints a blank. */
    private static String nameOf(Map<Long, String> names, Long employeeId) {
        if (employeeId == null) {
            return ReportText.DASH;
        }
        String name = names.get(employeeId);
        return name == null || name.isBlank() ? "Employee #" + employeeId : name;
    }

    /**
     * The inspection the report came from, by its document number.
     *
     * <p>Reads the number alone rather than the inspection. Loading the aggregate
     * would pull every check point and defect it carries in order to print one
     * string, and the report already has everything else it needs.
     */
    private String inspectionReference(UUID inspectionId) {
        if (inspectionId == null) {
            return ReportText.DASH;
        }
        return inspectionRepo.findNumberByIdScoped(inspectionId)
                .map(ReportText::orDash)
                .orElseGet(inspectionId::toString);
    }

    /** Display-ready projection of one outstanding non-conformance. */
    public record PunchRow(int index,
                           String ncrNumber,
                           String type,
                           String title,
                           String severity,
                           String status,
                           String siteEngineer,
                           String targetDate,
                           String raisedOn) {
    }
}
