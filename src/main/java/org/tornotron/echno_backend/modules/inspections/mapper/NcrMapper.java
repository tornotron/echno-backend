package org.tornotron.echno_backend.modules.inspections.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;
import org.tornotron.echno_backend.modules.inspections.dtos.NcrDto;

/**
 * Maps {@link Ncr} to its DTO. The report carries only the id of its inspection; the
 * inspection's number, title and project are read separately for a whole page and filled in
 * through {@link NcrDto#withInspection}, so they are left null here.
 */
@Mapper(componentModel = "spring")
public interface NcrMapper {

    @Mapping(target = "inspectionNumber", ignore = true)
    @Mapping(target = "inspectionTitle", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    @Mapping(target = "projectName", ignore = true)
    NcrDto toDto(Ncr ncr);
}
