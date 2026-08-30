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
import org.tornotron.echno_backend.indent.dto.IndentSummaryDto;
import org.tornotron.echno_backend.indentItem.IndentItemCountLookup;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;

/**
 * Maps {@link Indent} to its DTO. createdBy maps through {@link EmployeeMapper}, the
 * item lines through {@link IndentItemMapper}, and the project flattens to id + name.
 * The old converter set an empty list when the indent had no items, so
 * {@link #emptyItemsIfNull} restores that (MapStruct would otherwise leave it null).
 *
 * <p>The stock lookup is carried through to the line items, which carry it on to the material.
 *
 * <p>{@link #toSummaryDto} is the list projection. It drops the lines entirely, which is what
 * makes it worth having: a line carries a whole material, and a material carries stock figures
 * read from a further aggregate, so a page of indents renders a column of indent numbers by
 * materialising the catalogue. The one thing a list wants from the lines is how many there are,
 * and that is counted for the whole page in one read. It also flattens the raiser to an id and a
 * name rather than carrying a full employee, whose own DTO reaches a shift, a manager and a set
 * of attachments.
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

    /**
     * Converts an indent for a list, taking its line count from the supplied lookup.
     *
     * @param indent The indent to convert.
     * @param itemCounts The line counts read for the whole page of indents being mapped. An indent
     *                   absent from it reads as zero.
     * @return The indent summary.
     */
    @Mapping(source = "indent.createdBy.id", target = "createdById")
    @Mapping(source = "indent.createdBy.employeeName", target = "createdByName")
    @Mapping(source = "indent.project.id", target = "projectId")
    @Mapping(source = "indent.project.projectName", target = "projectName")
    @Mapping(target = "itemCount", expression = "java(itemCounts.itemCountOf(indent.getId()))")
    IndentSummaryDto toSummaryDto(Indent indent, @Context IndentItemCountLookup itemCounts);

    @AfterMapping
    default void emptyItemsIfNull(@MappingTarget IndentDto dto) {
        if (dto.getItems() == null) {
            dto.setItems(Collections.emptyList());
        }
    }
}
