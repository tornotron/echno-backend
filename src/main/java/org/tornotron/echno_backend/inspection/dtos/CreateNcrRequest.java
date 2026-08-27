package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.DefectSeverity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Raises a non-conformance report against an inspection. The NCR number, the type
 * and the raiser are all set server-side: the number from the {@code NCR} series,
 * the type from the originating inspection's category (so a safety inspection
 * cannot produce a quality NCR), and the raiser from the authenticated caller. The
 * status starts at {@code OPEN}, or {@code ASSIGNED} when a site engineer is named
 * here, which is the usual case.
 */
@Schema(description = "Payload to raise a non-conformance report against an inspection.")
public record CreateNcrRequest(
        @Schema(description = "Id of the inspection the non-conformance was found on.",
                example = "0f8b0a4c-9c2e-4f5b-b0a1-5b7e2d3c4a10")
        @NotNull UUID inspectionId,
        @Schema(description = "Id of the specific defect this NCR is about, when it is about one. "
                + "Omit to raise it against the inspection as a whole.",
                example = "3a1c5e77-2b44-4d90-9f11-8c6d0e2b7a55")
        UUID defectId,
        @Schema(description = "Short title of the non-conformance.",
                example = "Cover to slab reinforcement below specification")
        @NotBlank @Size(max = 200) String title,
        @Schema(description = "What does not conform, and against what requirement.",
                example = "Clear cover measured at 25 mm against a specified 40 mm at grid C3 to C6.")
        @NotBlank @Size(max = 2000) String description,
        @Schema(description = "Severity of the non-conformance.", example = "major")
        DefectSeverity severity,
        @Schema(description = "Id of the site engineer the corrective work is assigned to. Naming "
                + "one here moves the NCR straight to assigned.", example = "8")
        Long siteEngineerId,
        @Schema(description = "Date the corrective work is due.", example = "2026-09-10")
        LocalDate targetDate
) {}
