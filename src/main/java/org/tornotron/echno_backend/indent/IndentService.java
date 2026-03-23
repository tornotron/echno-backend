package org.tornotron.echno_backend.indent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IndentItemDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.IndentDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.dto.IndentUpdateDto;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemUpdateDto;
import org.tornotron.echno_backend.indent.dto.IndentCreationDto;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class IndentService {

    private final IndentRepository indentRepository;
    private final IndentItemRepository indentItemRepository;
    private final MaterialRepository materialRepository;
    private final FileStorageService fileStorageService;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final InventoryService inventoryService;

    public IndentService(IndentRepository indentRepository, IndentItemRepository indentItemRepository,
                         MaterialRepository materialRepository, FileStorageService fileStorageService,
                         TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository,
                         ProjectRepository projectRepository, InventoryService inventoryService) {
        this.indentRepository = indentRepository;
        this.indentItemRepository = indentItemRepository;
        this.materialRepository = materialRepository;
        this.fileStorageService = fileStorageService;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.inventoryService = inventoryService;
    }

    // ==================== Indent CRUD ====================
    @Transactional
    public IndentDto updateIndent(Long id, IndentUpdateDto indentDto) {
        Indent indent = indentRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId()).orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + id));

        if(indentDto.getIndentNumber() != null) {
            if(!indentDto.getIndentNumber().equals(indent.getIndentNumber()) &&
            indentRepository.existsByIndentNumberAndOrganization_Id(indentDto.getIndentNumber(), TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException(
                        "Indent with number "+ indentDto.getIndentNumber() + " already exists");
            }
            indent.setIndentNumber(indentDto.getIndentNumber());
        }

        if(indentDto.getCreatedByemployeeId() != null) {
            Employee employee = employeeRepository.findByIdAndOrganizationId(indentDto.getCreatedByemployeeId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + indentDto.getCreatedByemployeeId()));
            indent.setCreatedBy(employee);
        }

        if(indentDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(indentDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + indentDto.getProjectId()));
            indent.setProject(project);
        }

        if(indentDto.getStatus() != null) {
            indent.setStatus(IndentStatus.valueOf(indentDto.getStatus()));
        }

        if(indentDto.getExpectedOn() != null) {
            indent.setExpectedOn(indentDto.getExpectedOn());
        }

        if(indentDto.getRemarks() != null) {
            indent.setRemarks(indentDto.getRemarks());
        }

        return IndentDtoConvertor.convertIndentToDto(indentRepository.save(indent), fileStorageService, inventoryService);
    }

    @Transactional
    public IndentDto addIndent(IndentCreationDto indentCreationDto) {
        Indent indent = new Indent();
        Employee employee = employeeRepository.findByIdAndOrganizationId(indentCreationDto.getCreatedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + indentCreationDto.getCreatedByEmployeeId()));

        Project project = projectRepository.findByIdAndOrganization_Id(indentCreationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + indentCreationDto.getProjectId()));

        indent.setCreatedBy(employee);
        indent.setProject(project);
        indent.setIndentNumber(indentCreationDto.getIndentNumber());
        indent.setStatus(IndentStatus.valueOf(indentCreationDto.getStatus()));
        indent.setExpectedOn(indentCreationDto.getExpectedOn());
        indent.setRemarks(indentCreationDto.getRemarks());
        indent.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        // Add nested indent items if provided
        if (indentCreationDto.getItems() != null) {
            for (IndentItemCreationDto itemDto : indentCreationDto.getItems()) {
                IndentItem item = mapToIndentItemEntity(itemDto);
                item.setOrganization(indent.getOrganization());
                indent.addItem(item);
            }
        }

        return IndentDtoConvertor.convertIndentToDto(indentRepository.save(indent), fileStorageService, inventoryService);
    }

    @Transactional(readOnly = true)
    public Page<IndentDto> getAllIndents(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return indentRepository.findAll(pageable)
                .map(indent -> IndentDtoConvertor.convertIndentToDto(indent, fileStorageService, inventoryService));
    }

    @Transactional(readOnly = true)
    public List<IndentDto> getAllIndents() {
        return indentRepository.findAll().stream()
                .map(indent -> IndentDtoConvertor.convertIndentToDto(indent, fileStorageService, inventoryService))
                .toList();
    }

    @Transactional(readOnly = true)
    public IndentDto getAnIndent(Long id) {
        return indentRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .map(indent -> IndentDtoConvertor.convertIndentToDto(indent, fileStorageService, inventoryService))
                .orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + id));
    }

    @Transactional
    public void deleteIndent(Long id) {
        if (!indentRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Indent not found with id: " + id);
        }
        indentRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

    // ==================== IndentItem CRUD ====================

    @Transactional(readOnly = true)
    public List<IndentItemDto> getItemsByIndentId(Long indentId) {
        findIndentById(indentId);
        return indentItemRepository.findByIndentId(indentId).stream()
                .map(item -> IndentItemDtoConvertor.convertIndentItemToDto(item, fileStorageService, inventoryService))
                .collect(Collectors.toList());
    }

    @Transactional
    public IndentItemDto addItem(Long indentId, IndentItemCreationDto dto) {
        Indent indent = findIndentById(indentId);
        IndentItem item = mapToIndentItemEntity(dto);
        item.setOrganization(indent.getOrganization());
        indent.addItem(item);
        indentRepository.save(indent);
        return IndentItemDtoConvertor.convertIndentItemToDto(item, fileStorageService, inventoryService);
    }

    @Transactional
    public IndentItemDto updateItem(Long indentId, Long itemId, IndentItemUpdateDto dto) {
        findIndentById(indentId);
        IndentItem item = indentItemRepository.findByIdAndOrganization_Id(itemId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + itemId));
        if (!item.getIndent().getId().equals(indentId)) {
            throw new ResourceNotFoundException("IndentItem not found with id: " + itemId + " for indent: " + indentId);
        }

        if (dto.getMaterialId() != null) {
            Material material = materialRepository.findByIdAndOrganization_Id(dto.getMaterialId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + dto.getMaterialId()));
            item.setMaterial(material);
        }

        if (dto.getAdditionalSpecifications() != null) {
            item.setAdditionalSpecifications(dto.getAdditionalSpecifications());
        }

        if (dto.getRequestedQuantity() != null) {
            item.setRequestedQuantity(dto.getRequestedQuantity());
        }

        if (dto.getOrderedQuantity() != null) {
            item.setOrderedQuantity(dto.getOrderedQuantity());
        }

        if (dto.getRemarks() != null) {
            item.setRemarks(dto.getRemarks());
        }

        item = indentItemRepository.save(item);
        return IndentItemDtoConvertor.convertIndentItemToDto(item, fileStorageService, inventoryService);
    }

    @Transactional
    public void deleteItem(Long indentId, Long itemId) {
        Indent indent = findIndentById(indentId);
        IndentItem item = indentItemRepository.findByIdAndOrganization_Id(itemId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + itemId));
        if (!item.getIndent().getId().equals(indentId)) {
            throw new ResourceNotFoundException("IndentItem not found with id: " + itemId + " for indent: " + indentId);
        }
        indent.getItems().remove(item);
        indentItemRepository.delete(item);
    }

    // ==================== Helper Methods ====================

    private Indent findIndentById(Long id) {
        return indentRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent not found with id: " + id));
    }

    private IndentItem mapToIndentItemEntity(IndentItemCreationDto dto) {
        Material material = materialRepository.findByIdAndOrganization_Id(dto.getMaterialId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + dto.getMaterialId()));

        IndentItem item = new IndentItem();
        item.setMaterial(material);
        item.setAdditionalSpecifications(dto.getAdditionalSpecifications());
        item.setRequestedQuantity(dto.getRequestedQuantity());
        item.setOrderedQuantity(dto.getOrderedQuantity());
        item.setRemarks(dto.getRemarks());
        item.setConvertedToPurchaseOrder(false);
        return item;
    }
}
