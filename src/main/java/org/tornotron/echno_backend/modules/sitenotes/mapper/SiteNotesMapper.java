package org.tornotron.echno_backend.modules.sitenotes.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNotesEntry;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNotesEntryDto;

@Mapper(componentModel = "spring")
public interface SiteNotesMapper {

    SiteNotesEntryDto toDto(SiteNotesEntry entry);
}
