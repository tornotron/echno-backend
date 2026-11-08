package org.tornotron.echno_backend.indent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.indent.mapper.IndentMapper;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberAllocator;
import org.tornotron.echno_backend.common.documentnumber.DocumentNumberType;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.indent.dto.IndentUpdateDto;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemCountLookup;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemUpdateDto;
import org.tornotron.echno_backend.indent.dto.IndentCreationDto;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.dto.IndentSummaryDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.modules.access.api.AccessFilter;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
    private final DocumentNumberAllocator documentNumberAllocator;
    private final TransactionRetryTemplate retryTemplate;
    private final InventoryService inventoryService;
    private final IndentAccess indentAccess;

    public IndentService(IndentRepository indentRepository, IndentItemRepository indentItemRepository,
                         MaterialRepository materialRepository, IndentMapper indentMapper,
                         TenantEntityHelper tenantEntityHelper, EmployeeRepository employeeRepository,
                         ProjectRepository projectRepository, IndentItemMapper indentItemMapper,
                         DocumentNumberAllocator documentNumberAllocator,
                         TransactionRetryTemplate retryTemplate, InventoryService inventoryService,
                         IndentAccess indentAccess) {
        this.indentRepository = indentRepository;
        this.indentItemRepository = indentItemRepository;
        this.materialRepository = materialRepository;
        this.indentMapper = indentMapper;
        this.tenantEntityHelper = tenantEntityHelper;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.indentItemMapper = indentItemMapper;
        this.documentNumberAllocator = documentNumberAllocator;
        this.retryTemplate = retryTemplate;
        this.inventoryService = inventoryService;
        this.indentAccess = indentAccess;
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
        Indent indent = requireIndent(id, AccessPermission.EDIT);

        if(indentDto.getIndentNumber() != null) {
            if(!indentDto.getIndentNumber().equals(indent.getIndentNumber()) &&
            indentRepository.existsByIndentNumberAndOrganization_Id(indentDto.getIndentNumber(), TenantContext.getCurrentOrgId())) {
                throw new DuplicateResourceException(
                        "Indent number '" + indentDto.getIndentNumber() + "' is already in use in this organization");
            }
            indent.setIndentNumber(indentDto.getIndentNumber());
        }

        if(indentDto.getCreatedByEmployeeId() != null) {
            Employee employee = employeeRepository.findByIdAndOrganizationId(indentDto.getCreatedByEmployeeId(), TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + indentDto.getCreatedByEmployeeId() + " was not found in this organization"));
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
        // An edit can move the indent to another project or another raiser, and must not move it out
        // of what the caller may edit.
        indentAccess.require(AccessPermission.EDIT, IndentAccess.facts(indent));

        Indent saved = indentRepository.save(indent);
        return indentMapper.toDto(saved, stockForIndents(List.of(saved)));
    }

    /**
     * Creates an indent with any nested line items.
     *
     * <p>Allocates the indent number, resolves the creating employee and project, sets the
     * header fields, and attaches each supplied item, resolving its material.
     *
     * <p>The transaction is restarted on a serialization abort, and also on a unique
     * violation: the counter behind the indent number is the row two concurrent creates
     * contend on, and a fresh attempt allocates the next number rather than reporting a
     * collision the user did not cause.
     *
     * @param indentCreationDto The indent header fields and optional list of items.
     * @return The created indent as a DTO.
     * @throws ResourceNotFoundException if the creator, project, or a line's material is not found in this organization.
     */
    public IndentDto addIndent(IndentCreationDto indentCreationDto) {
        return retryTemplate.execute(
                "IndentService.addIndent",
                failure -> SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION),
                () -> addIndentInTransaction(indentCreationDto));
    }

    private IndentDto addIndentInTransaction(IndentCreationDto indentCreationDto) {
        Indent indent = new Indent();
        Employee employee = employeeRepository.findByIdAndOrganizationId(indentCreationDto.getCreatedByEmployeeId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee with ID " + indentCreationDto.getCreatedByEmployeeId() + " was not found in this organization"));

        Project project = projectRepository.findByIdAndOrganization_Id(indentCreationDto.getProjectId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Project with ID " + indentCreationDto.getProjectId() + " was not found in this organization"));

        indent.setCreatedBy(employee);
        indent.setProject(project);
        indent.setIndentNumber(
                documentNumberAllocator.allocate(DocumentNumberType.INDENT, TenantContext.getCurrentOrgId()));
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
        // Checked on the indent as raised, so nobody raises one where they may not.
        indentAccess.require(AccessPermission.CREATE, IndentAccess.facts(indent));

        Indent saved = indentRepository.save(indent);
        return indentMapper.toDto(saved, stockForIndents(List.of(saved)));
    }

    /**
     * Retrieves indents one page at a time, ordered by id.
     *
     * <p>Only the indents the caller may read are listed, and the narrowing is part of the query, so
     * the page and its total count only those.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of indents per page.
     * @return A page of indent DTOs ordered by id ascending.
     */
    @Transactional(readOnly = true)
    public Page<IndentDto> getAllIndents(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        return toDtos(visibleIndents(pageable));
    }

    /**
     * Every indent of the organization, one page at a time, for the organization report.
     *
     * <p>Not narrowed to the indents the caller may read. The report is open to roles that hold no
     * indents permission at all, and its reach is decided with the report itself, not here.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of indents per page.
     * @return A page of indent DTOs ordered by id ascending.
     */
    @Transactional(readOnly = true)
    public Page<IndentDto> getAllIndentsForReport(int pageNo, int pageSize) {
        return toDtos(indentRepository.findAll(PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"))));
    }

    private Page<IndentDto> toDtos(Page<Indent> indents) {
        MaterialStockLookup stock = stockForIndents(indents.getContent());
        return indents.map(indent -> indentMapper.toDto(indent, stock));
    }

    /** The indents the caller may read, narrowed in the query. */
    private Page<Indent> visibleIndents(Pageable pageable) {
        AccessFilter visible = indentAccess.filter(AccessPermission.READ);
        if (visible.isEmpty()) {
            return Page.empty(pageable);
        }
        return visible.everything()
                ? indentRepository.findAll(pageable)
                : indentRepository.findAll(IndentAccess.admittedBy(visible), pageable);
    }

    /**
     * Retrieves a page of indents as summaries: the indent's own fields, who raised it and how
     * many lines it has.
     *
     * <p>The list projection of {@link #getAllIndents}. The full DTO carries every requested item,
     * every item carries a whole material, and every material carries stock figures read from a
     * further aggregate, so a page of indents materialises a slice of the material catalogue to
     * render a column of indent numbers. The line count comes from one grouped read instead, and
     * the raiser flattens to an id and a name rather than a full employee.
     *
     * <p>Offered alongside {@link #getAllIndents} rather than replacing it, for the same reason
     * the project summary is: the published contract is hand-maintained, so which endpoints move
     * over is a decision per endpoint.
     *
     * @param pageNo Zero-based page index.
     * @param pageSize Number of indents per page.
     * @return A page of indent summaries ordered by id ascending.
     */
    @Transactional(readOnly = true)
    public Page<IndentSummaryDto> getAllIndentsSummary(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.ASC, "id"));
        Page<Indent> indents = visibleIndents(pageable);
        IndentItemCountLookup itemCounts = itemCountsFor(indents.getContent());
        return indents.map(indent -> indentMapper.toSummaryDto(indent, itemCounts));
    }

    /**
     * Reads the line count for a whole page of indents, in one query.
     *
     * @param indents The indents being converted.
     * @return Their line counts, with an indent that has no lines reading as zero.
     */
    private IndentItemCountLookup itemCountsFor(Collection<Indent> indents) {
        List<Long> ids = indents.stream()
                .map(Indent::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return IndentItemCountLookup.none();
        }
        return IndentItemCountLookup.of(indentItemRepository.countItemsByIndentIds(ids));
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
        Indent indent = requireIndent(id, AccessPermission.READ);
        return indentMapper.toDto(indent, stockForIndents(List.of(indent)));
    }

    /**
     * Deletes an indent and its items within the current tenant.
     *
     * @param id The id of the indent to delete.
     * @throws ResourceNotFoundException if no indent with the given id exists in this organization.
     */
    @Transactional
    public void deleteIndent(Long id) {
        requireIndent(id, AccessPermission.DELETE);
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
        requireIndent(indentId, AccessPermission.READ);
        List<IndentItem> items = indentItemRepository.findByIndentId(indentId);
        MaterialStockLookup stock = stockForItems(items);
        return items.stream()
                .map(item -> indentItemMapper.toDto(item, stock))
                .toList();
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
        Indent indent = requireIndent(indentId, AccessPermission.CREATE);
        IndentItem item = mapToIndentItemEntity(dto);
        item.setOrganization(indent.getOrganization());
        indent.addItem(item);
        indentRepository.save(indent);
        return indentItemMapper.toDto(item, stockForItems(List.of(item)));
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
        requireIndent(indentId, AccessPermission.EDIT);
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
        return indentItemMapper.toDto(item, stockForItems(List.of(item)));
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
        Indent indent = requireIndent(indentId, AccessPermission.DELETE);
        IndentItem item = indentItemRepository.findByIdAndOrganization_Id(itemId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + itemId + " was not found in this organization"));
        if (!item.getIndent().getId().equals(indentId)) {
            throw new ResourceNotFoundException("Indent item with ID " + itemId + " does not belong to indent " + indentId);
        }
        indent.getItems().remove(item);
        indentItemRepository.delete(item);
    }

    // ==================== Helper Methods ====================

    /**
     * The indent, if the caller may act on it this way. One they may not read is reported missing, as
     * one in another organization is; one they may read but not act on this way is refused.
     */
    private Indent requireIndent(Long id, String action) {
        Indent indent = indentRepository.findByIdAndOrganization_Id(id, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> notFound(id));
        indentAccess.check(action, IndentAccess.facts(indent), () -> notFound(id));
        return indent;
    }

    private static ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Indent with ID " + id + " was not found in this organization");
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

    /**
     * Reads the aggregate stock for every material on the given indents, in one query.
     *
     * <p>An indent line carries a full material DTO, so while the material mapper fetched its own
     * stock a ten-line indent cost twenty aggregate reads and a page of indents multiplied that by
     * the page size. One call now covers the page.
     *
     * @param indents The indents being converted.
     * @return The stock for their materials, with anything unstocked reading as zero.
     */
    private MaterialStockLookup stockForIndents(Collection<Indent> indents) {
        return stockForItems(indents.stream()
                .flatMap(indent -> indent.getItems() == null ? Stream.<IndentItem>empty() : indent.getItems().stream())
                .toList());
    }

    /**
     * Reads the aggregate stock for the materials on the given indent lines, in one query.
     *
     * @param items The lines being converted.
     * @return The stock for their materials, with anything unstocked reading as zero.
     */
    private MaterialStockLookup stockForItems(Collection<IndentItem> items) {
        return inventoryService.aggregateStockFor(items.stream()
                .map(IndentItem::getMaterial)
                .filter(Objects::nonNull)
                .map(Material::getId)
                .toList());
    }
}
