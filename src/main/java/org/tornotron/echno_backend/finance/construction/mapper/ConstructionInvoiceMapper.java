package org.tornotron.echno_backend.finance.construction.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoice;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceLineDto;
import org.tornotron.echno_backend.user.UserNameLookup;

/**
 * Maps {@link ConstructionInvoice} and its lines to their DTOs.
 *
 * <p>The invoice records who submitted, approved and paid it as a user id and nothing else, so the
 * names come from the {@link UserNameLookup} the caller hands in rather than from the invoice.
 * That is the shape {@code MaterialMapper.toWithStockDto} established and
 * {@code MapperDatabaseAccessTest} enforces: whatever a mapper cannot reach from the object it was
 * given, the caller reads once for the whole page and passes in.
 */
@Mapper(componentModel = "spring")
public interface ConstructionInvoiceMapper {

    /**
     * Converts an invoice, taking its stamp names from the supplied lookup.
     *
     * @param invoice The invoice to convert.
     * @param names The names read for every user id on the set of invoices being mapped.
     * @return The invoice DTO.
     */
    @Mapping(target = "submittedByName", expression = "java(names.nameOf(invoice.getSubmittedBy()))")
    @Mapping(target = "approvedByName", expression = "java(names.nameOf(invoice.getApprovedBy()))")
    @Mapping(target = "paymentRecordedByName",
            expression = "java(names.nameOf(invoice.getPaymentRecordedBy()))")
    ConstructionInvoiceDto toDto(ConstructionInvoice invoice, @Context UserNameLookup names);

    @Mapping(source = "costCategory.id", target = "costCategoryId")
    @Mapping(source = "costCategory.name", target = "costCategoryName")
    ConstructionInvoiceLineDto toLineDto(ConstructionInvoiceLine line);
}
