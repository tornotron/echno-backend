package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillEventType;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillStatus;

@Schema(description = "One entry on a bill's timeline.")
public record BillEventDto(
        UUID id,
        BillEventType type,
        @Schema(nullable = true)
        BillStatus fromStatus,
        @Schema(nullable = true)
        BillStatus toStatus,
        @Schema(nullable = true)
        String note,
        @Schema(nullable = true)
        String actorName,
        LocalDateTime createdAt
) {}
