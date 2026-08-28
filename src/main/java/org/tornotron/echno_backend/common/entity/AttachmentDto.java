package org.tornotron.echno_backend.common.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "A file attached to an entity, with a short-lived download URL and, where "
        + "the file is a document rather than a photo, what kind of document it is and when it "
        + "stops being valid.")
@Data
public class AttachmentDto {

    @Schema(description = "Database id of the attachment.", example = "88")
    private Long id;

    @Schema(description = "Time-limited download URL for the stored file.")
    private String url;

    @Schema(description = "The entity type the file is filed under.", example = "ASSET_DOCUMENTS")
    private String entityType;

    @Schema(description = "MIME type of the file.", example = "application/pdf")
    private String contentType;

    @Schema(description = "Size of the file in bytes.", example = "284410")
    private Long fileSize;

    @Schema(description = "Original filename as uploaded.", example = "insurance-policy-2026.pdf")
    private String fileName;

    @Schema(description = "When the attachment record was created.", example = "2026-08-20T09:00:00")
    private String createdAt;

    @Schema(description = "When the attachment record was last updated.", example = "2026-08-21T11:42:03")
    private String updatedAt;

    @Schema(description = "What kind of document this is, where it is a document.", example = "insurance")
    private String documentType;

    @Schema(description = "Date the document was issued.", example = "2026-06-10")
    private LocalDate issuedOn;

    @Schema(description = "Date the document stops being valid. Null for a file that does not expire.",
            example = "2027-06-10")
    private LocalDate expiresOn;

    @Schema(description = "Whether the document has already expired. Null where it carries no expiry.",
            example = "false")
    private Boolean expired;

    @Schema(description = "Whole days until the document expires, negative once it has. Null where "
            + "it carries no expiry.", example = "285")
    private Long daysUntilExpiry;
}
