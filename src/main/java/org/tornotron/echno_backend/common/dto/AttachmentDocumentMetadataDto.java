package org.tornotron.echno_backend.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "What kind of document an already-uploaded file is, and when it stops being "
        + "valid. Applied after the upload, so it works the same for the direct multipart path and "
        + "for the presign/register path, and for any entity that files documents rather than only "
        + "for assets.")
@Data
public class AttachmentDocumentMetadataDto {

    @Schema(description = "What kind of document this is: insurance, warranty, registration, "
            + "certification, service-record, purchase-invoice. Send null to clear it.",
            example = "insurance")
    @Size(max = 50)
    private String documentType;

    @Schema(description = "Date the document was issued. Send null to clear it.", example = "2026-06-10")
    private LocalDate issuedOn;

    @Schema(description = "Date the document stops being valid. Send null for a document that does "
            + "not expire.", example = "2027-06-10")
    private LocalDate expiresOn;

    /**
     * A document cannot stop being valid before it was issued. Checked here rather than in the
     * service because it is a property of the payload, and a document period the wrong way round
     * is a typo in the form rather than a state the system should ever hold.
     *
     * @return Whether the two dates are in a possible order, or either is absent.
     */
    @AssertTrue(message = "expiresOn must not be before issuedOn")
    @Schema(hidden = true)
    public boolean isDocumentPeriodInOrder() {
        return issuedOn == null || expiresOn == null || !expiresOn.isBefore(issuedOn);
    }
}
