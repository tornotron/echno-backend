package org.tornotron.echno_backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A chat message on the wire. The rich fields {@code mentions}, {@code entityMentions},
 * {@code reactions} and {@code attachments} are always emitted as empty arrays: they are
 * deferred features, present only so the web parsers have the shape they expect.
 */
@Schema(description = "A chat message with its author, content and edit state.")
@Data
public class ChatMessageDto {

    @Schema(description = "Message id.", example = "1201")
    private Long id;

    @Schema(description = "Id of the room the message belongs to.", example = "17")
    private Long roomId;

    @Schema(description = "Employee id of the message author.", example = "9")
    private Long senderId;

    @Schema(description = "Message body.", example = "Concrete pour on block C is done.")
    private String content;

    @Schema(description = "Id of the message this one replies to, if any.", example = "1198")
    private Long replyToId;

    @Schema(description = "Whether the message has been edited.", example = "false")
    @JsonProperty("isEdited")
    private boolean edited;

    @Schema(description = "When the message was edited; null if never.", example = "2026-08-22T14:20:00")
    private LocalDateTime editedAt;

    @Schema(description = "Whether the message has been deleted.", example = "false")
    @JsonProperty("isDeleted")
    private boolean deleted;

    @Schema(description = "Employee ids mentioned in the message. Deferred: always empty.")
    private List<Long> mentions = List.of();

    @Schema(description = "Entity mentions in the message. Deferred: always empty.")
    private List<Object> entityMentions = List.of();

    @Schema(description = "Emoji reactions on the message. Deferred: always empty.")
    private List<Object> reactions = List.of();

    @Schema(description = "File attachments on the message. Deferred: always empty.")
    private List<Object> attachments = List.of();

    @Schema(description = "When the message was created.", example = "2026-08-20T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "When the message was last updated.", example = "2026-08-22T14:20:00")
    private LocalDateTime updatedAt;
}
