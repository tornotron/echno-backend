package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Who authorizes payments on a project's bills, and the changes to that.")
public record ProjectPaymentAuthorizerDto(
        Long projectId,
        @Schema(nullable = true)
        String projectName,
        @Schema(description = "Null when the project has none; its payments then wait.", nullable = true)
        Long employeeId,
        @Schema(nullable = true)
        String employeeName,
        @Schema(description = "False when the employee named is no longer active, which leaves the project without one.")
        boolean active,
        @Schema(nullable = true)
        String setByName,
        @Schema(nullable = true)
        LocalDateTime setAt,
        @Schema(description = "Whether the reader is this project's payment authorizer.")
        boolean readerIsAuthorizer,
        @Schema(description = "Whether the reader may name or change the authorizer: a system admin or a project manager.")
        boolean readerMayChange,
        @Schema(description = "Newest first, the latest 20.")
        List<PaymentAuthorizerChangeDto> changes
) {}
