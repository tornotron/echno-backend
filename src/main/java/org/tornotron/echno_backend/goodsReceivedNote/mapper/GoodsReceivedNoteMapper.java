package org.tornotron.echno_backend.goodsReceivedNote.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GoodsReceivedNoteDto;
import org.tornotron.echno_backend.goodsReceivedNote.dto.GrnItemDto;
import org.tornotron.echno_backend.grnItem.GrnItem;

/**
 * Maps {@link GoodsReceivedNote} to its DTO. receivedBy maps through {@link EmployeeMapper};
 * vendor/purchase-order/project/storage-location flatten to id + name; item lines via
 * {@link #toItemDto}. The old converter left items null when there were none, so
 * {@link #nullifyEmptyItems} restores that.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface GoodsReceivedNoteMapper {

    @Mapping(source = "vendor.id", target = "vendorId")
    @Mapping(source = "vendor.vendorName", target = "vendorName")
    @Mapping(source = "purchaseOrder.id", target = "purchaseOrderId")
    @Mapping(source = "purchaseOrder.poNumber", target = "purchaseOrderNumber")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    @Mapping(source = "storageLocation.id", target = "storageLocationId")
    @Mapping(source = "storageLocation.locationName", target = "storageLocationName")
    GoodsReceivedNoteDto toDto(GoodsReceivedNote grn);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    GrnItemDto toItemDto(GrnItem item);

    @AfterMapping
    default void nullifyEmptyItems(@MappingTarget GoodsReceivedNoteDto dto) {
        if (dto.getItems() != null && dto.getItems().isEmpty()) {
            dto.setItems(null);
        }
    }
}
