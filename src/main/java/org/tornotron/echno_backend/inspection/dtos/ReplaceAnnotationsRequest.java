package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The complete set of marks for one inspection's defect photos.
 *
 * <p>A whole-set replace rather than per-mark create and delete, matching the way
 * the rest of the module is written: the web client already sends an inspection
 * back in full on every save, and a mark-up canvas has the same shape, one save of
 * everything currently drawn.
 *
 * <p>The size limit is on the request rather than on what the report will print.
 * It bounds what a single call can write, which the report caps are not able to do
 * afterwards.
 */
@Schema(description = "Every mark drawn across an inspection's defect photos, replacing what is stored.")
public record ReplaceAnnotationsRequest(
        @Schema(description = "The marks to store. Sending an empty list clears them.")
        @NotNull
        @Size(max = MAX_ANNOTATIONS,
                message = "An inspection may carry at most " + MAX_ANNOTATIONS + " photo annotations")
        List<@Valid DefectPhotoAnnotationRequest> annotations
) {
    /**
     * Most marks one inspection may carry.
     *
     * <p>Set well above marking up every photo of a heavily defective inspection
     * and well below the point where the write, or the report that reads it back,
     * costs real memory.
     */
    public static final int MAX_ANNOTATIONS = 400;
}
