package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "Payload to edit the body of an existing message.")
@Data
public class EditMessageDto {

    @Schema(description = "New message body.", example = "Concrete pour on block C is done, cured overnight.")
    @NotBlank
    private String content;
}
