package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Carries a retired element's construction-element link onto the element that replaced it "
        + "after an authoring tool regenerated the GlobalId.")
public record MergeBimElementRequest(
        @NotNull UUID intoElementId
) {}
