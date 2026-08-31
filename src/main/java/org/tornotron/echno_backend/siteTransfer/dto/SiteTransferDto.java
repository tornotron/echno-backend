package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A site transfer of materials between two projects, with resolved names for the projects and storage locations involved.")
@Data
public class SiteTransferDto {

    @Schema(description = "Id of the site transfer.", example = "31")
    private Long id;

    @Schema(description = "Unique transfer document number.", example = "ST-2026-0031")
    private String transferNumber;

    @Schema(description = "Date and time the transfer was issued.", example = "2026-01-15T09:30:00")
    private LocalDateTime issueDate;

    @Schema(description = "Employee who handed over the materials at the sending project.")
    private EmployeeDto sendingPerson;

    @Schema(description = "Id of the sending project.", example = "4")
    private Long sendingProjectId;

    @Schema(description = "Name of the sending project.", example = "Asset Homes - Kochi Phase 2")
    private String sendingProjectName;

    @Schema(description = "Id of the sending storage location, if one was specified.", example = "7")
    private Long sendingStorageLocationId;

    @Schema(description = "Name of the sending storage location.", example = "Main Site Store")
    private String sendingStorageLocationName;

    @Schema(description = "Id of the receiving project.", example = "9")
    private Long receivingProjectId;

    @Schema(description = "Name of the receiving project.", example = "Asset Homes - Coimbatore Villas")
    private String receivingProjectName;

    @Schema(description = "Id of the receiving storage location, if one was specified.", example = "15")
    private Long receivingStorageLocationId;

    @Schema(description = "Name of the receiving storage location.", example = "Site B Yard")
    private String receivingStorageLocationName;

    @Schema(description = "Current status of the transfer. PENDING means it has left the sending "
            + "site and nothing has been recorded as arriving, so its quantity is in transit; "
            + "PARTIALLY_TRANSFERRED and COMPLETED follow from what the receiving site confirmed; "
            + "CANCELLED means it was abandoned in transit and its stock returned to the sender.",
            example = "PENDING")
    private SiteTransferStatus status;

    @Schema(description = "Line items listing each material and quantity being transferred.")
    private List<SiteTransferItemDto> items;
}
