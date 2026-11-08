package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a run request queued.")
public record QueueVisionRunResult(
        @Schema(description = "Jobs queued, one per image and model version.") int queued,
        @Schema(description = "Image and version pairs skipped because a job is pending, or already ran without "
                + "rerun.") int skippedDuplicate,
        @Schema(description = "Files skipped because the worker cannot read them (only JPEG, PNG and WebP) or "
                + "they sit on a record with no project.") int skippedNotImage
) {}
