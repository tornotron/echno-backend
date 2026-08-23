package org.tornotron.echno_backend.wbs;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.wbs.dto.*;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages a project's Work Breakdown Structure tree: element CRUD, moves, and roll-ups.
 *
 * <p>Maintains the parent/child invariants as the tree changes: it keeps each node's level
 * in step with its depth, flips the leaf flag when children are added or removed, and blocks
 * moves that would create a cycle or cross project boundaries. Progress and cost roll up from
 * leaves to their ancestors, progress as a weight-weighted average of children and cost as the
 * sum of child actual costs. Progress can be set directly only on leaf elements. All lookups
 * are scoped to the current organization.
 */
@Service
public class WbsElementService {

    private final WbsElementRepository wbsElementRepository;
    private final ProjectRepository projectRepository;
    private final EmployeeRepository employeeRepository;
    private final WbsElementMapper wbsElementMapper;

    public WbsElementService(WbsElementRepository wbsElementRepository,
                             ProjectRepository projectRepository,
                             EmployeeRepository employeeRepository,
                             WbsElementMapper wbsElementMapper) {
        this.wbsElementRepository = wbsElementRepository;
        this.projectRepository = projectRepository;
        this.employeeRepository = employeeRepository;
        this.wbsElementMapper = wbsElementMapper;
    }

    /**
     * Creates a WBS element under a project, optionally nested beneath a parent element.
     *
     * <p>When a parent is given, the new element's level is set one below the parent and the
     * parent is demoted from leaf if it was one. When no parent is given, the element becomes
     * a root at level 0.
     *
     * @param projectId The ID of the project the element belongs to.
     * @param dto The element details, including WBS code, optional parent, dates, budget, weight, and creator.
     * @return The created WBS element DTO.
     * @throws ResourceNotFoundException if the project, the parent element, or the creator employee cannot be found in the organization.
     * @throws DuplicateResourceException if the WBS code already exists in the project.
     * @throws InvalidRequestException if the given parent belongs to a different project.
     */
    @Transactional
    public WbsElementDto createWbsElement(Long projectId, WbsElementCreationDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        Project project = projectRepository.findByIdAndOrganization_Id(projectId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization"));

        if (wbsElementRepository.existsByProjectIdAndWbsCode(projectId, dto.getWbsCode())) {
            throw new DuplicateResourceException("WBS code '" + dto.getWbsCode() + "' already exists in this project");
        }

        WbsElement element = new WbsElement();
        element.setWbsCode(dto.getWbsCode());
        element.setTitle(dto.getTitle());
        element.setDescription(dto.getDescription());
        element.setProject(project);
        element.setOrganization(project.getOrganization());

        if (dto.getParentId() != null) {
            WbsElement parent = wbsElementRepository.findByIdAndOrganization_Id(dto.getParentId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Parent WBS element with ID " + dto.getParentId() + " was not found in this organization"));

            if (!parent.getProject().getId().equals(projectId)) {
                throw new InvalidRequestException("Parent WBS element with ID " + dto.getParentId() + " does not belong to project with ID " + projectId);
            }

            element.setParent(parent);
            element.setLevel(parent.getLevel() + 1);

            if (parent.getIsLeaf()) {
                parent.setIsLeaf(false);
                wbsElementRepository.save(parent);
            }
        } else {
            element.setLevel(0);
        }

        element.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);

        if (dto.getStatus() != null) {
            element.setStatus(WbsStatus.valueOf(dto.getStatus()));
        }

        element.setStartDate(dto.getStartDate());
        element.setEndDate(dto.getEndDate());

        if (dto.getBudgetedCost() != null) {
            element.setBudgetedCost(dto.getBudgetedCost());
        }

        if (dto.getWeight() != null) {
            element.setWeight(dto.getWeight());
        }

        if (dto.getCreatedBy() != null) {
            Employee creator = employeeRepository.findByIdAndOrganizationId(dto.getCreatedBy(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Creator (employee) with ID " + dto.getCreatedBy() + " was not found in this organization"));
            element.setCreatedBy(creator);
        }

        WbsElement savedElement = wbsElementRepository.save(element);
        return wbsElementMapper.toDto(savedElement);
    }

    /**
     * Creates several WBS elements under a project in one transaction.
     *
     * <p>Each element is created in list order, so a parent must appear before any child that
     * references it. The batch shares the transaction, so a failure on any element rolls back
     * the whole call.
     *
     * @param projectId The ID of the project the elements belong to.
     * @param dto The batch of element creation details.
     * @return The list of created WBS element DTOs.
     * @throws ResourceNotFoundException if a referenced project, parent, or creator cannot be found in the organization.
     * @throws DuplicateResourceException if any WBS code already exists in the project.
     * @throws InvalidRequestException if a referenced parent belongs to a different project.
     */
    @Transactional
    public List<WbsElementDto> bulkCreateWbsElements(Long projectId, WbsBulkCreateDto dto) {
        return dto.getElements().stream()
                .map(elementDto -> createWbsElement(projectId, elementDto))
                .collect(Collectors.toList());
    }

    /**
     * Returns a project's WBS as a nested tree of root elements with their descendants.
     *
     * @param projectId The ID of the project whose tree to return.
     * @return The list of root WBS element DTOs, each carrying its children, ordered by sort order.
     * @throws ResourceNotFoundException if no project with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public List<WbsElementDto> getWbsTree(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization");
        }

        List<WbsElement> rootElements = wbsElementRepository
                .findByProjectIdAndParentIsNullAndOrganization_IdOrderBySortOrderAsc(projectId, orgId);

        return rootElements.stream()
                .map(element -> wbsElementMapper.toTreeDto(element))
                .collect(Collectors.toList());
    }

    /**
     * Returns all WBS elements of a project as a flat list ordered by WBS code.
     *
     * @param projectId The ID of the project whose elements to return.
     * @return The flat list of WBS element DTOs sorted ascending by WBS code.
     * @throws ResourceNotFoundException if no project with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public List<WbsElementFlatDto> getWbsFlatList(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization");
        }

        List<WbsElement> elements = wbsElementRepository
                .findByProjectIdAndOrganization_IdOrderByWbsCodeAsc(projectId, orgId);

        return elements.stream()
                .map(wbsElementMapper::toFlatDto)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a single WBS element by its ID within the current organization.
     *
     * @param elementId The ID of the element to retrieve.
     * @return The WBS element DTO, including its children.
     * @throws ResourceNotFoundException if no element with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public WbsElementDto getWbsElementById(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element with ID " + elementId + " was not found in this organization"));

        return wbsElementMapper.toTreeDto(element);
    }

    /**
     * Applies a partial update to a WBS element, changing only the fields present in the DTO.
     *
     * <p>Progress is a special case: it may be set only on a leaf element, and setting it
     * triggers a progress roll-up to the element's ancestors.
     *
     * @param elementId The ID of the element to update.
     * @param dto The fields to change; null fields are left untouched.
     * @return The updated WBS element DTO.
     * @throws ResourceNotFoundException if no element with the given ID exists in the organization.
     * @throws InvalidRequestException if progress is set on a non-leaf element.
     */
    @Transactional
    public WbsElementDto updateWbsElement(Long elementId, WbsElementUpdateDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element with ID " + elementId + " was not found in this organization"));

        if (dto.getTitle() != null) {
            element.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            element.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            element.setStatus(WbsStatus.valueOf(dto.getStatus()));
        }
        if (dto.getStartDate() != null) {
            element.setStartDate(dto.getStartDate());
        }
        if (dto.getEndDate() != null) {
            element.setEndDate(dto.getEndDate());
        }
        if (dto.getActualStartDate() != null) {
            element.setActualStartDate(dto.getActualStartDate());
        }
        if (dto.getActualEndDate() != null) {
            element.setActualEndDate(dto.getActualEndDate());
        }
        if (dto.getBudgetedCost() != null) {
            element.setBudgetedCost(dto.getBudgetedCost());
        }
        if (dto.getWeight() != null) {
            element.setWeight(dto.getWeight());
        }
        if (dto.getSortOrder() != null) {
            element.setSortOrder(dto.getSortOrder());
        }
        if (dto.getProgress() != null) {
            if (!element.getIsLeaf()) {
                throw new InvalidRequestException("WBS element " + elementId + " is not a leaf element; progress can only be set directly on leaf WBS elements");
            }
            element.setProgress(dto.getProgress());
            recalculateParentProgress(element.getParent());
        }

        WbsElement savedElement = wbsElementRepository.save(element);
        return wbsElementMapper.toDto(savedElement);
    }

    /**
     * Deletes a WBS element and its subtree, then repairs the former parent.
     *
     * <p>After deletion, if the parent has no children left it is marked a leaf again, and the
     * parent's progress is recalculated up the tree.
     *
     * @param elementId The ID of the element to delete.
     * @throws ResourceNotFoundException if no element with the given ID exists in the organization.
     */
    @Transactional
    public void deleteWbsElement(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element with ID " + elementId + " was not found in this organization"));

        WbsElement parent = element.getParent();

        wbsElementRepository.delete(element);
        wbsElementRepository.flush();

        if (parent != null) {
            List<WbsElement> remainingSiblings = wbsElementRepository
                    .findByParentIdOrderBySortOrderAsc(parent.getId());
            if (remainingSiblings.isEmpty()) {
                parent.setIsLeaf(true);
                wbsElementRepository.save(parent);
            }
            recalculateParentProgress(parent);
        }
    }

    /**
     * Re-parents a WBS element, optionally also changing its WBS code and sort order.
     *
     * <p>Moving to a new parent recomputes the subtree's levels and demotes the new parent from
     * leaf; moving to no parent makes the element a root. The old parent is repaired afterward
     * (marked a leaf if now childless, progress rolled up). A new WBS code must stay unique
     * within the project.
     *
     * @param elementId The ID of the element to move.
     * @param dto The move details: new parent (or none), optional new WBS code, and optional new sort order.
     * @return The moved WBS element DTO.
     * @throws ResourceNotFoundException if the element or the new parent cannot be found in the organization.
     * @throws InvalidRequestException if the new parent is in a different project or is the element's own descendant.
     * @throws DuplicateResourceException if the new WBS code is already used by another element in the project.
     */
    @Transactional
    public WbsElementDto moveWbsElement(Long elementId, WbsMoveDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element with ID " + elementId + " was not found in this organization"));

        WbsElement oldParent = element.getParent();

        if (dto.getNewParentId() != null) {
            WbsElement newParent = wbsElementRepository.findByIdAndOrganization_Id(dto.getNewParentId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("New parent WBS element with ID " + dto.getNewParentId() + " was not found in this organization"));

            if (!newParent.getProject().getId().equals(element.getProject().getId())) {
                throw new InvalidRequestException("Cannot move WBS element " + elementId + " to parent " + dto.getNewParentId() + " because it belongs to a different project");
            }

            if (isDescendantOf(newParent, element)) {
                throw new InvalidRequestException("Cannot move WBS element " + elementId + " under its own descendant " + dto.getNewParentId());
            }

            element.setParent(newParent);
            recalculateLevels(element, newParent.getLevel() + 1);

            if (newParent.getIsLeaf()) {
                newParent.setIsLeaf(false);
                wbsElementRepository.save(newParent);
            }
        } else {
            element.setParent(null);
            recalculateLevels(element, 0);
        }

        if (dto.getNewWbsCode() != null) {
            if (wbsElementRepository.existsByProjectIdAndWbsCode(element.getProject().getId(), dto.getNewWbsCode())) {
                WbsElement existing = wbsElementRepository
                        .findByProjectIdAndWbsCode(element.getProject().getId(), dto.getNewWbsCode())
                        .orElse(null);
                if (existing != null && !existing.getId().equals(elementId)) {
                    throw new DuplicateResourceException("WBS code '" + dto.getNewWbsCode() + "' already exists in this project");
                }
            }
            element.setWbsCode(dto.getNewWbsCode());
        }

        if (dto.getNewSortOrder() != null) {
            element.setSortOrder(dto.getNewSortOrder());
        }

        WbsElement savedElement = wbsElementRepository.save(element);

        if (oldParent != null) {
            List<WbsElement> remainingSiblings = wbsElementRepository
                    .findByParentIdOrderBySortOrderAsc(oldParent.getId());
            if (remainingSiblings.isEmpty()) {
                oldParent.setIsLeaf(true);
                wbsElementRepository.save(oldParent);
            }
            recalculateParentProgress(oldParent);
        }

        return wbsElementMapper.toDto(savedElement);
    }

    /**
     * Returns a project's leaf WBS elements as a flat list ordered by WBS code.
     *
     * @param projectId The ID of the project whose leaf elements to return.
     * @return The flat list of leaf WBS element DTOs sorted ascending by WBS code.
     * @throws ResourceNotFoundException if no project with the given ID exists in the organization.
     */
    @Transactional(readOnly = true)
    public List<WbsElementFlatDto> getLeafElements(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project with ID " + projectId + " was not found in this organization");
        }

        return wbsElementRepository.findByProjectIdAndIsLeafTrueAndOrganization_IdOrderByWbsCodeAsc(projectId, orgId)
                .stream()
                .map(wbsElementMapper::toFlatDto)
                .collect(Collectors.toList());
    }

    /**
     * Recomputes a WBS element's rolled-up cost and progress from its children.
     *
     * <p>Actual cost is summed recursively from descendant leaves, and progress is recomputed
     * as the weight-weighted average of the element's children. Leaf elements are left as is.
     *
     * @param elementId The ID of the element to recalculate.
     * @return The recalculated WBS element DTO.
     * @throws ResourceNotFoundException if no element with the given ID exists in the organization.
     */
    @Transactional
    public WbsElementDto recalculateElement(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element with ID " + elementId + " was not found in this organization"));

        recalculateCosts(element);
        recalculateProgressFromChildren(element);
        WbsElement saved = wbsElementRepository.save(element);

        return wbsElementMapper.toDto(saved);
    }

    private void recalculateParentProgress(WbsElement parent) {
        if (parent == null) return;

        recalculateProgressFromChildren(parent);
        wbsElementRepository.save(parent);
        recalculateParentProgress(parent.getParent());
    }

    private void recalculateProgressFromChildren(WbsElement element) {
        if (element.getIsLeaf()) return;

        List<WbsElement> children = wbsElementRepository
                .findByParentIdOrderBySortOrderAsc(element.getId());

        if (children.isEmpty()) return;

        double totalWeight = children.stream()
                .mapToDouble(c -> c.getWeight() != null ? c.getWeight() : 1.0)
                .sum();

        if (totalWeight == 0) return;

        double weightedProgress = children.stream()
                .mapToDouble(c -> {
                    double w = c.getWeight() != null ? c.getWeight() : 1.0;
                    double p = c.getProgress() != null ? c.getProgress() : 0.0;
                    return w * p;
                })
                .sum();

        element.setProgress(Math.round(weightedProgress / totalWeight * 100.0) / 100.0);
    }

    private void recalculateCosts(WbsElement element) {
        if (element.getIsLeaf()) return;

        List<WbsElement> children = wbsElementRepository
                .findByParentIdOrderBySortOrderAsc(element.getId());

        BigDecimal totalActualCost = children.stream()
                .map(c -> {
                    recalculateCosts(c);
                    return c.getActualCost() != null ? c.getActualCost() : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        element.setActualCost(totalActualCost.setScale(2, RoundingMode.HALF_UP));
    }

    private boolean isDescendantOf(WbsElement candidate, WbsElement ancestor) {
        if (candidate.getId().equals(ancestor.getId())) return true;

        List<WbsElement> children = wbsElementRepository
                .findByParentIdOrderBySortOrderAsc(ancestor.getId());

        for (WbsElement child : children) {
            if (isDescendantOf(candidate, child)) return true;
        }
        return false;
    }

    private void recalculateLevels(WbsElement element, int newLevel) {
        element.setLevel(newLevel);
        List<WbsElement> children = wbsElementRepository
                .findByParentIdOrderBySortOrderAsc(element.getId());
        for (WbsElement child : children) {
            recalculateLevels(child, newLevel + 1);
            wbsElementRepository.save(child);
        }
    }
}
