package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.common.dto.RegisterUploadRequest;

@Schema(description = "Confirms the files of one presign call have reached storage.")
public record IntakeUploadRegisterRequest(
        @NotNull UUID uploadId,
        @NotEmpty @Size(max = 20) List<RegisterUploadRequest> uploads
) {}
