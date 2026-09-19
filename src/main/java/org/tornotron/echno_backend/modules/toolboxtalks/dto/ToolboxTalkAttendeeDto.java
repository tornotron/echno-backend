package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An employee who attended a toolbox talk.")
public record ToolboxTalkAttendeeDto(Long employeeId) {}
