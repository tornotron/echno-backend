package org.tornotron.echno_backend.modules.workprogress.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Names the employee who authorizes payments on a project's bills.")
public record SetPaymentAuthorizerRequest(
        @Schema(description = "An active employee of the organization with a sign-in account.")
        @NotNull @Positive Long employeeId
) {}
