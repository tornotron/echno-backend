package org.tornotron.echno_backend.common.customAnnotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.tornotron.echno_backend.material.dto.MaterialCreationDto;

public class OpeningStockValidator implements ConstraintValidator<ValidOpeningStock, MaterialCreationDto> {

    @Override
    public boolean isValid(MaterialCreationDto dto, ConstraintValidatorContext context) {
        if (dto.getOpeningStock() == null || dto.getOpeningStock() <= 0) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        if (dto.getProjectId() == null) {
            context.buildConstraintViolationWithTemplate("projectId is required when openingStock is greater than 0")
                    .addPropertyNode("projectId")
                    .addConstraintViolation();
            valid = false;
        }

        if (dto.getStorageLocationId() == null) {
            context.buildConstraintViolationWithTemplate("storageLocationId is required when openingStock is greater than 0")
                    .addPropertyNode("storageLocationId")
                    .addConstraintViolation();
            valid = false;
        }

        return valid;
    }
}
