package org.tornotron.echno_backend.modules.bim.hierarchy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.bim.BimStorageLayout;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;
import org.tornotron.echno_backend.modules.bim.domain.BimElement;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyConfirmResult;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedBuilding;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedElement;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedFloor;
import org.tornotron.echno_backend.modules.bim.dto.BimHierarchyProposalDto.ProposedZone;
import org.tornotron.echno_backend.modules.bim.dto.ConfirmBimHierarchyRequest;
import org.tornotron.echno_backend.modules.bim.importer.BimArtifactReader;
import org.tornotron.echno_backend.modules.bim.repository.BimElementRepository;
import org.tornotron.echno_backend.modules.bim.service.BimModelService;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;
import org.tornotron.echno_backend.project.spatial.SpatialNode;
import org.tornotron.echno_backend.project.spatial.SpatialNodeConflictException;
import org.tornotron.echno_backend.project.spatial.SpatialNodeService;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialNodeDto;

/**
 * The hierarchy proposal: built inside the ingestion after the elements land, stored on the
 * version, and turned into spatial nodes only when a user confirms it. Every node is created
 * or matched through {@link SpatialNodeService}, keyed by the IFC GlobalId in
 * {@code bimElementGuid}, so confirming twice matches everything the first run created and a
 * tree a site team built by hand is matched rather than duplicated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BimHierarchyService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final BimModelService modelService;
    private final BimElementRepository elements;
    private final SpatialNodeService spatial;
    private final BimArtifactReader artifacts;
    private final ObjectMapper objectMapper;

    /** Builds and stores the proposal; called inside the ingestion transaction by the listener. */
    public void propose(BimModel model, BimModelVersion version, Map<String, Object> structure) {
        Map<String, UUID> index = spatial.bimElementGuidIndex(model.getProjectId());
        BimHierarchyProposalDto proposal = BimHierarchyProposalBuilder.build(version.getId(), structure,
                elements.findByModelId(model.getId()), index::get);
        version.setHierarchyProposal(objectMapper.convertValue(proposal, MAP));
        version.setHierarchyConfirmedAt(null);
    }

    @Transactional(readOnly = true)
    public BimHierarchyProposalDto get(UUID modelId, UUID versionId) {
        modelService.requireModel(modelId);
        BimModelVersion version = modelService.requireVersion(modelId, versionId);
        return requireProposal(version);
    }

    /** Rebuilds the proposal from the stored structure, picking up nodes created since. */
    @Transactional
    public BimHierarchyProposalDto regenerate(UUID modelId, UUID versionId) {
        BimModel model = modelService.requireModel(modelId);
        BimModelVersion version = modelService.requireVersion(modelId, versionId);
        if (version.getStatus() != BimVersionStatus.READY) {
            throw new InvalidRequestException("A proposal can only be built for a READY version; this one is "
                    + version.getStatus());
        }
        if (!versionId.equals(model.getCurrentVersionId())) {
            // The element table holds the latest import; an older version's membership is not kept.
            throw new InvalidRequestException("Only the current version's proposal can be rebuilt; version "
                    + version.getVersionNumber() + " has been superseded");
        }
        Map<String, Object> structure;
        try (InputStream in = artifacts.open(BimStorageLayout.structureKey(modelId, versionId))) {
            structure = objectMapper.readValue(in, MAP);
        } catch (IOException e) {
            throw new InvalidRequestException("structure.json could not be read for this version: " + e.getMessage());
        }
        propose(model, version, structure);
        return requireProposal(version);
    }

    @Transactional
    public BimHierarchyProposalDto confirm(UUID modelId, UUID versionId, ConfirmBimHierarchyRequest req) {
        BimModel model = modelService.requireModel(modelId);
        BimModelVersion version = modelService.requireVersion(modelId, versionId);
        BimHierarchyProposalDto proposal = requireProposal(version);
        Long projectId = model.getProjectId();
        Set<String> wanted = req == null || req.elementGlobalIds() == null || req.elementGlobalIds().isEmpty()
                ? null : new HashSet<>(req.elementGlobalIds());
        boolean withElements = req == null || req.elementsWanted();
        int[] counts = new int[4];

        for (ProposedBuilding building : proposal.buildings()) {
            UUID buildingId = nodeFor(projectId, null, SpatialLevel.BUILDING, building.globalId(), building.code(),
                    building.name(), null, null, counts);
            for (ProposedFloor floor : building.floors()) {
                UUID floorId = nodeFor(projectId, buildingId, SpatialLevel.FLOOR, floor.globalId(), floor.code(),
                        floor.name(), floor.levelIndex(), null, counts);
                for (ProposedZone zone : floor.zones()) {
                    UUID zoneId;
                    if (zone.defaultZone()) {
                        // A floor whose only zone is the default one still needs it for the chain to
                        // reach ZONE; a floor with real spaces gets its default zone only for elements.
                        if (zone.elements().isEmpty() && floor.zones().size() > 1) {
                            continue;
                        }
                        SpatialNode defaultZone = spatial.ensureDefaultZone(projectId, floorId);
                        zoneId = defaultZone.getId();
                        counts[1]++;
                    } else {
                        zoneId = nodeFor(projectId, floorId, SpatialLevel.ZONE, zone.globalId(), zone.code(),
                                zone.name(), null, null, counts);
                    }
                    if (!withElements) {
                        continue;
                    }
                    for (ProposedElement element : zone.elements()) {
                        if (wanted != null && !wanted.contains(element.globalId())) {
                            counts[3]++;
                            continue;
                        }
                        UUID nodeId = nodeFor(projectId, zoneId, SpatialLevel.ELEMENT, element.globalId(),
                                element.code(), element.name() == null ? element.globalId() : element.name(),
                                null, element.elementType(), counts);
                        elements.findByModelIdAndGlobalId(modelId, element.globalId()).ifPresent(row -> {
                            if (!nodeId.equals(row.getSpatialNodeId())) {
                                row.setSpatialNodeId(nodeId);
                                elements.save(row);
                                counts[2]++;
                            }
                        });
                    }
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        BimHierarchyConfirmResult result = new BimHierarchyConfirmResult(now, counts[0], counts[1], counts[2], counts[3]);
        // Keep the proposal as it was shown, stamp the confirmation on it and refresh the matches.
        BimHierarchyProposalDto stamped = new BimHierarchyProposalDto(proposal.versionId(), proposal.generatedAt(),
                now, rematch(projectId, proposal.buildings()), proposal.counts(), result);
        version.setHierarchyProposal(objectMapper.convertValue(stamped, MAP));
        version.setHierarchyConfirmedAt(now);
        log.info("BIM hierarchy for version {} confirmed: {} nodes created, {} matched, {} elements linked",
                versionId, counts[0], counts[1], counts[2]);
        return stamped;
    }

    // ------------------------------------------------------------------------------------

    private UUID nodeFor(Long projectId, UUID parentId, SpatialLevel level, String guid, String code, String name,
                         Integer levelIndex, String elementType, int[] counts) {
        if (guid != null) {
            SpatialNodeDto existing = spatial.findByBimElementGuid(projectId, guid).orElse(null);
            if (existing != null) {
                if (existing.archivedAt() != null) {
                    // The guid is the identity; an archived node with it is brought back, not duplicated.
                    spatial.restore(projectId, existing.id());
                }
                counts[1]++;
                return existing.id();
            }
        }
        SpatialNodeDto created = create(projectId, parentId, level, guid, code, name, levelIndex, elementType);
        counts[0]++;
        return created.id();
    }

    private SpatialNodeDto create(Long projectId, UUID parentId, SpatialLevel level, String guid, String code,
                                  String name, Integer levelIndex, String elementType) {
        try {
            return spatial.create(projectId, new CreateSpatialNodeRequest(parentId, level, code, name, null,
                    levelIndex, elementType, guid, null));
        } catch (SpatialNodeConflictException clash) {
            // A sibling of that code already exists from another source: keep the guid, vary the code.
            String suffix = "-" + (guid == null ? UUID.randomUUID().toString().substring(0, 6)
                    : guid.substring(Math.max(0, guid.length() - 6)));
            String varied = code.substring(0, Math.min(code.length(), BimHierarchyProposalBuilder.CODE_MAX - suffix.length())) + suffix;
            return spatial.create(projectId, new CreateSpatialNodeRequest(parentId, level, varied, name, null,
                    levelIndex, elementType, guid, null));
        } catch (InvalidRequestException unknownType) {
            if (elementType == null) {
                throw unknownType;
            }
            // The organisation has no active element type for this slug: create the node untyped.
            return create(projectId, parentId, level, guid, code, name, levelIndex, null);
        }
    }

    private List<ProposedBuilding> rematch(Long projectId, List<ProposedBuilding> buildings) {
        Map<String, UUID> index = spatial.bimElementGuidIndex(projectId);
        return buildings.stream().map(b -> new ProposedBuilding(b.globalId(), b.name(), b.code(),
                lookup(index, b.globalId()),
                b.floors().stream().map(f -> new ProposedFloor(f.globalId(), f.name(), f.code(), f.levelIndex(),
                        f.elevation(), lookup(index, f.globalId()),
                        f.zones().stream().map(z -> new ProposedZone(z.globalId(), z.name(), z.code(), z.defaultZone(),
                                lookup(index, z.globalId()),
                                z.elements().stream().map(e -> new ProposedElement(e.globalId(), e.ifcType(), e.name(),
                                        e.code(), e.elementType(), lookup(index, e.globalId()))).toList())).toList())).toList())).toList();
    }

    private static UUID lookup(Map<String, UUID> index, String guid) {
        return guid == null ? null : index.get(guid);
    }

    private BimHierarchyProposalDto requireProposal(BimModelVersion version) {
        if (version.getHierarchyProposal() == null) {
            throw new ResourceNotFoundException("No hierarchy proposal for version " + version.getId()
                    + "; it is built when the import finishes");
        }
        return objectMapper.convertValue(version.getHierarchyProposal(), BimHierarchyProposalDto.class);
    }
}
