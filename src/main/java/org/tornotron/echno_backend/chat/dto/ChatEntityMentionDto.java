package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * An entity reference woven into a message ({@code #[label](type:id)}), on the wire in the
 * shape the web {@code ChatEntityMention} type parses.
 */
@Schema(description = "A reference to a task, issue or project mentioned in a message.")
@Data
public class ChatEntityMentionDto {

    @Schema(description = "Kind of entity referenced.", example = "task")
    private String entityType;

    @Schema(description = "Id of the referenced entity.", example = "42")
    private Long entityId;

    @Schema(description = "Display label cached when the mention was written.", example = "Pour slab C")
    private String label;
}
