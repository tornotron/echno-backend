package org.tornotron.echno_backend.indentItem.mapper;

import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.material.mapper.MaterialMapper;

/**
 * Maps {@link IndentItem} to its DTO; the material maps through {@link MaterialMapper}.
 *
 * <p>The stock lookup is carried through rather than used here. {@link IndentItemDto} holds a
 * full material DTO, so while the material mapper fetched its own stock a ten-line indent cost
 * twenty aggregate queries and a page of indents multiplied that by the page size. The caller
 * reads the stock for every material on the page once and it travels down as a
 * {@code @Context}.
 */
@Mapper(componentModel = "spring", uses = MaterialMapper.class)
public interface IndentItemMapper {

    /**
     * Converts an indent line.
     *
     * @param item The line to convert.
     * @param stock The stock read for every material being mapped in this request.
     * @return The indent item DTO.
     */
    IndentItemDto toDto(IndentItem item, @Context MaterialStockLookup stock);
}
