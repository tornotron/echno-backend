package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Condensed view of a leave policy, used when embedding it inside a request or "
        + "balance record.")
@Data
public class LeavePolicySimpleDto {
    @Schema(description = "Id of the policy.", example = "3")
    private Long id;

    @Schema(description = "Short code identifying the leave type.", example = "CL")
    private String leaveTypeCode;

    @Schema(description = "Display name of the leave type.", example = "Casual Leave")
    private String leaveTypeName;

    @Schema(description = "Total days granted per year under this policy.", example = "12.0")
    private Double annualQuota;

    @Schema(nullable = true, description = "Whether requests under this policy can be for half a day. "
            + "Defaults to true when the create payload omits the field, and is null where the payload set "
            + "it to null, which the column allows.", example = "true")
    private Boolean allowHalfDay;

    @Schema(nullable = true, description = "Whether leave taken under this policy is paid. Defaults to true "
            + "on a create payload that omits the field. The column is nullable, so an explicit null is "
            + "stored and returned.", example = "true")
    private Boolean isPaid;
}
