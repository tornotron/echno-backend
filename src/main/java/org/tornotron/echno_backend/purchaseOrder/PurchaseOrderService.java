package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.PurchaseOrderDtoConvertor;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.indentItem.IndentItem;
import org.tornotron.echno_backend.indentItem.IndentItemRepository;
import org.tornotron.echno_backend.intend.Intend;
import org.tornotron.echno_backend.intend.IntendRepository;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.material.MaterialRepository;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderCreationDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderItemDto;
import org.tornotron.echno_backend.purchaseOrder.dto.PurchaseOrderUpdateDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItem;
import org.tornotron.echno_backend.purchaseOrderItem.PurchaseOrderItemRepository;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;
import org.tornotron.echno_backend.vendor.Vendor;
import org.tornotron.echno_backend.vendor.VendorRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final VendorRepository vendorRepository;
    private final UserRepository userRepository;
    private final IntendRepository intendRepository;
    private final IndentItemRepository indentItemRepository;
    private final MaterialRepository materialRepository;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                PurchaseOrderItemRepository purchaseOrderItemRepository,
                                VendorRepository vendorRepository,
                                UserRepository userRepository,
                                IntendRepository intendRepository,
                                IndentItemRepository indentItemRepository,
                                MaterialRepository materialRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.vendorRepository = vendorRepository;
        this.userRepository = userRepository;
        this.intendRepository = intendRepository;
        this.indentItemRepository = indentItemRepository;
        this.materialRepository = materialRepository;
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(PurchaseOrderCreationDto creationDto) {
        // Check for duplicate PO number
        if (purchaseOrderRepository.existsByPoNumber(creationDto.getPoNumber())) {
            throw new DuplicateResourceException("Purchase Order with PO number " + creationDto.getPoNumber() + " already exists");
        }

        // Validate vendor exists
        Vendor vendor = vendorRepository.findById(creationDto.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + creationDto.getVendorId()));

        // Validate user exists
        User createdBy = userRepository.findUserByName(creationDto.getCreatedBy())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with name: " + creationDto.getCreatedBy()));

        // Validate intend if provided
        Intend intend = null;
        if (creationDto.getIntendId() != null) {
            intend = intendRepository.findById(creationDto.getIntendId())
                    .orElseThrow(() -> new ResourceNotFoundException("Intend not found with id: " + creationDto.getIntendId()));
        }

        // Create purchase order
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setPoNumber(creationDto.getPoNumber());
        purchaseOrder.setVendor(vendor);
        purchaseOrder.setIntend(intend);
        purchaseOrder.setStatus(PurchaseOrderStatus.valueOf(creationDto.getStatus()));
        purchaseOrder.setCreatedBy(createdBy);
        purchaseOrder.setExpectedDeliveryDate(creationDto.getExpectedDeliveryDate());
        purchaseOrder.setRemarks(creationDto.getRemarks());
        purchaseOrder.setTotalAmount(creationDto.getTotalAmount() != null ? creationDto.getTotalAmount() : BigDecimal.ZERO);

        // Save PO first to get ID
        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);

        // Create PO items
        List<PurchaseOrderItem> items = new ArrayList<>();
        for (PurchaseOrderItemDto itemDto : creationDto.getItems()) {
            Material material = materialRepository.findById(itemDto.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material not found with id: " + itemDto.getMaterialId()));

            IndentItem indentItem = null;
            if (itemDto.getIndentItemId() != null) {
                indentItem = indentItemRepository.findById(itemDto.getIndentItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("IndentItem not found with id: " + itemDto.getIndentItemId()));

                // Update indent item to mark as converted to PO
                indentItem.setConvertedToPurchaseOrder(true);
                indentItem.setLinkedPurchaseOrderNumber(purchaseOrder.getPoNumber());
                indentItemRepository.save(indentItem);
            }

            PurchaseOrderItem poItem = new PurchaseOrderItem();
            poItem.setPurchaseOrder(purchaseOrder);
            poItem.setMaterial(material);
            poItem.setIndentItem(indentItem);
            poItem.setOrderedQuantity(itemDto.getOrderedQuantity());
            poItem.setReceivedQuantity(0);
            poItem.setUnitPrice(itemDto.getUnitPrice());
            poItem.setTotalPrice(itemDto.getTotalPrice());
            poItem.setRemarks(itemDto.getRemarks());

            items.add(poItem);
        }

        purchaseOrderItemRepository.saveAll(items);
        purchaseOrder.setItems(items);

        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrderById(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + id));
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getAllPurchaseOrders() {
        return purchaseOrderRepository.findAll().stream()
                .map(PurchaseOrderDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> getAllPurchaseOrders(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return purchaseOrderRepository.findAll(pageable)
                .map(PurchaseOrderDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByVendor(Long vendorId) {
        return purchaseOrderRepository.findByVendorId(vendorId).stream()
                .map(PurchaseOrderDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByIntend(Long intendId) {
        return purchaseOrderRepository.findByIntendId(intendId).stream()
                .map(PurchaseOrderDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> getPurchaseOrdersByStatus(PurchaseOrderStatus status) {
        return purchaseOrderRepository.findByStatus(status).stream()
                .map(PurchaseOrderDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public PurchaseOrderDto updatePurchaseOrder(PurchaseOrderUpdateDto updateDto) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(updateDto.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + updateDto.getId()));

        if (updateDto.getStatus() != null) {
            purchaseOrder.setStatus(PurchaseOrderStatus.valueOf(updateDto.getStatus()));
        }

        if (updateDto.getExpectedDeliveryDate() != null) {
            purchaseOrder.setExpectedDeliveryDate(updateDto.getExpectedDeliveryDate());
        }

        if (updateDto.getRemarks() != null) {
            purchaseOrder.setRemarks(updateDto.getRemarks());
        }

        if (updateDto.getTotalAmount() != null) {
            purchaseOrder.setTotalAmount(updateDto.getTotalAmount());
        }

        purchaseOrder = purchaseOrderRepository.save(purchaseOrder);
        return PurchaseOrderDtoConvertor.convertToDto(purchaseOrder);
    }

    @Transactional
    public void updatePurchaseOrderStatus(Long id, PurchaseOrderStatus status) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found with id: " + id));

        purchaseOrder.setStatus(status);
        purchaseOrderRepository.save(purchaseOrder);
    }
}
