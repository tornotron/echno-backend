package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * A compact preview of the message a reply points at, resolved from {@code replyToId} so the
 * web can render the quoted line without a second fetch. The author is carried only as
 * {@code senderId}; the client resolves the employee. The content is trimmed to a snippet.
 */
@Schema(description = "Preview of the message a reply quotes.")
@Data
public class ChatMessageReplyDto {

    @Schema(description = "Id of the quoted message.", example = "1198")
    private Long id;

    @Schema(description = "Employee id of the quoted message's author.", example = "9")
    private Long senderId;

    @Schema(description = "A trimmed snippet of the quoted message body.", example = "Concrete pour on block C is done.")
    private String content;
}
