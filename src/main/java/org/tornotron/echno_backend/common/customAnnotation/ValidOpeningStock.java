package org.tornotron.echno_backend.common.customAnnotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = OpeningStockValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOpeningStock {
    String message() default "projectId and storageLocationId are required when openingStock is greater than 0";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
