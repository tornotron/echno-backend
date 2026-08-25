package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "Payload to open (or reuse) a one-to-one direct room with another employee.")
@Data
public class CreateDirectRoomDto {

    @Schema(description = "Employee id of the other party in the direct conversation.", example = "9")
    @NotNull
    private Long employeeId;
}
