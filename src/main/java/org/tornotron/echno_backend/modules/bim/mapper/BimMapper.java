package org.tornotron.echno_backend.modules.bim.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.BimImportJobDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelVersionDto;

@Mapper(componentModel = "spring")
public interface BimMapper {

    @Mapping(target = "id", source = "model.id")
    @Mapping(target = "createdAt", source = "model.createdAt")
    @Mapping(target = "updatedAt", source = "model.updatedAt")
    @Mapping(target = "versions", source = "versions")
    BimModelDto toDto(BimModel model, List<BimModelVersionDto> versions);

    @Mapping(target = "hierarchyProposed", expression = "java(version.getHierarchyProposal() != null)")
    BimModelVersionDto toDto(BimModelVersion version);

    BimElementDto toDto(BimElement element);

    BimImportJobDto toDto(BimImportJob job);
}
