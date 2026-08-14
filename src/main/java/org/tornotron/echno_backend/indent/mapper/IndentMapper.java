package org.tornotron.echno_backend.indent.mapper;

import java.util.Collections;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;

/**
 * Maps {@link Indent} to its DTO. createdBy maps through {@link EmployeeMapper}, the
 * item lines through {@link IndentItemMapper}, and the project flattens to id + name.
 * The old converter set an empty list when the indent had no items, so
 * {@link #emptyItemsIfNull} restores that (MapStruct would otherwise leave it null).
 */
@Mapper(componentModel = "spring", uses = {EmployeeMapper.class, IndentItemMapper.class})
public interface IndentMapper {

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    IndentDto toDto(Indent indent);

    @AfterMapping
    default void emptyItemsIfNull(@MappingTarget IndentDto dto) {
        if (dto.getItems() == null) {
            dto.setItems(Collections.emptyList());
        }
    }
}
