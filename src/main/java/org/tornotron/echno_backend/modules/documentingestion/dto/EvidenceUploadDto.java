package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.PresignedUpload;

/** Where to PUT the evidence file, and the evidence to register once it is there. */
public record EvidenceUploadDto(UUID evidenceId, PresignedUpload upload) {
}
