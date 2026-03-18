package org.tornotron.echno_backend.wbs;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.WbsElementDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
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

@Service
public class WbsElementService {

    private final WbsElementRepository wbsElementRepository;
    private final ProjectRepository projectRepository;
    private final EmployeeRepository employeeRepository;
    private final FileStorageService fileStorageService;

    public WbsElementService(WbsElementRepository wbsElementRepository,
                             ProjectRepository projectRepository,
                             EmployeeRepository employeeRepository,
                             FileStorageService fileStorageService) {
        this.wbsElementRepository = wbsElementRepository;
        this.projectRepository = projectRepository;
        this.employeeRepository = employeeRepository;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public WbsElementDto createWbsElement(Long projectId, WbsElementCreationDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        Project project = projectRepository.findByIdAndOrganization_Id(projectId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

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
                    .orElseThrow(() -> new ResourceNotFoundException("Parent WBS element not found with id: " + dto.getParentId()));

            if (!parent.getProject().getId().equals(projectId)) {
                throw new InvalidRequestException("Parent WBS element does not belong to the same project");
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
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + dto.getCreatedBy()));
            element.setCreatedBy(creator);
        }

        WbsElement savedElement = wbsElementRepository.save(element);
        return WbsElementDtoConvertor.convertToDto(savedElement, fileStorageService);
    }

    @Transactional
    public List<WbsElementDto> bulkCreateWbsElements(Long projectId, WbsBulkCreateDto dto) {
        return dto.getElements().stream()
                .map(elementDto -> createWbsElement(projectId, elementDto))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WbsElementDto> getWbsTree(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }

        List<WbsElement> rootElements = wbsElementRepository
                .findByProjectIdAndParentIsNullAndOrganization_IdOrderBySortOrderAsc(projectId, orgId);

        return rootElements.stream()
                .map(element -> WbsElementDtoConvertor.convertToTreeDto(element, fileStorageService))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WbsElementFlatDto> getWbsFlatList(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }

        List<WbsElement> elements = wbsElementRepository
                .findByProjectIdAndOrganization_IdOrderByWbsCodeAsc(projectId, orgId);

        return elements.stream()
                .map(WbsElementDtoConvertor::convertToFlatDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WbsElementDto getWbsElementById(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element not found with id: " + elementId));

        return WbsElementDtoConvertor.convertToTreeDto(element, fileStorageService);
    }

    @Transactional
    public WbsElementDto updateWbsElement(Long elementId, WbsElementUpdateDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element not found with id: " + elementId));

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
                throw new InvalidRequestException("Progress can only be set directly on leaf WBS elements");
            }
            element.setProgress(dto.getProgress());
            recalculateParentProgress(element.getParent());
        }

        WbsElement savedElement = wbsElementRepository.save(element);
        return WbsElementDtoConvertor.convertToDto(savedElement, fileStorageService);
    }

    @Transactional
    public void deleteWbsElement(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element not found with id: " + elementId));

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

    @Transactional
    public WbsElementDto moveWbsElement(Long elementId, WbsMoveDto dto) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element not found with id: " + elementId));

        WbsElement oldParent = element.getParent();

        if (dto.getNewParentId() != null) {
            WbsElement newParent = wbsElementRepository.findByIdAndOrganization_Id(dto.getNewParentId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("New parent WBS element not found with id: " + dto.getNewParentId()));

            if (!newParent.getProject().getId().equals(element.getProject().getId())) {
                throw new InvalidRequestException("Cannot move WBS element to a parent in a different project");
            }

            if (isDescendantOf(newParent, element)) {
                throw new InvalidRequestException("Cannot move WBS element under its own descendant");
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

        return WbsElementDtoConvertor.convertToDto(savedElement, fileStorageService);
    }

    @Transactional(readOnly = true)
    public List<WbsElementFlatDto> getLeafElements(Long projectId) {
        Long orgId = TenantContext.getCurrentOrgId();

        if (!projectRepository.existsByIdAndOrganization_Id(projectId, orgId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }

        return wbsElementRepository.findByProjectIdAndIsLeafTrueAndOrganization_IdOrderByWbsCodeAsc(projectId, orgId)
                .stream()
                .map(WbsElementDtoConvertor::convertToFlatDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public WbsElementDto recalculateElement(Long elementId) {
        Long orgId = TenantContext.getCurrentOrgId();

        WbsElement element = wbsElementRepository.findByIdAndOrganization_Id(elementId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("WBS element not found with id: " + elementId));

        recalculateCosts(element);
        recalculateProgressFromChildren(element);
        WbsElement saved = wbsElementRepository.save(element);

        return WbsElementDtoConvertor.convertToDto(saved, fileStorageService);
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
