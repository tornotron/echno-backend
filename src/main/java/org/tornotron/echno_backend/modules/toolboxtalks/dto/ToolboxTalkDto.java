package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;

@Schema(description = "One toolbox talk of the current tenant, with the employees who attended.")
public record ToolboxTalkDto(
        UUID id,
        Long projectId,
        UUID spatialNodeId,
        String topic,
        LocalDate talkDate,
        LocalTime talkTime,
        Long conductorEmployeeId,
        List<ToolboxTalkAttendeeDto> attendees,
        String notes,
        ToolboxTalkStatus status,
        LocalDateTime recordedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
