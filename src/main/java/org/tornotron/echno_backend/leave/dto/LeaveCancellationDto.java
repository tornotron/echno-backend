package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload to cancel an approved leave request.
 *
 * <p>Replaces the {@code Map<String, String>} the endpoint read {@code reason} out of by hand. The
 * map published as {@code additionalProperties}, so the document named neither the one key the
 * endpoint reads nor the type it expects, and a caller who spelled it {@code cancellationReason}
 * got a 200 and a cancellation with no reason recorded.
 *
 * <p>The reason stays optional, which is what the endpoint has always accepted.
 */
@Schema(description = "Payload to cancel an approved leave request.")
@Data
public class LeaveCancellationDto {

    @Schema(description = "Why the leave is being cancelled, recorded against the request.",
            example = "Travel plans changed")
    @Size(max = 500, message = "Cancellation reason must not exceed 500 characters")
    private String reason;
}
