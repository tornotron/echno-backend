package org.tornotron.echno_backend.documentReversal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;

/**
 * Asks for a document to be reversed.
 *
 * <p>The requester is taken from the session, never from the body: the service refuses anyone
 * but the document's creator, and a body that could name the requester would name the creator.
 * The reason is required for the same reason a rejection's is: the record of why a posted
 * document was undone is what separates a reversal from a deletion.
 */
@Schema(description = "Payload to request the reversal of a site transfer, purchase order or goods received note.")
public record DocumentReversalRequestDto(
        @Schema(description = "The kind of document to reverse.", example = "GOODS_RECEIVED_NOTE")
        @NotNull ReversibleDocumentType documentType,

        @Schema(description = "Id of that document.", example = "18")
        @NotNull Long documentId,

        @Schema(description = "Why it should be reversed. Required.", example = "Delivery booked to the wrong project")
        @NotBlank @Size(max = 500) String reason
) {}
