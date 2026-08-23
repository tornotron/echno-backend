package org.tornotron.echno_backend.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Work category used to classify tasks, without resolved relations.")
@Data
public class CategorySimpleDto {
    @Schema(description = "Id of the category.", example = "7")
    private Long id;

    @Schema(description = "Name of the category.", example = "Reinforcement Steel")
    private String name;

    @Schema(description = "Short description of what the category covers.", example = "TMT bars, wire mesh and other reinforcement materials")
    private String description;

    @Schema(description = "Icon identifier or URL shown alongside the category.", example = "rebar-icon")
    private String icon;

    @Schema(description = "Image URL shown alongside the category.", example = "https://cdn.echno.xyz/categories/reinforcement-steel.png")
    private String image;
}
