package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.tornotron.echno_backend.common.dto.PresignedUpload;

@Schema(description = "The version created for an upload and the short-lived PUT URL for its source IFC.")
public record BimSourceUploadDto(
        UUID versionId,
        int versionNumber,
        PresignedUpload upload
) {}
