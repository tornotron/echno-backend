package org.tornotron.echno_backend.modules.workprogress.billing.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.AdjustmentEffect;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;
import org.tornotron.echno_backend.modules.workprogress.billing.dto.BillDto;
import org.tornotron.echno_backend.pdfGeneration.PdfRenderer;
import org.tornotron.echno_backend.pdfGeneration.ReportText;

/**
 * Renders a running account or milestone bill to a PDF: the header, the running account of every
 * claimed line (or the milestone's claimed and certified percent), the adjustments and the net
 * payable, with who signed each step. The figures are formatted here, in Indian digit grouping,
 * so the template carries no arithmetic.
 */
@Service
public class ContractBillPdfService {

    private final SpringTemplateEngine pdfTemplateEngine;
    private final PdfRenderer pdfRenderer;

    public ContractBillPdfService(@Qualifier("pdfTemplateEngine") SpringTemplateEngine pdfTemplateEngine,
                                  PdfRenderer pdfRenderer) {
        this.pdfTemplateEngine = pdfTemplateEngine;
        this.pdfRenderer = pdfRenderer;
    }

    public byte[] render(BillDto bill) throws IOException {
        Context ctx = new Context();
        boolean ra = bill.billingModel() == BillingModel.RUNNING_ACCOUNT;
        ctx.setVariable("title", (ra ? "Running Account Bill " : "Milestone Bill ") + bill.billNumber());
        ctx.setVariable("ra", ra);
        ctx.setVariable("billNumber", bill.billNumber());
        ctx.setVariable("status", ReportText.humanise(bill.status()));
        ctx.setVariable("final", bill.amountsFinal());
        ctx.setVariable("projectName", ReportText.orDash(bill.projectName()));
        ctx.setVariable("contractName", bill.contractName());
        ctx.setVariable("contractRef", ReportText.orDash(bill.contractRef()));
        ctx.setVariable("contractorName", bill.contractorName());
        ctx.setVariable("contractValue", inr(bill.contractValue()));
        ctx.setVariable("period", ra ? ReportText.date(bill.periodFrom()) + " to " + ReportText.date(bill.periodTo()) : null);
        ctx.setVariable("milestoneName", ReportText.orDash(bill.milestoneName()));
        ctx.setVariable("milestoneValue", inr(bill.milestoneValue()));
        ctx.setVariable("claimedPercent", percent(bill.claimedPercent()));
        ctx.setVariable("certifiedPercent", percent(bill.certifiedPercent()));
        ctx.setVariable("certifiedBeforePercent", percent(bill.milestoneCertifiedBeforePercent()));
        ctx.setVariable("contractorReference", ReportText.orDash(bill.contractorReference()));
        ctx.setVariable("location", ReportText.orDash(bill.location()));
        ctx.setVariable("measurementDate", ReportText.date(bill.measurementDate()));
        ctx.setVariable("measuredBy", ReportText.orDash(bill.measuredBy()));
        ctx.setVariable("clientRepresentative", ReportText.orDash(bill.clientRepresentative()));
        ctx.setVariable("lines", bill.lines().stream()
                .filter(l -> l.claimedQuantity().signum() > 0 || l.previousQuantity().signum() > 0)
                .map(l -> new PdfLine(l.itemCode(), l.description(), l.unit(), qty(l.contractQuantity()), inr(l.rate()),
                        qty(l.previousQuantity()), qty(l.claimedQuantity()),
                        l.acceptedQuantity() == null ? ReportText.DASH : qty(l.acceptedQuantity()),
                        qty(l.cumulativeQuantity()), inr(l.thisAmount()), inr(l.cumulativeAmount())))
                .toList());
        ctx.setVariable("adjustments", bill.adjustments().stream()
                .map(a -> new PdfAdjustment(a.label(), a.effect() == AdjustmentEffect.ADD ? "Add" : "Less", inr(a.amount())))
                .toList());
        ctx.setVariable("grossClaimed", inr(bill.grossClaimed()));
        ctx.setVariable("grossAmount", inr(bill.grossAmount()));
        ctx.setVariable("additions", inr(bill.additionsTotal()));
        ctx.setVariable("deductions", inr(bill.deductionsTotal()));
        ctx.setVariable("netPayable", inr(bill.netPayable()));
        ctx.setVariable("previousCertified", inr(bill.previousCertified()));
        ctx.setVariable("cumulativeCertified", inr(bill.cumulativeCertified()));
        ctx.setVariable("signatures", List.of(
                new PdfSignature("Prepared", ReportText.orDash(bill.preparedByName()), ReportText.stamp(bill.createdAt())),
                new PdfSignature("Submitted", ReportText.orDash(bill.submittedByName()), ReportText.stamp(bill.submittedAt())),
                new PdfSignature("Verified", ReportText.orDash(bill.verifiedByName()), ReportText.stamp(bill.verifiedAt())),
                new PdfSignature("Certified", ReportText.orDash(bill.certifiedByName()), ReportText.stamp(bill.certifiedAt())),
                new PdfSignature("Approved", ReportText.orDash(bill.approvedByName()), ReportText.stamp(bill.approvedAt()))));
        ctx.setVariable("generatedAt", ReportText.generatedNow());
        return pdfRenderer.render(pdfTemplateEngine.process("contract-bill/bill", ctx));
    }

    /** Rupees with Indian digit grouping (12,34,567.89), prefixed "Rs." as the PDF font has no rupee sign. */
    static String inr(BigDecimal value) {
        if (value == null) {
            return ReportText.DASH;
        }
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        String sign = v.signum() < 0 ? "-" : "";
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        StringBuilder grouped = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(tail);
        } else {
            grouped.append(whole);
        }
        return "Rs. " + sign + grouped + fraction;
    }

    private static String qty(BigDecimal value) {
        return value == null ? ReportText.DASH : value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value == null ? ReportText.DASH : value.stripTrailingZeros().toPlainString() + "%";
    }

    public record PdfLine(String code, String description, String unit, String contractQuantity, String rate,
                          String previous, String claimed, String accepted, String cumulative, String thisAmount,
                          String cumulativeAmount) {
    }

    public record PdfAdjustment(String label, String effect, String amount) {
    }

    public record PdfSignature(String step, String name, String at) {
    }
}
