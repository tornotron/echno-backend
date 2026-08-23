package org.tornotron.echno_backend.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload to create a work category used to classify tasks.")
@Data
public class CategoryCreationDto {

    @Schema(description = "Name of the category.", example = "Reinforcement Steel")
    @NotBlank(message = "name is required")
    private String name;

    @Schema(description = "Short description of what the category covers.", example = "TMT bars, wire mesh and other reinforcement materials")
    @Size(max = 255, message = "description must be at most 255 characters")
    @NotBlank(message = "description is required")
    private String description;

    @Schema(description = "Icon identifier or URL shown alongside the category.", example = "rebar-icon")
    private String icon;

    @Schema(description = "Image URL shown alongside the category.", example = "https://cdn.echno.xyz/categories/reinforcement-steel.png")
    private String image;
}
