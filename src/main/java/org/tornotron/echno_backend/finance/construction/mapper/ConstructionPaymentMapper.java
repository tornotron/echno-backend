package org.tornotron.echno_backend.finance.construction.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionPayment;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;

@Mapper(componentModel = "spring")
public interface ConstructionPaymentMapper {

    ConstructionPaymentDto toDto(ConstructionPayment payment);
}
