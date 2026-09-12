package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload to verify the corrective action on an NCR.")
public record VerifyNcrRequest(
        @Schema(description = "What was seen on re-inspection.",
                example = "Section chipped out and re-poured; cover re-measured at 42 mm.")
        @Size(max = 2000) String remarks,
        @Schema(description = "The passed reinspection this verification rests on. Optional this "
                + "release; when given it must belong to this NCR and have passed, and the "
                + "verifier and time are taken from its outcome.")
        UUID reinspectionId
) {}
