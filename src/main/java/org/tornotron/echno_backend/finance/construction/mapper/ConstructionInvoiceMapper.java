package org.tornotron.echno_backend.finance.construction.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineDto;

@Mapper(componentModel = "spring")
public interface ConstructionInvoiceMapper {

    ConstructionInvoiceDto toDto(ConstructionInvoice invoice);

    ConstructionInvoiceLineDto toLineDto(ConstructionInvoiceLine line);
}
