package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.inspections.domain.ElementTypeCatalogueEntry;
import org.tornotron.echno_backend.modules.inspections.domain.OrgElementType;
import org.tornotron.echno_backend.modules.inspections.dtos.ElementTypeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgElementTypeDto;

@Mapper(componentModel = "spring")
public interface ElementTypeMapper {

    OrgElementTypeDto toDto(OrgElementType elementType);

    ElementTypeCatalogueDto toCatalogueDto(ElementTypeCatalogueEntry entry);
}
