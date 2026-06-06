package org.tornotron.echno_backend.finance.invoice.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.invoice.domain.Invoice;
import org.tornotron.echno_backend.finance.invoice.domain.InvoiceLine;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceLineDto;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {
    @Mapping(source = "customer.id",   target = "customerId")
    @Mapping(source = "customer.name", target = "customerName")
    @Mapping(target = "balanceDue", expression = "java(invoice.balanceDue())")
    InvoiceDto toDto(Invoice invoice);

    @Mapping(source = "revenueAccount.id",   target = "revenueAccountId")
    @Mapping(source = "revenueAccount.code", target = "revenueAccountCode")
    InvoiceLineDto toLineDto(InvoiceLine line);
}
