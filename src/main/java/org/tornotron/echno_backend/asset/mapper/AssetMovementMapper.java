package org.tornotron.echno_backend.asset.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.asset.AssetMovement;
import org.tornotron.echno_backend.asset.dto.AssetMovementDto;

/**
 * Maps {@link AssetMovement} to its DTO. The associations flatten to their id; the name beside
 * each id is the snapshot the entry carries rather than the association's current name, so a
 * renamed or deleted project does not rewrite history.
 */
@Mapper(componentModel = "spring")
public interface AssetMovementMapper {

    @Mapping(source = "asset.id", target = "assetId")
    @Mapping(source = "fromProject.id", target = "fromProjectId")
    @Mapping(source = "toProject.id", target = "toProjectId")
    @Mapping(source = "fromLocation.id", target = "fromLocationId")
    @Mapping(source = "toLocation.id", target = "toLocationId")
    AssetMovementDto toDto(AssetMovement movement);
}
