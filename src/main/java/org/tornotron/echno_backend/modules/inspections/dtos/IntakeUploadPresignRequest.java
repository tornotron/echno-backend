package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.tornotron.echno_backend.common.dto.UploadRequest;

@Schema(description = "The images a producer is about to upload for one project. JPEG, PNG or WebP, up to 25 MB each.")
public record IntakeUploadPresignRequest(
        @NotNull Long projectId,
        @NotEmpty @Size(max = 20) List<UploadRequest> uploads
) {}
