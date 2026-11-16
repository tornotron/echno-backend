package org.tornotron.echno_backend.modules.assistant.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantQueryLog;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;

@Mapper(componentModel = "spring")
public interface AssistantQueryLogMapper {
    QueryLogDto toDto(AssistantQueryLog log);
}
