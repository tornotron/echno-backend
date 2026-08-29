package org.tornotron.echno_backend.indent.mapper;

import java.util.Collections;

import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;

/**
 * Maps {@link Indent} to its DTO. createdBy maps through {@link EmployeeMapper}, the
 * item lines through {@link IndentItemMapper}, and the project flattens to id + name.
 * The old converter set an empty list when the indent had no items, so
 * {@link #emptyItemsIfNull} restores that (MapStruct would otherwise leave it null).
 *
 * <p>The stock lookup is carried through to the line items, which carry it on to the material.
 */
@Mapper(componentModel = "spring", uses = {EmployeeMapper.class, IndentItemMapper.class})
public interface IndentMapper {

    /**
     * Converts an indent and its lines.
     *
     * @param indent The indent to convert.
     * @param stock The stock read for every material on this indent, or on the whole page of
     *              indents being mapped.
     * @return The indent DTO.
     */
    @Mapping(source = "indent.project.id", target = "projectId")
    @Mapping(source = "indent.project.projectName", target = "projectName")
    IndentDto toDto(Indent indent, @Context MaterialStockLookup stock);

    @AfterMapping
    default void emptyItemsIfNull(@MappingTarget IndentDto dto) {
        if (dto.getItems() == null) {
            dto.setItems(Collections.emptyList());
        }
    }
}
