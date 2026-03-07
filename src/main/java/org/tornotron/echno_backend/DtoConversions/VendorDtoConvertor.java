package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.dto.VendorDto;
import org.tornotron.echno_backend.vendor.dto.VendorTaxIdentifierDto;

import java.util.stream.Collectors;

@Component
public class VendorDtoConvertor {

    public static VendorDto convertToDto(Vendor vendor, FileStorageService fileStorageService) {
        if (vendor == null) {
            return null;
        }

        VendorDto dto = new VendorDto();
        dto.setId(vendor.getId());
        dto.setVendorName(vendor.getVendorName());
        dto.setVendorAddress(vendor.getVendorAddress());
        dto.setVendorEmail(vendor.getVendorEmail());
        dto.setCity(vendor.getCity());
        dto.setState(vendor.getState());
        dto.setPinCode(vendor.getPinCode());
        dto.setCountry(vendor.getCountry());
        dto.setWebsite(vendor.getWebsite());
        dto.setType(vendor.getType());
        dto.setStatus(vendor.getStatus());
        dto.setNotes(vendor.getNotes());
        dto.setGoodsReceivedNotes(vendor.getGoodsReceivedNotes()
                .stream().map(goodsReceivedNote -> GoodsReceivedNoteDtoConvertor.convertToDto(goodsReceivedNote, fileStorageService))
                .collect(Collectors.toList()));
        dto.setPurchaseOrders(vendor.getPurchaseOrders()
                .stream().map(purchaseOrder -> PurchaseOrderDtoConvertor.convertToDto(purchaseOrder,fileStorageService))
                .collect(Collectors.toList()));
        dto.setPayables(vendor.getPayables()
                .stream().map(payable -> PayableDtoConvertor.convertToDto(payable,fileStorageService))
                .collect(Collectors.toList()));
        dto.setContacts(vendor.getContacts()
                .stream().map(VendorContactDtoConvertor::convertToDto)
                .collect(Collectors.toList()));
        dto.setTaxIdentifiers(vendor.getTaxIdentifiers()
                .stream().map(VendorTaxIdentifierDtoConvertor::convertToDto)
                .collect(Collectors.toList()));
        dto.setBankAccounts(vendor.getBankAccounts()
                .stream().map(VendorBankAccountsDtoConvertor::convertToDto)
                .collect(Collectors.toList()));
        dto.setPaymentTerms(VendorPaymentTermsDtoConvertor.convertToDto(vendor.getPaymentTerms()));
        return dto;
    }
}
