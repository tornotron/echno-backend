package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * Emoji reactions on a message, grouped by emoji: one entry per distinct emoji carrying the
 * total {@code count} and the {@code employeeIds} of everyone who reacted with it. This is
 * the shape the web {@code ChatReaction} type parses.
 */
@Schema(description = "Reactions of one emoji on a message, with who reacted.")
@Data
public class ChatReactionDto {

    @Schema(description = "The emoji reacted with.", example = "👍")
    private String emoji;

    @Schema(description = "How many employees reacted with this emoji.", example = "2")
    private int count;

    @Schema(description = "Employee ids of everyone who reacted with this emoji.")
    private List<Long> employeeIds = List.of();
}
