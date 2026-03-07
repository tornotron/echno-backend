package org.tornotron.echno_backend.vendor.dto;

import lombok.Data;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.payable.dto.PayableDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.vendor.enums.VendorStatus;
import org.tornotron.echno_backend.vendor.enums.VendorType;

import java.util.List;

@Data
public class VendorDto {

    private Long id;
    private String vendorName;
    private String vendorAddress;
    private String vendorEmail;
    private String city;
    private String state;
    private String pinCode;
    private String country;
    private String website;
    private VendorType type;
    private VendorStatus status;
    private String notes;
    private List<GoodsReceivedNoteDto> goodsReceivedNotes;
    private List<PurchaseOrderDto> purchaseOrders;
    private List<PayableDto> payables;
    private List<VendorContactDto> contacts;
    private List<VendorTaxIdentifierDto> taxIdentifiers;
    private List<VendorBankAccountDto> bankAccounts;
    private VendorPaymentTermsDto paymentTerms;
}
