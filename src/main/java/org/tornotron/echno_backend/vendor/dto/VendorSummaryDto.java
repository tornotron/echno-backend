package org.tornotron.echno_backend.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
