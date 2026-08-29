package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to create a site transfer moving materials from a sending project "
        + "to a receiving project. The transfer number is allocated by the server and returned on "
        + "the created transfer; it is not part of this payload. The two sides must differ: a "
        + "transfer naming the same project and the same storage location on both sides moves "
        + "nothing and is refused, while two storage locations inside one project is a real move "
        + "and is accepted.")
@Data
public class SiteTransferCreationDto {

    @Schema(description = "Date and time the transfer was issued.", example = "2026-01-15T09:30:00")
    @NotNull(message = "issue date is required")
    private LocalDateTime issueDate;

    @Schema(description = "Id of the employee handing over the materials at the sending project.", example = "12")
    @NotNull(message = "sending person employee id is required")
    private Long sendingPerson;

    @Schema(description = "Id of the project the materials are being sent from.", example = "4")
    @NotNull(message = "sending project ID is required")
    private Long sendingProjectId;

    @Schema(description = "Id of the specific storage location within the sending project to draw stock "
            + "from, for example Main Site Store. Must either belong to the sending project or belong to "
            + "no project, which makes it an organisation-level store available from every project. "
            + "Optional; when omitted, stock is validated at the project level instead of a single "
            + "location.", example = "7")
    private Long sendingStorageLocationId;

    @Schema(description = "Id of the project the materials are being sent to.", example = "9")
    @NotNull(message = "receiving project ID is required")
    private Long receivingProjectId;

    @Schema(description = "Id of the specific storage location within the receiving project to credit the "
            + "materials to, for example Site B Yard. Must either belong to the receiving project or belong "
            + "to no project. When the receiving project is also the sending project this must name a "
            + "different location from the sending one, since a transfer that ends where it started moves "
            + "nothing. Optional.", example = "15")
    private Long receivingStorageLocationId;

    /**
     * The state the transfer starts in. Optional, and {@code PENDING} is the only value
     * accepted: the other two say the far end has received the materials, and that is recorded
     * through the transfer's own status endpoint. See {@code SiteTransferService.createSiteTransfer}.
     */
    @Schema(description = "State the transfer starts in. Optional, and PENDING is the only value "
            + "accepted, so leaving it out is the same as sending PENDING. "
            + "PARTIALLY_TRANSFERRED and COMPLETED say the receiving site has taken delivery, "
            + "which is recorded through PATCH /{id}/status; that endpoint wants a different "
            + "authority from create, and a transfer issued as COMPLETED would stand as received "
            + "with nobody having confirmed receipt.",
            example = "PENDING", allowableValues = {"PENDING"})
    private SiteTransferStatus status;

    @Schema(description = "Line items listing each material and quantity being transferred. Must contain "
            + "at least one item.")
    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<SiteTransferItemDto> items;
}
