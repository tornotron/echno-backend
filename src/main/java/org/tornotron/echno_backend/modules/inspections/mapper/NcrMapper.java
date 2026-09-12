package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;

@Mapper(componentModel = "spring")
public interface NcrMapper {

    NcrDto toDto(Ncr ncr);
}
