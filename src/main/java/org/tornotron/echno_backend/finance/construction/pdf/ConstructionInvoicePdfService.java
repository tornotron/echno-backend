package org.tornotron.echno_backend.finance.construction.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineDto;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectDto;
import org.tornotron.echno_backend.vendor.VendorService;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a construction invoice into a downloadable PDF. The invoice data is formatted
 * into display-ready strings here (currency, dates, humanised enum labels, resolved vendor
 * and project names) so the Thymeleaf template stays free of formatting logic, and the
 * result is rendered with the same openhtmltopdf engine used by the site progress report.
 */
@Service
public class ConstructionInvoicePdfService {

    private static final String DASH = "—";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private final SpringTemplateEngine pdfTemplateEngine;
    private final VendorService vendorService;
    private final ProjectService projectService;

    public ConstructionInvoicePdfService(@Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
                                         VendorService vendorService,
                                         ProjectService projectService) {
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.vendorService = vendorService;
        this.projectService = projectService;
    }

    /**
     * Builds the invoice PDF for the given invoice.
     *
     * @param invoice the invoice to render, already loaded and tenant scoped by the caller
     * @return the rendered PDF as a byte array
     * @throws IOException if the PDF cannot be rendered
     */
    public byte[] render(ConstructionInvoiceDto invoice) throws IOException {
        Context ctx = populateContext(invoice);
        String html = pdfTemplateEngine.process("invoice/invoice", ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "classpath:/templates/");
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        }
    }

    private Context populateContext(ConstructionInvoiceDto inv) {
        Context ctx = new Context();
        ctx.setVariable("invoiceNumber", orDash(inv.invoiceNumber()));
        ctx.setVariable("typeLabel", humanise(inv.type() == null ? null : inv.type().name()));
        ctx.setVariable("statusLabel", humanise(inv.status() == null ? null : inv.status().name()));
        ctx.setVariable("paymentStatusLabel",
                humanise(inv.paymentStatus() == null ? null : inv.paymentStatus().name()));

        ctx.setVariable("vendorName", resolveVendorName(inv.vendorId()));
        ctx.setVariable("projectName", resolveProjectName(inv.projectId()));

        ctx.setVariable("issueDate", date(inv.issueDate()));
        ctx.setVariable("dueDate", date(inv.dueDate()));
        ctx.setVariable("paymentDate", date(inv.paymentDate()));

        ctx.setVariable("gstNumber", nullToEmpty(inv.gstNumber()));
        ctx.setVariable("taxType", nullToEmpty(inv.taxType()));
        ctx.setVariable("paymentTerms", orDash(inv.paymentTerms()));
        ctx.setVariable("paymentMethod", orDash(inv.paymentMethod()));
        ctx.setVariable("notes", nullToEmpty(inv.notes()));
        ctx.setVariable("termsAndConditions", nullToEmpty(inv.termsAndConditions()));

        ctx.setVariable("subtotal", money(inv.subtotal()));
        ctx.setVariable("taxAmount", money(inv.taxAmount()));
        ctx.setVariable("discountAmount", money(inv.discountAmount()));
        ctx.setVariable("totalAmount", money(inv.totalAmount()));
        ctx.setVariable("paidAmount", money(inv.paidAmount()));
        ctx.setVariable("balanceAmount", money(inv.balanceAmount()));

        ctx.setVariable("lines", toLineRows(inv.lines()));
        ctx.setVariable("generatedOn", STAMP_FMT.format(LocalDateTime.now()));
        return ctx;
    }

    private List<PdfLine> toLineRows(List<ConstructionInvoiceLineDto> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(l -> new PdfLine(
                        orDash(l.description()),
                        orDash(l.unit()),
                        plain(l.quantity()),
                        money(l.unitPrice()),
                        percent(l.taxRate()),
                        percent(l.discountRate()),
                        money(l.total())))
                .toList();
    }

    private String resolveVendorName(Long vendorId) {
        if (vendorId == null) {
            return DASH;
        }
        try {
            VendorDto vendor = vendorService.getVendorById(vendorId);
            if (vendor != null && vendor.getVendorName() != null && !vendor.getVendorName().isBlank()) {
                return vendor.getVendorName();
            }
        } catch (RuntimeException ignored) {
            // Fall through to a stable placeholder if the vendor cannot be resolved.
        }
        return "Vendor #" + vendorId;
    }

    private String resolveProjectName(Long projectId) {
        if (projectId == null) {
            return DASH;
        }
        try {
            ProjectDto project = projectService.getAProject(projectId);
            if (project != null && project.getProjectName() != null && !project.getProjectName().isBlank()) {
                return project.getProjectName();
            }
        } catch (RuntimeException ignored) {
            // Fall through to a stable placeholder if the project cannot be resolved.
        }
        return "Project #" + projectId;
    }

    private static String money(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return "Rs. " + String.format(Locale.ENGLISH, "%,.2f", v);
    }

    private static String percent(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return DASH;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? DASH : DATE_FMT.format(value);
    }

    private static String humanise(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return DASH;
        }
        String[] parts = enumName.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? DASH : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Display-ready projection of a single invoice line for the template. */
    public record PdfLine(String description,
                          String unit,
                          String quantity,
                          String unitPrice,
                          String taxRate,
                          String discountRate,
                          String total) {
    }
}
