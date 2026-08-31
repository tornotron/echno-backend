package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "An employee's membership in a chat room.")
@Data
public class ChatParticipantDto {

    @Schema(description = "Id of the participating employee.", example = "9")
    private Long employeeId;

    @Schema(description = "Role of the participant in the room.", example = "member")
    private String role;

    @Schema(description = "When the employee joined the room.", example = "2026-08-20T09:00:00")
    private LocalDateTime joinedAt;

    @Schema(description = "How far the employee has read the room; null if never read.", example = "2026-08-22T14:20:00", nullable = true)
    private LocalDateTime lastReadAt;
}
