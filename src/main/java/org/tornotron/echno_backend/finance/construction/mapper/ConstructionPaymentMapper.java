package org.tornotron.echno_backend.finance.construction.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.user.UserNameLookup;

/**
 * Maps {@link ConstructionPayment} to its DTO.
 *
 * <p>The voucher records who raised and who verified it as user ids and nothing else, so both names
 * come from the {@link UserNameLookup} the caller hands in rather than from the payment. That is the shape
 * {@code ConstructionInvoiceMapper} and {@code StockAdjustmentMapper} already use, and
 * {@code MapperDatabaseAccessTest} enforces: whatever a mapper cannot reach from the object it was
 * given, the caller reads once for the whole page and passes in.
 *
 * <p>{@code employeeId} stays a bare id. It is the payee on a salary or advance voucher, an
 * employee id from a different sequence, and resolving it through the user directory is the
 * wrong-person bug echno-web#346 removed.
 */
@Mapper(componentModel = "spring")
public interface ConstructionPaymentMapper {

    /**
     * Converts a payment voucher, taking its raiser and verifier names from the supplied lookup.
     *
     * @param payment The payment to convert.
     * @param names The names read for every user id on the set of payments being mapped.
     * @return The payment DTO.
     */
    @Mapping(target = "raisedByName", expression = "java(names.nameOf(payment.getRaisedBy()))")
    @Mapping(target = "verifiedByName", expression = "java(names.nameOf(payment.getVerifiedBy()))")
    ConstructionPaymentDto toDto(ConstructionPayment payment, @Context UserNameLookup names);
}
