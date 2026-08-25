package org.tornotron.echno_backend.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload to toggle an emoji reaction on a message: the reaction is added if the caller has
 * not reacted with this emoji, or removed if they already have.
 */
@Schema(description = "Payload to toggle an emoji reaction on a message.")
@Data
public class ReactionRequestDto {

    @Schema(description = "The emoji to toggle.", example = "👍")
    @NotBlank
    private String emoji;
}
