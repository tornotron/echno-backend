package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload to define an organization's own inspection trade.")
public record CreateTradeRequest(
        @Schema(description = "Slug: lowercase letters, digits and hyphens. Unique within the organization.", example = "precast-erection")
        @NotBlank @Size(max = 50) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                message = "must be lowercase letters, digits and single hyphens") String code,
        @NotBlank @Size(max = 200) String name,
        @Schema(description = "Group heading. Any string; the seeded groups are structural, masonry, finishes, openings, mep, fire and general.", example = "structural")
        @NotBlank @Size(max = 50) String groupCode,
        String description,
        @Schema(description = "Position in pickers. Defaults to after every seeded trade.") Integer sortOrder
) {}
