package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Payload to archive or unarchive a room for the whole conversation.
 */
@Schema(description = "Payload to set a room's archived state.")
@Data
public class ArchiveRoomRequestDto {

    @Schema(description = "Whether the room should be archived.", example = "true")
    @NotNull
    private Boolean archived;
}
