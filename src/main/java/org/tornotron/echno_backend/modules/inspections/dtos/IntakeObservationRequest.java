package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.ObservationSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A finding posted by a machine producer: a fleet's drone or robot, a fixed camera, "
        + "or a model. Lands in the pending queue for a person to review. externalRef is the producer's "
        + "own id for the finding and makes the call idempotent per organisation.")
public record IntakeObservationRequest(
        @NotNull Long projectId,
        @NotBlank @Size(max = 200) String externalRef,
        @Schema(description = "AI, DRONE, ROBOT or FIXED_CAMERA. HUMAN is refused: people record "
                + "through the web endpoint.")
        @NotNull ObservationSource source,
        @Schema(description = "The device or integration that produced it, named by the producer. The "
                + "calling service account identifies the fleet; this identifies the unit.")
        @NotBlank @Size(max = 100) String sourceDeviceId,
        @Size(max = 100) String missionRef,
        @Schema(description = "Pointer into the robot data layer's capture record.", nullable = true)
        @Size(max = 200) String captureRef,
        @Schema(description = "Site structure node, when the tree has one for the place. Otherwise "
                + "locationNote.", nullable = true)
        UUID spatialNodeId,
        @Size(max = 300) String locationNote,
        @NotNull LocalDateTime observedAt,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @Size(max = 200) String category,
        DefectSeverity suggestedSeverity,
        @Size(max = 100) String modelName,
        @Size(max = 50) String modelVersion,
        @Schema(description = "Model confidence in [0, 1].", nullable = true)
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
        @Schema(description = "Pointers to captures outside the Echno store (frame index, point-cloud id, "
                + "crop bounds), in the producer's own shape.", nullable = true)
        List<Map<String, Object>> evidenceRefs
) {}
