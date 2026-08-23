package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Payload to create a site transfer moving materials from a sending project to a receiving project.")
@Data
public class SiteTransferCreationDto {

    @Schema(description = "Unique transfer document number.", example = "ST-2026-0031")
    @NotBlank(message = "transfer number is required")
    @Size(min = 1, max = 50, message = "transfer number must be between 1 and 50 characters")
    private String transferNumber;

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
            + "from, for example Main Site Store. Optional; when omitted, stock is validated at the "
            + "project level instead of a single location.", example = "7")
    private Long sendingStorageLocationId;

    @Schema(description = "Id of the project the materials are being sent to.", example = "9")
    @NotNull(message = "receiving project ID is required")
    private Long receivingProjectId;

    @Schema(description = "Id of the specific storage location within the receiving project to credit the "
            + "materials to, for example Site B Yard. Optional.", example = "15")
    private Long receivingStorageLocationId;

    @Schema(description = "Initial status of the transfer.", example = "PENDING")
    @NotBlank(message = "status is required")
    private String status;

    @Schema(description = "Line items listing each material and quantity being transferred. Must contain "
            + "at least one item.")
    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<SiteTransferItemDto> items;
}
