package org.tornotron.echno_backend.modules.sitenotes.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.modules.sitenotes.domain.SiteNote;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNoteDto;

@Mapper(componentModel = "spring")
public interface SiteNotesMapper {

    SiteNoteDto toDto(SiteNote note);
}
