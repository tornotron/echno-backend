package org.tornotron.echno_backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.common.entity.AttachmentDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A chat message on the wire, with its author, content, edit and delete state, and the rich
 * fields the web renders: employee {@code mentions}, {@code entityMentions}, grouped emoji
 * {@code reactions}, file {@code attachments}, and the {@code replyTo} preview resolved from
 * {@code replyToId}.
 */
@Schema(description = "A chat message with its author, content, edit state and rich content.")
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

    @Schema(description = "Preview of the replied-to message; null when this is not a reply.")
    private ChatMessageReplyDto replyTo;

    @Schema(description = "Whether the message has been edited.", example = "false")
    @JsonProperty("isEdited")
    private boolean edited;

    @Schema(description = "When the message was edited; null if never.", example = "2026-08-22T14:20:00")
    private LocalDateTime editedAt;

    @Schema(description = "Whether the message has been deleted.", example = "false")
    @JsonProperty("isDeleted")
    private boolean deleted;

    @Schema(description = "Employee ids mentioned in the message body.")
    private List<Long> mentions = List.of();

    @Schema(description = "Task, issue or project references in the message body.")
    private List<ChatEntityMentionDto> entityMentions = List.of();

    @Schema(description = "Emoji reactions on the message, grouped by emoji.")
    private List<ChatReactionDto> reactions = List.of();

    @Schema(description = "File attachments on the message, each with a presigned download URL.")
    private List<AttachmentDto> attachments = List.of();

    @Schema(description = "When the message was created.", example = "2026-08-20T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "When the message was last updated.", example = "2026-08-22T14:20:00")
    private LocalDateTime updatedAt;
}
