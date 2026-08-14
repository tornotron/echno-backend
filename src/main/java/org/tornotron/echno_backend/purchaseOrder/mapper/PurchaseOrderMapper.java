package org.tornotron.echno_backend.purchaseOrder.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderItemDto;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;

/**
 * Maps {@link PurchaseOrder} to its DTO. createdBy maps through {@link EmployeeMapper};
 * vendor/indent/project flatten to id + name; item lines via {@link #toItemDto}. The old
 * converter left items null when there were none, restored by {@link #nullifyEmptyItems}.
 */
@Mapper(componentModel = "spring", uses = EmployeeMapper.class)
public interface PurchaseOrderMapper {

    @Mapping(source = "vendor.id", target = "vendorId")
    @Mapping(source = "vendor.vendorName", target = "vendorName")
    @Mapping(source = "indent.id", target = "indentId")
    @Mapping(source = "indent.indentNumber", target = "indentNumber")
    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.projectName", target = "projectName")
    PurchaseOrderDto toDto(PurchaseOrder purchaseOrder);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.materialName", target = "materialName")
    @Mapping(source = "indentItem.id", target = "indentItemId")
    PurchaseOrderItemDto toItemDto(PurchaseOrderItem item);

    @AfterMapping
    default void nullifyEmptyItems(@MappingTarget PurchaseOrderDto dto) {
        if (dto.getItems() != null && dto.getItems().isEmpty()) {
            dto.setItems(null);
        }
    }
}
