package org.tornotron.echno_backend.wbs.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.wbs.WbsElement;
import org.tornotron.echno_backend.wbs.dto.WbsElementDto;
import org.tornotron.echno_backend.wbs.dto.WbsElementFlatDto;

/**
 * Maps {@link WbsElement} to its DTOs. createdBy maps through {@link EmployeeMapper};
 * project and parent flatten to id + name/code.
 *
 * toDto leaves children null (the flat, single-node view); toTreeDto maps the children
 * recursively onto itself (via the "tree" qualifier). The children collection is always
 * initialized on the entity, so an empty collection maps to an empty list, matching the
 * old converter's List.of() for a leaf. toFlatDto is the trimmed no-associations view.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface WbsElementMapper {

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "parent.id", target = "parentId")
    @Mapping(source = "parent.wbsCode", target = "parentWbsCode")
    @Mapping(target = "children", ignore = true)
    WbsElementDto toDto(WbsElement element);

    @Named("tree")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "parent.id", target = "parentId")
    @Mapping(source = "parent.wbsCode", target = "parentWbsCode")
    @Mapping(target = "children", source = "children", qualifiedByName = "tree")
    WbsElementDto toTreeDto(WbsElement element);

    @Mapping(source = "parent.id", target = "parentId")
    WbsElementFlatDto toFlatDto(WbsElement element);
}
