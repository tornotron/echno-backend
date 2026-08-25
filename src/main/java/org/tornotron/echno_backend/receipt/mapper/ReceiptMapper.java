package org.tornotron.echno_backend.receipt.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.receipt.Receipt;
import org.tornotron.echno_backend.receipt.dto.ReceiptDto;

/**
 * Maps {@link Receipt} to its response DTO. The entity is flat; organization flattens
 * to its id.
 */
@Mapper(componentModel = "spring")
public interface ReceiptMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    ReceiptDto toDto(Receipt receipt);
}
