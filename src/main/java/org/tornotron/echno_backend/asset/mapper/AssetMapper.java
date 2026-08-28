package org.tornotron.echno_backend.asset.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.asset.Asset;
import org.tornotron.echno_backend.asset.dto.AssetDto;

/**
 * Maps {@link Asset} to its DTO. Vendor/location/project/organization flatten to id (+ name).
 *
 * <p>{@code assignedProject} on the DTO stays a name for the client that already reads it, but
 * it is now derived rather than stored: the referenced project's name where the asset names one,
 * and otherwise the free text the asset carried before the reference migration, which is kept
 * rather than dropped.
 */
@Mapper(componentModel = "spring")
public interface AssetMapper {

    @Mapping(source = "assignedProject.id", target = "assignedProjectId")
    @Mapping(target = "assignedProject", expression = "java(asset.getAssignedProject() != null "
            + "? asset.getAssignedProject().getProjectName() : asset.getLegacyAssignedProject())")
    @Mapping(source = "vendor.id", target = "vendorId")
    @Mapping(source = "vendor.vendorName", target = "vendorName")
    @Mapping(source = "location.id", target = "locationId")
    @Mapping(source = "location.locationName", target = "locationName")
    @Mapping(source = "organization.id", target = "organizationId")
    AssetDto toDto(Asset asset);
}
