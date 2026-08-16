package org.tornotron.echno_backend.asset.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.asset.Asset;
import org.tornotron.echno_backend.asset.dto.AssetDto;

/** Maps {@link Asset} to its DTO. Vendor/location/organization flatten to id (+ name). */
@Mapper(componentModel = "spring")
public interface AssetMapper {

    @Mapping(source = "vendor.id", target = "vendorId")
    @Mapping(source = "vendor.vendorName", target = "vendorName")
    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    @Mapping(source = "organization.id", target = "organizationId")
    AssetDto toDto(Asset asset);
}
