package org.tornotron.echno_backend.indentItem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.dto.IndentItemCreationDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.Indent;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.inventoryTransaction.MaterialStockLookup;
import org.tornotron.echno_backend.material.MaterialRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Service
public class IndentItemService {

    private final IndentItemRepository indentItemRepository;
    private final IndentRepository indentRepository;
    private final MaterialRepository materialRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final IndentItemMapper indentItemMapper;
    private final InventoryService inventoryService;

    public IndentItemService(IndentItemRepository indentItemRepository,
                             IndentRepository indentRepository,
                             MaterialRepository materialRepository,
                             TenantEntityHelper tenantEntityHelper, IndentItemMapper indentItemMapper,
                             InventoryService inventoryService) {
        this.indentItemRepository = indentItemRepository;
        this.indentRepository = indentRepository;
        this.materialRepository = materialRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.indentItemMapper = indentItemMapper;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public IndentItemDto createIndentItem(IndentItemCreationDto creationDto) {
        Indent indent = indentRepository.findByIdAndOrganization_Id(creationDto.getIndentId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + creationDto.getIndentId() + " was not found in this organization"));

        Material material = materialRepository.findByIdAndOrganization_Id(creationDto.getMaterialId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + creationDto.getMaterialId() + " was not found in this organization"));

        IndentItem indentItem = new IndentItem();
        indentItem.setIndent(indent);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(creationDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(creationDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(creationDto.getOrderedQuantity());
        indentItem.setRemarks(creationDto.getRemarks());
        indentItem.setConvertedToPurchaseOrder(false);
        indentItem.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        indentItem = indentItemRepository.save(indentItem);
        return indentItemMapper.toDto(indentItem, stockFor(List.of(indentItem)));
    }

    @Transactional(readOnly = true)
    public IndentItemDto getIndentItemById(Long id) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));
        return indentItemMapper.toDto(indentItem, stockFor(List.of(indentItem)));
    }

    /**
     * Retrieves indent items one page at a time.
     *
     * <p>Replaces an unpaginated read of the whole table. Indent items accumulate with every
     * indent a tenant raises, so the row count has no ceiling and the only safe read is a
     * bounded one.
     *
     * @param pageable The page to fetch.
     * @return A page of indent item DTOs.
     */
    @Transactional(readOnly = true)
    public Page<IndentItemDto> getAllIndentItems(Pageable pageable) {
        Page<IndentItem> items = indentItemRepository.findAll(pageable);
        MaterialStockLookup stock = stockFor(items.getContent());
        return items.map(indentItem -> indentItemMapper.toDto(indentItem, stock));
    }

    /**
     * Retrieves a page of indent items, chosen by the caller.
     *
     * <p>The counterpart to the capped listing, which answers with the first page and says so in
     * its headers but gives a caller no way to ask for the next one. Here the {@link Page} reaches
     * the response, so the total row count and the page index survive with it.
     *
     * <p>Ordered by id ascending. The capped listing asks for no sort at all, which is tolerable
     * when there is only ever one page but not here: paging over an unordered result lets rows
     * repeat on one page and vanish from another, so an explicit order is what makes the pages
     * add up.
     *
     * @param pageNo   Zero-based page index; a negative value is treated as zero.
     * @param pageSize Rows per page, clamped to {@link UnpagedResultCap#MAX_ROWS} so one request
     *                 cannot re-create the unbounded read the cap exists to prevent.
     * @return A page of indent item DTOs.
     */
    @Transactional(readOnly = true)
    public Page<IndentItemDto> getIndentItemsPaginated(int pageNo, int pageSize) {
        int page = Math.max(pageNo, 0);
        int size = Math.clamp(pageSize, 1, UnpagedResultCap.MAX_ROWS);
        return getAllIndentItems(PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByIndentId(Long indentId) {
        return toDtos(indentItemRepository.findByIndentId(indentId));
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByMaterialId(Long materialId) {
        return toDtos(indentItemRepository.findByMaterialId(materialId));
    }

    @Transactional(readOnly = true)
    public List<IndentItemDto> getIndentItemsByConversionStatus(Boolean converted) {
        return toDtos(indentItemRepository.findByConvertedToPurchaseOrder(converted));
    }

    @Transactional
    public IndentItemDto updateIndentItem(Long id, IndentItemCreationDto updateDto) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));

        Indent indent = indentRepository.findByIdAndOrganization_Id(updateDto.getIndentId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent with ID " + updateDto.getIndentId() + " was not found in this organization"));

        Material material = materialRepository.findByIdAndOrganization_Id(updateDto.getMaterialId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + updateDto.getMaterialId() + " was not found in this organization"));

        indentItem.setIndent(indent);
        indentItem.setMaterial(material);
        indentItem.setAdditionalSpecifications(updateDto.getAdditionalSpecifications());
        indentItem.setRequestedQuantity(updateDto.getRequestedQuantity());
        indentItem.setOrderedQuantity(updateDto.getOrderedQuantity());
        indentItem.setRemarks(updateDto.getRemarks());

        indentItem = indentItemRepository.save(indentItem);
        return indentItemMapper.toDto(indentItem, stockFor(List.of(indentItem)));
    }

    @Transactional
    public void deleteIndentItem(Long id) {
        if (!indentItemRepository.existsByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())) {
            throw new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization");
        }
        indentItemRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
    }

    @Transactional
    public IndentItemDto markAsConverted(Long id, String purchaseOrderNumber) {
        IndentItem indentItem = indentItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + id + " was not found in this organization"));

        indentItem.setConvertedToPurchaseOrder(true);
        indentItem.setLinkedPurchaseOrderNumber(purchaseOrderNumber);

        indentItem = indentItemRepository.save(indentItem);
        return indentItemMapper.toDto(indentItem, stockFor(List.of(indentItem)));
    }

    /**
     * Converts a list of indent lines, reading the stock for all their materials once.
     *
     * @param items The lines to convert.
     * @return The lines as DTOs.
     */
    private List<IndentItemDto> toDtos(List<IndentItem> items) {
        MaterialStockLookup stock = stockFor(items);
        return items.stream()
                .map(indentItem -> indentItemMapper.toDto(indentItem, stock))
                .toList();
    }

    /**
     * Reads the aggregate stock for the materials on the given lines, in one query.
     *
     * <p>An indent line carries a full material DTO. While the material mapper fetched its own
     * stock, every line on a page cost two aggregate reads and nothing in the listing code said so.
     *
     * @param items The lines being converted.
     * @return The stock for their materials, with anything unstocked reading as zero.
     */
    private MaterialStockLookup stockFor(Collection<IndentItem> items) {
        return inventoryService.aggregateStockFor(items.stream()
                .map(IndentItem::getMaterial)
                .filter(Objects::nonNull)
                .map(Material::getId)
                .toList());
    }
}
