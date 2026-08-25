package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "Payload to post a message to a room.")
@Data
public class SendMessageDto {

    @Schema(description = "Message body.", example = "Concrete pour on block C is done.")
    @NotBlank
    private String content;

    @Schema(description = "Id of the message this one replies to, if any.", example = "1198")
    private Long replyToId;
}
