package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Drafts a toolbox talk in the current tenant.")
public record CreateToolboxTalkRequest(
        @NotNull Long projectId,
        UUID spatialNodeId,
        @NotBlank @Size(max = 200) String topic,
        @NotNull LocalDate talkDate,
        LocalTime talkTime,
        @NotNull Long conductorEmployeeId,
        @Size(max = 500) List<Long> attendeeEmployeeIds,
        @Size(max = 4000) String notes
) {}
