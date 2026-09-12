package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "A finding recorded by the signed-in inspector. The creator's act is the "
        + "decision: the observation is created accepted, with the caller as reporter and reviewer.")
public record CreateObservationRequest(
        @NotNull Long projectId,
        @Schema(description = "Inspection to file it under, when there is one.", nullable = true)
        UUID inspectionId,
        @Schema(description = "Site structure node. Optional; locationNote is the free-text fallback.",
                nullable = true)
        UUID spatialNodeId,
        @Size(max = 300) String locationNote,
        @Schema(description = "When it was seen. Defaults to now.", nullable = true)
        LocalDateTime observedAt,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @Size(max = 200) String category,
        DefectSeverity suggestedSeverity,
        @Schema(description = "Ids of attachments already in the Echno store to cite as evidence; "
                + "each becomes an {attachmentId} entry in evidenceRefs.", nullable = true)
        List<Long> evidenceAttachmentIds
) {}
