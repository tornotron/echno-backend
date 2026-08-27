package org.tornotron.echno_backend.inspection.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.inspection.domain.Ncr;
import org.tornotron.echno_backend.inspection.dtos.NcrDto;

@Mapper(componentModel = "spring")
public interface NcrMapper {

    NcrDto toDto(Ncr ncr);
}
