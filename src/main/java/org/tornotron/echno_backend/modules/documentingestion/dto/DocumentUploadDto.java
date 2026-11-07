package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.util.UUID;
import org.tornotron.echno_backend.common.dto.PresignedUpload;

/** Where to PUT the file, and the document to register once it is there. */
public record DocumentUploadDto(UUID documentId, PresignedUpload upload) {
}
