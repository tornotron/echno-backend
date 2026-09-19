package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Schema(description = "Changes a drafted toolbox talk. A recorded talk no longer changes.")
public record UpdateToolboxTalkRequest(
        UUID spatialNodeId,
        @NotBlank @Size(max = 200) String topic,
        @NotNull LocalDate talkDate,
        LocalTime talkTime,
        @NotNull Long conductorEmployeeId,
        @Size(max = 4000) String notes
) {}
