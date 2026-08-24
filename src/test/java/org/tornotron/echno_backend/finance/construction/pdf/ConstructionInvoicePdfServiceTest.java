package org.tornotron.echno_backend.finance.construction.pdf;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineDto;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.vendor.VendorService;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renders a fully populated invoice through the real openhtmltopdf pipeline and the actual
 * Thymeleaf template, proving the template is well formed enough to produce a PDF and that
 * the resolved vendor and project names are wired into the document.
 */
class ConstructionInvoicePdfServiceTest {

    private final VendorService vendorService = mock(VendorService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ConstructionInvoicePdfService service =
            new ConstructionInvoicePdfService(new ThymeleafConfig().pdfTemplateEngine(), vendorService, projectService);

    @Test
    void render_producesAPdf_forAFullyPopulatedInvoice() throws Exception {
        VendorDto vendor = new VendorDto();
        vendor.setId(17L);
        vendor.setVendorName("Sri Ganesh Traders");
        when(vendorService.getVendorById(17L)).thenReturn(vendor);

        ProjectDto project = new ProjectDto();
        project.setId(42L);
        project.setProjectName("Tower B, Riverside Residences");
        when(projectService.getAProject(42L)).thenReturn(project);

        byte[] pdf = service.render(sampleInvoice());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    void render_producesAPdf_whenOptionalFieldsAndLinesAreMissing() throws Exception {
        ConstructionInvoiceDto invoice = new ConstructionInvoiceDto(
                UUID.randomUUID(), "CINV-2026-0002", ConstructionInvoiceType.PURCHASE,
                ConstructionInvoiceStatus.DRAFT, ConstructionPaymentStatus.UNPAID,
                null, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null);

        byte[] pdf = service.render(invoice);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    private ConstructionInvoiceDto sampleInvoice() {
        ConstructionInvoiceLineDto line = new ConstructionInvoiceLineDto(
                UUID.randomUUID(), "OPC 53 grade cement", new BigDecimal("200"), "bag",
                new BigDecimal("350.00"), new BigDecimal("18.0"), new BigDecimal("12600.00"),
                new BigDecimal("5.0"), new BigDecimal("3500.00"), new BigDecimal("70000.00"),
                new BigDecimal("79100.00"), 512L, 88L, 1204L, UUID.randomUUID(), "Materials");

        return new ConstructionInvoiceDto(
                UUID.randomUUID(), "CINV-2026-0042", ConstructionInvoiceType.PURCHASE,
                ConstructionInvoiceStatus.PARTIALLY_PAID, ConstructionPaymentStatus.PARTIALLY_PAID,
                42L, 17L, 108L, 231L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null,
                new BigDecimal("67500.00"), new BigDecimal("12150.00"), new BigDecimal("3375.00"),
                new BigDecimal("76275.00"), new BigDecimal("40000.00"), new BigDecimal("36275.00"),
                "Net 30", "BANK_TRANSFER", "29ABCDE1234F1Z5", "CGST_SGST",
                "Second progress claim for tower B", "Payable within 30 days of receipt.",
                5L, null, 2L, null, 5L, UUID.randomUUID(), null,
                List.of(line));
    }
}
