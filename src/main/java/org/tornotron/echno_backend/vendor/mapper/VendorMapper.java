package org.tornotron.echno_backend.vendor.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.goodsReceivedNote.mapper.GoodsReceivedNoteMapper;
import org.tornotron.echno_backend.payable.mapper.PayableMapper;
import org.tornotron.echno_backend.purchaseOrder.mapper.PurchaseOrderMapper;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.dto.VendorDto;

/**
 * Maps {@link Vendor} to its DTO. The embedded GRN / purchase-order / payable lists map
 * through their domain mappers; contacts, tax identifiers, bank accounts and payment terms
 * through the vendor sub-mappers. Scalar fields map by name.
 */
@Mapper(componentModel = "spring", uses = {
        GoodsReceivedNoteMapper.class,
        PurchaseOrderMapper.class,
        PayableMapper.class,
        VendorContactMapper.class,
        VendorTaxIdentifierMapper.class,
        VendorBankAccountMapper.class,
        VendorPaymentTermsMapper.class
})
public interface VendorMapper {
    VendorDto toDto(Vendor vendor);
}
