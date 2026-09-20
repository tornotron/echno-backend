package org.tornotron.echno_backend.documentReversal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.documentReversal.enums.DocumentReversalStatus;
import org.tornotron.echno_backend.documentReversal.enums.ReversibleDocumentType;

import java.time.LocalDateTime;

@Schema(description = "A request to reverse a site transfer, purchase order or goods received note, "
        + "with the decision taken on it.")
@Data
public class DocumentReversalDto {

    @Schema(description = "Id of the reversal request.", example = "12")
    private Long id;

    @Schema(description = "Id of the owning organization.", example = "1")
    private Long organizationId;

    @Schema(description = "The kind of document the request names.", example = "SITE_TRANSFER")
    private ReversibleDocumentType documentType;

    @Schema(description = "Id of that document, within its own kind.", example = "31")
    private Long documentId;

    @Schema(description = "The document's number as it was when the request was raised.",
            example = "ST-2026-0031")
    private String documentNumber;

    @Schema(description = "Where the request stands.", example = "PENDING")
    private DocumentReversalStatus status;

    @Schema(description = "Why the reversal was asked for.", example = "Issued against the wrong store")
    private String reason;

    @Schema(description = "Id of the user who asked for it, always the document's creator.", example = "7")
    private Long requestedBy;

    @Schema(description = "Display name of the requester.", example = "Ravi Kumar")
    private String requestedByName;

    @Schema(description = "When the request was raised.", example = "2026-09-20T10:15:00")
    private LocalDateTime requestedAt;

    @Schema(description = "Id of the user who approved, rejected or cancelled it. Null while pending.",
            example = "3", nullable = true)
    private Long decidedBy;

    @Schema(description = "Display name of the decider. Null while pending.", example = "Anand R",
            nullable = true)
    private String decidedByName;

    @Schema(description = "When the decision was taken. Null while pending.",
            example = "2026-09-20T11:00:00", nullable = true)
    private LocalDateTime decidedAt;

    @Schema(description = "The approver's reason on a rejection, or a note on the other decisions. "
            + "Null while pending.", example = "The transfer was correct; the count was wrong",
            nullable = true)
    private String decisionNote;

    @Schema(description = "Reference number the correcting ledger entries carry. Null until approved, "
            + "and null on a purchase order reversal, which moves no stock.",
            example = "REV-ST-2026-0031", nullable = true)
    private String reversalReference;

    @Schema(description = "When the request row was created.", example = "2026-09-20T10:15:00")
    private LocalDateTime createdAt;

    @Schema(description = "When the request row was last written.", example = "2026-09-20T11:00:00")
    private LocalDateTime updatedAt;
}
