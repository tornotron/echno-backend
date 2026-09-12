package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "A checklist item within an inspection, as returned by the API.")
public record InspectionCheckItemDto(
        UUID id,
        String category,
        String checkPoint,
        @Schema(description = "Reference specification for the check point. Null where none was "
                + "recorded.", nullable = true)
        String specification,
        CheckItemStatus status,
        @Schema(description = "Free-text remarks recorded against the check point. Null where none "
                + "were recorded.", nullable = true)
        String remarks,
        boolean photosRequired,
        List<String> photos,
        @Schema(description = "Measured value recorded on site. Null until a measurement is taken, "
                + "and on qualitative check points that carry none.", nullable = true)
        String measurement,
        @Schema(description = "Value expected per specification. Null on check points with no "
                + "numeric target.", nullable = true)
        String expectedValue,
        @Schema(description = "What makes the check point pass, copied from the checklist template "
                + "when the inspection was created. Null where the template carried none.",
                nullable = true)
        String acceptanceCriterion,
        @Schema(description = "Permitted band around the expected value, copied from the checklist "
                + "template. Null where the template carried none.", nullable = true)
        String tolerance,
        @Schema(description = "Measured value minus the expected one, computed server-side on "
                + "every save. Null wherever the two do not both parse as a number in the same "
                + "unit, which is the normal case for a qualitative check point.", nullable = true)
        BigDecimal deviation,
        @Schema(description = "IFC GlobalId of the BIM element the check point was carried out "
                + "against. Copied from the payload, which no client fills in until the BIM "
                + "viewer lands, so it is null on every row today.", nullable = true)
        String bimElementGuid,
        @Schema(description = "Priority of the check point. The service substitutes \"medium\" "
                + "wherever a payload omits it, but the column permits null, so a row loaded "
                + "outside the application can carry none.", nullable = true)
        String priority
) {}
