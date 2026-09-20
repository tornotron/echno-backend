package org.tornotron.echno_backend.documentReversal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether one document can be reversed right now, and by the caller.
 *
 * <p>Read by the detail screens to decide whether to show the request control, so the answer
 * is worked out once on the server from the same checks the write path applies, rather than
 * re-implemented in the browser from the document's fields.
 */
@Schema(description = "Whether a document can currently be reversed, and whether the caller may ask for it.")
public record DocumentReversalEligibilityDto(
        @Schema(description = "True when nothing stands in the way of a reversal being requested.")
        boolean reversible,

        @Schema(description = "What stands in the way when reversible is false: the downstream document "
                + "or consumed stock, the document's state, or a pending request. Null when reversible.",
                nullable = true)
        String blocker,

        @Schema(description = "True when the caller is the document's creator and so may request its reversal.")
        boolean callerIsCreator,

        @Schema(description = "Id of the pending request on this document, if there is one.", nullable = true)
        Long pendingReversalId,

        @Schema(description = "Id of the approved reversal that undid this document, if it has been reversed.",
                nullable = true)
        Long reversalId
) {}
