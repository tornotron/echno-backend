package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.inventoryTransaction.CurrentStockRepository;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

@Component
public class MaterialDtoConvertor {

    public static MaterialDto convertToDto(Material material, FileStorageService fileStorageService, InventoryService inventoryService) {
        if (material == null) {
            return null;
        }

        MaterialDto dto = new MaterialDto();
        dto.setId(material.getId());
        dto.setSku(material.getSku());
        dto.setMaterialName(material.getMaterialName());
        dto.setUnit(material.getUnit());
        dto.setMoq(material.getMoq());
        dto.setHsn(material.getHsn());
        dto.setDescription(material.getDescription());
        dto.setOpeningStock(material.getOpeningStock());
        dto.setMinStock(material.getMinStock());
        dto.setMaxStock(material.getMaxStock());
        dto.setSafetyStock(material.getSafetyStock());
        dto.setReorderLevel(material.getReorderLevel());
        dto.setCurrentStock(inventoryService.getAggregateStock(material.getId()));
        dto.setStockValue(inventoryService.getAggregateStockValue(material.getId()));

        if(material.getCreatedBy() != null) {
            dto.setCreatedBy(EmployeeDtoConvertor.convertEmployeeToDto(material.getCreatedBy(),fileStorageService));
        }

        return dto;
    }

    public static MaterialWithStockDto convertToWithStockDto(Material material, Double currentStock) {
        if (material == null) {
            return null;
        }

        MaterialWithStockDto dto = new MaterialWithStockDto();
        dto.setId(material.getId());
        dto.setSku(material.getSku());
        dto.setMaterialName(material.getMaterialName());
        dto.setUnit(material.getUnit());
        dto.setCurrentStock(currentStock != null ? currentStock : 0);

        return dto;
    }

    public static MaterialWithStockDto convertToWithStockDto(Material material, Double currentStock, java.math.BigDecimal stockValue) {
        MaterialWithStockDto dto = convertToWithStockDto(material, currentStock);
        if (dto != null) {
            dto.setStockValue(stockValue);
        }
        return dto;
    }
}
