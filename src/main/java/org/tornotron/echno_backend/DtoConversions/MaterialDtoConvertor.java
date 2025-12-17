package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.dto.MaterialDto;
import org.tornotron.echno_backend.material.dto.MaterialWithStockDto;

@Component
public class MaterialDtoConvertor {

    public static MaterialDto convertToDto(Material material) {
        if (material == null) {
            return null;
        }

        MaterialDto dto = new MaterialDto();
        dto.setId(material.getId());
        dto.setSku(material.getSku());
        dto.setMaterialName(material.getMaterialName());
        dto.setUnit(material.getUnit());

        return dto;
    }

    public static MaterialWithStockDto convertToWithStockDto(Material material, Integer currentStock) {
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
}
