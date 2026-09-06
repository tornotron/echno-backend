package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class InviteCodeGenerationDto {

    /**
     * How many times the code may be redeemed. Defaults to single use, which is what an invite
     * addressed to one person should be.
     *
     * <p>The bounds are guardrails rather than policy. Below one the code is born unusable; far
     * above it the code stops being an invitation and becomes a standing key to the organization,
     * which is a different thing with a different risk and should not be reachable by leaving a
     * field unbounded. The one client that generates codes never sends this field at all.
     */
    @Min(value = 1, message = "maxUses must be at least 1")
    @Max(value = 1000, message = "maxUses must be at most 1000")
    int maxUses = 1;

    /**
     * How long the code stays redeemable, in days.
     *
     * <p>Bounded for the same reason: an unbounded value is how a credential ends up with no
     * expiry in practice, and a value large enough overflows the date arithmetic outright. A year
     * is already generous for an invitation. The console offers 7 to 90.
     */
    @Min(value = 1, message = "validityDays must be at least 1")
    @Max(value = 365, message = "validityDays must be at most 365")
    int validityDays = 5;

    private String employeeId;

    private String employeeName;

    private String email;

    private String phone;

    @NotBlank(message = "designation is required")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

    @NotBlank(message = "department is required")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    private Double salary;

    private Long managerId;

    private Long shiftTimingId;

    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

}
