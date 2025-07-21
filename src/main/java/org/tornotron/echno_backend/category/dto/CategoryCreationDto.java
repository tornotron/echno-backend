package org.tornotron.echno_backend.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryCreationDto {

    @NotBlank(message = "name is required")
    private String name;

    @Size(max = 255, message = "description must be at most 255 characters")
    @NotBlank(message = "description is required")
    private String description;

    private String icon;

    private String image;
}
