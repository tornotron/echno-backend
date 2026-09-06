package org.tornotron.echno_backend.vendor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.List;

/**
 * A vendor as it is served. Every optional field carries {@code nullable = true}, and a field
 * without it is one the schema behind it makes {@code NOT NULL}; see
 * {@code ReviewedResponseSchemas} for the field-by-field record and what holds it.
 */
@Data
public class VendorDto {

    private Long id;

    private String vendorName;

    @Schema(description = "Street address. Optional on creation, so null where none was given.",
            nullable = true)
    private String vendorAddress;

    private String vendorEmail;

    @Schema(description = "City. Optional on creation, so null where none was given.",
            nullable = true)
    private String city;

    @Schema(description = "State. Optional on creation, so null where none was given.",
            nullable = true)
    private String state;

    @Schema(description = "Postal code. Optional on creation, so null where none was given.",
            nullable = true)
    private String pinCode;

    @Schema(description = "Country. Optional on creation, so null where none was given.",
            nullable = true)
    private String country;

    @Schema(description = "Website. Optional on creation, so null where none was given.",
            nullable = true)
    private String website;

    private VendorType type;

    private VendorStatus status;

    @Schema(description = "Free-text notes about the vendor. Null where none were written.",
            nullable = true)
    private String notes;

    private List<GoodsReceivedNoteDto> goodsReceivedNotes;

    private List<PurchaseOrderDto> purchaseOrders;

    private List<PayableDto> payables;

    private List<VendorContactDto> contacts;

    private List<VendorTaxIdentifierDto> taxIdentifiers;

    private List<VendorBankAccountDto> bankAccounts;

    @Schema(description = "The vendor's single payment-terms record. Null until terms are set, "
            + "and null again once they are deleted.", nullable = true)
    private VendorPaymentTermsDto paymentTerms;
}
