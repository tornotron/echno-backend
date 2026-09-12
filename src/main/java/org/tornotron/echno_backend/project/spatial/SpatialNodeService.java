package org.tornotron.echno_backend.project.spatial;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.tornotron.echno_backend.modules.inspections.api.ElementTypeValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.spatial.dto.CreateSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportResult;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportRow;
import org.tornotron.echno_backend.project.spatial.dto.SpatialNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import org.tornotron.echno_backend.project.spatial.dto.SpatialTreeNodeDto;
import org.tornotron.echno_backend.project.spatial.dto.UpdateSpatialNodeRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the level rules, the materialised path and the archive lifecycle of a project's site
 * structure. Every read goes through the org-filtered repository, so a node of another
 * tenant, like a node of another project, reads as absent (404) rather than forbidden.
 *
 * <p>Errors: 409 on a sibling code clash, a parent of the wrong level or a move into a
 * node's own subtree; 404 on a node outside the project; 422 on a write to an archived node.
 */
@Service
@RequiredArgsConstructor
public class SpatialNodeService {

    private final SpatialNodeRepository repository;
    private final ProjectRepository projectRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final ObjectProvider<ElementTypeValidator> elementTypeValidator;

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<SpatialTreeNodeDto> getTree(Long projectId, boolean includeArchived) {
        requireProject(projectId);
        List<SpatialNode> nodes = includeArchived
                ? repository.findByProjectIdOrderByDepthAscSortOrderAscCodeAsc(projectId)
                : repository.findByProjectIdAndArchivedAtIsNullOrderByDepthAscSortOrderAscCodeAsc(projectId);
        return buildTree(nodes);
    }

    @Transactional(readOnly = true)
    public SpatialNodeDto getNode(Long projectId, UUID nodeId) {
        requireProject(projectId);
        return toDto(requireNode(projectId, nodeId));
    }

    /**
     * The node a consumer may write a reference to: it must belong to the project (and, by
     * the org filter, the tenant) and must not be archived. A reference already stored to a
     * since-archived node is fine on read; only new writes are refused.
     */
    @Transactional(readOnly = true)
    public SpatialNode requireUsableNode(Long projectId, UUID nodeId) {
        SpatialNode node = requireNode(projectId, nodeId);
        if (node.isArchived()) {
            throw new SpatialNodeArchivedException("Spatial node " + nodeId + " is archived");
        }
        return node;
    }

    /** Ordered ancestors from the building down to the node; empty when the node is not visible. */
    @Transactional(readOnly = true)
    public List<SpatialPathSegment> pathOf(UUID nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        return repository.findByIdScoped(nodeId).map(this::pathSegments).orElse(List.of());
    }

    /**
     * Breadcrumbs for many nodes in two queries: the nodes, then every ancestor they name.
     * A node that is not visible is left out of the result.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<SpatialPathSegment>> pathsOf(Collection<UUID> nodeIds) {
        List<UUID> wanted = nodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return new HashMap<>();
        }
        List<SpatialNode> nodes = repository.findAllScoped(wanted);
        Set<UUID> ancestorIds = new HashSet<>();
        for (SpatialNode n : nodes) {
            ancestorIds.addAll(idsOnPath(n));
        }
        Map<UUID, SpatialNode> byId = new HashMap<>();
        if (!ancestorIds.isEmpty()) {
            for (SpatialNode n : repository.findAllScoped(new ArrayList<>(ancestorIds))) {
                byId.put(n.getId(), n);
            }
        }
        Map<UUID, List<SpatialPathSegment>> result = new HashMap<>();
        for (SpatialNode n : nodes) {
            result.put(n.getId(), segmentsFrom(idsOnPath(n), byId));
        }
        return result;
    }

    /** The path prefix that selects the node and its whole subtree, when the node is visible. */
    @Transactional(readOnly = true)
    public Optional<String> subtreePathPrefix(UUID nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
        return repository.findByIdScoped(nodeId).map(SpatialNode::getPath);
    }

    // --------------------------------------------------------------- writes

    /**
     * The node carrying an IFC GlobalId, archived or not. The BIM module matches its hierarchy
     * proposal against this before creating anything; the guid is unique per project so there
     * is at most one.
     */
    @Transactional(readOnly = true)
    public Optional<SpatialNodeDto> findByBimElementGuid(Long projectId, String bimElementGuid) {
        requireProject(projectId);
        String guid = blankToNull(bimElementGuid);
        if (guid == null) {
            return Optional.empty();
        }
        return repository.findByProjectIdAndBimElementGuid(projectId, guid).map(this::toDto);
    }

    @Transactional
    public SpatialNodeDto create(Long projectId, CreateSpatialNodeRequest request) {
        requireProject(projectId);
        SpatialNode parent = resolveParentForLevel(projectId, request.level(), request.parentId());
        SpatialNode node = createNode(projectId, parent, request.level(), request.code().trim(),
                request.name().trim(), request.sortOrder(), request.levelIndex(),
                request.elementType(), request.bimElementGuid(), request.externalRef());
        return toDto(node);
    }

    @Transactional
    public SpatialNodeDto update(Long projectId, UUID nodeId, UpdateSpatialNodeRequest request) {
        requireProject(projectId);
        SpatialNode node = requireUsableNode(projectId, nodeId);
        if (request.code() != null && !request.code().trim().equals(node.getCode())) {
            String code = request.code().trim();
            rejectSiblingClash(projectId, node.getParentId(), code);
            node.setCode(code);
        }
        if (request.name() != null) {
            node.setName(request.name().trim());
        }
        if (request.sortOrder() != null) {
            node.setSortOrder(request.sortOrder());
        }
        if (request.levelIndex() != null) {
            node.setLevelIndex(request.levelIndex());
        }
        if (request.elementType() != null) {
            node.setElementType(validatedElementType(request.elementType()));
        }
        if (request.bimElementGuid() != null) {
            String guid = blankToNull(request.bimElementGuid());
            rejectBimGuidClash(projectId, guid, node.getId());
            node.setBimElementGuid(guid);
        }
        if (request.externalRef() != null) {
            node.setExternalRef(blankToNull(request.externalRef()));
        }
        return toDto(repository.save(node));
    }

    /**
     * Re-parents a node within its project. The new parent must be of the level above the
     * node, active, and outside the node's subtree. Descendant paths are rewritten in the
     * same transaction.
     */
    @Transactional
    public SpatialNodeDto move(Long projectId, UUID nodeId, UUID newParentId) {
        requireProject(projectId);
        SpatialNode node = requireUsableNode(projectId, nodeId);
        if (node.getLevel() == SpatialLevel.BUILDING) {
            throw new SpatialNodeConflictException("A building has no parent and cannot be moved");
        }
        SpatialNode newParent = resolveParentForLevel(projectId, node.getLevel(), newParentId);
        if (Objects.equals(newParent.getId(), node.getParentId())) {
            return toDto(node);
        }
        if (newParent.getPath().startsWith(node.childPathPrefix()) || newParent.getId().equals(node.getId())) {
            throw new SpatialNodeConflictException("Cannot move a node into its own subtree");
        }
        rejectSiblingClash(projectId, newParent.getId(), node.getCode());

        String oldPath = node.getPath();
        String oldChildPrefix = node.childPathPrefix();
        String newPath = newParent.childPathPrefix() + node.getId();
        List<SpatialNode> subtree = repository.findByProjectIdAndPathStartingWith(projectId, oldChildPrefix);
        for (SpatialNode descendant : subtree) {
            descendant.setPath(newPath + descendant.getPath().substring(oldPath.length()));
        }
        node.setParentId(newParent.getId());
        node.setPath(newPath);
        repository.saveAll(subtree);
        return toDto(repository.save(node));
    }

    /** Archives the node and every active descendant, all stamped with the same instant. */
    @Transactional
    public SpatialNodeDto archive(Long projectId, UUID nodeId) {
        requireProject(projectId);
        SpatialNode node = requireNode(projectId, nodeId);
        if (node.isArchived()) {
            return toDto(node);
        }
        Instant now = Instant.now();
        List<SpatialNode> subtree = repository.findByProjectIdAndPathStartingWith(projectId, node.getPath());
        for (SpatialNode n : subtree) {
            if (!n.isArchived()) {
                n.setArchivedAt(now);
            }
        }
        repository.saveAll(subtree);
        return toDto(node);
    }

    /**
     * Restores the node together with the descendants that were archived with it (those
     * carrying the same archive instant); a descendant archived on its own earlier stays
     * archived. The parent must be active, so a subtree is restored top down.
     */
    @Transactional
    public SpatialNodeDto restore(Long projectId, UUID nodeId) {
        requireProject(projectId);
        SpatialNode node = requireNode(projectId, nodeId);
        if (!node.isArchived()) {
            return toDto(node);
        }
        if (node.getParentId() != null) {
            SpatialNode parent = requireNode(projectId, node.getParentId());
            if (parent.isArchived()) {
                throw new SpatialNodeArchivedException(
                        "Parent " + parent.getId() + " is archived; restore it first");
            }
        }
        Instant stamp = node.getArchivedAt();
        List<SpatialNode> subtree = repository.findByProjectIdAndPathStartingWith(projectId, node.getPath());
        for (SpatialNode n : subtree) {
            if (stamp.equals(n.getArchivedAt())) {
                n.setArchivedAt(null);
            }
        }
        repository.saveAll(subtree);
        return toDto(node);
    }

    /**
     * The zone that stands in for an unzoned floor: the floor's first active zone, or a new
     * one carrying the floor's own code and name. Elements always sit under a zone so every
     * element path has the same depth.
     */
    @Transactional
    public SpatialNode ensureDefaultZone(Long projectId, UUID floorId) {
        SpatialNode floor = requireUsableNode(projectId, floorId);
        if (floor.getLevel() != SpatialLevel.FLOOR) {
            throw new SpatialNodeConflictException("Node " + floorId + " is a " + floor.getLevel() + ", not a FLOOR");
        }
        return firstActiveZone(projectId, floor.getId())
                .orElseGet(() -> createNode(projectId, floor, SpatialLevel.ZONE, floor.getCode(),
                        floor.getName(), 0, null, null, null, null));
    }

    private Optional<SpatialNode> firstActiveZone(Long projectId, UUID floorId) {
        return repository.findByProjectIdAndParentIdAndArchivedAtIsNull(projectId, floorId).stream()
                .min(Comparator.comparingInt(SpatialNode::getSortOrder).thenComparing(SpatialNode::getCode));
    }

    /** The node of {@code level} with this code under the parent, created with code as name when absent. */
    private SpatialNode findOrCreate(Long projectId, SpatialNode parent, SpatialLevel level, String code,
                                     Integer levelIndex, String elementType, int[] counts) {
        Optional<SpatialNode> existing = parent == null
                ? repository.findByProjectIdAndParentIdIsNullAndCode(projectId, code)
                : repository.findByProjectIdAndParentIdAndCode(projectId, parent.getId(), code);
        if (existing.isPresent()) {
            SpatialNode node = existing.get();
            if (node.isArchived()) {
                throw new SpatialNodeArchivedException(level + " '" + code + "' is archived; restore it before importing under it");
            }
            counts[1]++;
            return node;
        }
        counts[0]++;
        return createNode(projectId, parent, level, code, code, null, levelIndex, elementType, null, null);
    }

    /**
     * Stands up a tree from spreadsheet rows, one row per leaf. Each level of a row is found
     * by its code under the level above and created when absent, so posting the same rows
     * twice creates nothing the second time. An element with no zone goes under the floor's
     * default zone. A zone or element with no floor is a 400: the chain is strict. Counts are
     * per node visited, so a building shared by many rows is skipped once per row after the
     * first.
     */
    @Transactional
    public SpatialImportResult importRows(Long projectId, List<SpatialImportRow> rows) {
        requireProject(projectId);
        int[] counts = new int[2];
        for (SpatialImportRow row : rows) {
            String floorCode = blankToNull(row.floor());
            String zoneCode = blankToNull(row.zone());
            String elementCode = blankToNull(row.element());
            if (floorCode == null && (zoneCode != null || elementCode != null)) {
                throw new InvalidRequestException("Row with building '" + row.building()
                        + "' names a zone or element without a floor");
            }
            SpatialNode building = findOrCreate(projectId, null, SpatialLevel.BUILDING,
                    row.building().trim(), null, null, counts);
            if (floorCode == null) {
                continue;
            }
            SpatialNode floor = findOrCreate(projectId, building, SpatialLevel.FLOOR, floorCode,
                    row.levelIndex(), null, counts);
            SpatialNode zone = null;
            if (zoneCode != null) {
                zone = findOrCreate(projectId, floor, SpatialLevel.ZONE, zoneCode, null, null, counts);
            } else if (elementCode != null) {
                zone = firstActiveZone(projectId, floor.getId()).orElse(null);
                if (zone == null) {
                    zone = createNode(projectId, floor, SpatialLevel.ZONE, floor.getCode(), floor.getName(),
                            0, null, null, null, null);
                    counts[0]++;
                } else {
                    counts[1]++;
                }
            }
            if (elementCode != null) {
                findOrCreate(projectId, zone, SpatialLevel.ELEMENT, elementCode, null,
                        blankToNull(row.elementType()), counts);
            }
        }
        return new SpatialImportResult(counts[0], counts[1]);
    }

    // ------------------------------------------------------------- internals

    /** Creates one node under an already validated parent; sibling and guid clashes are checked here. */
    SpatialNode createNode(Long projectId, SpatialNode parent, SpatialLevel level, String code, String name,
                           Integer sortOrder, Integer levelIndex, String elementType,
                           String bimElementGuid, String externalRef) {
        UUID parentId = parent == null ? null : parent.getId();
        rejectSiblingClash(projectId, parentId, code);
        String guid = blankToNull(bimElementGuid);
        rejectBimGuidClash(projectId, guid, null);

        SpatialNode node = new SpatialNode();
        node.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
        node.setProjectId(projectId);
        node.setParentId(parentId);
        node.setLevel(level);
        node.setCode(code);
        node.setName(name);
        node.setSortOrder(sortOrder == null ? 0 : sortOrder);
        node.setDepth(level.depth());
        node.setLevelIndex(level == SpatialLevel.FLOOR ? levelIndex : null);
        node.setElementType(level == SpatialLevel.ELEMENT ? validatedElementType(elementType) : null);
        node.setBimElementGuid(guid);
        node.setExternalRef(blankToNull(externalRef));
        // The id is assigned at persist; the path needs it, so it is written in a second step.
        node.setPath("");
        node = repository.save(node);
        node.setPath((parent == null ? "" : parent.getPath()) + "/" + node.getId());
        return repository.save(node);
    }

    /**
     * The parent a node of {@code level} may hang from: none for a building, otherwise the
     * active node of the level above, within the project.
     */
    private SpatialNode resolveParentForLevel(Long projectId, SpatialLevel level, UUID parentId) {
        SpatialLevel required = level.parentLevel();
        if (required == null) {
            if (parentId != null) {
                throw new SpatialNodeConflictException("A BUILDING has no parent");
            }
            return null;
        }
        if (parentId == null) {
            throw new SpatialNodeConflictException("A " + level + " needs a " + required + " parent");
        }
        SpatialNode parent = requireNode(projectId, parentId);
        if (parent.isArchived()) {
            throw new SpatialNodeArchivedException("Parent " + parentId + " is archived");
        }
        if (parent.getLevel() != required) {
            throw new SpatialNodeConflictException(
                    "A " + level + " must sit under a " + required + ", not a " + parent.getLevel());
        }
        return parent;
    }

    private void rejectSiblingClash(Long projectId, UUID parentId, String code) {
        boolean clash = parentId == null
                ? repository.existsByProjectIdAndParentIdIsNullAndCode(projectId, code)
                : repository.existsByProjectIdAndParentIdAndCode(projectId, parentId, code);
        if (clash) {
            throw new SpatialNodeConflictException("Code '" + code + "' is already used by a sibling");
        }
    }

    private void rejectBimGuidClash(Long projectId, String guid, UUID selfId) {
        if (guid == null) {
            return;
        }
        UUID exclude = selfId == null ? new UUID(0L, 0L) : selfId;
        if (repository.existsByProjectIdAndBimElementGuidAndIdNot(projectId, guid, exclude)) {
            throw new SpatialNodeConflictException("BIM element guid '" + guid + "' is already used in this project");
        }
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findByIdAndOrganization_Id(projectId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
    }

    private SpatialNode requireNode(Long projectId, UUID nodeId) {
        return repository.findByIdAndProjectId(nodeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Spatial node " + nodeId + " not found in project " + projectId));
    }

    private List<SpatialPathSegment> pathSegments(SpatialNode node) {
        List<UUID> ids = idsOnPath(node);
        Map<UUID, SpatialNode> byId = new HashMap<>();
        for (SpatialNode n : repository.findAllScoped(ids)) {
            byId.put(n.getId(), n);
        }
        return segmentsFrom(ids, byId);
    }

    private static List<UUID> idsOnPath(SpatialNode node) {
        return Arrays.stream(node.getPath().split("/"))
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .toList();
    }

    private static List<SpatialPathSegment> segmentsFrom(List<UUID> ids, Map<UUID, SpatialNode> byId) {
        List<SpatialPathSegment> segments = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            SpatialNode n = byId.get(id);
            if (n != null) {
                segments.add(new SpatialPathSegment(n.getId(), n.getLevel(), n.getCode(), n.getName()));
            }
        }
        return segments;
    }

    private List<SpatialTreeNodeDto> buildTree(List<SpatialNode> nodes) {
        // Nodes arrive depth-first ascending, so every parent is placed before its children.
        Map<UUID, List<SpatialTreeNodeDto>> children = new LinkedHashMap<>();
        List<SpatialTreeNodeDto> roots = new ArrayList<>();
        for (SpatialNode n : nodes) {
            List<SpatialTreeNodeDto> own = new ArrayList<>();
            children.put(n.getId(), own);
            SpatialTreeNodeDto dto = new SpatialTreeNodeDto(n.getId(), n.getParentId(), n.getLevel(),
                    n.getCode(), n.getName(), n.getSortOrder(), n.getLevelIndex(), n.getElementType(),
                    n.getBimElementGuid(), n.getExternalRef(), n.getArchivedAt(), own);
            List<SpatialTreeNodeDto> siblings = n.getParentId() == null ? roots : children.get(n.getParentId());
            if (siblings != null) {
                siblings.add(dto);
            }
        }
        return roots;
    }

    SpatialNodeDto toDto(SpatialNode n) {
        return new SpatialNodeDto(n.getId(), n.getProjectId(), n.getParentId(), n.getLevel(), n.getCode(),
                n.getName(), n.getSortOrder(), n.getDepth(), n.getLevelIndex(), n.getElementType(),
                n.getBimElementGuid(), n.getExternalRef(), n.getArchivedAt(), pathSegments(n));
    }

    /**
     * Blanks to null and, when the inspections module is present, checks the slug against the
     * organization's active element types. Without the module any slug is accepted.
     */
    private String validatedElementType(String elementType) {
        String code = blankToNull(elementType);
        if (code != null) {
            elementTypeValidator.ifAvailable(validator -> validator.requireActive(code));
        }
        return code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
