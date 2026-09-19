package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "The employees to add to a drafted toolbox talk's attendance.")
public record ToolboxTalkAttendeesRequest(@NotEmpty @Size(max = 500) List<Long> employeeIds) {}
