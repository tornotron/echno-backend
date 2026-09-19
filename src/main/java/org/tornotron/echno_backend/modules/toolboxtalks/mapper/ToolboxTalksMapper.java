package org.tornotron.echno_backend.modules.toolboxtalks.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalk;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkAttendee;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkAttendeeDto;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;

@Mapper(componentModel = "spring")
public interface ToolboxTalksMapper {
    ToolboxTalkDto toDto(ToolboxTalk talk);

    ToolboxTalkAttendeeDto toDto(ToolboxTalkAttendee attendee);
}
