package org.tornotron.echno_backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A chat room on the wire, carrying its participants, the latest non-deleted message as
 * {@code lastMessage}, and the caller's {@code unreadCount}.
 */
@Schema(description = "A chat room with its participants, last message and unread count for the caller.")
@Data
public class ChatRoomDto {

    @Schema(description = "Room id.", example = "17")
    private Long id;

    @Schema(description = "Kind of room.", example = "direct")
    private String type;

    @Schema(description = "Room name; null for a direct room (the client derives it).", example = "Block C site team")
    private String name;

    @Schema(description = "Room description.", example = "Coordination for the block C interior work")
    private String description;

    @Schema(description = "Id of the project this room belongs to, for group rooms.", example = "3")
    private Long projectId;

    @Schema(description = "Participants of the room.")
    private List<ChatParticipantDto> participants = List.of();

    @Schema(description = "The latest non-deleted message in the room; null when the room is empty.")
    private ChatMessageDto lastMessage;

    @Schema(description = "Number of messages the caller has not yet read.", example = "3")
    private int unreadCount;

    @Schema(description = "Whether the room is archived.", example = "false")
    @JsonProperty("isArchived")
    private boolean archived;

    @Schema(description = "When the room was created.", example = "2026-08-20T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "When the room was last updated.", example = "2026-08-22T14:20:00")
    private LocalDateTime updatedAt;
}
