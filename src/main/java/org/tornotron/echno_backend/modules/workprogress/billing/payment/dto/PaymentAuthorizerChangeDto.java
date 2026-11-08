package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One change to a project's payment authorizer. A null employee on either side means none.")
public record PaymentAuthorizerChangeDto(
        UUID id,
        @Schema(nullable = true)
        Long previousEmployeeId,
        @Schema(nullable = true)
        String previousEmployeeName,
        @Schema(nullable = true)
        Long employeeId,
        @Schema(nullable = true)
        String employeeName,
        @Schema(nullable = true)
        String changedByName,
        LocalDateTime changedAt
) {}
