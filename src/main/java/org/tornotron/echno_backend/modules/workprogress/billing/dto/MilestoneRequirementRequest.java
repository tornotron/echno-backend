package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementStatus;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.RequirementType;

@Schema(description = "One thing a contract milestone needs before it can be certified for payment.")
public record MilestoneRequirementRequest(
        @Schema(example = "Concrete strength tests")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "SCOPE, QUALITY_TEST, QA_QC, DOCUMENT or INSPECTION.", example = "QUALITY_TEST")
        @NotNull RequirementType type,
        @Schema(nullable = true)
        @Size(max = 4000) String description,
        @Schema(description = "Whether certification waits for it. Defaults to true.", nullable = true)
        Boolean mandatory,
        @Schema(nullable = true)
        LocalDate dueDate,
        @Schema(description = "PENDING, UNDER_REVIEW, COMPLETED or NOT_APPLICABLE. Defaults to PENDING.", nullable = true)
        RequirementStatus status,
        @Schema(nullable = true)
        @Size(max = 4000) String remarks,
        @Schema(nullable = true)
        Integer sortOrder
) {}
