package org.tornotron.echno_backend.modules.documentingestion.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStage;
import org.tornotron.echno_backend.modules.documentingestion.domain.PackageStatus;

public record PackageDto(UUID id, String name, Long projectId, UUID subContractId, PackageStage stage,
                         PackageStatus status, long documentCount, LocalDateTime createdAt, Long createdBy) {

    public static PackageDto of(IngestPackage p, long documentCount) {
        return new PackageDto(p.getId(), p.getName(), p.getProjectId(), p.getSubContractId(), p.getStage(),
                p.getStatus(), documentCount, p.getCreatedAt(), p.getCreatedBy());
    }
}
