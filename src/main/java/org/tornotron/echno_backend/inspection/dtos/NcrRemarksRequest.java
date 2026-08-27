package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * The note that accompanies a step in the NCR lifecycle: what the site engineer
 * did, or why a verifier sent the work back or reopened the report. Shared by
 * those actions because the payload is the same in each case and the meaning of
 * the note is given by the endpoint it is sent to.
 */
@Schema(description = "A note recorded against a step in the NCR lifecycle.")
public record NcrRemarksRequest(
        @Schema(description = "What was done, or why the work was not accepted.",
                example = "Section chipped out and re-poured on 5 September; cover re-measured at 42 mm.")
        @Size(max = 2000) String remarks
) {}
