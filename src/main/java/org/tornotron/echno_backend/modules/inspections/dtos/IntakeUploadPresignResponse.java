package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.common.dto.PresignedUpload;

@Schema(description = "Where to PUT each file, and the uploadId to send back when registering them.")
public record IntakeUploadPresignResponse(
        UUID uploadId,
        List<PresignedUpload> uploads
) {}
