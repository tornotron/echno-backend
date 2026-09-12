package org.tornotron.echno_backend.modules.bim.service;

import java.util.List;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.BimImportJobDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelDto;
import org.tornotron.echno_backend.modules.bim.dto.BimModelVersionDto;

final class BimMapper {

    private BimMapper() {
    }

    static BimModelDto toDto(BimModel m, List<BimModelVersionDto> versions) {
        return new BimModelDto(m.getId(), m.getProjectId(), m.getName(), m.getDescription(),
                m.getCurrentVersionId(), versions, m.getCreatedAt(), m.getUpdatedAt());
    }

    static BimModelVersionDto toDto(BimModelVersion v) {
        return new BimModelVersionDto(v.getId(), v.getModelId(), v.getVersionNumber(), v.getStatus(),
                v.getSourceFilename(), v.getSourceSizeBytes(), v.getIfcSchema(), v.getElementCount(),
                v.getStoreyCount(), v.getMeta(), v.getHierarchyProposal() != null,
                v.getHierarchyConfirmedAt(), v.getError(), v.getImportedAt(), v.getCreatedAt());
    }

    static BimElementDto toDto(BimElement e) {
        return new BimElementDto(e.getId(), e.getModelId(), e.getGlobalId(), e.getIfcType(), e.getName(),
                e.getStoreyGlobalId(), e.getSpaceGlobalId(), e.getBbox(), e.getProperties(),
                e.getSpatialNodeId(), e.getFirstSeenVersionId(), e.getLastSeenVersionId(), e.isRetired(),
                e.getMergedIntoId());
    }

    static BimImportJobDto toDto(BimImportJob j) {
        return new BimImportJobDto(j.getId(), j.getModelId(), j.getVersionId(), j.getStatus(), j.getAttempt(),
                j.getMaxAttempts(), j.getWorkerId(), j.getWorkerVersion(), j.getElementCount(),
                j.getStoreyCount(), j.getError(), j.getQueuedAt(), j.getStartedAt(), j.getFinishedAt(),
                j.getIngestedAt());
    }
}
