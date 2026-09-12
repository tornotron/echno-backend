package org.tornotron.echno_backend.modules.bim.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.dto.BimElementDto;
import org.tornotron.echno_backend.modules.bim.dto.MergeBimElementRequest;
import org.tornotron.echno_backend.modules.bim.mapper.BimMapper;
import org.tornotron.echno_backend.modules.bim.repository.BimElementRepository;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.UpdateSpatialNodeRequest;

/**
 * The manual repair for the known weak spot of GlobalId identity: an authoring tool that
 * regenerates a GlobalId makes the import retire the old row and insert a new one, and the
 * construction element stays on the retired row. Merging carries it across.
 */
@Service
@RequiredArgsConstructor
public class BimElementService {

    private final BimElementRepository elements;
    private final BimModelService modelService;
    private final SpatialNodeService spatialNodeService;
    private final BimMapper mapper;

    @Transactional
    public BimElementDto merge(UUID retiredElementId, MergeBimElementRequest req) {
        BimElement source = modelService.requireElement(retiredElementId);
        BimElement target = modelService.requireElement(req.intoElementId());
        if (source.getId().equals(target.getId())) {
            throw new InvalidRequestException("An element cannot be merged into itself");
        }
        if (!source.getModelId().equals(target.getModelId())) {
            throw new InvalidRequestException("Elements of different models cannot be merged");
        }
        if (!source.isRetired()) {
            throw new InvalidRequestException("Only a retired element can be merged; " + source.getGlobalId()
                    + " is still present in the latest version");
        }
        if (target.isRetired() || target.getMergedIntoId() != null) {
            throw new InvalidRequestException("The target element is retired or was itself merged away");
        }
        if (source.getSpatialNodeId() != null) {
            if (target.getSpatialNodeId() != null && !target.getSpatialNodeId().equals(source.getSpatialNodeId())) {
                throw new InvalidRequestException("Both elements carry a construction element; unlink one first");
            }
            UUID nodeId = source.getSpatialNodeId();
            source.setSpatialNodeId(null);
            elements.saveAndFlush(source);
            target.setSpatialNodeId(nodeId);
            spatialNodeService.update(target.getProjectId(), nodeId,
                    new UpdateSpatialNodeRequest(null, null, null, null, null, target.getGlobalId(), null));
        }
        source.setMergedIntoId(target.getId());
        elements.save(source);
        return mapper.toDto(elements.save(target));
    }
}
