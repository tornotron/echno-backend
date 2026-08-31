package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;
import org.tornotron.echno_backend.inspection.pdf.NcrReportPdfService;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.inspection.service.NcrService;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.RenderedReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The non-conformance report and the punch list, rendered through the real
 * openhtmltopdf pipeline and the actual Thymeleaf templates.
 *
 * <p>No Spring context, following {@code ConstructionInvoicePdfServiceTest}: the
 * template engine is built from its configuration class and everything else is
 * mocked, so the 1 GB test JVM gains no cached context from this file.
 */
class NcrReportPdfServiceTest {

    private static final UUID NCR_ID = UUID.fromString("12121212-3434-5656-7878-909090909090");
    private static final UUID INSPECTION_ID =
            UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Long ORG_ID = 42L;

    private final NcrService ncrService = mock(NcrService.class);
    private final InspectionRepository inspectionRepo = mock(InspectionRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);

    private final NcrReportPdfService service = new NcrReportPdfService(
            new ThymeleafConfig().pdfTemplateEngine(), new PdfRenderer(),
            ncrService, inspectionRepo, employeeRepository);

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rendersAClosedReportWithItsWholeTrail() throws Exception {
        when(ncrService.findById(NCR_ID)).thenReturn(closedNcr());
        when(inspectionRepo.findNumberByIdScoped(INSPECTION_ID))
                .thenReturn(Optional.of("INSP-2026-0001"));
        givenNames(7L, "Priya QA", 9L, "Ravi Site");

        RenderedReport report = service.render(NCR_ID);

        assertThat(report.documentName()).isEqualTo("NCR-2026-0007");
        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    /**
     * A report that has only been raised still prints. Every stage the trail has
     * not reached prints its placeholder rather than being hidden, so a reader can
     * see how far it got.
     */
    @Test
    void rendersAReportThatHasOnlyBeenRaised() throws Exception {
        when(ncrService.findById(NCR_ID)).thenReturn(new NcrDto(
                NCR_ID, "NCR-2026-0008", NcrType.SAFETY, INSPECTION_ID, null,
                "Edge protection missing", "Open edge on level 4 with no barrier",
                DefectSeverity.CRITICAL, NcrStatus.OPEN, null, null, null, null, null,
                null, null, null, null, null,
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 21, 10, 0)));
        when(inspectionRepo.findNumberByIdScoped(INSPECTION_ID)).thenReturn(Optional.empty());

        RenderedReport report = service.render(NCR_ID);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    @Test
    void rendersThePunchList() throws Exception {
        givenOutstanding(3);
        givenNames(100L, "Ravi Site");

        RenderedReport report = service.renderPunchList(null, null, null);

        assertThat(report.documentName()).isEqualTo("punch-list");
        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
    }

    @Test
    void rendersAnEmptyPunchList() throws Exception {
        givenOutstanding(0);

        RenderedReport report = service.renderPunchList(null, NcrType.QUALITY, null);

        assertThat(new String(report.content(), 0, 5)).startsWith("%PDF-");
        // nothing to name, so the batch lookup is skipped entirely
        verify(employeeRepository, times(0)).findNamesByIds(anyLong(), any());
    }

    /**
     * The punch list is exactly the document a busy site produces with hundreds of
     * rows on it. Every site engineer on it is resolved in one query rather than one
     * per row, so the cost of naming them does not grow with the list.
     */
    @Test
    void resolvesEveryNameOnThePunchListInOneQuery() throws Exception {
        givenOutstanding(200);
        givenNames(100L, "Ravi Site");

        service.renderPunchList(null, null, null);

        verify(employeeRepository, times(1)).findNamesByIds(eq(ORG_ID), any());
    }

    /**
     * Naming the inspection must not load it. The aggregate carries every check
     * point and defect the inspection has, and the report needs one string off it.
     */
    @Test
    void namesTheInspectionWithoutLoadingIt() throws Exception {
        when(ncrService.findById(NCR_ID)).thenReturn(closedNcr());
        when(inspectionRepo.findNumberByIdScoped(INSPECTION_ID))
                .thenReturn(Optional.of("INSP-2026-0001"));
        givenNames(7L, "Priya QA", 9L, "Ravi Site");

        service.render(NCR_ID);

        verify(inspectionRepo).findNumberByIdScoped(INSPECTION_ID);
        verify(inspectionRepo, times(0)).findByIdScoped(any());
    }

    private void givenOutstanding(int rows) {
        List<NcrDto> content = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            // a distinct site engineer per row, so resolving them one at a time would
            // cost one query per row and the batch still costs one
            content.add(new NcrDto(UUID.randomUUID(), "NCR-2026-" + i, NcrType.QUALITY,
                    INSPECTION_ID, null, "Cover below specification", "Measured 25 mm",
                    DefectSeverity.MAJOR, NcrStatus.ASSIGNED, 100L + i, LocalDate.of(2026, 9, 10),
                    7L, null, null, null, null, null, null, null,
                    LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 21, 10, 0)));
        }
        Page<NcrDto> page = new PageImpl<>(content, PageRequest.of(0, 500), rows);
        when(ncrService.findAll(any(), any(), any(), any(), any(), any(), any(), eq(Boolean.TRUE), any()))
                .thenReturn(page);
    }

    /** Stubs the batch name lookup with the given id and name pairs. */
    private void givenNames(Object... idsAndNames) {
        List<EmployeeRepository.EmployeeName> names = new ArrayList<>();
        for (int i = 0; i < idsAndNames.length; i += 2) {
            Long id = (Long) idsAndNames[i];
            String name = (String) idsAndNames[i + 1];
            names.add(new EmployeeRepository.EmployeeName() {
                @Override
                public Long getId() {
                    return id;
                }

                @Override
                public String getEmployeeName() {
                    return name;
                }
            });
        }
        when(employeeRepository.findNamesByIds(anyLong(), any(Collection.class))).thenReturn(names);
    }

    private static NcrDto closedNcr() {
        return new NcrDto(NCR_ID, "NCR-2026-0007", NcrType.QUALITY, INSPECTION_ID,
                UUID.randomUUID(), "Cover below specification",
                "Measured 25 mm against a specified 40 mm", DefectSeverity.MAJOR,
                NcrStatus.CLOSED, 9L, LocalDate.of(2026, 9, 10), 7L, 7L, 7L,
                "Chipped out and re-poured with the correct cover blocks",
                "Re-measured at 41 mm, accepted",
                LocalDateTime.of(2026, 9, 5, 14, 0), LocalDateTime.of(2026, 9, 6, 9, 30),
                LocalDateTime.of(2026, 9, 6, 10, 0),
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 9, 6, 10, 0));
    }
}
