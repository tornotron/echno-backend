package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Organization-wide billing figures for the home page cards.")
public record BillingOverviewDto(
        @Schema(description = "Bills not yet approved or cancelled.")
        long openBills,
        long drafts,
        @Schema(description = "Submitted bills waiting for the joint measurement.")
        long awaitingVerification,
        @Schema(description = "Verified bills waiting for certification.")
        long awaitingCertification,
        @Schema(description = "Certified bills waiting for final approval.")
        long awaitingApproval,
        @Schema(description = "Bills returned to the preparer for correction.")
        long returned,
        long approvedBills,
        @Schema(description = "Gross certified on certified and approved bills, in rupees.")
        BigDecimal certifiedToDate,
        @Schema(description = "Net payable of approved bills, in rupees.")
        BigDecimal netApprovedToDate
) {}
