package org.tornotron.echno_backend.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.category.WorkSubcategory;

/**
 * One sub-category of a work category, as the task form's second dropdown lists it.
 *
 * @param id          Id of the sub-category.
 * @param categoryId  Id of the work category it sits under.
 * @param name        Name shown in the dropdown and saved on the task when chosen.
 * @param description What the sub-category covers.
 */
@Schema(description = "A sub-category of a work category, offered on the task form once the category is chosen.")
public record WorkSubcategoryDto(
        @Schema(description = "Id of the sub-category.", example = "12")
        Long id,
        @Schema(description = "Id of the work category it sits under.", example = "2")
        Long categoryId,
        @Schema(description = "Name of the sub-category.", example = "Excavation")
        String name,
        @Schema(nullable = true, description = "What the sub-category covers.",
                example = "Digging of soil or rock to the required depth for foundations, trenches and basements.")
        String description) {

    public static WorkSubcategoryDto from(WorkSubcategory subcategory) {
        return new WorkSubcategoryDto(subcategory.getId(), subcategory.getCategory().getId(),
                subcategory.getName(), subcategory.getDescription());
    }
}
