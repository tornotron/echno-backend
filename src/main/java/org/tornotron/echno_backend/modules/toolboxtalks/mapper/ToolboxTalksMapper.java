package org.tornotron.echno_backend.modules.toolboxtalks.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalksEntry;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalksEntryDto;

@Mapper(componentModel = "spring")
public interface ToolboxTalksMapper {

    ToolboxTalksEntryDto toDto(ToolboxTalksEntry entry);
}
