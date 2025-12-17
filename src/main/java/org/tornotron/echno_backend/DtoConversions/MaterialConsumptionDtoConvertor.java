package org.tornotron.echno_backend.DtoConversions;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;
import org.tornotron.echno_backend.materialConsumption.dto.MaterialConsumptionDto;

@Component
public class MaterialConsumptionDtoConvertor {

    public static MaterialConsumptionDto convertToDto(MaterialConsumption consumption) {
        if (consumption == null) {
            return null;
        }

        MaterialConsumptionDto dto = new MaterialConsumptionDto();
        dto.setId(consumption.getId());
        dto.setConsumptionDate(consumption.getConsumptionDate());
        dto.setQuantity(consumption.getQuantity());
        dto.setConsumptionType(consumption.getConsumptionType());
        dto.setDetails(consumption.getDetails());

        // Material info
        if (consumption.getMaterial() != null) {
            dto.setMaterialId(consumption.getMaterial().getId());
            dto.setMaterialName(consumption.getMaterial().getMaterialName());
        }

        // Created by
        if (consumption.getCreatedBy() != null) {
            dto.setCreatedBy(UserDtoConvertor.convertUserToDto(consumption.getCreatedBy()));
        }

        return dto;
    }
}
