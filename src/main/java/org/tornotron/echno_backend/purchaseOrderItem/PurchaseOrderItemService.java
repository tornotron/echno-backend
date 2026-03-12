package org.tornotron.echno_backend.purchaseOrderItem;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrder;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderRepository;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemCreationDto;
import org.tornotron.echno_backend.purchaseOrderItem.dto.PurchaseOrderItemResponseDto;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderItemService {

    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final MaterialRepository materialRepository;
    private final IndentItemRepository indentItemRepository;
    private final TenantEntityHelper tenantEntityHelper;

    public PurchaseOrderItemService(PurchaseOrderItemRepository purchaseOrderItemRepository,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    MaterialRepository materialRepository,
                                    IndentItemRepository indentItemRepository,
                                    TenantEntityHelper tenantEntityHelper) {
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.materialRepository = materialRepository;
        this.indentItemRepository = indentItemRepository;
        this.tenantEntityHelper = tenantEntityHelper;
    }

    @Transactional
    public PurchaseOrderItemResponseDto createPurchaseOrderItem(PurchaseOrderItemCreationDto creationDto) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(creationDto.getPurchaseOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + creationDto.getPurchaseOrderId()));

        Material material = materialRepository.findById(creationDto.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + creationDto.getMaterialId()));

        IndentItem indentItem = null;
        if (creationDto.getIndentItemId() != null) {
            indentItem = indentItemRepository.findById(creationDto.getIndentItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + creationDto.getIndentItemId()));

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
        poItem.setTotalPrice(creationDto.getTotalPrice());
        poItem.setRemarks(creationDto.getRemarks());
        poItem.setOrganization(tenantEntityHelper.resolveCurrentOrganization());

        poItem = purchaseOrderItemRepository.save(poItem);
        return convertToResponseDto(poItem);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderItemResponseDto getPurchaseOrderItemById(Long id) {
        PurchaseOrderItem item = purchaseOrderItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrderItem not found with id: " + id));
        return convertToResponseDto(item);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderItemResponseDto> getAllPurchaseOrderItems() {
        return purchaseOrderItemRepository.findAll().stream()
                .map(this::convertToResponseDto)
                .collect(Collectors.toList());
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
    public void deletePurchaseOrderItem(Long id) {
        if (!purchaseOrderItemRepository.existsById(id)) {
            throw new ResourceNotFoundException("PurchaseOrderItem not found with id: " + id);
        }
        purchaseOrderItemRepository.deleteById(id);
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
