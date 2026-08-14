package org.tornotron.echno_backend.indentItem.mapper;

import org.mapstruct.Mapper;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.material.mapper.MaterialMapper;

/** Maps {@link IndentItem} to its DTO; the material maps through {@link MaterialMapper}. */
@Mapper(componentModel = "spring", uses = MaterialMapper.class)
public interface IndentItemMapper {

    IndentItemDto toDto(IndentItem item);
}
