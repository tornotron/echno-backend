package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.payable.Payable;
import org.tornotron.echno_backend.payable.dto.PayableDto;

@Component
public class PayableDtoConvertor {

    public static PayableDto convertToDto(Payable payable, FileStorageService fileStorageService) {
        if (payable == null) {
            return null;
        }

        PayableDto dto = new PayableDto();
        dto.setId(payable.getId());
        dto.setPayableNumber(payable.getPayableNumber());
        dto.setContractorName(payable.getContractorName());
        dto.setContractType(payable.getContractType());
        dto.setAmountRecorded(payable.getAmountRecorded());
        dto.setAmountPaid(payable.getAmountPaid());
        dto.setAmountDue(payable.getAmountDue());
        dto.setCreatedAt(payable.getCreatedAt());

        // Vendor info
        if (payable.getVendor() != null) {
            dto.setVendorId(payable.getVendor().getId());
            dto.setVendorName(payable.getVendor().getVendorName());
        }

        // GRN info
        if (payable.getGoodsReceivedNote() != null) {
            dto.setGoodsReceivedNoteId(payable.getGoodsReceivedNote().getId());
            dto.setGrnNumber(payable.getGoodsReceivedNote().getGrnNumber());
        }

        // Created by
        if (payable.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(payable.getCreatedBy(), fileStorageService));
        }

        return dto;
    }
}
