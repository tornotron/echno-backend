package org.tornotron.echno_backend.purchaseOrderItem;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemUpdateDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderItemService {

    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final MaterialRepository materialRepository;
    private final IndentItemRepository indentItemRepository;
    private final TenantEntityHelper tenantEntityHelper;
    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderItemService(PurchaseOrderItemRepository purchaseOrderItemRepository,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    MaterialRepository materialRepository,
                                    IndentItemRepository indentItemRepository,
                                    TenantEntityHelper tenantEntityHelper,
                                    PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.materialRepository = materialRepository;
        this.indentItemRepository = indentItemRepository;
        this.tenantEntityHelper = tenantEntityHelper;
        this.purchaseOrderService = purchaseOrderService;
    }

    @Transactional
    public PurchaseOrderItemResponseDto createPurchaseOrderItem(PurchaseOrderItemCreationDto creationDto) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndOrganization_Id(creationDto.getPurchaseOrderId(), TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order with ID " + creationDto.getPurchaseOrderId() + " was not found in this organization"));

        Material material = materialRepository.findByIdAndOrganization_Id(creationDto.getMaterialId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Material with ID " + creationDto.getMaterialId() + " was not found in this organization"));

        IndentItem indentItem = null;
        if (creationDto.getIndentItemId() != null) {
            indentItem = indentItemRepository.findByIdAndOrganization_Id(creationDto.getIndentItemId(),TenantContext.getCurrentOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Indent item with ID " + creationDto.getIndentItemId() + " was not found in this organization"));

            indentItem.setConvertedToPurchaseOrder(true);
            indentItem.setLinkedPurchaseOrderNumber(purchaseOrder.getPoNumber());
            indentItemRepository.save(indentItem);
        }

        PurchaseOrderItem poItem = new PurchaseOrderItem();
        poItem.setPurchaseOrder(purchaseOrder);
        poItem.setMaterial(material);
        poItem.setIndentItem(indentItem);
        poItem.setOrderedQuantity(creationDto.getOrderedQuantity());
        poItem.setReceivedQuantity(0);
        poItem.setUnitPrice(creationDto.getUnitPrice());
        
        // Calculate total price
        BigDecimal unitPrice = creationDto.getUnitPrice() != null ? creationDto.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(creationDto.getOrderedQuantity()));
        poItem.setTotalPrice(totalPrice);
        
        poItem.setRemarks(creationDto.getRemarks());
        poItem.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        poItem = purchaseOrderItemRepository.save(poItem);
        
        // Recalculate PurchaseOrder total amount
        purchaseOrderService.recalculateTotalAmount(purchaseOrder.getId());
        
        return convertToResponseDto(poItem);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderItemResponseDto getPurchaseOrderItemById(Long id) {
        PurchaseOrderItem item = purchaseOrderItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order item with ID " + id + " was not found in this organization"));
        return convertToResponseDto(item);
    }

    /**
     * Retrieves purchase order items one page at a time.
     *
     * <p>Replaces an unpaginated read of the whole table. Line items accumulate with every
     * purchase order a tenant raises, so the row count has no ceiling and the only safe read is
     * a bounded one.
     *
     * @param pageable The page to fetch.
     * @return A page of purchase order item DTOs.
     */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderItemResponseDto> getAllPurchaseOrderItems(Pageable pageable) {
        return purchaseOrderItemRepository.findAll(pageable)
                .map(this::convertToResponseDto);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderItemResponseDto> getItemsByPurchaseOrderId(Long purchaseOrderId) {
        return purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrderId).stream()
                .map(this::convertToResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderItemResponseDto> getItemsByMaterialId(Long materialId) {
        return purchaseOrderItemRepository.findByMaterialId(materialId).stream()
                .map(this::convertToResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public PurchaseOrderItemResponseDto updatePurchaseOrderItem(PurchaseOrderItemUpdateDto updateDto) {
        PurchaseOrderItem item = purchaseOrderItemRepository.findByIdAndOrganization_Id(updateDto.getId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order item with ID " + updateDto.getId() + " was not found in this organization"));

        if (updateDto.getOrderedQuantity() != null) {
            item.setOrderedQuantity(updateDto.getOrderedQuantity());
        }

        if (updateDto.getUnitPrice() != null) {
            item.setUnitPrice(updateDto.getUnitPrice());
        }

        if (updateDto.getRemarks() != null) {
            item.setRemarks(updateDto.getRemarks());
        }

        // Recalculate item total price
        BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal totalPrice = unitPrice.multiply(new BigDecimal(item.getOrderedQuantity()));
        item.setTotalPrice(totalPrice);

        item = purchaseOrderItemRepository.save(item);

        // Recalculate PurchaseOrder total amount
        purchaseOrderService.recalculateTotalAmount(item.getPurchaseOrder().getId());

        return convertToResponseDto(item);
    }

    @Transactional
    public void deletePurchaseOrderItem(Long id) {
        PurchaseOrderItem item = purchaseOrderItemRepository.findByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order item with ID " + id + " was not found in this organization"));
        
        Long purchaseOrderId = item.getPurchaseOrder().getId();
        purchaseOrderItemRepository.deleteByIdAndOrganization_Id(id,TenantContext.getCurrentOrgId());
        
        // Recalculate PurchaseOrder total amount after deletion
        purchaseOrderService.recalculateTotalAmount(purchaseOrderId);
    }

    private PurchaseOrderItemResponseDto convertToResponseDto(PurchaseOrderItem item) {
        PurchaseOrderItemResponseDto dto = new PurchaseOrderItemResponseDto();
        dto.setId(item.getId());
        dto.setOrderedQuantity(item.getOrderedQuantity());
        dto.setReceivedQuantity(item.getReceivedQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setTotalPrice(item.getTotalPrice());
        dto.setRemarks(item.getRemarks());

        if (item.getPurchaseOrder() != null) {
            dto.setPurchaseOrderId(item.getPurchaseOrder().getId());
            dto.setPoNumber(item.getPurchaseOrder().getPoNumber());
        }

        if (item.getMaterial() != null) {
            dto.setMaterialId(item.getMaterial().getId());
            dto.setMaterialName(item.getMaterial().getMaterialName());
        }

        if (item.getIndentItem() != null) {
            dto.setIndentItemId(item.getIndentItem().getId());
        }

        return dto;
    }
}
