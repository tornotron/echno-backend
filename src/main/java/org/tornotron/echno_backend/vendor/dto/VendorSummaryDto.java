package org.tornotron.echno_backend.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Purchase, payable and receipt totals for one vendor.
 *
 * <p>No field here admits null. The counts are {@code COUNT}, which is zero over no rows, and
 * every money total is summed under a {@code COALESCE(..., 0)} in
 * {@link org.tornotron.echno_backend.vendor.VendorSummaryService}, so a vendor with no purchase
 * orders reports zero rather than the null a bare {@code SUM} would return. See
 * {@code ReviewedResponseSchemas}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorSummaryDto {

    private Long vendorId;
    private String vendorName;

    // Purchase Order summaries
    private long purchaseOrderCount;
    private BigDecimal totalPurchaseOrderValue;

    // Payable summaries
    private BigDecimal totalAmountRecorded;
    private BigDecimal totalAmountPaid;
    private BigDecimal outstandingAmount;

    // GRN summaries
    private long grnCount;
    private BigDecimal totalInvoiceAmount;
}
