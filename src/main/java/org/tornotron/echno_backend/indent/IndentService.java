package org.tornotron.echno_backend.indent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.indent.mapper.IndentMapper;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
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
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CRUD for material indents and their line items.
 *
 * <p>An indent is a site request for materials to be procured. This service manages the
 * indent header and its items within the current tenant, enforcing unique indent numbers
 * and keeping each item bound to its parent indent. Items carry a converted-to-purchase-order
 * flag that the purchase order flow sets when an item is fulfilled.
 */
@Service
public class IndentService {

    private final IndentRepository indentRepository;
    private final IndentItemRepository indentItemRepository;
    private final MaterialRepository materialRepository;
    private final IndentMapper indentMapper;
    private final TenantEntityHelper tenantEntityHelper;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final IndentItemMapper indentItemMapper;

    public IndentService(IndentRepository indentRepository, IndentItemRepository indentItemRepository,
                         MaterialRepository materialRepository, IndentMapper indentMapper,
                         TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository,
                         ProjectRepository projectRepository, IndentItemMapper indentItemMapper) {
        this.indentRepository = indentRepository;
        this.indentItemRepository = indentItemRepository;
        this.materialRepository = materialRepository;
        this.indentMapper = indentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.indentItemMapper = indentItemMapper;
    }

    // ==================== Indent CRUD ====================
    /**
     * Applies a partial update to an indent header.
     *
     * <p>Only the non-null fields on the update DTO are changed. A changed indent number is
     * checked for uniqueness before being applied.
     *
     * @param id The id of the indent to update.
     * @param indentDto The fields to change.
     * @return The updated indent as a DTO.
     * @throws ResourceNotFoundException if the indent, or a referenced employee or project, is not found in this organization.
     * @throws DuplicateResourceException if the new indent number is already in use in this organization.
     */
    @Transactional
    public IndentDto updateIndent(Long id, IndentUpdateDto indentDto) {
        Indent indent = indentRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId()).orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + id + " was not found in this organization"));

        if(indentDto.getIndentNumber() != null) {
            if(!indentDto.getIndentNumber().equals(indent.getIndentNumber()) &&
            indentRepository.existsByIndentNumberAndOrganization_Id(indentDto.getIndentNumber(), TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException(
                        "Indent number '" + indentDto.getIndentNumber() + "' is already in use in this organization");
            }
            indent.setIndentNumber(indentDto.getIndentNumber());
        }

        if(indentDto.getCreatedByemployeeId() != null) {
            Employee employee = employeeRepository.findByIdAndOrganizationId(indentDto.getCreatedByemployeeId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + indentDto.getCreatedByemployeeId() + " was not found in this organization"));
            indent.setCreatedBy(employee);
        }

        if(indentDto.getProjectId() != null) {
            Project project = projectRepository.findByIdAndOrganization_Id(indentDto.getProjectId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + indentDto.getProjectId() + " was not found in this organization"));
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

        return indentMapper.toDto(indentRepository.save(indent));
    }

    /**
     * Creates an indent with any nested line items.
     *
     * <p>Resolves the creating employee and project, sets the header fields, and attaches
     * each supplied item, resolving its material.
     *
     * @param indentCreationDto The indent header fields and optional list of items.
     * @return The created indent as a DTO.
     * @throws ResourceNotFoundException if the creator, project, or a line's material is not found in this organization.
     */
    @Transactional
    public IndentDto addIndent(IndentCreationDto indentCreationDto) {
        Indent indent = new Indent();
        Employee employee = employeeRepository.findByIdAndOrganizationId(indentCreationDto.getCreatedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + indentCreationDto.getCreatedByEmployeeId() + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(indentCreationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + indentCreationDto.getProjectId() + " was not found in this organization"));

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

        return indentMapper.toDto(indentRepository.save(indent));
    }

    /**
     * Retrieves indents one page at a time, ordered by id.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of indents per page.
     * @return A page of indent DTOs ordered by id ascending.
     */
    @Transactional(readOnly = true)
    public Page<IndentDto> getAllIndents(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return indentRepository.findAll(pageable)
                .map(indent -> indentMapper.toDto(indent));
    }

    @Transactional(readOnly = true)
    public List<IndentDto> getAllIndents() {
        return indentRepository.findAll().stream()
                .map(indent -> indentMapper.toDto(indent))
                .toList();
    }

    /**
     * Retrieves a single indent by its id within the current tenant.
     *
     * @param id The id of the indent to retrieve.
     * @return The indent as a DTO.
     * @throws ResourceNotFoundException if no indent with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public IndentDto getAnIndent(Long id) {
        return indentRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .map(indent -> indentMapper.toDto(indent))
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + id + " was not found in this organization"));
    }

    /**
     * Deletes an indent and its items within the current tenant.
     *
     * @param id The id of the indent to delete.
     * @throws ResourceNotFoundException if no indent with the given id exists in this organization.
     */
    @Transactional
    public void deleteIndent(Long id) {
        if (!indentRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Indent with ID " + id + " was not found in this organization");
        }
        indentRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

    // ==================== IndentItem CRUD ====================

    /**
     * Lists the line items of a given indent.
     *
     * @param indentId The indent whose items to return.
     * @return The indent's items as DTOs.
     * @throws ResourceNotFoundException if no indent with the given id exists in this organization.
     */
    @Transactional(readOnly = true)
    public List<IndentItemDto> getItemsByIndentId(Long indentId) {
        findIndentById(indentId);
        return indentItemRepository.findByIndentId(indentId).stream()
                .map(item -> indentItemMapper.toDto(item))
                .collect(Collectors.toList());
    }

    /**
     * Adds a line item to an existing indent.
     *
     * @param indentId The indent to add the item to.
     * @param dto The item fields, including its material.
     * @return The created item as a DTO.
     * @throws ResourceNotFoundException if the indent or the item's material is not found in this organization.
     */
    @Transactional
    public IndentItemDto addItem(Long indentId, IndentItemCreationDto dto) {
        Indent indent = findIndentById(indentId);
        IndentItem item = mapToIndentItemEntity(dto);
        item.setOrganization(indent.getOrganization());
        indent.addItem(item);
        indentRepository.save(indent);
        return indentItemMapper.toDto(item);
    }

    /**
     * Applies a partial update to a line item of an indent.
     *
     * <p>Only the non-null fields on the update DTO are changed. The item must belong to the
     * given indent.
     *
     * @param indentId The indent the item belongs to.
     * @param itemId The id of the item to update.
     * @param dto The fields to change.
     * @return The updated item as a DTO.
     * @throws ResourceNotFoundException if the indent or item is not found in this organization, if the item does not belong to the indent, or if a new material is not found.
     */
    @Transactional
    public IndentItemDto updateItem(Long indentId, Long itemId, IndentItemUpdateDto dto) {
        findIndentById(indentId);
        IndentItem item = indentItemRepository.findByIdAndOrganization_Id(itemId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + itemId + " was not found in this organization"));
        if (!item.getIndent().getId().equals(indentId)) {
            throw new ResourceNotFoundException("Indent item with ID " + itemId + " does not belong to indent " + indentId);
        }

        if (dto.getMaterialId() != null) {
            Material material = materialRepository.findByIdAndOrganization_Id(dto.getMaterialId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + dto.getMaterialId() + " was not found in this organization"));
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
        return indentItemMapper.toDto(item);
    }

    /**
     * Removes a line item from an indent.
     *
     * <p>The item must belong to the given indent.
     *
     * @param indentId The indent the item belongs to.
     * @param itemId The id of the item to remove.
     * @throws ResourceNotFoundException if the indent or item is not found in this organization, or if the item does not belong to the indent.
     */
    @Transactional
    public void deleteItem(Long indentId, Long itemId) {
        Indent indent = findIndentById(indentId);
        IndentItem item = indentItemRepository.findByIdAndOrganization_Id(itemId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + itemId + " was not found in this organization"));
        if (!item.getIndent().getId().equals(indentId)) {
            throw new ResourceNotFoundException("Indent item with ID " + itemId + " does not belong to indent " + indentId);
        }
        indent.getItems().remove(item);
        indentItemRepository.delete(item);
    }

    // ==================== Helper Methods ====================

    private Indent findIndentById(Long id) {
        return indentRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + id + " was not found in this organization"));
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
